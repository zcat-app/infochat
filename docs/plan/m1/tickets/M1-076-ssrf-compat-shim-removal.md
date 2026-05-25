---
id: M1-076
title: SSRF module — remove compat shims, deduplicate redirect loop, align timeout
status: ready
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 7
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFetcherTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1WatchdogIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerIT.java
  - docs/design/04-security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (03-module-infochat-ssrf.md#F1, 03-module-infochat-ssrf.md#F2, 03-module-infochat-ssrf.md#F4)
out_of_scope:
  - any IpBlocklist or PinnedDnsResolver change — those are correct as-is
  - any new SSRF defense or redirect-handling logic change — only structural dedup
  - any JSpecify annotation addition — M1-078 territory
acceptance:
  - "The two backwards-compatibility constructors (5-param 'M1-024' and 6-param 'M1-025') are removed. Only the full 7-param constructor and the no-arg CDI constructor remain. Verify: grep for 'preserved as a stable public API surface' in SsrfGuardedHttpClient.java returns empty"
  - "All external callers (RssFetcherTest, UrlProbeTest, and any others) are migrated to the full 7-param constructor. Verify: mvn clean verify green"
  - "get(URI) delegates to get(URI, Map.of()) — the redirect loop exists in exactly one place. Verify: code inspection shows get(URI) is a one-liner"
  - "DEFAULT_CONNECT_TIMEOUT is 5 seconds (matching docs/design/04-security.md). Verify: SsrfGuardedHttpClientTest.defaultConnectTimeoutIsFiveSeconds passes"
  - "mvn clean verify (full suite from root) is green"
test_plan:
  adds:
    - SsrfGuardedHttpClientTest.defaultConnectTimeoutIsFiveSeconds (new)
  modifies:
    - RssFetcherTest (update constructor call to 7-param)
    - UrlProbeTest (update constructor call to 7-param)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF protection
  - docs/design/04-security.md §Timeouts
decision_refs: []
---

## Context

Three issues in the SSRF module identified by deep review:

1. **Compat shims (§7 violation):** Two constructors exist solely for backwards compatibility with prior M1 tickets. Their javadoc explicitly says "preserved as a stable public API surface." M1 is greenfield — compat shims are forbidden.

2. **Redirect loop duplication:** `get(URI)` and `get(URI, Map<String,String>)` duplicate ~60 lines of the redirect loop, differing by one line (`extraHeaders.forEach`). In a security-critical module, duplicated control flow is a maintenance hazard — a fix applied to one copy but not the other would be a latent vulnerability.

3. **Timeout mismatch:** `DEFAULT_CONNECT_TIMEOUT` is 10s but `docs/design/04-security.md` documents 5s. The deployed behavior is 2x what the design states.

## Fix approach

1. Remove both compat constructors. Migrate the 2 external test callers to the full 7-param form.
2. Make `get(URI)` a one-liner delegating to `get(URI, Map.of())`.
3. Change `DEFAULT_CONNECT_TIMEOUT` from 10s to 5s.
