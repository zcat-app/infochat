---
id: M1-605
title: "switch-llm.sh must not silently sweep a hand-pinned per-task route on an all-default run: require explicit confirmation when the current config is mixed/pinned (M1-603 consent regression)"
status: pending
created: 2026-07-11
last_updated: 2026-07-11
blocked_by: []
files_budget: 3
files_scope:
  - prod/switch-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
complexity: medium
risk: low
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The M1-603 shared-default config model itself (infochat.llm.default.*
    inheritance, per-task overrides winning, the deployment-level "one backend
    for all tasks" reshape of switch-llm). This ticket does NOT revert to
    per-task switching; it adds a consent gate on top of the shipped
    deployment-level tool so an all-default run cannot silently mutate a
    hand-pinned config.
  - >-
    prod/scripts/4-llm.sh (the install-time wizard). It writes a uniform
    shared-default config from scratch, so it never faces the mixed/pinned
    input this gate protects; its pin-by-hand advice prose is already correct
    (M1-603). Untouched.
  - >-
    The per-task api-key coupling and the orphan-key advisory WARN
    (LlmRouterStartupGuard, M1-603) — a separate surface; this ticket is the
    switcher's consent flow only, no Java main-source change.
  - >-
    Embeddings handling (never touched by switch-llm) and the runtime
    privacy-disclosure text (already per-task-accurate, M1-603) — unchanged.
acceptance:
  - >-
    prod/switch-llm.sh detects a MIXED-or-PINNED current config: at least one
    per-task infochat.llm.<task>.base-url override is present (so the effective
    per-task backends are not uniformly the shared default) — the shape a
    pre-M1-603 mixed file has, or that a hand pin for privacy creates (e.g. an
    operator keeping chat on local Ollama while the rest are remote). In that
    state the deployment-level backend prompt MUST NOT accept an Enter-default
    that silently rewrites the config: the run either requires an explicit typed
    backend answer (no mutation-on-Enter) OR lists, by task, every per-task
    base-url/api-key line a switch would sweep and requires an explicit typed
    confirmation before any write. The implementer picks one; the invariant is
    "no silent mutation of a mixed/pinned file from an all-default run."
  - >-
    A UNIFORM current config (no per-task base-url overrides — the shape
    prod/scripts/4-llm.sh and this tool now write, where every task inherits
    infochat.llm.default.base-url) keeps its existing all-default byte-identical
    no-op guarantee unchanged: inspecting such a config with all-Enter still
    writes nothing and creates no backup.
  - >-
    On a confirmed switch of a mixed/pinned config, the tool never silently
    discards a hand-written per-task pin — each swept per-task base-url/api-key
    line is named before the write, and the existing timestamped backup +
    printed rollback command still cover the run.
  - >-
    SwitchLlmWiringTest gains coverage for the mixed/pinned input (an all-Enter
    run over a config with a per-task base-url override must NOT collapse it to
    a uniform remote config without the explicit confirmation, i.e. it is not a
    silent byte-mutating no-op), and its class javadoc's "an operator can
    inspect without fear of churn" claim is corrected to hold only for the
    uniform-config case (or made true for all cases by the gate). Existing
    SwitchLlmWiringTest cases stay green. mvn verify green from the repo root.
test_plan:
  adds:
    - >-
      SwitchLlmWiringTest: a mixed/pinned baseline (a per-task
      infochat.llm.<task>.base-url override present) driven with all-Enter must
      either leave the file byte-identical (explicit-answer-required variant) or
      require a typed confirmation before mutating (confirm variant) — asserting
      no SILENT sweep of the per-task pin; plus the uniform-config all-Enter
      no-op stays byte-identical.
  modifies:
    - >-
      SwitchLlmWiringTest — the "inspect without fear of churn" javadoc claim is
      corrected; any existing case whose stdin script changes shape under the
      new gate is updated (M1-599 positional-stdin lesson).
  preserves:
    - >-
      Every existing switch-llm guarantee: the uniform-config no-op, the
      embeddings block never touched, the backup/rollback flow, the per-task
      privacy disclosure, the up -d recreate command, secrets only in
      secrets.env.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D56
redteam_findings: []
redteam_audits: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-605: switch-llm must not silently sweep a hand-pinned route

## The regression, in plain English

Before M1-603, `prod/switch-llm.sh` switched backends **per task**: it prompted
task by task, and pressing Enter for each kept that task's current backend — so
an all-Enter run over a *mixed* config (say chat on a remote API, the six ingest
tasks on local Ollama) was a **byte-identical no-op**. An operator could run the
tool just to *look*, hit Enter through it, and change nothing. A now-deleted
test (`allEnterIsByteIdenticalNoOpEvenWithARemoteTask`) pinned exactly that.

M1-603 reshaped the tool to switch the **whole deployment** to one backend
(matching the new one-service config model). The deployment-level backend prompt
defaults to the backend it classifies from the current config. Over a *mixed*
old-format file that default classifies as `remote` (it reads the chat task's
base-url), so **accepting all defaults now sweeps every per-task line and routes
all seven tasks to the remote provider** — a real mutation from what used to be a
look-only run. The same sweep silently removes a *deliberate* hand-written
per-task LOCAL pin — e.g. an operator keeping chat on-host for privacy while the
rest are remote.

Surfaced by the M1-603 pre-commit red-team as an out-of-model item, carried
unchanged across all three audits (`docs/plan/m1/redteam/M1-603-2026-07-11*.md`).
It is a **consent / expectation regression**, not a threat-model violation — the
privacy disclosure still fires and is accurate, and `local-only=true` still hard-
gates — but a tool that silently converts a privacy-motivated local pin into a
remote route on an all-default run is a real footgun worth closing.

## The fix, in plain English

Add a consent gate for the mixed/pinned case only: when the current config
carries any per-task `base-url` override (so it is not the uniform shared-default
shape), an all-default run must not silently rewrite it. Either require an
explicit typed backend answer (nothing mutates on a bare Enter), or list the
per-task lines a switch would sweep and require a typed confirmation first. The
uniform-config no-op — the common post-M1-603 shape — is unchanged.

No config-model or Java main-source change: this is the switcher's consent flow
plus its wiring test. `security_relevant: true` because the regression converts a
privacy-motivated local route to remote without explicit consent, so a red-team
pass over the consent gate is warranted even though the threat model's stated
commitments were not breached.
