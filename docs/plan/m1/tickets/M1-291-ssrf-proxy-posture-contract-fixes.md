---
id: M1-291
title: "SSRF module: proxy posture, pinned-path policy, bounded discard, contract fixes"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 14
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceProxyPostureTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/UrlProbe.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeProxyPostureTest.java
  - docs/spec/deployment.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Consolidating the many per-caller SsrfGuardedHttpClient constructions onto the shared producer (opus-47/03 observation) — backlogged, not this ticket.
  - The blocklist/range policy itself and the M1-277 pin fast path — unchanged.
  - DNS-cache behaviour and resolver service registration NAME (must not change; pinned by acceptance).
acceptance:
  - "U-09: SsrfGuardedHttpClient's HttpClient builder (~:243) sets .proxy(HttpClient.Builder.NO_PROXY) so ambient JVM proxy properties (http.proxyHost/https.proxyHost/socksProxyHost) can never route guarded requests through a proxy that re-resolves the target and voids the DNS-pin rebind defense; a named test asserts the built client's proxy() is NO_PROXY. The caller-side WebSocket dials are gated by the SAME posture at their builders: the JDK HttpClient built in NostrStreamSource (~:349, the client injected into NostrRelayConnection's WebSocket dial) and UrlProbe.relayDialClient both set .proxy(HttpClient.Builder.NO_PROXY) — a WebSocket dial inherits its proxy selector from the HttpClient and cannot override it per-dial, so the builder is the only lever. Empirically confirmed (JDK 25): a default HttpClient.newBuilder().build() routes a non-loopback target through ambient http.proxyHost; NO_PROXY disables it. Named tests assert proxy()==NO_PROXY on the NostrStreamSource and UrlProbe dial clients (or the equivalent posture-asserting test). docs/spec/deployment.md gains one operator-facing sentence stating guarded egress ignores JVM proxy settings."
  - "U-39: PinnedDnsResolver.lookupByName honors LookupPolicy on the pinned path (today only the delegate gets it): the pinned address set is filtered by the policy's address-family characteristics in both resolver paths; named tests for IPv4-only and IPv6-only policies against a dual-family pinned set."
  - "U-40: redirect-hop response bodies are drained through a bounded discard (a discardBounded sibling of readBounded) instead of close(). The discard MUST be bounded in TIME as well as size: like readBounded, discardBounded enforces a total wall-clock deadline (bodyReadDeadline) and a per-read wall-clock watchdog (each in.read clamped to min(readTimeout, remaining-to-deadline)) so a slow-dribble redirect body cannot hold the fetcher thread past the deadline — closing the DoS gap that a size-only cap leaves open (docs/spec/security.md §SSRF: read-timeout caps are enforced on outbound body reads, redirect hops included; redteam_findings[0]). A redirect body that exceeds the time bound surfaces the same typed SsrfPolicyException reason readBounded raises (BODY_READ_TIMEOUT / BODY_READ_DEADLINE_EXCEEDED). Named tests assert (a) a redirect hop with an oversized body stops reading at the size cap, and (b) a redirect hop whose body stalls/dribbles past the deadline aborts with the typed timeout reason rather than blocking unbounded."
  - "U-37: HostInterfaceSet.enumerate() failure surfaces from resolveAndValidate as a typed SsrfPolicyException reason instead of IllegalStateException escaping get()'s documented SsrfPolicyException/IOException surface; a named test."
  - "U-38: readBounded propagates InterruptedException instead of wrapping it in IOException (get() already declares throws InterruptedException); interrupt-status handling follows the project's existing interrupt convention; a named test asserts an interrupt during the body read surfaces as InterruptedException (not IOException)."
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
reviews:
  - round: 1
    date: 2026-06-12
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 12
      added: 567
      removed: 156
  - round: 2
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 624
      removed: 157
  - round: 3
    date: 2026-06-12
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 922
      removed: 228
  - round: 4
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 969
      removed: 228
