# Session handoff — Tier 2 Group G: quarantine (admin commands + re-evaluation job + NEEDS_REVIEW + admin notification wiring)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-G ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- M1 is functionally complete: 82 done, 6 deferred, 0 pending.
  All Tier 2 sub-groups (T2-A through T2-H) have their M1
  implementation work done EXCEPT T2-F (groups) and T2-G (this
  session).
- T2-G's infrastructure ticket M1-058 (ThrottledAdminNotifier +
  admin_notification_state) is done and merged on main.
- STATUS.md: pending=0, in-progress=0, done=82, deferred=6, total=88.
- Branch is main, otherwise clean.
- Last allocated ticket ID: M1-068. Next free: M1-069.
- Last Flyway migration: V19__summary_anchor.sql. Next free: V20.
- Deferred tickets NOT in T2-G's path: M1-019, M1-020, M1-021,
  M1-031, M1-034, M1-042.

## What T2-G creates

T2-G lands the quarantine admin workflow and re-evaluation pipeline:

1. **Admin quarantine commands** — `/quarantine list [--all]`,
   `/quarantine approve <id>`, `/quarantine reject <id>`. The latter
   two invoke stored procedures under Provider EXECUTE grant. Also
   `/audit` (reads `audit_log_view`).

2. **Stored procedures** — `approve_quarantine(quarantine_id, actor_id)`
   and `reject_quarantine(quarantine_id, actor_id)` in a new Flyway
   migration + Provider EXECUTE grants.

3. **Re-evaluation job** — periodic Collector-side job that re-submits
   `stage2_failed=true` posts and UNKNOWN-verdict posts with separate
   attempt caps. Exhaustion transitions to `NEEDS_REVIEW`.

4. **`NEEDS_REVIEW` post status** — new enum value in `post.status`;
   the quarantine_review NOTIFY channel with tagged payload
   `(target_kind, target_id, new_status)`.

5. **`quarantine_review` NOTIFY channel** — Provider listener consumes
   tagged payload, drives throttled admin notifier on PENDING and
   NEEDS_REVIEW transitions, advances cursor for terminal transitions.

6. **Per-source UNKNOWN auto-disable** — source whose UNKNOWN rate
   exceeds threshold → `source.status = 'failed'` + admin notification.

7. **Tagger partial-valid handling** — a subset of the tagger's output
   is valid → accept the valid tags, don't fall back to bootstrap_tags.

8. **Embedding model identity guard** — mismatch with stored singleton
   row refuses startup.

9. **ThrottledAdminNotifier wiring** — connect existing infrastructure
   (M1-058) to the quarantine, re-eval, and tagger failure paths.

## Key seams in the current code

### Quarantine schema (V10)

Location: `infochat-core/src/main/resources/db/migration/V10__quarantine.sql`

- Table: `quarantine (id, post_id, post_uid, post_fetched_at, flagged_at,
  flagged_by, rule_id, span_start, span_end, original_html, placeholder_id,
  status, updated_at, reviewed_by, review_note)`
- Status enum: `PENDING, BENIGN_CLOSED, APPROVED, REJECTED` ✓
- View: `quarantine_review_view` (all columns EXCEPT `original_html`)
- Provider grants: SELECT on view only (no direct table access)
- Collector grants: SELECT, INSERT, UPDATE on table
- Stored procedures: **INTENTIONALLY ABSENT** — V10 comment says they
  land with T2-G alongside the admin commands.

### ThrottledAdminNotifier (M1-058, done)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifier.java`

- `@ApplicationScoped` CDI bean
- Signature: `notifyOnce(String key, String errorClass, String message) → NotifyOutcome {EMITTED|SUPPRESSED}`
- Backed by V16 `admin_notification_state` table
- Race-safe: UPSERT with conditional WHERE on throttle window
- Collector writes; Provider reads (future admin commands may surface counters)

### Stage 2 judge (existing, M1-033)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/`

