---
id: M1-261
title: "Consistency code lows: IpBlocklist per-pass enum, router name case"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 7
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
complexity: low
risk: low
round_cap: 2
security_relevant: false
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
  package-private `isBlockedAgainst(addr, snapshot)` so the override point moves
  there and the batch does not re-introduce per-address enumeration. If the
  BLOCKED_IP exception message needs the specific offending address, return the
  offending `InetAddress` from the batch method instead of a boolean.
- Router: lower-casing the registered name key and the override/default lookups
  is the simplest alignment (report Option A). Option B (make the guard
  case-sensitive instead) is rejected — it would let a mixed-case `Anthropic`
  silently escape the local-only remote-provider guard, the worse failure
  direction.
- These two changes touch different modules; keep the diff cleanly separable and
  each acceptance item independently verifiable.
</content>
