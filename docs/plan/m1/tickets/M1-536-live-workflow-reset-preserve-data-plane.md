---
id: M1-536
title: "live-test: workflow reset that clears the control-plane while preserving the fetched data-plane"
status: done
created: 2026-07-01
last_updated: 2026-07-01
clarity_check:
  date: 2026-07-01
  verdict: WARN
  warnings:
    - >-
      SECURITY-FLAG-CONSISTENT: the reset clears audit_log, which is append-only
      (schema.md invariant 10, D34); security_relevant: false was inconsistent
      with that. Resolved by setting security_relevant: true (see below).
  blockers: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 287
      removed: 8
redteam_findings: []
redteam_audits:
  - date: 2026-07-01
    verdict: CLEAN
    base: main
    head: m1/M1-536-live-workflow-reset-preserve-data-plane
    verdict_file: docs/plan/m1/redteam/M1-536-2026-07-01.md
    out_of_model_count: 2
    note: |
      In-progress audit of the branch tip before commit. CLEAN, no in-model
      findings. Two advisory out-of-model items: (1) no runtime interlock
      distinguishes a disposable live-test deployment from production before the
      audit_log wipe / all-admin delete — but reaching the script requires the
      DB owner password + host + docker, all trusted-operator surface the threat
      model scopes out; (2) TRUNCATE bypasses the append-only audit trigger,
      inherent to owner privilege and consistent with §DB roles. Neither is
      adversary-reachable; no follow-up ticket filed.
blocked_by: []
files_budget: 4
files_scope:
  - prod/live-reset.sh
  - prod/sql/reset-control-plane.sql
  - docs/testing/USER_TEST_PLAN.md
complexity: medium
risk: medium
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    setup.sh --reset (do_reset) — unchanged. That path is the FULL teardown
    (docker compose down + optional -v, incl. LLM services per M1-395); this
    ticket adds the COMPLEMENTARY preserve-data reset for fast live-run
    iteration. The two are distinct entry points; do not fold one into the other.
  - >-
    Adapter identity data-dirs (SimpleX queue keypair, signal-cli account) — never
    touched. Wiping them re-issues the bot address / is unrecoverable for Signal;
    a control-plane DB reset already yields "from scratch" app state without it.
  - >-
    LLM backend containers (ollama/llamacpp) — not this script's concern (that is
    setup.sh --reset, M1-395).
  - >-
    Flyway / schema migrations — the reset mutates DATA only (truncate/delete
    rows), never DDL. It runs against the already-migrated schema.
  - >-
    A cross-environment data-plane snapshot (pg_dump/restore). The in-place reset
    leaves the fetched posts in the live DB by construction, so no snapshot is
    needed for same-host iteration. Portability export is a separate follow-up.
  - >-
    The synthetic/deterministic seed corpus load — that is M1-537. This ticket
    only PRESERVES whatever data-plane rows already exist; it does not seed.
acceptance:
  - >-
    A prod-side script (prod/live-reset.sh) performs a "workflow reset" against a
    running deployment's database: it removes ALL control-plane rows (users,
    groups, group_membership, invite_code, invite_code_attempt,
    source_subscription, scope_tag, scope_preferences, chat_session,
    chat_message, chat_memory, summary_anchor, summary_cache, saved_post,
    audit_log, quarantine, admin_notification_state, provider_state,
    auto_joined_group) while leaving ALL data-plane rows intact (source, tag,
    post + partitions, post_embedding, post_entity, post_reference,
    price_snapshot, asset_config, bootstrap_meta, embedding_metadata).
  - >-
    The reset is FK-safe against the two cross-plane FKs into users
    (source.added_by/deleted_by ON DELETE SET NULL; tag.created_by RESTRICT) and
    MUST NOT cascade into or otherwise delete any data-plane row. A naive
    `TRUNCATE users CASCADE` is explicitly forbidden (it cascades on the FK graph
    and would truncate source/tag/post). The chosen mechanism (in-place DELETE
    with last-admin triggers handled + tag.created_by nulled, vs. any equivalent)
    is a design choice for the plan pass, but the data-plane-preservation and
    FK-safety properties are the pinned acceptance.
  - >-
    Runs under the schema-owner / superuser DB role (provider/collector roles
    lack the needed DELETE/TRUNCATE grants); the script obtains that connection
    the same way setup.sh reaches Postgres (docker compose exec).
  - >-
    A verification step in the script asserts, after the reset, that every
    control-plane table has row count 0 and that the total data-plane post count
    is unchanged from before the reset (captured pre/post); the script exits
    non-zero if either check fails.
  - >-
    Post-conditions provable by a follow-up manual/harness run (documented, not
    necessarily automated here): after reset + Provider restart the bootstrap
    admin re-seeds; the SimpleX admin-token re-arms (the is_admin row is gone, so
    the WHERE NOT EXISTS claim gate re-opens); a fresh invite→register cycle
    works; and a pre-existing preserved post is returned by /summary for a
    newly-subscribed user (data-plane survived).
  - >-
    Idempotent: running the script twice in a row leaves identical state (second
    run is a no-op on already-empty control-plane tables) and exits 0 both times.
  - "prod/live-reset.sh passes `bash -n`; the SQL is valid against the current schema."
  - >-
    USER_TEST_PLAN.md gains a short subsection pointing at this script as the
    live-iteration reset (distinct from setup.sh --reset full teardown).
