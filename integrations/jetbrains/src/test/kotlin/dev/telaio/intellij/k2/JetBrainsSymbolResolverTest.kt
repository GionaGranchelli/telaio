package dev.telaio.intellij.k2

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.telaio.core.symbol.*
import dev.telaio.intellij.bridge.TransactionSymbolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JetBrainsSymbolResolverTest : BasePlatformTestCase() {

    private lateinit var registry: TransactionSymbolRegistry
    private lateinit var resolver: JetBrainsSymbolResolver

    override fun setUp() {
        super.setUp()
        registry = TransactionSymbolRegistry()
        resolver = JetBrainsSymbolResolver(project, registry)
    }

    override fun tearDown() {
        registry.clearAll()
        super.tearDown()
    }

    /**
     * AT-1: Resolve private member
     * Given a Kotlin private method declaration,
     * when resolving the declaration position,
     * then the result identifies that exact method.
     */
    fun testAT1ResolvePrivateMember() {
        val file = myFixture.configureByText(
            "Service.kt",
            """
            package com.example.service

            class AccountService {
                private fun calculateSecretScore(userId: String): Int {
                    return userId.length * 42
                }
            }
            """.trimIndent()
        )

        val targetOffset = file.text.indexOf("calculateSecretScore")
        val request = ResolveSymbolRequest(
            transactionId = "txn-at1",
            file = file.virtualFile.path,
            offset = targetOffset
        )

        val result = resolver.resolveSymbol(request)

        assertTrue(result.outcome is ResolveOutcome.Resolved, "Expected outcome to be Resolved")
        val symbol = (result.outcome as ResolveOutcome.Resolved).symbol

        assertEquals("calculateSecretScore", symbol.name)
        assertEquals(SymbolKind.METHOD, symbol.kind)
        assertEquals(SymbolVisibility.PRIVATE, symbol.visibility)
        assertTrue(symbol.qualifiedIdentity.contains("AccountService.calculateSecretScore(kotlin.String): kotlin.Int"))
        assertEquals("txn-at1", symbol.handle.transactionId)
        assertTrue(symbol.handle.value.startsWith("sym:txn-at1:"))
    }

    /**
     * AT-2: Resolve reference to declaration
     * Given a call site,
     * when resolving the referenced symbol,
     * then the result identifies the declaration that the call resolves to.
     */
    fun testAT2ResolveReferenceToDeclaration() {
        val file = myFixture.configureByText(
            "Client.kt",
            """
            package com.example.client

            fun greet(name: String): String = "Hello, " + name

            fun main() {
                val message = greet("World")
            }
            """.trimIndent()
        )

        // Point to the call site "greet("
        val callOffset = file.text.indexOf("greet(\"World\")")
        val request = ResolveSymbolRequest(
            transactionId = "txn-at2",
            file = file.virtualFile.path,
            offset = callOffset
        )

        val result = resolver.resolveSymbol(request)

        assertTrue(result.outcome is ResolveOutcome.Resolved, "Expected call site to resolve to declaration")
        val symbol = (result.outcome as ResolveOutcome.Resolved).symbol

        assertEquals("greet", symbol.name)
        assertEquals(SymbolKind.FUNCTION, symbol.kind)
        assertTrue(symbol.qualifiedIdentity.contains("greet(kotlin.String): kotlin.String"))
        val declOffset = file.text.indexOf("fun greet")
        assertEquals(declOffset, symbol.sourceLocation.offset) // offset of the declaration
    }

    /**
     * AT-3: Distinguish overloads
     * Given:
     *   fun foo(value: String)
     *   fun foo(value: Int)
     * when resolving each declaration and matching call site,
     * then the identities remain distinct.
     */
    fun testAT3DistinguishOverloads() {
        val file = myFixture.configureByText(
            "Overloads.kt",
            """
            package com.example.overload

            class OverloadDemo {
                fun process(value: String): String = "str: " + value
                fun process(value: Int): String = "num: " + value
            }

            fun runner() {
                val demo = OverloadDemo()
                demo.process("test")
                demo.process(123)
            }
            """.trimIndent()
        )

        // 1. Resolve declaration of string overload
        val strDeclOffset = file.text.indexOf("process(value: String)")
        val resStrDecl = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at3", file = file.virtualFile.path, offset = strDeclOffset)
        )
        assertTrue(resStrDecl.outcome is ResolveOutcome.Resolved)
        val symStrDecl = (resStrDecl.outcome as ResolveOutcome.Resolved).symbol

        // 2. Resolve declaration of int overload
        val intDeclOffset = file.text.indexOf("process(value: Int)")
        val resIntDecl = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at3", file = file.virtualFile.path, offset = intDeclOffset)
        )
        assertTrue(resIntDecl.outcome is ResolveOutcome.Resolved)
        val symIntDecl = (resIntDecl.outcome as ResolveOutcome.Resolved).symbol

        // 3. Resolve call site with string arg
        val strCallOffset = file.text.indexOf("process(\"test\")")
        val resStrCall = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at3", file = file.virtualFile.path, offset = strCallOffset)
        )
        assertTrue(resStrCall.outcome is ResolveOutcome.Resolved)
        val symStrCall = (resStrCall.outcome as ResolveOutcome.Resolved).symbol

        // 4. Resolve call site with int arg
        val intCallOffset = file.text.indexOf("process(123)")
        val resIntCall = resolver.resolveSymbol(
            ResolveSymbolRequest(transactionId = "txn-at3", file = file.virtualFile.path, offset = intCallOffset)
        )
        assertTrue(resIntCall.outcome is ResolveOutcome.Resolved)
        val symIntCall = (resIntCall.outcome as ResolveOutcome.Resolved).symbol

        // Invariants:
        // Declarations must have distinct qualified identities
        assertFalse(symStrDecl.qualifiedIdentity == symIntDecl.qualifiedIdentity)
        assertTrue(symStrDecl.qualifiedIdentity.contains("process(kotlin.String): kotlin.String"))
        assertTrue(symIntDecl.qualifiedIdentity.contains("process(kotlin.Int): kotlin.String"))

        // Call site 1 resolves to String overload declaration
        assertEquals(symStrDecl.qualifiedIdentity, symStrCall.qualifiedIdentity)
        assertEquals(symStrDecl.sourceLocation.offset, symStrCall.sourceLocation.offset)

        // Call site 2 resolves to Int overload declaration
        assertEquals(symIntDecl.qualifiedIdentity, symIntCall.qualifiedIdentity)
        assertEquals(symIntDecl.sourceLocation.offset, symIntCall.sourceLocation.offset)
    }

    /**
     * AT-4: Cross-file resolution
     * Given a symbol declared in file A and referenced in file B,
     * then resolving the reference in file B identifies the declaration from file A.
     */
    fun testAT4CrossFileResolution() {
        val fileA = myFixture.addFileToProject(
            "com/example/lib/Calculator.kt",
            """
            package com.example.lib

            class Calculator {
                fun add(a: Int, b: Int): Int = a + b
            }
            """.trimIndent()
        )

        val fileB = myFixture.configureByText(
            "Consumer.kt",
            """
            package com.example.app

            import com.example.lib.Calculator

            fun compute() {
                val calc = Calculator()
                val sum = calc.add(5, 10)
            }
            """.trimIndent()
        )

        val addCallOffset = fileB.text.indexOf("add(5, 10)")
        val request = ResolveSymbolRequest(
            transactionId = "txn-at4",
            file = fileB.virtualFile.path,
            offset = addCallOffset
        )

        val result = resolver.resolveSymbol(request)

        assertTrue(result.outcome is ResolveOutcome.Resolved)
        val symbol = (result.outcome as ResolveOutcome.Resolved).symbol

        assertEquals("add", symbol.name)
        assertTrue(symbol.qualifiedIdentity.contains("Calculator.add(kotlin.Int, kotlin.Int): kotlin.Int"))
        assertEquals(fileA.virtualFile.path, symbol.sourceLocation.file)
    }

    /**
     * AT-5: Indexing state
     * Given semantic resolution is unavailable due to indexing,
     * then the result is TEMPORARILY_UNAVAILABLE: INDEXING, not NOT_FOUND.
     */
    fun testAT5IndexingState() {
        val file = myFixture.configureByText(
            "Demo.kt",
            """
            package com.example.demo

            fun example() {}
            """.trimIndent()
        )

        val offset = file.text.indexOf("example")
        val request = ResolveSymbolRequest(
            transactionId = "txn-at5",
            file = file.virtualFile.path,
            offset = offset
        )

        // Test normal resolution when indexing is not active
        val normalResult = resolver.resolveSymbol(request)
        assertTrue(normalResult.outcome is ResolveOutcome.Resolved)

        // Now test when dumb mode is active
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val indexingResult = resolver.resolveSymbol(request)
            assertTrue(indexingResult.outcome is ResolveOutcome.TemporarilyUnavailable, "Expected TemporarilyUnavailable during indexing")
            val unavailable = indexingResult.outcome as ResolveOutcome.TemporarilyUnavailable
            assertEquals(UnavailabilityReason.INDEXING, unavailable.reason)
            assertEquals("TEMPORARILY_UNAVAILABLE", indexingResult.provenance.outcomeStatus)
            assertFalse(indexingResult.outcome is ResolveOutcome.NotFound)
        }
    }

    /**
     * AT-6: No implementation leakage
     * Serialized output contains no PSI/K2 object reference or implementation-specific JVM identity.
     */
    fun testAT6NoImplementationLeakage() {
        val file = myFixture.configureByText(
            "LeakCheck.kt",
            """
            package com.example.leak

            class DataRepository {
                fun fetch(): String = "data"
            }
            """.trimIndent()
        )

        val offset = file.text.indexOf("fetch")
        val request = ResolveSymbolRequest(
            transactionId = "txn-at6",
            file = file.virtualFile.path,
            offset = offset,
            traceId = "trace-leak-check",
            toolCallId = "tool-call-1"
        )

        val result = resolver.resolveSymbol(request)
        assertTrue(result.outcome is ResolveOutcome.Resolved)

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(result)

        // Verify JSON string contains no JVM / PSI / internal class references
        assertFalse(serialized.contains("com.intellij.psi"))
        assertFalse(serialized.contains("org.jetbrains.kotlin.analysis"))
        assertFalse(serialized.contains("KaSymbol"))
        assertFalse(serialized.contains("PsiElement"))
        assertFalse(serialized.contains("SmartPsiElementPointer"))

        // Verify JSON string roundtrips cleanly in pure core without IntelliJ on classpath
        val deserialized = json.decodeFromString<ResolveSymbolResult>(serialized)
        assertEquals(result.outcome, deserialized.outcome)
        assertEquals(result.provenance, deserialized.provenance)
    }

    /**
     * Edge case: Invalid location (non-existent file)
     */
    fun testInvalidLocationNonExistentFile() {
        val request = ResolveSymbolRequest(
            transactionId = "txn-invalid-loc",
            file = "/non/existent/path/Missing.kt",
            offset = 10
        )
        val result = resolver.resolveSymbol(request)
        assertTrue(result.outcome is ResolveOutcome.InvalidLocation)
    }

    /**
     * Edge case: Not found on whitespace
     */
    fun testNotFoundOnWhitespace() {
        val file = myFixture.configureByText(
            "Empty.kt",
            """
            package com.example

            
            
            fun test() {}
            """.trimIndent()
        )
        val request = ResolveSymbolRequest(
            transactionId = "txn-whitespace",
            file = file.virtualFile.path,
            offset = file.text.indexOf("\n\n") + 1
        )
        val result = resolver.resolveSymbol(request)
        assertTrue(result.outcome is ResolveOutcome.NotFound)
    }
}
