---
id: M1-277
title: "SSRF: shared client, pin fast path, redirect scrub, ranges"
status: done
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 10
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/HostInterfaceSet.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/IpBlocklistTest.java
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
  modifies:
    - "infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/IpBlocklistTest.java
      — host-interface seam tests (hostInterfaceIpIsBlocked,
      nonHostPublicIpStillAllowed, hostInterfaceVia6to4IsBlocked,
      hostInterfaceViaTeredoIsBlocked, hostInterfaceViaNat64IsBlocked,
      hostInterfaceViaIpv4CompatibleIsBlocked,
      nonHostPublicIpViaTransitionFormStillAllowed,
      hostInterfaceAddedAfterStartupIsBlocked): swap the 203.0.113.5
      (TEST-NET-3) sentinel — which acceptance item 4 newly range-blocks —
      for a public, non-reserved IPv4 outside every blocked range (old and
      new), recomputing the 6to4/Teredo hex encodings to match. The sentinel
      must remain outside every blocked range so only the host-interface hop
      can block it (the tests stay non-vacuous). No assertion direction
      changes."
  preserves:
    - all tests currently green on main, modulo the authorized sentinel
      swap above
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 579
      removed: 167
  - round: 2
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 604
      removed: 171
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-10
    verdict: CLEAN
    base: 50335394de1bff6ae4b5b7a45dfabaa819823a74
    head: worktree@m1/M1-277-ssrf-shared-client-pin-fast-pa
    verdict_file: docs/plan/m1/redteam/M1-277-2026-06-10.md
    out_of_model_count: 2
    note: |
      Pre-commit audit of the round-2-approved working tree. CLEAN —
      the diff only widens the SSRF block surface (new ranges, explicit
      metadata entries, scrub-all cross-origin headers) and the shared
      client / pin fast path preserve the resolver-level pin model.
      Two advisory out-of-model observations recorded in the verdict
      file; no remediation required.
clarity_check:
  date: 2026-06-10
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-06-10
    reason: premise-fail rework — authorize the IpBlocklistTest TEST-NET-3
      sentinel swap that acceptance item 4 forces
    snapshot:
      status: escalated
      test_plan:
        adds:
          - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        preserves:
          - all tests currently green on main
escalations:
  - date: 2026-06-10
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — pre-implementation discovery. Acceptance item 4 adds the
      TEST-NET ranges to IpBlocklist, but IpBlocklistTest's host-interface
      seam tests use 203.0.113.5 (TEST-NET-3) as their "public-shaped,
      in-no-blocked-range" sentinel. hostInterfaceAddedAfterStartupIsBlocked
      asserts assertFalse(isBlocked(203.0.113.5)) with an empty host set
      and will hard-fail; six sibling host-interface tests turn vacuous
      (the new range block shadows the host-interface hop they pin).
      test_plan has no `modifies` authorization, so `preserves: all tests
      currently green on main` is unsatisfiable as written.
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
- Acceptance item 4's TEST-NET ranges collide with the existing
  host-interface tests' 203.0.113.5 sentinel (chosen on main precisely for
  being in no blocked range); `test_plan.modifies` authorizes the sentinel
  swap discovered at start-time (see `revisions[0]`).

## Round 1 rework

1. Remove the now-orphaned `private final Duration connectTimeout;` field
   (SsrfGuardedHttpClient.java:142) and its assignment `this.connectTimeout =
   connectTimeout;` (line 239). After moving HttpClient construction into the
   constructor, the field is read nowhere — the constructor uses the local
   parameter `connectTimeout` directly (line 247). Error Prone already flags it
   (`[UnusedVariable]`, test log line 629); §1 requires cleaning up variables
   the diff's own change made unused. Keep the constructor parameter and its
   validation; drop only the dead field and its assignment.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-277-*.md
```