test_plan:
  adds:
    - prod/live-reset.sh (the reset entry point + pre/post verification)
    - prod/sql/reset-control-plane.sql (the FK-safe data-only reset)
  modifies:
    - docs/testing/USER_TEST_PLAN.md (one subsection describing the live-iteration reset)
  preserves:
    - all tests currently green on main (this is prod-side tooling; no src/main, no test change)
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/testing/USER_TEST_PLAN.md §The automation boundary
decision_refs:
  - D34
  - D44
  - D50
---

# M1-536: workflow reset that preserves the fetched data-plane

## Context

The live-run verification effort (`docs/plan/live-e2e/README.md`) iterates the
chat-app workflow against real transports many times. Re-fetching real feeds on
every iteration is slow, non-deterministic, and impolite to public endpoints, so
the loop must **reset only the control-plane** (user/group/invite/chat state)
while **preserving the data-plane** (the already-fetched, already-evaluated
posts + embeddings). `setup.sh --reset` is the wrong tool: it is a full teardown
(`docker compose down`, and per M1-395 also the LLM services), which nukes the
data-plane and the containers.

**The cross-plane FK trap (verified 2026-07-01, `live-e2e/README.md` §3/§7).**
Two data-plane tables reference `users`: `source.{added_by,deleted_by}` (ON
DELETE SET NULL) and `tag.created_by` (RESTRICT). Because `TRUNCATE … CASCADE`
cascades on the FK-constraint graph (not on row values), `TRUNCATE users CASCADE`
would truncate `source` and `tag`, and via `post.source_id` the entire `post`
table — destroying the corpus we are trying to preserve. Nulling the columns
first does not change this (TRUNCATE ignores row values). The reset must
therefore avoid CASCADE into the data-plane — e.g. delete control-plane children
first, then `DELETE FROM users` (which honours `source`'s SET NULL and needs
`tag.created_by` pre-nulled) with the last-admin protection triggers
(`trg_users_last_admin_{delete,update}`) temporarily disabled. `audit_log`'s
append-only guard is row-level and does not fire on TRUNCATE.

The reset composes cleanly with bootstrap: with the `users` rows gone, the
Provider's `AdminBootstrap` re-seeds the configured admin, and the SimpleX
admin-token re-arms (its single-use gate is the presence of a
`(simplex, is_admin=TRUE)` row — D50).

## Acceptance

See frontmatter. A prod-side script clears the control-plane, provably preserves
the data-plane (FK-safe, no CASCADE into posts), runs under the owner role,
self-verifies row counts, and is idempotent. Mechanism (in-place DELETE vs.
equivalent) is a plan-pass design choice; the preservation + FK-safety
properties are pinned.

## Out-of-scope

See frontmatter. Not the full teardown (setup.sh --reset), not adapter
data-dirs, not LLM containers, not schema/Flyway, not the synthetic seed corpus
(M1-537), not a cross-host snapshot.

## Notes

- **Mechanism is a design decision for the plan pass** (mirrors M1-163's seam
  choice). Option A (in-place DELETE, owner role, triggers disabled around the
  users delete) keeps the fetched posts in the live DB with no restart and no
  re-migrate — fastest for the loop. Option B (drop-recreate + `pg_dump
  --data-only` restore with cross-plane FK columns NULLed) is more robust but
  needs a Collector restart to re-run Flyway; if chosen, the snapshot is the
  portability artifact excluded above. Prefer A unless the plan finds A fragile.
- Full FK graph + per-table plane classification live in
  `docs/plan/live-e2e/README.md` §3 and §7 — do not re-derive; reconcile against
  the live schema (V1..V56) at implementation time.
- The reset intentionally clears `audit_log` for a clean test slate; note in
  USER_TEST_PLAN that this is a TEST-loop action and never a production
  procedure (audit_log is append-only in prod, D34).
- **`security_relevant: true` is deliberate** (clarity WARN 2026-07-01): the
  script overrides the append-only `audit_log` invariant (D34) and disables the
  last-admin protection triggers around `DELETE FROM users`. Both are safe only
  because this is prod-side *test-loop* tooling against a disposable live
  deployment, never a production path — exactly the surface a `/redteam` pass
  should confirm before merge.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-536-*.md
```
