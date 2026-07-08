---
id: M1-595
title: "Chat-mode audit rows record no actor_contact_id — a chat interaction is unattributable"
status: done
created: 2026-07-08
last_updated: 2026-07-08
clarity_check:
  date: 2026-07-08
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Logging any user-authored chat prose (the message text, the LLM reply, or
    tool arguments) into the audit row. The existing rule stands: the CHAT_MODE
    row carries ONLY actor + scope, no content (ChatAgent.writeAuditRow comment,
    D37 data-minimization). This ticket adds the ACTOR's identity columns, not
    the chat content — actor_contact_id/actor_adapter are denormalized identity
    (schema.md §Identity and access), not user prose.
  - >-
    The AuditLogWriter INSERT path and the RedactionHook. The writer already
    binds actor_contact_id (param 2) and actor_adapter (param 3) and every
    command handler already supplies them via AuditRow.builder(); this ticket
    only makes ChatAgent's ONE call site populate the two fields it currently
    leaves null. Do NOT touch infochat-core AuditLogWriter / RedactionHook.
  - >-
    Threading contactId/adapter through new method parameters. Both values are
    already available on the request-scoped InboundContext ChatAgent injects
    (senderContactId(), adapterName()) — the router sets them at intake
    (InboundRouter setAdapterName/setSenderContactId) before chat dispatch, and
    ChatAgent already reads inboundContext.effectiveLanguage() on the same path.
    Read the two identity fields from that same InboundContext; do NOT widen
    handleTurn / doHandle / writeAuditRow signatures.
  - >-
    Backfilling actor_contact_id onto the CHAT_MODE rows already written with a
    null actor (the live rows observed 2026-07-08). This ticket makes NEW rows
    attributable; it is not a data migration (migration_touch: false).
  - >-
    Auditing whether any OTHER audited action leaves actor_contact_id null. The
    finding is scoped to CHAT_MODE specifically (every other observed action —
    VOUCH, PENDING_LIST, INVITE_CONSUME — already carries the actor id). A
    general audit-attribution sweep is a separate investigation.
