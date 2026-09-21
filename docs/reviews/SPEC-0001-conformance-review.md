# SPEC-0001 Conformance & Architecture Review

- **Target Specification:** [`docs/specs/phase-0/0001-semantic-symbol-resolution.md`](../specs/phase-0/0001-semantic-symbol-resolution.md)
- **Status:** Complete / Approved
- **Date:** 2026-09-21
- **Review Mode:** Adversarial Conformance & Boundary Verification
- **Reviewed Modules:** `telaio-core`, `integrations/jetbrains`

---

## 1. Executive Summary

This review assesses the initial implementation of **SPEC-0001: Semantic Symbol Resolution** against the canonical architecture ([`docs/architecture.md`](../architecture.md)), binding Architecture Decision Records ([`docs/adr/`](../adr/)), and the explicit acceptance criteria in SPEC-0001.

**Verdict:** **PASS (Safe to Freeze)**.
The implementation satisfies all architectural boundaries, enforces transaction-scoped opaque handles, guarantees zero JVM/PSI leakage across the bridge, respects the IntelliJ/K2 background threading and read-action constraints, and proves semantic fidelity across all acceptance tests without relying on text heuristics or mocks.

---

## 2. Requirements Traceability Matrix

| Requirement | Description | Implementation Target | Verification Test | Status |
| :--- | :--- | :--- | :--- | :--- |
| **BR-1** | **Semantic Resolution**: Must resolve symbols using JetBrains/K2 semantic APIs, not text search. | [`JetBrainsSymbolResolver.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolver.kt#L235-L348) | [`JetBrainsSymbolResolverTest`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolverTest.kt) (`testAT1`, `testAT2`, `testAT3`, `testAT4`) | **PASS** |
| **BR-2** | **Transaction-Scoped Handle**: Opaque handle in format `sym:<transactionId>:<id>`. | [`SymbolModels.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/symbol/SymbolModels.kt#L41-L57), [`TransactionSymbolRegistry.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/bridge/TransactionSymbolRegistry.kt#L24-L47) | [`SymbolModelsTest`](../../telaio-core/src/test/kotlin/dev/telaio/core/symbol/SymbolModelsTest.kt#L18-L30), [`JetBrainsSymbolResolverTest.testAT1`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolverTest.kt#L66-L67) | **PASS** |
| **BR-3** | **Overload Discrimination**: Preserve distinct identities for overloaded callables using receiver/parameter/return types. | [`JetBrainsSymbolResolver.buildQualifiedIdentity`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolver.kt#L484-L540) | [`JetBrainsSymbolResolverTest.testAT3DistinguishOverloads`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolverTest.kt#L118-L182) | **PASS** |
| **BR-4** | **No Stale-Identity Guarantee**: Post-mutation resolution must re-resolve symbols; handles are transaction-scoped tokens. | Domain design & immutable `SymbolRef` | Documented contract; handles do not expose live PSI | **PASS** |
| **BR-5** | **Temporary Unavailability**: Return `TEMPORARILY_UNAVAILABLE: INDEXING` during indexing/dumb mode instead of `NOT_FOUND`. | [`JetBrainsSymbolResolver.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolver.kt#L53-L74) | [`JetBrainsSymbolResolverTest.testAT5IndexingState`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolverTest.kt#L237-L267) | **PASS** |
| **Structured Outcomes** | Full fidelity outcome hierarchy: `RESOLVED`, `NOT_FOUND`, `AMBIGUOUS`, `UNSUPPORTED_SYMBOL`, `TEMPORARILY_UNAVAILABLE`, `INVALID_LOCATION`, `BACKEND_ERROR`. | [`ResolveSymbolProtocol.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/symbol/ResolveSymbolProtocol.kt#L21-L76) | [`SymbolModelsTest`](../../telaio-core/src/test/kotlin/dev/telaio/core/symbol/SymbolModelsTest.kt#L73-L94), [`JetBrainsSymbolResolverTest`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolverTest.kt#L316-L347) | **PASS** |
| **Provenance Data** | Record `traceId`, `transactionId`, `toolCallId`, `sourceFile`, `sourceLocation`, `outcomeStatus`, `backend`, `capabilityLevel`, `resolvedHandle`, `durationMs`. | [`ResolveSymbolProtocol.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/symbol/ResolveSymbolProtocol.kt#L78-L91) | Tested across all outcome responses | **PASS** |

---

## 3. Acceptance Tests Evaluation

| Acceptance Test | Claim | Actual Verification | Result |
| :--- | :--- | :--- | :--- |
| **AT-1** | Private member declaration resolution | Declares `private fun calculateSecretScore(userId: String): Int` in `AccountService`. Resolves offset on method name; asserts `name == "calculateSecretScore"`, `kind == METHOD`, `visibility == PRIVATE`, `qualifiedIdentity` contains `AccountService.calculateSecretScore(kotlin.String): kotlin.Int`, and handle has format `sym:txn-at1:*`. | **PASS** |
| **AT-2** | Reference $\to$ declaration resolution | Configures call site `greet("World")`. Resolves call-site offset; asserts outcome is `RESOLVED` to `greet` declaration, kind is `FUNCTION`, and `sourceLocation.offset` matches the declaration's start offset in `Client.kt`. | **PASS** |
| **AT-3** | Overloaded callable discrimination | Configures `fun process(value: String)` and `fun process(value: Int)` in `OverloadDemo`, and distinct call sites `demo.process("test")` and `demo.process(123)`. Resolves both declarations and both call sites; asserts declarations have distinct `qualifiedIdentity` strings and each call site resolves to its corresponding declaration. | **PASS** |
| **AT-4** | Cross-file reference resolution | Declares `Calculator.add` in `com/example/lib/Calculator.kt` and calls `calc.add(5, 10)` in `Consumer.kt`. Resolves call site in `Consumer.kt`; asserts `sourceLocation.file` points to `Calculator.kt` and `qualifiedIdentity` contains `Calculator.add(kotlin.Int, kotlin.Int): kotlin.Int`. | **PASS** |
| **AT-5** | Indexing state behavior | Invokes resolver under `DumbModeTestUtils.runInDumbModeSynchronously`. Asserts outcome is `ResolveOutcome.TemporarilyUnavailable` with `reason == INDEXING` and `outcomeStatus == "TEMPORARILY_UNAVAILABLE"`, explicitly failing if `NOT_FOUND` is returned. | **PASS** |
| **AT-6** | Zero implementation leakage | Resolves a symbol, serializes `ResolveSymbolResult` to JSON using `kotlinx.serialization`, asserts no substring contains `com.intellij.psi`, `org.jetbrains.kotlin.analysis`, `KaSymbol`, or `PsiElement`, and validates lossless roundtrip decoding in `telaio-core`. | **PASS** |

---

## 4. Architectural Findings

### 4.1 Boundary Isolation (ADR-0001 & ADR-0002)
* **Finding**: `telaio-core` has no dependency on `intellijPlatform` or Kotlin compiler internals. Its Gradle build only includes Kotlin standard library and `kotlinx-serialization-json`.
* **Evidence**: [`telaio-core/build.gradle.kts`](../../telaio-core/build.gradle.kts) and import inspection in [`SymbolModels.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/symbol/SymbolModels.kt) and [`ResolveSymbolProtocol.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/symbol/ResolveSymbolProtocol.kt).
* **Assessment**: Conforms completely to the gateway/bridge boundary.

### 4.2 Threading Model & K2 Analysis API Restrictions
* **Finding**: K2 Analysis API (`analyze(element) { ... }`) strictly forbids execution on the Event Dispatch Thread (EDT).
* **Evidence**: In [`JetBrainsSymbolResolver.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsSymbolResolver.kt#L38-L46), any call detected on the EDT is immediately offloaded to `ApplicationManager.getApplication().executeOnPooledThread { doResolveSymbol(request) }`, where `ReadAction.compute` executes safely without blocking or violating K2 analysis rules.
* **Assessment**: Conforms to IntelliJ Platform and K2 threading requirements.

### 4.3 Transaction Handle Isolation
* **Finding**: Handles are generated with `sym:<transactionId>:<hexCounter>` and tracked in a per-transaction map in [`TransactionSymbolRegistry`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/bridge/TransactionSymbolRegistry.kt#L22).
* **Evidence**: Lookups verify `transactions[handle.transactionId]?[handle.value]`. Handles from one transaction cannot resolve or leak into another transaction.
* **Assessment**: Conforms to ADR-0004 and SPEC-0001 BR-2.

---

## 5. Test Quality Findings

* **Real Fixtures vs Mocks**: All semantic resolution tests run against the IntelliJ Platform test fixture framework (`BasePlatformTestCase`) with Kotlin K2 compiler analysis active. No mocks are used for semantic resolution.
* **Negative & Edge Cases**: Verified that out-of-bounds offsets and missing files produce `INVALID_LOCATION`, whitespace/comments produce `NOT_FOUND`, and indexing produces `TEMPORARILY_UNAVAILABLE`.

---

## 6. Forward Compatibility Observations for SPEC-0002 / SPEC-0003

1. **Transaction Lifecycle Cleanup**: In SPEC-0001, `TransactionSymbolRegistry.clearTransaction(transactionId)` is available. When the Gateway transaction state machine is integrated (Phase 0B), it must call `clearTransaction` on `COMMITTED` or `ROLLED_BACK`.
2. **KDoc / Documentation Retrieval**: `extractDocComment` is safely gated on `KtDeclaration.docComment` to prevent unsupported `getChildren()` invocations on synthetic package PSI elements (`KtLightPackage`).
3. **Qualified Identity Format**: Overload formatting includes full parameter types and return type (`com.example.Class.method(Type1, Type2): ReturnType`), providing the exact semantic anchor required for post-mutation re-resolution in SPEC-0003.

---

## 7. Categorized Findings

### Blocking Findings
* **None**.

### Non-Blocking Observations / Recommendations
* **Observation NB-1**: When the authenticated RPC transport (HTTP/Unix socket) is introduced in Phase 0B, `ResolveSymbolRequest` deserialization should pass through the Gateway filter before hitting `JetBrainsSymbolResolver`.

---

## 8. Proposed ADRs

* **No new ADRs required**. The implementation conforms to ADR-0001 through ADR-0005.

---

## 9. Final Determination

**SPEC-0001 is complete, verified, and safe to freeze.**
The codebase is ready for SPEC-0002 (Impact Analysis).
