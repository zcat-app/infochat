---
id: M1-261
title: "Consistency code lows: IpBlocklist per-pass enum, router name case"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 24
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  # Refine 2026-06-09: the IpBlocklist seam move (override point relocated
  # from public isBlocked(addr) to protected isBlockedAgainst(addr,
  # snapshot)) orphans every loopback-permitting IpBlocklist test double.
  # 17 such doubles live across the collector/provider test packages — each
  # gets the identical mechanical rename. Listed file-by-file (not by dir)
  # so the scope is exactly the touched set. Policy byte-identical; build is proof.
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/LoopbackPermittingBlocklist.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceNostrProbeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeRelayTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The per-call (per-request) freshness contract of the host-interface set — PRESERVED; the fix enumerates once per validation pass, NOT once per construction. M1-026 Finding 3 requires per-request freshness; this only removes the redundant per-address re-enumeration within a single pass.
  - The blocklist policy result (which IPs are blocked) — byte-identical before/after; this is a pure enumeration-count optimization.
  - LlmRouterStartupGuard — already case-insensitive (it lower-cases before matching REMOTE_PROVIDER_NAMES); it is the correct target the router is being aligned TO, and is not modified.
  - Provider-name constants (anthropic / openai-compatible) and the routing priority order — unchanged; only the lookup/registration is made case-insensitive.
acceptance:
  - "IpBlocklist enumerates the host-interface set at most once per validation pass rather than once per resolved address: a host resolving to k addresses triggers exactly one HostInterfaceSet enumeration for that pass, not k. The per-request freshness contract is preserved (each validation pass takes a fresh snapshot) and the block/allow decision is identical to before."
  - "A named ssrf test asserts a multi-address host is validated against a single host-interface enumeration (e.g. a counting/spy provider observes exactly one get() per pass) while the block/allow outcome matches the existing behavior for blocked and allowed addresses."
  - "LlmRouter resolves provider names case-insensitively, matching the startup guard: a mixed-case default.provider / <task>.provider override (e.g. 'Anthropic') resolves to the same Entry as 'anthropic'. A named router test asserts a mixed-case provider name resolves identically to its lower-case form."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/LoopbackPermittingBlocklist.java
    # Refine 2026-06-09: sibling loopback-permitting doubles in the
    # collector/provider modules that override isBlocked(addr) — all moved
    # to override the new isBlockedAgainst(addr, snapshot) seam.
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/LoopbackPermittingBlocklist.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceNostrProbeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeRelayTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeTest.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 25
      added: 311
      removed: 60
