package dev.telaio.core.symbol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SymbolModelsTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `test valid symbol handle format`() {
        val handle = SymbolHandle.create("txn-123", "sym-456")
        assertEquals("sym:txn-123:sym-456", handle.value)
        assertEquals("txn-123", handle.transactionId)
        assertEquals("sym-456", handle.localId)
    }

    @Test
    fun `test invalid symbol handle format throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            SymbolHandle("invalid_handle")
        }
        assertFailsWith<IllegalArgumentException> {
            SymbolHandle("sym:txn")
        }
    }

    @Test
    fun `test symbol resolution result serialization roundtrip`() {
        val handle = SymbolHandle.create("txn-1", "001")
        val symbolRef = SymbolRef(
            handle = handle,
            name = "calculateTotal",
            kind = SymbolKind.FUNCTION,
            qualifiedIdentity = "com.example.Order.calculateTotal(kotlin.Int, kotlin.Double): kotlin.Double",
            visibility = SymbolVisibility.PUBLIC,
            module = "order-module",
            sourceLocation = SourceLocation(
                file = "src/Order.kt",
                line = 10,
                column = 5,
                offset = 120,
                length = 14
            ),
            docComment = "Calculates total order price."
        )

        val outcome = ResolveOutcome.Resolved(symbolRef)
        val provenance = ResolveProvenance(
            traceId = "trace-abc",
            transactionId = "txn-1",
            toolCallId = "call-1",
            sourceFile = "src/Order.kt",
            sourceLocation = symbolRef.sourceLocation,
            outcomeStatus = "RESOLVED",
            backend = "jetbrains-k2",
            capabilityLevel = CapabilityLevel.NATIVE,
            resolvedHandle = handle,
            durationMs = 15
        )

        val result = ResolveSymbolResult(outcome, provenance)
        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ResolveSymbolResult>(serialized)

        assertEquals(result, deserialized)
        assertTrue(deserialized.isResolved)
        assertEquals("calculateTotal", deserialized.resolvedSymbol?.name)
    }

    @Test
    fun `test temporarily unavailable serialization roundtrip`() {
        val outcome = ResolveOutcome.TemporarilyUnavailable(
            reason = UnavailabilityReason.INDEXING,
            message = "Indexing in progress"
        )
        val provenance = ResolveProvenance(
            transactionId = "txn-1",
            sourceFile = "src/Order.kt",
            outcomeStatus = "TEMPORARILY_UNAVAILABLE",
            durationMs = 2
        )

        val result = ResolveSymbolResult(outcome, provenance)
        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ResolveSymbolResult>(serialized)

        assertEquals(result, deserialized)
        assertEquals(UnavailabilityReason.INDEXING, (deserialized.outcome as ResolveOutcome.TemporarilyUnavailable).reason)
    }

    @Test
    fun `test all outcome variants serialize and deserialize correctly`() {
        val outcomes: List<ResolveOutcome> = listOf(
            ResolveOutcome.NotFound("Symbol not found"),
            ResolveOutcome.Ambiguous(emptyList(), "Multiple matches"),
            ResolveOutcome.UnsupportedSymbol("Synthetic declaration", "synth$1"),
            ResolveOutcome.TemporarilyUnavailable(UnavailabilityReason.INDEXING, "Indexing"),
            ResolveOutcome.InvalidLocation("File does not exist", "/path/to/missing.kt", 0, 1, 1),
            ResolveOutcome.BackendError("Internal error", "Stacktrace details")
        )

        for (outcome in outcomes) {
            val result = ResolveSymbolResult(
                outcome = outcome,
                provenance = ResolveProvenance(
                    transactionId = "txn-test",
                    sourceFile = "test.kt",
                    outcomeStatus = outcome.status,
                    durationMs = 1
                )
            )
            val jsonStr = json.encodeToString(result)
            val decoded = json.decodeFromString<ResolveSymbolResult>(jsonStr)
            assertEquals(result, decoded)
        }
    }
}
