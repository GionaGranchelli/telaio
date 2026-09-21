# SPEC-0002: Semantic Impact Analysis

```yaml
status: proposed
phase: 0A
related_adrs:
  - ADR-0001
  - ADR-0003
implementation:
  - integrations/jetbrains
  - telaio-coding-gateway
```

## 1. Purpose

Produce a compact, policy-usable impact report for a resolved semantic symbol while preserving uncertainty honestly.

## 2. Scope

Phase 0 impact analysis covers project-visible semantic relationships available from the JetBrains backend.

It must not imply knowledge of dependencies the backend cannot observe.

## 3. Preconditions

- A valid transaction-scoped symbol handle exists.
- The JetBrains backend can perform the required semantic searches.
- Required indexes are available.

If required analysis is temporarily blocked by indexing, return temporary unavailability rather than an incomplete report falsely presented as complete.

## 4. Inputs / Outputs

### Input

```text
ImpactAnalysisRequest {
  transactionId
  symbolHandle
}
```

### Output

Conceptually:

```text
ImpactReport {
  target
  scope
  semanticImpact
  dynamicImpact
  completeness
}
```

Minimum semantic impact fields:

```text
observedUsages
affectedFiles
affectedModules
callers
implementations
overrides
```

Minimum uncertainty fields where applicable:

```text
stringLiteralCandidates
reflectionRisk
frameworkReferenceCoverage
generatedCodeCoverage
externalConsumerCoverage
```

## 5. Behavioral Requirements

### BR-1 — Use observed terminology

Usage counts are named `observedUsages` unless the backend can formally establish completeness for the declared scope.

### BR-2 — Scope is mandatory

Every impact report declares its search/analysis scope.

Conceptual values:

```text
LOCAL
MODULE
PROJECT
WORKSPACE
EXTERNAL_UNKNOWN
```

### BR-3 — Completeness is mandatory

Every report declares:

```text
COMPLETE
PARTIAL
UNKNOWN
```

Completeness must be interpreted relative to the declared scope.

### BR-4 — Unknown is preserved

Unknown coverage must remain `UNKNOWN`.

It must not be serialized or normalized as:

```text
0
false
NONE
complete
```

### BR-5 — Public/external uncertainty

For a public API where external consumers cannot be established, external-consumer coverage must be `UNKNOWN`.

### BR-6 — Backend support is visible

If a component of impact analysis is only best-effort, the result must preserve that support level or uncertainty rather than presenting it as native semantic certainty.

## 6. Failure / Edge Cases

Structured outcomes include at least:

```text
IMPACT_READY
TEMPORARILY_UNAVAILABLE
STALE_SYMBOL_HANDLE
UNSUPPORTED
BACKEND_ERROR
```

If the symbol handle cannot be resolved in the current transaction context, do not produce an impact report for another symbol based on text-name fallback.

## 7. Provenance Requirements

Record:

```text
traceId
transactionId
toolCallId
target symbol
analysis scope
completeness
observed counts
uncertainty fields
backend capability levels
analysis duration
```

The evidence record must be able to reproduce the impact values on which a policy decision was based.

## 8. Acceptance Tests

### AT-1 — Project usage count

Given a symbol used across multiple Kotlin files,
the report contains the semantically resolved usage count as `observedUsages`.

### AT-2 — Cross-module impact

Where the fixture spans modules,
affected modules are reported distinctly.

### AT-3 — External consumers unknown

Given a public API and no mechanism proving external consumer absence,
`externalConsumerCoverage = UNKNOWN`.

### AT-4 — Completeness does not overclaim

A report containing unknown dynamic/reference categories cannot be marked `COMPLETE` at a scope that depends on those categories.

### AT-5 — Unknown is not zero

Serialization preserves `UNKNOWN` as a distinct value.

### AT-6 — Indexing state

If required indexes are unavailable,
return `TEMPORARILY_UNAVAILABLE` and do not emit a misleading zero-impact report.

### AT-7 — Overloaded target

Impact analysis for one overload includes references to that overload, not merely all textual occurrences of the shared name.
