---
id: M1-526
title: "Bot-admin /recover-pool command to recover the auto_joined_group pool"
status: pending
created: 2026-06-29
last_updated: 2026-06-30
blocked_by:
  - M1-525
files_budget: 9
files_scope: []
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-519
out_of_scope:
  - "Automatic leave-detection freeing (M1-522 items 1+2) — implemented by M1-525; not re-implemented or modified here."
  - "The D47 caps (per-user-activation-cap, global-max-groups) and their check-then-act enforcement in the auto-join path (M1-519) — unchanged. This command only frees existing slots within the cap model; it does not alter cap values or the cap check."
  - "Any new or altered DB GRANT/REVOKE or Flyway migration — M1-525 V56's infochat_provider GRANT UPDATE(removed_at, ...) ON auto_joined_group is reused unchanged, so migration_touch stays false. DELETE stays revoked (append-only, M1-519 V55)."
  - "Bulk/mass freeing (a 'free all slots' mode) — one slot per free invocation, so an admin cannot reset every cap at once."
  - "A confirm/intent two-step gate (the /ban pattern) — the free is single-step. The effect is low-stakes and reversible (removed_at is a soft append-only marker; a re-join reactivates the slot per V56), so there is no _INTENT AuditAction."
  - "A known-group reconciliation SPI to AUTO-detect the residual join-only SimpleX group (the deferred item in M1-525 out_of_scope) — /recover-pool is the manual in-band recovery for that residual, not an automated detector."
acceptance:
  - >-
    A new bot-admin slash command /recover-pool is handled by a CommandHandler in
    app.zcat.infochat.provider.command, registered and dispatched like the other
    privileged-tier handlers (e.g. BanCommandHandler). Authorization is a
    deterministic bot-admin (user.is_admin) check in Java — NEVER exposed as an
    LLM tool — and the command is DM-only. A non-admin caller (and a group-scope
    invocation) receives a localized error reply and performs no DB mutation. A
    named test (RecoverPoolCommandHandlerTest) asserts a non-admin invocation is
    rejected with no removed_at write.
  - >-
    /recover-pool with no argument replies with the current ACTIVE auto_joined_group
    pool — each entry's natural key (adapter + upstream_group_id), inviter, and
    joined-at — via a new GroupJoinRepository read method that returns only rows
    with removed_at IS NULL. This is the discovery path for residual join-only
    SimpleX entries that have no groups row (so /list-groups cannot surface them).
    A named test asserts the list reflects active pool entries and excludes
    already-freed ones.
  - >-
    /recover-pool <natural-key-ref> frees the matching auto_joined_group slot by
    setting removed_at, reusing M1-525's
    GroupJoinRepository.markRemovedByNaturalKey(adapter, upstreamGroupId) — the
    natural-key path, so it works even for a join-only group that has no groups
    row. No new DB GRANT or migration: the UPDATE runs under the infochat_provider
    role, which already holds GRANT UPDATE (removed_at, ...) ON auto_joined_group
    from M1-525 V56; DELETE stays revoked. A named test asserts a bot-admin
    recovers a SATURATED pool: with the global auto_joined_group count at the D47
    global cap, freeing one slot sets its removed_at, decrements
    GroupJoinRepository.countJoins(), and a subsequent re-record/auto-join then
    succeeds (M1-519 redteam Finding 2).
  - >-
    Each successful free writes exactly one audit row with a new
    AuditAction.RECOVER_AUTO_JOINED_GROUP verb (actor = the admin) in the SAME
    transaction as the removed_at UPDATE (audit-before-effect). All success and
    error reply strings (not-admin, not-a-DM, empty pool, natural key not
    found / already freed) are keyed in BundleKeys and present in BOTH
    bundles/en.properties and bundles/cs.properties (parity enforced by
    BundleLoaderTest). /recover-pool is added to the bot-admin group of
    docs/spec/commands.md §Permission model AND to LlmOutputSanitizer.CLOSED_LIST,
    keeping LlmOutputSanitizerTest.matchSetEqualsSpecClosedList green
    (byte-equality between spec and code).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "RecoverPoolCommandHandlerTest — non-admin/group-scope rejection (no removed_at write); no-arg list mode reflects active pool and excludes freed rows; free-by-natural-key sets removed_at and decrements countJoins(); saturated-pool recovery lets a subsequent auto-join succeed; audit row written with RECOVER_AUTO_JOINED_GROUP."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-522
