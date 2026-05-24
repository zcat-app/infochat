---
id: M1-052
title: Saved-post library — /save + /saved + /unsave + saved_post snapshot
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 12
files_scope:
  - infochat-core/src/main/resources/db/migration/V15__saved_post.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnsaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - any change to the spec — docs/spec/commands.md §Content + docs/spec/schema.md §Per-user state + §Invariants (1 carve-out, 6 carve-out) are the source of truth
  - any /forget interaction beyond "/forget purges saved_post rows" — T2-E territory; this ticket lands the table so T2-E has something to purge
  - any /export interaction beyond "saved_post rows are exportable" — T2-E territory; same reasoning
  - any /quarantine flow change — T2-G territory; this ticket's visibility filter reads the existing `post.status` column without modifying quarantine semantics
  - any audit-log writer change — `/save`, `/saved`, `/unsave` are NOT in the spec's audit-logged set (no entry in `AuditAction` for save actions); only privileged actions write audit rows per spec §Authorization model
  - any change to the M1-051 ConfirmStateService — none of `/save`, `/saved`, `/unsave` is confirmable per spec; the handlers do NOT integrate with ConfirmStateService and the M1-051 step 4.5 router sweep is unaffected
  - any TranslationProvider interaction — T2-C territory; new bundle entries are English only
  - any change to InboundRouter intake-step splice from M1-044b — handlers register as new CommandHandler beans and the router picks them up via `Instance<CommandHandler>` iteration; no router edit
  - any change to the V5 `users.save_count` column or its denormalization shape — V5 already declares `save_count INT NOT NULL DEFAULT 0`; this ticket's V15 migration only adds the triggers that maintain it
  - any change to CommandPermissions — `saved` is already in the slow-start ALLOWED set; `save` and `unsave` are intentionally outside it per spec §Slow-start tier (Blocked column)
  - any chat-mode interaction — T2-D territory
  - any per-user save-cap value change — value lives in design notes (`docs/design/02-schema.md` §2.6.1 commits 1000 as the spec-level cap); this ticket consumes the value via a profile-driven config property