- `Stage2Judge` invokes the LLM security judge
- Returns verdicts: BENIGN, INJECTION, MALWARE, UNKNOWN
- On BENIGN: quarantine row → BENIGN_CLOSED; post stays READY with redactions
- On INJECTION/MALWARE: post → QUARANTINED; quarantine row stays PENDING
- On UNKNOWN: post → QUARANTINED; quarantine row stays PENDING
- **No re-eval exists** — a post stuck in UNKNOWN stays there until T2-G

### Tagger (existing, M1-034a)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/`

- On retry exhaustion or zero-valid output → falls back to
  `source.bootstrap_tags` with `post.tagger_fallback = true`
- Logs canonical `error_class` strings for future notifier pickup
- **Partial-valid handling is NOT yet implemented** — current code
  treats partial output the same as zero-valid (full fallback).
  T2-G implements: accept the valid subset, don't fall back.

### Embedding (existing, M1-034b)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/`

- On failure: release post without vector; queries filter
  `WHERE embedding IS NOT NULL`
- **Model identity guard not yet implemented** — T2-G adds the
  singleton metadata row check at startup.

### Post status enum

- Current values in code/schema: `RAW`, `READY`, `QUARANTINED`
- `NEEDS_REVIEW` does NOT exist yet — T2-G adds it via migration.

### Provider state (V9)

Location: `infochat-core/src/main/resources/db/migration/V9__provider_state.sql`

- Currently has one channel: `new_post`
- T2-G adds: `quarantine_review` channel row with compound cursor
  `(reviewed_at, target_kind, target_id)` and CAS UPDATE
- Provider listener infrastructure exists from M1 (`NewPostListener`,
  `NewPostReconciler`) — T2-G adds a parallel
  `QuarantineReviewListener`.

### InboundRouter admin command dispatch

- Admin commands exist: `/grant-admin`, `/revoke-admin`, `/ban`,
  `/unban`, `/vouch`, `/invite create|list|revoke`
- Pattern: each is a `CommandHandler` bean discovered by CDI;
  permission check inside the handler checks `user.is_admin`.
- T2-G's `/quarantine list|approve|reject` and `/audit` follow the
  same pattern.

### QuarantineDao (Collector side)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java`

- Handles Stage 1 inserts only
- T2-G's approve/reject goes through stored procedures called by
  Provider — a separate code path from Collector writes.

## Spec sections T2-G cites

- `docs/spec/security.md` §Quarantine workflow (line 687)
- `docs/spec/security.md` §Re-evaluation job (line 830)
- `docs/spec/security.md` §Failure handling — Stage 2 infra-failure
- `docs/spec/security.md` §Failure handling — Tagger
- `docs/spec/security.md` §Re-evaluation job — Per-source UNKNOWN auto-disable
- `docs/spec/commands.md` §Admin — `/quarantine list [--all]`
- `docs/spec/commands.md` §Admin — `/quarantine approve`
- `docs/spec/commands.md` §Admin — `/quarantine reject`
- `docs/spec/commands.md` §Admin — `/audit`
- `docs/spec/schema.md` §Posts and derivatives — NEEDS_REVIEW
- `docs/spec/schema.md` §Invariants — 6 (admin-review TTL auto-reject)
- `docs/spec/architecture.md` §Inter-service communication —
  `quarantine_review` channel
- `docs/spec/llm.md` §Failure handling — Tagger partial-valid
- `docs/spec/llm.md` §Embedding pipeline — Model identity guard
- `docs/spec/verification.md` §Re-evaluation job
- `docs/spec/verification.md` §UNKNOWN re-eval
- `docs/spec/verification.md` §Admin-review TTL auto-reject
- `docs/spec/verification.md` §Tagger partial-valid output

## Implementation plan mapping

T2-G maps to **Milestone 2** (Full eval pipeline + LISTEN/NOTIFY
breadth) in the implementation plan (§4 of
`docs/plan/implementation-plan.md`). Read that milestone section for
full acceptance criteria (A1–A11) and G/W/T scenarios — they are
the source of truth for T2-G's acceptance items.

