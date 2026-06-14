---
id: M1-326
title: "Nostr: gate relay productivity on signature-verified delivery"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The EOSE arm. EOSE (end-of-stored-events) is genuine relay liveness independent of event validity and MUST keep calling markProductive().
  - The signature verifier itself (NostrEventVerifier) and the failedSig counter semantics — unchanged; this ticket only changes WHEN productivity is credited relative to the existing verify gate.
  - The health-tracker / cooldown / terminal-failed state machine — unchanged; this ticket feeds it the corrected signal, it does not alter its thresholds.
acceptance:
  - "In NostrRelayConnection.handleFrame, the NostrMessage.Event arm no longer calls markProductive() unconditionally before delivery. Productivity is credited only when the event crosses the signature trust boundary: the eventSink reports acceptance (it already returns boolean from NostrStreamSource.enqueueInbound, which runs verifier.verify() and returns false on failure) and markProductive() fires only on a true result. The eventSink field type changes from Consumer<NostrEvent> to a boolean-returning functional type (e.g. Predicate<NostrEvent>) so the accept result is observable; NostrStreamSource passes this::enqueueInbound unchanged."
  - "The EOSE arm (NostrMessage.Eose) still calls markProductive() unconditionally — answering the REQ with end-of-stored-events is relay liveness regardless of event validity."
  - "A unit/integration test pins the degradation-evasion case: a connection fed a stream of well-framed EVENT frames whose signatures all fail verify() does NOT credit productivity (productiveSinceConnect stays false / healthTracker.recordSuccess is not called on the verify-fail path), so the unproductive-close recordFailure path at NostrRelayConnection runLoop can still fire and the per-relay cooldown/terminal-failed escalation is reachable. A companion test pins that a single verify-passing EVENT DOES credit productivity (no regression)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr (productivity-after-verify cases)
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 249
      removed: 21
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — budget-breach. The mandated Consumer<NostrEvent> →
      Predicate<NostrEvent> signature change forces edits to two direct
      test construction sites (NostrSsrfTest:148, NostrSsrfIT:105, both
      pass a void-body `event -> {}` Consumer lambda that cannot satisfy
      Predicate). Total files: NostrRelayConnection.java + new productivity
      test + NostrSsrfTest + NostrSsrfIT = 4, vs files_budget: 3. All four
      are inside files_scope; only the numeric budget is exceeded, by one.
      NostrStreamSource.java is NOT touched (this::enqueueInbound already
      returns boolean, so it adapts to Predicate unchanged), so the clarity
      estimate of "2 prod + 1 test" was off: actual is 1 prod + 3 test.
revisions:
  - date: 2026-06-14
    reason: budget-breach refine — the mandated Consumer<NostrEvent> →
      Predicate<NostrEvent> eventSink type change forces edits to two direct
      NostrRelayConnection construction sites in pre-existing tests
      (NostrSsrfTest:148, NostrSsrfIT:105), each passing a void-body
      `event -> {}` Consumer lambda that cannot satisfy Predicate. Total file
      count is 4 (NostrRelayConnection.java + new productivity test +
      NostrSsrfTest + NostrSsrfIT), not 3: NostrStreamSource.java is NOT
      touched because this::enqueueInbound already returns boolean and adapts
      to Predicate unchanged. Bump files_budget 3→4, add a test_plan.modifies
      list to authorize the two mechanical SSRF-test call-site fixes, and add a
      §Notes line scoping those edits to a signature-forced lambda swap with no
      assertion change. No acceptance-item, production-scope, or migration
      change.
    prior_values: |
      files_budget: 3
      test_plan had no modifies list (all test work was additive).
      clarity_check: PASS 2026-06-14 (evaluated the files_budget:3 ticket; its
        estimate assumed 2 prod + 1 test, missing the two SSRF-test call sites
        and wrongly counting NostrStreamSource.java as touched).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: 9a001c51a17ea615fa1ec41597e88ca5124ac60b
    head: "working-tree (uncommitted impl + refine commit on worktree-M1-326)"
    verdict_file: docs/plan/m1/redteam/M1-326-2026-06-14.md
    out_of_model_count: 0
    note: |
      In-progress audit (between review APPROVE round 1 and commit) on the
      security_relevant productivity-after-verify change. CLEAN — no gap
      between the threat model and the diff; no remediation needed.
---

# M1-326: Nostr — gate relay productivity on signature-verified delivery

## Context

Deep-review v5.5 (opus-48, `06-module-infochat-collector.md` F1) found that
`NostrRelayConnection.handleFrame` calls `markProductive()` on the
`NostrMessage.Event` arm **before** the event reaches the signature gate. The
sink (`NostrStreamSource::enqueueInbound`) runs `verifier.verify(event)` and is
where BIP-340 verification actually happens — but it is typed as a
`Consumer<NostrEvent>`, so its `boolean` return is discarded and productivity is
credited the moment a syntactically-valid EVENT *frame* arrives.

`markProductive()` sets `productiveSinceConnect=true` and calls
`healthTracker.recordSuccess`, which resets backoff and prevents the
unproductive-close `recordFailure` path from firing. A relay streaming a flood
of well-framed but signature-invalid EVENTs is therefore scored healthy:
backoff resets every connect, no cooldown is ever applied, and the all-relays-
bad / terminal-failed escalation can never trip for that relay.

`docs/spec/architecture.md` §"Per-relay (or per-endpoint) degradation" requires
that a relay "returning malformed events MUST NOT block the StreamSource" and is
marked "unusable for a cooldown window." A signature-invalid event flood is
exactly a relay "returning malformed events," so the current ordering is a
degradation-evasion primitive: a hostile relay holds a live socket and the
bot's reconnect attention without ever delivering a trusted event, silencing the
operator's cooldown/terminal safety valve. **Verified at source 2026-06-14:**
`eventSink` is `Consumer<NostrEvent>` (NostrRelayConnection.java:85);
`markProductive()` at line 324 precedes `eventSink.accept` (line 325);
`enqueueInbound` returns `boolean` and runs `verify()` (NostrStreamSource.java:186,191).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Recommended fix (report Option A): change the sink to a predicate and gate
  `markProductive()` on the post-verify acceptance result. Option B (feed
  `failedSig` into the tracker only at disconnect) does not cover the long-lived-
  socket variant and is rejected.
- The `failedSig` counter stays a log/observability surface; this ticket does
  not wire it into the health state machine.
- Test-modification authorization (budget-breach refine, 2026-06-14):
  `NostrSsrfTest` and `NostrSsrfIT` each construct `NostrRelayConnection`
  directly with a `event -> {}` event-sink lambda. The eventSink type change
  (`Consumer` → `Predicate`) forces those two lambdas to a boolean-returning
  form (`event -> true`). This is a mechanical compile-fix only — both are SSRF
  tests whose assertions never depend on the sink return value; no assertion is
  altered, weakened, or removed.
