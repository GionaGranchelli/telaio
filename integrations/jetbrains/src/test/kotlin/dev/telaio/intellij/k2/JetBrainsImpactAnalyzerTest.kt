package dev.telaio.intellij.k2

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.telaio.core.impact.*
import dev.telaio.core.symbol.*
import dev.telaio.intellij.bridge.TransactionSymbolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JetBrainsImpactAnalyzerTest : BasePlatformTestCase() {

    private lateinit var registry: TransactionSymbolRegistry
    private lateinit var resolver: JetBrainsSymbolResolver
    private lateinit var analyzer: JetBrainsImpactAnalyzer

    override fun setUp() {
        super.setUp()
        registry = TransactionSymbolRegistry()
        resolver = JetBrainsSymbolResolver(project, registry)
        analyzer = JetBrainsImpactAnalyzer(project, registry)
    }

    override fun tearDown() {
        registry.clearAll()
        super.tearDown()
    }

    /**
     * AT-1: Direct Function Usages
     * Given a top-level function with 3 call sites across 2 files,
     * returns observedUsageCount = 3, affectedFilesCount = 2,
     * and 3 CALL_SITE relationships with certainty = SEMANTICALLY_PROVEN.
     */
    fun testAT1DirectFunctionUsages() {
        val fileA = myFixture.addFileToProject(
            "com/example/math/MathOps.kt",
            """
            package com.example.math

            fun addNumbers(a: Int, b: Int): Int = a + b

            fun internalCaller() {
                val sum = addNumbers(1, 2)
            }
            """.trimIndent()
        )

        val fileB = myFixture.addFileToProject(
            "com/example/math/App.kt",
            """
            package com.example.math

            fun run() {
                val first = addNumbers(10, 20)
                val second = addNumbers(30, 40)
            }
            """.trimIndent()
        )

        // Resolve symbol handle for addNumbers in MathOps.kt
        val declOffset = fileA.text.indexOf("addNumbers")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at1", file = fileA.virtualFile.path, offset = declOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        // Analyze impact across project
        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at1", symbolHandle = handle, scope = AnalysisScope.PROJECT)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady, "Expected ImpactReady")
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(3, report.blastRadius.observedUsageCount)
        assertEquals(2, report.blastRadius.affectedFilesCount)
        assertEquals(3, report.semanticRelationships.size)
        assertTrue(report.semanticRelationships.all { it.certainty == EvidenceCertainty.SEMANTICALLY_PROVEN })
        assertTrue(report.semanticRelationships.all { it.relationKind == SemanticRelationKind.CALL_SITE })
        assertEquals(ImpactCompleteness.COMPLETE, report.completeness)
    }

    /**
     * AT-2: Overload Discrimination
     * Given foo(String) and foo(Int), impact analysis for foo(String)
     * returns only references passing string arguments, with 0 references to foo(Int).
     */
    fun testAT2OverloadDiscrimination() {
        val file = myFixture.configureByText(
            "Overloads.kt",
            """
            package com.example.overload

            class Dispatcher {
                fun handle(msg: String) {}
                fun handle(code: Int) {}
            }

            fun client() {
                val d = Dispatcher()
                d.handle("hello")
                d.handle("world")
                d.handle(404)
            }
            """.trimIndent()
        )

        // Resolve handle for handle(msg: String)
        val strDeclOffset = file.text.indexOf("handle(msg: String)")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at2", file = file.virtualFile.path, offset = strDeclOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val strHandle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        // Impact analysis for handle(String)
        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at2", symbolHandle = strHandle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        // Exactly 2 usages (hello, world) and 0 for handle(404)
        assertEquals(2, report.blastRadius.observedUsageCount)
        assertEquals(2, report.semanticRelationships.size)
        assertTrue(report.semanticRelationships.all { it.relationKind == SemanticRelationKind.CALL_SITE })
        val snippets = report.semanticRelationships.mapNotNull { it.snippet }
        assertTrue(snippets.any { it.contains("\"hello\"") })
        assertTrue(snippets.any { it.contains("\"world\"") })
        assertFalse(snippets.any { it.contains("404") })
    }

    /**
     * AT-3: Cross-Module Impact
     * Given multiple files in the project workspace,
     * returns affectedFilesCount and affectedFiles accurately.
     */
    fun testAT3CrossModuleImpact() {
        val fileA = myFixture.addFileToProject(
            "core/Service.kt",
            """
            package com.example.core

            class Service {
                fun execute() {}
            }
            """.trimIndent()
        )

        val fileB = myFixture.addFileToProject(
            "client1/ClientOne.kt",
            """
            package com.example.client1
            import com.example.core.Service

            fun callOne() {
                Service().execute()
            }
            """.trimIndent()
        )

        val fileC = myFixture.addFileToProject(
            "client2/ClientTwo.kt",
            """
            package com.example.client2
            import com.example.core.Service

            fun callTwo() {
                Service().execute()
            }
            """.trimIndent()
        )

        val declOffset = fileA.text.indexOf("execute")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at3", file = fileA.virtualFile.path, offset = declOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at3", symbolHandle = handle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(2, report.blastRadius.observedUsageCount)
        assertEquals(2, report.blastRadius.affectedFilesCount)
        assertTrue(report.blastRadius.affectedFiles.contains(fileB.virtualFile.path))
        assertTrue(report.blastRadius.affectedFiles.contains(fileC.virtualFile.path))
    }

    /**
     * AT-4: Override & Subtype Discovery
     * Given an interface method and 2 implementing classes,
     * returns 2 relationships with relationKind = IMPLEMENTATION and certainty = SEMANTICALLY_PROVEN.
     */
    fun testAT4OverrideAndSubtypeDiscovery() {
        val file = myFixture.configureByText(
            "Hierarchy.kt",
            """
            package com.example.hierarchy

            interface PaymentProcessor {
                fun processPayment(amount: Double): Boolean
            }

            class CreditCardProcessor : PaymentProcessor {
                override fun processPayment(amount: Double): Boolean = true
            }

            class PaypalProcessor : PaymentProcessor {
                override fun processPayment(amount: Double): Boolean = true
            }
            """.trimIndent()
        )

        val ifaceDeclOffset = file.text.indexOf("processPayment(amount: Double)")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at4", file = file.virtualFile.path, offset = ifaceDeclOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at4", symbolHandle = handle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(2, report.blastRadius.implementationCount)
        val implementations = report.semanticRelationships.filter { it.relationKind == SemanticRelationKind.IMPLEMENTATION }
        assertEquals(2, implementations.size)
        assertTrue(implementations.all { it.certainty == EvidenceCertainty.SEMANTICALLY_PROVEN })
    }

    /**
     * AT-5: Property & Accessor Usages
     * Given a Kotlin property, returns all read and write reference locations as PROPERTY_ACCESS.
     */
    fun testAT5PropertyAccessorUsages() {
        val file = myFixture.configureByText(
            "Account.kt",
            """
            package com.example.account

            class Account {
                var balance: Double = 0.0
            }

            fun update(acc: Account) {
                acc.balance = 100.0
                val current = acc.balance
            }
            """.trimIndent()
        )

        val propOffset = file.text.indexOf("balance: Double")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at5", file = file.virtualFile.path, offset = propOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at5", symbolHandle = handle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(2, report.blastRadius.observedUsageCount)
        assertTrue(report.semanticRelationships.all { it.relationKind == SemanticRelationKind.PROPERTY_ACCESS })
    }

    /**
     * AT-6: Zero Usage Private Member
     * Given an unused private helper function, returns observedUsageCount = 0,
     * completeness = COMPLETE, empty semanticRelationships, and empty limitations.
     */
    fun testAT6ZeroUsagePrivateMember() {
        val file = myFixture.configureByText(
            "Helper.kt",
            """
            package com.example.helper

            class Helper {
                private fun unusedHelper() {}
            }
            """.trimIndent()
        )

        val helperOffset = file.text.indexOf("unusedHelper")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at6", file = file.virtualFile.path, offset = helperOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at6", symbolHandle = handle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(0, report.blastRadius.observedUsageCount)
        assertEquals(0, report.blastRadius.affectedFilesCount)
        assertTrue(report.semanticRelationships.isEmpty())
        assertEquals(ImpactCompleteness.COMPLETE, report.completeness)
        assertTrue(report.limitations.isEmpty())
    }

    /**
     * AT-7: Public API External Uncertainty
     * Given a public API method with 0 internal usages,
     * returns observedUsageCount = 0, completeness = COMPLETE (for PROJECT scope),
     * dynamicRisk.externalConsumerCoverage = UNKNOWN,
     * and limitations containing EXTERNAL_CONSUMERS_UNVERIFIABLE.
     */
    fun testAT7PublicApiExternalUncertainty() {
        val file = myFixture.configureByText(
            "PublicApi.kt",
            """
            package com.example.api

            class PublicLibrary {
                fun exportedMethod() {}
            }
            """.trimIndent()
        )

        val methodOffset = file.text.indexOf("exportedMethod")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at7", file = file.virtualFile.path, offset = methodOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at7", symbolHandle = handle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        assertEquals(0, report.blastRadius.observedUsageCount)
        assertEquals(ImpactCompleteness.COMPLETE, report.completeness)
        assertEquals(ImpactCompleteness.UNKNOWN, report.dynamicRisk.externalConsumerCoverage)
        assertTrue(report.limitations.any { it.code == LimitationCode.EXTERNAL_CONSUMERS_UNVERIFIABLE })
    }

    /**
     * AT-8: Indexing Unavailable
     * Given indexing is active, returns TEMPORARILY_UNAVAILABLE: INDEXING
     * and does NOT return a false zero-usage report.
     */
    fun testAT8IndexingUnavailable() {
        val file = myFixture.configureByText(
            "Demo.kt",
            """
            package com.example.demo
            fun doWork() {}
            """.trimIndent()
        )

        val offset = file.text.indexOf("doWork")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at8", file = file.virtualFile.path, offset = offset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val impactRes = analyzer.analyzeImpact(
                ImpactAnalysisRequest(transactionId = "txn-at8", symbolHandle = handle)
            )

            assertTrue(impactRes.outcome is ImpactOutcome.TemporarilyUnavailable)
            val unavailable = impactRes.outcome as ImpactOutcome.TemporarilyUnavailable
            assertEquals(UnavailabilityReason.INDEXING, unavailable.reason)
            assertEquals("TEMPORARILY_UNAVAILABLE", impactRes.provenance.outcomeStatus)
        }
    }

    /**
     * AT-9: Stale Handle Rejection
     * Given an unregistered handle or handle from a different transaction,
     * returns STALE_SYMBOL_HANDLE.
     */
    fun testAT9StaleHandleRejection() {
        val fakeHandle = SymbolHandle.create("txn-unknown", "deadbeef")

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-unknown", symbolHandle = fakeHandle)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.StaleSymbolHandle)
        val stale = impactRes.outcome as ImpactOutcome.StaleSymbolHandle
        assertEquals(fakeHandle, stale.handle)
    }

    /**
     * AT-10: Heuristic String Candidate Separation
     * Given symbol name appearing inside a string literal,
     * places occurrence in heuristicCandidates (not semanticRelationships)
     * and increments dynamicRisk.stringLiteralCandidatesFound without altering observedUsageCount.
     */
    fun testAT10HeuristicStringCandidateSeparation() {
        val file = myFixture.configureByText(
            "ConfigUsage.kt",
            """
            package com.example.config

            fun deployService() {}

            fun configure() {
                val call = deployService()
                val configString = "target-action: deployService"
            }
            """.trimIndent()
        )

        val declOffset = file.text.indexOf("fun deployService") + 4
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at10", file = file.virtualFile.path, offset = declOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at10", symbolHandle = handle, includeCandidates = true)
        )

        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
        val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

        // observedUsageCount must strictly be 1 (the semantic call), not 2
        assertEquals(1, report.blastRadius.observedUsageCount)
        assertEquals(1, report.semanticRelationships.size)
        assertEquals(SemanticRelationKind.CALL_SITE, report.semanticRelationships[0].relationKind)

        // The string occurrence is captured separately in heuristicCandidates
        assertEquals(1, report.heuristicCandidates.size)
        assertEquals("STRING_LITERAL_MATCH", report.heuristicCandidates[0].candidateKind)
        assertEquals("deployService", report.heuristicCandidates[0].matchedText)
        assertEquals(1, report.dynamicRisk.stringLiteralCandidatesFound)
    }

    /**
     * AT-11: Deterministic Ordering
     * Emitted relationship and candidate lists are deterministically sorted
     * by file URI, offset, and relation/candidate kind.
     */
    fun testAT11DeterministicOrdering() {
        val file = myFixture.configureByText(
            "MultiCalls.kt",
            """
            package com.example.order

            fun target() {}

            fun first() { target() }
            fun second() { target() }
            fun third() { target() }
            """.trimIndent()
        )

        val declOffset = file.text.indexOf("fun target") + 4
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at11", file = file.virtualFile.path, offset = declOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val res1 = analyzer.analyzeImpact(ImpactAnalysisRequest(transactionId = "txn-at11", symbolHandle = handle))
        val res2 = analyzer.analyzeImpact(ImpactAnalysisRequest(transactionId = "txn-at11", symbolHandle = handle))

        assertTrue(res1.outcome is ImpactOutcome.ImpactReady)
        assertTrue(res2.outcome is ImpactOutcome.ImpactReady)

        val list1 = (res1.outcome as ImpactOutcome.ImpactReady).report.semanticRelationships
        val list2 = (res2.outcome as ImpactOutcome.ImpactReady).report.semanticRelationships

        assertEquals(3, list1.size)
        assertEquals(list1, list2)

        // Verify offsets are in strictly ascending order
        val offsets = list1.mapNotNull { it.sourceLocation.offset }
        assertEquals(offsets.sorted(), offsets)
    }

    /**
     * AT-12: Zero Implementation Leakage
     * Full JSON serialization roundtrip of ImpactAnalysisResult
     * verifies no com.intellij.* or Ka* class names appear in the payload.
     */
    fun testAT12ZeroImplementationLeakage() {
        val file = myFixture.configureByText(
            "Leak.kt",
            """
            package com.example.leak
            fun secretAction() {}
            fun invoker() { secretAction() }
            """.trimIndent()
        )

        val declOffset = file.text.indexOf("secretAction")
        val resolveRes = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at12", file = file.virtualFile.path, offset = declOffset)
        )
        assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
        val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

        val impactRes = analyzer.analyzeImpact(
            ImpactAnalysisRequest(transactionId = "txn-at12", symbolHandle = handle)
        )
        assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(impactRes)

        // Invariants: No internal JVM/PSI references in serialized payload
        assertFalse(serialized.contains("com.intellij"))
        assertFalse(serialized.contains("org.jetbrains.kotlin.analysis"))
        assertFalse(serialized.contains("KaSymbol"))
        assertFalse(serialized.contains("PsiElement"))
        assertFalse(serialized.contains("SmartPsiElementPointer"))

        // Roundtrip deserialization into pure core
        val deserialized = json.decodeFromString<ImpactAnalysisResult>(serialized)
        assertEquals(impactRes.outcome, deserialized.outcome)
        assertEquals(impactRes.provenance, deserialized.provenance)
    }
}
