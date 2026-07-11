---
id: M1-604
title: "Align %remote-llm ingest-task routing intent with design §5.7: all generative tasks route remote by default — fix the stale 'ingest stays local even under remote-llm' props comment, add the missing classifier rows to §5.7, record the decision"
status: pending
created: 2026-07-11
last_updated: 2026-07-11
blocked_by: [M1-603]
files_budget: 5
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
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
    %remote-llm boot-refusal posture for unrouted tasks). This ticket runs AFTER
    M1-603 (blocked_by) and only aligns intent statements and profile lines with
    what M1-603 shipped — it adds no config keys and changes no resolution code.
  - >-
    The wizard scripts (prod/scripts/4-llm.sh, prod/switch-llm.sh). Their remote
    branch already routes ALL generative tasks to the remote endpoint (D54),
    which IS the posture this ticket documents — they are consistent, not drifted.
  - >-
    Embeddings (D54: always local nomic-768, never remote) — untouched, and the
    §5.5/§5.7 embeddings cells already carry their D54 supersession notes.
  - >-
    The %remote-llm per-task Anthropic lines for chat/summarizer/translator
    (base-url/provider/model/max-tokens). They express chat-tier intent defaults,
    not ingest routing; whether an out-of-box Anthropic default without an
    api-key is worth keeping is a separate question this ticket does not decide.
acceptance:
  - >-
    docs/spec/decisions.md gains a new entry (next free D-number) recording:
    under the remote-llm profile ALL generative ModelTasks — ingest
    (security/tagger/entity/classifier) included — route to the operator's
    remote provider by default, as design §5.7's remote-llm column ("provider
    judge"/"provider chat") and §5.10's privacy disclosure (post bodies of
    security/tagger/entity/classifier/summarizer/chat calls are sent to the
    remote provider) already document, and as the D54 wizard remote branch
    already implements. Keeping ingest tasks on a local Ollama under remote-llm
    is a supported OPT-IN via per-task base-url/provider/model overrides (a
    cost/privacy optimization), not a baked default. The entry notes that the
    §5.7 remote-llm generative cells are operator-supplied values with no
    bakeable default (an operator-specific endpoint/model cannot ship in
    application.properties), which is why M1-603's no-default-on-%remote-llm
    boot-refusal is the out-of-box posture.
  - >-
    The collector application.properties classifier-block comment claiming
    classification "is a local ingest task and stays on the local model even
    under the remote-llm profile" (and any sibling task comment making the same
    claim — audit both services' properties files) is corrected to match the
    decision: ingest tasks follow the shared default under remote-llm; local is
    per-task opt-in. Comments are reconciled in whatever form M1-603 left the
    blocks (per-task model lines remain; base-url/api-key lines are gone).
  - >-
    docs/design/05-llm-and-embeddings.md §5.7 gains the classifier rows missing
    since M1-597 (infochat.llm.classifier.model and
    infochat.llm.classifier.max-concurrency), with per-profile values matching
    the checked-in %laptop/%vps/%pi/%remote-llm max-concurrency lines and the
    laptop-default model, and a remote-llm model cell consistent with the other
    ingest rows ("provider chat"-style operator-supplied value).
  - >-
    Both properties files' %remote-llm blocks are audited against the decision:
    any remaining line or comment expressing ingest-local-by-default intent is
    removed or corrected. No behavioral change beyond M1-603's already-shipped
    posture is introduced — this ticket is docs, comments, and profile-line
    hygiene only; mvn verify green from the repo root (properties files are in
    the diff, so the full suite runs).
test_plan:
  adds: []
  modifies: []
  preserves:
    - >-
      All existing tests green — the diff touches comments, docs, and (at most)
      %remote-llm profile lines whose removal M1-603's tests already cover; no
      test asserts the stale comment text.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D54
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

# M1-604: one story for where ingest LLM tasks run under remote-llm

## The problem, in plain English

The repo tells two contradictory stories about where the ingest LLM tasks
(security judge, tagger, entity extractor, classifier) run when the deployment
uses the `remote-llm` profile:

- **Story A — "ingest stays local":** the collector's checked-in
  `application.properties` classifier block says classification "is a local
  ingest task and stays on the local model even under the remote-llm profile,"
  and the `%remote-llm` profile blocks leave all four ingest tasks on the baked
  local-Ollama defaults (only chat/summarizer/translator are pointed at a
  remote endpoint).
- **Story B — "everything generative goes remote":** design §5.7's remote-llm
  column routes the ingest tasks to remote provider models ("provider judge" /
  "provider chat"), §5.10 explicitly discloses that ingest post bodies are sent
  to the remote provider, CLAUDE.md defines the profile as "local DB/services +
  **remote LLM API**", and the D54 wizard remote branch routes ALL generative
  tasks to the operator's remote endpoint.

Story A is one comment plus profile-block inertia; Story B is the documented
design, the profile's own definition, and the shipped tooling behavior. Story A
is also what made the 2026-07-10 classifier incident possible in spirit: it
normalizes ingest tasks silently pointing at a loopback address that a
containerized remote-llm host does not serve (surfaced during the M1-603
re-scope, 2026-07-11 — all four ingest tasks sat on that dead default
out-of-box; the wizard-generated operator file masked three of them).

## The fix, in plain English

Pick Story B — because the design already did — and make every artifact say it:

1. **Record the decision** (decisions.md): remote-llm routes all generative
   tasks remote by default; local ingest under remote-llm is an explicit
   per-task opt-in, not a baked default. The out-of-box posture for an unrouted
   task on remote-llm is M1-603's loud boot refusal, because an
   operator-specific remote endpoint cannot be baked into
   `application.properties`.
2. **Fix the stale comment(s)** in the properties files.
3. **Add the classifier rows missing from §5.7** (the table predates M1-597).
4. **Audit the `%remote-llm` blocks** in both services for leftover
   ingest-local-by-default intent, in the post-M1-603 config model.

No code paths change; the wizard already behaves this way. This is
documentation, comments, and profile-line hygiene — making the repo tell one
story so the next config-model change doesn't have to re-litigate it.

## Why blocked_by M1-603

M1-603 deletes the baked per-task base-url/api-key lines this ticket's comments
sit next to, and establishes the boot-refusal posture the decision entry
describes. Sequencing avoids editing the same property blocks twice and lets
the decision text reference the shipped config model instead of a moving
target.
