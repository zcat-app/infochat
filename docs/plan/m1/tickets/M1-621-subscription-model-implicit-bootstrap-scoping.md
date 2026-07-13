---
id: M1-621
title: "Subscription model: implicit-bootstrap corpus, private custom sources, command surface + spec amendment"
status: pending
created: 2026-07-13
last_updated: 2026-07-13
escalations:
  - date: 2026-07-13
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED. REASON: No implementable outline exists within
      files_budget: 18. The ticket-named surface alone is non-negotiable at
      15-16 production/spec files, and the acceptance-named tests add a floor
      of 8 more even when folded into one combined retrieval-scope IT, giving
      23 minimum versus 18. Worse, ground truth shows the "four query classes"
      list is incomplete for the ticket's own "end-to-end as one working
      feature" claim: the periodic group digest does NOT run through
      EligiblePostQuery (which serves /summary) but through
      DigestPostCollector's own two subscription-scoped statements; and
      GetPostTool, SaveCommandHandler, UnfollowTagCommandHandler (ALL->EXPLICIT
      seed joins source_subscription), and SummaryCommandHandler's empty branch
      all carry the same subscriptions-only world-predicate — left unchanged,
      chat search returns bootstrap posts whose uid then resolves to null in
      get_post and fails /save. Closing those coherence gaps pushes the honest
      total to roughly 33-36 files. SUGGESTED ESCALATION: refine (bump
      files_budget to ~34, rewrite acceptance item 2 to name
      DigestPostCollector, add the five missing predicate sites plus their
      tests to test_plan.modifies, decide the /summary empty-branch semantics);
      not decompose, because the predicate flip is atomic — any split produces
      broken intermediate states (search-visible but unfetchable posts).
