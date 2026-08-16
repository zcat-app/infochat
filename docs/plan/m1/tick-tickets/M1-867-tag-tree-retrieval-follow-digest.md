---
id: M1-867
title: "Tree-aware follow-tag, digest sections, and search"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written FollowTopDigestIT.followTopRendersOneAggregatedSectionAndIncludesFutureLeaves
  (child of a 2+ decomposition — needs M1-865's tree schema and
  M1-866's seeded vocabulary; `start` converts the marker per workflow
  §0). Intended wrong behavior it states: after the v2 migration, a
  scope following the TOP node 'tech' (a tag row, so
  FollowTagCommandHandler.lookupTagId's WHERE name = ? accepts it and
  scope_tag stores it) gets an EMPTY EXPLICIT digest: the filter
  p.tags && (SELECT array_agg(t.name) FROM scope_tag st JOIN tag t ON
  t.id = st.tag_id ...) expands to ARRAY['tech'], posts store LEAVES
  ('ai', 'cybersecurity', ...), no post matches, and the digest renders
  zero posts — verified against DigestPostCollector.java:201-204 and
  SearchPostsTool.java:160-163, both of which apply the requested names
  as-is with no subtree expansion.
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by:
  - M1-865
  - M1-866
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategorizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/FollowTopDigestIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTopExpansionTest.java
  - docs/design/03-commands.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    /summary's FLAT/--full/--short behavior, the digest lead (M1-725),
    prominence ordering (M1-724), the degraded digest (D17), per-category
    delivery and counter attribution (D63), and the section cap's
    Other-never-dropped carve-out (M1-721) — all hold UNCHANGED; leaf-
    followed digests must render byte-identically to the pre-change
    output (pinned by test).
  - >-
    THE WRITE PATHS — following still stores ONE scope_tag row for the
    followed node (FollowTagCommandHandler already accepts any tag row;
    no handler edit is in scope beyond the unfollow-seed note below).
    Fan-out seeding (copying a top's leaves into scope_tag at follow
    time) is REJECTED BY DESIGN: 'top = subtree wildcard including
    future leaves (no re-follow on vocabulary growth)' requires
    READ-TIME expansion.
  - >-
    chat/RAG BREADTH (D59) — semanticSearch, getPost, getReferences, and
    the world predicates are untouched; tree expansion applies ONLY to
    the requested-tag filters, never to world visibility.
  - >-
    THE SPEC TEXT — commands.md/security.md wording is M1-869's single
    user-approvable diff; this ticket lands behavior + design-note sync
    only.
  - >-
    EDITING pre-existing tests beyond additions — DigestCategorizerTest,
    DigestRendererSectionsTest, DigestWorkerTest, the follow/unfollow
    handler tests, and SummaryCommandHandlerTest stay green unmodified
    (their fixtures are leaf-level, which is the unchanged path);
    test_plan.modifies is empty.
