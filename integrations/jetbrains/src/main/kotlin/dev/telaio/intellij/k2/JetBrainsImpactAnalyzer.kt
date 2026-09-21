@file:OptIn(KaExperimentalApi::class)

package dev.telaio.intellij.k2

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.telaio.core.impact.*
import dev.telaio.core.symbol.*
import dev.telaio.intellij.bridge.TransactionSymbolRegistry
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.util.concurrent.CancellationException

class JetBrainsImpactAnalyzer(
    private val project: Project,
    private val registry: TransactionSymbolRegistry
) : SemanticImpactAnalyzer {

    private val logger = Logger.getInstance(JetBrainsImpactAnalyzer::class.java)

    override fun analyzeImpact(request: ImpactAnalysisRequest): ImpactAnalysisResult {
        val application = ApplicationManager.getApplication()
        if (application != null && application.isDispatchThread) {
            return application.executeOnPooledThread<ImpactAnalysisResult> {
                doAnalyzeImpact(request)
            }.get()
        }
        return doAnalyzeImpact(request)
    }

    private fun doAnalyzeImpact(request: ImpactAnalysisRequest): ImpactAnalysisResult {
        val startTime = System.currentTimeMillis()

        try {
            // Check 1: Dumb/indexing mode check (BR-9, AT-8)
            if (DumbService.isDumb(project)) {
                val duration = System.currentTimeMillis() - startTime
                return ImpactAnalysisResult(
                    outcome = ImpactOutcome.TemporarilyUnavailable(
                        reason = UnavailabilityReason.INDEXING,
                        message = "IntelliJ is currently indexing. Impact analysis is temporarily unavailable."
                    ),
                    provenance = ImpactProvenance(
                        traceId = request.traceId,
                        transactionId = request.transactionId,
                        toolCallId = request.toolCallId,
                        targetSymbolHandle = request.symbolHandle,
                        outcomeStatus = "TEMPORARILY_UNAVAILABLE",
                        backend = "jetbrains-k2",
                        capabilityLevel = CapabilityLevel.NATIVE,
                        durationMs = duration
                    )
                )
            }

            // Check 2: Transaction handle validation (BR-10, AT-9)
            val entry = registry.get(request.symbolHandle)
            if (entry == null || entry.handle.transactionId != request.transactionId) {
                val duration = System.currentTimeMillis() - startTime
                return ImpactAnalysisResult(
                    outcome = ImpactOutcome.StaleSymbolHandle(
                        handle = request.symbolHandle,
                        message = "Symbol handle '${request.symbolHandle.value}' is invalid, expired, or belongs to another transaction"
                    ),
                    provenance = ImpactProvenance(
                        traceId = request.traceId,
                        transactionId = request.transactionId,
                        toolCallId = request.toolCallId,
                        targetSymbolHandle = request.symbolHandle,
                        outcomeStatus = "STALE_SYMBOL_HANDLE",
                        backend = "jetbrains-k2",
                        capabilityLevel = CapabilityLevel.NATIVE,
                        durationMs = duration
                    )
                )
            }

            return ReadAction.compute<ImpactAnalysisResult, Throwable> {
                val targetElement = entry.psiPointer.element
                if (targetElement == null || !targetElement.isValid) {
                    val duration = System.currentTimeMillis() - startTime
                    return@compute ImpactAnalysisResult(
                        outcome = ImpactOutcome.StaleSymbolHandle(
                            handle = request.symbolHandle,
                            message = "Underlying PSI element for '${request.symbolHandle.value}' has been invalidated or deleted"
                        ),
                        provenance = ImpactProvenance(
                            traceId = request.traceId,
                            transactionId = request.transactionId,
                            toolCallId = request.toolCallId,
                            targetSymbolHandle = request.symbolHandle,
                            outcomeStatus = "STALE_SYMBOL_HANDLE",
                            backend = "jetbrains-k2",
                            capabilityLevel = CapabilityLevel.NATIVE,
                            durationMs = duration
                        )
                    )
                }

                executeSearch(request, entry.symbolRef, targetElement, startTime)
            }
        } catch (e: Throwable) {
            if (e is ProcessCanceledException || e is CancellationException) {
                throw e
            }
            logger.error("Error during semantic impact analysis", e)
            val duration = System.currentTimeMillis() - startTime
            return ImpactAnalysisResult(
                outcome = ImpactOutcome.BackendError(
                    message = e.message ?: "Unknown backend error during impact analysis",
                    details = e.stackTraceToString()
                ),
                provenance = ImpactProvenance(
                    traceId = request.traceId,
                    transactionId = request.transactionId,
                    toolCallId = request.toolCallId,
                    targetSymbolHandle = request.symbolHandle,
                    outcomeStatus = "BACKEND_ERROR",
                    backend = "jetbrains-k2",
                    capabilityLevel = CapabilityLevel.NATIVE,
                    durationMs = duration
                )
            )
        }
    }

    private fun executeSearch(
        request: ImpactAnalysisRequest,
        targetSymbol: SymbolRef,
        targetElement: PsiElement,
        startTime: Long
    ): ImpactAnalysisResult {
        val searchScope = resolveSearchScope(request.scope, targetElement)
        val semanticRelationships = mutableListOf<DiscoveredSemanticRelationship>()
        val heuristicCandidates = mutableListOf<DiscoveredHeuristicCandidate>()
        val limitations = mutableListOf<AnalysisLimitation>()

        // 1. Direct references search (BR-5, AT-1, AT-2, AT-3, AT-5)
        val references = ReferencesSearch.search(targetElement, searchScope).findAll()
        val targetTextRange = targetElement.textRange

        for (ref in references) {
            val refElement = ref.element
            // Ignore self-references inside declaration
            if (refElement.containingFile == targetElement.containingFile && targetTextRange != null && targetTextRange.contains(refElement.textRange)) {
                continue
            }

            val relationKind = classifyRelationKind(refElement, targetElement)
            val location = extractSourceLocation(refElement)
            val enclosingFqn = findEnclosingDeclarationFqn(refElement)
            val snippet = extractSnippet(refElement)

            semanticRelationships.add(
                DiscoveredSemanticRelationship(
                    relationKind = relationKind,
                    certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                    sourceLocation = location,
                    enclosingDeclarationFqName = enclosingFqn,
                    snippet = snippet
                )
            )
        }

        // 2. Hierarchy search: overrides, implementations & base declarations (BR-6, AT-4)
        discoverHierarchyRelationships(targetElement, searchScope, semanticRelationships)

        // 3. Heuristic string literal search (BR-4, AT-10)
        if (request.includeCandidates && targetSymbol.name.isNotBlank()) {
            discoverHeuristicCandidates(targetSymbol.name, targetElement, searchScope, heuristicCandidates)
        }

        // 4. Dynamic Risk & Limitations assessment (BR-3, AT-6, AT-7)
        val isPublicApi = targetSymbol.visibility == SymbolVisibility.PUBLIC
        val externalConsumerCoverage = if (isPublicApi) {
            limitations.add(
                AnalysisLimitation(
                    code = LimitationCode.EXTERNAL_CONSUMERS_UNVERIFIABLE,
                    description = "Public API declaration may have external consumers outside current project workspace",
                    affectedScope = null
                )
            )
            ImpactCompleteness.UNKNOWN
        } else {
            ImpactCompleteness.COMPLETE
        }

        val dynamicRisk = DynamicRiskAssessment(
            reflectionRisk = false,
            stringLiteralCandidatesFound = heuristicCandidates.size,
            frameworkAnnotationWiringSuspected = false,
            externalConsumerCoverage = externalConsumerCoverage
        )

        // 5. Deterministic sorting (BR-7, AT-11)
        val sortedRelationships = semanticRelationships.sortedWith(
            compareBy(
                { it.sourceLocation.file.lowercase() },
                { it.sourceLocation.offset ?: Int.MAX_VALUE },
                { it.relationKind.name }
            )
        )

        val sortedCandidates = heuristicCandidates.sortedWith(
            compareBy(
                { it.sourceLocation.file.lowercase() },
                { it.sourceLocation.offset ?: Int.MAX_VALUE },
                { it.candidateKind }
            )
        )

        // 6. Blast Radius aggregation
        val targetFile = targetElement.containingFile
        val targetModule = targetFile?.let { ModuleUtilCore.findModuleForPsiElement(it)?.name }

        val affectedFiles = (sortedRelationships.map { it.sourceLocation.file } + listOfNotNull(targetFile?.virtualFile?.path)).distinct().sorted()
        val usageModules = sortedRelationships.mapNotNull { rel ->
            val psiFile = findPsiFile(rel.sourceLocation.file)
            psiFile?.let { ModuleUtilCore.findModuleForPsiElement(it)?.name }
        }
        val affectedModules = (usageModules + listOfNotNull(targetModule)).distinct().sorted()

        val overrideCount = sortedRelationships.count { it.relationKind == SemanticRelationKind.OVERRIDE }
        val implementationCount = sortedRelationships.count { it.relationKind == SemanticRelationKind.IMPLEMENTATION }

        val blastRadius = BlastRadius(
            observedUsageCount = sortedRelationships.size,
            affectedFilesCount = sortedRelationships.map { it.sourceLocation.file }.distinct().size,
            affectedModulesCount = affectedModules.size,
            affectedFiles = sortedRelationships.map { it.sourceLocation.file }.distinct().sorted(),
            affectedModules = affectedModules,
            overrideCount = overrideCount,
            implementationCount = implementationCount
        )

        val report = ImpactReport(
            targetSymbol = targetSymbol,
            requestedScope = request.scope,
            evaluatedScope = request.scope,
            completeness = ImpactCompleteness.COMPLETE,
            blastRadius = blastRadius,
            semanticRelationships = sortedRelationships,
            heuristicCandidates = sortedCandidates,
            dynamicRisk = dynamicRisk,
            limitations = limitations
        )

        val duration = System.currentTimeMillis() - startTime
        return ImpactAnalysisResult(
            outcome = ImpactOutcome.ImpactReady(report),
            provenance = ImpactProvenance(
                traceId = request.traceId,
                transactionId = request.transactionId,
                toolCallId = request.toolCallId,
                targetSymbolHandle = request.symbolHandle,
                outcomeStatus = "IMPACT_READY",
                backend = "jetbrains-k2",
                capabilityLevel = CapabilityLevel.NATIVE,
                durationMs = duration
            )
        )
    }

    private fun resolveSearchScope(scope: AnalysisScope, element: PsiElement): SearchScope {
        return when (scope) {
            AnalysisScope.LOCAL -> {
                val file = element.containingFile ?: return GlobalSearchScope.projectScope(project)
                LocalSearchScope(file)
            }
            AnalysisScope.MODULE -> {
                val file = element.containingFile
                if (file != null) {
                    val module = ModuleUtilCore.findModuleForPsiElement(file)
                    if (module != null) {
                        GlobalSearchScope.moduleWithDependentsScope(module)
                    } else {
                        GlobalSearchScope.projectScope(project)
                    }
                } else {
                    GlobalSearchScope.projectScope(project)
                }
            }
            AnalysisScope.PROJECT -> GlobalSearchScope.projectScope(project)
        }
    }

    private fun classifyRelationKind(refElement: PsiElement, targetElement: PsiElement): SemanticRelationKind {
        if (PsiTreeUtil.getParentOfType(refElement, KtImportDirective::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, PsiImportStatementBase::class.java, false) != null) {
            return SemanticRelationKind.IMPORT
        }

        if (PsiTreeUtil.getParentOfType(refElement, KDoc::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, PsiDocCommentBase::class.java, false) != null) {
            return SemanticRelationKind.DOC_REFERENCE
        }

        if (PsiTreeUtil.getParentOfType(refElement, KtUserType::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, KtTypeReference::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, PsiTypeElement::class.java, false) != null) {
            return SemanticRelationKind.TYPE_REFERENCE
        }

        if (targetElement is KtProperty || targetElement is PsiField || targetElement is KtParameter) {
            return SemanticRelationKind.PROPERTY_ACCESS
        }

        if (PsiTreeUtil.getParentOfType(refElement, KtCallExpression::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, KtConstructorCalleeExpression::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(refElement, PsiMethodCallExpression::class.java, false) != null) {
            return SemanticRelationKind.CALL_SITE
        }

        return SemanticRelationKind.CALL_SITE
    }

    private fun discoverHierarchyRelationships(
        targetElement: PsiElement,
        searchScope: SearchScope,
        relationships: MutableList<DiscoveredSemanticRelationship>
    ) {
        val containingKtClass = PsiTreeUtil.getParentOfType(targetElement, KtClassOrObject::class.java, false)
        val isTargetInterfaceOrAbstract = when (targetElement) {
            is KtClass -> targetElement.isInterface() || targetElement.hasModifier(KtTokens.ABSTRACT_KEYWORD)
            is KtNamedFunction -> targetElement.hasModifier(KtTokens.ABSTRACT_KEYWORD) || ((containingKtClass as? KtClass)?.isInterface() == true)
            is PsiClass -> targetElement.isInterface || targetElement.hasModifierProperty(PsiModifier.ABSTRACT)
            is PsiMethod -> targetElement.hasModifierProperty(PsiModifier.ABSTRACT) || (targetElement.containingClass?.isInterface == true)
            else -> false
        }

        val relationKind = if (isTargetInterfaceOrAbstract) {
            SemanticRelationKind.IMPLEMENTATION
        } else {
            SemanticRelationKind.OVERRIDE
        }

        val addedLocations = relationships.map { it.sourceLocation.file to it.sourceLocation.offset }.toMutableSet()

        // Use DefinitionsScopedSearch for Kotlin / Java element hierarchy definitions
        val definitions = DefinitionsScopedSearch.search(targetElement, searchScope).findAll()
        for (def in definitions) {
            if (def == targetElement) continue
            val location = extractSourceLocation(def)
            if (addedLocations.add(location.file to location.offset)) {
                val enclosingFqn = findEnclosingDeclarationFqn(def)
                val snippet = extractSnippet(def)

                relationships.add(
                    DiscoveredSemanticRelationship(
                        relationKind = relationKind,
                        certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                        sourceLocation = location,
                        enclosingDeclarationFqName = enclosingFqn,
                        snippet = snippet
                    )
                )
            }
        }

        // Also search light method overrides if target is a KtNamedFunction or PsiMethod
        if (targetElement is KtNamedFunction) {
            val lightMethods = targetElement.toLightMethods()
            for (lightMethod in lightMethods) {
                val overrides = OverridingMethodsSearch.search(lightMethod, searchScope, true).findAll()
                for (overrideMethod in overrides) {
                    val srcElement = overrideMethod.navigationElement ?: overrideMethod
                    if (srcElement == targetElement) continue
                    val location = extractSourceLocation(srcElement)
                    if (addedLocations.add(location.file to location.offset)) {
                        val enclosingFqn = findEnclosingDeclarationFqn(srcElement)
                        val snippet = extractSnippet(srcElement)

                        relationships.add(
                            DiscoveredSemanticRelationship(
                                relationKind = relationKind,
                                certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                                sourceLocation = location,
                                enclosingDeclarationFqName = enclosingFqn,
                                snippet = snippet
                            )
                        )
                    }
                }
            }
        } else if (targetElement is PsiMethod) {
            val overrides = OverridingMethodsSearch.search(targetElement, searchScope, true).findAll()
            for (overrideMethod in overrides) {
                val srcElement = overrideMethod.navigationElement ?: overrideMethod
                if (srcElement == targetElement) continue
                val location = extractSourceLocation(srcElement)
                if (addedLocations.add(location.file to location.offset)) {
                    val enclosingFqn = findEnclosingDeclarationFqn(srcElement)
                    val snippet = extractSnippet(srcElement)

                    relationships.add(
                        DiscoveredSemanticRelationship(
                            relationKind = relationKind,
                            certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                            sourceLocation = location,
                            enclosingDeclarationFqName = enclosingFqn,
                            snippet = snippet
                        )
                    )
                }
            }
        }

        // 2. Upward hierarchy search: base declarations (BR-6)
        discoverBaseDeclarations(targetElement, relationships, addedLocations)
    }

    private fun discoverBaseDeclarations(
        targetElement: PsiElement,
        relationships: MutableList<DiscoveredSemanticRelationship>,
        addedLocations: MutableSet<Pair<String, Int?>>
    ) {
        if (targetElement is KtCallableDeclaration) {
            analyze(targetElement) {
                val symbol = targetElement.symbol as? KaCallableSymbol
                val directlyOverriddenSymbols = symbol?.directlyOverriddenSymbols
                if (directlyOverriddenSymbols != null) {
                    for (superSym in directlyOverriddenSymbols) {
                        val superPsi = superSym.psi
                        if (superPsi != null && superPsi != targetElement) {
                            val location = extractSourceLocation(superPsi)
                            if (addedLocations.add(location.file to location.offset)) {
                                val enclosingFqn = findEnclosingDeclarationFqn(superPsi)
                                val snippet = extractSnippet(superPsi)
                                relationships.add(
                                    DiscoveredSemanticRelationship(
                                        relationKind = SemanticRelationKind.BASE_DECLARATION,
                                        certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                                        sourceLocation = location,
                                        enclosingDeclarationFqName = enclosingFqn,
                                        snippet = snippet
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (targetElement is KtNamedFunction) {
            val lightMethods = targetElement.toLightMethods()
            for (lightMethod in lightMethods) {
                val superMethods = lightMethod.findSuperMethods(false)
                for (sm in superMethods) {
                    val superPsi = sm.navigationElement ?: sm
                    if (superPsi == targetElement) continue
                    val location = extractSourceLocation(superPsi)
                    if (addedLocations.add(location.file to location.offset)) {
                        val enclosingFqn = findEnclosingDeclarationFqn(superPsi)
                        val snippet = extractSnippet(superPsi)
                        relationships.add(
                            DiscoveredSemanticRelationship(
                                relationKind = SemanticRelationKind.BASE_DECLARATION,
                                certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                                sourceLocation = location,
                                enclosingDeclarationFqName = enclosingFqn,
                                snippet = snippet
                            )
                        )
                    }
                }
            }
        } else if (targetElement is PsiMethod) {
            val superMethods = targetElement.findSuperMethods(false)
            for (sm in superMethods) {
                val superPsi = sm.navigationElement ?: sm
                if (superPsi == targetElement) continue
                val location = extractSourceLocation(superPsi)
                if (addedLocations.add(location.file to location.offset)) {
                    val enclosingFqn = findEnclosingDeclarationFqn(superPsi)
                    val snippet = extractSnippet(superPsi)
                    relationships.add(
                        DiscoveredSemanticRelationship(
                            relationKind = SemanticRelationKind.BASE_DECLARATION,
                            certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                            sourceLocation = location,
                            enclosingDeclarationFqName = enclosingFqn,
                            snippet = snippet
                        )
                    )
                }
            }
        }
    }

    private fun discoverHeuristicCandidates(
        symbolName: String,
        targetElement: PsiElement,
        searchScope: SearchScope,
        candidates: MutableList<DiscoveredHeuristicCandidate>
    ) {
        val helper = PsiSearchHelper.getInstance(project)
        helper.processElementsWithWord(
            { element, _ ->
                if (element is KtStringTemplateExpression || element is PsiLiteralExpression) {
                    val text = element.text
                    if (text.contains(symbolName)) {
                        val location = extractSourceLocation(element)
                        val enclosingFqn = findEnclosingDeclarationFqn(element)
                        val snippet = extractSnippet(element)

                        candidates.add(
                            DiscoveredHeuristicCandidate(
                                candidateKind = "STRING_LITERAL_MATCH",
                                matchedText = symbolName,
                                sourceLocation = location,
                                snippet = snippet
                            )
                        )
                    }
                }
                true
            },
            searchScope,
            symbolName,
            UsageSearchContext.IN_STRINGS,
            true
        )
    }

    private fun findEnclosingDeclarationFqn(element: PsiElement): String? {
        val namedDecl = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
        if (namedDecl != null) {
            return namedDecl.fqName?.asString() ?: namedDecl.name
        }
        val psiMember = PsiTreeUtil.getParentOfType(element, PsiMember::class.java, false)
        if (psiMember != null) {
            return (psiMember as? PsiClass)?.qualifiedName ?: psiMember.name
        }
        return null
    }

    private fun extractSnippet(element: PsiElement): String? {
        val enclosingCallOrStmt = PsiTreeUtil.getParentOfType(
            element,
            KtCallExpression::class.java,
            KtDotQualifiedExpression::class.java,
            KtProperty::class.java,
            KtImportDirective::class.java,
            KtNamedDeclaration::class.java,
            PsiStatement::class.java,
            PsiMember::class.java
        )
        if (enclosingCallOrStmt != null) {
            val top = PsiTreeUtil.getParentOfType(enclosingCallOrStmt, KtDotQualifiedExpression::class.java) ?: enclosingCallOrStmt
            return top.text?.trim()
        }
        return element.parent?.text?.trim() ?: element.text?.trim()
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

    private fun findPsiFile(filePath: String): PsiFile? {
        val virtualFile = when {
            filePath.startsWith("temp://") || filePath.startsWith("file://") || filePath.startsWith("jar://") -> {
                com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(filePath)
            }
            else -> {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                    ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
                    ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("temp://$filePath")
                    ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("temp:///$filePath")
                    ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(filePath)
                    ?: com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(java.io.File(filePath))
            }
        } ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }
}
