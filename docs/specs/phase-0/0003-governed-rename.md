# SPEC-0003: Governed Semantic Rename

```yaml
status: proposed
phase: 0B
related_adrs:
  - ADR-0001
  - ADR-0002
  - ADR-0003
  - ADR-0004
  - ADR-0005
implementation:
  - telaio-coding-gateway
  - integrations/jetbrains
```

## 1. Purpose

Execute a Kotlin semantic rename as a governed transaction using JetBrains native refactoring, with approval, state revalidation, verification, rollback, and provenance.

## 2. Scope

Phase 0 supports one mutation:

```text
rename_symbol
```

Excluded:

```text
change_signature
safe_delete
move_symbol
raw file editing
generic PSI generation
```

## 3. Preconditions

Before the transaction may proceed to mutation:

```text
repository is Git-managed
working tree is CLEAN
JetBrains backend is available
target symbol resolves
SEMANTIC_RENAME support is sufficient
required impact analysis completed
```

## 4. Inputs / Outputs

### Input

```text
RenameSymbolIntent {
  targetHandle
  newName
}
```

### Terminal outcomes

```text
COMMITTED
DENIED
STALE
ROLLED_BACK
FAILED
PRECONDITION_FAILED
```

Intermediate async state may include:

```text
PENDING_APPROVAL
```

## 5. Behavioral Requirements

### BR-1 — Analyze before mutation

No source mutation occurs before:

- target resolution;
- workspace snapshot;
- impact analysis;
- policy evaluation.

### BR-2 — Approval is asynchronous

If policy requires human approval, return a pending transaction/approval identifier rather than holding an MCP call indefinitely.

### BR-3 — Approval is state-bound

Approval is bound to:

```text
intent
analysis
workspace snapshot
policy decision
```

### BR-4 — Revalidate before authorization

After approval and immediately before execution:

```text
current HEAD == analyzed HEAD
working tree == CLEAN
target re-resolves
```

Failure yields `STALE`.

No mutation authorization is issued for stale state.

### BR-5 — Repository mutation lease

Only one mutating transaction may hold the repository mutation lease.

The lease is acquired before execution and held through commit or rollback completion.

### BR-6 — Native semantic rename

The Bridge executes the rename using supported JetBrains native semantic refactoring infrastructure.

Textual search-and-replace is forbidden as the implementation of `rename_symbol`.

### BR-7 — Synchronization barrier

Before external verification, IDE semantic/document/VFS state must be persisted such that external build/test processes observe the same source state.

### BR-8 — Fresh post-mutation resolution

Post-mutation verification uses fresh semantic resolution.

Do not treat a surviving pre-mutation pointer as proof of correctness.

### BR-9 — Verification

At minimum evaluate:

```text
renamed target resolves
old target no longer resolves as the same declaration
no new required diagnostics failures
required build passes
required tests pass
```

### BR-10 — Rollback

If verification fails, restore only transaction-modified files from the captured base state.

Do not use `git reset --hard`.

After disk rollback, synchronize IntelliJ VFS/Document/PSI state and verify restoration before reporting `ROLLED_BACK`.

### BR-11 — Provenance

The transaction must emit enough evidence to reconstruct:

```text
intent
model/tool invocation where observable
impact
uncertainty
policy
approval
workspace snapshot
revalidation
authorization
exact source mutation
verification
rollback/commit
Git base/result
```

## 6. Failure / Edge Cases

### Dirty working tree

Result:

```text
PRECONDITION_FAILED: DIRTY_WORKTREE
```

### Repository changes while waiting for approval

Result:

```text
STALE
```

No rename occurs.

### Target no longer resolves

Result:

```text
STALE
```

### Indexing begins before execution

If required semantic guarantees are unavailable, execution must not silently downgrade.

Transaction waits, retries, or fails according to policy.

### Verification fails

Transaction enters failure/rollback path.

### Rollback cannot synchronize IDE and filesystem state

Do not report `ROLLED_BACK`.

Report an explicit rollback failure state/error requiring intervention.

## 7. Provenance Requirements

Minimum evidence fields:

```text
traceId
transactionId
intent
base HEAD
workspace state
target identity
impact report
policy decision
approval principal/timestamps
revalidation result
authorization identity
backend capability
files modified
before/after hashes
diff artifact
verification results
terminal transaction result
```

Where model instrumentation exists, correlate:

```text
model/provider/version
observable prompts/responses
tool calls/results
input/output/cache/reasoning tokens where exposed
```

## 8. Acceptance Tests

### AT-1 — Private rename auto-authorized

Given policy permits a low-risk private rename,
the transaction executes without human approval and commits when verification passes.

### AT-2 — Public API requires approval

Given a public API rename,
the transaction enters `PENDING_APPROVAL` according to policy.

### AT-3 — Approval then successful execution

Given approval and unchanged workspace,
revalidation passes,
authorization is issued,
native rename executes,
verification passes,
and transaction becomes `COMMITTED`.

### AT-4 — HEAD drift during approval

Given approval is pending,
when HEAD changes before execution,
then transaction becomes `STALE` and no rename occurs.

### AT-5 — Dirty-tree drift during approval

Given HEAD is unchanged but a tracked file is edited,
revalidation detects a non-clean tree and transaction becomes `STALE`.

### AT-6 — Concurrent mutation

Given transaction A owns the repository mutation lease,
transaction B cannot concurrently execute a semantic mutation.

### AT-7 — Build failure

If rename completes but required build fails,
the transaction enters rollback and restores transaction-modified files.

### AT-8 — Rollback convergence

After rollback,
disk/VFS/Document/PSI state must converge before transaction is marked `ROLLED_BACK`.

### AT-9 — Provenance reconstruction

A completed transaction contains enough correlated evidence to reconstruct the approved analysis, executed mutation, exact diff, and verification result.