acceptance:
  - "Flyway migration `infochat-core/src/main/resources/db/migration/V15__saved_post.sql` applies cleanly on a fresh DB. The migration creates the `saved_post` table per `docs/design/02-schema.md` §2.6.1 (PK = `(user_id, post_uid)`; FKs to `users(id)` and `source(id)`; snapshot columns `title`, `body`, `url`, `author`, `published_at`, `snapshot_tags`; personal columns `personal_tags`, `note`; `saved_at` defaulting to `now()`) AND the two `AFTER INSERT|DELETE ... FOR EACH ROW EXECUTE FUNCTION trg_saved_post_count()` triggers that maintain `users.save_count`. **The `saved_post` row carries `user_id` only — there is NO scope discriminator column**, per spec §Invariants 1 carve-out and §Per-user state. The DDL CHECK constraints (if any) MUST NOT introduce a scope_kind / scope_id column. Migration filename is `V15__saved_post.sql` — T2-H.a (M1-055a) consumed `V14__asset_config.sql` first in its sibling worktree, so this ticket lands on V15. The implementing session re-runs `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail` at `/m1-tick start` to confirm V14 is still the highest applied migration on `main` (M1-055a not yet merged) AND that V15 remains free; if either has shifted further, rebase the filename plus all V15 references in test resources / scripts accordingly"
  - "`SaveCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"save\"`. It is discovered by `InboundRouter` via the existing `Instance<CommandHandler>` iteration — no router case-edit, no manual registration. Argument shape: positional `<uid>` plus optional `-t personal-tags` (comma-separated). The handler runs inside ONE database transaction that: (1) reads the actor row + `save_count` via `SELECT ... FROM users WHERE id = ? FOR UPDATE` (atomic cap enforcement per spec §Content + design §2.6.1); (2) reads the target post via `SELECT id, title, body, url, author, published_at, source_id, post_uid, status FROM post WHERE post_uid = ? AND status = 'READY'`; (3) on row-not-found OR status != READY, returns `error.save.unknown_uid` (the visibility filter — QUARANTINED and NEEDS_REVIEW posts are indistinguishable from missing UIDs at the user surface, per spec §Content Visibility-of-target rules); (4) on `save_count >= cap` (cap value injected via `@ConfigProperty(name = \"infochat.save.cap\")`), returns `error.save.cap_met` (the friendly cap-exceeded error pointing the user at /unsave); (5) otherwise INSERTs a `saved_post` row snapshotting the post's body+title+url+author+published_at+source_id+bot's current `bootstrap_tags` into `snapshot_tags`, with the caller's `-t` values into `personal_tags`; (6) returns `reply.save.success` interpolated with the UID. The V15 trigger increments `users.save_count` automatically. A duplicate save against the same `(user_id, post_uid)` PK collides per the table's PK constraint — the handler MUST surface `error.save.already_saved` rather than letting the SQLException escape (handler-side check via the SELECT step OR caught at INSERT time; either is acceptable)"
  - "SaveCommandHandlerTest follows **Shape B (Thin-SQL)** per `docs/process/test-pyramid.md` §Handler unit tests — `@QuarkusTest` against Quarkus DevServices Postgres (the V1..V15 migrations run on container start), `@Inject DataSource` + `@Inject SaveCommandHandler` + `@Inject BundleLoader` + `@Inject InboundContext`, direct `handler.handle(...)` dispatch (the test MUST NOT route through `InboundRouter` per the section-root rule), `@BeforeEach` cleanup against the actor's contact-id prefix. Shape B fits per the choosing-rule's literal criterion (`test-pyramid.md:106` — ≥2 real-DB-dependent statements: `SELECT … FOR UPDATE` on `users` for atomic cap; `INSERT` against the `(user_id, post_uid)` PK; INSERT-trigger on `users.save_count`). Canonical comparator: `GrantAdminCommandHandlerTest`. Scenarios — each is a separate `@Test` method whose method name reads as the assertion: `saveHappyPathReturnsSuccessAndWritesSnapshotRow`, `saveAgainstQuarantinedPostReturnsUnknownUid`, `saveAgainstNeedsReviewPostReturnsUnknownUid`, `saveAgainstUnknownUidReturnsUnknownUid`, `saveAgainstAlreadySavedPostReturnsAlreadySaved`, `saveAtCapReturnsCapMetAndWritesNoRow`, `saveWithPersonalTagsPopulatesPersonalTagsColumn`, `saveSnapshotsBodyTitleUrlAuthorPublishedAtAndSourceId`"
  - "`SavedCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"saved\"`. Argument shape: optional positional `[tag]` plus optional `-w <window>` plus optional `--page N`. The handler reads the actor's saves via `SELECT post_uid, title, url, snapshot_tags, personal_tags, saved_at FROM saved_post WHERE user_id = ? [AND <personal_tags filter>] [AND saved_at > ?] ORDER BY saved_at DESC LIMIT <pagesize> OFFSET <(N-1)*pagesize>` — the query carries **no scope filter** (per-user-globally per spec §Content Decision D13). Page size is a fixed constant from `docs/design/03-commands.md` §`/saved` (20). The reply header **MUST** disclose per-user-global semantics — a bundle key e.g. `reply.saved.header.global` whose text mentions saves are visible across DM and groups so a user invoking `/saved` from a group is not surprised by DM-only saves appearing"
  - "SavedCommandHandlerTest follows **Shape B (Thin-SQL)** per `docs/process/test-pyramid.md` §Handler unit tests — same `@QuarkusTest` + DevServices Postgres shape as SaveCommandHandlerTest. Although the handler issues a single `SELECT` (the literal `test-pyramid.md:106` choosing-rule would point to Shape A), Shape B applies by the section's **rationale paragraph** (`test-pyramid.md:41`): the handler's behavioral contract IS the SQL predicate (per-user-global with no scope discriminator clause; optional `personal_tags` filter; optional `saved_at > ?` window; `ORDER BY saved_at DESC LIMIT/OFFSET` pagination). Shape A would either inspect the assembled SQL string at the JDBC stub (the whitebox tautology the rationale paragraph warns against) or return canned rows regardless of args (so the predicate logic is unobserved at the handler tier) — neither is meaningful. Real-DB observation against seeded `saved_post` rows is the only honest way to verify the contract. Scenarios: `savedReturnsEmptyHeaderWhenLibraryEmpty`, `savedReplyHeaderDisclosesGlobalScope`, `savedListsAllRowsRegardlessOfCallingScope` (seed two saves — one whose `saved_at` was originally created from a DM context, one from a group context — and assert both appear from BOTH DM and group invocations; the cross-scope appearance IS the per-user-global semantics), `savedFiltersByPersonalTag`, `savedFiltersByWindow`, `savedPaginatesByPageFlag`"
  - "`UnsaveCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"unsave\"`. Argument shape: positional `<uid>`. The handler issues `DELETE FROM saved_post WHERE user_id = ? AND post_uid = ?` and returns `reply.unsave.success` on `affectedRows == 1` or `error.unsave.unknown_uid` on `affectedRows == 0`. The V15 trigger decrements `users.save_count` automatically. No confirm gate — spec §Content explicitly says `/unsave` has no confirmation"
  - "UnsaveCommandHandlerTest follows **Shape B (Thin-SQL)** per `docs/process/test-pyramid.md` §Handler unit tests — same `@QuarkusTest` + DevServices Postgres shape as SaveCommandHandlerTest. Although the handler issues a single `DELETE` (the literal `test-pyramid.md:106` choosing-rule would point to Shape A), Shape B applies by the section's **rationale paragraph** (`test-pyramid.md:41`): the handler's behavioral contract includes the trigger-driven `users.save_count` decrement, which is load-bearing — the cap-reset mechanism (saturate-then-unsave-then-save-readmits) depends on the trigger firing on every `DELETE`. Shape A would assert only the `affectedRows` branch (success vs unknown-uid) and lose trigger observation at the handler tier; the `unsaveAfterSaveAtCapAllowsSubsequentSave` scenario verifies the trigger explicitly and is meaningful only against the real DB. Scenarios: `unsaveHappyPathRemovesRowAndDecrementsSaveCount`, `unsaveUnknownUidReturnsUnknownUidAndLeavesSaveCountUnchanged`, `unsaveAfterSaveAtCapAllowsSubsequentSave` (saturate at cap, /unsave one, then a second /save admits — verifies the trigger-driven decrement keeps the cap check correct)"
  - "SaveCapConcurrencyIT is a `@QuarkusTest`-shaped IT against Testcontainers Postgres. Seeds the actor's `users.save_count` to cap-1 (where cap is the test profile's `infochat.save.cap` value), then issues two `/save` calls from two threads simultaneously against two different READY posts. Asserts exactly one save admits (one INSERT succeeds, one returns `error.save.cap_met`) AND `users.save_count == cap` afterwards. The IT exists because the `SELECT ... FOR UPDATE` lock in the handler is the **only** atomic-cap guarantee — a unit test against a single-threaded handler cannot exercise the lock; the IT does. Method name: `concurrentSavesAtCapMinusOneAdmitExactlyOne`"
  - "SavedLibraryIT is a `@QuarkusTest`-shaped IT that drives `/save`, `/saved`, `/unsave` end-to-end via the InMemoryAdapter (mirrors the M1-036 AddSourceIT pattern). One scenario: seed one READY post, deliver `/save <uid>` from the actor in DM, assert one outbound reply matches `reply.save.success`; deliver `/saved` from the same actor in a DIFFERENT scope (group scope simulated by the InMemoryAdapter's group deliver hook), assert the outbound lists the saved row + carries the per-user-global header disclosure; deliver `/unsave <uid>` in DM, assert the row is removed AND `users.save_count == 0`. Method name: `saveListUnsaveRoundtripCrossScopeVisibility`"
  - "`BundleKeys.java` adds the new `public static final String` constants: `ERROR_SAVE_UNKNOWN_UID`, `ERROR_SAVE_CAP_MET`, `ERROR_SAVE_ALREADY_SAVED`, `REPLY_SAVE_SUCCESS`, `REPLY_SAVED_HEADER_GLOBAL`, `REPLY_SAVED_LINE`, `REPLY_SAVED_EMPTY`, `ERROR_UNSAVE_UNKNOWN_UID`, `REPLY_UNSAVE_SUCCESS`. `bundles/en.properties` adds the corresponding entries. The `BundleLoaderTest` reflective assertion (from M1-035c) catches any missing key or orphan automatically — running `mvn -B clean verify` from the repo root exits 0 with the new keys"
  - "`infochat-provider/src/main/resources/application.properties` declares `infochat.save.cap=1000` as the base default plus per-profile overrides matching `docs/design/02-schema.md` §2.6.1 (laptop/vps/remote-llm = 1000; pi value lives in design notes — if the design file does not pin a separate `pi` value, the base default applies). The value is read by `SaveCommandHandler` via `@ConfigProperty(name = \"infochat.save.cap\")` — tests override via `%test.infochat.save.cap=<small-value>` to make cap-saturation scenarios cheap (e.g., cap = 3 in unit tests)"
  - "`mvn -B clean verify` from the repo root exits 0. All pre-existing tests still pass — the bundle-completeness assertion catches any missing key; the M1-051 ConfirmStateServiceTest / ConfirmFlowIT remain green because none of `/save`, `/saved`, `/unsave` consumes ConfirmStateService; the M1-049 plain-JUnit handler tests are unaffected; the M1-035 / M1-036 source / summary surfaces are unchanged"
