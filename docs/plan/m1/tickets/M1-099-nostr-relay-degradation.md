---
id: M1-099
title: "Nostr per-relay degradation + cycle cap"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-096
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTracker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
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
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
