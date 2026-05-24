---
id: M1-054
title: Per-scope tag preferences — /follow-tag + /unfollow-tag + tag-mode state machine
status: done
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-051
  - M1-057
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagAllConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TagModeRoundtripIT.java
out_of_scope:
  - any change to the spec — docs/spec/commands.md §Per-scope tag preferences + docs/spec/schema.md §Sources and tags + §Per-scope state are the source of truth
  - any change to the `scope_tag` table or `scope_preferences.tag_mode` column — V7 already ships both (`scope_tag (scope_kind, scope_id, tag_id)` PK and `scope_preferences.tag_mode TEXT NOT NULL DEFAULT 'ALL' CHECK (tag_mode IN ('ALL','EXPLICIT'))`); no Flyway migration in this ticket
  - any new AuditAction enum value — tag-preference mutations are NOT audit-logged in v1 (user-preference, not privileged action per spec §Authorization model); the handlers write zero rows to `audit_log`
  - any change to the digest scheduler — T2-F territory; this ticket writes the `scope_tag` + `tag_mode` state, the scheduler reads it when T2-F lands
  - any /add-source change — M1-036's commit is consumed unchanged
  - any vocabulary-removal mechanism — append-only is the spec commitment for v1 (`schema.md` §Sources and tags Vocabulary lifecycle); a `/follow-tag` against a tag whose only contributing source was removed long ago is **valid** input
  - any TranslationProvider exercise — T2-C territory; new bundle entries are English only
  - any chat-mode interaction — T2-D territory
  - any change to M1-051 ConfirmStateService — only `/unfollow-tag --all` consumes the confirm gate, and it does so via the existing `remember` / `takeMatching` calls with a new `commandName` key (`"unfollow-tag-all"`)
  - any change to the InboundRouter intake-step splice from M1-044b — handlers register as new CommandHandler beans and the router picks them up via `Instance<CommandHandler>` iteration; no router edit
  - any change to CommandPermissions — `/follow-tag` and `/unfollow-tag` are intentionally outside the slow-start ALLOWED set per spec §Slow-start tier Blocked column; the M1-045 step 5 gate is the primary defence
  - any /save / /saved / /unsave handler — T2-B.1 territory
  - any /list-sources / /remove-source / /source-enable / /source-disable handler — T2-B.2 territory