acceptance:
  - >-
    Investigation recorded: the ticket's implementer confirms WHY the CHAT_MODE
    audit row's actor_contact_id/actor_adapter are null today (ChatAgent's
    writeAuditRow builds the AuditRow with only .actorUserId(userId), omitting
    .actorContactId(...)/.actorAdapter(...) that every command handler supplies)
    and confirms it is NOT a deliberate privacy omission (the actor's contact id
    is not chat content, it is the same denormalized identity schema.md
    §Identity and access defines as an audit_log column, and it is already in
    scope on the request-scoped InboundContext). The §Notes section states this
    finding explicitly. IF investigation instead concludes the omission is
    deliberate, the fork in the next item applies.
  - >-
    PRIMARY (gap confirmed): ChatAgent.writeAuditRow populates the CHAT_MODE
    audit row's actor identity from the request-scoped InboundContext —
    .actorContactId(inboundContext.senderContactId()) and
    .actorAdapter(inboundContext.adapterName()) added to the AuditRow.builder()
    chain alongside the existing .actorUserId(userId) — so a chat interaction is
    attributable in the audit log exactly like every other audited action
    (VOUCH, PENDING_LIST, INVITE_CONSUME). No content is added: the row still
    carries only actor + scope, the existing no-prose comment stands.
    ALTERNATIVE (only if investigation shows the omission is deliberate): instead
    of populating, ADD a code comment at writeAuditRow AND a design note under
    docs/design/ explaining the deliberate divergence, so the inconsistency is
    intentional and documented rather than a silent gap. Exactly one of the two
    forks is implemented, not both.
  - >-
    NAMED TEST (primary fork): ChatAgentAuditActorTest exercises the REAL
    writeAuditRow (not the no-op override ChatAgentTest uses) with a capturing
    AuditLogWriter test double and an InboundContext carrying a known
    senderContactId + adapterName, and asserts the captured RedactionHook.AuditRow
    reports action() == CHAT_MODE, actorContactId() equal to the InboundContext's
    senderContactId, and actorAdapter() equal to its adapterName (previously both
    null). Red-before / green-after on the two identity assertions. (If the
    ALTERNATIVE fork is taken instead, this test is replaced by a wiring/source
    assertion pinning the documented divergence — the fork is decided in item 2.)
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
      — captures the AuditRow ChatAgent.writeAuditRow builds and asserts the
      CHAT_MODE row carries actorContactId + actorAdapter from InboundContext.
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      ChatAgentTest's existing no-op writeAuditRow override and its
      reply/tool-loop assertions (that class does not exercise the audit row's
      actor columns; the new test does).
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/security.md §DB roles
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 74
      removed: 7
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-08
    verdict: CLEAN
    base: main (c6438a87)
    head: working-tree (m1/M1-595-chat-mode-audit-actor branch, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-595-2026-07-08.md
    out_of_model_count: 0
    note: |
      Pre-commit --in-progress audit of the CHAT_MODE actor-attribution
      change. CLEAN — no findings. Populating actor_contact_id/actor_adapter
      is a pure audit-coverage improvement; the raw column is redaction-masked
      in audit_log_view, so it widens no operator-facing exposure. Nothing
      feeds a future ticket.
---

# M1-595: chat-mode audit rows record no actor_contact_id

## Context

Found 2026-07-08 during live testing. Reading the audit log, a free-form
chat interaction writes a row with `action='CHAT_MODE'` but an **empty
`actor_contact_id`**, while every other audited action records its actor:

```
VOUCH|4          ← actor contact id 4
PENDING_LIST|4   ← actor contact id 4
INVITE_CONSUME|7 ← actor contact id 7
CHAT_MODE||...   ← actor contact id BLANK
```

So a chat interaction cannot be attributed to the user who made it in the
same way every other privileged action can. The `audit_log` schema defines
`actor_contact_id` and `actor_adapter` as denormalized identity columns
"denormalized at write time for redaction-free historical lookup"
(`schema.md` §Identity and access) — they are actor identity, not the user's
chat prose, so recording them does not violate the row's no-content rule.

Root cause (verified in code): `ChatAgent.writeAuditRow` (chat/ChatAgent.java
~line 496) builds its `RedactionHook.AuditRow` with only
`.actorUserId(userId)` — it never calls `.actorContactId(...)` /
`.actorAdapter(...)`, so those two columns bind SQL NULL. Every command
handler DOES supply them (e.g. `GrantAdminCommandHandler`,
`PendingCommandHandler`, `InviteCommandHandler` all chain
`.actorContactId(actor.contactId).actorAdapter(adapter)`), which is why their
rows carry the actor id and CHAT_MODE's does not. The `AuditLogWriter` INSERT
already binds both columns (params 2 and 3); the values were simply never
threaded into this one call site.

This is a **gap, not a deliberate privacy decision**: the actor's contact id
is not chat content, and it is already in scope on the request-scoped
`InboundContext` that ChatAgent injects. `InboundRouter` sets
`setAdapterName(...)` and `setSenderContactId(...)` at intake (well before the
chat dispatch), and ChatAgent already reads `inboundContext.effectiveLanguage()`
on the same path — so `senderContactId()` and `adapterName()` are populated and
available when `writeAuditRow` runs.

## The fix

In `ChatAgent.writeAuditRow`, add the two identity fields to the existing
`AuditRow.builder()` chain, read from the already-injected `InboundContext`:

```java
.actorUserId(userId)
.actorContactId(inboundContext.senderContactId())
.actorAdapter(inboundContext.adapterName())
.action(AuditAction.CHAT_MODE)
```

No new method parameters, no AuditLogWriter change, no content added — the row
still carries only actor + scope (the existing no-user-prose comment stands).
A chat interaction becomes attributable in the audit log exactly like every
other audited action.

The audit-write is exercised today only via `ChatAgentTest`'s **no-op**
`writeAuditRow` override, so no existing test asserts the actor columns. A new
`ChatAgentAuditActorTest` runs the real `writeAuditRow` against a capturing
`AuditLogWriter` double (reuse the existing `SanitizerTestDoubles.noOpDataSource`
proxy Connection so no real DB is needed) plus an `InboundContext` carrying a
known `senderContactId`/`adapterName`, and asserts the captured row reports
those two values. Red-before (both null) / green-after.

## Out-of-scope

See frontmatter. Notably: no chat prose in the row (identity only), no
AuditLogWriter/RedactionHook change (writer already binds both columns), no new
method-signature threading (values come off the injected InboundContext), no
backfill of the already-written null-actor rows (not a migration), and no
general sweep of other actions' attribution (CHAT_MODE-only).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX test-user walkthrough).
  Not a red-team finding.
- **Intent determination (acceptance item 1).** Confirmed a gap, not a
  deliberate omission: the actor contact id is denormalized identity
  (`schema.md` §Identity and access), not the user's chat content, and it is
  already resident on the request-scoped `InboundContext`. The PRIMARY fork
  (populate) therefore applies. The ALTERNATIVE fork (comment + design note)
  exists only as the escape hatch if the implementer's own investigation reaches
  a different conclusion; it is not the expected path.
- **Attribution vs. redaction.** `actor_contact_id` is redaction-masked in the
  read-side `audit_log_view` (`security.md` §DB roles), so populating the raw
  column does not widen the operator-facing exposure surface — it only makes the
  underlying row attributable, consistent with every other action.
