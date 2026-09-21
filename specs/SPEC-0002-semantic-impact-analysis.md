# SPEC-0002: Semantic Impact Analysis

```yaml
id: SPEC-0002
title: Semantic Impact Analysis
status: proposed
phase: 0A
related_adrs:
  - ADR-0001
  - ADR-0002
  - ADR-0003
  - ADR-0005
upstream:
  - SPEC-0001
downstream:
  - SPEC-0003
implementation:
  - telaio-core
  - integrations/jetbrains
  - telaio-coding-gateway
```

---

## 1. Purpose

Given a valid transaction-scoped symbol handle produced by SPEC-0001 (`sym:<transactionId>:<id>`), compute a deterministic, structured, evidence-based **Impact Report** describing the semantic blast radius of modifying the target symbol.

The core principle of SPEC-0002 is **honest, first-class uncertainty**:
$$\text{UNKNOWN} \neq 0 \quad\text{and}\quad \text{OBSERVED} \neq \text{TOTAL}$$

The system explicitly separates:
1. **Semantic Evidence**: Formally proven references and hierarchy relationships discovered within the project;
2. **Heuristic & Dynamic Candidates**: Textual or string-literal matches that cannot be semantically proven;
3. **Scope-Bound Completeness**: Whether analysis completed exhaustively *within the evaluated scope*;
4. **Analysis Limitations**: Explicit representation of unobservable vectors (e.g., external consumers, reflection, indexing).

---

## 2. Scope

### 2.1 Included in Phase 0 (SPEC-0002)

* **Target Symbols**: Any resolved Kotlin declaration handle from SPEC-0001 (functions, methods, classes, properties, constructors, parameters).
* **Direct Usages & References**: All call sites, property accesses, constructor invocations, type references, and import occurrences within the project workspace.
* **Overloaded Target Discrimination**: Reference search scoped strictly to the specific callable signature identified by the handle, excluding sibling overloads with the same name.
* **Hierarchy Relationships**:
  * Direct overrides (methods overriding the target member);
  * Overridden base declarations (methods/interfaces the target implements or overrides);
  * Direct sub-types and interface implementations.
* **Cross-File & Cross-Module Boundaries**: Discovery across all source modules within the IntelliJ project.
* **Structured Semantic Evidence**: File, line, column, offset, length, enclosing declaration, and relation type for each observed usage.
* **Heuristic Candidates Separation**: Distinct collection for string literal occurrences and textual candidate matches, preventing them from masquerading as semantic relationships.
* **Deterministic Aggregation**: Blast radius metrics (`observedUsageCount`, `affectedFilesCount`, `affectedModulesCount`, list of distinct files and modules).
* **Scope-Bound Completeness & Limitations**: Formal representation of intra-scope completeness, indexing status, unverifiable external consumers, reflection risk, and string literal candidate presence.

### 2.2 Explicitly Deferred to Later Phases

* **Recursive Transitive Call Graphs**: Transitive call chains beyond the direct 1-hop blast radius and direct hierarchy relationships (deferred to prevent combinatorial explosion; 1-hop + hierarchy is sufficient for Phase 0 Governed Rename).
* **Multi-Repository Downstream Analysis**: Analyzing consumers in external unlinked repositories.
* **Bytecode/Decompiled Library Rewriting**: Mutating binary dependencies (read-only references to binaries are reported as external boundaries).
* **Refactoring & Patch Execution**: Governed mutation belongs to SPEC-0003.
* **Mutation Policy Decisions**: Determining auto-approval vs human escalation thresholds belongs to the governance policy layer.

---

## 3. Analysis Boundary & Workspace Model

The impact search is evaluated against well-defined physical and logical boundaries:

```text
┌─────────────────────────────────────────────────────────────┐
│ Project Boundary (IntelliJ Project)                         │
│                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐  │
│  │ Source Module A         │   │ Source Module B         │  │
│  │  - Kotlin Sources       │   │  - Kotlin Sources       │  │
│  │  - Direct Usages [PROVEN│   │  - Direct Usages [PROVEN│  │
│  │  - Overrides    [PROVEN]│   │  - Implements  [PROVEN] │  │
│  └─────────────────────────┘   └─────────────────────────┘  │
│               │                             │               │
│               ▼                             ▼               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ String Candidates & Comments [HEURISTIC_CANDIDATE]    │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ External / Unobserved Boundary [Completeness: UNKNOWN]      │
│  - External Consumers (Downstream repos / consumers)        │
│  - Reflection / Dynamic Invocations                         │
│  - Configuration / Framework Wiring                         │
└─────────────────────────────────────────────────────────────┘
```

