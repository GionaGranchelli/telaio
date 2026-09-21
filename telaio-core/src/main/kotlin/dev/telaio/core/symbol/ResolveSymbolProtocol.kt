package dev.telaio.core.symbol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResolveSymbolRequest(
    val transactionId: String,
    val file: String,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val traceId: String? = null,
    val toolCallId: String? = null
) {
    init {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(file.isNotBlank()) { "file must not be blank" }
        require(offset != null || (line != null && column != null)) {
            "Either offset or line+column must be provided"
        }
    }
}

@Serializable
sealed interface ResolveOutcome {
    val status: String

    @Serializable
    @SerialName("RESOLVED")
    data class Resolved(
        val symbol: SymbolRef
    ) : ResolveOutcome {
        override val status: String get() = "RESOLVED"
    }

    @Serializable
    @SerialName("NOT_FOUND")
    data class NotFound(
        val message: String = "No symbol found at the specified location"
    ) : ResolveOutcome {
        override val status: String get() = "NOT_FOUND"
    }

    @Serializable
    @SerialName("AMBIGUOUS")
    data class Ambiguous(
        val candidates: List<SymbolRef>,
        val message: String = "Multiple candidate symbols resolved at the specified location"
    ) : ResolveOutcome {
        override val status: String get() = "AMBIGUOUS"
    }

    @Serializable
    @SerialName("UNSUPPORTED_SYMBOL")
    data class UnsupportedSymbol(
        val reason: String,
        val rawName: String? = null
    ) : ResolveOutcome {
        override val status: String get() = "UNSUPPORTED_SYMBOL"
    }

    @Serializable
    @SerialName("TEMPORARILY_UNAVAILABLE")
    data class TemporarilyUnavailable(
        val reason: UnavailabilityReason,
        val message: String
    ) : ResolveOutcome {
        override val status: String get() = "TEMPORARILY_UNAVAILABLE"
    }

    @Serializable
    @SerialName("INVALID_LOCATION")
    data class InvalidLocation(
        val reason: String,
        val file: String,
        val offset: Int? = null,
        val line: Int? = null,
        val column: Int? = null
    ) : ResolveOutcome {
        override val status: String get() = "INVALID_LOCATION"
    }

    @Serializable
    @SerialName("BACKEND_ERROR")
    data class BackendError(
        val message: String,
        val details: String? = null
    ) : ResolveOutcome {
        override val status: String get() = "BACKEND_ERROR"
    }
}

@Serializable
data class ResolveProvenance(
    val traceId: String? = null,
    val transactionId: String,
    val toolCallId: String? = null,
    val sourceFile: String,
    val sourceLocation: SourceLocation? = null,
    val outcomeStatus: String,
    val backend: String = "jetbrains-k2",
    val capabilityLevel: CapabilityLevel = CapabilityLevel.NATIVE,
    val resolvedHandle: SymbolHandle? = null,
    val durationMs: Long
)

@Serializable
data class ResolveSymbolResult(
    val outcome: ResolveOutcome,
    val provenance: ResolveProvenance
) {
    val isResolved: Boolean get() = outcome is ResolveOutcome.Resolved
    val resolvedSymbol: SymbolRef? get() = (outcome as? ResolveOutcome.Resolved)?.symbol
}
