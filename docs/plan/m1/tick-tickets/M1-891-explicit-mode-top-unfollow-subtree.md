---
id: M1-891
title: "EXPLICIT-mode top unfollow subtracts the subtree truthfully"
status: pending
created: 2026-08-19
last_updated: 2026-08-19
flow: tick
reproduction: >-
  to-be-written
  UnfollowTagCommandHandlerTest.explicitUnfollowTopRemovesDescendantLeavesAndNamesThem
  — `start` converts the marker per workflow §0 (write the test, run it RED)
  before any fix code. The wrong behavior it states: a DM scope in EXPLICIT
  mode following two leaves under top `t` plus one unrelated leaf receives, on
  `/unfollow-tag t`, the reply.unfollow_tag.success_in_place text naming `t`
  while the DELETE (`UnfollowTagCommandHandler.java:284` via
  `DELETE_SCOPE_TAG_ONE_SQL`, :117-119) matches no row — both descendant
  leaves stay in scope_tag and tag_subscription_version is still bumped.
  Live-verified as defect D-4 (2026-08-18): EXPLICIT {ai, cybersecurity},
  `/unfollow-tag tech` replied "Removed 'tech' from your followed tags" and
  removed nothing (.scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md §D-4; plan §9
  follow/unfollow case, .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md:700-705).
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - docs/spec/commands.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The ALL → EXPLICIT seed path and INSERT_SEED_ALL_MINUS_ONE_SQL — M1-883's subtree exclusion there is landed, spec-recorded (commands.md:1118-1125), and pinned by FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves; do not re-touch it.
  - The /unfollow-tag --all prompt/confirm wipe path (executeUnfollowTagAllTransaction).
  - /follow-tag behavior, M1-867 read-time subtree expansion, digest/search/summary read surfaces.
  - Any migration or backfill of existing scope_tag / scope_preferences state.
  - Adding audit rows for tag-preference mutations — the no-audit rule (handler javadoc :48-51, spec §Authorization model) stands.
  - Rewording the existing REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL / SUCCESS_IN_PLACE / FLIPS_BACK_TO_ALL templates; new keys only.
acceptance:
  - "UnfollowTagCommandHandlerTest.explicitUnfollowTopRemovesDescendantLeavesAndNamesThem (the converted reproduction) passes: EXPLICIT mode, followed set {leaf-A, leaf-B under top T, unrelated leaf U} — `/unfollow-tag T` deletes exactly the two descendant rows, keeps U, keeps tag_mode=EXPLICIT, bumps tag_subscription_version exactly once, and the reply names T and the actually-removed leaf names — verification: named Testcontainers handler test; mutating the delete predicate back to `tag_id = ?` (one-row subtraction) leaves both leaves followed and fails this test (P1/P3/P8; spec: docs/spec/commands.md §Per-scope tag preferences as amended by this ticket)."
  - "Failure mode / truthful no-op: UnfollowTagCommandHandlerTest.explicitUnfollowMatchingNothingRepliesTruthfullyAndMutatesNothing passes — EXPLICIT mode, followed set {U} only, `/unfollow-tag T` (a real vocabulary top with no followed rows under it) replies with the new nothing-matched template naming the normalized tag, deletes zero rows, does NOT bump tag_subscription_version, and does NOT change tag_mode — verification: named handler test asserting reply key, row count, version, and mode before/after (P2; spec: docs/spec/commands.md §Per-scope tag preferences as amended)."
  - "UnfollowTagCommandHandlerTest.explicitUnfollowTopEmptyingTheSetFlipsBackToAllAndNamesTheRemoved passes: EXPLICIT mode, followed set = exactly the leaves under top T — `/unfollow-tag T` removes them, flips tag_mode to ALL (the zero-rows flip, commands.md:1127-1128 preserved), bumps the version once, and the flip reply names the removed tags — verification: named handler test (P3; spec: docs/spec/commands.md §Per-scope tag preferences)."
  - "UnfollowTagCommandHandlerTest.explicitUnfollowOfStoredTopRowRemovesTheWildcardRow passes: EXPLICIT mode, followed set {stored top row T (a legal wildcard follow per commands.md:1122), unrelated leaf U} — `/unfollow-tag T` removes the T row, keeps U, stays EXPLICIT — verification: named handler test (P1's second half: the subtree delete covers the requested node itself, so stored-top and descendant-leaf cases share one predicate)."
  - "Bundle parity: every new BundleKeys constant this ticket adds has a non-empty, localized value in all five bundles (en, cs, es, ru, tr) — verification: the existing bilateral BundleLoaderTest completeness check passes unmodified (P6)."
  - "Spec amendment rides this diff (user pre-approved the behavior change 2026-08-19; engineering-rules §12 exact-wording approval at implementation): docs/spec/commands.md §Per-scope tag preferences' EXPLICIT-mode bullet (:1126-1128) is amended to rule-text only — no dates, ticket IDs, or report citations — recording that EXPLICIT-mode `/unfollow-tag <tag>` removes the stored row for the tag AND every stored row for a descendant of it, that a nothing-matched invocation changes nothing and says so, that a successful removal replies with what was actually removed, and that the zero-rows → ALL flip still applies — verification: user approval of the exact proposed text before the edit lands, plus an rg probe over the amended lines showing rule-text only (P7)."
  - "Controls preserved unmodified: existing UnfollowTagCommandHandlerTest cases (DM/group authorization gate, unknown-tag fuzzy-suggestion error, no-inbound-reflection reply, normalization, version-on-mutation, no-audit, --all prompt/confirm legs) and FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves / unfollowSeedNodesFlowThroughExplicitDigest pass UNCHANGED — verification: those test classes green with zero edits (P5; engineering-rules §10)."
  - "mvn verify from the repository root is green — verification: command exit 0."
