---
id: M1-286
title: "UNKNOWN-rate auto-disable stops the running stream-source worker"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceSupervisor.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Polled fetchers — FetchScheduler already skips disabled sources on subsequent ticks; only the long-running stream-source worker ignores the disable today.
  - A scheduled reconciliation loop for operator-side /source-disable of stream sources — optional hardening the report mentions; file separately if wanted (see Notes).
  - Re-keying the supervisor keyspace by source UUID — the minimal v1 fix is the javadoc correction plus the registrar-mediated stop (see Notes); re-keying is the alternative, not the default.
  - The UNKNOWN-rate threshold/window mechanics themselves.
acceptance:
  - "Spec sentence implemented (docs/spec/security.md ~:953, verbatim: 'Auto-disable only blocks new ingest — it stops the fetcher or stream-source worker from enqueueing new posts from the source.'): a named test auto-disables a running Nostr stream source via PerSourceUnknownTracker and asserts the worker for that source stops enqueueing (supervisor stop reached for the right dispatch key)."
  - "Spec sentence preserved (verbatim: 'Posts already in the outbox or re-evaluation queue continue through their current evaluation stage unaffected'): a named test asserts already-enqueued posts from the disabled source still complete their current stage."
  - "The disable signal travels tracker → NostrStreamSource.Registrar (owner of the sourceId → dispatchKey map) → supervisor.stop(dispatchKey); the tracker gains no direct supervisor dependency; a stop for an unknown/already-stopped source id is a logged no-op (boundary, not internal defensive code)."
  - "U-58 fixed first: StreamSourceSupervisor.stop's javadoc no longer blesses cross-keyspace calls (fetch and stream dispatch keys are both monotonic from 1, so a cross-keyspace stop(key) would stop an unrelated stream) and no longer claims a false no-op behaviour; the corrected javadoc states the single-keyspace contract this ticket's new caller relies on."
  - "The existing admin notification on auto-disable now tells the truth: it fires only after (or together with) the worker stop, so 'source disabled' is accurate; existing notification tests stay green or are updated to the new ordering."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
- If the optional scheduled reconciliation for operator-side disables is
  wanted, file it as a follow-up ticket; do not fold it in here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-286-*.md
```
