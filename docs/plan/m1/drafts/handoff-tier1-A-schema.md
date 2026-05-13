# Session handoff — Tier 1 Group A: MVP schema (umbrella + 3 subtickets)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author four ticket files and stop. Do NOT
include this preamble paragraph when pasting — only the fenced block
that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets are done and merged on main:
    M1-001..M1-005 (Phase 1 scaffolding)
    M1-006 + M1-009 (DB roles, advisory lock + heartbeat)
    M1-007 + M1-007a + M1-007b + M1-007c (SPI module umbrella + 3 subtickets)
  Plus 9 process tickets M1-010..M1-018 on the /m1-tick skill itself.
- M1-019 and M1-020 (post-MVP security hardening) are `status: deferred`
  with `deferred_reason: post-mvp-hardening` and empty `deferred_on`.
  Once T1-D's LLM ticket is authored, M1-019's deferred_on should be
  updated to point at it; once T1-E's messaging umbrella is authored,
  M1-020's deferred_on should be updated to point at it. Not your job
  in this session.
- Flyway migrations already live on disk under
  infochat-core/src/main/resources/db/migration/:
    V1__init.sql, V2__roles.sql, V3__heartbeat.sql, V4__nologin.sql.
  The next free version number is V5 (M1-008a's migration).
- infochat-core/src/test/java/ exists (M1-007a created it;
  IngestSpisLoadTest.java lives there). The MVP-schema integration
  test will live in that tree (see umbrella section below).
- Branch is main, otherwise clean.

## What you do this session

Author exactly four ticket files in docs/plan/m1/tickets/:
  M1-008  — MVP schema umbrella (per-(user, scope) isolation IT)
  M1-008a — Identity, audit, last-admin protection trigger (§2.1)
  M1-008b — Sources and tags catalogues (§2.2 base tables)
  M1-008c — Subscriptions, scope preferences, posts + cross-cutting
            isolation IT data fixtures (§2.2 join tables + §2.3)

These four share heavy context — docs/spec/schema.md §Identity and
access + §Sources and tags + §Posts and derivatives + §Per-user
state + §Per-scope state + §Invariants, and the per-section design
expansion in docs/design/02-schema.md §2.1 + §2.2 + §2.3. The
subtickets all add Flyway migrations under
infochat-core/src/main/resources/db/migration/, all add per-table
GRANTs for the infochat_collector / infochat_provider roles created
by M1-006, and all defer their cross-cutting integration test to
the M1-008 umbrella. Once you've authored M1-008a, the migration
shape for M1-008b and M1-008c is largely substitution-templated.
Author the umbrella M1-008 last in the SAME session so the IT path
matches what each subticket lists in out_of_scope.

When you finish, leave the four new files UNTRACKED on main
(workflow rule: drafts ride untracked through /m1-tick start).
Do NOT commit.

## Where you are in the milestone

Tier 0 (foundation) is complete. This session opens Tier 1 (MVP
vertical slice). Tier 1 has six groups:
  T1-A schema (this session — 4 tickets)
  T1-B ingest sources (bootstrap loader, RSS Fetcher)
  T1-C outbox/NOTIFY (outbox + LISTEN/NOTIFY + provider_state + rehydrator)
  T1-D eval pipeline (Stage 1, LLM + Stage 2, tagger + embedding)
  T1-E adapter + router (umbrella + InMemoryAdapter + router + /help)
  T1-F first commands (/add-source, /summary)

After T1-A, the next session authors T1-B's detailed handoff JIT.
See docs/plan/m1/drafts/session-grouping-plan.md for the full plan.

## Locked decisions for the four tickets

All IDs and structural choices are LOCKED. Don't re-debate.

The umbrella+subticket pattern is the same idiom M1-007 used. See
docs/process/workflow.md §Ticket-ID placeholder convention for the
"Umbrella + subticket idiom" paragraph. Read it once. Subtickets
MUST list the umbrella's integration test file in out_of_scope.

### Shared invariants across all four tickets

- The umbrella's integration test path is LOCKED:
    infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
  Each subticket lists this exact path in its out_of_scope.
  Rationale: the schema lives in infochat-core (M1-017 relocated
  the Flyway migrations there), the per-(user, scope) isolation
  invariant is a schema-level property, and the IT must exercise
  rows that span identity + sources + joins + posts — all in the
  same module. Provider-side or Collector-side placement would
  drag in extra runtime context unnecessarily.
- "No application code" is a HARD rule. M1-008a/b/c each add
  Flyway SQL migrations + per-table GRANTs + trigger bodies +
  minimal SQL-level tests of the trigger logic only. NO Java entity
  classes, NO repositories, NO services. Those land in later tickets
  (T1-B onward). The cross-cutting per-(user, scope) isolation IT
  is a @QuarkusTest in infochat-core that exercises raw JDBC + a
  TestContainers Postgres against the migrated schema — no entity
  layer is introduced.
- Per-table GRANTs land in the migration that creates each table,
  per M1-006's out-of-scope listing (M1-006 created the roles only,
  not the table grants). The grant policy follows
  docs/spec/security.md §DB roles:
    Identity/audit tables (§2.1): users, groups, group_membership,
      invite_code — Provider writes, Collector reads.
      audit_log: INSERT-only for both Collector and Provider; SELECT
      on audit_log_view for Provider; full rights for Admin only.
    Source/tag catalogues (§2.2.1, §2.2.2): Collector writes (bootstrap
      loader + /add-source on the Provider side both run as
      infochat_provider per D34, so writes belong to Provider; Collector
      reads to discover sources to poll). Verify against §DB roles
      before locking the GRANTs; the column says "Provider write" for
      source-list curation.
    Per-scope join tables (§2.2.3, §2.2.4, §2.2.5): Provider writes
      (user-state mutations), Collector reads where applicable.
    Posts (§2.3): Collector writes, Provider reads.
