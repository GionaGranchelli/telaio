# ADR-0003: Impact Uncertainty Is First-Class

- **Status:** Accepted
- **Date:** 2026-09-21
- **Decision owners:** Telaio maintainers
- **Related:** `docs/architecture.md`

## Context

Static semantic analysis can precisely identify references visible to the backend, but it cannot universally prove the absence of dependencies through:

- reflection;
- string-based references;
- generated code;
- framework wiring;
- configuration;
- downstream repositories;
- external consumers.

Governance decisions may depend on blast radius. Treating an observed usage count as globally complete would make policy decisions appear more certain than the semantic evidence supports.

## Decision

Impact reports must explicitly encode both:

- **scope** — where the backend looked;
- **completeness** — how complete the reported impact is believed to be.

Use `observedUsages`, not an unconditional `usages` count, unless completeness is formally established.

Conceptual completeness:

```text
COMPLETE
PARTIAL
UNKNOWN
```

Conceptual scope:

```text
LOCAL
MODULE
PROJECT
WORKSPACE
EXTERNAL_UNKNOWN
```

Unknown must never be interpreted as none or zero.

Dynamic/uncertain categories should be represented explicitly, including where relevant:

- string literal candidates;
- reflection risk;
- framework reference coverage;
- generated-code coverage;
- external-consumer coverage.

## Consequences

### Positive

- Prevents false confidence in governance decisions.
- Allows policy to fail safe.
- Preserves the difference between precision and completeness.
- Makes evidence records more honest.

### Negative

- Some operations will escalate more often.
- Impact DTOs and policies become slightly more complex.
- `COMPLETE` must be defined relative to a declared scope.

## Policy Rule

Policies may automatically authorize based on observed impact only when the required completeness guarantees are satisfied.

`UNKNOWN != NONE`.
