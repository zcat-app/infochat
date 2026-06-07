---
id: M1-191
title: "SSRF: replace the JVM-wide pin lock with a per-host pin map"
status: done
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 5
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the small SSRF fixes batch (Location-resolve IAE wrap, fec0::/10, scheme case-folding, reason()-based test assertions) — UNIFIED.md T32, lows batch, not yet filed
  - IpBlocklist contents and the blocklist evaluation order
  - redirect-cap / body-size / timeout enforcement — untouched semantics
  - callers of the SSRF client (fetchers, probes, Nostr dials) — the module API surface should stay source-compatible; if a signature must change, sweep call sites and escalate on files_scope
acceptance:
  - "Outbound fetches to DIFFERENT hosts no longer serialize on a JVM-wide lock: a named test in SsrfGuardedHttpClientConcurrencyTest holds one pinned dial open (slow connect) and asserts a concurrent fetch to a different host completes without waiting for it (today PinnedDnsResolver.Provider has a single static ReentrantLock and a single static volatile ACTIVE_PINS slot, so every outbound HTTP fetch and every Nostr WebSocket dial in the JVM serializes — lock held across connect+headers per hop, ~4 hops adversarial ≈ 140s)"
  - "Per docs/spec/security.md §SSRF and outbound connections — \"DNS is re-resolved after every redirect (TOCTOU defense); the IP blocklist re-applies each hop.\" — the existing redirect/rebind tests stay green: the concurrency change does not weaken per-hop re-validation"
  - "Per docs/spec/security.md §SSRF and outbound connections — \"The IP-blocklist and DNS-rebind defenses are **transport-agnostic** — a `wss://` relay connection is gated by the same checks as an `https://` feed fetch (decision D38).\" — checkAndPinForWebSocket still pins and validates WS dials, now concurrently with HTTP fetches: a named test in SsrfGuardedHttpClientConcurrencyTest runs a WS pin and an HTTP fetch simultaneously"
  - "Pin isolation under concurrency: while host A is pinned, a lookup of host A returns exactly its validated addresses and a concurrent pin of host B neither disturbs nor observes A's pin — a named test in PinnedDnsResolverConcurrencyTest pins two hosts concurrently and asserts both resolve to their own validated address lists"
  - "Pin lifetime is correct under failure: a throw inside the guarded section releases that host's pin (no stale pin survives), and concurrent holders of the SAME host don't release each other's pin early — named tests in PinnedDnsResolverConcurrencyTest cover unlock-on-throw and same-host overlap"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/PinnedDnsResolverConcurrencyTest.java
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientConcurrencyTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D20
  - D38
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 630
      removed: 118
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED FAIL: test_plan.modifies lists
      infochat-ssrf/src/test/java/app/zcat/infochat/ssrf but the ticket
      body contains no "Authorized test changes" section. Add a section
      that names each existing test class or method that will be modified
      and describes the new expected behavior (or explicitly states
      "setup-only changes: assertion logic is unchanged, only
      construction/instantiation of PinnedDnsResolver/SsrfGuardedHttpClient
      is updated to match new API"). If the existing tests require no
      behavioral changes — only mechanical API-call updates — state that
      explicitly with the class names.
revisions:
  - date: 2026-06-07
    reason: clarity-fail-refine (round 1) — disclaim modifications to existing tests, name the planned test classes, settle the files_budget grouping
    snapshot: |
      test_plan (pre-refine):
        adds:
          - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        modifies:
          - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        preserves:
          - all tests currently green on main
      Acceptance items 1, 3, 4, 5 said "a named test" / "named tests" without
      naming classes; the body carried NO "Authorized test changes" section
      while test_plan.modifies listed the whole test directory (clarity blocker
      TEST-CHANGES-AUTHORIZED). Verified on disk pre-refine: none of the three
      existing test classes (IpBlocklistTest 34 tests, SsrfGuardedHttpClientTest
      18, UrlRedactorTest 8) references PinnedDnsResolver, the Provider pin
      internals (ACTIVE_PINS / ReentrantLock / PinnedDial), or checkAndPin —
      grep over infochat-ssrf/src/test returned zero hits — so the modifies
      entry was unfounded and is removed rather than authorized.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-07
    verdict: CLEAN
    base: 7cef606^
    head: 7cef606
    verdict_file: docs/plan/m1/redteam/M1-191-2026-06-07.md
    out_of_model_count: 3
    note: |
      CLEAN on the post-commit, pre-merge implementation commit. Three
      advisory out-of-model notes, none requiring action on this ticket:
      a cosmetic javadoc-precision nit on Provider.pin's "still-active
      holder" phrasing under latest-wins overlap; the pre-existing
      DNS-vs-DNS (not socket-peer) comparison in the peer-IP-change
      watcher pattern (candidate input for a future Nostr StreamSource
      hardening decision); and the pre-existing JDK InetAddress
      positive cache above the resolver SPI (bypassed-but-equivalent).
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-191.md
---

# M1-191: SSRF: replace the JVM-wide pin lock with a per-host pin map

## Context

The DNS-rebind defense pins validated addresses through a single static
slot: `PinnedDnsResolver.Provider` holds `private static final ReentrantLock
LOCK` (PinnedDnsResolver.java:111) guarding one static volatile
`ACTIVE_PINS` map. Every outbound connection in the JVM — Collector feed
fetches, redirect hops, Provider /add-source probes, and Nostr WebSocket
dials via `checkAndPinForWebSocket` (SsrfGuardedHttpClient.java:599, same
lock) — serializes on it, with the lock held across connect+headers per hop.
One slow or adversarial host stalls the entire outbound plane (~140s for a
4-hop adversarial chain). Consensus finding across four audit runs; unified
finding S1 (high-perf), `deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter. The concurrency contract changes; the security contract
(per-hop re-validation, transport-agnostic gating, no resolution escaping
the pin) is preserved verbatim.

## Out-of-scope

See frontmatter.

## Authorized test changes

None. No existing test class is modified. Verified on disk: the three
existing classes — IpBlocklistTest (34 tests), SsrfGuardedHttpClientTest
(18), UrlRedactorTest (8) — reference neither PinnedDnsResolver nor the
Provider pin internals (ACTIVE_PINS, ReentrantLock, PinnedDial,
checkAndPin) and assert through the module's public API only; all must
stay green unchanged (acceptance item 2). New concurrency scenarios land
exclusively in two NEW classes: PinnedDnsResolverConcurrencyTest
(pin isolation, unlock-on-throw, same-host overlap — items 4, 5) and
SsrfGuardedHttpClientConcurrencyTest (cross-host non-serialization,
WS+HTTP simultaneity — items 1, 3). With this grouping the touched-file
count is 2 production + 2 new test files = 4 ≤ files_budget 5. If
implementation discovers an unavoidable touch to an existing test class —
even a mechanical one — it is NOT pre-authorized here: escalate.

## Notes

- Source: `UNIFIED.md` §3 T15 under `deep-code-review/v2/` (opus-48 ssrf
  F2, kimi-folder ssrf F1, gpt P3 — consensus).
- High-risk module: this is the deliberate `complexity: high` /
  plan-writer ticket of the batch. The resolver is a JVM-wide
  InetAddressResolverProvider SPI — the pin map is global state by
  necessity; what changes is its granularity and locking, not its
  existence.
- Known wart to not reintroduce: PinnedDial.close() has thread-affinity
  (ReentrantLock unlock must happen on the locking thread) — a refcount
  scheme must not inherit that constraint accidentally.

## Suggested direction (unverified hypothesis)

A refcounted per-host pin map: pin(host, addresses) increments a per-host
entry; release decrements and removes at zero; the resolver consults the
map per-host with no global mutual exclusion (proposed by the opus-48 run;
seconded by kimi-folder and gpt).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