blocked_by: []
files_budget: 36
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    User-facing GUIDANCE / onboarding copy — the welcome message, the
    "chat still searches everything" clarifier on /follow-tag, and the
    empty-digest nudge. Those explanatory strings are M1-622 (blocked on this
    ticket). This ticket owns behaviour and the FUNCTIONAL reply strings its own
    commands need (e.g. the /list-sources rows, an /unfollow-source confirmation),
    but not the educational hints that teach the new model.
  - >-
    The post-tagging pipeline. post.tags (the LLM tagger's per-post content tags)
    and source.bootstrap_tags (the deterministic fallback + vocabulary seed) are
    untouched; /follow-tag keeps filtering the DIGEST on post.tags. This ticket
    only DECOUPLES the RAG keyword tool from follow-tag; it does not change how
    tags are produced or how the digest applies them.
  - >-
    Group auto-subscribe as a distinct mechanism, registration-time behaviour, and
    the asset/price path. Implicit bootstrap applies uniformly to every scope
    (DM and group) through the query predicate — no per-scope fan-out, no
    source_subscription backfill, no change to /approve-group, the D45 probation
    flow, or any /zcash-style command.
acceptance:
  - >-
    A new Flyway migration V59 adds a NOT NULL `source.source_origin` column,
    CHECK (source_origin IN ('bootstrap','user')), DEFAULT 'user' (the
    privacy-safe default — a forgotten insert is private, not public), backfilling
    existing rows to 'bootstrap' (they are operator-seeded), and creates the
    per-scope bootstrap-exclusion table (a separate table, NOT a kind-column on
    source_subscription — see Notes). BootstrapLoader sets 'bootstrap' on its
    upsert INSERT and its ON CONFLICT update promotes an existing 'user' row to
    'bootstrap' (operator seeding makes a source public); the /add-source insert
    path sets 'user'. A migration/repository test asserts the column, CHECK,
    default, backfill, the exclusion table, that a bootstrap-loaded source is
    'bootstrap' while an /add-source'd source is 'user', and the user→bootstrap
    promote on upsert conflict. The migration applies cleanly on a fresh DB and
    a prod-shaped DB.
  - >-
    Retrieval + digest scoping becomes "source.source_origin='bootstrap' AND the
    source is not excluded by this scope, OR source_id IN (this scope's
    source_subscription)" across the FULL world-predicate surface:
    EligiblePostQuery (serves /summary), DigestPostCollector (the periodic
    digest — its POSTS_ALL_SQL / POSTS_EXPLICIT_SQL), SemanticSearchTool,
    SearchPostsTool, GetReferencesTool, GetPostTool, and SaveCommandHandler's
    SELECT_POST_SQL — no post may be search-visible but unfetchable (get_post
    and /save must resolve every uid retrieval can return).
    UnfollowTagCommandHandler's ALL→EXPLICIT seed
    (INSERT_SEED_ALL_MINUS_ONE_SQL) seeds from the same world predicate, not
    source_subscription alone. SummaryCommandHandler's zero-subscription steer
    becomes an empty-world check: it fires only when the scope has zero
    non-excluded bootstrap sources AND zero subscriptions (functional wording
    only; educational copy stays M1-622). Named tests: (a) a bootstrap-origin
    post is retrieved by a scope with NO subscriptions, via both
    EligiblePostQuery and DigestPostCollector; (b) a user-origin (custom) post
    is retrieved by its subscriber but NOT by a different scope (privacy);
    (c) a subscription-less scope can get_post and /save a bootstrap post
    surfaced by search; (d) /unfollow-tag from ALL mode on a subscription-less
    scope seeds the EXPLICIT set from the bootstrap world minus the unfollowed
    tag (the digest does not zero out).
  - >-
    RAG is decoupled from /follow-tag: SearchPostsTool no longer applies the
    scope_tag / tag_mode filter (SemanticSearchTool and GetReferencesTool already
    don't). A test proves a scope in EXPLICIT tag mode with a narrow followed-tag
    set still gets keyword hits outside those tags. EligiblePostQuery (/summary)
    and DigestPostCollector (periodic digest) KEEP the follow-tag filter — tests
    prove both still narrow. SearchPostsToolTest's pre-existing EXPLICIT-mode
    narrowing assertions (including its "EXPLICIT searchPosts matches
    EligiblePostQuery" parity assertion) are rewritten to the new contract: the
    two surfaces intentionally diverge.
  - >-
    /list-sources (bare, non-admin) lists the bootstrap catalogue PLUS the
    caller's own custom (origin='user') subscriptions, and never another scope's
    custom sources. The bot-admin --all / --include-deleted forms are unchanged. A
    handler test proves a subscription-less caller sees the bootstrap sources, and
    a caller does not see a custom source added by a different scope.
  - >-
    /unfollow-source works on any source in the caller's world: unfollowing a
    CUSTOM source removes the caller's subscription (as today); unfollowing a
    BOOTSTRAP source records a per-scope EXCLUSION so that source drops from the
    caller's retrieval/digest — and only the caller's. /follow-all-sources is
    repurposed to "re-include all bootstrap (clear this scope's exclusions)" so it
    retains a coherent role rather than silently no-opping; it stays in the
    command catalogue (no CommandCatalogueParityTest / closed-set change). Tests
    prove: exclude-a-bootstrap-source hides it for that scope only; re-include
    restores it; custom unfollow is unchanged.
  - >-
    Spec updated: docs/spec/commands.md §Source management + §Per-scope tag
    preferences describe the model — bootstrap sources are an implicit public
    corpus every scope retrieves (opt-out via /unfollow-source), custom sources
    are private to their subscribers, /follow-tag narrows the DIGEST only while
    chat/RAG stays broad. A new decision D59 in docs/spec/decisions.md records
    "implicit bootstrap corpus" and explicitly supersedes the prior "no
    auto-subscribe at registration / explicit per-scope opt-in" stance.
    docs/spec/schema.md §Sources and tags gains source_origin and the exclusion
    representation. All parity tests stay green; `mvn verify` is green.
test_plan:
  adds:
    - >-
      A migration/repository test: V59 applies on a fresh + prod-shaped DB;
      source_origin NOT NULL/CHECK/'user'-default; existing rows backfilled to
      'bootstrap'; bootstrap-loaded='bootstrap', add-source'd='user'.
    - >-
      A retrieval-scope test across EligiblePostQuery + the three RAG tools:
      bootstrap-origin visible to a subscription-less scope; a custom source's
      posts visible only to its subscriber.
    - >-
      A RAG/follow-tag decoupling test: SearchPostsTool returns hits outside a
      narrow EXPLICIT followed-tag set; EligiblePostQuery still narrows the digest.
    - >-
      Command tests: /list-sources shows bootstrap + own customs and hides other
      scopes' customs; /unfollow-source excludes a bootstrap source for one scope
      only; /follow-all-sources re-includes (clears exclusions).
  modifies:
    - >-
      BootstrapLoader.java + the /add-source source-insert path (origin), and
      their tests.
    - >-
      EligiblePostQuery.java, SemanticSearchTool.java, SearchPostsTool.java,
      GetReferencesTool.java (scoping predicate + exclusion; SearchPostsTool drops
      the follow-tag filter), and their tests.
    - >-
      ListSourcesCommandHandler.java, UnfollowSourceCommandHandler.java,
      FollowAllSourcesCommandHandler.java (repurpose), and their tests.
    - >-
      DigestPostCollector.java (POSTS_ALL_SQL / POSTS_EXPLICIT_SQL world
      predicate), GetPostTool.java, SaveCommandHandler.java (SELECT_POST_SQL),
      UnfollowTagCommandHandler.java (ALL→EXPLICIT seed world),
      SummaryCommandHandler.java (empty-world steer), and their tests.
    - >-
      bundles/en.properties + cs.properties (+ BundleKeys if a new key is
      added) for the functional reply strings this ticket's commands need (D43
      bilateral keyset).
    - >-
      docs/spec/commands.md, docs/spec/decisions.md (new D59), docs/spec/schema.md.
  preserves:
    - all tests currently green on main
    - >-
      the post-tagging pipeline (post.tags, bootstrap_tags fallback) and the
      DIGEST's follow-tag narrowing (EligiblePostQuery keeps it)
    - >-
      CommandCatalogueParityTest and the LlmOutputSanitizer closed-set parity
      (no command added or removed — /follow-all-sources is repurposed, not
      dropped)
    - >-
      /add-source's private-subscription behaviour (adder-only) and the
      bot-admin /list-sources --all / --include-deleted forms
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D5
  - D7
  - D15
reviews: {}
revisions:
  - date: 2026-07-13
    reason: >-
      outline-fail refine (user-directed via /m1-tick run escalation menu,
      "refine, apply changes"): the plan phase's ground-truth audit found
      files_budget: 18 infeasible (23-file compressed minimum for the named
      surface alone) and the "four query classes" world-predicate surface
      incomplete — the periodic digest runs through DigestPostCollector, not
      EligiblePostQuery (which serves /summary), and GetPostTool /
      SaveCommandHandler / UnfollowTagCommandHandler / SummaryCommandHandler
      carry the same subscriptions-only predicate (unchanged, they would make
      bootstrap posts search-visible but unfetchable and let /unfollow-tag
      zero a fresh scope's digest).
    snapshot: |
      files_budget (pre-refine): 18.
      acceptance item 2 (pre-refine, gist): predicate change scoped to
        "EligiblePostQuery (digest), SemanticSearchTool, SearchPostsTool,
        GetReferencesTool (both post endpoints)" with tests (a) bootstrap
        visible to a subscription-less scope, (b) custom private to subscriber.
      acceptance item 3 (pre-refine, gist): SearchPostsTool drops scope_tag /
        tag_mode; "EligiblePostQuery (the DIGEST) KEEPS the follow-tag filter".
      test_plan.modifies (pre-refine): BootstrapLoader + add-source path; the
        four query classes; the three source command handlers; the three spec
        docs. No DigestPostCollector / GetPostTool / SaveCommandHandler /
        UnfollowTagCommandHandler / SummaryCommandHandler / bundle entries.
      Notes (pre-refine): "Exclusion representation is a plan-phase decision —
        a boolean/kind on source_subscription, or a separate table."
      resolution: files_budget 18->36 (plan-writer's honest range 33-36);
        acceptance item 2 rewritten to the full world-predicate surface (adds
        DigestPostCollector, GetPostTool, SaveCommandHandler SELECT_POST_SQL,
        the UnfollowTagCommandHandler ALL->EXPLICIT seed, and the
        SummaryCommandHandler empty-world steer semantics) plus tests (c)/(d);
        item 3 gains the SearchPostsToolTest parity-assertion rewrite and
        names both digest surfaces; item 1 gains the exclusion table and the
        BootstrapLoader user->bootstrap promote-on-conflict; test_plan.modifies
        gains the five predicate sites + bundles; exclusion representation
        pinned to a separate table (a kind-column would poison the ~10
        untouched source_subscription subqueries). complexity / risk /
        round_cap / security_relevant / migration_touch unchanged.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-621: Subscription model — implicit-bootstrap corpus, private customs, command surface

## Context

The v1 subscription/tag model has confusing overlap (source subscription vs.
`/follow-tag`), no non-admin source browsing, and a real UX gap: a fresh user's
feed is empty until they `/follow-all-sources`. A redesign was decided 2026-07-13
— full write-up `docs/plan/subscription-model-redesign.md`, tracked as a v1 gap
in `docs/plan/v1-verification-truth.md` §6b. The converged model: **bootstrap
sources are an implicit public corpus every scope retrieves (opt-out per source);
custom (user-added) sources are private to their subscribers; `/follow-tag` is
the one user knob and narrows the DIGEST only — chat/RAG stays broad.**

This ticket delivers that model **end-to-end as one working feature**: the
`source.source_origin` discriminator + per-scope exclusions (schema), the
implicit-bootstrap scoping across every world-predicate query site (retrieval,
both digest surfaces, uid resolution, the `/unfollow-tag` seed — the full list
is acceptance item 2), the affected command surface (`/list-sources` shows
bootstrap + own customs, `/unfollow-source` opts a scope out of a bootstrap
source, `/follow-all-sources` re-includes, `/add-source` marks custom), the
RAG/`follow-tag` decoupling, and the spec amendment (D59). The user-facing *guidance copy* that teaches the model is the one
dependent piece split out — **M1-622** (`blocked_by` this ticket) — because it's
pure bilingual string work, independently reviewable, and ties to the pre-release
message-audit gate.

## Acceptance

See the YAML `acceptance:` list. In prose: add `source.source_origin`
(`bootstrap`|`user`, default `user`, existing→`bootstrap`) + a per-scope
exclusion table via Flyway V59; mark origin in BootstrapLoader /
`/add-source` (with user→bootstrap promote on upsert conflict); change every
world-predicate query site — EligiblePostQuery, DigestPostCollector, the three
RAG search tools, GetPostTool, SaveCommandHandler, the UnfollowTagCommandHandler
seed, SummaryCommandHandler's empty-branch steer — to "bootstrap (not excluded)
OR my subscriptions" so bootstrap is implicit and customs stay private; make
`/list-sources` show bootstrap + own customs (never others'), `/unfollow-source`
exclude a bootstrap source (or remove a custom subscription), `/follow-all-sources`
clear exclusions; decouple SearchPostsTool from `/follow-tag` while both digest
surfaces keep it; and amend `commands.md`, `decisions.md` (D59), `schema.md`.
Parity tests and `mvn verify` stay green.

## Out-of-scope

Prose in the YAML `out_of_scope:`. In short: the educational guidance copy is
**M1-622**, not here (this ticket owns behaviour + its own functional reply
strings); no post-tagging change; no registration/group-approval/asset change.

## Notes

- **Privacy is the load-bearing property.** The predicate must be exactly
  "`source_origin='bootstrap'` AND not-excluded-by-this-scope, OR `source_id IN`
  (this scope's subscriptions)". A custom source (`origin='user'`) is reachable
  only via a `source_subscription` row, which `/add-source` writes only for the
  adder — so it never surfaces to another scope. The `'user'` column default is
  deliberate fail-closed.
- **Exclusion representation is PINNED (refine 2026-07-13): a separate
  per-scope exclusion table**, created in V59 — NOT a boolean/kind column on
  `source_subscription`, which would silently change the semantics of the ~10
  other `source_id IN (SELECT … FROM source_subscription)` subqueries this
  ticket does not touch. The behaviour stands: a scope opts out of one
  bootstrap source; only that scope is affected; re-include restores it. Every
  world-predicate site applies it uniformly.
- **Dependent ticket M1-622** (guidance copy) is filed alongside this and
  `blocked_by` it; its welcome/hint strings describe this ticket's behaviour, so
  it lands after. Do not add those educational strings here.
- **Sizing:** this is a large cohesive feature. `files_budget: 36` was set by
  the 2026-07-13 outline-fail refine from the plan phase's ground-truth
  enumeration (~21 production/spec files + ~13–15 test files; the plan-writer's
  honest range was 33–36). If the diff genuinely cannot land as one reviewable
  change, decompose at implementation via the budget-breach escalation — with
  real knowledge — rather than pre-splitting.
- **Adjacent pattern:** mirror the existing `tag.source_origin='bootstrap'`
  (BootstrapLoader, `tag` table) for `source.source_origin`. The
  world-predicate sites already share the `source_id IN (source_subscription …)`
  sub-select shape — the change is a consistent disjunction + exclusion across
  every site named in acceptance item 2.
- **Determinism (D19) unaffected:** the predicate is deterministic SQL; no LLM in
  retrieval.
- Design authority: `docs/plan/subscription-model-redesign.md`.

## OUTLINE FAILED (2026-07-13, plan-writer, escalation reason: outline-fail — ADDRESSED by the same-day refine; see `revisions[0]`. Kept for the evidence pointers.)

REASON: No implementable outline exists within `files_budget: 18`. The
ticket-named surface alone is non-negotiable at 15–16 production/spec files
(V59 migration; BootstrapLoader; SourceUpsertService; EligiblePostQuery;
SearchPostsTool; SemanticSearchTool; GetReferencesTool;
ListSources/UnfollowSource/FollowAllSources handlers; en+cs bundle values
(+BundleKeys); commands.md, decisions.md, schema.md), and the acceptance-named
tests add a floor of 8 more even when folded into one combined retrieval-scope
IT (SearchPostsToolTest must additionally be modified because its pre-existing
EXPLICIT-mode assertions — lines 220–302 — invert under acceptance item 3),
giving 23 minimum versus 18. Worse, ground truth shows the "four query classes"
list is incomplete for the ticket's own "end-to-end as one working feature"
claim: the periodic group digest does NOT run through EligiblePostQuery (which
serves `/summary`) but through `DigestPostCollector`'s own two
subscription-scoped statements, so acceptance item 2 as written leaves the
empty-digest cliff unfixed in the one place users see it; and `GetPostTool`,
`SaveCommandHandler`, `UnfollowTagCommandHandler` (its ALL→EXPLICIT seed joins
`source_subscription`, so a fresh scope's `/unfollow-tag` would seed an empty
set and zero out the digest), and `SummaryCommandHandler`'s empty branch
(`countSubscriptions()==0` → "no subscriptions" steer, factually wrong once
bootstrap is implicit) all carry the same subscriptions-only world-predicate —
left unchanged, chat search returns bootstrap posts whose uid then resolves to
`null` in get_post and fails `/save`. Closing those coherence gaps pushes the
honest total to roughly 33–36 files. The ticket's own §Sizing note designates
the plan phase as where the budget gets bumped "with real knowledge" — this is
that escalation: refine the ticket (bump `files_budget` to ~34, rewrite
acceptance item 2 to name `DigestPostCollector` as the digest query and add the
five missing predicate sites plus their tests to `test_plan.modifies`, and
decide the `/summary` empty-branch semantics) rather than decompose, because
the predicate flip is atomic — any split that lands the migration or the
commands without all world-predicate sites produces broken intermediate states
(search-visible but unfetchable posts), which the ticket itself argues against
pre-splitting.

SUGGESTED ESCALATION: refine

EVIDENCE:
- `files_budget: 18` (frontmatter) vs the 23-file compressed minimum enumerated
  above; the ticket's own `clarity_check` already flagged ~23 as a WARN, and
  ground truth confirms it as a floor, not an estimate.
- Acceptance item 2 "EligiblePostQuery (digest)": the periodic digest's
  subscription predicates are `POSTS_ALL_SQL`/`POSTS_EXPLICIT_SQL` in
  `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java`
  (lines 117–144), a file the ticket never names.
- Unnamed world-predicate sites verified by Read/Grep:
  `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPostTool.java`
  (lines 62–63),
  `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java`
  (`SELECT_POST_SQL`, lines 114–123),
  `infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java`
  (`INSERT_SEED_ALL_MINUS_ONE_SQL`, lines 94–103),
  `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java`
  (line 228 → `EligiblePostQuery.countSubscriptions`, line 381).
- Inverting pre-existing tests:
  `infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java`
  lines 220–302 (EXPLICIT-mode narrowing assertions, including "EXPLICIT
  searchPosts and the /summary EligiblePostQuery must return the same"), which
  acceptance item 3 flips — modification is authorized by `test_plan.modifies`,
  but the parity-with-EligiblePostQuery assertion needs a refined contract once
  the two intentionally diverge.
- Supporting ground truth for the refine: latest migration is
  `V58__post_search_tsv.sql` (V59 free); `tag.source_origin` mirror pattern at
  `infochat-core/src/main/resources/db/migration/V6__sources_tags.sql` lines
  81–82; `BootstrapLoader.upsertSources` INSERT omits `source_origin` (lines
  163–171 — would take the V59 `'user'` default unless changed, and its
  `ON CONFLICT DO UPDATE` branch needs an explicit promote-to-`'bootstrap'`
  decision); the refined ticket should also pin the exclusion representation
  toward a separate table, since a kind-column on `source_subscription` would
  silently poison the ~10 other `source_id IN (SELECT … FROM
  source_subscription)` subqueries the ticket does not touch.
