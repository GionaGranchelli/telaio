package dev.telaio.core.symbol

import kotlinx.serialization.Serializable

@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ENUM_ENTRY,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    FIELD,
    LOCAL_VARIABLE,
    PARAMETER,
    TYPE_ALIAS,
    PACKAGE,
    UNKNOWN
}

@Serializable
enum class SymbolVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PRIVATE,
    LOCAL,
    UNKNOWN
}

@Serializable
enum class CapabilityLevel {
    UNSUPPORTED,
    BEST_EFFORT,
    SEMANTIC,
    NATIVE
}

@Serializable
enum class UnavailabilityReason {
    INDEXING,
    NOT_READY,
    TIMEOUT,
    OTHER
}

@Serializable
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
    val offset: Int? = null,
    val length: Int? = null
)

@Serializable
@JvmInline
value class SymbolHandle(val value: String) {
    init {
        require(value.matches(HANDLE_REGEX)) {
            "Invalid symbol handle '$value'. Expected format 'sym:<transactionId>:<id>'"
        }
    }

    val transactionId: String
        get() = value.split(":")[1]

    val localId: String
        get() = value.split(":")[2]

    companion object {
        private val HANDLE_REGEX = Regex("^sym:[a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+$")

        fun create(transactionId: String, id: String): SymbolHandle {
            return SymbolHandle("sym:$transactionId:$id")
        }
    }
}

@Serializable
data class SymbolRef(
    val handle: SymbolHandle,
    val name: String,
    val kind: SymbolKind,
    val qualifiedIdentity: String,
    val visibility: SymbolVisibility,
    val module: String? = null,
    val sourceLocation: SourceLocation,
    val docComment: String? = null
)
