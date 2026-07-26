---
id: M1-703
title: "Summary degraded notice misleads when only some topics fail"
status: done
created: 2026-07-26
last_updated: 2026-07-26
blocked_by: []
clarity_check:
  date: 2026-07-26
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 384
      removed: 61
redteam_audits:
  - date: 2026-07-26
    gate: redteam-multi (user override; security_relevant: false)
    auditors: [kimi, opencode]
    verdict: CLEAN
    findings: 0
    evidence: docs/plan/m1/redteam-multi/M1-703-2026-07-26/
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The per-cluster degradation MECHANISM in SummaryProseGenerator (refusal
    marker / empty text / call-failed → ClusterProse.degraded=true). That is
    correct and stays; this ticket only changes how the COMPOSED reply
    represents partial vs total degradation to the user.
  - >-
    The window_too_large_notice path (en.properties:193 / cs.properties:191)
    — that notice fires only when the post count exceeds the summarizer cap,
    where NO prose is generated for ANY cluster, so its "no prose" wording is
    accurate. Untouched.
  - >-
    Model refusal behavior itself (DeepSeek refusing a malware-construction
    post is the model's safety classifier). The summarizer's per-topic
    degradation on refusal is the correct handling; this ticket is about the
    banner honesty, not the refusal.
  - >-
    The /retry render-form dispatch (M1-696/M1-699) and the M1-700 four-mode
    rendering. Only the degraded-notice gating on the /retry replay paths is
    in scope, not the replay mechanics.
acceptance:
  - >-
    When a /summary (bare, --full, or --flat) has SOME clusters degraded and
    the rest with successful prose, the user-visible reply does NOT claim
    "no prose" / "showing headlines + source URLs + post UIDs only". Either
    no total-degradation notice is shown, or a notice accurate for the
    partial case is shown. A named test in SummaryCommandHandlerTest pins
    the partial case (≥1 degraded AND ≥1 successful cluster) and asserts the
    total-degradation notice (reply.summary.degraded_notice) is absent, or
    that a partial-degradation notice names the degraded subset honestly.
  - >-
    When ALL clusters are degraded (total outage), the existing
    reply.summary.degraded_notice is still prefixed (behavior unchanged for
    the total case). A named test covers the total case.
  - >-
    The /retry replay paths (RetryCommandHandler --short and per-cluster)
    apply the same partial-vs-total distinction as the /summary paths — a
    partial replay does not claim total degradation. A named test in
    RetryCommandHandlerTest pins this.
  - >-
    Any new or changed notice copy is added to BOTH en.properties and
    cs.properties (en/cs parity per the M1-624 convention).
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs:
  - D43
---

# M1-703: Summary degraded notice misleads when only some topics fail

## Context

Follow-up from the 2026-07-26 live-test of the M1-685…701 fixes
(report `/home/infochat/M1-685-PLUS-LIVE-TEST-REPORT-20260726.md`,
Finding F1). The user acknowledged this as known and non-blocking; this
ticket records it for the backlog.

`reply.summary.degraded_notice` (en.properties:192 / cs.properties:190)
reads *"Summarizer LLM is unreachable; showing headlines + source URLs +
post UIDs only (no prose)."* That wording is accurate ONLY when the
summarizer failed for EVERY cluster. It is gated at the composition sites
by an ANY-degraded predicate, so it fires as soon as a SINGLE cluster
degrades — even when every other cluster returned full prose. The user is
then shown a reply that begins "…no prose" above a body that is in fact
mostly prose.

Observed live (2026-07-26, test instance at `f1043894`, admin DM,
`/summary -w 6h` and `/summary -w 6h --full`): one topic (`t-20ade06e`,
the SourTrace malvertising "browser builds the executable" story) hit
`SUMMARIZER returned refusal marker … degrading`
(`SummaryProseGenerator.java:132`); every other topic rendered full
synthesized prose. The reply nonetheless led with the "no prose" banner.
(The `--short` form, which makes one category-level LLM call and has no
per-cluster prose path, did NOT trip the banner — confirming the trigger
is the per-cluster any-match, not a real outage.)

The per-cluster granularity already exists — `ClusterProse.degraded`
(`SummaryProseGenerator.java:97`) and `DigestRenderer.ShortResult.anyRollupMissing`
(`DigestRenderer.java:366`) both carry it. The defect is purely that the
four composition sites translate "any" into a "no prose" claim, and the
`ShortResult` flag is itself an ANY signal by construction
(`DigestRenderer.java:348`).

## Census

Four call sites prefix `REPLY_SUMMARY_DEGRADED_NOTICE` on an ANY-degraded
predicate, plus one producer flag that is ANY by construction:

    grep -rn 'REPLY_SUMMARY_DEGRADED_NOTICE\|anyMatch(ClusterProse::degraded)\|anyRollupMissing' \
      --include=*.java infochat-provider/src/main/

| Site | Disposition |
|---|---|
| `SummaryCommandHandler.java:427` (--short path, `if (shortResult.anyRollupMissing())`) | fix |
| `SummaryCommandHandler.java:461` (bare/--full/--flat path, `boolean anyDegraded = prose.stream().anyMatch(...)`) | fix — the primary live-reproduced site |
| `RetryCommandHandler.java:293` (/retry --short replay, `anyRollupMissing()`) | fix — mirror the /summary --short fix |
| `RetryCommandHandler.java:304` (/retry per-cluster replay, `anyMatch(ClusterProse::degraded)`) | fix — mirror the /summary per-cluster fix |
| `DigestRenderer.java:327-356,366` (`ShortResult.anyRollupMissing` — set true if ANY category roll-up empty) | fix — carry a count (or partial/total distinction) instead of a single any-flag, so the --short callers can be honest |
| `infochat-provider/src/main/resources/bundles/en.properties:192` + `cs.properties:190` (the notice copy) | fix — new/changed copy for the partial case, en+cs |
| `BundleKeys.java:608` (the constant) | no change unless a new partial-notice key is added |

## Acceptance

Mirrors the YAML `acceptance:` list. A partial-degradation reply (some
clusters degraded, some with prose) must not claim "no prose" — either
suppress the total-degradation notice or emit one accurate for the partial
case (naming the degraded subset). The total-degradation case is
unchanged. The /retry replay paths apply the same distinction. Any new
copy lands in both en and cs bundles. The implementer picks the exact
representation (see §Notes); SummaryCommandHandlerTest and
RetryCommandHandlerTest encode the partial and total cases.
`mvn -pl infochat-provider -am verify` is green.

## Out-of-scope

See the YAML `out_of_scope:` list. In short: do not touch the
SummaryProseGenerator per-cluster degradation mechanism, the
window_too_large_notice (whose "no prose" wording is accurate), the
model's refusal behavior, or the /retry render-form dispatch. Only the
degraded-notice gating/copy at the composition sites is in scope.

## Notes

- Mechanism is the implementer's call and should be settled at `start`.
  Candidates: (a) gate the existing `degraded_notice` on `allMatch(...)`
  (total only) and emit a NEW `reply.summary.partial_degraded_notice`
  (with `{0}`/`{1}` count placeholders) when partial; (b) keep the
  single notice but make its wording cover both cases ("N of M topics
  could not be summarized…"); (c) drop the global banner entirely for
  the partial case and mark only the degraded clusters inline. Option
  (a) is the most explicit; (c) is the quietest. The existing tests pin
  the TOTAL case (their fixtures force all clusters to fail), so they
  keep passing; add a PARTIAL-case test (mixed degraded/successful
  clusters) that's currently impossible to assert honestly.
- The four sites should stay in lockstep — a /summary path and its /retry
  replay twin must report degradation identically, or /retry will
  contradict what the user just saw on /summary.
- Existing test anchors to update-be-aware-of (they assert the
  `degraded_notice` prefix is present on the TOTAL-degradation fixtures,
  which stays correct): `SummaryIT.java:212`,
  `SummaryCommandHandlerTest.java:483` (--short) and `:984`
  (per-cluster), `RetryCommandHandlerTest.java:454` (--short replay).
  Census the exact line numbers at `start` — they drift.
- Severity is low: this is a misleading-status UX/honesty defect, not a
  security-control failure (degradation itself is honest per-cluster;
  authorization, sanitization, and delivery are untouched). Not flagged
  `security_relevant` — the change touches reply composition copy/gating,
  not a security boundary. `security.md §Failure handling` is the
  honesty contract this ticket refinements the banner to actually honor.
