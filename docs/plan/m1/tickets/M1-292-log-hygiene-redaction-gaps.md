---
id: M1-292
title: "Log hygiene: redactor timeout arms, URL paths, upstream bytes, LLM previews"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/UrlRedactor.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  # Added by round-1 budget-breach refine (2026-06-12): U-11's UrlRedactor
  # shape change breaks these collector caller-tests, which pin the old shape.
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/SingleGetFetchTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerLogRedactionTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Redactor's pattern catalogue and in-band sentinel design (v4 recorded wont-fix) — only the timeout arms change.
  - UrlRedactor callers' choice of WHAT to log — only the redaction shape changes.
  - LLM request/response handling beyond the logging/exception-text surface.
acceptance:
  - "U-07: Redactor.isLoggable's message-timeout and parameter-timeout arms call record.setThrown(null) exactly as the thrown-timeout arm already does (today only :152 does), so a scan timeout can never leak the unscanned thrown graph — raw exception messages, the very place secrets live — to the console formatter (fail-closed contract, security.md §Secrets handling); an isLoggable(record, timeoutMs) test seam is added and named tests drive each timeout arm (the filter-level timeout path is currently untested)."
  - "U-11: UrlRedactor collapses the URL path to /[REDACTED] (today :69-72 appends getRawPath() verbatim while redacting only the query), so path-borne secrets (Slack/Discord webhook tokens) no longer reach WARN logs; scheme, host, and port are preserved (what triage needs); a named test feeds a webhook-shaped URL and asserts the token never appears in output; existing UrlRedactor tests are updated to the new shape, not deleted — this includes the collector caller-tests (SingleGetFetchTest, FetchSchedulerLogRedactionTest) that pin the old redacted-URL shape; their security assertions (no userinfo/token leak) are preserved."
  - "U-12: asset-source upstream bytes are control-stripped and truncated before logs and admin notifications: KrakenSnapshotSource no longer embeds errorArr.toString() raw in exception text (~:139), and AssetSnapshotFetcher applies SafeLog.stripControls + truncation to the cause message it logs and forwards into notifyOnce (~:315); named tests with control-character payloads."
  - "U-13: LlmHttpSupport no longer puts provider error-body previews into WARN logs or exception messages by default (~:139) — those bodies can echo request fragments/user content, bypassing the SafeLog discipline at worker call sites; default text carries provider name, HTTP status, and host only; the preview() helper's mislabeled 'bytes' (it counts chars) javadoc/label is corrected in passing since the method is rewritten."
  - "LinkingJob's logging goes through the module's SafeLog discipline like its collector siblings (fable-5/06#F9)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/log
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 413
      removed: 86
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: 45040c0c2211acbb188122f89b6a15eb1df4b4f4
    head: working-tree-uncommitted
    verdict_file: docs/plan/m1/redteam/M1-292-2026-06-12.md
    out_of_model_count: 2
    note: |
      --in-progress audit of the uncommitted log-hygiene/redaction diff
      (Redactor, UrlRedactor, LlmHttpSupport, AssetSnapshotFetcher,
      KrakenSnapshotSource, LinkingJob + tests). No gap between the threat
      model's redaction promises and the diff. 2 out-of-model advisories
      (advisory only) recorded in the verdict file; no remediation ticket.
revisions:
  - date: 2026-06-12
    reason: budget-breach refine — widen files_scope for U-11 caller-tests
    snapshot:
      files_budget: 14
      files_scope_added:
        - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/SingleGetFetchTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerLogRedactionTest.java
escalations:
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-review. U-11's mandated UrlRedactor shape change (path now
      collapses to /[REDACTED]) breaks two collector caller-tests that pin
      the OLD shape and are NOT in files_scope:
        - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/SingleGetFetchTest.java
          (asserts ".../rss?[REDACTED]"; the security assertions — no userinfo/token
           leak — still pass)
        - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerLogRedactionTest.java
          (asserts the path "example.com/feed" survives as diagnostic context;
           U-11 now collapses the path — direct semantic conflict)
      Updating both needs files_scope +2 paths and files_budget 14→16
      (currently at 14). The main/ fetchers only call UrlRedactor (no output
      assertions), so no production code changes.
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE [5]: LinkingJob item names no specific test, no line anchor, cites a gitignored report (fable-5/06#F9); implementer must read sibling collector code to learn the SafeLog pattern."
    - "SELF-CONTAINED-CHECK: same root cause — LinkingJob item delegates to 'like its collector siblings' plus an inaccessible report; the SafeLog.stripControls+truncation pattern is established elsewhere (U-12)."
  blockers: []
---

# M1-292: Log hygiene: redactor timeout arms, URL paths, upstream bytes, LLM previews

## Context

Deep-review v5 verified **U-07** (MEDIUM), **U-11** (MEDIUM), **U-12** (LOW),
**U-13** (LOW-MED), plus the LinkingJob SafeLog bypass and the preview()
label fix from U-72 (`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources
`fable-5/02#F1`, `gpt-55#M-01`, `opus-47/03#F2`, `fable-5/06#F5`,
`gpt-55#L-05`, `gpt-55#M-05`, `fable-5/06#F9`, `fable-5/04#F5` —
gitignored; all load-bearing facts inlined):

One theme: bytes from untrusted or secret-bearing surfaces reach logs,
exception messages, or admin notifications without the redaction/stripping
discipline the rest of the codebase applies. All file:line anchors verified
2026-06-11 (Redactor setThrown only at :152/:156; UrlRedactor path append
at :69; AssetSnapshotFetcher notifyOnce(cause.getMessage()) at :315-316;
LlmHttpSupport preview at :139).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-13 alternative the report mentions (preview behind an explicit
  unsafe-debug config) is allowed but NOT the default — propose it at start
  only if operator-side debugging genuinely needs the body text;
  otherwise plain removal is the simpler form.
- Coordination: M1-296 also edits LlmHttpSupport (shared ObjectMapper /
  exception type) and M1-291 edits the ssrf module. Different regions;
  check the worktree landscape at start.
- M1-272 (redactor thrown-chain hygiene) is the precedent for the Redactor
  test style; extend, don't fork.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-292-*.md
```
