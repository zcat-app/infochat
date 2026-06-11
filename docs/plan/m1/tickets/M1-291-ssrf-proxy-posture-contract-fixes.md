---
id: M1-291
title: "SSRF module: proxy posture, pinned-path policy, bounded discard, contract fixes"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 14
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/UrlProbe.java
  - docs/spec/deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Consolidating the many per-caller SsrfGuardedHttpClient constructions onto the shared producer (opus-47/03 observation) — backlogged, not this ticket.
  - The blocklist/range policy itself and the M1-277 pin fast path — unchanged.
  - DNS-cache behaviour and resolver service registration NAME (must not change; pinned by acceptance).
acceptance:
  - "U-09: SsrfGuardedHttpClient's HttpClient builder (~:243) sets .proxy(HttpClient.Builder.NO_PROXY) so ambient JVM proxy properties (http.proxyHost/https.proxyHost/socksProxyHost) can never route guarded requests through a proxy that re-resolves the target and voids the DNS-pin rebind defense; a named test asserts the built client's proxy() is NO_PROXY. The caller-side WebSocket/HTTP dials in NostrRelayConnection and UrlProbe get the same posture (or a named test/comment showing their builder path already cannot pick up ambient proxies); docs/spec/deployment.md gains one operator-facing sentence stating guarded egress ignores JVM proxy settings."
  - "U-39: PinnedDnsResolver.lookupByName honors LookupPolicy on the pinned path (today only the delegate gets it): the pinned address set is filtered by the policy's address-family characteristics in both resolver paths; named tests for IPv4-only and IPv6-only policies against a dual-family pinned set."
  - "U-40: redirect-hop response bodies are drained through a bounded discard (a discardBounded sibling of readBounded) instead of close(); a named test asserts a redirect hop with an oversized body stops reading at the cap."
  - "U-37: HostInterfaceSet.enumerate() failure surfaces from resolveAndValidate as a typed SsrfPolicyException reason instead of IllegalStateException escaping get()'s documented SsrfPolicyException/IOException surface; a named test."
  - "U-38: readBounded propagates InterruptedException instead of wrapping it in IOException (get() already declares throws InterruptedException); interrupt-status handling follows the project's existing interrupt convention; a named test or the compiler pins the new signature path."
  - "U-48: PinnedDnsResolver's zero-production-caller instance snapshot surface and Provider.builtin() (~:196) are moved to test scope or deleted; the resolver service-registration name is unchanged (named pin test); the live ForwardingResolver path is the single production lens."
  - "Rejected-finding residual (report §6.4): PinHandle.release's null arm becomes throw-on-null (loud) instead of a silent no-op — NOTE: Map.compute's remapping function takes a @Nullable value parameter, so deleting the arm fails NullAway; throw inside it."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
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

# M1-291: SSRF module: proxy posture, pinned-path policy, bounded discard, contract fixes

## Context

Deep-review v5 verified **U-09** (MEDIUM, security), **U-37**, **U-38**,
**U-39**, **U-40**, **U-48**, plus the §6.4 residual on PinHandle.release
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources
`fable-5/03-module-infochat-ssrf.md#F1/#F2`, `gpt-55/report.md#M-03`,
`opus-47/03#F1/#F3/#F4`, `opus-48/03#F1`, `deepseek/03#F1` — gitignored;
all load-bearing facts inlined):

The headline is U-09: the guarded client builds its `HttpClient` without
`.proxy(NO_PROXY)` (verified 2026-06-11: `HttpClient.newBuilder()` at :243
with no proxy call in the file). With ambient JVM proxy properties set, the
proxy re-resolves the target itself — the pinned resolver and the
blocklist-validated peer IP never apply. The rest are verified contract
fixes in the same module, bundled to land the ssrf sweep in one review.

Two correctness notes carried from the unified report:
- deepseek's U-38 trade-off text contains a wrong Java-semantics claim
  ("throwing InterruptedException clears the flag by specification") — the
  fix direction (propagate) is right, the rationale text is not; don't copy
  it into comments.
- opus-47's U-39 SECURITY/medium framing overstates: pinned addresses
  remain blocklist-validated; this is an SPI-contract/address-family
  correctness fix.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-48 deletion guard: the snapshot lens and `builtin()` are hand-synced
  duplicates of the live resolution policy — production-shipped with
  zero/test-only callers. Grep call sites (including tests) before
  deleting; the service-registration name is load-bearing for the JDK
  resolver SPI and must not change.
- Coordination: M1-292 touches UrlRedactor in this module; different files,
  but check the worktree landscape at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-291-*.md
```
