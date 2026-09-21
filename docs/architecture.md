This v1.1 specification is tight, disciplined, and ready to be built.

The evolution across iterations has addressed the classic traps: you didn’t build another chat assistant, you didn’t write a redundant AST engine, and you didn’t leave the security posture vulnerable to a simple `sed -i` bypass.

Here is an analysis of what works in this specification, followed by two critical operational warnings to heed during implementation.

---

### What Elevates v1.1 Above Prior Iterations

1. **Explicit Modeling of Uncertainty (`ImpactCompleteness`)**
Section 11–14 is one of the strongest technical concepts in the document:

$$\text{UNKNOWN} \neq 0$$



Most AI tooling equates "the static analysis found 0 references" with "it is safe to delete/rename." By explicitly treating dynamic reflection, string templates, external consumers, and generated files as `ImpactCompleteness.PARTIAL` or `UNKNOWN`—and driving policy escalation off that uncertainty—you transform the tool from a naive linter into an enterprise compliance mechanism.
2. **Clean-Git Targeted Restoration (AD-16 & AD-17)**
Requiring a clean working tree prior to `BEGIN_TRANSACTION` and tracking only touched files prevents the nightmare of dealing with untracked files, dirty stash pops, or accidental `git reset --hard` catastrophes that erase developer scratch work. It makes transactional rollback deterministic for Phase 0.
3. **Separation of the Three Authorities (Section 42)**
* **LLM Authority**: Intent & planning.
* **Semantic Authority**: Meaning, resolution, propagation.
* **Governance Authority**: Policy, risk tolerance, verification, audit.


This triad is clear and conceptually sound. It gives TramAI an unambiguous domain without stepping on JetBrains' toes.
4. **The Headless/Remote Reality Check (Section 35 / AD-20)**
Explicitly decoupling developer workstation execution from "unattended CI headless JetBrains" avoids getting bogged down in JetBrains Client / Projector / Qodana licensing and JVM warmup complexities. Proving workstation value first is the pragmatic call.

---

### Two Crucial Implementation Traps to Watch During Phase 0

#### 1. In-Memory PSI Rollback vs. Disk Rollback

Section 23 states:

> *If verification fails: restore transaction-modified files from captured HEAD $\to$ save documents $\to$ re-resolve.*

* **The Trap**: If JetBrains' `RenameProcessor` modified 12 documents in memory, and your verification step triggers a rollback via `git checkout HEAD -- <files>`, Git touches the files **on the filesystem**.
* IntelliJ does not always instantaneously re-read files from disk into its in-memory virtual file system (VFS) unless explicitly commanded. If the VFS remains out of sync, the IDE will continue to hold the dirty refactored AST in memory (and could even auto-save it back over the Git-restored files a split-second later).
* **The Fix in the Bridge**:
```kotlin
// When rolling back via disk/git:
VfsUtil.markDirtyAndRefresh(false, true, true, *affectedVirtualFiles)
PsiDocumentManager.getInstance(project).recommitAllDocuments()

```


Ensuring the IDE VFS synchronizes and drops dirty document buffers on a rollback is critical.

#### 2. K2 Target Identity Tracking Across the Mutation Barrier

Section 10 correctly specifies discarding the old pointer and deriving the expected new identity for fresh resolution.

* **The Trap**: For standard top-level functions or classes, deriving the new fully qualified name (FQN) from the rename parameter is trivial. However, for:
* Overloaded member methods (`foo(String)` vs `foo(Int)`)
* Local declarations
* Extension functions with complex receiver types


* Constructing an FQN string manually can be brittle.
* **The Fix**: During Phase 0A/0B, restrict target resolution to **named declarations with canonical qualified names** (classes, top-level functions, unique member methods). Reject or tag ambiguous overload renames as `SupportLevel.BEST_EFFORT` until Phase 1.

---

### Suggested Directory Layout for Implementation

Since this stays inside `tramai`, keep the integration clean:

```
tramai/
├── tramai-coding-gateway/             # Pure Kotlin CLI / MCP Server
│   ├── src/main/kotlin/dev/tramai/gateway/
│   │   ├── mcp/                      # Tool definitions (check_status, rename_symbol, etc.)
│   │   ├── engine/                   # Transaction state machine (CREATED -> COMMITTED)
│   │   ├── policy/                   # Policy evaluation (Public API, Unknowns, etc.)
│   │   ├── git/                      # Worktree clean check + targeted checkout rollback
│   │   └── client/                   # Authenticated bridge client (HTTP/Unix socket)
│   └── build.gradle.kts
│
└── integrations/
    └── jetbrains/                    # IntelliJ Platform Plugin (2026.2+ target)
        ├── src/main/kotlin/dev/tramai/intellij/
        │   ├── bridge/               # Auth filter + local RPC server
        │   ├── k2/                   # analyze(declaration) { ... } readers
        │   ├── refactor/             # RenameProcessor write action dispatcher
        │   ├── vfs/                  # Save/flush & VFS refresh barrier
        │   └── ui/                   # Minimal "Governed Change" ToolWindow card
        └── build.gradle.kts          # intellijPlatform plugin config

```

### Verdict

The spec is sound. The assumptions are challenged, the boundaries are drawn, the threat model is honest, and the kill criteria are clear.

Proceed with **Phase 0A** (K2 read bridge) and **Phase 0B** (governed rename).