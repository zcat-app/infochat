---
id: M1-951
title: "Tech-leg disposition: drift restore, 946/947, caveat"
status: done
created: 2026-08-28
last_updated: 2026-08-28
flow: tick
reproduction: >-
  Probe (disposition ticket; the M1-944 measurement posture — the missing
  statements ARE the wrong behavior): (a) the tech world's DB no longer
  matches its labels — an operator RetrievalEvalRunnerIT smoke against the
  test DB today exits with the runner's named refusal "DB fingerprint drift
  against the labels" (RetrievalEvalRunnerIT.java:296-302): the frozen pin
  ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed…927
  no longer holds (brief-given operator observation, re-derived at start:
  world READY posts with ready_at beyond the frozen max — 48 posts, 47 arXiv
  + 1 BBC, ready_at up to 2026-08-27 23:54, an external collector boot; not
  restored), so EVERY campaign owner-run delta gate (M1-944's section,
  record :485-496) is currently unrunnable and M1-946's four rows cannot
  label against the pin; (b) `grep -ci representativeness
  docs/measurement/retrieval-eval-baseline.md` returns 0 (verified
  2026-08-28) — the record's numbers are presented without the world-scope
  caveat the shadow RESULTS.md records, and the golden set's demotion to
  tech-instance regression suite is stated nowhere.