Key acceptance items from M2:
- A1: Re-evaluation job (infra-failure + UNKNOWN separate caps)
- A2: Per-source UNKNOWN auto-disable
- A3: Absolute NEEDS_REVIEW depth alert
- A4: quarantine_review channel with tagged payload
- A5: Admin quarantine commands (approve/reject invoke stored procs)
- A6: /audit reads redacted view
- A7: Cross-source linking (D6) — may be separate from T2-G if too large
- A8: Tagger partial-valid handling
- A9: Embedding model identity guard
- A10: Local-only routing conflict (already done in M1)
- A11: Admin command surface (/ban, /unban, etc. — already done in T2-A)

Note: M2 A7 (cross-source linking) and A11 (admin commands already
done) are NOT T2-G's scope. A10 (local-only conflict) was also done
in M1. T2-G focuses on A1–A6, A8, A9.

## Recommended ticket split

Per session-grouping-plan.md, T2-G is estimated at 1 ticket. However
the scope spans Collector-side re-eval + Provider-side admin commands +
new migration + NOTIFY wiring. Evaluate file count at authoring time:

  **T2-G.1** — If single-ticket viable (~12 files):
  - V20 migration: stored procedures + EXECUTE grants +
    NEEDS_REVIEW enum value + provider_state row for
    quarantine_review channel
  - Re-evaluation job (Collector): periodic, separate caps for
    infra-failure and UNKNOWN classes, per-post attempt counter,
    NEEDS_REVIEW transition, per-source UNKNOWN auto-disable
  - quarantine_review NOTIFY emit (Collector)
  - QuarantineReviewListener (Provider): consumes tagged payload,
    drives throttled notifier, advances cursor
  - /quarantine list, /quarantine approve, /quarantine reject handlers
  - /audit handler (reads audit_log_view)
  - Tagger partial-valid fix
  - Embedding model identity guard at startup
  - ThrottledAdminNotifier wiring for all paths
  - Admin-review TTL auto-reject scheduled job

  **If file count exceeds 12**, split along Collector/Provider seam:
  - T2-G.1a: V20 migration + re-eval job + NOTIFY emit +
    tagger partial-valid + embedding identity guard + per-source
    auto-disable + admin-review TTL job (Collector side)
  - T2-G.1b: quarantine_review listener + admin quarantine commands +
    /audit + ThrottledAdminNotifier wiring into Provider (Provider side)

  Likely file count for single-ticket approach:
  - V20 migration (1)
  - ReEvaluationJob (1)
  - QuarantineNotifier (Collector NOTIFY emit) (1)
  - QuarantineReviewListener (Provider) (1)
  - AdminReviewTtlJob (1)
  - PerSourceUnknownTracker (1)
  - QuarantineListCommandHandler (1)
  - QuarantineApproveCommandHandler (1)
  - QuarantineRejectCommandHandler (1)
  - AuditCommandHandler (1)
  - TaggerPipeline fix (existing file edit, not new)
  - EmbeddingProvider startup guard (existing file edit)
  - Bundle keys addition (existing file edit)
  - ThrottledAdminNotifier call sites (existing file edits)
  - 4+ test files
  ≈ 14-18 files → likely needs umbrella+subs

## Existing tests to not break

- `Stage2JudgeTest` — tests BENIGN/INJECTION/MALWARE/UNKNOWN verdicts
- `Stage1WatchdogIT` — tests regex timeout → QUARANTINED (50ms cap,
  known marginal — see memory `project_stage1watchdogit_flake.md`)
- `TaggerPipelineTest` — tests fallback to bootstrap_tags
- `EmbeddingWorkerTest` — tests release without vector on failure
- `QuarantineDaoTest` — tests Stage 1 inserts
- All handlers that currently short-circuit group scope — their tests
  assert the short-circuit; T2-G does NOT change those (T2-F does)

## Dependencies and ordering

