---
id: M1-214
title: "SSRF small fixes: Location-resolve exception contract, fec0::/10, scheme case-fold, reason()-based test assertions"
status: done
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 6
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the JVM-wide pin-lock replacement — M1-191's (complexity high, same two files; do NOT run the two tickets concurrently)
  - the IPv6 bracket pin-key claim (audit S5, PLAUSIBLE) — unproven JDK-resolver-SPI behavior, security impact rated zero by its own reporter; dropped from this batch, recorded in the batch summary
  - redirect-cap, body-size, and timeout semantics — untouched
  - consumers of SsrfPolicyException outside the module — swept at draft time: UrlProbe's reason() switch has a default arm whose comment explicitly anticipates future Reason constants ("any future one, via the default arm, is a policy violation → BLOCKED_SSRF"), and NostrRelayConnection does not switch on Reason — no consumer change needed for the new constant
acceptance:
  - "A syntactically malformed redirect Location header surfaces through the documented exception contract, not a raw IllegalArgumentException: a redirect response whose Location cannot resolve against the current URI produces an SsrfPolicyException with a dedicated Reason (REDIRECT_LOCATION_INVALID or equivalent, sibling to the existing REDIRECT_LOCATION_MISSING), pinned by a named test (today the hop loop calls current.resolve(location) with no RuntimeException handling, so the IAE escapes the SsrfPolicyException/IOException contract)"
  - "Per docs/spec/security.md §SSRF and outbound connections — \"DNS-resolved IPs are checked against a blocklist of private, loopback, link-local, multicast, CGNAT, and cloud-metadata ranges (notably `169.254.169.254` and IPv6 equivalents) plus the host's own non-loopback interfaces.\" — the deprecated IPv6 site-local range fec0::/10 is blocked: a named test asserts an address in that range is rejected (today IpBlocklist covers ::1/::, fe80::/10, fc00::/7, ff00::/8 and transition forms; fec0::/10 is absent)"
  - "Per docs/spec/security.md §SSRF and outbound connections — \"Allowed schemes: `http`, `https`, `ws`, `wss`.\" — scheme matching is case-insensitive consistently with isCrossOrigin (which already case-folds): a named test asserts an upper-cased scheme variant of an allowed scheme passes the scheme gate and proceeds to the IP checks (today the allowlist check compares the raw getScheme() against a lowercase set, so HTTP:// is rejected — fail-closed, so this is correctness-of-errors, not a security hole; the inconsistency with isCrossOrigin is the point)"
  - "Module tests assert the machine-readable rejection contract, not rewordable prose: the existing getMessage()-text assertions in the module's tests (13 at draft time, zero reason() assertions) are rewritten to assert SsrfPolicyException.reason(), keeping a message-text assertion only where the text itself is the documented contract"
  - "The stale WebSocket test narrative is corrected: the test named rejectsWebsocketSchemeForNow no longer claims ws/wss rejection is temporary (the WS wrapper shipped in the same class), and the WS-scheme acceptance path has at least one module-local named test"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D20
  - D38
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 134
      removed: 42
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: ea400e18b8fa1579e7d38b1b28fdeab08d13bfa6
    head: "working-tree @ m1/M1-214-ssrf-small-fixes-location-reso (uncommitted; review-time diff)"
    verdict_file: docs/plan/m1/redteam/M1-214-2026-06-08.md
    out_of_model_count: 2
    note: |
      Pre-commit audit on the round-1 APPROVED diff: CLEAN, no
      promise/delivery gap. Two advisory out-of-model notes: (1) IPv4
      special-purpose ranges (192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4)
      absent from the spec's committed category list — candidate for a
      future hardening ticket / design-note edit; (2) SsrfPolicyException
      messages embed attacker-controlled strings, neutralized by SafeLog
      and by the reason()-based caller contract this ticket strengthens.
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings: ["FILES-BUDGET-PLAUSIBLE: budget 6 is plausible but tight. The test rewrite (13 existing assertions) plus 4-5 new named tests may span 3-4 test classes. If the implementer discovers tests are spread across more files than the budget allows, escalate via the standard path rather than quietly expanding scope."]
  blockers: []
---

# M1-214: SSRF small fixes

## Context

Five small SSRF-module findings (unified S2, S3, S4, S6, S7 —
`deep-code-review/v2/UNIFIED.md` §2), all re-grounded 2026-06-07:

1. **S2 (med-low).** The redirect hop loop resolves the Location
   header with no RuntimeException handling; a malformed Location
   throws a raw IllegalArgumentException that escapes the module's
   SsrfPolicyException/IOException contract. The Reason enum already
   has REDIRECT_LOCATION_MISSING — the invalid case wants a sibling.
2. **S3 (low, sec).** fec0::/10 (deprecated IPv6 site-local) is the
   one private-equivalent v6 range the blocklist misses.
3. **S4 (low).** The scheme allowlist check is case-sensitive against
   a lowercase set while isCrossOrigin case-folds — Tier-A framing is
   binding: current behavior is fail-closed, so this is
   correctness-of-errors, not a vulnerability.
4. **S6 (low).** First independent verification at draft time
   (ACCEPTED-tier in the audit): 13 getMessage() assertions and zero
   reason() assertions in the module's tests — the javadoc calls the
   message text rewordable, so the tests pin the wrong surface.
5. **S7 (low).** rejectsWebsocketSchemeForNow still narrates a
   pre-WS-wrapper world.

**TEST-AUTH:** the message-text → reason() rewrite modifies existing
test assertions; that modification is explicitly authorized by this
ticket (test_plan.modifies).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T32 under `deep-code-review/v2/` (opus-48
  ssrf F1/F3/F4, kimi-folder ssrf F4/F5/F6, opus-47 ssrf F6).
- **Serialization (mandatory):** M1-191 rewrites PinnedDnsResolver and
  touches SsrfGuardedHttpClient in the same module at complexity high
  — do not run the two tickets concurrently; whichever lands second
  rebases.
- Reason-consumer sweep result is recorded in out_of_scope: UrlProbe
  (default arm absorbs new constants by design) and
  NostrRelayConnection (no Reason switch) need no change.
- Suggested direction for S6 is Tier-B (see below).

## Suggested direction (unverified hypothesis)

The audit (opus-48 ssrf F3) suggested rewriting the message-text
assertions to a `reason()`-based pattern, asserting the enum constant
per rejection class.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
