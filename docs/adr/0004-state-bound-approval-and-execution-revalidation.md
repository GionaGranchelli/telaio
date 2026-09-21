# ADR-0004: Approval Is Bound to Analyzed State and Must Be Revalidated

- **Status:** Accepted
- **Date:** 2026-09-21
- **Decision owners:** Telaio maintainers
- **Related:** `docs/architecture.md`

## Context

Telaio supports asynchronous human approval.

The repository may change between:

1. semantic analysis;
2. policy evaluation;
3. human approval;
4. mutation execution.

Executing an approved operation against a different workspace state creates a time-of-check/time-of-use (TOCTOU) gap. The impact analysis and policy decision may no longer describe the repository being mutated.

## Decision

Approval is bound to:

```text
intent
+
analysis result
+
workspace snapshot
```

Phase 0 workspace snapshots require:

- Git repository;
- clean working tree;
- captured HEAD;
- target semantic identity;
- analysis timestamp.

Immediately before mutation, Telaio enters `REVALIDATING` and confirms:

```text
current HEAD == analyzed HEAD
AND
working tree == CLEAN
AND
target symbol re-resolves
```

If any condition fails, the transaction becomes `STALE`. No mutation authorization is issued.

Final short-lived execution authorization is issued only after successful revalidation.

Phase 0 permits only one mutating transaction per repository at a time via a repository mutation lease.

## Consequences

### Positive

- Prevents stale approvals from mutating changed repositories.
- Makes approval evidence meaningful.
- Reduces race conditions between governed transactions.

### Negative

- Long-running approvals may require re-analysis.
- Repository mutation concurrency is intentionally limited in Phase 0.
- Clean-worktree requirements are restrictive.

## Transaction States

At minimum:

```text
CREATED
ANALYZING
POLICY_EVALUATED
PENDING_APPROVAL
APPROVED
REVALIDATING
STALE
AUTHORIZED
EXECUTING
VERIFYING
COMMITTED
FAILED
ROLLING_BACK
ROLLED_BACK
DENIED
```
