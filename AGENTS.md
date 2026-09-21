# Telaio — Agent Instructions

Telaio is a governed semantic execution layer for AI coding agents.

Its purpose is **not** to build another coding assistant, chat UI, semantic index, code graph, or generic IDE automation layer. Telaio sits between coding agents and trusted semantic development tooling so consequential code mutations can be analyzed, governed, authorized, verified, and reconstructed afterward.

The canonical architecture is documented in:

`docs/architecture.md`

Read that document before making architectural or cross-module changes.

---

## 1. Core Mental Model

Telaio separates four authorities:

- **LLM authority** — decides what should be attempted.
- **Semantic authority** — determines what the program means and performs semantic operations.
- **Governance authority** — decides whether an operation is allowed and under what conditions.
- **Verification authority** — determines whether the resulting state satisfies required postconditions.

For the initial implementation:

- JetBrains/K2 is the semantic authority.
- Telaio is the governance, transaction, verification-orchestration, and provenance authority.
- The coding agent is replaceable.

The intended flow is:

```text
Human request
    ↓
Agent intent
    ↓
Semantic analysis
    ↓
Impact report + uncertainty
    ↓
Policy
    ↓
Approval if required
    ↓
Workspace revalidation
    ↓
Authorization
    ↓
Native semantic mutation
    ↓
Verification
    ↓
Commit or rollback
    ↓
Provenance + evidence
```

Do not collapse these responsibilities into one layer.

---

## 2. Architectural Source of Truth

`docs/architecture.md` is authoritative.

Before implementing a feature that affects architecture, security boundaries, transaction semantics, backend contracts, provenance, or Phase 0 scope:

1. Read the relevant architecture section.
2. Check whether the proposed work conflicts with a frozen Architecture Decision.
3. Prefer the smallest implementation that satisfies the current architecture.
4. Do not silently redefine an Architecture Decision in code.
5. If implementation evidence proves an Architecture Decision wrong, document the conflict explicitly before changing the architecture.

Do not broaden scope merely because an adjacent feature is technically easy.

---

## 3. Phase 0 Scope

Phase 0 is deliberately narrow.

Current target:

```text
IDE/backend: IntelliJ IDEA 2026.2+
Language: Kotlin
Semantic frontend: K2
Deployment: developer workstation
Assurance profile: Advisory
Mutation: semantic rename only
Git: required
Working tree: clean
Approval: asynchronous
State revalidation: mandatory
Mutation concurrency: one per repository
Rollback: targeted restore from captured HEAD
CI/headless: out of scope
Generic privileged file writes: forbidden
```

Phase 0 consists of:

### Phase 0A — Semantic read bridge

Implement only the semantic reads required to produce a compact and honest `ImpactReport`, including:

```text
resolve_symbol
symbol_info
find_usages
find_implementations
find_overrides
impact_analysis
diagnostics
```

### Phase 0B — Governed rename

Implement one semantic write capability:

```text
rename_symbol
```

Use JetBrains native refactoring machinery.

Do not implement `change_signature`, `safe_delete`, `move_symbol`, general PSI generation, or raw file mutation unless Phase 0 has already validated the architecture and the architecture document is intentionally updated.

---

## 4. JetBrains Bridge Rules

The JetBrains integration must remain a **thin semantic sensor and privileged semantic executor**.

It must not contain:

- agent planning;
- prompts;
- model integrations;
- organization-specific policy;
- long-term agent memory;
- a chat interface;
- a generic file-write API;
- a second semantic index;
- a persistent custom code graph.

Use JetBrains/K2 capabilities instead of reimplementing language semantics.

### Reads

Semantic reads must use supported IntelliJ/K2 APIs and respect the IntelliJ threading model.

Do not perform long semantic reads on the UI thread.

Handle indexing/dumb mode explicitly. Temporary indexing unavailability must never be reported as:

```text
0 usages
symbol absent
unsupported
```

Use a state equivalent to:

```text
TEMPORARILY_UNAVAILABLE: INDEXING
```

### Writes

Prefer native JetBrains semantic refactoring.

For Phase 0, rename must be performed through JetBrains rename/refactoring infrastructure, not text replacement.

The bridge must **not** expose privileged operations such as:

```text
write_file
replace_text
apply_patch
raw_diff
```

