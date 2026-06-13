---
id: M1-345
title: "infochat-ssrf: bracket-host IPv6 validation, redirect drain order, IPv6 zero-scan helper reuse"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 3
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The DNS-rebind pinning core, blocklist coverage, body-read DoS bounds, scheme allowlist, redirect cap, and cross-origin header scrub — all verified correct in review; untouched.
  - Behavioral change to currently-correct callers — none; every bracketed host URI.getHost() returns today already passes the new IPv6 check, and the redirect drain reorder is correctness-neutral.
acceptance:
  - "canonicalizeHost validates that a [...]-bracketed host is actually an IPv6 literal before re-bracketing it. Today the bracket branch case-folds and re-brackets the inner string with no check (canonicalizeHost(\"[example.com]\") -> \"[example.com]\"), an asymmetry with the IDN branch which rejects everything IDN.toASCII cannot parse. The fix parses the inner via InetAddress.getByName (a pure parse for an IP literal, no DNS) and rejects (IllegalArgumentException -> SsrfPolicyException(INVALID_HOST)) anything that is not an Inet6Address, so the pin-map key invariant becomes 'a real bracketed IPv6 literal or an IDN-normalized ASCII hostname' with no synthetic third class."
  - "The redirect-handling order moves the redirect-cap check above the body drain: on the hop that pushes redirectCount past redirectCap, the wrapper no longer drains the entire (bodyCap-bounded) body it is about to discard. The body is explicitly closed (response.body().close()) before the throw so an ofInputStream()-backed close does not read-and-discard the whole body. Drains still happen on hops actually followed."
  - "isLoopbackV6 and isAllZeroV6 reuse the existing allZero(raw, from, to) helper instead of re-implementing the byte-range zero scan: isAllZeroV6 becomes allZero(raw,0,16) and isLoopbackV6 becomes allZero(raw,0,15) && raw[15]==1. Behavior is identical; the call sites in isBlockedV6 are unchanged."
  - "Tests pin the bracket validation (a bracketed non-IPv6 host is rejected; a real bracketed IPv6 literal still canonicalizes) and confirm the existing redirectCapExceededRaises and blocklist V6 loopback/all-zero cases stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (bracket-host validation case)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-345: infochat-ssrf — low-severity hardening sweep

## Context

Three low-severity deep-review v5.5 findings on `infochat-ssrf`, grouped by
module (all three are small, correctness-neutral-or-tightening, and verified at
source 2026-06-14):

- **opus-47 `03-module-infochat-ssrf.md` F2** — `canonicalizeHost` IPv6-bracket
  path admits non-IPv6 bracketed input. SsrfGuardedHttpClient.java:327-329
  re-brackets the inner string with no validation, an asymmetry with the IDN
  branch. Not exploitable today (`URI.getHost()` only brackets real IPv6), but the
  contract is implicit and a future entry point not going through `URI` could feed
  a synthetic pin key.

- **opus-47 `03-module-infochat-ssrf.md` F1** — redirect body is drained before
  the cap-exceeded check (SsrfGuardedHttpClient.java:411-419), so the over-cap hop
  pays a `bodyCap`-bounded drain it is about to discard. Correctness-neutral
  hygiene (worst case already bounded by `bodyCap` + `bodyReadDeadline`).

- **opus-48 `03-module-infochat-ssrf.md` F1** — `isAllZeroV6`/`isLoopbackV6`
  (IpBlocklist.java:348-364) re-implement the byte-range zero scan the existing
  `allZero(raw, from, to)` helper (line 415) already provides.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- `security_relevant: true` because two of the three touch the SSRF canonicalizer
  / blocklist; none changes a currently-correct caller's behavior.
- `InetAddress.getByName` on an IP literal performs no DNS, so the bracket check
  adds no I/O on the security-critical hot path.
