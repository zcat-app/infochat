---
id: M1-345
title: "infochat-ssrf: bracket-host IPv6 validation, redirect drain order, IPv6 zero-scan helper reuse"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
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
  - "canonicalizeHost validates that a [...]-bracketed host is actually an IPv6 literal before re-bracketing it. Today the bracket branch case-folds and re-brackets the inner string with no check (canonicalizeHost(\"[example.com]\") -> \"[example.com]\"), an asymmetry with the IDN branch which rejects everything IDN.toASCII cannot parse. The fix validates the inner via Inet6Address.ofLiteral (a pure parse that never performs DNS, for any input — including the rejection path) and lets the IllegalArgumentException it throws for any non-IPv6-literal propagate to the existing INVALID_HOST wrapper in resolveAndValidate (IllegalArgumentException -> SsrfPolicyException(INVALID_HOST)). IPv4-mapped IPv6 literals (e.g. the [::ffff:8.8.8.8] that URI.getHost() yields) are accepted by ofLiteral and continue to flow to the IpBlocklist embedded-v4 decode unchanged, so no currently-correct caller's behavior changes. Inet6Address.ofLiteral is chosen over InetAddress.getByName because getByName both performs DNS on a non-literal inner and returns Inet4Address for IPv4-mapped literals (an instanceof Inet6Address check would then reject [::ffff:8.8.8.8], a regression); ofLiteral does neither. The pin-map key invariant becomes 'a real bracketed IPv6 literal or an IDN-normalized ASCII hostname' with no synthetic third class."
  - "The redirect-handling order moves the redirect-cap check above the body drain: on the hop that pushes redirectCount past redirectCap, the wrapper no longer drains the entire (bodyCap-bounded) body it is about to discard. The body is explicitly closed (response.body().close()) before the throw so an ofInputStream()-backed close does not read-and-discard the whole body. Drains still happen on hops actually followed."
  - "isLoopbackV6 and isAllZeroV6 reuse the existing allZero(raw, from, to) helper instead of re-implementing the byte-range zero scan: isAllZeroV6 becomes allZero(raw,0,16) and isLoopbackV6 becomes allZero(raw,0,15) && raw[15]==1. Behavior is identical; the call sites in isBlockedV6 are unchanged."
  - "Tests pin the bracket validation (a bracketed non-IPv6 host is rejected; a real bracketed IPv6 literal still canonicalizes; a bracketed IPv4-mapped literal [::ffff:8.8.8.8] still canonicalizes rather than being rejected — the no-regression property) and confirm the existing redirectCapExceededRaises and blocklist V6 loopback/all-zero cases stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (bracket-host validation case)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
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
      files: 5
      added: 92
      removed: 27
escalations:
  - date: 2026-06-14
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail surfaced pre-implementation. Acceptance item 1's
      prescribed mechanism (InetAddress.getByName + reject non-Inet6Address)
      regresses bracketed IPv4-mapped literals ([::ffff:8.8.8.8], a real
      URI.getHost() output that today flows to the IpBlocklist embedded-v4
      decode), performs DNS on the non-literal rejection path, and throws
      UnknownHostException rather than the IllegalArgumentException the item's
      own error contract names. Verified empirically on JDK 25.0.3. Resolved
      by refine to Inet6Address.ofLiteral (user-approved 2026-06-14).
revisions:
  - date: 2026-06-14
    reason: "premise-fail refine — acceptance item 1's getByName mechanism regresses bracketed IPv4-mapped literals and throws the wrong exception type (verified on JDK 25.0.3); switch the prescribed API to Inet6Address.ofLiteral (pure parse, never DNS, accepts IPv4-mapped so no caller regression, throws IllegalArgumentException matching the existing INVALID_HOST wrapper). Item 4 gains a [::ffff:8.8.8.8] no-regression test. No files_scope / files_budget / complexity / risk / out_of_scope change."
    prior_values: |
      acceptance item 1 (pre-refine):
        - "...The fix parses the inner via InetAddress.getByName (a pure parse for an IP literal, no DNS) and rejects (IllegalArgumentException -> SsrfPolicyException(INVALID_HOST)) anything that is not an Inet6Address..."
      acceptance item 4 (pre-refine):
        - "Tests pin the bracket validation (a bracketed non-IPv6 host is rejected; a real bracketed IPv6 literal still canonicalizes) and confirm the existing redirectCapExceededRaises and blocklist V6 loopback/all-zero cases stay green."
      Notes bullet (pre-refine):
        - "InetAddress.getByName on an IP literal performs no DNS, so the bracket check adds no I/O on the security-critical hot path."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: 9508c98bfa60236f01269e62d227c6ff6f1b8ece
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-345-2026-06-14.md
    out_of_model_count: 0
    note: |
      Pre-commit (--in-progress) audit of the SSRF hardening diff (bracket-host
      Inet6Address.ofLiteral validation, redirect-cap-before-drain reorder,
      allZero helper reuse). CLEAN — no gap between docs/spec/security.md §SSRF
      and the diff; no remediation needed.
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
- `Inet6Address.ofLiteral` performs no DNS for any input (literal or not), so the
  bracket check adds no I/O on the security-critical hot path and none on the
  rejection path either.