acceptance:
  - "`FollowTagCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"follow-tag\"`. Argument shape: positional `<tag>` (one tag per invocation). Permission gate runs FIRST in the handler: (a) DM scope — the caller's own scope is the target; (b) group scope — the caller MUST be the group admin (per spec §Permission model Group-admin set explicitly lists `/follow-tag in groups`); non-admin in group returns `error.follow_tag.group_admin_only`. The handler validates the tag against the controlled vocabulary — a tag value NOT in `tag` returns `error.follow_tag.unknown_tag` with fuzzy-suggestion footer (the same friendly-error shape as `/add-source --tags`). On valid tag input, the handler executes the mode-transition state machine in ONE transaction: (1) `SELECT tag_mode FROM scope_preferences WHERE scope_kind = ? AND scope_id = ? FOR UPDATE` (the row exists for every active scope per the M1-007c / M1-035 scope-bootstrap path); (2) if `tag_mode = 'ALL'` → flip to `EXPLICIT` AND `INSERT INTO scope_tag (scope_kind, scope_id, tag_id) VALUES (?, ?, ?)` (seed the single followed tag — `I asked for X, only X` per spec); (3) if `tag_mode = 'EXPLICIT'` → `INSERT INTO scope_tag ... ON CONFLICT DO NOTHING` (idempotent add in place); (4) increment `scope_preferences.tag_subscription_version` so the digest cache invalidates on the next read; (5) return `reply.follow_tag.success_from_all` for the ALL→EXPLICIT transition (interpolating the followed tag) OR `reply.follow_tag.success_in_place` for the EXPLICIT add (interpolating the tag)"
  - "FollowTagCommandHandlerTest is Shape B (Thin-SQL) per `docs/process/test-pyramid.md` §Shape B: `@QuarkusTest` against the default-profile DevServices Postgres image, `@Inject` for the handler and its DataSource / BundleLoader / InboundContext collaborators, direct `handler.handle(scope, rawText)` calls (the router-leak rule applies — no `adapter.deliverDm(...)` here; the full chain belongs to TagModeRoundtripIT). `@BeforeEach` cleanup deletes test rows by a class-wide contact-id prefix the same way the M1-046 GrantAdminCommandHandlerTest / M1-044c BanCommandHandlerTest precedents do. Scenarios — one `@Test` per branch: `followTagDmInAllModeFlipsToExplicitAndSeedsSingleTag`, `followTagDmInExplicitModeAddsRowInPlace`, `followTagDmInExplicitModeIdempotentOnDuplicateAdd`, `followTagDmUnknownTagReturnsFuzzySuggestionError`, `followTagGroupScopeShortCircuitsToGroupAdminOnly`, `followTagIncrementsTagSubscriptionVersion`. The original `followTagGroupAdminFlipsModeForGroupScope` scenario is dropped: the frozen `CommandHandler.handle(ScopeRef, String)` SPI does not carry the inbound caller's contact id in group scope (`ScopeRef.Group` holds only the adapter-side group id), so the handler cannot consult `group_membership` to identify a group admin; v1 short-circuits ALL group calls to `error.follow_tag.group_admin_only` per the M1-036 AddSourceCommandHandler precedent. T2-F wires the actor seam and the group-admin proceed-path test lands then."
  - "`UnfollowTagCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"unfollow-tag\"`. Argument shape: positional `<tag>` OR the `--all` flag (mutually exclusive — passing both returns `error.unfollow_tag.mutually_exclusive`). Permission gate is identical to FollowTagCommandHandler (DM = own scope; group = group-admin only). The handler branches on the argument shape: (A) `<tag>` form — validates the tag against vocabulary (same error path as /follow-tag), then in ONE transaction: (1) SELECT tag_mode FOR UPDATE; (2) if `tag_mode = 'ALL'` → flip to `EXPLICIT` AND seed `scope_tag` rows for **all currently subscribed-source `bootstrap_tags` for this scope MINUS the unfollowed tag** (`I want everything except X` per spec) — the seeding query joins `source_subscription` × `source` × `unnest(bootstrap_tags)` × `tag` filtered to the scope; (3) if `tag_mode = 'EXPLICIT'` → `DELETE FROM scope_tag WHERE scope_kind = ? AND scope_id = ? AND tag_id = ?`; if the post-delete row count for `(scope_kind, scope_id)` is 0 → flip `tag_mode` back to `ALL`; (4) increment `tag_subscription_version`; (5) return the appropriate success reply. (B) `--all` form — confirm-gated. Two-call shape: first call (no trailing ` confirm`) registers a pending confirm via `confirmStateService.remember(actor.id, scope, \"unfollow-tag-all\", ...)` and returns `reply.confirm.prompt.unfollow_tag_all` (interpolates timeout seconds + the current scope's row count to give the user a warning of what they are clearing). Confirm call (`/unfollow-tag --all confirm`) takes the pending via `takeMatching`; on empty Optional → `error.confirm.no_pending`; on non-empty → in ONE transaction: `DELETE FROM scope_tag WHERE scope_kind = ? AND scope_id = ?`, `UPDATE scope_preferences SET tag_mode = 'ALL', tag_subscription_version = tag_subscription_version + 1 WHERE scope_kind = ? AND scope_id = ?`, returns `reply.unfollow_tag_all.success` (the count of deleted rows)"
  - "UnfollowTagCommandHandlerTest is Shape B (Thin-SQL) per `docs/process/test-pyramid.md` §Shape B: `@QuarkusTest` against the default-profile DevServices Postgres image, `@Inject` for the handler and its DataSource / BundleLoader / InboundContext / ConfirmStateService collaborators, direct `handler.handle(scope, rawText)` calls (the router-leak rule applies — no `adapter.deliverDm(...)` here; the full chain belongs to TagModeRoundtripIT). `@BeforeEach` cleanup deletes test rows by a class-wide contact-id prefix the same way the M1-046 GrantAdminCommandHandlerTest / M1-044c BanCommandHandlerTest precedents do; an `@AfterEach` restores `confirmStateService.setClock(Clock.systemUTC())` per the BanCommandHandlerTest precedent. Scenarios: `unfollowTagDmInAllModeFlipsToExplicitAndSeedsAllMinusOne` (seed three subscribed-source bootstrap_tags = {a,b,c}; unfollow b in ALL mode; assert tag_mode='EXPLICIT' AND scope_tag = {a,c}), `unfollowTagDmInExplicitModeRemovesRowInPlace`, `unfollowTagDmInExplicitModeRowCountToZeroFlipsBackToAll`, `unfollowTagDmUnknownTagReturnsFuzzySuggestionError`, `unfollowTagGroupScopeShortCircuitsToGroupAdminOnly` (renamed from `unfollowTagGroupNonAdminReturnsGroupAdminOnlyError` for symmetry with the FollowTag rename; same v1 actor-seam reason — handler can't distinguish group-admin from non-admin and short-circuits all group calls), `unfollowTagAllFirstCallReturnsPromptAndNoStateChange`, `unfollowTagAllConfirmWithinWindowDeletesAllRowsAndFlipsToAll`, `unfollowTagAllConfirmWithoutPendingReturnsNoPending`, `unfollowTagMutuallyExclusivePositionalAndAllFlagReturnsError`, `unfollowTagIncrementsTagSubscriptionVersionOnEveryMutation`"
  - "TagModeRoundtripIT is a `@QuarkusTest`-shaped IT that exercises the ALL→EXPLICIT→ALL round-trip end-to-end via the InMemoryAdapter. Method `tagModeRoundtripAllExplicitAll` — seed an actor + a source-subscription whose source carries `bootstrap_tags = {ai, security, java}`; assert the scope starts in `tag_mode='ALL'`; deliver `/follow-tag ai` in DM; assert `tag_mode='EXPLICIT'` AND `scope_tag` contains exactly one row (ai); deliver `/follow-tag java`; assert `scope_tag` contains {ai, java} AND tag_mode unchanged; deliver `/unfollow-tag ai`; assert `scope_tag` contains {java}; deliver `/unfollow-tag java`; assert `scope_tag` is empty AND `tag_mode='ALL'`; assert `tag_subscription_version` has incremented exactly four times across the round-trip. Method `unfollowTagAllConfirmRoundtrip` — seed `scope_tag = {ai, security}` in EXPLICIT mode; deliver `/unfollow-tag --all`; assert prompt outbound + no state change; deliver `/unfollow-tag --all confirm`; assert success reply naming the deletion count AND `scope_tag` is empty AND `tag_mode='ALL'` AND `tag_subscription_version` incremented once for the bulk reset"
  - "Each handler consumes the M1-040 `InboundContext` request-scoped bean for the actor/scope lookup; contact-id-bearing exception messages are interpolated via `ContactIds.redact` per the M1-038 / M1-039 redaction precedent. The handlers write zero rows to `audit_log` — verified by the unit-test scenarios `followTagWritesNoAuditRow` and `unfollowTagAllWritesNoAuditRow` (each asserts the post-execution `SELECT COUNT(*) FROM audit_log WHERE user_id = ?` matches the pre-execution count)"
  - "`BundleKeys.java` adds the new constants: `ERROR_FOLLOW_TAG_UNKNOWN_TAG`, `ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY`, `REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL`, `REPLY_FOLLOW_TAG_SUCCESS_IN_PLACE`, `ERROR_UNFOLLOW_TAG_UNKNOWN_TAG`, `ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY`, `ERROR_UNFOLLOW_TAG_MUTUALLY_EXCLUSIVE`, `REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL`, `REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE`, `REPLY_UNFOLLOW_TAG_FLIPS_BACK_TO_ALL`, `REPLY_CONFIRM_PROMPT_UNFOLLOW_TAG_ALL`, `REPLY_UNFOLLOW_TAG_ALL_SUCCESS`. `bundles/en.properties` adds the corresponding entries; the M1-035c `BundleLoaderTest` reflective check enforces alignment"
  - "`mvn -B clean verify` from the repo root exits 0. Pre-existing tests stay green: M1-051 ConfirmStateServiceTest / ConfirmFlowIT remain green because this ticket's `--all` confirm consumes the service via the established `remember` / `takeMatching` API; M1-036 AddSourceCommandHandlerTest remains green because the vocabulary-union path is not touched (this ticket READS the vocabulary, never writes); M1-049 plain-JUnit handler tests are unaffected"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TagModeRoundtripIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
  preserves:
    - all tests currently green on main
    - every M1-035 / M1-036 / M1-044 / M1-045 / M1-051 test
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Permission model
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Per-scope state
  - docs/spec/security.md §Slow-start tier