test_plan:
  adds:
    - infochat-core/src/main/resources/db/migration/V15__saved_post.sql
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnsaveCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/main/resources/application.properties
  preserves:
    - all tests currently green on main
    - every M1-035 / M1-036 / M1-044 / M1-045 / M1-051 test
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Permission model
  - docs/spec/schema.md §Per-user state (scope-independent)
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Slow-start tier
  - docs/process/test-pyramid.md §Handler unit tests
decision_refs:
  - D13
  - D33
reviews: {}
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-05-24
    prior_deferred_reason: spec-amend
    prior_deferred_on: M1-056
    reason: M1-056 (test-pyramid Shape B carve-out) landed; reopening with acceptance items 3/5/7 rewritten to cite Shape B and spec_refs widened to include the amended pyramid section
redteam_findings: []
clarity_check:
  date: 2026-05-24
  verdict: WARN
  warnings:
    - "files_scope hardcodes V14__saved_post.sql but M1-055a may consume V14 first, requiring rename to V15+; acceptance item 1 instructs the implementer how to handle it. Reviewer should verify the actual migration filename in the diff matches what was agreed at start time."
  blockers: []
escalations:
  - date: 2026-05-24
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — developer-surfaced before any implementation. Acceptance item 3
      requires SaveCommandHandlerTest to be BOTH "plain JUnit per the M1-049
      test pyramid (no @QuarkusTest)" AND "exercises the handler against a
      Testcontainers Postgres bootstrapped with V1..V14 migrations
      (`@TestInstance(Lifecycle.PER_CLASS)` + Flyway-on-startup helper, the
      M1-044c pattern)". These three citations are mutually exclusive in
      the project:
        (a) docs/process/test-pyramid.md §Handler unit tests MUST NOT use a
            real DataSource. The two canonical examples
            (AddSourceCommandHandlerTest, SummaryCommandHandlerTest) both
            use StubUserDataSource (hand-rolled, not a real DB).
        (b) The "M1-044c pattern" (GrantAdminCommandHandlerTest) is itself
            `@QuarkusTest` using Quarkus DevServices — NOT plain JUnit,
            NOT Testcontainers.
        (c) No plain-JUnit + manual-Testcontainers + Flyway-on-startup
            pattern exists anywhere in the project; inventing it would
            require ~200 lines of new infrastructure + a Maven dependency.
      Acceptance items 5 (SavedCommandHandlerTest) and 7
      (UnsaveCommandHandlerTest) inherit the same shape ambiguity.
      Resolution: spec-amend via M1-056 — carve out a "Thin-SQL handler
      exception" subsection in docs/process/test-pyramid.md §Handler unit
      tests legitimizing the M1-044c `@QuarkusTest` pattern for handlers
      whose business logic IS DB interaction. After M1-056 lands, M1-052
      reopens and refines acceptance items 3/5/7 to cite the codified
      exception.
