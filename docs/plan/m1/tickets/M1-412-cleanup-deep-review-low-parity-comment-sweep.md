---
id: M1-412
title: "cleanup: deep-review low-severity parity, dedup, and comment-drift sweep"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 16
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/PartitionScan.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "OpenAiCompatibleProvider / OpenAiCompatibleEmbeddingProvider — they do not read max-tokens (only AnthropicProvider does), so they get no max-tokens guard; their base-url + timeout-ms guards already hold (M1-330, M1-409)."
  - "isCrossOrigin — a runtime null-guard is NOT added; the fix is a one-line precondition comment only. Adding a defensive null-check would violate §No-defensive-code (the precondition is structurally guaranteed by callers passing absolute URIs)."
  - "PinnedDnsResolver.activePinsSnapshot and the IpBlocklist v6 helpers — no behavior change; the only changes are inlining the single-use helpers and a clarifying comment on the test-only seam."
  - "The CT1 fixes already landed in M1-409 (feed item-count caps, timeout-ms startup validation) — unchanged; this ticket fixes the REMAINING parity-drift instances surfaced by the same theme, it does not re-touch the landed ones."
  - "The deeper structural 'shared boundary-validation seam' (CT1 synthesizer suggestion) — recorded under Notes as an alternative, NOT built here; this ticket does the per-site fixes only."
  - "Asset/price paths, Flyway migrations, the partition-scan slack VALUE (Duration.ofDays(2) is unchanged) — only the duplication is removed, not the value."
acceptance:
  - "AnthropicProvider validates max-tokens positivity at the startup resolvability scan — the same boundary that already validates base-url (requireHttpBaseUrl) and timeout-ms (requirePositiveTimeoutMs) — via a new LlmHttpSupport.requirePositiveMaxTokens that mirrors requirePositiveTimeoutMs and names the offending property; a non-positive max-tokens is rejected at startup, not at first live call."
  - "A test under infochat-llm-adapter/src/test/.../impl asserts the startup scan rejects max-tokens <= 0, mirroring the existing timeout-ms startup-validation test; the at-or-above-zero positive case still resolves."
  - "SafeLog.formatSafe runs the caller msg through stripControls in addition to Redactor.redact before emission, closing the CR/LF/ANSI log-forgery asymmetry with the peer log boundary (ThrottledAdminNotifier already strips controls). A test under infochat-core/src/test/.../log asserts a msg containing control characters is stripped in the formatted line."
  - "The Duration.ofDays(2) partition-scan slack is single-sourced: PartitionScan.PARTITION_SCAN_SLACK becomes the one declaration, referenced by ReEvaluationJob, PerSourceUnknownTracker, and NostrStreamSource; their three duplicate private declarations are removed. The emitted slack value is unchanged."
  - "SsrfGuardedHttpClient.isCrossOrigin carries a comment documenting that from.getScheme() is non-null by caller precondition (callers pass an absolute URI), with no runtime null-guard added."
  - "SignalGroupHandler computes SignalMessageCodec.usableTimestamp once on the group inbound path (the two computations at SignalGroupHandler.java:173 and :192 collapse to one hoisted value); inbound behavior is unchanged."
  - "IpBlocklist inlines the single-use isLoopbackV6 and isAllZeroV6 predicates at their sole call sites and removes the helper methods; classification behavior is unchanged."
  - "PinnedDnsResolver.activePinsSnapshot carries a comment marking it a test-only inspection seam so it is not mistaken for dead code; no behavior change."
  - "SignalAdapter.awaitEndpoint parenthesizes the probe-timeout arithmetic so the doubling applies before the int cast — (int) (ENDPOINT_PROBE_INTERVAL.toMillis() * 2) — preserving the current value for the 100 ms constant."
  - "SimpleXMessageCodec.decodeError and SimpleXAdapter comments are corrected: the decodeError comment no longer claims to keep wire bytes out unconditionally (the recognized errorTag is still forwarded), and the SimpleXAdapter javadoc reference to a never-modeled AUTH_FAILED state is removed/corrected. Comment-only."
  - "All tests currently green on main remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (max-tokens startup-validation test, mirroring timeout-ms)
    - infochat-core/src/test/java/app/zcat/infochat/core/log (SafeLog.formatSafe control-strip test)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §User content in exceptions
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 156
      removed: 79
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-20
    verdict: CLEAN
    base: main
    head: m1/M1-412-cleanup-deep-review-low-parity-comment-sweep
    verdict_file: docs/plan/m1/redteam/M1-412-2026-06-20.md
    out_of_model_count: 0
    note: |
      Pre-commit redteam (between review APPROVE and commit) on this
      security_relevant ticket. CLEAN — no gaps between docs/spec/security.md
      and the diff (max-tokens guard, SafeLog control-strip, isCrossOrigin
      precondition comment, SimpleX/decodeError comment corrections all
      reviewed against the threat model). No remediation needed.
clarity_check:
  date: 2026-06-20
  verdict: WARN
  warnings:
    - "Acceptance items 5, 8, and 10 are comment-only acceptance items (verify by reading the diff that the comment is present and correct); weakest runnable form, but each correctly declares itself comment-only and names the exact site."
  blockers: []
