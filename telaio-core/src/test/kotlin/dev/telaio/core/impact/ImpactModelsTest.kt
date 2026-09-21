package dev.telaio.core.impact

import dev.telaio.core.symbol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImpactModelsTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `test impact request transaction id validation`() {
        val handle = SymbolHandle.create("txn-100", "42")
        val validRequest = ImpactAnalysisRequest(
            transactionId = "txn-100",
            symbolHandle = handle
        )
        assertEquals("txn-100", validRequest.transactionId)

        // Transaction ID mismatch must fail
        assertFailsWith<IllegalArgumentException> {
            ImpactAnalysisRequest(
                transactionId = "txn-different",
                symbolHandle = handle
            )
        }
    }

    @Test
    fun `test impact report serialization roundtrip`() {
        val handle = SymbolHandle.create("txn-1", "001")
        val targetSymbol = SymbolRef(
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
            )
        )

        val relationships = listOf(
            DiscoveredSemanticRelationship(
                relationKind = SemanticRelationKind.CALL_SITE,
                certainty = EvidenceCertainty.SEMANTICALLY_PROVEN,
                sourceLocation = SourceLocation("src/Client.kt", 15, 8, 250, 14),
                enclosingDeclarationFqName = "com.example.Client.submitOrder",
                snippet = "val total = calculateTotal(count, price)"
            )
        )

        val heuristicCandidates = listOf(
            DiscoveredHeuristicCandidate(
                candidateKind = "STRING_LITERAL_MATCH",
                matchedText = "calculateTotal",
                sourceLocation = SourceLocation("src/Config.kt", 30, 12, 500, 14),
                snippet = "\"handler: calculateTotal\""
            )
        )

        val report = ImpactReport(
            targetSymbol = targetSymbol,
            requestedScope = AnalysisScope.PROJECT,
            evaluatedScope = AnalysisScope.PROJECT,
            completeness = ImpactCompleteness.COMPLETE,
            blastRadius = BlastRadius(
                observedUsageCount = 1,
                affectedFilesCount = 1,
                affectedModulesCount = 1,
                affectedFiles = listOf("src/Client.kt"),
                affectedModules = listOf("order-module"),
                overrideCount = 0,
                implementationCount = 0
            ),
            semanticRelationships = relationships,
            heuristicCandidates = heuristicCandidates,
            dynamicRisk = DynamicRiskAssessment(
                reflectionRisk = false,
                stringLiteralCandidatesFound = 1,
                externalConsumerCoverage = ImpactCompleteness.UNKNOWN
            ),
            limitations = listOf(
                AnalysisLimitation(
                    code = LimitationCode.EXTERNAL_CONSUMERS_UNVERIFIABLE,
                    description = "Public API may have external consumers outside current project scope"
                )
            )
        )

        val outcome = ImpactOutcome.ImpactReady(report)
        val provenance = ImpactProvenance(
            traceId = "trace-1",
            transactionId = "txn-1",
            toolCallId = "call-1",
            targetSymbolHandle = handle,
            outcomeStatus = "IMPACT_READY",
            durationMs = 25
        )

        val result = ImpactAnalysisResult(outcome, provenance)
        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ImpactAnalysisResult>(serialized)

        assertEquals(result, deserialized)
        assertTrue(deserialized.isReady)
        assertEquals(1, deserialized.reportOrNull?.blastRadius?.observedUsageCount)
        assertEquals(1, deserialized.reportOrNull?.heuristicCandidates?.size)
    }

    @Test
    fun `test all impact outcomes serialization roundtrip`() {
        val handle = SymbolHandle.create("txn-1", "002")
        val outcomes: List<ImpactOutcome> = listOf(
            ImpactOutcome.TemporarilyUnavailable(UnavailabilityReason.INDEXING, "Indexing active"),
            ImpactOutcome.StaleSymbolHandle(handle, "Handle expired"),
            ImpactOutcome.UnsupportedSymbol("Non-Kotlin symbol"),
            ImpactOutcome.BackendError("Backend failure", "Details")
        )

        for (outcome in outcomes) {
            val result = ImpactAnalysisResult(
                outcome = outcome,
                provenance = ImpactProvenance(
                    transactionId = "txn-1",
                    targetSymbolHandle = handle,
                    outcomeStatus = outcome.status,
                    durationMs = 5
                )
            )
            val serialized = json.encodeToString(result)
            val decoded = json.decodeFromString<ImpactAnalysisResult>(serialized)
            assertEquals(result, decoded)
        }
    }
}