decision_refs:
  - D15
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
reviews:
  - round: 1
    date: 2026-05-24
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 2441
      removed: 4
  - round: 2
    date: 2026-05-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 2481
      removed: 12
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-05-24
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-057
    reason: M1-057 landed — blocker resolved
redteam_findings: []
clarity_check:
  date: 2026-05-24
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-05-24
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Surfaced during /m1-tick start M1-054 (post-clarity-PASS).
      Implementation requires consuming ConfirmStateService.remember(...)
      for the /unfollow-tag --all confirm gate. remember() only accepts
      a PendingConfirm payload (line 116 of ConfirmStateService.java),
      and PendingConfirm is a sealed nested interface (lines 221-252)
      permitting only Ban, InviteCreateOpen, InviteRevoke. Adding the
      required "unfollow-tag-all" commandName forces a new permit on
      ConfirmStateService.java — outside files_scope and explicitly
      forbidden by out_of_scope clause 9 ("any change to M1-051
      ConfirmStateService"). Same root cause as M1-053's outline-fail
      (commit b36ef1c) — sealed-type extension serializes all
      confirmable-command tickets through one file. Two failures of
      the same shape is the workflow's pattern signal; defer onto
      M1-057 (unseal PendingConfirm + extract variants) rather than
      patching M1-054 in isolation.
  - date: 2026-05-24
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — escalation surfaced by developer during /m1-tick start M1-054
      (post-clarity-PASS, pre-implementation). Acceptance items 2 + 4
      explicitly require the handler tests to be "plain JUnit per the
      M1-049 test pyramid (no `@QuarkusTest`)". This conflicts with
      `docs/process/test-pyramid.md` as amended by M1-056 (commit
      48749), which codifies a two-shape selection rule:
        - Shape A (plain JUnit, no Quarkus boot) when handler has ≥2
          non-DB collaborators with rich orchestration logic.
        - Shape B (@QuarkusTest, real DevServices Postgres, direct
          handler.handle()) when handler has ≤1 non-DB collaborator
          AND ≥2 real-DB-dependent statements (FOR UPDATE locking,
          PK/FK/CHECK constraints, triggers, INSERT...SELECT joins).
      FollowTagCommandHandler + UnfollowTagCommandHandler fit Shape B
      unambiguously: 0 rich non-DB collaborators (BundleLoader is
      config-style; InboundContext is a request-scoped string holder;
      ConfirmStateService is a thin in-memory state holder), and 5+
      real-DB-dependent statements per state-machine transition
      (SELECT FOR UPDATE on scope_preferences; INSERT on scope_tag PK
      + FK to tag(id); UPDATE on tag_mode CHECK; INSERT...SELECT from
      4-way join over source_subscription × source ×
      unnest(bootstrap_tags) × tag; DELETE with row-count semantics
      for the EXPLICIT→ALL flip-back branch). Test-pyramid §Shape B
      explicitly warns that stubbing JDBC for Thin-SQL handlers
      "reduces tests to whitebox tautologies (asserting that the
      handler issued the exact SQL string the test stubbed)" — and
      hand-rolling proxy stubs for 6 distinct query shapes would also
      violate feedback_avoid_test_inner_classes (>3 inner classes per
      test file). All six Shape-B canonical examples named in the doc
      (GrantAdmin / Revoke / Ban / Unban / Invite / Vouch) use
      @QuarkusTest for exactly this combination.
      Proposed refinement: acceptance items 2 + 4 reword "plain JUnit
      per the M1-049 test pyramid (no `@QuarkusTest`)" → "Shape B per
      docs/process/test-pyramid.md §Shape B (Thin-SQL): @QuarkusTest,
      real DevServices Postgres, direct handler.handle() calls (the
      router-leak rule still applies — no adapter.deliverDm here; the
      TagModeRoundtripIT handles the full chain)". Test method names
      stay; scenarios stay; files_scope unchanged; files_budget
      unchanged. Audit-zero assertion (item 5) becomes cleaner — real
      SELECT COUNT(*) against the real audit_log partitioned table.