overrides: []
aborted_attempts: []
reopens: []
escalations:
  - date: 2026-06-09
    reason: scope-expansion (call-site sweep miss)
    summary: |
      During implementation, `mvn -B clean verify` failed: the IpBlocklist
      seam move (production now calls firstBlocked → package-private
      isBlockedAgainst instead of the per-address public isBlocked) orphans
      every loopback-permitting IpBlocklist test double, since they all
      override the now-bypassed isBlocked(addr). The ticket's Notes
      anticipated this for the ONE LoopbackPermittingBlocklist in
      infochat-ssrf (listed in files_scope) but the drafter never grepped
      sibling modules: 17 more such doubles exist in infochat-collector (13)
      and infochat-provider (4), all outside files_scope, all failing with
      `SsrfPolicyException: blocked IP: 127.0.0.1` when their loopback
      carve-out stopped being consulted.

      No safe design avoids the sweep: the doubles override the exact
      single-arg method whose internal enumeration IS the cost being
      removed, so enumerating once requires not virtual-dispatching through
      it. The only override-preserving alternative (a per-pass passSnapshot
      field on IpBlocklist) is thread-unsafe on a shared security singleton
      and was rejected. The seam move is therefore the correct design; the
      17 edits are its unavoidable mechanical tail, not independent work.

      Resolution: refine (user-authorized 2026-06-09) — expand files_scope
      to the collector/provider test dirs, raise files_budget 7 → 24, list
      the 17 doubles in test_plan.modifies, apply the identical rename
      (`public boolean isBlocked(InetAddress addr)` →
      `boolean isBlockedAgainst(InetAddress addr, Set<InetAddress> hostInterfaces)`,
      `super.isBlocked(addr)` → `super.isBlockedAgainst(addr, hostInterfaces)`)
      to each. Policy is byte-identical; the green build is the proof.
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: 650b4cd
    head: m1/M1-261-consistency-code-lows working tree (uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-261-2026-06-09.md
    out_of_model_count: 1
    note: |
      --in-progress audit before commit (security_relevant: true; touches the
      SSRF enforcement path). CLEAN — the diff is a behavior-preserving
      consistency refactor: SSRF block/allow decision byte-identical, per-pass
      enumeration correctness-neutral, per-redirect TOCTOU defense unaffected,
      the 18 loopback doubles override the new protected isBlockedAgainst seam
      (no test weakened), and the LlmRouter case fix touches no trust boundary.
      One OUT-OF-MODEL advisory (case-only provider-name collision) was
      falsified post-audit — entry names are compile-time constants, not
      operator strings — so no guard and no remediation ticket.
revisions:
  - date: 2026-06-09
    reason: scope-expansion refine snapshot (sibling test-double sweep)
    summary: |
      Pre-refine frontmatter: files_budget=7; files_scope limited to
      infochat-ssrf + infochat-llm-adapter; test_plan.modifies listed only
      infochat-ssrf/.../LoopbackPermittingBlocklist.java. The refine raised
      files_budget to 24, added the collector fetcher/nostr-stream and
      provider command/source test dirs to files_scope, and enumerated the
      17 sibling loopback-permitting doubles under test_plan.modifies. See
      the escalation entry above for the full reasoning.
clarity_check:
  date: 2026-06-09
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: IpBlocklist and SsrfGuardedHttpClient are SSRF security enforcement classes; even though block/allow policy is asserted byte-identical, consider security_relevant: true so the post-commit redteam runs."
    - "TEST-CHANGES-AUTHORIZED (advisory): if the implementation changes the existing LoopbackPermittingBlocklist test subclass override, that modification must be listed in test_plan.modifies."
  blockers: []
---

# M1-261: Consistency code lows

## Context

Two low-severity internal-consistency findings from the v3.5 deep review, grouped
per the request to avoid one-finding micro-tickets. Each is a small, independently
testable behavior fix; they share no code but share the shape "a path diverges
from the consistent treatment its siblings use." Source reports:
`deep-code-review/v3.5/opus-48/03-module-infochat-ssrf.md#F1` (IpBlocklist) and
`04-module-infochat-llm-adapter.md#F2` (router name case). Both verified live on
main.

- **IpBlocklist per-pass enumeration (03#F1).** `SsrfGuardedHttpClient.resolveAndValidate`
  loops over every resolved IP and calls `blocklist.isBlocked(addr)`, which runs
  `HostInterfaceSet.enumerate()` (a `NetworkInterface.getNetworkInterfaces()` JNI
  walk + fresh `HashSet`) on *every* call. A host resolving to k addresses
  triggers k identical enumerations within one validation, and the multiplier
  repeats per redirect hop. The interface set cannot change between two addresses
  checked microseconds apart in the same pass, so the per-address
  re-enumeration buys nothing — it only bites on many-interface hosts (k8s nodes,
  multi-tunnel VPN hosts) on the collector's feed-fetch hot path. Not a security
  gap (the result is identical), purely avoidable work.
- **Router name case (04#F2).** `LlmRouterStartupGuard` lower-cases the
  operator-supplied provider name before comparing to `REMOTE_PROVIDER_NAMES`, so
  `default.provider=Anthropic` is judged a remote provider and fails the
  local-only guard. `LlmRouter` does NOT lower-case: `entriesByName` is keyed by
  the exact constant and `forTask` does an exact `Map.get`, so the same `Anthropic`
  resolves to no Entry (priority-1 override throws; priority-3 default falls
  through to WARN + first entry). The two collaborators reason about the same
  operator string under different normalization — an inconsistent experience for
  one typo and a maintenance hazard.

## Acceptance

See frontmatter. In prose: (1) enumerate the host-interface set once per
validation pass (a batch entry point that snapshots once and checks each address
against the cached set), preserving per-request freshness and the identical
block/allow result; (2) make `LlmRouter`'s registration and lookups
case-insensitive so it agrees with the already-case-insensitive startup guard.
Each has a named test; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The per-request freshness contract, the blocklist policy result,
the startup guard, and the provider-name constants/priority order are all
preserved.

## Notes

- IpBlocklist: keep `isBlocked(InetAddress)` for single-address callers (it
  preserves per-call freshness); add a batch entry point (e.g. `anyBlocked(List)`)
  that snapshots the interface set once. The `LoopbackPermittingBlocklist` test
  subclass currently overrides `isBlocked` — route the batch through a
  `protected isBlockedAgainst(addr, snapshot)` seam so the override point moves
  there and the batch does not re-introduce per-address enumeration. (The seam
  is `protected`, not package-private: the refine below found loopback-permitting
  doubles in the collector/provider test packages that must override it from
  OUTSIDE `app.zcat.infochat.ssrf`, which a package-private method cannot allow.)
  If the BLOCKED_IP exception message needs the specific offending address,
  return the offending `InetAddress` from the batch method instead of a boolean.
- Router: lower-casing the registered name key and the override/default lookups
  is the simplest alignment (report Option A). Option B (make the guard
  case-sensitive instead) is rejected — it would let a mixed-case `Anthropic`
  silently escape the local-only remote-provider guard, the worse failure
  direction.
- These two changes touch different modules; keep the diff cleanly separable and
  each acceptance item independently verifiable.
</content>