### Executable Analysis Scopes (`AnalysisScope`)

Every `AnalysisScope` represents an actual executable search boundary supported by the JetBrains backend:

* `LOCAL`: Scoped strictly to the enclosing file, class, or local block.
* `MODULE`: Search scope limited to the owning compilation module and its dependent modules.
* `PROJECT`: Entire IntelliJ project across all configured source roots and modules.

---

## 4. Preconditions & Invariants

1. **Valid Transaction Context**: The `symbolHandle` must match the active `transactionId` and exist in `TransactionSymbolRegistry`.
2. **Fresh Resolved Target**: The underlying PSI element must remain valid (`smartPointer.element != null`).
3. **Index Availability**: JetBrains backend must not be in dumb mode. If indexing is active, return `TEMPORARILY_UNAVAILABLE: INDEXING`.
4. **Read-Action & Background Threading**: Impact analysis must run on background worker threads within an IntelliJ read action, never on the EDT.
5. **No Implementation Leakage**: No `PsiReference`, `PsiElement`, `SmartPsiElementPointer`, or K2 compiler types may escape `integrations/jetbrains`.

---

## 5. Domain Models & Protocol Contracts (`telaio-core`)

All models are immutable Kotlin data classes with `@Serializable` support.

### 5.1 Request DTO

```kotlin
@Serializable
data class ImpactAnalysisRequest(
    val transactionId: String,
    val symbolHandle: SymbolHandle,
    val scope: AnalysisScope = AnalysisScope.PROJECT,
    val includeCandidates: Boolean = true,
    val traceId: String? = null,
    val toolCallId: String? = null
)
```

### 5.2 Scope & Completeness Model

```kotlin
@Serializable
enum class AnalysisScope {
    LOCAL,      // Enclosing file / class / local block
    MODULE,     // Owning module
    PROJECT     // Complete IntelliJ project workspace
}

@Serializable
enum class ImpactCompleteness {
    COMPLETE,   // Analysis completely and exhaustively executed within evaluatedScope
    PARTIAL,    // Analysis executed partially (e.g. bounded search or timeout within evaluatedScope)
    UNKNOWN     // Analysis within evaluatedScope could not establish completeness
}
```

### 5.3 Semantic Relationships vs Heuristic Candidates

```kotlin
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
```

### 5.4 Limitations & Dynamic Risk Assessment

```kotlin
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
    val reflectionRisk: Boolean,
    val stringLiteralCandidatesFound: Int = 0,
    val frameworkAnnotationWiringSuspected: Boolean = false,
    val externalConsumerCoverage: ImpactCompleteness = ImpactCompleteness.UNKNOWN
)
```

### 5.5 Blast Radius Summary & Impact Report

```kotlin
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
    val heuristicCandidates: List<DiscoveredHeuristicCandidate>,
    val dynamicRisk: DynamicRiskAssessment,
    val limitations: List<AnalysisLimitation>
)
```

### 5.6 Response Protocol & Outcomes

```kotlin
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
```

### 5.7 Core Analyzer Interface

```kotlin
package dev.telaio.core.impact

interface SemanticImpactAnalyzer {
    fun analyzeImpact(request: ImpactAnalysisRequest): ImpactAnalysisResult
}
```

---

## 6. Behavioral Requirements

