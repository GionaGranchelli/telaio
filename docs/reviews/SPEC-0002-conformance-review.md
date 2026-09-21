# SPEC-0002 Conformance & Architecture Review

- **Target Specification:** [`docs/specs/phase-0/0002-impact-analysis.md`](../specs/phase-0/0002-impact-analysis.md)
- **Status:** Complete / Approved
- **Date:** 2026-09-21
- **Review Mode:** Adversarial Conformance & Boundary Verification
- **Reviewed Modules:** `telaio-core`, `integrations/jetbrains`

---

## 1. Executive Summary

This review assesses the implementation of **SPEC-0002: Semantic Impact Analysis** against the canonical architecture ([`docs/architecture.md`](../architecture.md)), binding Architecture Decision Records (ADR-0001 through ADR-0005), and the explicit acceptance criteria in frozen SPEC-0002.

Following an independent conformance review and subsequent correction pass, all blocking items have been resolved:
1. **Real Cross-Module Testing (AT-3)**: Implemented via a genuine multi-module fixture in `JetBrainsMultiModuleImpactTest` with explicit project-dependency configuration, verifying module blast radius across library and consumer modules.
2. **Layering & Cross-Transaction Stale Handle Outcome (AT-9)**: DTO construction permits valid transaction strings while semantic mismatch is evaluated inside `JetBrainsImpactAnalyzer`, producing structured `STALE_SYMBOL_HANDLE` outcomes across unregistered, mismatched, and cleared transactions.
3. **Upward Hierarchy Discovery (BASE_DECLARATION)**: Implemented via K2 Analysis API `KaCallableSymbol.directlyOverriddenSymbols` and light method `findSuperMethods(false)` to distinguish super interface/base declarations from derived implementations/overrides.
4. **MODULE Scope Definition**: Formally unified and clarified as `owning module + dependent modules` (`GlobalSearchScope.moduleWithDependentsScope`), supported by dedicated tests.

**Verdict:** **PASS (Safe to Freeze)**.

---

## 2. Requirements Traceability Matrix