---

# M1-052: Saved-post library — /save + /saved + /unsave + saved_post snapshot

## Context

T2-B.1 — the first of three Tier-2.B DM-command tickets. Lands the
saved-post library surface: three new `CommandHandler` beans
(`/save`, `/saved`, `/unsave`) plus the `saved_post` table and
its `users.save_count` denormalization triggers. The handlers
implement spec §Content's three commitments:

1. **Per-user-globally.** `saved_post` carries `user_id` only — no
   scope discriminator. This is the documented exception to
   Invariant 1 (per-(user, scope) isolation); a save made in DM
   appears in `/saved` from any group, and vice versa. The
   `/saved` reply header **must disclose** this so a user invoking
   the command in a group is not surprised by DM-only saves.
2. **Snapshot-on-save (Invariant 6 carve-out).** The save captures
   the visible body + metadata into `saved_post` columns; a later
   `post` partition drop (Invariant 6) does not break the bookmark.
   The bookmark is keyed on the spec UID (`post_uid`) which is
   stable across rebinds; the snapshot fields satisfy retrieval
   without re-reading `post`.
3. **Atomic per-user cap.** The cap (1000 per design §2.6.1) is
   enforced by `SELECT ... FOR UPDATE` on the actor's `users` row
   inside the save transaction. Two concurrent `/save` calls at
   cap-1 admit exactly one — verified by SaveCapConcurrencyIT
   (the lock is the **only** atomic guarantee; single-thread unit
   tests cannot exercise it).