---

# M1-054: Per-scope tag preferences — /follow-tag + /unfollow-tag + tag-mode state machine

## Context

T2-B.3 — the third of three Tier-2.B DM-command tickets. Lands the
`/follow-tag` and `/unfollow-tag` command handlers (including the
`/unfollow-tag --all` confirm-gated bulk reset). The
`scope_tag (scope_kind, scope_id, tag_id)` table and the
`scope_preferences.tag_mode` column are **already on disk** in
the V7 migration (`infochat-core/src/main/resources/db/migration/V7__joins_post.sql`
lines 64 and 84-95) — this ticket only wires the handlers + the
mode-transition state machine against the existing schema.

The spec-load-bearing commitments this ticket pins:

1. **Mode is recorded explicitly.** `scope_preferences.tag_mode ∈
   {ALL, EXPLICIT}` is the spec-required discriminator (spec
   §Per-scope tag preferences explicitly forbids implicit-mode
   logic like "any rows in `scope_tag`?"). The empty `scope_tag`
   set + `tag_mode='ALL'` IS the dynamic default; row presence is
   NEVER the mode signal.
2. **Three atomic mode transitions** (spec §Per-scope tag
   preferences):
   - `ALL` + `/follow-tag <t>` → flip to `EXPLICIT`, seed `[t]`
     only (`I asked for X, only X`).
   - `ALL` + `/unfollow-tag <t>` → flip to `EXPLICIT`, seed
     `[all subscribed bootstrap_tags - t]` (`I want everything
     except X`).
   - `EXPLICIT` + `/follow-tag` / `/unfollow-tag` → add or remove
     in place; row count → 0 flips back to `ALL`.
   Every transition runs in ONE transaction (the flip + seed must
   not crash halfway leaving the scope in an inconsistent
   {tag_mode, row-set} state).
