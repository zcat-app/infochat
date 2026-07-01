---
id: M1-538
title: "live-test: inject an adversarial RAW post so the real eval pipeline quarantines it"
status: draft
created: 2026-07-01
last_updated: 2026-07-01
blocked_by: []
files_budget: 4
files_scope:
  - prod/live-inject-adversarial.sh
  - prod/sql/inject-adversarial-raw.sql
  - docs/testing/USER_TEST_PLAN.md
complexity: medium
risk: low
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The READY terminal-state corpus (M1-537). That seed inserts ALREADY-EVALUATED
    rows for stable retrieval assertions and deliberately bypasses the eval
    pipeline; this ticket does the opposite — it injects at the pre-eval RAW stage
    precisely so the real Stage-1/Stage-2 + real LLM runs. The two compose.
  - >-
    The control-plane reset (M1-536) and the READY seed (M1-537). This ticket
    assumes a running, migrated deployment and adds only its own adversarial
    source + RAW post(s); it upserts its own source so it does not depend on
    M1-537 having run.
  - >-
    Chat-side (Provider) prompt injection — adversarial-input-kit §B (the [real-LLM]
    DM cases). This ticket is INGEST-side only (kit §A): post bodies entering the
    Collector eval pipeline. Provider-side is a distinct surface and follow-up.
  - >-
    Any change to the eval pipeline itself (Stage 1 scrub, Stage 2 judge, tagger,
    embedding, OutboxRehydrator, Stage1Worker). This is test tooling with NO
    src/main change. If the live run reveals a defense gap, that is a separate
    remediation ticket — never an inline fix here.
  - >-
    A movable application Clock. Re-evaluation is triggered by the real
    OutboxRehydrator (collector restart) or the real Stage1Worker stale-raw reaper
    (via a backdated status_changed_at), NOT by mocking the prod Clock (hardcoded
    Clock.systemUTC()).
  - "Flyway / schema changes — data inserts into the existing schema only."
  - >-
    Deterministic @QuarkusTest coverage of the injection path. The mocked-LLM
    suite already proves the deterministic half (kit §A has automated proxies);
    this ticket is the real-LLM live-run proof the mocked suite cannot give.
acceptance:
  - >-
    A prod-side script (prod/live-inject-adversarial.sh) inserts >=1 post at
    status='RAW' directly into a running deployment's database, with a body drawn
    from adversarial-input-kit §A (at least the A1 prompt-injection case), a
    deterministic uid (m1-538- prefix), and a valid source_id — the script upserts
    its own dedicated adversarial source by (kind,identifier) so it is
    self-contained (does not depend on the M1-537 seed source).
  - >-
    The inserted RAW row's status_changed_at is backdated beyond the configured
    stale-raw age (infochat.eval.stale-raw.age, default 30m) so the real
    Stage1Worker.reEmitStaleRaw() reaper re-enqueues it into the eval-queue without
    a restart; the script documents the collector-restart fast path (OutboxRehydrator,
    immediate pickup of all RAW rows) as the alternative trigger.
  - >-
    After triggering evaluation and a bounded, polled wait, a documented check
    (query in the script) shows the adversarial post reached a non-READY terminal
    state (QUARANTINED per D22, or NEEDS_REVIEW if the LLM verdict is UNKNOWN) with
    a corresponding quarantine row, AND the verbatim injection payload does not
    appear in any READY/retrievable post body (Stage 1 redaction replaced it with
    [REDACTED:<id>]) — proving the real Stage-1/Stage-2 + LLM pipeline processed and
    contained it. The script exits non-zero if the post is still RAW after the wait
    or if the payload is retrievable.
  - >-
    The loader is idempotent: re-running upserts the adversarial source by
    (kind,identifier) and delete-then-inserts the RAW post(s) by uid, resetting them
    to RAW for re-evaluation, so a second run neither duplicates rows nor errors and
    exits 0.
  - >-
    Runs under the schema-owner / superuser DB role (post/source inserts are
    collector-owned tables; the provider role cannot INSERT them), obtained the same
    way setup.sh / live-reset.sh reach Postgres.
  - "prod/live-inject-adversarial.sh passes `bash -n`; the SQL is valid against the current schema."
  - >-
    USER_TEST_PLAN.md gains a §Adversarial RAW injection subsection (parallel to the
    M1-537 §Synthetic corpus seed) documenting the script, the trigger mechanism
    (reaper vs restart), and the expected quarantine outcome.
