@file:OptIn(KaExperimentalApi::class)

package dev.telaio.intellij.k2

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.telaio.core.symbol.*
import dev.telaio.intellij.bridge.TransactionSymbolRegistry
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.*
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.*
import java.io.File

class JetBrainsSymbolResolver(
    private val project: Project,
    private val registry: TransactionSymbolRegistry = TransactionSymbolRegistry()
) : SemanticSymbolResolver {

    private val logger = Logger.getInstance(JetBrainsSymbolResolver::class.java)

    override fun resolveSymbol(request: ResolveSymbolRequest): ResolveSymbolResult {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (application != null && application.isDispatchThread) {
            return application.executeOnPooledThread<ResolveSymbolResult> {
                doResolveSymbol(request)
            }.get()
        }
        return doResolveSymbol(request)
    }

    private fun doResolveSymbol(request: ResolveSymbolRequest): ResolveSymbolResult {
        val startTime = System.currentTimeMillis()
        var sourceLocation: SourceLocation? = null

        try {
            // Check 1: Dumb/indexing mode check (SPEC-0001 BR-5, AT-5)
            if (DumbService.isDumb(project)) {
                val duration = System.currentTimeMillis() - startTime
                return ResolveSymbolResult(
                    outcome = ResolveOutcome.TemporarilyUnavailable(
                        reason = UnavailabilityReason.INDEXING,
                        message = "IntelliJ is currently indexing. Semantic resolution is temporarily unavailable."
                    ),
                    provenance = ResolveProvenance(
                        traceId = request.traceId,
                        transactionId = request.transactionId,
                        toolCallId = request.toolCallId,
                        sourceFile = request.file,
                        sourceLocation = null,
                        outcomeStatus = "TEMPORARILY_UNAVAILABLE",
                        backend = "jetbrains-k2",
                        capabilityLevel = CapabilityLevel.NATIVE,
                        resolvedHandle = null,
                        durationMs = duration
                    )
                )
            }

            // Check 2: Find file and target offset inside ReadAction
            return ReadAction.compute<ResolveSymbolResult, Throwable> {
                val psiFile = findPsiFile(request.file)
                if (psiFile == null) {
                    val duration = System.currentTimeMillis() - startTime
                    return@compute ResolveSymbolResult(
                        outcome = ResolveOutcome.InvalidLocation(
                            reason = "File not found: ${request.file}",
                            file = request.file,
                            offset = request.offset,
                            line = request.line,
                            column = request.column
                        ),
                        provenance = ResolveProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            sourceFile = request.file,
                            sourceLocation = null,
                            outcomeStatus = "INVALID_LOCATION",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.NATIVE,
                            resolvedHandle = null,
                            durationMs = duration
                        )
                    )
                }

                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                val targetOffset = calculateOffset(document, request)
                if (targetOffset < 0 || (document != null && targetOffset > document.textLength)) {
                    val duration = System.currentTimeMillis() - startTime
                    return@compute ResolveSymbolResult(
                        outcome = ResolveOutcome.InvalidLocation(
                            reason = "Target offset $targetOffset out of bounds for file ${request.file}",
                            file = request.file,
                            offset = targetOffset,
                            line = request.line,
                            column = request.column
                        ),
                        provenance = ResolveProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            sourceFile = request.file,
                            sourceLocation = null,
                            outcomeStatus = "INVALID_LOCATION",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.NATIVE,
                            resolvedHandle = null,
                            durationMs = duration
                        )
                    )
                }

                if (psiFile !is KtFile) {
                    val duration = System.currentTimeMillis() - startTime
                    return@compute ResolveSymbolResult(
                        outcome = ResolveOutcome.UnsupportedSymbol(
                            reason = "Non-Kotlin file: ${psiFile.fileType.name}",
                            rawName = psiFile.name
                        ),
                        provenance = ResolveProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            sourceFile = request.file,
                            sourceLocation = null,
                            outcomeStatus = "UNSUPPORTED_SYMBOL",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.UNSUPPORTED,
                            resolvedHandle = null,
                            durationMs = duration
                        )
                    )
                }

                val psiElement = psiFile.findElementAt(targetOffset)
                if (psiElement == null) {
                    val duration = System.currentTimeMillis() - startTime
                    return@compute ResolveSymbolResult(
                        outcome = ResolveOutcome.NotFound("No element found at offset $targetOffset"),
                        provenance = ResolveProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            sourceFile = request.file,
                            sourceLocation = null,
                            outcomeStatus = "NOT_FOUND",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.NATIVE,
                            resolvedHandle = null,
                            durationMs = duration
                        )
                    )
                }

                // Analyze using K2 Analysis API
                resolveWithK2(request, psiFile, psiElement, startTime)
            }
        } catch (e: Throwable) {
            logger.error("Error during semantic symbol resolution", e)
            val duration = System.currentTimeMillis() - startTime
            return ResolveSymbolResult(
                outcome = ResolveOutcome.BackendError(
                    message = e.message ?: "Unknown backend error during symbol resolution",
                    details = e.stackTraceToString()
                ),
                provenance = ResolveProvenance(
                    traceId = request.traceId,
                    transactionId = request.transactionId,
                    toolCallId = request.toolCallId,
                    sourceFile = request.file,
                    sourceLocation = sourceLocation,
                    outcomeStatus = "BACKEND_ERROR",
                    backend = "jetbrains-k2",
                    capabilityLevel = CapabilityLevel.NATIVE,
                    resolvedHandle = null,
                    durationMs = duration
                )
            )
        }
    }

    private fun findPsiFile(filePath: String): PsiFile? {
        val virtualFile = when {
            filePath.startsWith("temp://") || filePath.startsWith("file://") || filePath.startsWith("jar://") -> {
                VirtualFileManager.getInstance().findFileByUrl(filePath)
            }
            else -> {
                LocalFileSystem.getInstance().findFileByPath(filePath)
                    ?: VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
                    ?: VirtualFileManager.getInstance().findFileByUrl("temp://$filePath")
                    ?: VirtualFileManager.getInstance().findFileByUrl("temp:///$filePath")
                    ?: VirtualFileManager.getInstance().findFileByUrl(filePath)
                    ?: LocalFileSystem.getInstance().findFileByIoFile(File(filePath))
            }
        } ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    private fun calculateOffset(document: com.intellij.openapi.editor.Document?, request: ResolveSymbolRequest): Int {
        val reqOffset = request.offset
        if (reqOffset != null) return reqOffset

        val reqLine = request.line
        val reqCol = request.column
        if (document != null && reqLine != null && reqCol != null) {
            val zeroBasedLine = (reqLine - 1).coerceAtLeast(0)
            if (zeroBasedLine < document.lineCount) {
                val lineStart = document.getLineStartOffset(zeroBasedLine)
                val lineEnd = document.getLineEndOffset(zeroBasedLine)
                val zeroBasedCol = (reqCol - 1).coerceAtLeast(0)
                return (lineStart + zeroBasedCol).coerceAtMost(lineEnd)
            }
        }
        return -1
    }

    private fun resolveWithK2(
        request: ResolveSymbolRequest,
        ktFile: KtFile,
        psiElement: PsiElement,
        startTime: Long
    ): ResolveSymbolResult {
        // Check if element is part of a comment or whitespace
        if (psiElement is com.intellij.psi.PsiWhiteSpace || psiElement is com.intellij.psi.PsiComment) {
            val duration = System.currentTimeMillis() - startTime
            return ResolveSymbolResult(
                outcome = ResolveOutcome.NotFound("Position is on whitespace or comment"),
                provenance = ResolveProvenance(
                    traceId = request.traceId,
                    transactionId = request.transactionId,
                    toolCallId = request.toolCallId,
                    sourceFile = request.file,
                    sourceLocation = null,
                    outcomeStatus = "NOT_FOUND",
                    backend = "jetbrains-k2",
                    capabilityLevel = CapabilityLevel.NATIVE,
                    resolvedHandle = null,
                    durationMs = duration
                )
            )
        }

        val enclosingKtElement = PsiTreeUtil.getParentOfType(psiElement, KtElement::class.java, false)
        if (enclosingKtElement == null) {
            val duration = System.currentTimeMillis() - startTime
            return ResolveSymbolResult(
                outcome = ResolveOutcome.NotFound("No enclosing Kotlin AST element found"),
                provenance = ResolveProvenance(
                    traceId = request.traceId,
                    transactionId = request.transactionId,
                    toolCallId = request.toolCallId,
                    sourceFile = request.file,
                    sourceLocation = null,
                    outcomeStatus = "NOT_FOUND",
                    backend = "jetbrains-k2",
                    capabilityLevel = CapabilityLevel.NATIVE,
                    resolvedHandle = null,
                    durationMs = duration
                )
            )
        }

        return analyze(enclosingKtElement) {
            val targetDeclaration = findTargetDeclaration(psiElement, enclosingKtElement)
            if (targetDeclaration != null) {
                // Resolution from Declaration position (BR-1, AT-1, AT-3)
                val symbol = targetDeclaration.symbol
                return@analyze createResolvedResult(request, symbol, targetDeclaration, startTime)
            }

            // Resolution from Reference / Expression position (BR-1, AT-2, AT-4)
            val referenceTarget = findReferenceTarget(psiElement, enclosingKtElement)
            if (referenceTarget != null) {
                val symbols = referenceTarget.mainReference?.resolveToSymbols() ?: emptyList()
                if (symbols.isEmpty()) {
                    val single = referenceTarget.mainReference?.resolveToSymbol()
                    if (single != null) {
                        val declPsi = single.psi ?: referenceTarget
                        return@analyze createResolvedResult(request, single, declPsi, startTime)
                    }
                } else if (symbols.size == 1) {
                    val single = symbols.first()
                    val declPsi = single.psi ?: referenceTarget
                    return@analyze createResolvedResult(request, single, declPsi, startTime)
                } else {
                    // Ambiguous reference (SPEC-0001 Section 6)
                    val candidates = symbols.mapNotNull { sym ->
                        val declPsi = sym.psi ?: referenceTarget
                        createSymbolRef(request.transactionId, sym, declPsi)
                    }
                    val duration = System.currentTimeMillis() - startTime
                    return@analyze ResolveSymbolResult(
                        outcome = ResolveOutcome.Ambiguous(
                            candidates = candidates,
                            message = "Multiple candidate symbols resolved (${symbols.size} candidates)"
                        ),
                        provenance = ResolveProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            sourceFile = request.file,
                            sourceLocation = null,
                            outcomeStatus = "AMBIGUOUS",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.NATIVE,
                            resolvedHandle = null,
                            durationMs = duration
                        )
                    )
                }
            }

            val duration = System.currentTimeMillis() - startTime
            ResolveSymbolResult(
                outcome = ResolveOutcome.NotFound("No resolvable declaration or reference found at position"),
                provenance = ResolveProvenance(
                    traceId = request.traceId,
                    transactionId = request.transactionId,
                    toolCallId = request.toolCallId,
                    sourceFile = request.file,
                    sourceLocation = null,
                    outcomeStatus = "NOT_FOUND",
                    backend = "jetbrains-k2",
                    capabilityLevel = CapabilityLevel.NATIVE,
                    resolvedHandle = null,
                    durationMs = duration
                )
            )
        }
    }

    private fun findTargetDeclaration(psiElement: PsiElement, enclosingKtElement: KtElement): KtDeclaration? {
        val declaration = PsiTreeUtil.getParentOfType(psiElement, KtDeclaration::class.java, false)
        if (declaration != null) {
            val namedDecl = declaration as? KtNamedDeclaration
            if (namedDecl != null && (namedDecl.nameIdentifier == psiElement || namedDecl.nameIdentifier?.textRange?.contains(psiElement.textRange) == true)) {
                return declaration
            }
            if (declaration is KtConstructor<*> && declaration.textRange.contains(psiElement.textRange)) {
                return declaration
            }
            if (namedDecl != null && namedDecl.textRange.startOffset <= psiElement.textOffset && psiElement.textOffset <= (namedDecl.nameIdentifier?.textRange?.endOffset ?: namedDecl.textRange.startOffset)) {
                return declaration
            }
        }
        return null
    }

    private fun findReferenceTarget(psiElement: PsiElement, enclosingKtElement: KtElement): KtElement? {
        val simpleName = PsiTreeUtil.getParentOfType(psiElement, KtSimpleNameExpression::class.java, false)
        if (simpleName != null) return simpleName

        val refExpr = PsiTreeUtil.getParentOfType(psiElement, KtReferenceExpression::class.java, false)
        if (refExpr != null) return refExpr

        val callExpr = PsiTreeUtil.getParentOfType(psiElement, KtCallExpression::class.java, false)
        if (callExpr != null) return callExpr.calleeExpression ?: callExpr

        return enclosingKtElement
    }

    private fun KaSession.createResolvedResult(
        request: ResolveSymbolRequest,
        symbol: KaSymbol,
        psi: PsiElement,
        startTime: Long
    ): ResolveSymbolResult {
        val symbolRefEntry = registry.register(project, request.transactionId, psi) { handle ->
            buildSymbolRef(handle, symbol, psi)
        }
        val symbolRef = symbolRefEntry.symbolRef
        val duration = System.currentTimeMillis() - startTime

        return ResolveSymbolResult(
            outcome = ResolveOutcome.Resolved(symbolRef),
            provenance = ResolveProvenance(
                traceId = request.traceId,
                transactionId = request.transactionId,
                toolCallId = request.toolCallId,
                sourceFile = request.file,
                sourceLocation = symbolRef.sourceLocation,
                outcomeStatus = "RESOLVED",
                backend = "jetbrains-k2",
                capabilityLevel = CapabilityLevel.NATIVE,
                resolvedHandle = symbolRef.handle,
                durationMs = duration
            )
        )
    }

    private fun KaSession.createSymbolRef(
        transactionId: String,
        symbol: KaSymbol,
        psi: PsiElement
    ): SymbolRef {
        val id = Integer.toHexString(System.identityHashCode(psi) xor symbol.hashCode())
        val handle = SymbolHandle.create(transactionId, id)
        return buildSymbolRef(handle, symbol, psi)
    }

    private fun KaSession.buildSymbolRef(
        handle: SymbolHandle,
        symbol: KaSymbol,
        psi: PsiElement
    ): SymbolRef {
        val name = when (symbol) {
            is KaDeclarationSymbol -> symbol.name?.asString() ?: psi.containingFile.name
            else -> psi.text ?: "unknown"
        }
        val kind = determineSymbolKind(symbol, psi)
        val qualifiedIdentity = buildQualifiedIdentity(symbol, psi)
        val visibility = determineVisibility(symbol)
        val module = ModuleUtilCore.findModuleForPsiElement(psi)?.name
        val location = extractSourceLocation(psi)
        val docComment = extractDocComment(psi)

        return SymbolRef(
            handle = handle,
            name = name,
            kind = kind,
            qualifiedIdentity = qualifiedIdentity,
            visibility = visibility,
            module = module,
            sourceLocation = location,
            docComment = docComment
        )
    }

    private fun KaSession.determineSymbolKind(symbol: KaSymbol, psi: PsiElement): SymbolKind {
        return when (symbol) {
            is KaNamedClassSymbol -> when (symbol.classKind) {
                KaClassKind.CLASS -> SymbolKind.CLASS
                KaClassKind.INTERFACE -> SymbolKind.INTERFACE
                KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT -> SymbolKind.OBJECT
                KaClassKind.ENUM_CLASS -> SymbolKind.ENUM_CLASS
                KaClassKind.ANNOTATION_CLASS -> SymbolKind.CLASS
                else -> SymbolKind.CLASS
            }
            is KaAnonymousObjectSymbol -> SymbolKind.OBJECT
            is KaConstructorSymbol -> SymbolKind.CONSTRUCTOR
            is KaNamedFunctionSymbol, is KaAnonymousFunctionSymbol -> {
                if (symbol.containingDeclaration is KaClassSymbol) {
                    SymbolKind.METHOD
                } else {
                    SymbolKind.FUNCTION
                }
            }
            is KaPropertySymbol -> SymbolKind.PROPERTY
            is KaJavaFieldSymbol -> SymbolKind.FIELD
            is KaLocalVariableSymbol -> SymbolKind.LOCAL_VARIABLE
            is KaValueParameterSymbol -> SymbolKind.PARAMETER
            is KaTypeAliasSymbol -> SymbolKind.TYPE_ALIAS
            is KaPackageSymbol -> SymbolKind.PACKAGE
            is KaEnumEntrySymbol -> SymbolKind.ENUM_ENTRY
            else -> when (psi) {
                is KtClass -> if (psi.isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
                is KtNamedFunction -> SymbolKind.FUNCTION
                is KtProperty -> SymbolKind.PROPERTY
                is KtParameter -> SymbolKind.PARAMETER
                is KtDestructuringDeclarationEntry -> SymbolKind.LOCAL_VARIABLE
                else -> SymbolKind.UNKNOWN
            }
        }
    }

    private fun KaSession.buildQualifiedIdentity(symbol: KaSymbol, psi: PsiElement): String {
        return when (symbol) {
            is KaNamedFunctionSymbol -> {
                val containingFqName = symbol.callableId?.classId?.asFqNameString()
                    ?: symbol.callableId?.packageName?.asString()
                    ?: (psi as? KtNamedDeclaration)?.fqName?.parent()?.asString()
                    ?: ""
                val prefix = if (containingFqName.isNotBlank()) "$containingFqName." else ""
                val receiver = symbol.receiverType?.let { renderType(it) + "." } ?: ""
                val params = symbol.valueParameters.joinToString(", ") { param ->
                    renderType(param.returnType)
                }
                val returnType = renderType(symbol.returnType)
                "$prefix$receiver${symbol.name.asString()}($params): $returnType"
            }
            is KaConstructorSymbol -> {
                val containingClass = (symbol.containingDeclaration as? KaClassSymbol)?.classId?.asFqNameString() ?: ""
                val params = symbol.valueParameters.joinToString(", ") { param ->
                    renderType(param.returnType)
                }
                "$containingClass.<init>($params)"
            }
            is KaPropertySymbol -> {
                val containingFqName = symbol.callableId?.classId?.asFqNameString()
                    ?: symbol.callableId?.packageName?.asString()
                    ?: ""
                val prefix = if (containingFqName.isNotBlank()) "$containingFqName." else ""
                val receiver = symbol.receiverType?.let { renderType(it) + "." } ?: ""
                val returnType = renderType(symbol.returnType)
                "$prefix$receiver${symbol.name.asString()}: $returnType"
            }
            is KaClassSymbol -> {
                symbol.classId?.asFqNameString() ?: (psi as? KtClassOrObject)?.fqName?.asString() ?: symbol.name?.asString() ?: "unknown"
            }
            is KaTypeAliasSymbol -> {
                symbol.classId?.asFqNameString() ?: symbol.name.asString()
            }
            is KaEnumEntrySymbol -> {
                val containingClass = (symbol.containingDeclaration as? KaClassSymbol)?.classId?.asFqNameString() ?: ""
                "$containingClass.${symbol.name.asString()}"
            }
            is KaValueParameterSymbol -> {
                val name = symbol.name.asString()
                val type = renderType(symbol.returnType)
                val owner = (psi as? KtParameter)?.ownerFunction?.let { (it as? KtNamedDeclaration)?.fqName?.asString() } ?: ""
                if (owner.isNotBlank()) "$owner#$name: $type" else "$name: $type"
            }
            is KaLocalVariableSymbol -> {
                val name = symbol.name.asString()
                val type = renderType(symbol.returnType)
                "local#$name: $type"
            }
            else -> {
                (psi as? KtNamedDeclaration)?.fqName?.asString() ?: psi.text ?: "unknown"
            }
        }
    }

    private fun KaSession.renderType(type: KaType): String {
        return type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, position = org.jetbrains.kotlin.types.Variance.INVARIANT)
    }

    private fun KaSession.determineVisibility(symbol: KaSymbol): SymbolVisibility {
        if (symbol !is KaDeclarationSymbol) return SymbolVisibility.UNKNOWN
        val vis = symbol.visibility
        val name = vis.name.lowercase()
        return when {
            name.contains("public") -> SymbolVisibility.PUBLIC
            name.contains("protected") -> SymbolVisibility.PROTECTED
            name.contains("internal") -> SymbolVisibility.INTERNAL
            name.contains("private") -> SymbolVisibility.PRIVATE
            name.contains("local") -> SymbolVisibility.LOCAL
            else -> SymbolVisibility.UNKNOWN
        }
    }

    private fun extractSourceLocation(psi: PsiElement): SourceLocation {
        val containingFile = psi.containingFile
        val virtualFile = containingFile?.virtualFile
        val path = virtualFile?.path ?: containingFile?.name ?: "unknown"
        val doc = containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val textRange = psi.textRange

        val (line, column) = if (doc != null && textRange != null && textRange.startOffset <= doc.textLength) {
            val lineIndex = doc.getLineNumber(textRange.startOffset)
            val lineStart = doc.getLineStartOffset(lineIndex)
            val colIndex = textRange.startOffset - lineStart
            Pair(lineIndex + 1, colIndex + 1)
        } else {
            Pair(1, 1)
        }

        return SourceLocation(
            file = path,
            line = line,
            column = column,
            offset = textRange?.startOffset,
            length = textRange?.length
        )
    }

    private fun extractDocComment(psi: PsiElement): String? {
        if (psi is KtDeclaration) {
            return psi.docComment?.text
        }
        return null
    }
}
