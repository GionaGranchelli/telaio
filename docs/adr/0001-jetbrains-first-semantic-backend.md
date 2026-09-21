# ADR-0001: JetBrains Is the First Semantic Backend

- **Status:** Accepted
- **Date:** 2026-09-21
- **Decision owners:** Telaio maintainers
- **Related:** `docs/architecture.md`

## Context

Telaio needs reliable semantic information for source-code operations such as symbol resolution, usage discovery, overrides, implementations, diagnostics, and semantic rename.

Building an independent parser, symbol index, type system, or project-wide semantic graph would duplicate mature functionality already available in modern IDE/compiler platforms and would significantly increase correctness and maintenance risk.

The first target ecosystem is Kotlin/JVM, where JetBrains IntelliJ Platform and K2 provide mature semantic analysis and native refactoring capabilities.

## Decision

JetBrains IntelliJ Platform is the first semantic backend for Telaio.

For Kotlin, the implementation targets K2-era APIs only.

Telaio will use JetBrains as the semantic authority for:

- symbol resolution;
- references/usages;
- implementation and override relationships;
- semantic impact data;
- diagnostics;
- native refactoring execution.

Telaio will not build a replacement semantic engine for Phase 0.

## Consequences

### Positive

- Reuses battle-tested semantic and refactoring infrastructure.
- Reduces the risk of incorrect symbol propagation.
- Makes Phase 0 small enough to validate quickly.
- Keeps Telaio focused on governance, verification, and provenance.

### Negative

- Phase 0 depends on a running JetBrains backend.
- JetBrains API compatibility must be maintained.
- Phase 0 is Kotlin/K2-focused rather than language-neutral in practice.

## Alternatives Considered

### Build a custom semantic graph

Rejected for Phase 0 because it duplicates compiler/IDE functionality and introduces a large correctness burden.

### Start from generic LSP

Rejected as the initial backend because LSP implementations expose inconsistent semantic depth and do not provide a uniform equivalent of JetBrains-native refactoring capabilities.

### Implement multiple backends from the beginning

Rejected because it would force speculative abstractions before the core hypothesis is validated.

## Follow-up

A later backend may be added only through capability-based contracts. JetBrains-specific types must not leak into Telaio core.