- The closed verb enum audit_log.action (§2.1.8) is part of M1-008a.
  Do not invent new verbs — the spec enumerates the v1 closed set;
  read it once and copy faithfully.
- M1-008a includes the last-admin protection trigger AND its
  serialization requirement (Invariant 2 in docs/spec/schema.md
  §Invariants: the trigger MUST serialize concurrent revocations
  via LOCK TABLE … IN SHARE ROW EXCLUSIVE MODE or SELECT … FOR
  UPDATE on admin rows). The trigger's SQL-level test exercises
  both single-tx revocation and concurrent revocation (two
  transactions racing to revoke the last two admins) and asserts
  exactly one transaction commits. This is non-trivial; budget
  files_budget accordingly.

### M1-008 — MVP schema umbrella (per-(user, scope) isolation IT)
- blocked_by: [M1-008a, M1-008b, M1-008c]
- complexity: low, risk: low
- security_relevant: TRUE   (Invariant 1 is the keystone of the
  authorization model — a cross-scope leak undoes /forget, /save,
  and chat-memory privacy guarantees in one shot)
- migration_touch: FALSE    (umbrella does no schema work; it just
  consumes the migrated tables)
- round_cap: 2
- files_budget: 2  (the IT class + at most one test-resources fixture)
- files_scope:
    - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
- Scope:
  * A @QuarkusTest IT that, against the TestContainers Postgres
    provisioned by infochat-core's existing test setup (Quarkus DevServices
    or a dedicated profile), seeds rows representing two users (A, B)
    in two scopes (DM, group:G), inserts scope-scoped rows in
    scope_preferences + scope_tag + source_subscription, and asserts:
      - SELECT against user A's DM scope returns ONLY A-in-DM rows.
      - SELECT against user A's group:G scope returns ONLY A-in-group:G rows.
      - SELECT against user B's DM scope returns ONLY B-in-DM rows.
      - The carve-out: saved_post rows for user A are visible to A
        regardless of scope (Invariant 1 carve-out — D13).
  * The IT does NOT add or modify any Flyway migration. It depends on
    M1-008a + M1-008b + M1-008c having migrated the schema.
  * Body explains WHY this is a separate commit: the umbrella idiom
    (workflow.md §Ticket-ID placeholder convention) — whole-topic
    verification of Invariant 1 is meaningfully different from any
    single subticket's per-table assertions, so it ships as its own
    reviewable unit.
- Spec_refs (all verified to exist):
  * docs/spec/schema.md §Invariants — Invariant 1 (per-(user, scope) isolation)
  * docs/spec/schema.md §Per-user state (scope-independent) — the D13 carve-out
  * docs/spec/security.md §Trust boundaries
