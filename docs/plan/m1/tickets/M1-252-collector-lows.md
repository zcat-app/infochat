---
id: M1-252
title: "Collector lows: Nostr digest reuse, Stage2 redundant UPDATE, ssrf producer"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEventVerifier.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/ssrf/CollectorSsrfClientProducer.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - ReEvaluationJob and the re-eval candidate scan — owned by M1-245.
  - EmbeddingWorker's InterruptedException handling (report T26) — DROPPED after verification: the catch already restores the interrupt flag (Thread.currentThread().interrupt()) with a documented shutdown rationale, which is correct handling, NOT a silent swallow. Do not touch it.
  - The Nostr signature-verification algorithm and the Stage2 verdict semantics — unchanged; T24/T25 are allocation/redundant-write cleanups, behavior identical.
  - Re-pointing the consumers that bypass CollectorSsrfClientProducer — T27 addresses the producer itself (document-as-canonical or remove), not a mass consumer rewrite.
acceptance:
  - "T24: NostrEventVerifier reuses a MessageDigest rather than allocating a new one per verify() (a thread-safe strategy — ThreadLocal or reset-per-call, implementer's choice given verify() may run on multiple stream threads); a named test asserts verification of a known-good and a known-bad event is unchanged."
  - "T25: Stage2VerdictHandler does not issue a separate UPDATE post for stage2_verdict (line ~213) in addition to the stage2_done/status UPDATE on the same (id, fetched_at) row in the same transaction — the two writes to that row are folded into one UPDATE; a named test asserts the verdict + done/status persist correctly with a single UPDATE on that row. (If the implementer finds the two UPDATEs are NOT on the same row/flow, escalate — the fold is unsafe.)"
  - "T27: CollectorSsrfClientProducer is reconciled with its consumers — EITHER its legitimate consumer keeps using it and the producer is documented as the one supported construction path, OR (if genuinely bypassed/redundant) it is removed and its consumer migrated to the same guarded construction the others use; the chosen direction is justified in the commit message and all collector SSRF clients still go through the guarded path. Existing collector tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 281
      removed: 46
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-252: Collector lows

## Context

Three low-severity `infochat-collector` findings bundled by module, re-grounded
against current main. Source: `deep-code-review/v3/` UNIFIED-REPORT.md T24 (mimo
`06#F1`), T25 (mimo `06#F2`), T27 (opus `06#F2`).

- **T24.** `NostrEventVerifier` allocates a `MessageDigest` per `verify()`
  (`MessageDigest.getInstance("SHA-256").digest(...)`).
- **T25.** `Stage2VerdictHandler` issues a 2nd `UPDATE post` (stage2_verdict) on a
  row the parent transaction already updates (stage2_done/status) — foldable.
- **T27.** `CollectorSsrfClientProducer` is bypassed by most consumers —
  reconcile the producer with reality (document-as-canonical or remove).

**Dropped during verification — report T26 ("EmbeddingWorker swallows
InterruptedException silently"):** falsified. The catch already calls
`Thread.currentThread().interrupt()` to restore the interrupt status and carries
a documented shutdown rationale ("Swallowing the interrupt here would hinder
shutdown"). That is the correct handling of `InterruptedException`, not a silent
swallow; the only thing absent is a log line, which is optional on a clean
shutdown path. Excluded from scope.

## Acceptance

See frontmatter. In prose: reuse the digest; fold the redundant Stage2 UPDATE;
reconcile the SSRF producer with its consumers. Named tests pin each
behavior-preserving change; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The re-eval scan (M1-245), the dropped T26 EmbeddingWorker
catch, the Nostr verify algorithm, the Stage2 verdict semantics, and mass-rewiring
SSRF consumers are untouched.

## Notes

- T24: `MessageDigest` is not thread-safe — confirm `verify()`'s concurrency
  before choosing `ThreadLocal<MessageDigest>` vs `reset()` per call.
- T25: the fold is only safe if the two UPDATEs truly hit the same row in the
  same transaction/flow — the named test must prove it; if not, escalate rather
  than force the fold.
- T27: if removal is chosen, confirm the one real consumer is migrated to the
  same guarded construction the others use, so no client escapes the SSRF guard.
  If unsure, prefer document-as-canonical over removal.
</content>
</invoke>
