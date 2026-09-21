# ADR-0002: Separate Governance Gateway from Semantic Bridge

- **Status:** Accepted
- **Date:** 2026-09-21
- **Decision owners:** Telaio maintainers
- **Related:** `docs/architecture.md`

## Context

Telaio combines two fundamentally different responsibilities:

1. deciding whether an operation should be allowed and under what conditions;
2. understanding and changing source code using JetBrains semantics.

Combining these inside the JetBrains plugin would couple policy, model orchestration, semantic execution, and IDE lifecycle. It would also make future non-JetBrains backends harder to support.

## Decision

Telaio is split into two principal execution components.

### Telaio Coding Gateway

Owns:

- tool/API exposure;
- transaction lifecycle;
- policy evaluation;
- approval workflows;
- workspace-state binding;
- authorization;
- repository mutation leases;
- verification orchestration;
- provenance and evidence.

### JetBrains Semantic Bridge

Owns:

- JetBrains/K2 semantic reads;
- native semantic refactoring execution;
- diagnostics;
- semantic re-resolution;
- IDE/document/VFS synchronization.

The Bridge is intentionally thin and must not contain:

- LLM prompts;
- model integrations;
- organization-specific policy;
- agent planning;
- generic privileged source editing;
- long-term agent memory.

## Consequences

### Positive

- Keeps policy independent from IDE internals.
- Makes the semantic backend replaceable.
- Creates a clear privilege boundary.
- Keeps the JetBrains plugin small and testable.

### Negative

- Requires an authenticated inter-process boundary.
- Requires explicit transaction and transport contracts.
- Some workflows involve more orchestration than an in-process implementation.

## Alternatives Considered

### Put the whole agent inside the JetBrains plugin

Rejected because it mixes semantic execution with orchestration and product policy and would unnecessarily compete with existing IDE assistants.

### Expose JetBrains tools directly to the agent

Rejected as the governed execution path because it bypasses Telaio policy and evidence generation.

## Invariant

The semantic Bridge must never become a general-purpose privileged file editor.
