---
id: M1-604
title: "Doc alignment for remote-llm ingest routing: record the all-generative-remote decision + add the classifier rows missing from design §5.7 (M1-603 already shipped the props comment + Anthropic-block removal)"
status: done
created: 2026-07-11
last_updated: 2026-07-11
blocked_by: []
files_budget: 3
files_scope:
  - docs/design/05-llm-and-embeddings.md
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The M1-603 config-model mechanics (infochat.llm.default.base-url/.api-key
    inheritance, removal of the baked per-task base-url/api-key lines, the
    %remote-llm boot-refusal posture for unrouted tasks) — shipped and merged
    (D56, 2026-07-11). This ticket only aligns the remaining DOCS with that
    shipped state.
  - >-
    Both application.properties files. M1-603 already REMOVED the stale
    classifier comment ("stays on the local model even under the remote-llm
    profile") and the baked %remote-llm Anthropic route blocks + ingest
    base-urls (the "keep the keyless Anthropic default?" question this ticket
    once deferred was decided there: removed, because a keyless default shadows
    the operator's shared endpoint into a 401). Both %remote-llm blocks are
    clean of ingest-local-by-default intent — verified 2026-07-11. This ticket
    edits no properties file; if an audit turns up a residual, that is a scope
    change (refine to re-add the file).
  - >-
    The wizard scripts (prod/scripts/4-llm.sh, prod/switch-llm.sh) — their
    remote branch already routes ALL generative tasks to the operator's
    endpoint (D54/D56), which IS the posture this ticket documents.
  - >-
    Embeddings (D54: always local nomic-768, never remote) — untouched; the
    §5.5/§5.7 embeddings cells already carry their D54 supersession notes.
acceptance:
  - >-
    docs/spec/decisions.md gains a new entry (next free D-number, D57) recording:
    under the remote-llm profile ALL generative ModelTasks — ingest
    (security/tagger/entity/classifier) included — route to the operator's
    remote provider by default, as design §5.7's remote-llm column ("provider
    judge"/"provider chat") and §5.10's privacy disclosure (post bodies of
    security/tagger/entity/classifier/summarizer/chat calls are sent to the
    remote provider) already document, and as the D54 wizard remote branch
    already implements. Keeping ingest tasks on a local Ollama under remote-llm
    is a supported OPT-IN via per-task base-url/provider/model overrides (a
    cost/privacy optimization), not a baked default. The entry cross-references
    D56 (the shared-default config model whose no-default-on-%remote-llm
    boot-refusal is why the out-of-box posture is "route everything explicitly"
    rather than a baked remote endpoint the image cannot guess).
  - >-
    docs/design/05-llm-and-embeddings.md §5.7 gains the classifier rows missing
    since M1-597 (infochat.llm.classifier.model and
    infochat.llm.classifier.max-concurrency), with per-profile values matching
    the checked-in %laptop/%vps/%pi/%remote-llm max-concurrency lines and the
    laptop-default model, and a remote-llm model cell consistent with the other
    ingest rows ("provider chat"-style operator-supplied value).
  - >-
    Pure-doc ticket — docs/design/ + docs/spec/ only, no application.properties
    or code change (M1-603 already aligned the props). The diff is inert for the
    build (no *.java / pom.xml / src/**/resources/**), so mvn verify is N/A per
    the inert-diff gate; the clarity pre-flight and reviewer still run.
test_plan:
  adds: []
  modifies: []
  preserves:
    - >-
      No test surface — the diff is docs-only (design §5.7 table + a decision
      row); no test asserts on either. The full suite's last green (M1-603
      merge) is the baseline; nothing in this diff can regress it.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D54
  - D56
redteam_findings: []
redteam_audits: []
reviews:
  - round: 1
    date: 2026-07-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 15
      removed: 8
escalations: []
overrides: []
revisions:
  - date: 2026-07-11
    reason: >-
      Tweaked after M1-603 merged: M1-603 already performed the props
      comment-fix and the %remote-llm Anthropic-block + ingest-base-url removal
      that were this ticket's acceptance items 2 and 4, and decided the
      "keep the keyless Anthropic default?" question (removed). Narrowed to the
      genuine pure-doc residual — the §5.7 classifier rows and the routing
      decision record — dropped both application.properties from files_scope
      (M1-603 left them clean), files_budget 5→3, blocked_by cleared (M1-603
      done). Now a pure-doc ticket; could alternatively land as a `spec:` commit.
  - date: 2026-07-11
    reason: >-
      Filed as an M1-603 re-scope follow-up (findings 2/3): the checked-in
      classifier comment + %remote-llm blocks contradicted design §5.7 / §5.10 /
      D54; §5.7 also missing classifier rows since M1-597.
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-07-11
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-604: one story for where ingest LLM tasks run under remote-llm

## What M1-603 already settled

This ticket was filed (2026-07-11) to resolve a two-story contradiction about
where the ingest LLM tasks (security/tagger/entity/classifier) run under the
`remote-llm` profile. **M1-603 — merged the same day — already resolved most of
it in code:**

- the stale collector comment ("classification … stays on the local model even
  under the remote-llm profile") is **rewritten**;
- the baked `%remote-llm` Anthropic route blocks and the ingest baked
  base-urls are **removed** (a keyless out-of-box Anthropic default would
  shadow the operator's shared endpoint into a 401 — the "separate question"
  this ticket once deferred, decided there);
- both `%remote-llm` blocks are **clean** of ingest-local-by-default intent.

So the properties-file work (this ticket's original items 2 and 4) is **done**.
What survives is pure documentation.

## The residual (pure-doc)

1. **Record the decision** (`decisions.md`, D57): under `remote-llm`, all
   generative tasks — ingest included — route to the operator's remote provider
   by default; local ingest is an explicit per-task opt-in. Design §5.7 and
   §5.10 already say this; the wizard already does it; D56 is the config-model
   mechanism (no baked default → boot-refuses an unrouted task). D57 makes the
   *intent* a first-class decision so the next config-model change does not
   re-litigate it.
2. **Add the classifier rows to §5.7** (`docs/design/05-llm-and-embeddings.md`):
   the per-profile model/concurrency table predates M1-597, so the classifier
   row is missing.

Both are `docs/design/` + `docs/spec/` edits with no code change — an inert diff
for the build. This could equally land as a `spec:` commit; it is kept as a
ticket so the D57 decision record goes through clarity/review like any decision
addition. No `application.properties` or code changes.