Visibility-of-target rules: `/save` against a QUARANTINED post
(Stage 2 hidden) or a NEEDS_REVIEW post is treated as an unknown
UID — the handler queries `post WHERE post_uid = ? AND status =
'READY'` so non-READY rows are indistinguishable from "no such UID"
at the user surface. The flow never lets a user bookmark content
they cannot see.

The migration is `V15__saved_post.sql`. At the moment this ticket
was authored, V13 was the last applied migration on `main`; T2-H.a
(M1-055a) consumed `V14__asset_config.sql` first in its sibling
worktree, so this ticket lands on V15. The implementing session
re-runs `ls infochat-core/src/main/resources/db/migration/ | sort -V`
at `/m1-tick start` to confirm V14 is still the highest applied
migration on `main` (M1-055a not yet merged) AND that V15 remains
free; if either has shifted further, rebase the filename plus any
V15 string references in test resources / scripts accordingly.

`complexity: medium` — three small handlers + one migration + one
concurrency IT. The atomic-cap IT is the load-bearing test; the
rest is straightforward CRUD.

`risk: medium` — the cap-enforcement lock is correctness-critical
(a missed lock allows over-cap saves), but the test pyramid covers
it explicitly. No authorization-state implications.

`security_relevant: false` — saves are user-owned data with no
authorization-state implications. `/redteam` after `/commit` is
**not** the default recommendation for this ticket.

## Acceptance

The behavioural contract mirrored from the YAML `acceptance:` list
above. Every item is either a named test method the diff must add
and pass, a prose behavioural assertion, or a runnable command.
The eleven items in YAML are the binding list; do not duplicate
them here.

The single load-bearing IT (SaveCapConcurrencyIT) pins the atomic
cap-enforcement — without the two-thread IT, a `SELECT ... FOR
UPDATE` regression (e.g., a developer dropping the lock during a
refactor) would silently allow over-cap saves and only surface in
production under concurrent load.

## Out-of-scope

The YAML `out_of_scope` list above enumerates fourteen exclusions.
Highlights:

- **No `/forget` or `/export` change.** T2-E (privacy) lands those;
  this ticket only puts `saved_post` rows on disk so T2-E has
  something to purge / export.
- **No `/quarantine` flow change.** T2-G owns quarantine; this
  ticket reads `post.status = 'READY'` as the visibility filter
  but does not modify the underlying status machine.
- **No ConfirmStateService consumption.** Spec §Content is
  explicit that `/unsave` has no confirmation and that `/save` and
  `/saved` are non-destructive; the M1-051 confirm machinery is
  unused.
- **No CommandPermissions edit.** `saved` is already in the
  M1-045 slow-start ALLOWED set
  (`CommandPermissions.java:46-57`); `save` and `unsave` are
  intentionally outside it (spec §Slow-start tier Blocked column).
  The handler-side probation check is the M1-045 step 5 gate and
  needs no augmentation.
- **No audit-log row.** `/save`, `/saved`, `/unsave` are
  user-preference actions — not in the spec's audit-logged
  privileged-action set. Adding an audit verb (e.g. `SAVE`,
  `UNSAVE`) would be a spec amendment; v1 commits to no audit on
  user-state saves.

## Notes

- **Spec anchors (verbatim citations):**
  - `docs/spec/commands.md` §Content — the `/save`, `/saved`,
    `/unsave` paragraphs (per-user-global semantics, cap
    enforcement, visibility-of-target rules).
  - `docs/spec/schema.md` §Per-user state — the `saved_post`
    entity description (per-user-globally; the Invariant 1
    exception).
  - `docs/spec/schema.md` §Invariants — Invariant 6 (UID rebind
    discipline; the snapshot rule on `/save`).
  - `docs/spec/commands.md` §Permission model — `/save`, `/saved`,
    `/unsave` are NOT in the closed bot-admin set.
  - `docs/spec/security.md` §Slow-start tier — `/saved` Allowed;
    `/save` and `/unsave` Blocked.