test_plan:
  adds:
    - UnfollowTagCommandHandlerTest.explicitUnfollowTopRemovesDescendantLeavesAndNamesThem
    - UnfollowTagCommandHandlerTest.explicitUnfollowMatchingNothingRepliesTruthfullyAndMutatesNothing
    - UnfollowTagCommandHandlerTest.explicitUnfollowTopEmptyingTheSetFlipsBackToAllAndNamesTheRemoved
    - UnfollowTagCommandHandlerTest.explicitUnfollowOfStoredTopRowRemovesTheWildcardRow
  modifies: []
  preserves:
    - all existing UnfollowTagCommandHandlerTest cases (authorization, fuzzy-error, no-reflection, normalization, version, no-audit, --all legs)
    - FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves and unfollowSeedNodesFlowThroughExplicitDigest (ALL-mode path untouched)
    - BundleLoaderTest bilateral completeness check
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §Authorization model
decision_refs:
  - D59
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-891: EXPLICIT-mode top unfollow subtracts the subtree truthfully

## Context

Defect D-4 (live-verified 2026-08-18, `.scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md` §D-4): with a scope in EXPLICIT tag_mode following leaves `ai` and `cybersecurity`, `/unfollow-tag tech` replies "Removed 'tech' from your followed tags" while removing nothing — the top has no `scope_tag` row and the EXPLICIT branch deletes by exact tag id only. The user directed the fix (2026-08-19, option b): EXPLICIT-mode `/unfollow-tag <top>` performs the subtree subtraction it promises — removing the stored top row if present AND every descendant leaf row — matching the ALL-mode semantics M1-883 landed (commands.md:1118-1125) and the user mental model; the zero-rows → ALL flip (commands.md:1127-1128) still applies; a nothing-matched invocation replies truthfully with no fake success; the reply names what was actually removed; all five locale bundles. The EXPLICIT bullet's current "add or remove the row in place" wording (commands.md:1126-1128) cannot record this, so a spec amendment rides the diff with §12 exact-wording approval at implementation.

## Root cause