- decision_refs: D13

### M1-008a — Identity, audit, last-admin trigger (§2.1)
- blocked_by: [M1-005, M1-006, M1-017]
- complexity: high, risk: high
- security_relevant: TRUE   (last-admin trigger is the only barrier
  against an "admin-empty deployment"; trigger correctness under
  concurrent revocation is in scope; threat-actor should review)
- migration_touch: TRUE     (adds Flyway V5 migration)
- round_cap: 3              (last-admin trigger + concurrent-revocation
  test justify the higher cap per CLAUDE.md §M1 workflow)
- files_budget: 8           (V5 migration + last-admin trigger function +
  audit_log_view DDL + per-table GRANTs + SQL trigger tests +
  the closed-verb enum + delete_preban_user stored proc)
- Scope:
  * Flyway V5 migration creating per docs/design/02-schema.md:
      - §2.1.1 users
      - §2.1.2 last-admin protection trigger (UPDATE path + DELETE path,
        with LOCK TABLE … IN SHARE ROW EXCLUSIVE MODE serialization
        per Invariant 2)
      - §2.1.3 groups
      - §2.1.4 group_membership (including the §2.1 partial unique
        index that enforces Invariant 3: at most one group admin per
        group)
      - §2.1.5 invite_code
      - §2.1.6 delete_preban_user stored procedure
      - §2.1.7 audit_log
      - §2.1.8 audit_log.action closed enum (copy verbatim from the
        spec; do not invent verbs)
      - §2.1.9 audit_log_view
  * Per-table GRANTs to infochat_collector / infochat_provider /
    infochat_admin per docs/spec/security.md §DB roles. audit_log
    is INSERT-only for Collector and Provider; SELECT on
    audit_log_view is Provider-only (no SELECT on audit_log itself).
  * SQL-level pgTAP-or-equivalent test of the last-admin trigger
    that covers: (i) single-tx revoke of the last admin → rejected;
    (ii) two concurrent transactions racing to revoke different
    admin rows when only two admins exist → exactly one commits.
    If pgTAP isn't already in the test stack, a plain JDBC test in
    infochat-core/src/test/ that opens two connections is acceptable.
- Out-of-scope MUST list:
    infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
    (the umbrella's cross-cutting IT — that ticket asserts the
    cross-scope invariant; this ticket asserts trigger correctness
    only)
- Spec_refs (all verified):
  * docs/spec/schema.md §Identity and access
  * docs/spec/schema.md §Invariants — Invariant 2 (last-admin),
    Invariant 3 (at most one group admin), Invariant 7
    (audit-before-effect), Invariant 10 (audit log is append-only)
  * docs/spec/security.md §DB roles
  * docs/spec/security.md §Authorization model
  * docs/design/02-schema.md §2.1 (whole subsection 2.1.1..2.1.9)
- decision_refs: D44, D45, D46

### M1-008b — Sources and tags catalogues (§2.2.1, §2.2.2)
- blocked_by: [M1-005, M1-006, M1-017]
- complexity: medium, risk: medium
- security_relevant: FALSE  (catalogue tables only; no authorization
  surface, no user content)
- migration_touch: TRUE     (adds Flyway V6 migration)
- round_cap: 2
- files_budget: 5           (V6 migration + per-table GRANTs + smoke
  test asserting the partial-unique-index on source enforces D38)
- Scope:
  * Flyway V6 migration creating per docs/design/02-schema.md:
      - §2.2.1 source (with the (kind, identifier) upsert key per D38;
        soft-delete via state column per Invariant 4)
      - §2.2.2 tag (the controlled vocabulary table)
  * Per-table GRANTs per §DB roles. source is Provider-write (the
    bootstrap loader and /add-source both run on the Provider side,
    inserting and updating); Collector reads to know what to poll.
    tag is similarly Provider-write (the bootstrap loader seeds the
    initial vocabulary).
  * SQL-level smoke test: the UNIQUE constraint on (kind, identifier)
    rejects a duplicate insert; the soft-delete column round-trips.
- Out-of-scope MUST list:
    infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
- Spec_refs (all verified):
  * docs/spec/schema.md §Sources and tags
  * docs/spec/schema.md §Invariants — Invariant 4 (soft-delete only for sources)
  * docs/spec/security.md §DB roles
  * docs/design/02-schema.md §2.2.1, §2.2.2
