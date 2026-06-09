---
id: M1-277
title: "SSRF: shared client, pin fast path, redirect scrub, ranges"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 10
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/HostInterfaceSet.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - docs/design/04-security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The builtin-resolver refactor for the JDK name cache — rejected as a security finding (UNIFIED-REPORT §4); optional hardening only, not here.
  - The pinning model itself (resolver-level, JVM-wide pin map) — unchanged.
  - Per-redirect re-validation semantics — unchanged.
  - Arbitrary NAT64 prefixes beyond the RFC 8215 local-use prefix (inherently undetectable; report's "narrow fix only").
acceptance:
  - "SsrfGuardedHttpClient reuses one HttpClient across get() calls (no per-call client/SelectorManager thread); a comment documents the lifecycle choice and its compatibility with the JVM-wide pin map; all existing SSRF tests stay green."
  - "PinnedDnsResolver fast-paths an exact get(canonical(host)) before any snapshot allocation, so JVM-wide lookups for unpinned hosts while a pin is active no longer pay a HashMap snapshot + ephemeral resolver + Map.copyOf; a named test asserts pinned and unpinned hosts resolve identically to current behavior."
  - "Cross-origin redirects forward no caller-supplied request headers beyond a defined safe set (today only the 3 credential headers are scrubbed); a named test asserts an extra header (e.g. Range) does not cross origins, or — if the keep-Range contract is chosen — the contract is documented and the named test pins exactly the safe set."
  - "IpBlocklist covers 192.0.0.0/24, the TEST-NET ranges, 198.18.0.0/15, 240.0.0.0/4, and RFC 8215 64:ff9b:1::/48; named tests per added range."
  - "Design 04 §150's explicit-entry mandate is satisfied: 100.100.100.200 and fd00:ec2::254 appear as explicit blocklist entries with named tests (range coverage retained), and the design's incorrect rationale sentence ('missing it leaves the blocklist incomplete' — they are range-covered) is corrected in the same diff."
  - "HostInterfaceSet no longer pays the redundant Set.copyOf per call."
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

# M1-277: SSRF: shared client, pin fast path, redirect scrub, ranges

## Context

Deep-review v4 verified mediums **M-S1..M-S3** and the **T-SSRF-HARDEN** low
sweep (`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/deepseek/report.md` #F1,
`deep-code-review/v4/gpt-55/report.md` L-02,
`deep-code-review/v4/opus-47/03-module-infochat-ssrf.md#F1/#F2/#F3`,
`deep-code-review/v4/mimo/report.md` MED-001/MED-002,
`deep-code-review/v4/fable5/03-module-infochat-ssrf.md#F1`,
`deep-code-review/v4/opus-48/03-module-infochat-ssrf.md#F2`):

- **M-S1:** one `HttpClient` per `get()` call (:346) — a SelectorManager
  thread per call. A shared client is compatible with the JVM-wide pin map
  (pinning is resolver-level). The existing `B-HTTP-CLIENT` comment explains
  cross-hop reuse but not the per-call choice.
- **M-S2:** `PinnedDnsResolver` has only a `PINS.isEmpty()` fast path; with
  any pin active, every JVM-wide lookup (DB pool, LLM calls) pays a snapshot
  allocation.
- **M-S3:** cross-origin redirect scrubs only the 3 credential headers
  (:409-411); current callers only pass `Range` (benign) — structural risk.
- **T-SSRF-HARDEN:** reserved/special-use ranges absent from `isBlockedV4`
  (extra-spec hardening); RFC 8215 NAT64 local-use prefix not decoded;
  design 04 §150 mandates two explicit entries that are only range-covered —
  doc-vs-code drift, NOT the runtime gap mimo labeled HIGH (both addresses
  verified blocked byte-wise today); `HostInterfaceSet` `Set.copyOf` extra
  allocation per call.
- §4 rejects: the DNS-cache divergence (opus-48's #1), Location-header
  logging, and bracket-literal validation are all explicitly NOT in scope.

## Acceptance

See frontmatter. SSRF block/allow policy only *widens* (new ranges, explicit
entries); nothing currently blocked becomes reachable.

## Out-of-scope

See frontmatter — three §4-rejected findings are listed so the implementer
doesn't rediscover them.

## Notes

- The M1-261 precedent applies: loopback-permitting IpBlocklist test doubles
  across modules now override `isBlockedAgainst`; adding ranges shouldn't
  touch the seam, but any signature change re-triggers the 18-double sweep —
  avoid signature changes.
- For M-S3, scrub-all-extra-headers is the simpler, safer default; keeping
  `Range` across same-origin hops only is fine. Pick one, document, pin.
- Design 04 §150's own rationale is wrong (the entries ARE range-covered);
  the acceptance keeps the explicit entries AND fixes the sentence so design
  and code stop disagreeing in both directions.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-277-*.md
```
