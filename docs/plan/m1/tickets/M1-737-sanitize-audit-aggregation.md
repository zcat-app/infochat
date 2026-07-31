---
id: M1-737
title: "Aggregate sanitize audit rows per distinct token per call"
status: done
created: 2026-07-31
last_updated: 2026-07-31
blocked_by: [M1-730]
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Narrowing the 2000-char `DisplayHeadline.BODY_SCAN_LIMIT` sanitize
    unit, or any change to `DisplayHeadline`. That unit is M1-729's
    shipped contract on four callers; this ticket changes the SHAPE of
    the audit emission, not what the sanitizer is handed.
  - >-
    Per-command rate buckets. The inbound limiter is a single token
    bucket per contact (`RateCapBucket`, default 60 msg/min) with no
    per-command partition; building that partition is M1-705.
  - >-
    `audit_log` schema changes. None are needed — `detailsJson` already
    carries a `match_count` field (`emitAuditRows` writes the literal
    `"match_count":1` today).
  - >-
    The INFO-LEAK residual from the same M1-730 audit (the `/saved`
    quarantine interlock). It is fixed inside M1-730 itself.
acceptance:
  - >-
    A named `LlmOutputSanitizerTest` test passes pinning the aggregated
    shape: one `sanitize` call over a field carrying N occurrences of
    the same closed-list token writes ONE `LLM_OUTPUT_SANITIZED`
    `audit_log` row for that token whose `detailsJson.match_count` is
    exactly N — no occurrence suppressed, no per-occurrence rows.
  - >-
    A second named `LlmOutputSanitizerTest` test passes pinning that
    DISTINCT tokens still get one row each, and that the redacted output
    text is byte-identical to today's per-occurrence implementation's —
    the rewrite is unchanged; only the emission aggregates.
  - >-
    The one-WARN-per-match log line at the strip loop
    (`LLM_OUTPUT_SANITIZED token=... position=...` — the log-flood
    amplifier riding the same vector) collapses to one WARN per
    distinct token per call, carrying the count.
  - >-
    The three pre-existing contract pins of per-occurrence emission are
    reworked in step to the aggregated shape (named here per the
    test-edit disclosure rule): `SummaryCommandHandlerTest`'s two
    `LLM_OUTPUT_SANITIZED` row pins (one at the producer/ renderer
    pair, one pinning per-occurrence emission, ~:967 and ~:1561) and
    `ClusterBlockRendererTest`'s pin (~:231). New expectation: one row
    per distinct token carrying the exact count.
  - >-
    `docs/spec/security.md` is amended in the same diff: §LLM output
    sanitizer's "Every match is audit-logged (per-occurrence, not
    throttled)" becomes the aggregated commitment (every match is
    audit-logged; rows aggregate per distinct token per call and carry
    the exact occurrence count — counted, never throttled), and the
    §"Flag position mirrors the parser's own scan" caller enumeration's
    "`/saved` reply one row's title and one row's tags" is updated to
    the post-M1-730 unit (one row's title OR up to `BODY_SCAN_LIMIT`
    chars of its body, plus one row's tags) — the out-of-model
    spec-text drift from the same audit.
  - >-
    The two residual per-occurrence commitments in the same section are
    amended to the aggregated shape in the same diff (M1-737 redteam
    finding 1, 2026-07-31): "the `/save -t` render-side redaction ...
    emits the per-occurrence `LLM_OUTPUT_SANITIZED` audit row on every
    hit" (~:421) and "within one field, per-occurrence
    `LLM_OUTPUT_SANITIZED` rows fire at least as often as before the
    narrowing" (~:547). New wording keeps every-hit audit coverage but
    states the aggregated cardinality (rows aggregate per distinct
    token per call, carrying the exact occurrence count).
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  preserves:
    - all tests currently green on main, except the three per-occurrence
      contract pins the acceptance list names for in-step rework
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Rate limiting
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 404
      removed: 70
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-31
    verdict: FINDINGS
    base: 2e6b7a6c
    head: working tree (pre-commit branch tip, /m1-tick run gate)
    verdict_file: docs/plan/m1/redteam/M1-737-2026-07-31.md
    findings_count: 2
    out_of_model_count: 0
    note: |
      Two low findings, both spec-text. Finding 1 (stale per-occurrence
      sentences at security.md:421/:547) remediated via the
      redteam-finding refine (80e64b43) — folded into acceptance.
      Finding 2 falsified against main (M1-730, e9bd3fc0): a
      worktree-staleness artifact. Auditor: kimi threat-actor via
      run-gate.sh, contamination=none.
  - date: 2026-07-31
    verdict: CLEAN
    base: 2e6b7a6c
    head: working tree + refine commit 80e64b43 (re-audit, round 2)
    verdict_file: docs/plan/m1/redteam/M1-737-2026-07-31-r2.md
    out_of_model_count: 0
    note: |
      Re-audit of the post-refine diff with explicit re-audit framing.
      Round-1 finding 1 verified remediated; finding 2 not re-reported
      (falsified against main). CLEAN. Auditor: kimi threat-actor via
      run-gate.sh, contamination=none.
clarity_check:
  date: 2026-07-31
  verdict: PASS
  warnings:
    - "self-check: flag-bearing entries emit their per-occurrence WARN inside redactFlagEntry (LlmOutputSanitizer.java:325), not only at the regex strip loop (:578); implementing acceptance item 3 as one WARN per distinct token per call across BOTH paths, since the item's own wording is per-token-per-call and the Notes require uniform amplification capping"
  blockers: []
escalation_reason:
---

# M1-737: Aggregate sanitize audit rows per distinct token per call

## Context

Filed from the M1-730 redteam disposition (audits
`docs/plan/m1/redteam/M1-730-2026-07-30.md` and `-r2.md` — the
DOS/medium residual both rounds re-reported).
`LlmOutputSanitizer.emitAuditRows` writes one uncapped `audit_log`
INSERT per closed-list OCCURRENCE, and M1-730 makes the 2,000-char
`BODY_SCAN_LIMIT` sanitize unit reachable from a user-pulled command by
promoting `saved_post.body` into the `/saved` headline. A body of
concatenated `/audit` tokens yields ~333 matches per row (the
closed-list word-boundary lookahead admits a following `/`), so one
20-row `/saved` page costs ~6,600–10,000 INSERTs; at the single inbound
bucket's 60 msg/min cap that is ~400k–600k INSERTs/min, self-contained
— the attacker needs only their own registered account past probation,
no victim. The pre-M1-730 baseline at the same cap was ~48k/min
(200-char write-capped title). `security.md` §Rate limiting files
`/saved` under "One bucket; high cap; cheap." `blocked_by: [M1-730]`
because the worst case is M1-730's reachability; the emission shape
itself is independent.

## Acceptance

See the YAML `acceptance:` list. In prose: aggregate the audit emission
per distinct token per call (the count rides `detailsJson.match_count`,
a field the emission already writes as the literal `1`), collapse the
matching per-occurrence WARN, rework the three consumer pins in step,
and amend the two stale `security.md` sentences in the same diff.

## Out-of-scope

The sanitize UNIT (M1-729's 2000-char contract) stays — narrowing it
for one caller breaks the shared-derivation property, narrowing it for
all four re-opens M1-729. No per-command buckets (M1-705's territory).
No schema change. The sibling INFO-LEAK residual landed in M1-730.

## Notes

- Redteam 2026-07-31 (kimi threat-actor, FINDINGS low=2): finding 1
  (residual per-occurrence sentences at security.md ~:421/~:547) is
  folded into this ticket via the refine — see the acceptance list.
  Finding 2 (`/saved` body-scan claim undelivered) was FALSIFIED
  against main: M1-730 landed at e9bd3fc0 after this branch's fork
  point, and on main `SavedCommandHandler.java:328` sanitizes via
  `DisplayHeadline.of(row.title, row.body, ...)` with the
  `BODY_SCAN_LIMIT` bound repeated in its SELECT (:123) — the claimed
  unit exists in the merge result; the gap was a worktree-staleness
  artifact. Re-audit must not re-report it.
- Falsified alternatives, for the record: (a) capping occurrences per
  field with an overflow marker IS throttling — an attacker pads benign
  hits to push later ones past the cap, degrading the audit signal
  exactly under attack; aggregation has no such hole because counts are
  exact. (b) An ingest-time cap on `post.body` guts the summarizer's
  input. (c) A save-time cap on `saved_post.body` corrupts the D13
  snapshot `/export` reads. (d) Accept-and-amend the rate-bucket text
  is the honest fallback but strictly inferior while this fix is small.
- `/audit` reads `audit_log_view` generically
  (`AuditCommandHandler.java:177-216`), so a row with
  `match_count:333` renders with no consumer change. Positions were
  never in the rows (only in the WARN stream), so no forensic signal is
  lost; the collapsed WARN keeps token + count.
- The aggregation caps the same amplification on the three
  digest/summary surfaces uniformly — one change, no caller drift.