- decision_refs: D5, D7, D38, D42

### M1-008c — Joins, scope preferences, posts (§2.2.3..§2.2.5 + §2.3)
- blocked_by: [M1-008a, M1-008b]
- complexity: high, risk: medium
- security_relevant: TRUE   (post is partitioned and TTL-aged per
  Invariants 5 + 6; getting the partition lifecycle wrong silently
  leaks data past its retention horizon)
- migration_touch: TRUE     (adds Flyway V7 migration)
- round_cap: 2
- files_budget: 8           (V7 migration + 4 table DDLs + post
  partition declaration + per-table GRANTs + initial partition row +
  smoke test for the scope discriminator NOT NULL)
- Scope:
  * Flyway V7 migration creating per docs/design/02-schema.md:
      - §2.2.3 source_subscription (per-scope join, FK to source +
        users/groups)
      - §2.2.4 scope_tag (per-scope tag follow / unfollow)
      - §2.2.5 scope_preferences
      - §2.3.1 post — declared as a partitioned table by fetched_at
        per Invariant 6. The migration creates the parent table plus
        at least one initial partition so the schema is queryable
        on day one; the partition cadence + pruner schedule belong
        to a later T1-C/T1-D ticket (out of scope here).
  * Per-table GRANTs per §DB roles. Join tables are Provider-write.
    post is Collector-write, Provider-read.
  * SQL-level smoke test asserting:
      - Every join-table row carries a scope discriminator ('dm' or
        'group') and the relevant scope FK (Invariant 1 schema-level
        enforcement);
      - source_id FKs on post reference source rows that survive
        soft-delete (Invariant 4 — Collector can still write posts
        against a soft-deleted source for the in-flight ingest run);
      - The partition routing places a row inserted into post into
        the correct partition (verifying the parent is correctly
        declared PARTITION BY).
- Out-of-scope MUST list:
    infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
    (the cross-table isolation invariant lives there; this ticket's
    smoke test covers per-row schema constraints only)
- Spec_refs (all verified):
  * docs/spec/schema.md §Sources and tags
  * docs/spec/schema.md §Posts and derivatives
  * docs/spec/schema.md §Per-user state (scope-independent)
  * docs/spec/schema.md §Per-scope state
  * docs/spec/schema.md §Invariants — Invariant 1, Invariant 4,
    Invariant 5, Invariant 6
  * docs/spec/security.md §DB roles
  * docs/design/02-schema.md §2.2.3, §2.2.4, §2.2.5, §2.3.1
- decision_refs: D5, D13, D33, D38

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by `grep -n '^## \|^### ' <file>` at this
session's authoring time. Any spec_ref you cite that ISN'T in this
list, verify the anchor exists by reading the cited file before
using it. The clarity-preflight subagent will FAIL the ticket if
a spec_ref doesn't resolve.

  docs/spec/schema.md §Entities                              (line 11)
  docs/spec/schema.md §Identity and access                   (line 13)
  docs/spec/schema.md §Sources and tags                      (line 175)
  docs/spec/schema.md §Posts and derivatives                 (line 245)
  docs/spec/schema.md §Per-user state (scope-independent)    (line 351)
  docs/spec/schema.md §Per-scope state                       (line 366)
  docs/spec/schema.md §Operational                           (line 443)
  docs/spec/schema.md §Invariants                            (line 554)
  docs/spec/security.md §DB roles                            (line 943)
  docs/spec/security.md §Trust boundaries                    (line 38)
  docs/spec/security.md §Authorization model                 (line 300)
  docs/design/02-schema.md §2.1 Identity & access            (line 41)
  docs/design/02-schema.md §2.1.1 users                      (line 43)
  docs/design/02-schema.md §2.1.2 Last-admin protection trigger (line 86)
  docs/design/02-schema.md §2.1.3 groups                     (line 161)
  docs/design/02-schema.md §2.1.4 group_membership           (line 183)
  docs/design/02-schema.md §2.1.5 invite_code                (line 229)
  docs/design/02-schema.md §2.1.6 delete_preban_user         (line 282)
  docs/design/02-schema.md §2.1.7 audit_log                  (line 322)
  docs/design/02-schema.md §2.1.8 audit_log.action enum      (line 381)
  docs/design/02-schema.md §2.1.9 audit_log_view             (line 419)
  docs/design/02-schema.md §2.2 Sources & tags               (line 456)
  docs/design/02-schema.md §2.2.1 source                     (line 458)
  docs/design/02-schema.md §2.2.2 tag                        (line 504)
  docs/design/02-schema.md §2.2.3 source_subscription        (line 529)
  docs/design/02-schema.md §2.2.4 scope_tag                  (line 547)
  docs/design/02-schema.md §2.2.5 scope_preferences          (line 563)
  docs/design/02-schema.md §2.3 Posts (ingest)               (line 592)
  docs/design/02-schema.md §2.3.1 post                       (line 594)