3. **`/unfollow-tag --all` is a confirm-gated bulk reset.** Deletes
   all `scope_tag` rows for the scope and sets `tag_mode='ALL'`.
   Consumes M1-051 `ConfirmStateService` under a new
   `commandName` key (`"unfollow-tag-all"`).
4. **Append-only vocabulary.** A `/follow-tag <t>` against a tag
   whose only contributing source was removed long ago is **valid**
   input — the controlled vocabulary is append-only in v1
   (`schema.md` §Sources and tags Vocabulary lifecycle). Only
   tags that NEVER entered the vocabulary are rejected, with a
   fuzzy-suggestion error.
5. **Permission**: DM = own-scope only; group = group-admin only
   (spec §Permission model Group-admin set explicitly lists
   `/follow-tag in groups`, `/unfollow-tag in groups`).
6. **Not audit-logged.** Tag-preference mutations are
   user-preference, not privileged action; the handlers write
   zero rows to `audit_log` (spec §Authorization model — the
   step-7-then-step-8 sequence applies to privileged actions, not
   user-preference state). Verified by explicit unit-test
   scenarios.

`complexity: medium` — two handlers (one with a sub-shape branch
on `--all`) + the mode-transition state machine. The state machine
has six cells (`{ALL,EXPLICIT} × {/follow-tag, /unfollow-tag,
/unfollow-tag --all}`) and the per-cell test enumeration is the
load-bearing coverage.