### BR-1 — Observed Usage Terminology
All usage counts must be exposed as `observedUsageCount` in [`BlastRadius`](#55-blast-radius-summary--impact-report). Reports must never assert an unqualified `totalUsages` unless completeness is proven `COMPLETE`.

### BR-2 — Scope-Bound Completeness Definition
`ImpactCompleteness` evaluates strictly whether the backend completed its supported analysis *within the `evaluatedScope`*. If search completed across all files in the project without truncation or error, `completeness = COMPLETE` for `AnalysisScope.PROJECT`.

### BR-3 — External Uncertainty Is Recorded in Limitations & Dynamic Risk
Uncertainty regarding external consumers outside the project boundary must **not** downgrade an otherwise complete `PROJECT` analysis to `UNKNOWN`. Instead, external uncertainty must be explicitly recorded in `dynamicRisk.externalConsumerCoverage = ImpactCompleteness.UNKNOWN` and `limitations` (with `LimitationCode.EXTERNAL_CONSUMERS_UNVERIFIABLE`).

### BR-4 — Strict Separation of Semantic Evidence from Heuristic Candidates
Textual coincidences (such as string literals matching a symbol name) must **not** be included in `semanticRelationships` or count toward `observedUsageCount`. They must be recorded in `heuristicCandidates` as `DiscoveredHeuristicCandidate`.

### BR-5 — Overload Discrimination in Reference Search
Impact analysis for an overloaded method (e.g. `foo(String)`) must query references using the exact `PsiElement` resolved in SPEC-0001. Call sites invoking sibling overloads (e.g. `foo(Int)`) must not be reported as references to `foo(String)`.

### BR-6 — Hierarchy Relationship Classification
Direct method call sites, overrides (derived methods implementing/overriding the target), and base declarations (super-methods) must be classified under their distinct [`SemanticRelationKind`](#53-semantic-relationships-vs-heuristic-candidates).

### BR-7 — Deterministic Ordering
Discovered semantic relationships and heuristic candidates must be sorted deterministically before emission:
1. `sourceLocation.file` ascending (case-insensitive lexical);
2. `sourceLocation.offset` ascending (nulls last);
3. `relationKind` / `candidateKind` ascending.

### BR-8 — Zero Implementation Leakage
Serialized outputs must contain only pure data types from `telaio-core`. No `PsiElement`, `PsiReference`, or K2 internal instances may cross the bridge.

### BR-9 — Dumb Mode Protection
If `DumbService.isDumb(project)` is true, the analyzer must immediately return `TEMPORARILY_UNAVAILABLE: INDEXING` without performing partial searches.

### BR-10 — Stale Handle Detection
If the `SymbolHandle` is not found in the transaction registry, or if the underlying `SmartPsiElementPointer` has become null/invalid, the analyzer must return `STALE_SYMBOL_HANDLE` and must never fall back to textual name searches.

### BR-11 — Threading Conformance
Long-running semantic searches must execute inside background read actions off the EDT. If invoked on the EDT, execution must be dispatched to application pooled worker threads.

### BR-12 — Evidence Reporting, Not Policy Enforcement
SPEC-0002 must exclusively produce objective semantic and risk evidence. It must not make or prescribe governance decisions (such as approval requirements or refactoring thresholds).

---

## 7. Failure & Edge Cases

| Condition | Required Outcome | Specific Fields |
| :--- | :--- | :--- |
| Indexing in progress | `TEMPORARILY_UNAVAILABLE` | `reason = INDEXING` |
| Handle from different transaction | `STALE_SYMBOL_HANDLE` | `handle` |
| Element deleted / PSI invalidated | `STALE_SYMBOL_HANDLE` | `handle` |
| Private member with 0 usages | `IMPACT_READY` | `observedUsageCount = 0`, `completeness = COMPLETE`, `semanticRelationships = []` |
| Public member with 0 internal usages | `IMPACT_READY` | `observedUsageCount = 0`, `completeness = COMPLETE` (within `PROJECT`), `dynamicRisk.externalConsumerCoverage = UNKNOWN`, `limitations = [EXTERNAL_CONSUMERS_UNVERIFIABLE]` |
| Non-Kotlin element target | `UNSUPPORTED_SYMBOL` | `reason` |
| Unexpected backend exception | `BACKEND_ERROR` | `message`, `details` |

---

## 8. Acceptance Test Matrix

| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **AT-1** | **Direct Function Usages** | Given a top-level function with 3 call sites across 2 files, returns `observedUsageCount = 3`, `affectedFilesCount = 2`, and 3 `CALL_SITE` relationships with `certainty = SEMANTICALLY_PROVEN`. |
| **AT-2** | **Overload Discrimination** | Given `foo(String)` and `foo(Int)`, impact analysis for `foo(String)` returns only references passing string arguments, with 0 references to `foo(Int)`. |
| **AT-3** | **Cross-Module Impact** | Given a library module declaring a class and an app module consuming it, returns `affectedModulesCount = 2` with both module names listed in `blastRadius.affectedModules`. |
| **AT-4** | **Override & Subtype Discovery** | Given an interface method and 2 implementing classes, returns 2 relationships with `relationKind = IMPLEMENTATION` and `certainty = SEMANTICALLY_PROVEN`. |
| **AT-5** | **Property & Accessor Usages** | Given a Kotlin property, returns all read and write reference locations as `PROPERTY_ACCESS`. |
| **AT-6** | **Zero Usage Private Member** | Given an unused private helper function, returns `observedUsageCount = 0`, `completeness = COMPLETE`, empty `semanticRelationships`, and empty `limitations`. |
| **AT-7** | **Public API External Uncertainty** | Given a public API method with 0 internal usages, returns `observedUsageCount = 0`, `completeness = COMPLETE` (for `PROJECT` scope), `dynamicRisk.externalConsumerCoverage = UNKNOWN`, and `limitations` containing `EXTERNAL_CONSUMERS_UNVERIFIABLE`. |
| **AT-8** | **Indexing Unavailable** | Given indexing is active, returns `TEMPORARILY_UNAVAILABLE: INDEXING` and does NOT return a false zero-usage report. |
| **AT-9** | **Stale Handle Rejection** | Given an un-registered handle or handle from a closed transaction, returns `STALE_SYMBOL_HANDLE`. |
| **AT-10** | **Heuristic String Candidate Separation** | Given symbol name appearing inside a string literal, places the occurrence in `heuristicCandidates` (not `semanticRelationships`) and increments `dynamicRisk.stringLiteralCandidatesFound` without altering `observedUsageCount`. |
| **AT-11** | **Deterministic Ordering** | Given unordered usage results from search queries, emitted relationship and candidate lists are deterministically sorted by file URI, offset, and relation/candidate kind. |
| **AT-12** | **Zero Implementation Leakage** | Full JSON serialization roundtrip of `ImpactAnalysisResult` verifies no `com.intellij.*` or `Ka*` class names appear in the payload. |

---

## 9. Integration Contracts

### 9.1 Upstream: SPEC-0001 (Symbol Resolution)
* SPEC-0002 consumes `SymbolHandle` directly from SPEC-0001 `ResolveSymbolResult`.
* Bridge retrieves the `SmartPsiElementPointer<PsiElement>` from `TransactionSymbolRegistry` using `registry.get(handle)`.

### 9.2 Downstream: Governance & SPEC-0003 (Governed Rename) Evidence Contract
* SPEC-0002 delivers objective evidence to the Gateway:
  * **Blast Radius Metrics**: `observedUsageCount`, `affectedFilesCount`, `affectedModulesCount`, list of distinct files/modules;
  * **Scope-Bound Completeness**: `completeness` (`COMPLETE` vs `PARTIAL` vs `UNKNOWN`);
  * **Semantic Evidence**: Exact list of proven call sites, property accesses, and overrides;
  * **Heuristic Candidates**: Textual/string matches requiring potential human review;
  * **Dynamic Risk & Limitations**: Explicit flags for reflection risk and unverifiable external consumers.
* SPEC-0003 and the Governance layer consume this structured evidence to evaluate organization-specific policy rules and determine authorization requirements.

---

## 10. Architectural Assessment

### 10.1 ADR Consistency Check
* **ADR-0001 (JetBrains First Backend)**: Conforms — Uses IntelliJ `ReferencesSearch` and `OverridingMethodsSearch` through the bridge.
* **ADR-0002 (Gateway / Bridge Separation)**: Conforms — `SemanticImpactAnalyzer` interface in `telaio-core`, implementation in `integrations/jetbrains`.
* **ADR-0003 (Impact Uncertainty Is First-Class)**: Conforms — Implements formal `ImpactCompleteness`, `AnalysisLimitation`, `DynamicRiskAssessment`, and `observedUsageCount`.
* **ADR-0005 (Provenance Is First-Class)**: Conforms — Structured `ImpactProvenance` records duration, transaction ID, toolCallId, and target handle.

### 10.2 Proposed ADRs
* **No new ADRs required**. The corrections refine domain precision within existing architectural boundaries established by ADR-0001 through ADR-0005.