analysis_ref: docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md
blocked_by: []
files_scope:
  - scripts/tech-drift-restore.sql
  - docs/measurement/retrieval-eval-baseline.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Re-labeling or extending the tech golden set — the set is M1-942's frozen
    output; the RESTORE path exists precisely so no re-label is needed. Only
    if the user rules re-label / accept-drifted-world at start does that
    path open, and it is then its own supersedes-relabel ticket (rule D1's
    explicit path) — never an in-place edit here.
  - >-
    M1-946's four xling rows — that ticket owns them (re-scoped per the
    disposition below); M1-947's record section — its reading is superseded
    by the two-leg record's tech leg (M1-952), and the redirect NOTE in the
    old baseline record is M1-952's, not this ticket's.
  - >-
    The fam leg entirely (M1-948/949/950) and any harness change — this
    ticket touches the TECH world and the TECH record only.
  - >-
    ANY production / main-source change and ANY docs/spec/** edit — the SQL
    is an operator procedure against the test DB, doc-visible; probe: git
    diff --name-only names no src/main and no docs/spec path.
  - >-
    The frozen stack's non-post state beyond the drift set — existing
    sources, posts, embeddings, and the two landed results sections are
    untouched (the note is append-only; the SQL deletes ONLY the identified
    drift rows and their dangling derivatives).
acceptance:
  - "Pre-mutation snapshot (the lane's standing constraint, analysis P13): the infochat-test_infochat-pgdata volume is snapshotted BEFORE any delete, and the snapshot's location is recorded in the ticket notes — probe: the ticket notes name the snapshot artifact (operator-local); the SQL file's header states the precondition."
  - "The restore SQL is deterministic and minimal (analysis P13): it identifies the drift set mechanically — world-visible READY posts (the D59 WORLD_WHERE shape, RetrievalEvalRunnerIT.java:388-395) with ready_at STRICTLY AFTER the frozen max 2026-08-24 16:00:57.001472+00 — prints the set for review (count expected 48, 47 arXiv + 1 BBC, brief-given, RE-DERIVED by the SELECT before any DELETE), deletes those posts' dangling derivatives (post_embedding, entity/tag links, references, queue rows) and the posts themselves, inside a transaction, and ends with the runner-equivalent fingerprint SELECT — probe: the SELECT-only prefix run first prints exactly the drift set; the DELETE is gated on operator review of that printout."
  - "Fingerprint exactness (FAILURE-MODE, analysis P13): after the delete, the fingerprint read returns the frozen pin BYTE-EXACTLY (ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de1…927) — a post-restore read that differs by ANY component is a STOP (recorded as refused, fallback to the user's ruling: re-label vs accept-drifted-world), never an obstacle to route around — probe: the ticket notes restate the post-restore fingerprint read; an operator M1-929 smoke then passes with label_fingerprint_match true (the executable oracle)."
  - "A NEW dated section is APPENDED to docs/measurement/retrieval-eval-baseline.md — byte-identical prior content (append-only; corrections stay visible) — stating: (1) the REPRESENTATIVENESS caveat (every number in this record describes the TECH instance's world — ~90% tech tags; the fam instance runs the same architecture over a global general-news distribution where ai/cyber are tail; instrument-local vs architecture-level findings split per the shadow record's caveat); (2) the DEMOTION (the golden set is the tech-instance regression suite; no decision-grade product-wide claim off it alone — two-leg gating supersedes); (3) the drift disposition (what happened, what was restored, the fingerprint proof, or the user's alternative ruling if restore was not taken); (4) the M1-946/947 disposition — probes: `grep -ci representativeness docs/measurement/retrieval-eval-baseline.md` returns >= 1; git diff over the record shows pure additions; `grep -c '^## ' docs/measurement/retrieval-eval-baseline.md` grows by exactly 1."
  - "The M1-946/M1-947 disposition is EXECUTED per the ruling recorded at start (clarity_check), and the appended note names it. RECOMMENDED fork (analysis Decomposition): KEEP M1-946 refined — its floor-bump survives, its reading consumer is re-pointed from M1-947 to M1-952's tech leg, and blocked_by gains M1-951 (its rows label against the pin, which only exists again after the restore); ABORT M1-947 (abandoned_reason: superseded — its widened-set re-baseline reading IS the tech leg of M1-952's mixed baseline; a separate section would duplicate the same run against the same frozen stack). The alternative forks (abort both; keep both unchanged) remain the user's call — probe: the driver state (frontmatter of M1-946/M1-947) matches the ruling named in the appended note."
  - "mvn verify from repo root is green (doc + SQL diff; the SQL is not executed by any test — verify runs for the suite, and doc-only inert-diff discipline does not apply because scripts/ IS in the diff); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen — probe: git diff --name-only."
test_plan:
  adds: []
  modifies:
    - >-
      docs/measurement/retrieval-eval-baseline.md (AUTHORIZED: ONE new dated
      section APPENDED; every existing line byte-identical — the M1-944
      append precedent).
  preserves:
    - all tests currently green on main (no test diff at all).
    - >-
      the golden set, the harness, and the two landed Results sections —
      untouched.
spec_refs:
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs:
  - D19
  - D29
  - D58
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-28
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: NOT-APPLICABLE; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "4 files, +295/-10 (SQL +178 new, record +63 pure append, ticket +50/-6 incl. Run notes, board +14/-10 regen)"
    notes: "0 findings; 4 falsification candidates dropped with citations (dry-run/re-run TOCTOU defeated by in-tx re-derivation + two DO asserts; summary_anchor over-delete defeated by reviewed 0-count printout; predicate drift vs runner WORLD_WHERE defeated by byte-comparison + label_fingerprint_match empirical proof; uncovered derivative tables defeated by schema enumeration — six non-FK tables all deleted explicitly). Reviewer verified the smoke manifest on disk, pure-additions shape, 946/947 frontmatter, log freshness, no secrets. Verdict: .scratch/tick-review-M1-951-r1.txt"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  start_repro: >-
    No blocking question. Reproduction re-derived at start (2026-08-28,
    read-only): the fingerprint read returns ready=5261;max_ready_at=2026-08-27
    23:54:23.003542+00;uid_sha256=776e8c7c… (drift confirmed); the MECHANICAL
    drift set (D59 world READY strictly after the frozen max) is 47 posts —
    all arXiv cs.AI — and 5261-47=5214 closes the arithmetic. The brief's
    "48, 47 arXiv + 1 BBC" counts one extra READY post beyond the frozen max
    that is WORLD-INVISIBLE (BBC rss via a source_origin=user source, not
    bootstrap, no subscription): the fingerprint cannot see it, the ticket's
    mechanical definition excludes it, and it STAYS. The SELECT printout (47)
    governs; the byte-exact fingerprint read is the oracle.
  ruling: >-
    The 946/947 disposition ruling is already recorded in Context and
    executed in frontmatter (M1-946 blocked_by=[M1-945, M1-951] pending;
    M1-947 abandoned superseded) — verified at start; only the record note
    naming it remains.
escalation_reason:
---

# M1-951: Tech-leg disposition: drift restore, 946/947, caveat

## Context

Two forced decisions on the tech leg, plus one missing statement. (1) The
frozen tech world DRIFTED — an external collector boot ingested 48 posts
(47 arXiv + 1 BBC) beyond the frozen `max_ready_at` — so the labels' pin no
longer matches the DB, every campaign owner-run delta gate is unrunnable,
and M1-946 cannot label. (2) M1-946/947 were authored as a single-world
widening + re-baseline; under the two-world re-anchor their disposition is
forced (expected abort or re-scope, binding brief constraint 3). (3) The
baseline record still presents tech-world numbers without the
representativeness caveat, and the golden set's demotion to tech-instance
regression suite is stated nowhere. This ticket restores the world (or
records the user's alternative ruling), records the caveat + demotion, and
executes the 946/947 disposition. Shared analysis: `analysis_ref:`.

**Ruling recorded (2026-08-28, user):** restore fork + the RECOMMENDED
disposition (keep M1-946 re-gated on this ticket; abort M1-947 as
superseded by M1-952) — the ticket-state half executed in frontmatter the
same day (M1-946 blocked_by += M1-951; M1-947 abandoned); the restore,
caveat section, and note remain this ticket's implementation.

## Root cause

Verified: every golden record pins the frozen fingerprint (grep over
golden-set.jsonl: 77/77 lines); the runner refuses on label-fingerprint
mismatch (RetrievalEvalRunnerIT.java:296-302) — so drift makes the whole
instrument unrunnable, by design. The drift itself is brief-given operator
state (not verifiable from a fresh checkout); the restore SQL re-derives the
set mechanically (world READY posts with `ready_at` beyond the frozen max),
so the ticket is safe to start regardless of the observation's precision:
the SELECT printout IS the re-derivation, and the fingerprint read is the
proof either way. The record's missing caveat is verified (grep returns 0).
M1-946 is `pending` runnable; M1-947 `pending` blocked by 946 (STATUS-TICK
2026-08-28) — both re-scopable via the driver's refine/abandon paths, no
review rounds spent.

## Pitfalls

Numbered per the analysis document; this ticket carries P13, P12-adjacent
(record discipline), P15-adjacent (the pin must come back BYTE-EXACTLY or
not at all), P17 (sequencing: the restore unblocks M1-946 and every future
tech-leg owner-run delta).

- P13: drift-restore exactness — the delete must reproduce the frozen
  fingerprint byte-exactly; partial restore (missed posts, dangling rows,
  over-deletion) surfaces as the runner's refusal = STOP, fallback to the
  user's ruling (re-label vs accept-drifted), never route-around. Volume
  snapshot first (the lane's standing cheap-insurance constraint).
- P12-adjacent: the appended note is corrections-visible and append-only;
  the two landed Results sections and the pre-registered rules stay
  byte-identical.
- P15-adjacent: the campaign's gating reference (M1-944's section) becomes
  RUNNABLE again only because the pin returns exactly — the whole M1-930..945
  investment rides on byte-exactness.
- P17: sequencing — this ticket gates M1-946 (labels bind the pin) and every
  tech-leg delta run; it is independent of the fam side (M1-948 ∥).

## Approach

- **Files to touch** — `files_scope`: the restore SQL and the record's
  appended section (plus operator-local snapshot + run notes).
- **Steps in implementation order:**
  1. Snapshot the `infochat-test_infochat-pgdata` volume; record the
     artifact (P13).
  2. Author `scripts/tech-drift-restore.sql`: the review SELECT (drift set
     printout), the transactional DELETE (posts + dangling derivatives),
     the fingerprint SELECT (P13).
  3. Operator run: SELECT-first review (expected 48 — re-derived, not
     trusted), DELETE, fingerprint read; a post-restore M1-929 smoke with
     `label_fingerprint_match` true (P13, P15-adjacent).
  4. Append the dated record section: representativeness caveat, demotion,
     drift disposition with the fingerprint proof (P12-adjacent).
  5. Execute the M1-946/947 disposition per the recorded ruling (recommended
     fork in acceptance item 5) via the driver; name it in the note.
  6. `mvn verify` green (scripts/ is in the diff, so the suite runs); diff
     fence.
- **Controls to preserve (§10):** the runner's refusal posture is the fence
  this ticket ANSWERS, not weakens — no harness line changes; the record's
  append-only shape; the golden set untouched.
- **Pitfall→mitigation:** P13→steps 1-3 (snapshot, SELECT-first, byte-exact
  read, smoke oracle, stop-not-route-around); P12-adjacent→step 4 pure
  additions; P15-adjacent→step 3 smoke; P17→step 5 ordering note to the
  driver (M1-946 blocked_by gains M1-951).

## Definition of done

The volume snapshot exists and is recorded; the restore SQL's SELECT
printout matches the mechanical drift set; the post-delete fingerprint read
is byte-exact the frozen pin (or the alternative ruling is recorded and
executed instead); an M1-929 smoke passes with `label_fingerprint_match`
true; the record carries the appended caveat/demotion/disposition section
with pure additions; the M1-946/947 driver state matches the recorded
ruling; `mvn verify` is green; the diff touches nothing outside
`files_scope`.

## Verification

- P13 → the ticket notes restate: snapshot artifact, the SELECT printout
  (count + composition), the post-delete fingerprint read, the smoke's
  `label_fingerprint_match: true`; FAILURE-MODE posture: any read that
  differs from the pin is a recorded refusal and the fallback ruling governs
  — a run scored across drift fails this item.
- P12-adjacent → git diff over the record: pure additions; the `'^## '`
  count grows by exactly one.
- P15-adjacent → the smoke oracle (label_fingerprint_match true) — the
  campaign's gating reference is runnable again.
- P17 → the disposition note + driver state match; M1-946's blocked_by
  includes M1-951 under the recommended fork.
- acceptance items → the named probes (greps over the record, git diff
  shapes, ticket-notes restatements, driver frontmatter state).

## Out-of-scope

Named in `out_of_scope`: re-labeling/extending the tech set (opens only via
the fallback ruling, as its own supersedes ticket); M1-946's rows; M1-947's
section (superseded by M1-952's tech leg; the redirect note is M1-952's);
the fam leg; any production/spec change; the frozen stack's non-drift
state. The record IS modified — authorized in `test_plan.modifies`: one
appended dated section, every existing line byte-identical
(engineering-rules §8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-951-tech-leg-disposition.md
```

## Run notes (operator, 2026-08-28)

- Pre-mutation snapshot (P13): stopped-stack tar of the
  `infochat-test_infochat-pgdata` volume at
  `/home/infochat/infochat-test/backups/pgdata-pre-M1-951-20260828T153405Z.tar.gz`
  (141 MB; postgres stopped for the tar, healthy immediately after).
- SELECT-first review printout: drift set = **47 posts, all
  `rss.arxiv.org/rss/cs.AI`**, ready_at 2026-08-27 23:44:03–23:54:23 UTC;
  world arithmetic 5214 + 47 = 5261 (the pre-restore read returned
  ready=5261;max_ready_at=2026-08-27 23:54:23.003542+00;uid_sha256=776e8c7c…).
  The brief's "48, 47 arXiv + 1 BBC" counts one READY post beyond the
  frozen max that is WORLD-INVISIBLE (BBC rss via a `source_origin=user`
  source, not bootstrap, zero subscriptions): outside the mechanical set,
  fingerprint cannot see it, left untouched (recorded in
  `clarity_check.start_repro`).
- Derivative delete counts, exactly as printed in review:
  post_reference 36, post_entity 67, post_embedding 47, quarantine 0,
  saved_post 0, summary_anchor 0, posts 47 — one transaction, COMMIT only
  after the in-transaction fingerprint assert passed.
- Post-delete fingerprint read (byte-exact the frozen pin):
  `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727`
- M1-929 operator smoke (RetrievalEvalRunnerIT, commit `04d56d45`,
  artifacts `.bench/retrieval-eval/results/20260828-171442/`):
  `label_fingerprint_match: true`, both passes' fingerprints equal the
  frozen pin, `translator_fallback_records: []`, BUILD SUCCESS.
- FAILURE-MODE posture honored: no refusal occurred; no route-around was
  needed (the first smoke attempt failed on an operator credential
  mistake — placeholder env-expression passed instead of the real
  password — not on a harness refusal; fixed and re-run).
