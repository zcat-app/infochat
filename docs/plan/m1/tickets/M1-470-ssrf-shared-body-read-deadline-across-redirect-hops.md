---
id: M1-470
title: "SSRF: share one body-read deadline across all redirect hops of a get()"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 2
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  # The per-read watchdog (supervisedReadChunk's readTimeout classification),
  # the body-size cap (bodyCap / BODY_CAP_EXCEEDED), the redirect cap, the
  # DNS-pin re-validation per hop, and the cross-origin header scrub all stay
  # exactly as-is. This ticket changes ONLY where bodyReadStartNanos is
  # captured (the time budget's origin), not how any limit is enforced.
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
acceptance:
  - >-
    SsrfGuardedHttpClient.get(URI, Map) captures a single bodyReadStartNanos
    (one System.nanoTime() sample) before the redirect loop and threads it
    into every discardBounded(...) call inside the loop and the terminal
    readBounded(...) after it; supervisedDrain and supervisedReadChunk take
    bodyReadStartNanos as a parameter instead of each capturing their own.
    The net effect: the cumulative body-read wall-clock across all followed
    redirect-body drains plus the terminal read is bounded by ONE
    bodyReadDeadline, matching the "TOTAL wall-clock time" the class javadoc
    (and DEFAULT_BODY_READ_DEADLINE / supervisedReadChunk javadoc) already
    promises for the M1-026 Finding 1 drip-attacker defense.
  - >-
    A new test drives a redirect chain of redirectCap followed hops where each
    hop's body drips under readTimeout (so the per-read watchdog never fires)
    but consumes a large share of bodyReadDeadline, and asserts the whole
    get() call aborts once cumulative body-read time crosses one
    bodyReadDeadline (a BODY_READ_DEADLINE / read-timeout classification) —
    i.e. the bound is NOT (redirectCap + 1) x bodyReadDeadline. The existing
    single-hop deadline and per-read-watchdog tests remain green unchanged
    (behaviour on a no-redirect fetch is byte-for-byte identical).
  - mvn -B verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
      — redirectChainSharesOneBodyReadDeadline(): a redirectCap-length
      self-redirect chain dripping each body under readTimeout aborts the
      get() once cumulative body-read elapsed exceeds one bodyReadDeadline,
      proving the deadline is per-call not per-hop.
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-470: SSRF — share one body-read deadline across all redirect hops of a get()

## Context

`SsrfGuardedHttpClient.get(URI, Map)` follows up to `redirectCap` redirects.
For each followed hop it drains the redirect body with `discardBounded`
(`SsrfGuardedHttpClient.java:451`); after the loop it reads the terminal body
with `readBounded` (`SsrfGuardedHttpClient.java:492`). Both funnel through
`supervisedDrain`, which captures its own `bodyReadStartNanos` at
`SsrfGuardedHttpClient.java:793` — once **per body-read phase**.

The class frames `bodyReadDeadline` as a *total* wall-clock bound: the class
javadoc (`SsrfGuardedHttpClient.java:76`), `DEFAULT_BODY_READ_DEADLINE`'s
comment, and `supervisedReadChunk`'s javadoc
(`SsrfGuardedHttpClient.java:683-687`) all describe it as bounding "the TOTAL
wall-clock time" of the body-read phase against "a drip attacker that returns
1 byte per (readTimeout - epsilon)" — the exact M1-026 Finding 1 vector.

Because each `discardBounded` and the final `readBounded` get a *fresh*
`bodyReadStartNanos`, a single `get()` actually grants `redirectCap + 1`
independent full deadlines. An attacker controlling a public host (passing
`IpBlocklist` + DNS-pin re-validation each hop) can self-redirect
`redirectCap` times, dripping every hop's body fast enough that each
individual `in.read()` returns under `readTimeout` (per-read watchdog never
fires) but slow enough to consume nearly a whole `bodyReadDeadline` per hop.
At defaults (`redirectCap=3`, `bodyReadDeadline=2m`) one `get()` can occupy a
fetcher thread for ~8 minutes of body-read time instead of the documented ~2.

The per-read watchdog still prevents the original *unbounded* DoS, so this is
a bounded constant-factor amplification of a defense bound, not a reopening of
the unbounded case — hence **low** severity. But it contradicts the stated
invariant, on the SSRF trust boundary with an attacker-controlled vector,
which is why it is worth closing.

Source: `/deep-code-review full` (2026-06-27), ssrf report F1.

## Acceptance

See frontmatter. In prose: capture one `bodyReadStartNanos` in `get()` before
the redirect loop; pass it through `discardBounded`, `readBounded`, and
`supervisedDrain` (drop their internal captures) so `supervisedReadChunk`'s
`elapsedNanos = System.nanoTime() - bodyReadStartNanos` measures cumulative
body-read time across every hop. The existing deadline-classification logic at
the watchdog then bounds the whole call by one `bodyReadDeadline` unchanged.
Add a redirect-chain test proving the per-call (not per-hop) bound; full suite
green.

## Out-of-scope

See frontmatter. No change to the per-read watchdog, the body-size cap, the
redirect cap, DNS pinning, or the cross-origin scrub — only the origin of the
single shared time budget moves from inside `supervisedDrain` up to `get()`.

## Notes

- This is the fix labelled "Option A" in the deep-review report; "Option B"
  (keep per-hop deadlines but reword the javadoc to "per body-read phase" and
  document the effective `(redirectCap + 1) x bodyReadDeadline` bound) was
  rejected because it leaves the ~8-minute fetcher occupation in place and
  weakens the M1-026 Finding 1 guarantee in writing rather than restoring it.
- Trade-off accepted: a legitimate multi-redirect feed now shares one 2-minute
  body-read budget across all hops rather than a fresh 2 minutes per hop.
  Redirect bodies are normally empty/tiny and the terminal body is the only
  substantial read, so 2 minutes for one real body remains generous.
- This is `security_relevant`: run `/redteam M1-470` before merge.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-470-*.md
```
