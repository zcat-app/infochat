---
id: M1-670
title: Fix release-audit doc drift in guides and spec
status: done
created: 2026-07-22
last_updated: 2026-07-22
clarity_check:
  date: 2026-07-22
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 5
files_scope:
  - README.md
  - SETUP_GUIDE.md
  - ADMIN_GUIDE.md
  - docs/spec/security.md
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any code, test, or migration                    # doc-only
  - USER_GUIDE.md                                   # no F1-F5 finding lands here (/pending is bot-admin, documented in ADMIN_GUIDE)
  - docs/spec/commands.md                           # the canonical command index is the correct source of truth; /pending is already listed there
  - the asset --vs per-source currency behavior (F6)  # that is an IMPLEMENTATION fix, not doc — tracked in M1-671; do not weaken USER_GUIDE's per-source currency text to match current code
  - OVERVIEW.md / DEVELOPER.md                      # audited clean for F1-F5
acceptance:
  - "ADMIN_GUIDE.md documents `/pending` (bot-admin, DM-only, lists users still in slow-start probation with copy-pasteable contact ids for /vouch//ban) — e.g. a row in the admin toolkit and/or a line in the probation playbook, consistent with docs/spec/commands.md §Command catalogue and decision D55"
  - "SETUP_GUIDE.md §AI backend performance — the ollama table row no longer labels the default model as `llama3.2:3b` as if it were the shipped chat model: it reflects that the laptop default for chat/summary/tagger/entity/classifier/translator is `llama3.1:8b` (llama3.2:3b is only the security judge), and the 'ollama is the default because its model is smaller' reasoning is no longer inverted"
  - "README.md §Security & privacy posture ('You choose where the AI runs') no longer omits the summarizer: it reflects that the summarizer (a request-time task) and chat can both send public post bodies to a remote provider, consistent with decision D57 (which lists security/tagger/entity/classifier/summarizer/chat)"
  - "SETUP_GUIDE.md §Switching your AI backend later AND docs/spec/security.md ingest-task enumeration both include `classifier` (the four ingest tasks are security/tagger/entity/classifier), consistent with decision D57 and prod/scripts/4-llm.sh"
  - "docs/spec/decisions.md D54 no longer states 'six generative tasks (security/tagger/entity/summarizer/chat/translator)' — it includes classifier and counts seven generative tasks, consistent with D57 and the ModelTask enum"
  - "No file other than README.md, SETUP_GUIDE.md, ADMIN_GUIDE.md, docs/spec/security.md, docs/spec/decisions.md is modified"
  - mvn verify is green
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
  - docs/spec/security.md §Ingest pipeline (security side)
decision_refs:
  - D54
  - D55
  - D57
reviews:
  - round: 1
    date: 2026-07-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 30
      removed: 12
---

# M1-670: Fix release-audit doc drift in guides and spec

## Context

A release audit cross-checked README, SETUP_GUIDE, USER_GUIDE, ADMIN_GUIDE, and
OVERVIEW against the scripts, Java, and config, then appended verified findings
to `.scratch/release-audit.md` (F1–F5 are doc/spec-only; F6 is an implementation
gap tracked separately in M1-671). Five doc-level drifts survived re-verification
against ground truth: a live bot-admin command (`/pending`) missing from every
guide; a performance table whose "default ollama model" label is the security
judge, not the shipped chat model; a README privacy sentence that drops the
summarizer from the remote-LLM exposure set; and a stale ingest-task enumeration
(security/tagger/entity) plus a contradicting decision (D54) that both omit the
real fourth ingest task, `classifier`. This ticket fixes all five in one doc-only
pass. No behavior changes.

Evidence per finding (each already verified against code/config — see
`.scratch/release-audit.md` F1–F5 for full file:line citations):

1. **F1 (MISSING) — `/pending` undocumented.** `PendingCommandHandler`
   (`infochat-provider/.../command/PendingCommandHandler.java:43`, name()="pending",
   bot-admin + DM-only) is in the canonical, machine-checked command index
   (`docs/spec/commands.md:153`) and rationale-tracked as D55, but none of the
   guides mention it. It belongs in ADMIN_GUIDE's probation playbook.
