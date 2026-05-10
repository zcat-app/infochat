# Session handoff — Tier 0 Group 1: DB roles + advisory lock

Paste the body below into a fresh Claude Code session as the opening
message. The session will author two ticket files and stop. Do NOT
include this preamble paragraph when pasting — only the fenced block
that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- M1-001, M1-002, M1-003 done and merged on main.
- Process patches landed on main (commit 2daa4d6): umbrella + subticket
  idiom in /m1-tick skill, workflow.md, /redteam skill, ticket-template.md.
- M1-004 and M1-005 ticket files exist as UNTRACKED drafts on main
  (status: pending). They are correctly untracked per the M1 workflow —
  they ride through the per-ticket commit at /m1-tick start time. DO NOT
  pre-commit them.
- Branch is main, otherwise clean.

## What you do this session

Author exactly two ticket files in docs/plan/m1/tickets/:
  M1-006 — DB role matrix (collector, provider, admin)
  M1-009 — Advisory-lock single-instance enforcement + heartbeat

These two share heavy context: docs/spec/security.md §DB roles + §Trust
boundaries, the Flyway migration mechanics established by M1-005, and
both carry `migration_touch: true` + `security_relevant: true`
frontmatter. That shared context is what makes them a natural pair —
loading the security.md sections once benefits both tickets, and the
two-file boundary keeps the session within a clean context window.

When you finish, leave the new files UNTRACKED on main (workflow rule:
drafts ride untracked through /m1-tick start). Do NOT commit them.

## The 5-step macro plan (you are in step 4)

  1. ✓ done: 1 calibration ticket (M1-003)
  2. ✓ done: skeleton-pass enumeration (~47 tickets across 3 tiers + adapters)
  3. ✓ done: dependency graph rendered, no cycles
  4. ⬅ Tier 0 ticket files in flight; THIS SESSION lands 2 of the 6 remaining
       (M1-006, M1-009). A separate session lands the SPI group
       (M1-007 umbrella + M1-007a/b/c). Total Tier 0 = 8 tickets;
       4 currently authored after this session.
  5. (later) /m1-tick start invocations with user-driven escalation.

## Locked decisions for the two tickets

All IDs and structural choices are LOCKED. Don't re-debate.

### M1-006 — DB role matrix
- blocked_by: [M1-005]
- complexity: medium, risk: medium
- security_relevant: TRUE   (touches security.md §DB roles invariants)
- migration_touch: TRUE     (adds Flyway V2 migration)
- round_cap: 2
- files_budget: ~4
- Scope:
  * Add Flyway V2 migration creating three Postgres roles per
    docs/spec/security.md §DB roles:
      - infochat_collector — INSERT/UPDATE on ingest-owned tables,
        SELECT on the rest, INSERT-only on audit_log, LISTEN/NOTIFY.
      - infochat_provider  — write on user-state, SELECT on
        collector-owned (incl. SELECT-only on price_snapshot and
        asset_config), SELECT on audit_log_view (not audit_log),
        INSERT-only on audit_log, EXECUTE on approve_quarantine /
        reject_quarantine stored procs, LISTEN/NOTIFY.
      - infochat_admin     — operator psql sessions; full rights.
  * Migration only creates the ROLES + does default DB-level grants.
    Per-table grants land in the migrations that create those tables
    (M1-008 schema umbrella subtickets and onward). M1-006 explicitly
    does NOT grant any specific table — there are none yet to grant on.
  * Out-of-scope MUST list: the heartbeat table (M1-009 creates it AND
    grants on it); audit_log_view + the stored procs (M1-008 schema
    work); any GRANTs on entity tables.
- Spec_refs (all verified to exist):
  * docs/spec/security.md §DB roles
  * docs/spec/security.md §Trust boundaries
  * docs/spec/architecture.md §Service split
- decision_refs: D34

### M1-009 — Advisory-lock + heartbeat
- blocked_by: [M1-005, M1-006]
- complexity: medium, risk: medium
- security_relevant: TRUE   (the lock is a structural integrity boundary
  preventing dual-instance corruption of LISTEN/NOTIFY + high-water-mark
  invariants; threat-actor should review)
- migration_touch: TRUE     (adds Flyway V3 migration)
- round_cap: 2
- files_budget: ~8
- Scope:
  * Flyway V3 migration creates the `heartbeat` table. Suggested columns
    per design/07-deployment.md §7.8.5: `service` (text PK; values
    'collector' | 'provider'), `host_id` (text — hostname or container
    id), `pid` (int), `last_seen_at` (timestamptz, default now()).
    Grants: SELECT + INSERT + UPDATE to BOTH infochat_collector and
    infochat_provider (each writes its own row but reads the other's
    to populate fatal-log messages).
  * @Startup beans in BOTH infochat-collector and infochat-provider:
      - Acquire pg_advisory_lock with name "infochat.collector" and
        "infochat.provider" respectively, int8-hashed via Postgres'
        hashtext() function. Use pg_try_advisory_lock so failure is
        a fast return, not a wait.
      - On lock acquisition failure: read the heartbeat row for this
        service, log fatal "cannot start: another infochat.<service>
        instance is running on host <host_id> (pid <pid>, last seen
        <last_seen_at>)", then call System.exit(1) — see Quarkus'
        Quarkus.asyncExit() for the proper shutdown path.
      - On success: upsert this instance's heartbeat row, then a
        Scheduled (@Scheduled) task UPDATEs last_seen_at every N
        seconds. N is profile-driven; for laptop use a small value
        like 5 seconds. Add the value as a placeholder in the
        %laptop. namespace established by M1-005.
  * Bean ordering: the advisory-lock bean must run AFTER Flyway
    has migrated (so V3 has created the heartbeat table) and BEFORE
    any business-logic startup beans. Quarkus' @Startup @Priority
    is the mechanism; design/01-architecture.md §1.4.3 has the
    canonical ordering.