escalations:
  - date: 2026-06-12
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      Round-3 reviewer returned MANUAL. All checks PASS except SCOPE-DRIFT-CHECK:
      FAIL — a files_scope MEMBERSHIP breach. The two new U-09 proxy-posture
      tests live in the collector and provider src/test packages
      (infochat-collector/src/test/.../stream/nostr/NostrStreamSourceProxyPostureTest.java,
      infochat-provider/src/test/.../source/UrlProbeProxyPostureTest.java), but
      the collector/provider files_scope entries are specific src/main .java
      files (NostrRelayConnection.java, NostrStreamSource.java, UrlProbe.java),
      not directories — so neither test file matches any files_scope entry.
      Unresolvable ticket-internal conflict: the refined U-09 acceptance MANDATES
      "Named tests assert proxy()==NO_PROXY on the NostrStreamSource and UrlProbe
      dial clients," whose only correct home is the sibling src/test package of
      each class (package-private access), yet the budget-breach revision added
      the main NostrStreamSource.java to files_scope but NOT the two test
      directories. Developer cannot widen files_scope; round-2 APPROVE missed it
      (same two files were present then). Resolution requires a files_scope
      amendment adding the collector stream/nostr and provider source src/test
      directories. Build green, all acceptance PASS, code otherwise correct.
  - date: 2026-06-12
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-291 (--in-progress, post-review-APPROVE round 2) returned
      FINDINGS: 1 HIGH (DOS). discardBounded (the U-40 redirect-hop drain,
      SsrfGuardedHttpClient.java:722-735, called at :413) enforces a SIZE cap
      only — no per-read watchdog and no total deadline — unlike readBounded
      (:634-700) which bounds every read by min(readTimeout, remaining) plus a
      total bodyReadDeadline. request.timeout bounds only header receipt, not
      the ofInputStream() body, so an attacker-injected redirect whose body is
      dribbled slowly holds a fetcher thread for hours (M1-025 slow-dribble
      DoS reintroduced on the redirect path). Verified at source. Full text in
      redteam_findings[0] and docs/plan/m1/redteam/M1-291-2026-06-12.md.
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — files_scope path-breach surfaced at start/grounding before any
      code was written. U-09's Nostr arm requires .proxy(NO_PROXY) on the
      HttpClient built at infochat-collector/.../stream/nostr/NostrStreamSource.java:349,
      which is NOT in files_scope (only NostrRelayConnection.java is). The
      WebSocket dial in NostrRelayConnection inherits its proxy posture from
      that injected HttpClient and cannot override it per-dial
      (WebSocket.Builder has no proxy() method). Empirically confirmed
      (JDK 25 loopback probe): a default HttpClient.newBuilder().build()
      routes a non-loopback target through ambient http.proxyHost; NO_PROXY
      disables it. The acceptance escape hatch ("comment showing the builder
      path already cannot pick up ambient proxies") is therefore false.
revisions:
  - date: 2026-06-12
    reason: budget-breach refine (add NostrStreamSource to files_scope for U-09; tighten U-09/U-38 acceptance; raise risk)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      risk_at_snapshot: medium
      files_scope_at_snapshot:
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
        - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/source/UrlProbe.java
        - docs/spec/deployment.md
      acceptance_at_snapshot:
        - "U-09: ... a named test asserts the built client's proxy() is NO_PROXY. The caller-side WebSocket/HTTP dials in NostrRelayConnection and UrlProbe get the same posture (or a named test/comment showing their builder path already cannot pick up ambient proxies); ..."
        - "U-38: ... a named test or the compiler pins the new signature path."
  - date: 2026-06-12
    reason: redteam-finding refine (U-40 HIGH DOS — discardBounded must time-bound the redirect-hop drain, not just size-bound it)
    snapshot:
      status: escalated
      escalation_reason: redteam-finding
      acceptance_at_snapshot:
        - "U-40: redirect-hop response bodies are drained through a bounded discard (a discardBounded sibling of readBounded) instead of close(); a named test asserts a redirect hop with an oversized body stops reading at the cap."
  - date: 2026-06-12
    reason: manual-verdict refine (add the two collector/provider proxy-posture test files to files_scope — U-09's mandated NostrStreamSource/UrlProbe proxy-posture tests had no scoped home; the budget-breach refine added the main files but not their sibling test files)
    snapshot:
      status: escalated
      escalation_reason: manual-verdict
      files_scope_at_snapshot:
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
        - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/source/UrlProbe.java
        - docs/spec/deployment.md
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-12
    category: DOS
    severity: high
    promise: |
      docs/spec/security.md §SSRF and outbound connections: "Redirect,
      body-size, connect-timeout, and read-timeout caps are enforced; an
      unset timeout is a configuration error." The read-timeout cap exists
      because a malicious upstream can dribble body bytes one per minute and
      hold a fetcher thread for hours (readBounded watchdog rationale,
      M1-025 Finding 4); the terminal-hop body read defends this with a
      per-read wall-clock watchdog plus a total bodyReadDeadline.
    gap: |
      The new redirect-hop drain discardBounded (SsrfGuardedHttpClient.java
      :722-735, called at :413) enforces only a SIZE cap (while total <= cap),
      NOT a time cap. It calls in.read(buf) directly on the fetcher thread
      with no per-read watchdog and no total deadline — unlike readBounded
      (:634-700) which supervises every read on a virtual thread bounded by
      min(readTimeout, remaining-to-deadline). The per-hop request.timeout
      bounds only receipt of response HEADERS, not reads from the
      ofInputStream() body, so the redirect-body drain has no wall-clock
      bound. U-40 delivered the size half of the cap but dropped the
      read-timeout/deadline half the spec commits to.
    repro: |
      Adversary controls (or injects a redirect into) a Collector feed URL or
      an /add-source probe target — both spec-untrusted. The malicious server
      answers the first hop with a 30x + Location (so the wrapper enters
      discardBounded), then trickles the redirect body at ~1 byte/minute,
      staying under bodyCap total. discardBounded blocks in in.read(buf) on
      the fetcher thread with no timeout, holding it for hours. Repeating
      across the fetch concurrency pool starves the Collector's ingest
      workers — the slow-dribble hold the per-read watchdog was built to
      prevent, reintroduced on the redirect path.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-12
    verdict: FINDINGS
    base: 591c3b2a28192e606cf0d35c5362ffc33ec319af
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-291-2026-06-12.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      In-progress audit (--in-progress) between review APPROVE round 2 and
      commit. One HIGH DoS finding verified at source: discardBounded
      redirect-hop drain is size-bounded but not time-bounded, unlike
      readBounded. One advisory OUT-OF-MODEL note (ambient-proxy bypass on
      LLM/adapter clients) the subagent itself classifies as intended scope.
      Disposition pending user decision.
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1 (U-09): the 'or a named test/comment' escape hatch for NostrRelayConnection and UrlProbe caller posture allows non-testable verification; prefer a named test or an explicit code assertion over a prose comment."
    - "ACCEPTANCE-RUNNABLE item 5 (U-38): 'a named test or the compiler pins the new signature path' is ambiguous; prefer a named test or name the specific compiler guarantee."
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium under-states U-09's severity (proxy bypass voids the DNS-pin rebind defense on all guarded egress); consider risk: high."
  blockers: []
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

## Round 1 rework

Reviewer verdict: REWORK (round 1) — ACCEPTANCE-CHECK PARTIAL. All checks PASS
and the build is green; the sole gap is one missing named test.

1. Add a named test asserting the resolver service-registration name is
   unchanged, as U-48 requires ("the resolver service-registration name is
   unchanged (named pin test)"). `PinnedDnsResolver.Provider` is an
   `InetAddressResolverProvider` with a public `name()` returning
   `"infochat-ssrf-pinned-resolver"` (`PinnedDnsResolver.java:140-141`); add a
   test in the ssrf src/test package asserting
   `new PinnedDnsResolver.Provider().name().equals("infochat-ssrf-pinned-resolver")`
   so a future rename — which would silently break JDK resolver SPI
   registration — fails the build. This is the only acceptance sub-clause
   without its named test.

Address only this item, then re-run `mvn verify`, then `/m1-tick review M1-291`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-291-*.md
```