Verified against the code: the EXPLICIT branch of `executeUnfollowTagPositionalTransaction` (`UnfollowTagCommandHandler.java:278-296`) calls `deleteOneScopeTag`, whose `DELETE_SCOPE_TAG_ONE_SQL` (:117-119) is `DELETE FROM scope_tag WHERE scope_kind = ? AND scope_id = ? AND tag_id = ?` — one exact-id row. A top node followed only through its leaves has no such row, so the delete is a no-op; the branch then unconditionally bumps `tag_subscription_version` (:292) and returns the `reply.unfollow_tag.success_in_place` template (:293-295, en.properties:515) regardless of the deleted-row count. The ALL-mode branch already contains the correct subtree machinery — `INSERT_SEED_ALL_MINUS_ONE_SQL`'s recursive CTE over `tag.parent_name` (:108-114), landed by M1-883 — but it runs only on the ALL → EXPLICIT transition. Proven; nothing remains for implementation-time discovery beyond exact reply wording.

## Pitfalls

- P1: one-id subtraction persists in disguise — a fix that deletes only descendant leaf rows but forgets a stored top wildcard row (top follows ARE storable, commands.md:1122; M1-867 stores one row per followed node), or vice versa. The subtree set must include the requested node itself, exactly like the M1-883 CTE (`SELECT name FROM tag WHERE name = ?` is the anchor of the recursive walk, :110). Mutation that must fail a test: delete predicate reduced to `tag_id = ?`.
- P2: fake success on a zero-row delete — today's branch replies success and bumps the version even when the DELETE matched nothing (:284-295). The truthful fix must make a nothing-matched invocation change NOTHING: no rows, no version bump, no mode change, and a dedicated nothing-matched reply. This is D-4's reply half; a fix that only widens the DELETE leaves a silent-success path whenever a top's subtree is partially followed elsewhere or a leaf is unfollowed twice.
- P3: zero-rows → ALL flip interaction — a subtree delete that empties the followed set MUST flip to ALL (commands.md:1127-1128, preserved); a subtree delete that leaves residual rows (unrelated leaves, other tops' fallback leaves such as `other-sport`, or a partially-followed sibling subtree) must NOT flip and bumps the version exactly once. Partially-followed subtree (`{ai}` only, unfollow `tech`) empties the set and flips — both directions need a pinning test.
- P4: reply content trust — the names interpolated into the new replies must come from the `tag` table rows actually deleted (trusted, normalized vocabulary), never from raw inbound bytes; the `{0}` requested-tag token is the TagNormalizer-normalized name, matching the existing no-reflection precedent (`unfollowTagUnknownTagReplyDoesNotReflectInboundText`; security.md:369-375 friendly-error reflection surface; M1-656).
- P5: preserve the path's controls (engineering-rules §10) — this is an adapter-inbound command path: TagNormalizer + controlled-vocabulary lookup + fuzzy-suggestion unknown-tag error (:236-247), DM/group-admin authorization gate (:164-191), zero audit rows (:48-51), the upsert + SELECT ... FOR UPDATE transaction shape (:261-262), exactly-once version bump per mutation, `ContactIds.redact` on exception paths, and no D59 world-source join on the EXPLICIT branch (it operates on stored rows only — do not import the ALL-branch's world predicate). Existing tests pin each; they must pass unmodified.
- P6: five-locale bundle parity — every new BundleKeys constant needs a non-empty localized value in en/cs/es/ru/tr or the bilateral BundleLoaderTest completeness check fails the build (BundleLoaderTest javadoc, M1-474 teeth).
- P7: spec-amendment discipline (engineering-rules §12) — the amended EXPLICIT bullet states rules only: no dates, ticket IDs, or report citations in spec prose (this ticket and the D-4 record carry the history); the exact wording goes to the user for approval before the edit lands. §11 companion: the handler's class/SQL comments describing the EXPLICIT branch (:85-95 region and the branch comment :279-283) must be re-read as claims about the NEW code and corrected, not left asserting row-in-place semantics.
- P8: fixture discrimination (the M1-785 lesson) — every new fixture must pin a state that differs between the rejected options and the chosen design: the reproduction's `{leaf-A, leaf-B, U}` set fails under one-row subtraction AND under fake-success; the nothing-matched fixture fails under any unconditional-success reply; a fixture that only checks "reply contains tag name" would pass under the old code and is decoration.

## Approach