`risk: medium` — a buggy mode flip would silently change every
future digest's content; the round-trip IT pins the end-to-end
state machine.

`security_relevant: false` — tag preferences are user/scope
preferences with no authorization-state implications. `/redteam`
is **not** the default recommendation.

`migration_touch: false` — V7 already ships `scope_tag` and
`scope_preferences.tag_mode`.

## Acceptance

The eight items in the YAML `acceptance:` list above pin the
behavioural contract:

- Two structural items (one per handler) lock the SPI shape +
  permission gate + mode-transition state machine semantics.
- Two test-enumeration items name the per-branch `@Test`
  methods.
- The round-trip IT pins the ALL→EXPLICIT→ALL state machine
  end-to-end and the `--all` confirm-roundtrip.
- The audit-zero invariant is one item with explicit unit-test
  scenarios (`followTagWritesNoAuditRow`,
  `unfollowTagAllWritesNoAuditRow`).
- The bundle-keys + BundleLoaderTest alignment is one item.
- `mvn -B clean verify` exits 0 closes the list.

## Out-of-scope

The YAML `out_of_scope` list above enumerates thirteen exclusions.
Highlights:

- **No spec, design, or schema edits.** V7 already ships the
  table + column shape this ticket needs.
- **No new AuditAction.** Tag-preference mutations do not write
  to `audit_log` in v1.
- **No digest-scheduler change.** T2-F territory; this ticket
  writes the state, the scheduler reads it later.
- **No vocabulary-removal logic.** Append-only is v1 spec
  commitment.
- **No InboundRouter edit.** Two new CommandHandler beans land;
  the existing `Instance<CommandHandler>` iteration picks them
  up.
- **No M1-051 ConfirmStateService internals change.** The
  `/unfollow-tag --all` path consumes `remember` / `takeMatching`
  with a new `commandName` key.
- **No CommandPermissions edit.** `/follow-tag` and
  `/unfollow-tag` are intentionally outside the slow-start
  ALLOWED set per spec §Slow-start tier; M1-045's step-5 gate
  handles probation rejection.
- **No T2-B.1 or T2-B.2 surface.** Save / source-management
  handlers are separate tickets.

## Notes

- **Spec anchors (verbatim citations):**
  - `docs/spec/commands.md` §Per-scope tag preferences — the
    full mode-transition table + `/unfollow-tag --all`
    semantics + digest-query rule.
  - `docs/spec/schema.md` §Per-scope state — the
    `scope_preferences.tag_mode ∈ {ALL, EXPLICIT}` column
    description (default `ALL`).
  - `docs/spec/schema.md` §Sources and tags — the `scope_tag`
    entity description + the v1 append-only vocabulary
    lifecycle.
  - `docs/spec/commands.md` §Permission model — DM = own scope;
    group = group-admin only.
  - `docs/spec/security.md` §Slow-start tier — `/follow-tag` and
    `/unfollow-tag` Blocked during probation.