| Requirement | Description | Implementation Target | Verification Test | Status |
| :--- | :--- | :--- | :--- | :--- |
| **BR-1** | **Observed Usage Terminology**: Expose counts as `observedUsageCount` in `BlastRadius`, never claiming unqualified global totals. | [`ImpactModels.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/impact/ImpactModels.kt#L84-L92), [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L231-L245) | [`ImpactModelsTest`](../../telaio-core/src/test/kotlin/dev/telaio/core/impact/ImpactModelsTest.kt), [`JetBrainsImpactAnalyzerTest.testAT1DirectFunctionUsages`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L42-L93) | **PASS** |
| **BR-2** | **Scope-Bound Completeness**: `ImpactCompleteness.COMPLETE` reflects complete search within `evaluatedScope` (`LOCAL`, `MODULE`, `PROJECT`). | [`ImpactModels.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/impact/ImpactModels.kt#L14-L18), [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L246-L255) | [`JetBrainsImpactAnalyzerTest.testAT1DirectFunctionUsages`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L42-L93) | **PASS** |
| **BR-3** | **External Uncertainty in Dynamic Risk & Limitations**: External consumers outside the project are recorded in `dynamicRisk.externalConsumerCoverage = UNKNOWN` and `AnalysisLimitation(EXTERNAL_CONSUMERS_UNVERIFIABLE)`. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L187-L208) | [`JetBrainsImpactAnalyzerTest.testAT7PublicApiExternalUncertainty`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L274-L310) | **PASS** |
| **BR-4** | **Candidate Separation**: Textual matches in string literals are segregated into `heuristicCandidates` and do not inflate `observedUsageCount`. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L465-L500) | [`JetBrainsImpactAnalyzerTest.testAT10HeuristicStringCandidateSeparation`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L430-L469) | **PASS** |
| **BR-5** | **Overload Discrimination**: Impact analysis for overloaded callables queries references for the exact resolved declaration. | Native IntelliJ `ReferencesSearch` on `targetElement` | [`JetBrainsImpactAnalyzerTest.testAT2OverloadDiscrimination`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L95-L141) | **PASS** |
| **BR-6** | **Hierarchy Relationship Classification**: Separate `CALL_SITE`, `PROPERTY_ACCESS`, `TYPE_REFERENCE`, `OVERRIDE`, `IMPLEMENTATION`, `BASE_DECLARATION`, `IMPORT`, and `DOC_REFERENCE`. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L286-L463) | [`JetBrainsImpactAnalyzerTest.testAT4OverrideAndSubtypeDiscovery`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L145-L188), [`testBaseDeclarationDiscovery`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L388-L425) | **PASS** |
| **BR-7** | **Deterministic Ordering**: Lists sorted by `sourceLocation.file` (case-insensitive), `sourceLocation.offset` ascending, and relation kind ascending. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L210-L225) | [`JetBrainsImpactAnalyzerTest.testAT11DeterministicOrdering`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L471-L513) | **PASS** |
| **BR-8** | **Zero Implementation Leakage**: Serialized outputs contain pure Kotlin data classes from `telaio-core` with no IDE/compiler internals. | [`ImpactModels.kt`](../../telaio-core/src/main/kotlin/dev/telaio/core/impact/ImpactModels.kt) | [`JetBrainsImpactAnalyzerTest.testAT12ZeroImplementationLeakage`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L515-L557) | **PASS** |
| **BR-9** | **Dumb Mode Protection**: Returns `TEMPORARILY_UNAVAILABLE: INDEXING` immediately if `DumbService.isDumb(project)` is true. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L52-L73) | [`JetBrainsImpactAnalyzerTest.testAT8IndexingUnavailable`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L312-L340) | **PASS** |
| **BR-10** | **Stale Handle Detection**: Unregistered, cross-transaction, or cleared handles return `STALE_SYMBOL_HANDLE` with zero fallback to text names. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L74-L95) | [`JetBrainsImpactAnalyzerTest.testAT9StaleHandleRejection`](../../integrations/jetbrains/src/test/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzerTest.kt#L342-L386) | **PASS** |
| **BR-11** | **Threading Conformance**: Background read actions run off the EDT; UI thread invocations are dispatched to pooled worker threads. | [`JetBrainsImpactAnalyzer.kt`](../../integrations/jetbrains/src/main/kotlin/dev/telaio/intellij/k2/JetBrainsImpactAnalyzer.kt#L38-L47) | Executed in pooled thread dispatched test harness | **PASS** |
| **BR-12** | **Evidence Reporting, Not Governance**: Produces structured objective evidence without prescribing approval requirements or refactoring actions. | Architecture & Domain model separation | No governance policy rules encoded in `telaio-core` or analyzer | **PASS** |

---

## 3. Acceptance Tests Evaluation

| Test ID | Scenario | Verification Method | Result |
| :--- | :--- | :--- | :---: |
| **AT-1** | Direct Function Usages | Top-level function with 3 call sites across 2 files discovered with `observedUsageCount = 3`, `affectedFilesCount = 2`, and 3 `CALL_SITE` relationships (`certainty = SEMANTICALLY_PROVEN`). | **PASS** |
| **AT-2** | Overload Discrimination | Function with string vs integer overloads verifies that analysis for `handle(String)` discovers only 2 string argument call sites and 0 integer call sites. | **PASS** |
| **AT-3** | Cross-Module Impact | Multi-module test fixture (`JetBrainsMultiModuleImpactTest`) with library module and dependent consumer module verifies `affectedModulesCount = 2`, `affectedModules` contains both module names, and consumer file is tracked. | **PASS** |
| **AT-4** | Override & Subtype Discovery | Interface method with 2 concrete implementations yields `implementationCount = 2` with `relationKind = IMPLEMENTATION` (`certainty = SEMANTICALLY_PROVEN`). | **PASS** |
| **AT-5** | Property & Accessor Usages | Mutable property read and write references classified as `PROPERTY_ACCESS`. | **PASS** |
| **AT-6** | Zero Usage Private Member | Unused private function yields `observedUsageCount = 0`, `completeness = COMPLETE`, empty relationships, and empty limitations. | **PASS** |
| **AT-7** | Public API External Uncertainty | Public API declaration with 0 internal usages returns `observedUsageCount = 0`, `completeness = COMPLETE` within `PROJECT`, `dynamicRisk.externalConsumerCoverage = UNKNOWN`, and `limitations = [EXTERNAL_CONSUMERS_UNVERIFIABLE]`. | **PASS** |
| **AT-8** | Indexing Unavailable | When `DumbService.isDumb(project)` is active, returns `TEMPORARILY_UNAVAILABLE: INDEXING` and avoids false zero-usage outputs. | **PASS** |
| **AT-9** | Stale Handle Rejection | Tested 3 independent sub-cases: (1) unregistered handle, (2) handle from transaction A used with request transaction B, (3) handle whose transaction was cleared. All produce `STALE_SYMBOL_HANDLE`. | **PASS** |
| **AT-10** | Heuristic String Candidate Separation | Textual match in string literal is isolated in `heuristicCandidates`, leaving `observedUsageCount = 1` (the semantic call) and setting `dynamicRisk.stringLiteralCandidatesFound = 1`. | **PASS** |
| **AT-11** | Deterministic Ordering | Validates that relationship and candidate outputs are deterministically sorted by file URI, offset ascending, and relationship kind. | **PASS** |
| **AT-12** | Zero Implementation Leakage | Full JSON serialization roundtrip in `telaio-core` verifies zero IDE/compiler class names in JSON payload. | **PASS** |

---

## 4. Architectural & Conformance Findings

### 4.1 Boundary Isolation (ADR-0001 & ADR-0002)
- **`telaio-core`**: Contains only pure domain types (`SemanticImpactAnalyzer`, `ImpactAnalysisRequest`, `ImpactAnalysisResult`, `ImpactReport`, `BlastRadius`, `DiscoveredSemanticRelationship`, `DiscoveredHeuristicCandidate`, `DynamicRiskAssessment`, `AnalysisLimitation`). It has **zero dependencies** on IntelliJ SDK, PSI, or Kotlin compiler internals.
- **`integrations/jetbrains`**: Contains `JetBrainsImpactAnalyzer`, implementing the core interface using native IntelliJ search indices (`ReferencesSearch`, `DefinitionsScopedSearch`, `OverridingMethodsSearch`, and `PsiSearchHelper`).

### 4.2 Upward Hierarchy Discovery (`BASE_DECLARATION`)
- Upward hierarchy discovery utilizes K2 Analysis API `KaCallableSymbol.directlyOverriddenSymbols` and light method `findSuperMethods(false)` to discover base declarations (e.g. interface or abstract superclass methods) when analyzing concrete implementations.
- Output is classified as `SemanticRelationKind.BASE_DECLARATION` with `certainty = SEMANTICALLY_PROVEN`.

### 4.3 Cancellation Exception Safety
- `JetBrainsImpactAnalyzer` explicitly checks for `com.intellij.openapi.progress.ProcessCanceledException` and `java.util.concurrent.CancellationException` and rethrows them directly, preventing IntelliJ progress cancellations from being converted into `ImpactOutcome.BackendError`.

### 4.4 Dynamic Risk Semantics
- `DynamicRiskAssessment` fields (`reflectionRisk`, `frameworkAnnotationWiringSuspected`) default to `false`, which indicates that no static heuristic evidence was observed during analysis—not a formal proof of absence of runtime dynamic behaviors. This contract is explicitly documented on the data class.

---

## 5. Non-Blocking Observations / Follow-up Notes

1. **Callable Reference Classification**: Callable references (e.g. `::func` represented by `KtCallableReferenceExpression`) are classified as `CALL_SITE` in Phase 0. If finer distinction is required in Phase 1 (e.g. `CALLABLE_REFERENCE`), it can be added without breaking the existing contract.
2. **Multi-Module Search Invariants**: `AnalysisScope.MODULE` uses `GlobalSearchScope.moduleWithDependentsScope`, allowing symbols declared in upstream library modules to discover usages in downstream consumer modules within the project workspace.

---

## 6. Proposed ADRs

- **No new ADRs required**. The implementation conforms strictly to ADR-0001 through ADR-0005.

---

## 7. Final Determination

**SPEC-0002 is complete, verified, and safe to freeze.**
All acceptance criteria are tested and pass under Gradle `check`.
The repository is in a clean, tested state ready for SPEC-0003 design.