reopens: []
redteam_findings: []
clarity_check: {}
revisions:
  - date: 2026-06-30
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 8
      complexity: low
      risk: low
      round_cap: 2
      security_relevant: false
      migration_touch: false
      out_of_scope: []
      acceptance: []
      spec_refs: []
      decision_refs: []
      note: >-
        Skeleton from the M1-522 decompose; §Acceptance and §Out-of-scope were
        TODO placeholders. clarity-fail blockers: empty acceptance + empty
        out_of_scope. Refine names the command /recover-pool (single-step free,
        no confirm gate — user decisions), authors the acceptance/out_of_scope,
        and recalibrates sizing. Two corrections vs the skeleton Notes: (1) NO
        new DB GRANT/migration is needed — M1-525 V56 already granted the
        provider role UPDATE(removed_at), so migration_touch stays false; (2) the
        command must free by NATURAL KEY (markRemovedByNaturalKey) plus a list
        mode, because its primary target is the residual join-only SimpleX group
        that has no groups row (M1-525 out_of_scope), which a groups.id path
        cannot reach — this adds a GroupJoinRepository read method, so
        files_budget rises 8 -> 9 to match the clarity reviewer's 8-9
        enumeration. security_relevant flips to true (admin gate + audit) and
        risk to medium (reversible state mutation, reuses M1-525's tested
        freeing method, no new DB privilege).
      clarity_check:
        date: 2026-06-29
        verdict: FAIL
        blockers:
          - "acceptance: [] is empty and the body §Acceptance is marked TODO. Author runnable criteria: name the command, the bot-admin authorization check, the asserted DB state (removed_at), and the named test class/method."
          - "out_of_scope: [] is empty and the body §Out-of-scope is marked TODO. Move the sketched boundaries (automatic leave-detection freeing belongs to M1-525; D47 total caps belong to M1-519) into named entries."
        warnings:
          - "risk: low should be medium/high before start — admin-tier gate change with a DB GRANT (Notes flag this explicitly)."
          - "security_relevant: false should be true before start — admin-tier gate, DB GRANT, audit log (Notes flag this explicitly)."
escalations:
  - date: 2026-06-29
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (2 blockers, 2 warnings)
      BLOCKER 1: acceptance: [] empty; body §Acceptance marked
        "TODO — fill in before /m1-tick start M1-526". Not runnable.
      BLOCKER 2: out_of_scope: [] empty; body §Out-of-scope marked TODO.
      WARN: risk: low and security_relevant: false both miscalibrated for an
        admin-tier DB-GRANT command (the ticket Notes already direct flipping
        both before start).
---

# M1-526: Bot-admin /recover-pool command to recover the auto_joined_group pool

## Context

Decomposed from M1-522 (the plan-writer flagged that M1-522's four acceptance
items could not fit `files_budget: 8`). This child carries the in-band bot-admin
recovery command (M1-522 acceptance item 3): a deterministic bot-admin command
that frees `auto_joined_group` slots from chat so a flooded global pool is
recoverable in-band, not only via operator psql under the DB owner role (M1-519
redteam Finding 2). It is `blocked_by` M1-525 because it depends on the
`auto_joined_group.removed_at` column and the `GroupJoinRepository` freeing
method M1-525 introduces.

**Primary target — the residual join-only SimpleX group.** M1-525 frees a slot
automatically when the bot is removed (native event on Signal) or when a
permanent-delivery-failure signal fires. But a pure-join-only SimpleX group that
is never `@mention`ed has no `groups` row and is never sent to, so neither path
can detect a leave (M1-525 `out_of_scope`). Those residual rows can saturate the
D47 global cap with no automatic relief. `/recover-pool` is the manual in-band
recovery for exactly that residual: because such a row has no `groups` row, the
command frees by **natural key** (`adapter` + `upstream_group_id`) via M1-525's
`markRemovedByNaturalKey`, and a **list mode** surfaces the active pool so the
admin can read off the natural key to free (`/list-groups` cannot show a row
with no `groups` row).

**No new DB privilege.** Contrary to the original skeleton Notes, this ticket
adds **no** GRANT and **no** migration: M1-525's `V56` already granted
`infochat_provider` `UPDATE (removed_at, inviter_user_id) ON auto_joined_group`,
and every user-facing command runs as `infochat_provider`, so `/recover-pool`
reuses that existing column-scoped grant. `migration_touch` stays `false`. The
ticket is `security_relevant: true` only because it adds an admin-tier
authorization gate and an audit verb.

## Acceptance