If a future feature requires controlled PSI mutation, it must be treated as a distinct lower-confidence mutation class and must preserve all transaction and verification invariants.

---

## 5. Semantic Identity

Never expose raw `PsiElement`, K2 symbol objects, or JVM object references outside the bridge.

External identities are transaction-scoped opaque handles, for example:

```text
sym:txn-184:7f31
```

Internally, resilient JetBrains pointers may be used where appropriate, but they are implementation helpers, not durable identity.

After a semantic mutation:

1. Discard assumptions derived from the old semantic object.
2. Re-resolve the expected new target using the semantic backend.
3. Continue verification using the newly resolved state.

Symbol identity must distinguish overloads where the backend can distinguish them. Do not reduce identity to a bare function name or fragile FQN string if owner/signature/receiver information is available.

Phase 0 may reject declarations whose identity cannot be made reliable.

---

## 6. Impact Analysis Must Be Honest

Static semantic precision does not imply global completeness.

An impact report may know that 37 references definitely resolve to a symbol while still not knowing about:

- reflection;
- string-based references;
- dependency-injection wiring;
- generated code;
- configuration;
- downstream repositories;
- external consumers.

Use terminology such as:

```text
observedUsages
```

rather than implying an absolute count.

Impact reports must include both scope and completeness.

Conceptually:

```text
scope:
  LOCAL | MODULE | PROJECT | WORKSPACE | EXTERNAL_UNKNOWN

completeness:
  COMPLETE | PARTIAL | UNKNOWN
```

Unknown must never be converted to zero.

Policy must be allowed to escalate on uncertainty.

If the backend cannot establish whether external consumers exist, represent that fact explicitly.

---

## 7. Backend Capabilities

Backends are capability-driven.

Do not invent a fake universal AST or universal compiler object model.

Capability support is graded:

```text
UNSUPPORTED
BEST_EFFORT
SEMANTIC
NATIVE
```

Policies may require minimum capability levels.

Example:

```text
PUBLIC_API_RENAME requires:
  SEMANTIC_RENAME >= SEMANTIC
  USAGE_SEARCH    >= SEMANTIC
  DIAGNOSTICS     >= SEMANTIC
```

JetBrains-specific implementation details must stay behind the JetBrains backend boundary.

---

## 8. Transaction Semantics

A governed mutation is a transaction, not a direct tool call.

Expected lifecycle:

```text
CREATED
  ↓
ANALYZING
  ↓
POLICY_EVALUATED
  ├─→ DENIED
  └─→ PENDING_APPROVAL
          ↓
       APPROVED
          ↓
      REVALIDATING
       ├─→ STALE
       └─→ AUTHORIZED
                ↓
            EXECUTING
                ↓
            VERIFYING
             ├─→ FAILED
             │     ↓
             │ ROLLING_BACK
             │     ↓
             │ ROLLED_BACK
             └─→ COMMITTED
```

Do not skip states merely to simplify an implementation.

A mutation is not successful until verification succeeds.

---

## 9. Async Approval and TOCTOU Protection

Approval may happen long after analysis.

Approval therefore applies to:

```text
intent
+
analysis
+
workspace snapshot
```

Immediately before execution, revalidate the workspace.

For Phase 0 require:

```text
current HEAD == analyzed HEAD
AND
working tree == CLEAN
AND
target symbol re-resolves
```

If revalidation fails:

```text
TRANSACTION_STALE
```

Do not silently continue and do not reinterpret stale approval as valid for the new workspace state.

Final short-lived execution authorization must be issued **after successful revalidation**.

---

## 10. Repository Mutation Lease

Phase 0 permits only one mutating transaction per repository at a time.

Reads may be concurrent.

Acquire a repository mutation lease before final mutation execution and hold it until the transaction reaches:

```text
COMMITTED
```

or:

```text
ROLLED_BACK
```

Do not allow two governed semantic mutations to race against the same repository state.

---

## 11. Git Preconditions and Rollback

Phase 0 requires:

```text
Git repository
clean working tree
```

If the working tree is dirty, fail before mutation with a transaction precondition error.

At transaction creation capture at least:

```text
base HEAD
pre-operation file hashes where required
transaction-touched files
```

On failed verification:

- restore only files modified by the transaction;
- restore them from the captured base state;
- never use `git reset --hard` as the transaction rollback mechanism;
- synchronize filesystem, IntelliJ VFS, documents, and PSI afterward;
- fresh-resolve semantic state;
- verify that rollback restored the expected pre-state.