Derived from `docs/spec/commands.md §Per-scope tag preferences` (mode-transition table, :1113-1128) extended by this ticket's amendment, with M1-883's landed ALL-mode subtree exclusion (:1118-1125) as the semantic template.

- **Files to touch:** `UnfollowTagCommandHandler.java` (EXPLICIT branch), `BundleKeys.java` + the five bundle properties (new reply keys), `docs/spec/commands.md` (EXPLICIT bullet amendment, §12-approved wording), `UnfollowTagCommandHandlerTest.java` (four new cases; existing helpers `seedTop`/`seedLeafUnder`/`seedScopeTag` already exist, :550-576/:649-658).
- **Steps in implementation order:**
  1. Convert the reproduction marker per workflow §0: write the four named tests and run them RED. The reproduction states D-4 exactly; the no-op, flip-back, and stored-top cases pin the edges. Fixtures use tree rows via the existing `seedTop`/`seedLeafUnder` helpers.
  2. Replace `deleteOneScopeTag` on the EXPLICIT branch with a subtree delete that returns what it removed, reusing the M1-883 recursive-CTE shape over `tag.parent_name` anchored at the normalized requested name: `DELETE FROM scope_tag st USING tag t WHERE st.tag_id = t.id AND st.scope_kind = ? AND st.scope_id = ? AND t.name IN (WITH RECURSIVE subtree ... ) RETURNING t.name` — one statement, inside the existing FOR UPDATE transaction, so the removed-name list and the post-delete count are consistent (P1, P5).
  3. Branch on the result: empty removed-set → nothing-matched reply, no version bump, no mode change (P2); non-empty → post-delete count, zero → flip to ALL + flip reply naming the removed set, else bump version once + success reply naming the removed set (P3). When the removed set is exactly the requested row (plain leaf unfollow, or a stored top row with no followed descendants), the existing SUCCESS_IN_PLACE / FLIPS_BACK_TO_ALL keys and their tests stay valid — keep those paths byte-identical (§1 surgical, §8 unmodified-tests).
  4. Add the new bundle keys (nothing-matched; subtree success; subtree flip-back) with `{0}` = normalized requested tag and `{1}` = the removed tag names, localized in all five bundles (P4, P6).
  5. Amend the commands.md EXPLICIT bullet with user-approved rule-text-only wording (P7); correct the now-stale branch comments (§11).
- **Controls to preserve (§10):** enumerated in P5 — normalization/lookup/fuzzy-error, authorization gate, no-audit, transaction shape, exactly-once version bump on mutations only, redacted exceptions, no D59 join on this branch. Their pinning tests are acceptance 7 and must not be edited.
- **Pitfall→mitigation:** P1→step 2's self-inclusive CTE + acceptance 1/4; P2→step 3's empty-set arm + acceptance 2; P3→step 3's count gate + acceptance 3; P4→step 4's tag-table-sourced names + existing no-reflection test; P5→acceptance 7 unmodified controls; P6→acceptance 5 parity check; P7→acceptance 6 approval gate; P8→step 1's discriminating fixtures.

## Definition of done

Every YAML acceptance item holds, each verified by its named test or probe: the converted reproduction passes with the descendant leaves removed and named; the nothing-matched invocation is a truthful no-op (no rows, no version bump, no mode flip); the emptying subtree delete flips to ALL and names the removed set; a stored top wildcard row is removed; all five bundles carry the new keys (BundleLoaderTest green); the spec amendment lands with user-approved rule-text-only wording; every pre-existing handler/digest/bundle test passes unmodified; `mvn verify` is green.

## Verification

