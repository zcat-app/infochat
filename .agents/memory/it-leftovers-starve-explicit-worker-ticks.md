---
name: it-leftovers-starve-explicit-worker-ticks
description: A collector IT whose leftover RAW posts are pending for the tagger/entity/classifier workers can starve a later IT that drives those workers' onTick() explicitly — the tick picks at most maxConcurrency posts, ORDER BY fetched_at ASC; seed entity_done/classifier_done=TRUE unless the fixture is about that stage.
metadata:
  type: project
---

Collector pipeline ITs share ONE Dev Services Postgres per module and clean
up only their own uid-prefix — the LAST test of a class leaves its posts
behind. Those leftovers matter beyond their own IT: several pipeline ITs
(e.g. `ReEvalVerdictNotifyIT.unknownBenignReEvalCompletesPipelineAndEmitsNewPost`)
drive `taggerWorker/entityExtractorWorker/classifierWorker.onTick()`
EXPLICITLY (the scheduler is halted under `%test`), and each such tick
processes at most `infochat.llm.<task>.max-concurrency` (4) pending posts,
`ORDER BY fetched_at, id ASC`. A leftover RAW post that is pending for one
of those stages (e.g. `tagger_done=TRUE, entity_done=FALSE`) and carries an
EARLIER `fetched_at` than a later IT's fixture occupies that fixture's
one-tick slot: the fixture's stage never completes, the ReadyPromoter gate
holds, and the later IT fails with a baffling `expected READY but was RAW`
while passing in isolation.

Observed 2026-08-01 (M1-715): EmbeddingWorkerIT's new gate test left two
`entity_done=FALSE, classifier_done=FALSE` posts at fetched_at 2026-05-16;
the re-eval IT's June-7 fixture starved on both the entity and classifier
slots (each tick processed exactly 4 = the limit — the tell). On main the
same test had ≤3 such leftovers and squeaked through.

Rule for fixture authors: a seeded post that is not the subject of a given
stage must be marked DONE for that stage (`entity_done=TRUE`,
`classifier_done=TRUE`, `summary_done=TRUE` as applicable) so it is
invisible to that worker's pickup; the alternative (deleting fixtures at
test end) fights the per-class cleanup idiom. Related:
[[scan-window-fixture-timebombs]] (the other fixture-hygiene guard),
[[full-suite-timing-flakes]] (isolation-vs-suite divergences are real
regressions by construction, but ONLY when the suite is actually green on
main — check leftover composition before blaming timing).
