# Telaio

**Governed Semantic Execution Layer for AI Coding Agents**

Telaio sits between AI coding agents and trusted semantic development tooling so consequential code mutations can be analyzed, governed, authorized, verified, and reconstructed with full provenance.

---

## Architecture & Mental Model

Telaio separates four distinct authorities:

1. **LLM Authority** — Decides what changes should be attempted (planning & intent).
2. **Semantic Authority** — Determines what the program means and performs native semantic operations (JetBrains/K2).
3. **Governance Authority** — Decides whether an operation is allowed based on impact analysis, policy, and human approvals.
4. **Verification Authority** — Determines whether the resulting state satisfies required postconditions (builds, tests, compiler diagnostics, invariant checks).

```text
Human Request
    ↓
Agent Intent
    ↓
Semantic Analysis (K2)
    ↓
Impact Report + Uncertainty (Blast Radius)
    ↓
Policy Evaluation
    ↓
Human Approval (if required)
    ↓
Workspace Revalidation (TOCTOU check)
    ↓
Short-Lived Authorization
    ↓
Native Semantic Mutation (JetBrains Refactoring)
    ↓
Verification (Diagnostics + Builds + Tests)
    ↓
Commit or Targeted Rollback
    ↓
Provenance & Audit Evidence
```

See [`docs/architecture.md`](docs/architecture.md) and [`docs/adr/`](docs/adr/) for detailed architectural decisions.

---

## Project Structure

```text
telaio/
├── telaio-core/              # Pure Kotlin domain models, protocol DTOs, and interfaces
│   ├── src/main/kotlin/dev/telaio/core/symbol/
│   └── src/test/kotlin/dev/telaio/core/symbol/
│
├── integrations/
│   └── jetbrains/           # IntelliJ Platform Plugin (K2 Analysis API bridge)
│       ├── src/main/kotlin/dev/telaio/intellij/
│       │   ├── bridge/      # Transaction-scoped symbol handle registry
│       │   └── k2/          # K2 Analysis API semantic resolver
│       └── src/test/kotlin/dev/telaio/intellij/k2/
│
└── docs/
    ├── architecture.md      # Canonical architecture document
    ├── adr/                 # Architecture Decision Records
    └── specs/               # Executable behavioral specifications
```

---

## Current Status (Phase 0)

- [x] **SPEC-0001: Semantic Symbol Resolution** ([`docs/specs/phase-0/0001-semantic-symbol-resolution.md`](docs/specs/phase-0/0001-semantic-symbol-resolution.md))
  - Kotlin K2 semantic resolution from declaration and call-site positions.
  - Opaque transaction-scoped handles (`sym:<txnId>:<id>`).
  - Overload discrimination with fully qualified type signatures.
  - Explicit indexing handling (`TEMPORARILY_UNAVAILABLE: INDEXING`).
  - Zero JVM/PSI leakage across the bridge boundary.
- [ ] **SPEC-0002: Impact Analysis**
- [ ] **SPEC-0003: Governed Rename**

---

## Getting Started

### Prerequisites

* Java 21+
* Linux / macOS / Windows

### Building and Testing

Build the complete project and run all unit and IntelliJ Platform integration tests:

```bash
./gradlew check
```

Run tests specifically:

```bash
# Core protocol & domain tests
./gradlew :telaio-core:test

# IntelliJ / K2 Analysis API integration tests
./gradlew :integrations:jetbrains:test
```

---

## License

Apache License 2.0 (or project configured license).