---

# M1-412: deep-review low-severity parity, dedup, and comment-drift sweep

## Context

The 2026-06-20 `/deep-code-review full` run
(`.reviews/deep-review/full-2026-06-20-1855/`) surfaced eight findings across
six modules — **zero critical/high/medium, all eight `low`**. None is a live
bug: the two security-adjacent items (SafeLog log-forgery, isCrossOrigin SSRF
scheme dereference) were verified by the reviewers as not currently reachable,
and the rest are dedup/simplification/comment-precision. They were deliberately
deferred past beta user-testing as hygiene. This ticket batches all eight into
one focused sweep so each is fixed once, at its source, without interrupting the
test cycle. Every finding was re-verified at source on 2026-06-20 before this
ticket was written.

The two RULES-DRIFT items at the top (max-tokens guard gap, SafeLog
control-strip gap) are the remaining instances of the run's one cross-cutting
theme, **CT1 "sibling boundary-validation parity drift"** — a guard applied at
one boundary silently absent on a structurally identical sibling. The first two
CT1 instances (feed item caps, timeout-ms startup validation) already landed in
**M1-409**; max-tokens is the direct sibling of that timeout-ms guard and was
left behind.

## Acceptance

See frontmatter. Three behavior-changing fixes (max-tokens startup validation;
SafeLog control-strip on msg; partition-slack single-sourcing) plus five
behavior-preserving cleanups (isCrossOrigin precondition comment; SignalGroup
timestamp hoist; IpBlocklist helper inlining; PinnedDnsResolver seam comment;
SignalAdapter cast parenthesization; SimpleX comment corrections). The two new
tests mirror existing sibling tests (the timeout-ms startup test and the
SafeLog stripControls test).

## Out-of-scope

See frontmatter. The OpenAI providers get no max-tokens guard (they don't read
the property). `isCrossOrigin` gets a comment, **not** a runtime null-guard —
adding one would violate §No-defensive-code, since callers structurally
guarantee an absolute URI. The partition-slack *value* is unchanged; only its
four-way duplication is removed. The landed M1-409 CT1 fixes are not re-touched.
The deeper "shared boundary-validation seam" refactor is explicitly not built.

## Notes

- **Per-finding source map** (all verified 2026-06-20):
  - max-tokens: `AnthropicProvider.java:137` reads `max-tokens` with no positivity validator while `:132` (base-url) and `:136` (timeout-ms) both validate. → `04-module-infochat-llm-adapter.md#F1`
  - SafeLog: `SafeLog.formatSafe` (`SafeLog.java:70-73`) calls `Redactor.redact(msg)` but not the `stripControls` (`:35`) it owns; peer `ThrottledAdminNotifier` strips controls. → `02-module-infochat-core.md#F1`
  - partition slack: `Duration.ofDays(2)` declared in `PartitionScan.java:38`, `ReEvaluationJob.java:89`, `PerSourceUnknownTracker.java:50`, `NostrStreamSource.java:586` (they already cross-reference each other in comments but each redeclares). → `06-module-infochat-collector.md#F1`
  - isCrossOrigin: `SsrfGuardedHttpClient.java:521-525` dereferences `from.getScheme()`. → `03-module-infochat-ssrf.md#F3`
  - usableTimestamp: `SignalGroupHandler.java:173` and `:192` both call `usableTimestamp(...)`. → `05-module-infochat-messaging-adapter.md#F1`
  - IpBlocklist helpers: `isAllZeroV6`/`isLoopbackV6` defined `:382/:386`, sole call sites `:342/:345`. → `03-module-infochat-ssrf.md#F1`
  - activePinsSnapshot: `PinnedDnsResolver.java:181`. → `03-module-infochat-ssrf.md#F2`
  - SignalAdapter cast: `SignalAdapter.java:616` `(int) ENDPOINT_PROBE_INTERVAL.toMillis() * 2`. → `05-module-infochat-messaging-adapter.md#F3`
  - SimpleX comments: `SimpleXMessageCodec.java:727-751` (decodeError) and `SimpleXAdapter.java:46` (`AUTH_FAILED` javadoc — SimpleX has no auth; the dead auth.fail metric was already removed in M1-396). → `05-module-infochat-messaging-adapter.md#F2`
- **security_relevant: true** because two items touch documented security
  properties (log-forgery hardening, the SSRF cross-origin credential scrub),
  even though both are currently non-live. This invites a `/redteam` pass.
- **Alternatives considered (CT1 structural fix):** the synthesizer suggested
  making "all members of a boundary family share one validate/strip helper" a
  convention so a future property/sink inherits the guard by default, rather
  than fixing each site. That is the larger move; this ticket does the per-site
  fixes and does not build the shared seam. File a follow-up if the convention
  is wanted.
- **Implementer note:** the eight items are independent. If the reviewer or
  implementer prefers, this can be split per-module without loss — it is bundled
  per the explicit request to have one ready ticket, not because the changes are
  coupled.
- Full reports: `.reviews/deep-review/full-2026-06-20-1855/` (`00-summary.md`
  first).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-412-cleanup-deep-review-low-parity-comment-sweep.md
```
