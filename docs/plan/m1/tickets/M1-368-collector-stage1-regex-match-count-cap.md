---
id: M1-368
title: "collector: bound Stage 1 regex match collection with a fail-closed match-count cap"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The per-character wall-clock watchdog (regex-timeout-ms) — unchanged; this adds an orthogonal count bound, it does not replace the time bound.
  - The 5 MiB inbound body cap in infochat-ssrf — unchanged; the amplification this addresses is the match-list-per-body factor, not the body size.
  - The Stage1RegexSet rule set and overlap-resolution algorithm — unchanged.
acceptance:
  - "findAllMatchesUnderWatchdog bounds the accumulated match list: a new config knob infochat.security.stage1.max-matches caps how many Match tuples may be collected across all rules for one body. When the cap is exceeded the post is routed to the SAME fail-closed whole-body quarantine path as the watchdog abort (a dedicated error_class, e.g. stage1.match_overflow, and rule_id), never silently truncated and never allowed to keep allocating past the cap."
  - "A unit test in infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1 feeds a body crafted to exceed max-matches and asserts (a) the fail-closed whole-body quarantine row is written with the overflow error_class and (b) the post does not reach the normal redact/accept path."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1 (add the match-overflow fail-closed test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-368: Stage 1 regex match-count cap (fail-closed)

## Context

Deep-review v7 (opus-48) collector finding **F1** (SECURITY). Verified at
source 2026-06-14:

`Stage1Pipeline.findAllMatchesUnderWatchdog`
(`infochat-collector/.../eval/stage1/Stage1Pipeline.java:322-357`) collects
every regex hit into `List<Match> all` with `while (m.find())` across all 8
`Stage1RegexSet.RULES`. The only bound is the `InterruptibleCharSequence`
per-character wall-clock deadline (`infochat.security.stage1.regex-timeout-ms`,
default **100ms**). The match *list* and the downstream `rowsToInsert`
quarantine list have no count bound: a hostile feed body (bounded by the 5 MiB
SSRF body cap × 8 rules) can transiently allocate a large match list within the
100 ms window before the time-based watchdog fires.

**Severity note (honest):** the original report called this "unbounded"; it is
strictly **bounded** by `bodyCap × ruleCount` and the 100 ms window, so the real
exposure is a bounded-but-amplified transient allocation (tens–hundreds of MB
under a crafted feed, multiplied by eval concurrency), not an unbounded leak. It
is still a legitimate fail-closed hardening on the upstream-untrusted ingest
boundary, hence a real ticket — but it is a hardening item, not an active
exploit. The collector has no user-facing API and sources are admin-curated, so
the practical trigger is a compromised or malicious upstream feed.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Reuse the existing watchdog-abort quarantine path (`handleWatchdogAbort` /
  the `REGEX_TIMEOUT_RULE_ID` whole-body row shape) rather than inventing a new
  failure shape — overflow is the same fail-closed outcome as a timeout, just a
  different trigger.
- Pick a default `max-matches` comfortably above any legitimate feed (a real
  post hits a handful of injection patterns at most); the cap exists for the
  pathological case only.
