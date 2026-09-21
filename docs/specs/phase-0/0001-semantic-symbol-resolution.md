# SPEC-0001: Semantic Symbol Resolution

```yaml
status: proposed
phase: 0A
related_adrs:
  - ADR-0001
  - ADR-0002
implementation:
  - integrations/jetbrains
```

## 1. Purpose

Resolve a Kotlin source position to a stable transaction-scoped semantic handle and compact symbol description using JetBrains/K2 semantics.

This is the foundational read capability for Phase 0.

## 2. Scope

Included:

- Kotlin source;
- K2 semantic frontend;
- named declarations that can be reliably identified;
- functions/methods, classes, properties, and similar named symbols;
- overloaded callables when semantic identity can distinguish them.

Excluded initially:

- declarations whose identity cannot be reliably re-established;
- unsupported/non-Kotlin languages;
- textual name search presented as semantic resolution.

## 3. Preconditions

The JetBrains backend must be:

- connected;
- project-loaded;
- semantic analysis available;
- not in a state where required indexes are unavailable.

If indexing prevents semantic resolution, return a temporary-unavailability result rather than `NOT_FOUND`.

## 4. Inputs / Outputs

### Input

Conceptually:

```text
ResolveSymbolRequest {
  transactionId
  file
  offset | line+column
}
```

### Output

Successful resolution returns:

```text
SymbolRef {
  handle
  name
  kind
  qualifiedIdentity
  visibility
  module
  sourceLocation
}
```

The external handle is opaque and transaction-scoped:

```text
sym:<transactionId>:<id>
```

No JetBrains/K2 implementation object crosses the Bridge boundary.

## 5. Behavioral Requirements

### BR-1 — Semantic resolution

Resolution must use JetBrains/K2 semantic APIs, not text matching.

### BR-2 — Transaction-scoped handle

Every successful result receives an opaque handle valid only in the active transaction context.

### BR-3 — Overload discrimination

Where K2 can distinguish overloaded declarations, the symbol identity must preserve that distinction using semantic signature/owner/receiver information.

Do not collapse:

```text
foo(String)
foo(Int)
```

into the same identity.

### BR-4 — No stale-identity guarantee

The returned handle does not guarantee that the underlying PSI/K2 object remains valid after mutation.

Mutation workflows must fresh-resolve post-mutation state.

### BR-5 — Temporary unavailability

If indexing/dumb mode prevents reliable resolution, return:

```text
TEMPORARILY_UNAVAILABLE
reason = INDEXING
```

Do not return:

```text
NOT_FOUND
```

unless semantic resolution actually completed and found no resolvable symbol.

## 6. Failure / Edge Cases

Required structured outcomes include at least:

```text
RESOLVED
NOT_FOUND
AMBIGUOUS
UNSUPPORTED_SYMBOL
TEMPORARILY_UNAVAILABLE
INVALID_LOCATION
BACKEND_ERROR
```

Ambiguity must be explicit.

`null` must not be used to collapse distinct outcomes.

## 7. Provenance Requirements

Record at minimum:

```text
traceId
transactionId
toolCallId
source file
source location
resolution outcome
backend
capability support level
resolved symbol handle when successful
duration
```

Do not persist raw PSI/K2 objects.

## 8. Acceptance Tests

### AT-1 — Resolve private member

Given a Kotlin private method declaration,
when resolving the declaration position,
then the result identifies that exact method.

### AT-2 — Resolve reference to declaration

Given a call site,
when resolving the referenced symbol,
then the result identifies the declaration that the call resolves to.

### AT-3 — Distinguish overloads

Given:

```kotlin
fun foo(value: String)
fun foo(value: Int)
```

when resolving each declaration and matching call site,
then the identities remain distinct.

### AT-4 — Cross-file resolution

Given a symbol declared in file A and referenced in file B,
then resolving the reference in file B identifies the declaration from file A.

### AT-5 — Indexing state

Given semantic resolution is unavailable due to indexing,
then the result is `TEMPORARILY_UNAVAILABLE: INDEXING`,
not `NOT_FOUND`.

### AT-6 — No implementation leakage

Serialized output contains no PSI/K2 object reference or implementation-specific JVM identity.