## Style requirements

Match M1-007 + M1-007a/b/c in docs/plan/m1/tickets/ — they're your
closest structural analogue (umbrella + 3 subtickets, same letter-
suffix naming). Read all four once for style. Read
docs/process/ticket-template.md once for the canonical schema.
Then write.

Length per ticket: M1-008 umbrella is the short one (~150-180
lines — the IT is non-trivial); M1-008a is the longest (~280-340
lines — last-admin trigger and the concurrent-revocation test
carry real complexity); M1-008b ~180-220 lines; M1-008c ~240-300
lines. Total: ~850-1040 lines authored this session.

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep/test/SQL assertions, not prose.
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-007, M1-007a, M1-007b, M1-007c once for style.
- DO read docs/process/ticket-template.md once.
- DO read docs/process/workflow.md §Ticket-ID placeholder convention once.
- DO read docs/spec/schema.md once (one full pass — ~700 lines).
- DO read docs/design/02-schema.md §2.1 + §2.2 + §2.3 (contiguous
  in the file).
- DO read docs/spec/security.md §DB roles + §Trust boundaries +
  §Authorization model in one pass.
- DO NOT spawn Explore or any other subagent.
- DO NOT pre-load the full docs/spec/ tree.
- DO NOT re-read sections you already loaded.

## After authoring all four tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm the umbrella's integration test path matches what each
   subticket listed in out_of_scope. The locked path is
   infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
   in all four files. Fix any mismatch BEFORE you stop.
3. Print a one-paragraph summary: "M1-008 umbrella + M1-008a/b/c
   drafted as untracked files on main. T1-A schema group authoring
   is complete; the user opens /m1-tick start M1-008a (or whichever
   leaf the dependency graph surfaces first) when ready."
4. STOP. Do NOT commit. Do NOT run /m1-tick start.

## What you do NOT do

- Do NOT commit any ticket file.
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT renumber. The IDs M1-008, M1-008a, M1-008b, M1-008c are LOCKED.
- Do NOT begin authoring Tier 1 B/C/D/E/F tickets. Those are
  separate sessions with their own handoffs.
- Do NOT add Java entity classes, repositories, services, or any
  application code. Subtickets are migrations + GRANTs + trigger
  bodies + SQL-level tests ONLY. The cross-cutting IT in M1-008 is
  raw JDBC against the migrated schema, no entity layer.
- Do NOT touch M1-019 or M1-020. Their deferred_on fields get
  updated by the sessions that author T1-D's LLM ticket (for M1-019)
  and T1-E's messaging umbrella (for M1-020), not by this session.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start.
- Suffix-IDs (M1-008a/b/c) and umbrella semantics: see
  docs/process/workflow.md §Ticket-ID placeholder convention.

## Your immediate task when the user says "go"

1. Read M1-007, M1-007a, M1-007b, M1-007c in docs/plan/m1/tickets/
   once for style.
2. Read docs/process/ticket-template.md once.
3. Read docs/process/workflow.md §Ticket-ID placeholder convention once.
4. Read docs/spec/schema.md in one pass.
5. Read docs/design/02-schema.md §2.1 + §2.2 + §2.3 in one pass.
6. Read docs/spec/security.md §DB roles + §Trust boundaries +
   §Authorization model in one pass.
7. Write M1-008a (largest of the subtickets; serves as a template).
8. Write M1-008b (smaller; substitute source/tag scope).
9. Write M1-008c (medium; substitute join + post scope, include
   partitioning specifics).
10. Write M1-008 umbrella (shortest; the cross-cutting IT only).
11. Print the summary. STOP.
```