acceptance:
  - "FollowTopDigestIT.followTopRendersOneAggregatedSectionAndIncludesFutureLeaves (the converted reproduction) passes: a scope in EXPLICIT mode following the top node 'tech' receives a digest whose tech-subtree clusters render under ONE aggregated TECH section (not one section per leaf); the qualifying universe for an EXPLICIT digest is the leaf set under the scope's followed nodes, and threshold-3 + section-cap-8 apply at the RENDERED level (a top section is one section; a top section qualifies with >= category-min-clusters clusters whose best leaf lies under it) — and a leaf ADDED under tech AFTER the follow appears in the next digest with no re-follow (read-time expansion) (spec: docs/spec/commands.md §Per-scope tag preferences + §Periodic group digests as amended by M1-869; analysis P14; decisions D15/D59/D62/D63)."
  - "Byte-identity for the unchanged path: a digest (and /summary categorized form) over LEAF-followed or ALL-mode scopes with no followed tops renders byte-identically to the pre-change output — the existing DigestCategorizerTest/DigestRendererSectionsTest/DigestWorkerTest fixtures pass UNMODIFIED and a new test re-renders a leaf-follow fixture against golden bytes (analysis P19; spec: docs/spec/commands.md §Periodic group digests — D62 arithmetic unchanged for leaf categories)."
  - "DigestPostCollector's EXPLICIT filter expands followed nodes to their subtree leaf set at read time (the p.tags && (...) subquery at DigestPostCollector.java:201-204 resolves each followed node to itself-if-leaf or its subtree leaves); ALL-mode is untouched (no tag predicate) — pinned by an IT seeding posts under multiple tech leaves and a followed top (analysis P10/P14)."
  - "searchPosts tree-aware expansion AFTER validation, pinned by SearchPostsToolTopExpansionTest.topNameExpandsToSubtreeLeavesForTheFilter and SearchPostsToolTopExpansionTest.expandedSearchNeverSurfacesOutOfWorldOrNonReadyPosts: validateTagsKnown (SearchPostsTool.java:103-122) still validates against the live tag table (tops are rows — accepted), and queryPosts expands requested top names to their subtree leaves before the p.tags && ?::TEXT[] filter (SearchPostsTool.java:160-163); tags=['tech'] returns leaf-tagged posts in the caller's world ONLY (failure mode: a post outside the world or not READY never surfaces through the expansion — the D59 world predicate is untouched), and an unknown name still rejects the whole call with 'Unknown tag' (spec: docs/spec/security.md §Prompt-injection defenses (LLM call sites) — the searchPosts row as amended by M1-869; analysis P14)."
  - "/summary <tag> positional accepts a top: EligiblePostQuery's positional/top-3 restrictions (:350, :354) expand top arguments to subtree leaves; the '+N more' steer token for a top-followed aggregated section is the top's name; /summary with a leaf renders unchanged — pinned by tests for both levels (spec: docs/spec/commands.md §Content; analysis P14)."
  - "Mixed follows key correctly: a scope following both a top and one of its leaves gets ONE section per followed node (the top aggregated, the leaf granular), section count ~= followed-node count; a cluster's section is the most specific FOLLOWED node containing its best qualifying leaf; clusters whose best leaf lies under no followed node land in Other via the existing fold pass — pinned by a mixed-follow digest test (analysis P14)."
  - "The Others-top/null-Other distinction holds: following the 'others' top renders aggregated leaf content (personal/opinion/misc) as a REAL section, never colliding with the D62 null-tag Other bucket's literal 'other' slug (DigestRenderer.java:783, DigestSectionRepository.java:146/223, DigestDelivery.java:74/199 — untouched sentinel); a cluster whose EVERY member carries the 'personal' INGEST classification still routes to the null-tag Other bucket per M1-727 and never enters the followed-others section (the tag leaf and the classification label stay different axes) — failure-mode test: a digest containing a followed-others section, an all-personal cluster, and null-tag Other content renders three distinct outcomes with distinct slugs (analysis P14b, P14c)."
  - "The /unfollow-tag ALL->EXPLICIT seed carries node semantics, pinned by FollowTopDigestIT.unfollowSeedNodesFlowThroughExplicitDigest: INSERT_SEED_ALL_MINUS_ONE_SQL (UnfollowTagCommandHandler.java:97-110) seeds the world bootstrap_tags' NODE rows (leaf or top) minus the unfollowed node — bootstrap_tags hold mapped leaf names post-M1-866, so the existing statement is semantically correct; the test drives /unfollow-tag on a tree-migrated scope and asserts the seeded node rows flow through the (now-expanding) EXPLICIT digest with posts under them delivered (analysis P10)."
  - "Census (read-site class, mechanically enumerated): grep -n 'tags &&\\|tags @>\\|scope_tag' over infochat-provider/src/main/java returns exactly the sites this ticket touches (SearchPostsTool filter, DigestPostCollector EXPLICIT, EligiblePostQuery positional + top-3 + EXPLICIT arm, UnfollowTagCommandHandler seed) — every site disposed: expanded here / unchanged-here (ALL-mode, world predicates) — the census table lands in the ticket's Verification section at review (analysis P14)."
  - "docs/design/03-commands.md records the followed-level section rule and the read-time expansion rule (design-note sync, no docs/spec/** edit) — probe: grep -n 'followed' docs/design/03-commands.md."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/FollowTopDigestIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTopExpansionTest.java
    - >-
      Expansion + section-keying cases alongside the existing digest
      tests (new methods or a new test class — implementer's choice);
      mixed-follow, others-top/slug-distinction, byte-identity golden,
      /summary top-positional cases.
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      DigestCategorizerTest, DigestRendererSectionsTest, DigestWorkerTest,
      DigestDeliveryTest, DigestRoundtripIT, SummaryCommandHandlerTest,
      FollowTag/UnfollowTagCommandHandlerTest, TagModeRoundtripIT —
      unmodified (leaf-level fixtures are the unchanged path).
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs:
  - D15
  - D59
  - D62
  - D63
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

# M1-867: tree-aware follow-tag, digest sections, and search

## Context

After M1-865/M1-866, posts store single resolved LEAVES and the
vocabulary is a tree — but every consumer still applies tag names as-is.
Following the top `tech` stores a valid scope_tag row and yields an
EMPTY digest (posts carry `ai`, the filter asks for `tech`); searchPosts
with `tags: ['tech']` matches nothing; D62 sections have no notion of a
followed level. This ticket makes the four read surfaces tree-aware per
decision 7: top OR leaf everywhere, tops as read-time subtree wildcards
(future leaves included, no re-follow), digest sections at the followed
level, threshold-3/cap-8 at the rendered level. Shared context:
`analysis_ref:` (analysis doc, Pitfalls P14, P19; Ground truth's
consumer citations).

## Root cause

Verified: the four matching sites apply requested/followed names with no
expansion (SearchPostsTool.java:160-163; DigestPostCollector.java:
201-204; EligiblePostQuery.java:350/354; UnfollowTagCommandHandler.java:
97-110). Nothing is wrong with them for flat names — the vocabulary
changed shape underneath them (O4b: leaf-only storage concentrates all
tree-awareness at exactly these sites, by design).

## Pitfalls

- P14a: tops must never enter post.tags or D62 would double-count —
  already prevented by M1-865's leaf-only storage; this ticket must not
  reintroduce top names into stored arrays.
- P14b: the `others` top vs the null-tag Other bucket's literal `other`
  slug — distinct sections, sentinel untouched.
- P14c: M1-727's gate still wins — a cluster whose every member carries
  the `personal` ingest classification routes to the null-tag Other
  bucket regardless of tags and never enters a followed-`others`
  section (the tag leaf and the classification label are different
  axes); the all-members rule and personal-last ordering hold unchanged
  under tree sections.
- P19: fixtures calibrated to the END state — tests assume post-866 data
  (leaf-tagged posts, tree rows); leaf-level fixtures must render
  byte-identically (the golden test).
- Read-time expansion is the load-bearing choice: seed-time fan-out
  would break "future leaves without re-follow" silently.

## Approach

- **Files to touch:** SearchPostsTool (top expansion after validation),
  DigestPostCollector (EXPLICIT subtree expansion), DigestCategorizer +
  DigestRenderer (followed-level section keying; the scope's followed
  node set enters as an input), EligiblePostQuery (positional top
  expansion), UnfollowTagCommandHandler (seed statement stays; pinned
  semantics), the two new IT/test files, 03-commands design note.
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `followTopRendersOneAggregatedSectionAndIncludesFutureLeaves`, run
     RED (empty digest today).
  2. Subtree expansion primitive: one shared SQL shape (node -> leaf
     set via the parent link, depth-2 today, written depth-generally)
     reused by all four sites — the census grep proves no site is
     missed.
  3. Digest section keying: the EXPLICIT digest's qualifying universe =
     leaves under followed nodes; roll up to the followed level for
     section keys; threshold/cap at the rendered level; Other fold
     unchanged.
  4. searchPosts + /summary positional expansion (validation first,
     expansion after; world predicates untouched).
  5. Byte-identity golden test for the leaf-level path; mixed-follow and
     slug-distinction tests; unfollow-seed pin.
  6. Design-note sync (03-commands).
- **Controls to preserve (engineering-rules §10):** the tool-arg trust
  boundary (validateTagsKnown stays a live-table check; expansion never
  widens the D59 world or READY predicates — both stay INSIDE the
  query); D62/D63 controls (threshold, fold-into-Other, cap with
  Other-never-dropped, sequential per-category delivery, one counter
  outcome per slot) — all hold for the rendered-level sections; the
  `other` slug sentinel untouched; D19/D36 replay untouched (no cached
  content rewritten).
- **Pitfall→mitigation:** P14a→no stored top names (tests assert
  post.tags stays leaf-only); P14b/P14c→acceptance 7's distinction test
  (slug sentinel untouched; classifier-routed personal clusters stay in
  the null-tag bucket); P19→acceptance 2's golden bytes;
  expansion-not-fanout→acceptance 1's future-leaf leg.

## Definition of done

Every acceptance item holds: the converted reproduction passes (top
follow renders ONE aggregated section including future leaves);
byte-identity for leaf-level digests; EXPLICIT filter expansion;
searchPosts expansion with world-predicate failure-mode; /summary
positional tops; mixed follows; others-top/slug distinction; unfollow
seed pin; the read-site census table; design-note sync; `mvn verify`
green.

## Verification

- P14a → reproduction + acceptance 3 (expansion at the filter, never in
  storage).
- P14b → acceptance 7 (failure mode: a digest holding both an
  followed-others section and null-tag Other renders two distinct
  sections/slugs).
- P19 → acceptance 2 (golden bytes on the unchanged path) + existing
  digest/follow/summary tests green unmodified.
- World-predicate failure mode → acceptance 4 (expanded search never
  surfaces out-of-world or non-READY posts).
- Census → acceptance 9: `grep -n 'tags &&\|tags @>\|scope_tag'` over
  infochat-provider/src/main/java — every returned site gets a row
  (expanded here / explicitly unchanged with the reason).
- Reproduction → acceptance 1.
- acceptance 11 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — digest lead/prominence/degraded/delivery/cap
mechanics unchanged; no write-path fan-out (rejected by design); chat
breadth untouched; spec text is M1-869's; no pre-existing-test edits.
`/follow-tag`'s handler itself needs NO edit (it already accepts any
tag row — verified FollowTagCommandHandler.java:99-107); if
implementation finds it does, that is a finding to surface, not silent
scope growth.

## Census

Class-scoped (the tree-aware read-site family). Mechanical enumeration:
`grep -n 'tags &&\|tags @>\|scope_tag' infochat-provider/src/main/java`
returns the sites named in acceptance 9; each is disposed as
expanded-here or unchanged-here (ALL-mode digest, world predicates,
saved-post personal_tags — free-form, not Tier-1, excluded by the
surface-conventions rule). The completed table is recorded in this
ticket at review time.
