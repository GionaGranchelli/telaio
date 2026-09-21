# ADR-0005: AI Change Provenance Is First-Class

- **Status:** Accepted
- **Date:** 2026-09-21
- **Decision owners:** Telaio maintainers
- **Related:** `docs/architecture.md`

## Context

Git records the resulting source state well, but it does not fully explain how an AI-assisted change was produced.

For AI-generated or AI-assisted changes, teams may need to reconstruct:

- the human request;
- observable model input/output;
- model/provider/version;
- tool calls and results;
- semantic analysis;
- policy decisions;
- approvals;
- exact file mutations;
- verification;
- token usage and cost where available;
- the resulting Git state.

This is relevant for debugging, compliance, code review, operational learning, and future contribution policies.

## Decision

Telaio treats provenance as a first-class architectural concern.

Each agent session should have a `traceId`.

Each governed operation should have a `transactionId`.

Observable events should be causally correlated, including where available:

```text
human.input
model.request
model.response
tool.call
tool.result
semantic.analysis
policy.decision
approval
state.revalidation
authorization
source.mutation
verification
rollback
git.result
```

Source mutation provenance includes:

- path;
- before hash;
- after hash;
- exact diff/patch artifact;
- causing tool call;
- causing transaction.

Telaio records token and reasoning-token usage only when exposed by the provider.

Telaio does not require or store hidden chain-of-thought.

## Provenance Assurance Levels

### Level 1 — Mutation provenance

Semantic operations, policy, approval, source mutations, verification, and Git state are known.

### Level 2 — Agent provenance

Instrumented model prompts/responses/tool calls/token usage are also known.

### Level 3 — Enforced provenance

All model, tool, and write paths inside the configured execution boundary are instrumented.

Telaio must never claim a higher assurance level than the deployment actually supports.

## Privacy

Provenance policy must eventually support at least:

```text
METADATA_ONLY
CONTENT_HASHED
FULL_CONTENT_ENCRYPTED
```

Generic GenAI telemetry should align with OpenTelemetry conventions where practical.