- **Design anchors:**
  - `docs/design/03-commands.md` §`/follow-tag` /
    `/unfollow-tag` — handler organisation + reply layout +
    bundle-key naming.
  - The V7 migration (file `V7__joins_post.sql` under
    `infochat-core/src/main/resources/db/migration/`) is the
    ground truth for the `scope_tag` PK shape
    (`(scope_kind, scope_id, tag_id)`) and the
    `scope_preferences.tag_mode` CHECK constraint
    (`tag_mode IN ('ALL','EXPLICIT')`). This ticket does NOT
    modify that migration.
- **State machine details.** The transactional seed for the
  ALL + /unfollow-tag path joins `source_subscription × source ×
  unnest(bootstrap_tags) × tag` to compute `[all subscribed
  bootstrap_tags - unfollowed]` and inserts the result set as
  `scope_tag` rows in one statement (`INSERT ... SELECT`). The
  alternative — fetch tags into the application, then INSERT in a
  loop — is forbidden because it widens the transaction window;
  the single-statement seed is the M1-007c precedent.
- **`tag_subscription_version` increment.** Every mutation
  increments `scope_preferences.tag_subscription_version` so the
  T2-F digest cache key changes on the next read. The increment
  is part of the same UPDATE statement that touches `tag_mode`
  (or a separate UPDATE in the same transaction when only rows
  change without a mode flip).
- **InboundRouter behavior.** No router edit — `InboundRouter.handleSlash`
  iterates `Instance<CommandHandler>` and matches by `handler.name()`.
  Two new beans land in `app.zcat.infochat.provider.command`; the
  router picks them up automatically. The M1-051 step 4.5
  confirm-cancel sweep treats `/follow-tag` and `/unfollow-tag <tag>`
  as "any other input" — when no pending confirm exists for the
  actor, the sweep is a no-op; when one DOES exist from some
  other admin command, the sweep cancels it BEFORE dispatching to
  the tag handler (the spec's `any other input cancels` semantics).
  Nothing for this ticket to wire on the router.
- **ConfirmStateService consumption.** One new keyspace entry via
  `confirmStateService.remember(actor.id, scope, "unfollow-tag-all",
  ...)` for the bulk-reset path. The `sweepPrefix` is the literal
  `"unfollow-tag-all"` so `/unfollow-tag --all confirm` matches
  via the existing `isConfirmShape(normalized, pending)` logic
  (the body starts with `/unfollow-tag ` and ends with ` confirm`
  per M1-051's relaxation rule).
- **CommandHandler interface signature.** Per the M1-035 SPI,
  `handle(ScopeRef, String body)` is the entry point; the
  `String` carries the post-normalize body. The handler parses
  its own positional arg / flag from that string. The
  M1-038 / M1-039 normalization is upstream.
- **T2-H parallel collision.** T2-B.3 does NOT add a migration;
  the only shared seam with T2-H is `en.properties` (both
  groups append). The `BundleLoaderTest` catches any rebase
  drop.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-054-per-scope-tag-preferences.md
```

## Round 1 rework

Reviewer verdict (round 1, 2026-05-24): REWORK. All checks otherwise PASS
(scope drift, test integrity, out-of-scope, negative space, acceptance,
spec conformance, parameter contracts). Two surgical-changes cleanups
remain — fix only the named items, re-run `mvn -B clean verify`, then
`/m1-tick review M1-054`.

1. Remove the unused import
   `import static org.junit.jupiter.api.Assertions.assertFalse;` from
   `infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java`.
   No `assertFalse(...)` call appears in that class — only `assertEquals`
   and `assertTrue` are used. Per
   `docs/process/engineering-rules-verbatim.md` §1 ("Clean up
   imports/variables that YOUR changes made unused").

2. Remove the unused import
   `import static org.junit.jupiter.api.Assertions.assertNotEquals;`
   from
   `infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java`.
   No `assertNotEquals(...)` call appears in that class. Per
   `docs/process/engineering-rules-verbatim.md` §1.