- Spec_refs (all verified to exist):
  * docs/spec/architecture.md §Deployment topology (v1)
  * docs/design/01-architecture.md §1.4.3 Startup-bean ordering and
    single-instance enforcement
  * docs/design/07-deployment.md §7.8.5 Single-instance enforcement
    (pg_advisory_lock + heartbeat)
- decision_refs: D41

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by grep'ing for `^## ` and `^### ` headings in a
prior session. Any spec_ref you cite that ISN'T in this list, verify
the anchor exists by reading the cited file before using it. The
clarity-preflight subagent will FAIL the ticket if a spec_ref doesn't
resolve.

  docs/spec/security.md §DB roles (line 943)
  docs/spec/security.md §Trust boundaries (line 38)
  docs/spec/security.md §Threat model (line 12)
  docs/spec/security.md §Failure handling (line 724)
  docs/spec/architecture.md §Service split (line 12)
  docs/spec/architecture.md §Deployment topology (v1) (line 111)
  docs/spec/architecture.md §Architectural principles (line 334)
  docs/spec/deployment.md §Configuration surface (spec level) (line 255)
  docs/design/01-architecture.md §1.2 Module layout (Maven) (line 89)
  docs/design/01-architecture.md §1.4.3 Startup-bean ordering and
    single-instance enforcement (line 433)
  docs/design/07-deployment.md §7.4 Canonical application.properties (line 129)
  docs/design/07-deployment.md §7.8.5 Single-instance enforcement (line 644)

## Style requirements

Match M1-003, M1-004, M1-005 — those files in docs/plan/m1/tickets/
are your style exemplars. Read all three once before authoring.
Then read docs/process/ticket-template.md once for the canonical
schema. THEN write.

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep/test assertions, not prose.
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.
- Length per ticket: ~180-220 lines for these two (they have
  meaningful complexity in the migration and bean-ordering details).

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

Planning is paid for. Implementation-ticket authoring is narrow:
- DO read M1-003, M1-004, M1-005 once for style — they're in
  docs/plan/m1/tickets/.
- DO read docs/process/ticket-template.md once.
- DO read ONLY the spec_refs sections you actually cite, and only
  the lines you need.
- DO NOT spawn Explore subagent for spec walks — stay in main
  conversation.
- DO NOT pre-load the full docs/spec/ tree as background.

## After authoring both tickets

1. Verify both files parse as YAML+markdown (don't run a parser; just
   eyeball the frontmatter).
2. Print a one-paragraph summary: "M1-006 and M1-009 drafted as
   untracked files on main. They join M1-004 and M1-005 as the
   four Tier-0 ticket drafts authored so far. The SPI group
   (M1-007 umbrella + M1-007a/b/c) is the remaining Tier-0 batch,
   in a separate session per
   docs/plan/m1/drafts/handoff-tier0-group2-spi-surfaces.md."
3. STOP. Do NOT commit. Do NOT run /m1-tick start. Do NOT begin
   implementing. The user starts /m1-tick when ready.

## What you do NOT do

- Do NOT commit any ticket file. Drafts ride untracked per the M1
  workflow.
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT renumber. M1-006 and M1-009 are LOCKED IDs.
- Do NOT begin authoring M1-007 or its subtickets — those are the
  other session.
- Do NOT add tickets to the plan unless the user explicitly asks.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start (CLAUDE.md §M1 workflow).

## Your immediate task when the user says "go"

1. Read M1-003-quarkus-app-skeleton-and-first-test.md,
   M1-004-postgres-pgvector-dev-compose.md, and
   M1-005-profile-selector-flyway-infra.md once for style.
2. Read docs/process/ticket-template.md once.
3. Read docs/spec/security.md §DB roles + §Trust boundaries (one read
   covers both M1-006 and M1-009's authorization framing).
4. Read docs/design/07-deployment.md §7.8.5 once (for M1-009's
   advisory-lock + heartbeat shape).
5. Write M1-006-db-role-matrix-collector-provid.md (slug truncated
   from "DB role matrix (collector, provider, admin)" → 30 chars,
   trailing hyphen trimmed if any).
6. Write M1-009-advisory-lock-single-instance.md (slug from
   "Advisory-lock single-instance enforcement + heartbeat").
7. Print the summary, STOP.
```
