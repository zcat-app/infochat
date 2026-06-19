---
id: M1-405
title: "provider: validate ChatToolDispatcher args before the call cap"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The clampLimit logic and the per-turn cache-key derivation (M1-375 set the cache key on clamped args) — unchanged.
  - The per-turn call cap value and the "Tool call limit exceeded for this turn" ValidationError text — unchanged.
  - The set of input-length / size limits enforced by validateInputLengths — unchanged; only WHICH map it reads and WHEN it runs change.
acceptance:
  - "validateInputLengths runs on validatedArgs (the same map used for the cache key and the actual tool dispatch), not the raw args map, so the length check validates exactly the map that is executed."
  - "The length check runs BEFORE the per-turn cache lookup, the turn.callCount++, and the cap check, so an over-length tool call is rejected as a ValidationError without consuming a per-turn call-budget slot (the budget bounds executions, and a length-rejected call is not an execution)."
  - "A test in infochat-provider/src/test/java/app/zcat/infochat/provider/chat asserts an over-length argument is rejected as a ValidationError and turn.callCount is NOT incremented by that rejected call."
  - "ChatToolDispatcherTest and ChatAgentToolArgsTest remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat (length-rejected call does not charge the cap)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-405: validate ChatToolDispatcher args before the call cap

## Context

Deep-review full (2026-06-19) provider finding **F1** (MAINTAINABILITY-RULES-DRIFT).
Verified at source 2026-06-19:

In `ChatToolDispatcher.dispatch`
(`infochat-provider/.../chat/ChatToolDispatcher.java:149-194`) two small
inconsistencies sit together. (1) `validateInputLengths(args)` runs at line 172,
AFTER `turn.callCount++` and the cap check (166-170), so a model that emits an
over-length argument burns a per-turn call-budget slot on a call that never runs any
SQL — even though the budget comment (165) says it "counts only non-cached
executions" and the class header advertises rejecting oversized inputs "before any
SQL runs". (2) The length check reads the raw `args` map while the cache key (158)
and the actual dispatch (179) use `validatedArgs`. Because `clampLimit` only mutates
the numeric `limit`, the two maps differ today only in a value the length check
never inspects, so the divergence is currently harmless — but it is invisible to a
future reader: anyone who later makes a clamp touch a string/list value would
silently validate one map and dispatch another.

Low severity, no current security or correctness impact — a clarity/accounting
nicety that makes the advertised "validate the thing you dispatch, before charging
the budget" contract actually hold. This is the same method M1-375 (done) recently
touched (cache key on clamped args).

## Acceptance

See frontmatter. Move the length check to run on `validatedArgs` and before the
cache/cap block. Well-formed calls behave identically.

## Out-of-scope

See frontmatter. The clamp, cache key, cap value, and length-limit set are all
unchanged.

## Notes

- Minor behavioral edge: an over-length argument that today happens to be the call
  that trips the cap will now surface the length ValidationError instead of the cap
  ValidationError. Both are model-self-correctable ValidationErrors, so the
  user-visible effect is nil. The existing oversized-input tests
  (rejectsOversizedInput, rejectsOversizedList) assert a ValidationError is returned,
  which stays true — no existing test pins the cap-before-length ordering, so none
  needs editing; this ticket only adds the callCount-not-charged assertion.