- T2-G does NOT depend on T2-F (groups). They are independent work
  streams. T2-G's admin commands run from DM (bot admin); group
  scope is not required.
- T2-G depends on M1-058 (ThrottledAdminNotifier) — already done.
- T2-G depends on M1-033 (Stage 2 judge) — already done.
- T2-G depends on M1-034a (Tagger pipeline) — already done.
- T2-G depends on M1-034b (Embedding + READY promoter) — already done.
- The quarantine_review NOTIFY channel is consumed by Provider; it
  does NOT require group support (Provider listener fires admin
  notifications regardless of whether groups exist).

## Design-vs-spec drift notes

1. The implementation plan §Milestone 2 includes "cross-source linking
   (D6)" under A7. This is a separate feature (entity extraction +
   pgvector cosine similarity + `post_reference` INSERTs + `last_linked_at`
   cursor). It does NOT belong in T2-G's quarantine scope. If the
   session-grouping-plan intended it for T2-G, explicitly exclude it
   in the ticket's out_of_scope and note that it's a standalone future
   ticket.

2. M2 A11 lists `/ban`, `/unban`, `/grant-admin`, `/revoke-admin`,
   `/vouch`, `/invite list`, `/invite revoke`. These are ALL done in
   T2-A (M1-044/M1-045/M1-046). T2-G does NOT re-implement them.

3. The `NEEDS_REVIEW` post status does not exist in current schema.
   The migration must ALTER the status CHECK constraint on `post` to
   add `'NEEDS_REVIEW'`. Verify the current constraint shape at
   authoring time.

4. `quarantine_review` NOTIFY emit — the Collector currently emits
   `new_post` only. Adding a second NOTIFY channel is a new code path
   in the eval pipeline's transition logic.

5. Admin-review TTL auto-reject (Invariant 6): `PENDING` rows older
   than the TTL transition to `REJECTED`; `BENIGN_CLOSED` rows are
   NOT affected. This is a scheduled Collector-side job. The TTL value
   is profile-driven.

6. Absolute NEEDS_REVIEW depth alert (spec §Re-evaluation job): fires
   when the total NEEDS_REVIEW queue exceeds the profile-driven cap,
   independent of per-source ratio. Single throttled notification.

## Verify at authoring time (do not trust brief's values if main moved)

  - Next free ticket ID: `ls docs/plan/m1/tickets/ | sort -V | tail`
  - Next free Flyway integer: `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail`
  - Quarantine table constraint shape: `grep -n "status\|CHECK" infochat-core/src/main/resources/db/migration/V10__quarantine.sql`
  - Post status enum/constraint: `grep -n "status" infochat-core/src/main/resources/db/migration/V7__joins_post.sql`
  - provider_state existing channels: `grep -n "INSERT\|channel" infochat-core/src/main/resources/db/migration/V9__provider_state.sql`
  - ThrottledAdminNotifier location: `find . -name "ThrottledAdminNotifier.java" -path "*/main/*"`
  - Stage2Judge location: `find . -name "Stage2*" -path "*/main/*"`
  - TaggerPipeline location: `find . -name "Tagger*" -path "*/main/*"`
  - EmbeddingWorker location: `find . -name "Embedding*" -path "*/main/*"`
  - Existing admin handler pattern: `find . -name "*AdminCommandHandler.java" -path "*/main/*"`
  - NewPostListener pattern: `find . -name "NewPost*" -path "*/main/*"`

## Task

Author the T2-G ticket files in `docs/plan/m1/tickets/`. Follow
the ticket template at `docs/process/ticket-template.md`. Each
ticket must have correct frontmatter (id, title, status: pending,
complexity, risk, spec_refs, files_budget, files_scope, out_of_scope,
blocked_by, acceptance). Use the M1-044 umbrella pattern if file
counts exceed 12.

After authoring, run `scripts/lint-ticket.py` on each new ticket
file and fix any errors. Do NOT run `/m1-tick start` — only author.
```
