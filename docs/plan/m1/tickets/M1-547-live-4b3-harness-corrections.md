---
id: M1-547
title: Live 4b-3 harness corrections (D51 mention shape + confirm-gated mint scenarios)
status: done
created: 2026-07-03
last_updated: 2026-07-03
reviews:
  - round: 1
    date: 2026-07-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 63
      removed: 39
clarity_check:
  date: 2026-07-03
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: consider security_relevant: true or a one-line
      justification for false despite the invite-gate/admin-command surface
      (the change is test-harness-only; no production code)"
    - "SPEC-REFS-VALID: 'commands.md §Admin' has a second substring candidate
      ('group-admin race'); tie-break resolves to 'Admin (bot admin)' l.937 —
      consider tightening the anchor"
  blockers: []
blocked_by: []
files_budget: 4
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any main-scope (production) code change — test-scope only; in particular
    NO change to SimpleXMessageCodec or the production adapters
  - the F-live-6 fix (per-task max-tokens for OpenAiCompatibleProvider) —
    that is a separate production ticket
  - the F-live-4 fix (live-reset.sh provider_state re-seed) — separate ticket
  - s12/chat-mode scenario changes (blocked on F-live-6)
  - new scenario directives or ScenarioRunner/grammar changes
acceptance:
  - LiveSimpleXClient's D51 mention envelope composes
    `mentions{<localDisplayName>: <numeric local groupMemberId>}` (a JSON
    number), NOT the former `{memberId: <base64>}` object shape, which real
    simplex-chat v6.5.4.1 rejects with `commandError` ("bad chat command:
    Failed reading: empty"); GroupMember carries the numeric groupMemberId
    resolved from the `/members` response's `groupMemberId` field.
  - LiveSimpleXHarnessFrameTest pins the corrected envelope (numeric mention
    value; still exactly the production composed message plus the mentions
    object) and stays the declared landing spot for live wire-shape
    corrections (M1-546 convention).
  - The s03 and s15 live scenarios drive `/invite create --open` through its
    confirm gate (expect the confirm prompt, then send
    `/invite create --open confirm`, then expect `Invite code:`) — the
    --open path is confirm-gated per docs/spec/commands.md §Admin; only
    --contact mints immediately.
  - mvn verify is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main; LiveSimpleXHarnessFrameTest updated
      in place (5 tests) remains hermetic (no live gate)
spec_refs:
  - docs/spec/commands.md §Admin
  - docs/spec/verification.md §Test layers
decision_refs:
  - D51
  - D-live-9
---

## Context

The first live 4b-3 scenario run (2026-07-03, HANDOFF running log) landed the
two corrections M1-546 declared as live-discovery items:

1. **D51 mention envelope wire shape.** The best-guess outbound shape
   mirrored the inbound mention object (`mentions{name: {memberId}}`). Real
   simplex-chat v6.5.4.1 rejects it at command parse. Probing raw `/_send`
   forms on the real CLI showed the accepted value is the sender-local
   NUMERIC `groupMemberId` (simplex-chat itself resolves it to the wire
   memberId the bot byte-compares). A WS probe of `/members live-group`
   confirmed member objects carry `groupMemberId` alongside `memberId`.
2. **Confirm-gated open mint in s03/s15.** `/invite create --open` first
   replies with the confirm prompt and mints only on
   `/invite create --open confirm` (spec §Admin; the --contact path the
   InMemory twin scenario uses is exempt, so CI never exercised the gate).

## Live evidence (already gathered with exactly this diff)

- s03 GREEN over real relays: confirm prompt matched 991 ms, mint+capture
  1112 ms, consume 1012 ms.
- s07 GREEN (5/5 steps ~1 s) — the corrected mention envelope drove the D47
  group lifecycle end to end; group-scope `/follow-tag` and `/digest off`
  fixtures were also delivered live via the corrected raw `/_send` form.
- s15 GREEN (6/6 steps) after the confirm-leg fix.
- LiveSimpleXHarnessFrameTest 5/5 green hermetically.
