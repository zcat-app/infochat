---
id: M1-286
title: "UNKNOWN-rate auto-disable stops the running stream-source worker"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceSupervisor.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - Polled fetchers — FetchScheduler already skips disabled sources on subsequent ticks; only the long-running stream-source worker ignores the disable today.
  - A scheduled reconciliation loop for operator-side /source-disable of stream sources — optional hardening the report mentions; file separately if wanted (see Notes).
  - Re-keying the supervisor keyspace by source UUID — the minimal v1 fix is the javadoc correction plus the registrar-mediated stop (see Notes); re-keying is the alternative, not the default.
  - The UNKNOWN-rate threshold/window mechanics themselves.
acceptance:
  - "Spec sentence implemented (docs/spec/security.md ~:953, verbatim: 'Auto-disable only blocks new ingest — it stops the fetcher or stream-source worker from enqueueing new posts from the source.'): a named test auto-disables a running Nostr stream source via PerSourceUnknownTracker and asserts the worker for that source stops enqueueing (supervisor stop reached for the right dispatch key)."
  - "Spec sentence preserved (verbatim: 'Posts already in the outbox or re-evaluation queue continue through their current evaluation stage unaffected'): the already-green test PerSourceUnknownTrackerTest.autoDisable_inflightPostsContinueUnaffected covers this — it asserts already-enqueued posts from the disabled source still complete their current stage — and must remain green and unmodified."
  - "The disable signal travels tracker → NostrStreamSource.Registrar (owner of the sourceId → dispatchKey map) → supervisor.stop(dispatchKey); the tracker gains no direct supervisor dependency; a stop for an unknown/already-stopped source id is a logged no-op."
  - "U-58 fixed first: StreamSourceSupervisor.stop's javadoc no longer blesses cross-keyspace calls (fetch and stream dispatch keys are both monotonic from 1, so a cross-keyspace stop(key) would stop an unrelated stream) and no longer claims a false no-op behaviour; the corrected javadoc states the single-keyspace contract this ticket's new caller relies on and contains a greppable phrase asserting stop accepts only a dispatch key minted by this same supervisor instance (e.g. the phrase 'only a dispatch key returned by this supervisor')."
  - "The existing admin notification on auto-disable now tells the truth: within disableSource() the worker-stop signal is dispatched before the admin notify, so 'source disabled' is accurate. A new named test (in the stream/nostr suite) asserts the stop signal reaches the supervisor before the admin notify fires (ordering). No pre-existing test is modified: the existing presence-based notification tests (PerSourceUnknownTrackerTest.unknownRateExceedsThreshold_disablesSource and twoSourcesDisabledInWindow_eachNotifies) assert only that a notification was emitted — a property the reordering preserves — so they stay green unchanged; no existing test pins the old notify-without-stop ordering. Firing the new disable signal must not break these tests when no stream source is registered for the disabled id (the Registrar observer is then a logged no-op per the item above)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  modifies: []
  preserves:
    - "PerSourceUnknownTrackerTest.autoDisable_inflightPostsContinueUnaffected (acceptance item 2 — already green; unmodified)"
    - "PerSourceUnknownTrackerTest.unknownRateExceedsThreshold_disablesSource (notification presence — stays green; unmodified)"
    - "PerSourceUnknownTrackerTest.twoSourcesDisabledInWindow_eachNotifies (per-source notify — stays green; unmodified)"
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
      files: 7
      added: 366
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
revisions:
  - date: 2026-06-11
    reason: "clarity-fail refine — TEST-CHANGES-AUTHORIZED blocker + 4 warnings"
    summary: |
      Pre-refine state (git history carries the full prior frontmatter):
      - test_plan.modifies listed the eval/reeval test directory; acceptance
        item 5 used a 'stay green or are updated' disjunction naming no test.
        Resolved option (a): no pre-existing test is modified (modifies: []);
        the existing notification tests assert presence only and stay green.
      - acceptance item 3 embedded the '(boundary, not internal defensive code)'
        design directive — moved to Notes.
      - acceptance item 4 verified javadoc 'by inspection' — now requires a
        greppable contract phrase.
      - acceptance item 2 now names the existing green test that satisfies it.
      - risk: medium → high; round_cap: 2 → 3 (confirmed SECURITY/HIGH U-03 fix).
escalations:
  - date: 2026-06-11
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.modifies lists the eval/reeval
      test directory, but acceptance item 5 ("existing notification tests stay
      green or are updated to the new ordering") names no specific test file/
      method and no new expected assertion for the "are updated" case. Pre-existing
      test modifications must be explicitly listed with the new expected behavior.
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: 05da0987e58a80cfdfebd233de5a7bb641f69a48
    head: working-tree (m1/M1-286-auto-disable-stops-stream-worker)
    verdict_file: docs/plan/m1/redteam/M1-286-2026-06-12.md
    out_of_model_count: 1
    note: |
      Adversarial audit on the in-progress branch before commit. CLEAN — the
      SourceDisabled event → Registrar → supervisor.stop path and the U-58
      javadoc contract introduce no threat-model gap; auto-disable now actually
      bounds the admin-review-capacity-exhaustion attack U-03 named. One
      advisory out-of-model observation recorded in the verdict file; no
      remediation ticket needed.
clarity_check:
  date: 2026-06-11
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-286: UNKNOWN-rate auto-disable stops the running stream-source worker

## Context

Deep-review v5 verified SECURITY/HIGH **U-03** + LOW **U-58**
(`deep-code-review/v5/UNIFIED-REPORT.md` §2/§4; sources
`deep-code-review/v5/fable-5/06-module-infochat-collector.md#F1` (includes
the registrar-event sketch), `deep-code-review/v5/gpt-55/report.md#H-01`,
`fable-5/06#F8` for U-58 — gitignored; all load-bearing facts inlined):

`PerSourceUnknownTracker.disableSource()` only updates `source.status` and
notifies the admin; it contains zero references to the stream supervisor
(verified 2026-06-11: no "supervisor" occurrence in the file). The only
`StreamSourceSupervisor.stop()` callers are NostrStreamSource's own
relay-health paths. Consequence: a malicious or compromised Nostr source
keeps enqueueing posts after auto-disable until restart — the
admin-review-capacity-exhaustion attack the mechanism exists to bound
continues, while the admin notification claims the source was disabled.

U-58 is bundled because this ticket adds the first cross-class caller of
`StreamSourceSupervisor.stop`, and that method's javadoc currently blesses
a call pattern that would stop the wrong source (fetch and stream keyspaces
are both monotonic from 1).

## Acceptance

See frontmatter. The two spec sentences are transcribed verbatim from
`docs/spec/security.md` (verified 2026-06-11).

## Out-of-scope

See frontmatter.

## Notes

- Fix shape (fable-5 sketch, non-binding): a CDI event fired by the tracker,
  observed by `NostrStreamSource.Registrar`, which owns the
  `sourceId → dispatchKey` map and calls `supervisor.stop(dispatchKey)`.
  This keeps the tracker free of stream-module dependencies.
- Fix U-58's javadoc BEFORE wiring the new caller (the report is explicit
  about the order) — the corrected contract is what the new call site is
  written against.
- Acceptance item 3's no-op for an unknown/already-stopped source id is
  system-boundary handling at the Registrar's event-observer entry (the
  sourceId → dispatchKey map is the boundary), NOT internal defensive code
  between two trusted internal classes — so it does not violate the
  No-defensive-code rule.
- If the optional scheduled reconciliation for operator-side disables is
  wanted, file it as a follow-up ticket; do not fold it in here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-286-*.md
```
