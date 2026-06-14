---
id: M1-355
title: "ssrf: resolve the close-drain body-cap contradiction on the followed-redirect path and unify the two bounded-read loops"
status: done
created: 2026-06-14
last_updated: 2026-06-14
escalations:
  - date: 2026-06-14
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — surfaced at start of implementation. Acceptance item 4 asserts
      "Existing SSRF body-cap and slow-dribble tests stay green," but acceptance
      item 2 reverses discardBounded's over-cap behaviour from "stop reading and
      return" to "throw BODY_CAP_EXCEEDED." The existing green test
      SsrfGuardedHttpClientTest.discardBoundedStopsReadingAtCap
      (SsrfGuardedHttpClientTest.java:994-1035) pins the OLD return-without-throw
      behaviour and therefore CANNOT stay green under item 2. The test_plan has no
      `modifies` field authorising the rewrite. Modifying a green test without
      test_plan authorisation violates the engineering rules → escalate→refine to
      add the authorisation.
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 1's 'result is recorded in a code comment' clause is verifiable only by reading the diff; would be self-verifying if it named the expected comment content (whether close() on an ofInputStream() body drains)."
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-ssrf/pom.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The redirect cap, body cap, and the per-read/total body-read watchdog values — unchanged; this fixes how the over-cap case is handled, not the limits.
  - The three-constructor parameter chain → Config-record refactor (opus-47 ssrf F2) — explicitly deferred; the reviewer judged it not worth a dedicated ticket today.
  - The header-injection boundary guard (opus-47 ssrf F1, Accept/User-Agent) — tracked separately; not in this ticket.
acceptance:
  - "An integration test pins the actual JDK 25 HttpResponse.BodyHandlers.ofInputStream() close()-after-partial-read behaviour against a fixture that serves more than bodyCap bytes on a followed (within-cap) 3xx hop, proving whether close() drains; the result is recorded in a code comment so the wrapper's own contradictory comments (the explicit response.body().close() at the redirect-cap path vs the discardBounded javadoc) no longer disagree."
  - "discardBounded no longer relies on an implicit close-drain to bound an over-cap followed-redirect body: once total exceeds cap it treats the hop as a policy violation (throws SsrfPolicyException BODY_CAP_EXCEEDED) rather than breaking the loop and letting try-with-resources close() potentially read the remainder unbounded — matching the terminal readBounded path."
  - "readBounded and discardBounded are expressed through one shared supervised-drain helper parameterized by a per-chunk sink (accumulate vs discard), so the size cap, per-read watchdog, and total deadline have a single point of truth and cannot drift between the terminal and redirect paths."
  - "All existing SSRF body-cap and slow-dribble tests stay green EXCEPT discardBoundedStopsReadingAtCap, which is converted (see test_plan.modifies) to assert the new over-cap abort because it pinned the exact return-without-throw behaviour item 2 reverses; a test covers the over-cap followed-redirect hop aborting with BODY_CAP_EXCEEDED."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (real-HttpClient over-cap redirect close-behaviour IT + over-cap-redirect abort test)
  modifies:
    - "SsrfGuardedHttpClientTest.discardBoundedStopsReadingAtCap (SsrfGuardedHttpClientTest.java:994-1035): rewritten to assert discardBounded now THROWS SsrfPolicyException BODY_CAP_EXCEEDED on an over-cap body, replacing the OLD assertion that it returns and merely stops reading at the cap. The old assertion pinned the precise behaviour acceptance item 2 reverses, so it cannot stay green; this is a behaviour-reversal test update authorised via escalate→refine (premise-fail, 2026-06-14). If the discardBounded(InputStream, long cap) signature loses its now-redundant cap parameter (item 3 unifies on the bodyCap field as the single point of truth), the mechanical call-site update to discardBoundedAbortsWhenRedirectBodyStallsPastReadTimeout (SsrfGuardedHttpClientTest.java:1037-1081) is authorised under the same arm."
  preserves:
    - all tests currently green on main except those listed under modifies
spec_refs: []
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
      files: 4
      added: 270
      removed: 84
revisions:
  - date: 2026-06-14
    reason: "premise-fail refine — authorise the behaviour-reversal test modification omitted from the original test_plan."
    snapshot:
      acceptance_item_4: "Existing SSRF body-cap and slow-dribble tests stay green; a test covers the over-cap followed-redirect hop aborting with BODY_CAP_EXCEEDED."
      test_plan:
        adds:
          - "infochat-ssrf/src/test/java/app/zcat/infochat/ssrf (real-HttpClient over-cap redirect close-behaviour IT + over-cap-redirect abort test)"
        preserves:
          - "all tests currently green on main"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: be64c431f152eac468d8b20c3aa6a179edb43739
    head: working-tree-m1-M1-355
    verdict_file: docs/plan/m1/redteam/M1-355-2026-06-14.md
    out_of_model_count: 0
    note: |
      Run after review APPROVE (round 1), before /m1-tick commit, against the
      working-tree implementation (diff vs branch fork point). CLEAN, no
      out-of-model items. The over-cap followed-redirect abort is a fail-closed
      tightening of the SSRF body-cap posture; nothing feeds a future ticket.
---

# M1-355: SSRF redirect body-cap + bounded-read unification

## Context

**opus-48 `03-module-infochat-ssrf.md` F1** (medium, SECURITY) + **F2** (low,
SIMPLIFICATION — the structural fix that makes F1 permanent). opus-47's ssrf
synthesizer observation independently flagged the same load-bearing `close()`
assumption and recommended a focused integration test.

**Verified at source 2026-06-14** — the codebase contains two contradictory
statements about `ofInputStream()` `close()`:
- `SsrfGuardedHttpClient.java:431-436` (redirect-cap-exceeded path) closes the
  body explicitly and comments it releases the connection *"without the
  read-and-discard that a drain would perform."*
- `SsrfGuardedHttpClient.java:744-747` (discardBounded javadoc) states `close()`
  on an `ofInputStream()` body *"can read-and-discard the WHOLE
  (attacker-controlled) body."*

Both cannot be true. `discardBounded` (lines 761-776) loops `while (total <= cap)`
then exits to try-with-resources `in.close()` on a **non-EOF** stream when the
body exceeds cap — so if `close()` drains, the followed-redirect path performs the
exact unbounded read the body cap exists to prevent. `readBounded` (628-654)
throws `BODY_CAP_EXCEEDED` on over-cap but also closes a non-EOF stream on the way
out, so it shares the exposure. The spec commits the body-size cap is enforced and
"never buffers an unbounded response" (docs/spec/security.md §SSRF; design
§SSRF rule 5).

This is the strongest security item across both v6 runs and the one finding the
two runs converge on. The IT settles the JDK behaviour fact both reviewers could
not verify by inspection; Option A (abort over-cap) is safe regardless of the
answer.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- A redirect hop with a multi-megabyte body is anomalous; failing closed on it is
  the correct outbound-SSRF posture and matches the terminal-body treatment.
- `infochat-ssrf/pom.xml` is in scope only if the IT needs a test-scope HTTP
  fixture dependency; prefer the JDK's own `HttpServer` to avoid a new dep.