- **Design anchors:**
  - `docs/design/02-schema.md` §2.6.1 — `saved_post` DDL +
    `users.save_count` denormalization + the 1000-save cap.
  - `docs/design/03-commands.md` §`/save`, §`/saved`, §`/unsave`
    — handler organization + bundle-key naming + reply layout
    + page size constant (20).
- **Cap mechanism.** Design §2.6.1 commits to `SELECT ... FOR
  UPDATE` on `users.save_count` (the denormalized counter) as the
  atomic mechanism. The handler reads `save_count` inside the
  transaction with the row lock; on cap-met the transaction
  aborts before any INSERT runs. The V15 triggers maintain the
  count under contention.
- **InboundRouter behavior.** No router edit — `InboundRouter.handleSlash`
  (`InboundRouter.java:559-568`) iterates `Instance<CommandHandler>`
  and matches by `handler.name()`. Three new beans land in
  `app.zcat.infochat.provider.command`; the router picks them up
  automatically.
- **M1-051 ConfirmStateService.** No consumption. The router's
  step 4.5 sweep treats `/save`, `/saved`, `/unsave` like any
  non-confirm inbound — when the actor has no pending confirm,
  the sweep is a no-op; when they DO have a pending confirm from
  some other admin command, the sweep cancels the pending state
  and sends the cancellation ack BEFORE the dispatched
  `/save|/saved|/unsave` reply (same as the spec's
  "any other input cancels" semantics). Nothing for this ticket
  to wire.
- **`personal_tags` and `snapshot_tags`** are separate arrays per
  design §2.6.1: `personal_tags` carries the user's `-t` values
  (free-form, no vocabulary check — spec §Content
  "Personal tags are free-form and never join the controlled
  vocabulary"), `snapshot_tags` carries the bot's current
  `bootstrap_tags` for the source at save time (the user is
  bookmarking the bot's tag classification AS WELL as the post).
- **No new AuditAction.** Verified at authoring time: the
  `AuditAction` enum in `infochat-core` contains no `SAVE` or
  `UNSAVE` value, and adding one would be a spec amendment. The
  handlers write zero rows to `audit_log`.
- **T2-H parallel collision (resolved).** T2-H.a (asset_config +
  price_snapshot) consumed `V14__asset_config.sql` first in its
  sibling worktree; this ticket lands on `V15__saved_post.sql`.
  The implementing session re-checks at `/m1-tick start` that V14
  remains the highest applied migration on `main` (M1-055a
  unmerged) and V15 is still free; rebase further if either has
  shifted.
- **Test-shape choice (Shape B for all three handler tests).**
  Items 3 / 5 / 7 above all cite Shape B (Thin-SQL) per
  `docs/process/test-pyramid.md` §Handler unit tests.
  SaveCommandHandlerTest fits the literal `test-pyramid.md:106`
  choosing-rule (≥2 real-DB-dependent statements). The other two
  (SavedCommandHandlerTest, UnsaveCommandHandlerTest) each issue a
  single statement and so do NOT fit the literal rule — they
  invoke Shape B by the section's **rationale paragraph**
  (`test-pyramid.md:41`) which states "the handler's behavioral
  contract IS the DB interaction (lock acquisition,
  trigger-driven state, constraint enforcement); the test must
  observe the DB to verify the contract". For /saved that
  contract is the SQL predicate construction
  (per-user-global / tag filter / window / pagination); for
  /unsave it is the trigger-driven `users.save_count` decrement
  that makes the cap-reset mechanism work. Shape A for either
  would either reduce to whitebox-tautology against a JDBC stub
  or to a rendering-only test that leaves the contract
  unobserved — both are degenerate. The literal rule undercounts
  thin handlers whose ONE statement has rich semantics; the
  rationale paragraph is the canonical defense. Reviewer is
  invited to flag this if the literal-rule reading is preferred,
  in which case the resolution path is a follow-up amendment to
  broaden the `test-pyramid.md:106` choosing-rule rather than
  re-shaping the M1-052 tests against the rationale's own
  warning.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-052-saved-post-library.md
```
