---
id: M1-758
title: "switch-llm.sh: translator now carries private user messages"
status: pending
created: 2026-08-03
last_updated: 2026-08-03
blocked_by:
  - M1-746
files_budget: 3
files_scope:
  - prod/switch-llm.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/SwitchLlmWiringTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    `docs/spec/security.md`. M1-746 already corrected the spec-side
    disclosure (§Rate limiting and §Secrets handling); this ticket carries
    only the operator-facing text the script PRINTS at switch time. Editing
    the spec here would duplicate a landed change.
  - >-
    Changing which backend any task routes to, the `LLM_TASKS` list, or the
    config-writing logic. This is a disclosure-text correction only — a diff
    that alters routing has changed the deployment, not the warning about it.
  - >-
    Adding a locality constraint pinning `ModelTask.TRANSLATOR` local. That
    would be a real behavioural change needing its own decision (the D54
    embedding-locality precedent is a spec-level commitment, not a default to
    extend by analogy).
acceptance:
  - >-
    The `translator` line in the Phase 4 privacy disclosure states that for a
    scope on a non-English `/lang`, the user's RAW chat message is sent to the
    remote provider — not only "translation of the bot's replies to you". After
    M1-746 the query-anchoring leg passes the D28 pre-fetch's query, which IS
    the user's message (truncated, not redacted), to `ModelTask.TRANSLATOR`.
    The current text understates this to bot-reply echo.
  - >-
    `translator` is grouped with `chat` in the loud/private tier rather than
    reading as topic-interest exposure. The script's own Phase 4 comment says
    "A wrong claim here is a security defect"; an operator deciding whether to
    switch backends must see both tasks that carry private messages.
  - >-
    The header comment above `LLM_TASKS` (and the Phase 4 block comment
    asserting "chat carries PRIVATE user DMs" as the sole private-data task)
    is corrected to match, so the next reader does not restore the old
    grouping from the comment.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/SwitchLlmWiringTest.java
      — asserts the remote-backend disclosure names the translator task's
      private-message exposure, so the text cannot silently regress to the
      bot-reply-only wording.
  preserves:
    - >-
      Every existing SwitchLlmWiringTest assertion, including the positional
      stdin sequence (adding a task to `LLM_TASKS` shifts it by one slot —
      this ticket adds no task, so the sequence is unchanged).
    - >-
      The per-task shape of the disclosure. The text must stay per-task and
      must never become a blanket "privacy sacrificed" line.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D58
  - D28
  - D56
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
escalation_reason:
---

# M1-758: switch-llm.sh — translator now carries private user messages

## Context

M1-746 added the query-anchoring translation leg: for a scope on a non-English
`/lang`, `SemanticSearchTool` sends the search query to `ModelTask.TRANSLATOR`
before embedding it. On the D28 pre-fetch path that query is the user's raw
chat message (`ChatAgent.buildSemanticRetrievalBlock`, truncated to
`SEMANTIC_QUERY_MAX_CHARS`, not redacted).

`prod/switch-llm.sh`'s Phase 4 disclosure still describes the pre-M1-746 world:

```
translator — translation of the bot's replies to you; exposes the bot-reply
text (which can echo your queries).
```

That was accurate when the translator only rendered bot prose. It now
understates the exposure: the operator is told `chat` is the one task carrying
private messages, and switches backends on that basis, while `translator`
carries them too.

Found by the M1-746 r6 red-team (claude, INFO-LEAK medium),
`docs/plan/m1/redteam-multi/M1-746-2026-08-03-r6/`. M1-746 corrected the
spec-side disclosure; the script's runtime text was outside its `files_scope`,
which is why it is a separate ticket rather than a silent scope widening.

## Approach

Text-only. The script's own comment already states the standard this change
holds it to — "A wrong claim here is a security defect, so the text is
per-task, never a blanket 'privacy sacrificed' line" — so the fix keeps the
per-task shape and moves `translator` into the loud tier beside `chat`.

## Out-of-scope

The spec (landed in M1-746). Routing changes. Any locality constraint on
`ModelTask.TRANSLATOR` — pinning the translator local would genuinely close
the exposure rather than disclose it, but that is a behavioural decision with
its own cost (no local translator is configured on the remote-llm profile) and
needs its own ticket.

## Notes

- The exposure is conditional on a non-English scope. `en` scopes are a strict
  no-op in the translator leg, so today's `en`-only deployments send nothing
  new. The disclosure must still be accurate for the `cs` scopes the
  `LanguageRegistry` already enables.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-758-switch-llm-translator-privacy-disclosure.md`