test_plan:
  adds:
    - prod/live-inject-adversarial.sh (the inject entry point + polled quarantine-verification query)
    - prod/sql/inject-adversarial-raw.sql (the adversarial source + RAW post(s), backdated status_changed_at, upsert-shaped)
  modifies:
    - docs/testing/USER_TEST_PLAN.md (add the §Adversarial RAW injection subsection)
  preserves:
    - all tests currently green on main (prod-side tooling; no src/main, no test change)
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/architecture.md §Pipelines
  - docs/testing/adversarial-input-kit.md §A. Ingest-side prompt injection
  - docs/plan/live-e2e/README.md §3. Data & reset strategy
decision_refs:
  - D20
  - D22
---

# M1-538: inject an adversarial RAW post so the real eval pipeline quarantines it

## Context

M1-537 seeds an ALREADY-EVALUATED READY corpus for stable retrieval assertions —
it deliberately bypasses the eval pipeline. The malicious-detection half of the
live-e2e data strategy (`docs/plan/live-e2e/README.md` §3) is the opposite:
"seed at the RAW / pre-eval stage so the real Stage-1/Stage-2 + real LLM pipeline
runs on them (inserting as READY bypasses the very thing under test)." This
ticket delivers that — a prod-side tool that injects an adversarial post
(`adversarial-input-kit.md` §A, the A1 prompt-injection case) at `status='RAW'`
into a running deployment and proves the real ingest defense (D20 layered Stage
1 + Stage 2; D22 Stage-2 verdict → quarantine) contains it. It is a `[real-LLM]`
test: the mocked-LLM suite cannot prove Stage 2's judge, which is the whole point
of the adversarial kit. It composes with M1-536 (reset) and M1-537 (benign READY
corpus) to complete the reset + benign + malicious live-run data set.

## Acceptance

See frontmatter. An idempotent, owner-role prod script inserts an adversarial RAW
post with a backdated `status_changed_at`, the real Stage1Worker reaper (or a
collector restart via OutboxRehydrator) re-enqueues it into the live eval
pipeline, and a polled check proves it reached a non-READY quarantined state with
the injection payload redacted and non-retrievable.

## Out-of-scope

See frontmatter. Not the READY seed (M1-537), not the reset (M1-536), not
chat-side injection (kit §B), not any eval-pipeline code change, not a movable
clock, not schema changes, not deterministic @QuarkusTest coverage.

## Notes

- **Trigger mechanism (verified 2026-07-01).** A row inserted directly at
  `status='RAW'` is NOT auto-enqueued (the normal enqueue happens inside
  `PostPersister`, not on a bare INSERT). Two real mechanisms re-enqueue it into
  the `eval-queue` → `Stage1Worker.onPostKey`:
  - `OutboxRehydrator` (`@Startup @Priority(300)`) scans `WHERE status='RAW'` and
    re-enqueues every RAW row on collector boot — immediate, restart-triggered.
  - `Stage1Worker.reEmitStaleRaw()` (`@Scheduled every 5m`) re-enqueues RAW rows
    with `status_changed_at < now() - infochat.eval.stale-raw.age` (default 30m).
    Backdating `status_changed_at` well beyond that age makes the reaper pick the
    row up within one poll interval WITHOUT a restart — the preferred default so
    the tool does not disrupt a running collector.
  There is NO NOTIFY path for eval enqueue. The rehydrator keys ONLY on
  `status='RAW'` (no `*_done` / `source_id` predicate), so the minimal valid RAW
  row is `(uid, source_id→existing source, title NOT NULL, status='RAW')`.
- **Real-LLM nondeterminism.** Stage 1 (deterministic scrub + redaction) is
  reproducible; Stage 2's verdict is the model's. D22 sends INJECTION / MALWARE /
  UNKNOWN all to quarantine, so the robust assertion is "post is NOT READY + a
  quarantine row exists + the raw payload is not retrievable," which holds for any
  of those verdicts. QUARANTINED is the expected primary outcome for A1.
- **Reuse, don't rebuild.** Mirror M1-537's `prod/live-seed.sh` +
  `prod/sql/seed-synthetic-corpus.sql` structure (owner-role `compose_psql`,
  parameterized psql vars, fixed UUIDs, delete-by-uid idempotency) so the two live
  tools stay consistent. `m1-538-` uid/identifier prefix isolates this from the
  real data-plane and from the M1-537 seed.
- **Payload check.** "Not retrievable" means the verbatim injection string (e.g.
  `grantAdmin`) must not appear in any READY post body returned by the
  deterministic retrieval path; the stored/redacted form shows `[REDACTED:<id>]`
  (kit §A1). Assert on the raw substring, not on the post's presence.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-538-*.md
```