1. **Command + authorization.** A new bot-admin slash command `/recover-pool` is
   handled by a `CommandHandler` in `app.zcat.infochat.provider.command`,
   registered and dispatched like the other privileged-tier handlers. The
   authorization is a deterministic bot-admin (`user.is_admin`) check in Java —
   never an LLM tool — and the command is DM-only. A non-admin caller and a
   group-scope invocation each get a localized error reply with no DB mutation.
   A named test (`RecoverPoolCommandHandlerTest`) asserts a non-admin invocation
   is rejected with no `removed_at` write.
2. **List mode (discovery).** `/recover-pool` with no argument replies with the
   current ACTIVE pool — each entry's natural key (`adapter` +
   `upstream_group_id`), inviter, and joined-at — via a new `GroupJoinRepository`
   read method that returns only rows with `removed_at IS NULL`. A named test
   asserts the list reflects active pool entries and excludes freed ones.
3. **Free mode.** `/recover-pool <natural-key-ref>` frees the matching
   `auto_joined_group` slot by setting `removed_at`, reusing M1-525's
   `GroupJoinRepository.markRemovedByNaturalKey(adapter, upstreamGroupId)` — the
   natural-key path, so it works for a join-only group with no `groups` row. No
   new GRANT/migration (see Context). A named test asserts a bot-admin recovers a
   SATURATED pool: at the D47 global cap, freeing one slot sets `removed_at`,
   decrements `countJoins()`, and a subsequent auto-join then succeeds (M1-519
   redteam Finding 2).
4. **Audit + reply + closed list.** Each successful free writes one audit row
   with a new `AuditAction.RECOVER_AUTO_JOINED_GROUP` verb in the same
   transaction as the UPDATE (audit-before-effect). Reply strings (success +
   not-admin, not-a-DM, empty pool, key-not-found/already-freed) are keyed in
   `BundleKeys` and present in BOTH `bundles/en.properties` and
   `bundles/cs.properties` (`BundleLoaderTest` parity). `/recover-pool` is added
   to the bot-admin group of `docs/spec/commands.md` §Permission model AND to
   `LlmOutputSanitizer.CLOSED_LIST`, keeping
   `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` green.
5. `mvn -B verify` is green from the repo root.

## Out-of-scope

- Automatic leave-detection freeing (M1-522 items 1+2) — implemented by M1-525;
  not re-implemented or modified here.
- The D47 caps and their check-then-act enforcement (M1-519) — unchanged; this
  command only frees existing slots within the cap model.
- Any new or altered DB GRANT/REVOKE or Flyway migration — M1-525 `V56`'s
  `infochat_provider` UPDATE grant is reused unchanged; DELETE stays revoked.
- Bulk/mass freeing — one slot per free invocation; no "free all" mode.
- A confirm/intent two-step gate — the free is single-step (low-stakes,
  reversible via re-join reactivation); no `_INTENT` AuditAction.
- A known-group reconciliation SPI to auto-detect the residual join-only SimpleX
  group (M1-525 `out_of_scope`) — `/recover-pool` is the manual in-band recovery
  for that residual, not an automated detector.

## Notes

- **File surface (~9, the `files_budget`):** new `RecoverPoolCommandHandler`;
  new `GroupJoinRepository` list/read method; `AuditAction` (new verb);
  `BundleKeys` + `bundles/en.properties` + `bundles/cs.properties` (reply
  strings, `BundleLoaderTest` parity); `docs/spec/commands.md` §Permission model
  AND `LlmOutputSanitizer.CLOSED_LIST` (byte-equality enforced by
  `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList`); new
  `RecoverPoolCommandHandlerTest`. To stay within budget, REUSE the existing
  freeing method (`markRemovedByNaturalKey`) and the existing audit-writer path
  rather than adding new freeing/audit plumbing; if group-scope rejection needs
  a helper already present for other DM-only commands, reuse it. If a 10th file
  proves unavoidable, that is a `budget-breach` escalation, not a silent
  expansion.
- **Single-step, free-by-natural-key (decided at refine):** the command is not
  confirm-gated, and frees by natural key rather than `groups.id` — see Context
  for why the natural-key path is required (residual rows have no `groups` row).
- Parent context: `docs/plan/m1/tickets/M1-522-auto-join-slot-freeing.md`;
  sibling `docs/plan/m1/tickets/M1-525-free-auto-join-slots-on-leave.md`; M1-519
  redteam audit: `docs/plan/m1/redteam/M1-519-2026-06-29.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-526-*.md
```