Do not mark a transaction `ROLLED_BACK` until IDE and filesystem state have converged.

---

## 12. Workspace Synchronization

The IDE semantic state and external build tools must observe the same source contents.

Before running external Gradle/Maven/Git-based verification, establish a synchronization barrier:

```text
commit PSI/document state
    ↓
save modified documents
    ↓
flush/persist pending VFS changes using supported target-version APIs
    ↓
allow external verification
```

After rollback, perform the inverse reconciliation so disk, VFS, Document, and PSI state converge before continuing.

The invariant matters more than any specific API call. Use supported IntelliJ 2026.2 APIs rather than copying outdated examples.

---

## 13. Verification

At minimum, governed rename verification should include:

```text
fresh target resolution
diagnostics
required build
required tests
postconditions
```

Example postconditions:

```text
new compiler errors == 0
new unresolved references == 0
expected renamed symbol resolves
old symbol no longer resolves
required build == PASS
required tests == PASS
```

Verification failure means the transaction failed even if the IDE refactoring itself succeeded.

---

## 14. Advisory and Enforced Assurance

### Advisory Mode

Phase 0 operates in Advisory Mode.

The agent may still have normal filesystem or shell tools.

The valid claim is:

> Operations executed through Telaio are governed, verified, and auditable.

Do not claim that every repository modification is governed.

### Enforced Mode

Future Enforced Mode requires an actual write boundary.

The expected model is:

```text
agent source view      READ ONLY
scratch/build cache    READ/WRITE as needed
Telaio Gateway         accessible
privileged executor    not directly accessible
authorization secrets  not accessible
```

In Enforced Mode, shell access may remain available, but direct source mutation must fail outside the governed path.

Do not implement Enforced Mode as prompt instructions or tool naming conventions. It requires a real OS/process/filesystem boundary.

---

## 15. Provenance Is First-Class

Every governed coding activity should be reconstructable as far as instrumentation permits.

Use:

```text
traceId
transactionId
toolCallId
```

to correlate events.

Capture, where observable:

```text
human prompt
observable model input
model/provider/version
model output
tool calls
tool results
files read
semantic analysis
impact report
policy decision
approval
workspace revalidation
authorization
source mutation
before/after hashes
exact diff artifact
verification
rollback
Git base/result
token usage
reasoning-token usage when exposed
latency
cost when available
```

Do not claim access to provider-hidden prompts, hidden model state, or private chain-of-thought.

Reasoning provenance means metadata such as:

```text
reasoningEnabled
reasoningEffort
reasoningTokenCount
```

when exposed by the provider.

Do not store hidden chain-of-thought content.

---

## 16. Provenance Assurance Levels

Make provenance guarantees explicit.

### Level 1 — Mutation provenance

Known:

```text
semantic operations
policy
approval
files modified
diff
verification
Git state
```

### Level 2 — Agent provenance

Additionally known from an instrumented agent/runtime:

```text
prompts
responses
tool calls
token usage
```

### Level 3 — Enforced provenance

All model, tool, and source-write paths inside the configured execution boundary are instrumented.

Never claim a higher level than the deployed environment actually supports.

---

## 17. Provenance Privacy

Prompts, source, tool arguments, outputs, and diffs may contain sensitive information.

The provenance layer should eventually support policies equivalent to:

```text
METADATA_ONLY
CONTENT_HASHED
FULL_CONTENT_ENCRYPTED
```

Do not make full prompt/source retention mandatory for the architecture to function.

Generic model/tool telemetry should align with OpenTelemetry GenAI concepts where practical rather than inventing unnecessary incompatible telemetry.

---

## 18. Approval Evidence

An approval record should identify:

```text
who approved
when approval was requested
when approval occurred
which analysis/workspace snapshot was approved
which policy decision required approval
```

Approval latency should be derivable from timestamps rather than independently stored as authoritative state.

Do not store only:

```text
status = APPROVED
```

for an auditable transaction.

---

## 19. Coding Style and Implementation Discipline

Prefer:

- explicit immutable domain models;
- sealed state/result types;
- narrow interfaces;
- small backend-specific adapters;
- deterministic policy functions;
- structured errors instead of ambiguous `null`;
- testable pure logic outside IntelliJ APIs;
- JetBrains-specific code isolated in the integration module;
- comments explaining invariants and non-obvious platform constraints, not obvious syntax.

