---
id: M1-099
title: "Nostr per-relay degradation + cycle cap"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-096
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTracker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-provider/** — no provider changes
  - signature verification — M1-097
  - cross-relay dedup — M1-098
  - kind-6 linking — M1-100
  - SSRF on wss:// — M1-101
  - D42 fetcher failure ladder — M1-094 (applies to polled Fetcher sources; this is the StreamSource parallel)
acceptance:
  - "RelayHealthTracker tracks per-relay health state: healthy, cooldown, or permanently-failed"
  - "A relay that repeatedly disconnects or returns malformed events is marked unusable for a profile-driven cooldown window — one bad relay does not block the StreamSource"
  - "When all relays are in cooldown, the StreamSource waits until the earliest cooldown expires rather than tight-loop reconnecting"
  - "On the all-relays-bad transition, a throttled admin notification fires via ThrottledAdminNotifier (one notification per transition, not per relay)"
  - "When the first relay returns to healthy after an all-relays-bad state, a recovery notification fires"
  - "After a profile-driven number of consecutive all-relays-bad cycles, the StreamSource transitions to terminal failed state and stops reconnecting"
  - "On terminal failure, a one-time admin notification fires: 'StreamSource for source <id> permanently stopped: all-relays-bad cycle cap exhausted'"
  - "An operator must /source-enable to restart a terminally-failed StreamSource"
  - "RelayHealthTrackerTest.singleBadRelay_cooldownDoesNotBlockOthers passes — 1 of 3 relays enters cooldown; the other 2 continue receiving events"
  - "RelayHealthTrackerTest.allRelaysBad_waitsForEarliestCooldown passes — all relays in cooldown; the tracker signals wait-until = earliest expiry"
  - "RelayHealthTrackerTest.allRelaysBadCycleCap_terminalFailure passes — after N consecutive all-relays-bad cycles, the tracker returns terminal-failed state"
  - "RelayHealthTrackerTest.recoveryAfterAllRelaysBad_clearsCounter passes — one relay recovering resets the all-relays-bad cycle counter"
  - "NostrDegradationIT.relayDegradation_endToEnd passes — a QuarkusTest with 2 fake relays; one relay drops connections; events from the healthy relay continue flowing"
  - "Signature-verification failures (M1-097 failedSig counter) DO NOT contribute to RelayHealthTracker state — relay health is decided from connection-level events only (TCP drop, WSS frame errors, parse failures inside NostrRelayConnection)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
  modifies:
    # Constructor-arg propagation only — NostrStreamSource gains an 8th
    # parameter (RelayHealthTracker, added by this ticket; the 7th —
    # NostrDedupFilter — was added by M1-098 which landed on main while
    # this ticket was in-flight). These four files are the existing direct
    # call sites of `new NostrStreamSource(...)`. No test intent or
    # behavior is changed.
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 944
      removed: 34
  - round: 2
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 1008
      removed: 44
escalations:
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer-detected during implementation, before any review round.
      Discovered while wiring RelayHealthTracker into NostrStreamSource:
      the tracker integration cannot be completed without modifying
      infochat-collector/src/main/java/.../stream/nostr/NostrRelayConnection.java,
      which is NOT in files_scope. NostrRelayConnection owns the per-relay
      reconnect loop (lines 127–158) and the productive-frame detection (line
      180) — both must call tracker.recordFailure / recordSuccess / nextAttemptTime /
      isTerminal. The class exposes no external observation hook on its
      runLoop state (running, productiveSinceConnect, currentWebSocket are
      all private/volatile with no accessors), so a wrap-from-outside design
      requires either reflection or rewriting the WebSocket loop into
      NostrStreamSource (much larger change than just adding the file to scope).
revisions:
  - date: 2026-05-31
    reason: |
      refine after budget-breach (pre-implementation; widen files_scope to
      include NostrRelayConnection.java — the per-relay reconnect loop is
      the actual integration site for tracker.recordFailure / recordSuccess
      / nextAttemptTime / isTerminal, and the class exposes no external
      observation hook so a wrap-from-outside design is impractical).
      NostrRelayConnectionTest.java does NOT need to enter scope: it
      exercises only the static backoffDelay() helper (verified by grep —
      no `new NostrRelayConnection(...)` call sites), and preserving
      backoffDelay's signature keeps the test green.
    snapshot:
      files_budget: 8
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTracker.java
        - infochat-collector/src/main/resources/application.properties
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - date: 2026-05-31
    reason: |
      refine after post-merge-conflict rebase (post-round-1; M1-098 landed
      on main between this ticket's start and the /m1-tick merge step,
      adding NostrDedupFilter as the 7th NostrStreamSource ctor argument).
      The rebase resolution wove the dedupFilter into the production
      Registrar and into the three test files already in test_plan.modifies,
      but missed two test call sites that the round-1 review never saw:
        - NostrDegradationIT.java (M1-099's new file, authored against the
          pre-M1-098 7-arg ctor) — needs `dedupFilter` appended as the 8th arg.
        - NostrDedupIT.java (M1-098's new file, brought into the branch by
          the rebase, authored against the pre-M1-099 7-arg ctor) — needs
          `healthTracker` inserted at position 7. Adding it to files_scope
          / test_plan.modifies bumps files_budget from 9 to 10.
      Strictly mechanical constructor-arg propagation, analogous to the
      M1-097 cascade that already triggered an earlier refine on this
      ticket. No test intent or behavior is changed.
    snapshot:
      files_budget: 9
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTracker.java
        - infochat-collector/src/main/resources/application.properties
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
overrides: []
aborted_attempts:
  - date: 2026-05-30
    prior_status: in-progress
    reviews_at_abort: {}
    clarity_check_at_abort:
      date: 2026-05-30
      verdict: WARN
      warnings:
        - "TEST-CHANGES-AUTHORIZED: Existing tests NostrStreamSourceIT.java, NostrStreamSourceTest.java, NostrRelayConnectionTest.java exercise NostrStreamSource. Ticket has no test_plan.modifies. If implementation finds these need updates (e.g., to inject RelayHealthTracker or accommodate changed reconnect behavior), authorize before modification."
      blockers: []
    revisions_at_abort: []
    reason: no reason given
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-31
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-099: Nostr per-relay degradation + cycle cap

## Context

`architecture.md` §Ingest SPIs commits to: "a single misbehaving relay
MUST NOT block the StreamSource." The per-relay degradation model
parallels D42's polled-fetcher failure ladder but applies to long-lived
relay connections. This ticket implements the cooldown, all-relays-bad
wait, cycle cap, and terminal-failure state.

## Acceptance

See frontmatter.

## Out-of-scope

- D42 fetcher failure ladder — M1-094 (separate, polled sources).
- Signature verification — M1-097.
- SSRF guard — M1-101.

## Notes

- **Cooldown window.** Profile-driven value. A relay enters cooldown
  after N consecutive failures (connection drop, malformed data, timeout).
  During cooldown, no reconnect attempts are made to that relay.
- **All-relays-bad wait.** When every relay is in cooldown, the
  StreamSource parks on `earliest_cooldown_expiry` instead of spinning.
  Virtual threads make this natural (`Thread.sleep` or `parkUntil`).
- **Cycle cap.** A "cycle" is one transition into all-relays-bad. The
  counter resets to 0 when any relay recovers. After N consecutive
  cycles, terminal failure. The cap is profile-driven.
- **Terminal failure.** Sets `source.status='failed'` via
  `SourceRepository` (same method M1-094 uses for polled sources).
  The supervisor stops the StreamSource.
- **ThrottledAdminNotifier.** Same pattern as M1-081a and M1-094.
  Error class: `nostr_all_relays_bad` for the transition notification,
  `nostr_terminal_failure` for the permanent stop.
- **M1-097 constructor wiring.** M1-097 (committed after this ticket
  was authored) added a `NostrEventVerifier verifier` parameter to
  `NostrStreamSource`'s package-private constructor, taking it from 5
  to 6 arguments. This ticket adds a 7th `RelayHealthTracker tracker`
  parameter. The producer-loop in `NostrStreamSource.NostrSourcesProducer`
  must instantiate a fresh tracker per source (relay health is per-
  source state) and pass it; the verifier remains the shared instance.
  All three existing direct call sites are tests, captured in
  `files_scope` + `test_plan.modifies`; the constructor-arg change is
  mechanical and preserves test intent.
- **Sig failures are NOT a relay-health signal.** M1-097's `failedSig`
  counter tracks forged events at the trust boundary inside
  `enqueueInbound`. RelayHealthTracker reacts only to connection-level
  events surfaced by `NostrRelayConnection` (TCP drop, WSS frame error,
  parse failure). Reason: a forger spraying bad-sig events through any
  relay (including healthy ones) would otherwise DoS innocent relays'
  reputations — the two failure surfaces have different actors (relay
  misconfig vs. event forgery) and conflating them would let an
  attacker harm operators by exploiting the degradation logic.
- **Tracker hook points inside NostrRelayConnection.** The relay
  connection's private `runLoop` (lines 127–158) is the integration
  site for the four tracker calls. Specifically:
    - **`while`-condition (line 129)** gains `&& !tracker.isTerminal()`
      so the loop exits once the source-level cycle cap is exhausted.
    - **Sleep computation (line 152)** must honor cooldown timing —
      the per-attempt backoff floor stays (preserves backoffDelay
      semantics and its unit test), but is extended by
      `tracker.nextAttemptTime(relayUri)` so an in-cooldown relay
      sleeps the cooldown duration rather than just the backoff curve.
    - **Outcome recording (around line 150)** calls
      `tracker.recordFailure(relayUri)` when `productiveSinceConnect`
      is false at close (a relay that connected but never sent EOSE/
      EVENT is treated as a failure for cooldown purposes).
    - **Productivity detection (line 180, inside `handleFrame`)** calls
      `tracker.recordSuccess(relayUri)` on the false→true transition
      of `productiveSinceConnect`, so the all-relays-bad RECOVERED
      transition fires in real time on the first productive frame
      rather than retrospectively on the next disconnect.
  No constructor-signature changes to existing call sites of static
  `NostrRelayConnection.backoffDelay()`; `NostrRelayConnectionTest`
  stays untouched.