2. **F2 (CONFUSION) — perf table mislabels the default model.** `SETUP_GUIDE.md:828`
   labels the ollama row `llama3.2:3b`, but the laptop default chat/summary model
   is `llama3.1:8b` (`infochat-provider/src/main/resources/application.properties:374-379`;
   `DEVELOPER.md:115-117`). `llama3.2:3b` is only the security judge
   (`application.properties:362`). The "~50 s chat reply" and "ollama is the
   default because smaller" reasoning do not reflect the shipped default config.
3. **F3 (MISSING) — README omits summarizer from remote-LLM exposure.**
   `README.md:134` frames remote routing as "post bodies for the ingest tasks,
   private messages for chat", but `SUMMARIZER` is a request-time task that also
   sends post bodies (`SummaryProseGenerator.java:177`). D57
   (`docs/spec/decisions.md:74`) already lists summarizer; the README is the drift.
4. **F4 (OUTDATED) — stale ingest-task list drops classifier.** `SETUP_GUIDE.md:511`
   and `docs/spec/security.md:1390` both enumerate "security/tagger/entity";
   `classifier` is the real fourth ingest task (`infochat-collector/.../eval/classifier/ClassifierWorker.java`,
   M1-597) and `prod/scripts/4-llm.sh:74` routes all seven.
5. **F5 (CONTRADICTION) — D54 miscounts/mislists tasks.** `docs/spec/decisions.md:71`
   (D54) says "six generative tasks (security/tagger/entity/summarizer/chat/translator)",
   omitting `classifier` and undercounting. The `ModelTask` enum has seven values
   (`infochat-llm-adapter/.../ModelTask.java:23-30`, CLASSIFIER at :27) and sibling
   D57 (`decisions.md:74`) correctly lists classifier — so D54 contradicts both
   D57 and the code.

## Acceptance

The seven YAML `acceptance:` items, in prose: add `/pending` to ADMIN_GUIDE;
correct the SETUP_GUIDE perf table's ollama model label (shipped default chat
model is llama3.1:8b) and de-invert the "smaller → that's why it's the default"
reasoning; expand the README remote-LLM exposure sentence to include the
summarizer (matching D57); add `classifier` to the ingest-task enumeration in
both SETUP_GUIDE and security.md (matching D57); and fix D54 in decisions.md to
include classifier / count seven tasks (matching D57 + the ModelTask enum). Only
the five named files are touched, and `mvn verify` is green (doc-only no-op).

## Out-of-scope

Doc-only: no code, test, or migration. USER_GUIDE.md is untouched (no F1–F5
finding lands there — `/pending` is bot-admin so it goes in ADMIN_GUIDE).
`docs/spec/commands.md` is untouched (the canonical command index is the correct
source of truth that the guides should catch up to). The asset `--vs` per-source
currency behavior (audit finding F6) is deliberately **not** weakened here: that
is an implementation fix in M1-671 that makes the code match the doc, so
USER_GUIDE's per-source currency text stays as the intended contract. OVERVIEW.md
and DEVELOPER.md audited clean for F1–F5.

## Notes

- House style: surgical edits matching the surrounding voice (see M1-469 for the
  reference tone). Fix the wrong claim; don't rewrite surrounding prose.
- For F2, the simplest correct fix is to relabel the ollama row with the shipped
  default chat model (`llama3.1:8b`) and adjust the "~50 s" / RAM commentary to
  match (or add a one-line note that the table measured a smaller non-default
  model). The table's "rough yardstick, not a promise" framing can stay.
- For F3, the README is the drift, not D57 — mirror D57's posture (summarizer +
  chat can both send post bodies; chat also sends private messages) rather than
  re-deriving it. The separately-verified claim "source post bodies are never sent
  to a translator" (README.md:129) is TRUE and stays untouched.
- For F5, D54 and D57 live in the same file — make D54's task list/count
  consistent with D57 and the `ModelTask` enum rather than editing D57.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-670-release-audit-doc-drift.md
```