Avoid:

- speculative abstractions for hypothetical backends;
- framework layers with one implementation and no demonstrated need;
- architecture changes hidden inside refactors;
- broad cleanup unrelated to the current task;
- silently weakening safety invariants to make tests pass.

Keep Phase 0 code boring where possible.

---

## 20. Testing Expectations

Every behavior-changing implementation should add or update tests.

Prioritize tests for invariants.

Important categories include:

```text
impact completeness
policy decisions
approval state transitions
stale workspace detection
authorization binding
repository mutation lease
dirty-worktree rejection
rollback targeting
evidence/provenance correlation
capability-level enforcement
TEMPORARILY_UNAVAILABLE indexing behavior
```

JetBrains integration tests should prove the semantic/refactoring boundary against realistic Kotlin fixtures, including at least:

```text
private member rename
public member rename
multiple usages
overloaded callable identity
cross-file usages
cross-module impact where feasible
```

Do not rely solely on mocks for behavior that depends on PSI/K2/refactoring semantics.

---

## 21. Evaluation Discipline

Telaio must prove value, not architectural elegance.

Phase 0 evaluation separates three configurations:

```text
A — Raw agent
B — Semantic agent
C — Governed semantic agent
```

A vs B evaluates semantic efficiency.

B vs C evaluates governance value and overhead.

Do not expect C to be faster than B.

Useful C outcomes include:

```text
policy violation caught
unknown impact escalated
stale approval rejected
failed postcondition detected
rollback completed
full provenance reconstructed
```

Governance overhead must be measured separately from governance effectiveness.

---

## 22. Kill Criteria

Do not continue expanding Telaio simply because the architecture is interesting.

Reconsider the project if evidence shows that:

```text
semantic tooling provides little value over normal agent workflows
governance adds little beyond normal PR review
impact uncertainty cannot be represented usefully
rollback is unreliable
operational overhead is unacceptable
Enforced Mode requires impractical deployment constraints
target users do not value increased agent autonomy
```

A sophisticated approval dialog is not sufficient product differentiation.

---

## 23. Out of Scope for Phase 0

Do not implement unless architecture scope is deliberately changed:

```text
chat UI
model picker
AI autocomplete
custom semantic index
persistent custom code graph
multi-language backend
headless CI backend
Enforced Mode sandbox
generic file editing
change_signature
safe_delete
move_symbol
complex PSI generation
enterprise provenance dashboard
long-term telemetry warehouse
PKI
distributed transaction system
```

---

## 24. Before Starting Any Task

Before editing code:

1. Read `docs/architecture.md`.
2. Inspect the existing implementation and tests.
3. Identify which Phase 0 capability the task belongs to.
4. Identify relevant architectural invariants.
5. Check the Git working tree before any governed mutation work.
6. Prefer extending an existing boundary over creating a parallel abstraction.
7. Keep the change minimal.
8. Add tests for the invariant being implemented.
9. Do not modify unrelated user changes.

If a requested implementation conflicts with the architecture, surface the conflict explicitly rather than silently choosing one interpretation.

---

## 25. Before Declaring Work Complete

Confirm:

```text
architecture invariants preserved
tests updated
tests pass
no unrelated code changed
no raw privileged source-writing path introduced
impact uncertainty preserved
state transitions valid
rollback behavior considered
provenance/evidence updated where relevant
documentation updated if contract changed
```

For JetBrains-backed mutations also confirm:

```text
semantic target re-resolves
IDE/document/filesystem state is synchronized
external verification observed current content
```

---

## 26. Final Principle

Every meaningful AI-assisted code mutation should eventually be reconstructable as:

```text
WHO requested it?
WHAT did they ask?
WHICH model participated?
WHAT context was observable?
WHAT tools were called?
WHAT semantic entity was targeted?
WHAT impact was observed?
WHAT remained unknown?
WHICH policy applied?
WHO approved it?
WHICH workspace state was approved?
WAS that state still valid at execution?
WHAT exact source mutation occurred?
WHICH files changed?
HOW was the result verified?
WHAT model resources were consumed?
WHAT Git state resulted?
```

Telaio exists to make AI code modification a **governed, semantic, verifiable, and reconstructable engineering event** rather than an opaque text edit.