- P1 → `UnfollowTagCommandHandlerTest.explicitUnfollowTopRemovesDescendantLeavesAndNamesThem` (feeds EXPLICIT {leaf-A, leaf-B under T, U}; asserts both descendant rows gone, U kept, reply names T + removed leaves) and `.explicitUnfollowOfStoredTopRowRemovesTheWildcardRow` (stored top row removed). Mutation caught: `tag_id = ?` predicate leaves the descendants followed and fails the row assertions.
- P2 → failure mode: `UnfollowTagCommandHandlerTest.explicitUnfollowMatchingNothingRepliesTruthfullyAndMutatesNothing` feeds the handler a real vocabulary top with zero followed rows under it and asserts the protected behavior — the reply must NOT be a success template, no rows are deleted, the version never bumps, and the mode never changes. Mutations caught: unconditional success reply, unconditional version bump.
- P3 → `UnfollowTagCommandHandlerTest.explicitUnfollowTopEmptyingTheSetFlipsBackToAllAndNamesTheRemoved` (followed set = exactly T's leaves → ALL flip, one version bump, removed names in reply); the reproduction's U-survives assertion pins the must-NOT-flip direction (a flip with residual rows would wrongly widen the digest to ALL).
- P4 → existing `unfollowTagUnknownTagReplyDoesNotReflectInboundText` (unmodified) plus the reproduction's assertion that the reply contains only the normalized tag-table names — the reply must never contain raw inbound bytes.
- P5 → acceptance 7: existing authorization/fuzzy/normalization/version/no-audit/--all tests and the two FollowTopDigestIT cases green with zero edits.
- P6 → acceptance 5: BundleLoaderTest bilateral completeness — fails if any new key is absent or empty in any of the five bundles.
- P7 → acceptance 6: user approval of exact spec text + rg probe for rule-text-only (no dates/ticket IDs/report citations).
- P8 → the reproduction and the P2 failure-mode test are the discriminating fixtures: each names a concrete mutation of this diff's own code that fails it (one-row predicate; unconditional success reply); a fixture asserting only "reply mentions the tag" is non-vacuously rejected at review by §8 assertion-adequacy.
- Acceptance 8 → `mvn verify` from repo root, exit 0.

## Out-of-scope

The ALL → EXPLICIT seed (M1-883's SQL and spec wording stand untouched), the `--all` confirm wipe, /follow-tag, all read-time expansion surfaces, any migration/backfill, any audit-row addition, and rewording of existing reply templates. No pre-existing test is modified: the new truthful-no-op behavior changes only paths no current test exercises (every existing EXPLICIT-mode test performs a real matching removal — verified `UnfollowTagCommandHandlerTest.java:231-273, 440-469`), so `test_plan.modifies` is legitimately empty; if implementation finds a pre-existing test pinning fake-success behavior, that is a finding to surface, not a silent edit.

## Census

Class-scoped: every production site that matches a requested or followed tag against stored `scope_tag` rows. Mechanical enumeration, re-run at start: `rg -n "FROM scope_tag|INTO scope_tag" infochat-provider/src/main/java`. Every returned site is disposed below (verified 2026-08-19).

| Site | Disposition |
|---|---|
| UnfollowTagCommandHandler.java:118 (`DELETE_SCOPE_TAG_ONE_SQL`) | **Fixed here** — becomes the self-inclusive subtree delete on the EXPLICIT branch |
| UnfollowTagCommandHandler.java:122 (`DELETE_SCOPE_TAG_ALL_SQL`) | **Unchanged** — the `--all` bulk reset deletes by scope only; no tag matching by design |
| UnfollowTagCommandHandler.java:97 (`INSERT_SEED_ALL_MINUS_ONE_SQL`, subtree CTE :108-114) | **Unchanged** — ALL → EXPLICIT seed exclusion landed by M1-883; pinned by FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves |
| UnfollowTagCommandHandler.java:83 (`COUNT_SCOPE_TAG_FOR_SCOPE_SQL`) | **Unchanged** — a row count for the flip gate, not a tag matcher |
| FollowTagCommandHandler.java:106 (`INSERT INTO scope_tag`) | **Unchanged** — follow adds the one followed-node row in place; no subtraction semantics (M1-867) |
| GetTagsCommandHandler.java:66, ExportDataCollector.java:85 (scope_tag reads) | **Unchanged** — read-only listing/export over stored rows; no matching semantics (M1-867 census) |
| EligiblePostQuery.java:442/:465, TagTreeExpansion.java:37/:89 (scope_tag reads) | **Unchanged** — /summary read-time expansion surfaces owned by M1-867; this ticket changes no read path |
