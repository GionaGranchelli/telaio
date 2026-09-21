package dev.telaio.core.impact

import dev.telaio.core.symbol.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisScope {
    LOCAL,      // Enclosing file / class / local block
    MODULE,     // Owning module and its dependents
    PROJECT     // Complete IntelliJ project workspace
}

@Serializable
enum class ImpactCompleteness {
    COMPLETE,   // Analysis completely and exhaustively executed within evaluatedScope
    PARTIAL,    // Analysis executed partially (e.g. bounded search or timeout within evaluatedScope)
    UNKNOWN     // Analysis within evaluatedScope could not establish completeness
}

@Serializable
enum class SemanticRelationKind {
    CALL_SITE,              // Direct invocation of a function/constructor
    PROPERTY_ACCESS,        // Read or write access to a property/field
    TYPE_REFERENCE,         // Usage in type annotation, cast, or type argument
    OVERRIDE,               // Declaration overrides target member
    IMPLEMENTATION,         // Declaration implements abstract target member
    BASE_DECLARATION,       // Declaration is overridden by target
    IMPORT,                 // Direct import directive
    DOC_REFERENCE           // Reference in KDoc
}

@Serializable
enum class EvidenceCertainty {
    SEMANTICALLY_PROVEN,    // Formally resolved by K2 compiler / PSI references
    STRUCTURALLY_INFERRED   // Inferred from class hierarchy / type system
}

@Serializable
data class DiscoveredSemanticRelationship(
    val relationKind: SemanticRelationKind,
    val certainty: EvidenceCertainty,
    val sourceLocation: SourceLocation,
    val enclosingDeclarationFqName: String? = null,
    val snippet: String? = null
)

@Serializable
data class DiscoveredHeuristicCandidate(
    val candidateKind: String,          // e.g., "STRING_LITERAL_MATCH", "COMMENT_MATCH"
    val matchedText: String,
    val sourceLocation: SourceLocation,
    val snippet: String? = null
)

@Serializable
enum class LimitationCode {
    INDEXING_IN_PROGRESS,
    EXTERNAL_CONSUMERS_UNVERIFIABLE,
    REFLECTION_RISK,
    STRING_REFERENCE_POSSIBILITY,
    GENERATED_CODE_UNINDEXED,
    UNSUPPORTED_LANGUAGE_PRESENT,
    ANALYSIS_TIMEOUT,
    PARTIAL_SCOPE_SEARCH
}

@Serializable
data class AnalysisLimitation(
    val code: LimitationCode,
    val description: String,
    val affectedScope: AnalysisScope? = null
)

@Serializable
data class DynamicRiskAssessment(
    val reflectionRisk: Boolean = false,
    val stringLiteralCandidatesFound: Int = 0,
    val frameworkAnnotationWiringSuspected: Boolean = false,
    val externalConsumerCoverage: ImpactCompleteness = ImpactCompleteness.UNKNOWN
)

@Serializable
data class BlastRadius(
    val observedUsageCount: Int,
    val affectedFilesCount: Int,
    val affectedModulesCount: Int,
    val affectedFiles: List<String>,
    val affectedModules: List<String>,
    val overrideCount: Int,
    val implementationCount: Int
)

@Serializable
data class ImpactReport(
    val targetSymbol: SymbolRef,
    val requestedScope: AnalysisScope,
    val evaluatedScope: AnalysisScope,
    val completeness: ImpactCompleteness,
    val blastRadius: BlastRadius,
    val semanticRelationships: List<DiscoveredSemanticRelationship>,
    val heuristicCandidates: List<DiscoveredHeuristicCandidate> = emptyList(),
    val dynamicRisk: DynamicRiskAssessment = DynamicRiskAssessment(),
    val limitations: List<AnalysisLimitation> = emptyList()
)

@Serializable
data class ImpactAnalysisRequest(
    val transactionId: String,
    val symbolHandle: SymbolHandle,
    val scope: AnalysisScope = AnalysisScope.PROJECT,
    val includeCandidates: Boolean = true,
    val traceId: String? = null,
    val toolCallId: String? = null
) {
    init {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(transactionId == symbolHandle.transactionId) {
            "Transaction ID mismatch: request transactionId '$transactionId' does not match handle transactionId '${symbolHandle.transactionId}'"
        }
    }
}

@Serializable
sealed interface ImpactOutcome {
    val status: String

    @Serializable
    @SerialName("IMPACT_READY")
    data class ImpactReady(
        val report: ImpactReport
    ) : ImpactOutcome {
        override val status: String get() = "IMPACT_READY"
    }

    @Serializable
    @SerialName("TEMPORARILY_UNAVAILABLE")
    data class TemporarilyUnavailable(
        val reason: UnavailabilityReason,
        val message: String
    ) : ImpactOutcome {
        override val status: String get() = "TEMPORARILY_UNAVAILABLE"
    }

    @Serializable
    @SerialName("STALE_SYMBOL_HANDLE")
    data class StaleSymbolHandle(
        val handle: SymbolHandle,
        val message: String = "Symbol handle is expired, invalid, or no longer resolves in workspace"
    ) : ImpactOutcome {
        override val status: String get() = "STALE_SYMBOL_HANDLE"
    }

    @Serializable
    @SerialName("UNSUPPORTED_SYMBOL")
    data class UnsupportedSymbol(
        val reason: String
    ) : ImpactOutcome {
        override val status: String get() = "UNSUPPORTED_SYMBOL"
    }

    @Serializable
    @SerialName("BACKEND_ERROR")
    data class BackendError(
        val message: String,
        val details: String? = null
    ) : ImpactOutcome {
        override val status: String get() = "BACKEND_ERROR"
    }
}

@Serializable
data class ImpactProvenance(
    val traceId: String? = null,
    val transactionId: String,
    val toolCallId: String? = null,
    val targetSymbolHandle: SymbolHandle,
    val outcomeStatus: String,
    val backend: String = "jetbrains-k2",
    val capabilityLevel: CapabilityLevel = CapabilityLevel.NATIVE,
    val durationMs: Long
)

@Serializable
data class ImpactAnalysisResult(
    val outcome: ImpactOutcome,
    val provenance: ImpactProvenance
) {
    val isReady: Boolean get() = outcome is ImpactOutcome.ImpactReady
    val reportOrNull: ImpactReport? get() = (outcome as? ImpactOutcome.ImpactReady)?.report
}

interface SemanticImpactAnalyzer {
    fun analyzeImpact(request: ImpactAnalysisRequest): ImpactAnalysisResult
}
