---
id: M1-621
title: "Subscription model: implicit-bootstrap corpus, private custom sources, command surface + spec amendment"
status: pending
created: 2026-07-13
last_updated: 2026-07-13
blocked_by: []
files_budget: 18
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
    existing rows to 'bootstrap' (they are operator-seeded). BootstrapLoader sets
    'bootstrap' on its upsert; the /add-source insert path sets 'user'. A
    migration/repository test asserts the column, CHECK, default, backfill, and
    that a bootstrap-loaded source is 'bootstrap' while an /add-source'd source is
    'user'. The migration applies cleanly on a fresh DB and a prod-shaped DB.
  - >-
    Retrieval + digest scoping becomes "source.source_origin='bootstrap' AND the
    source is not excluded by this scope, OR source_id IN (this scope's
    source_subscription)" in EligiblePostQuery (digest), SemanticSearchTool,
    SearchPostsTool, and GetReferencesTool (both post endpoints). Named tests:
    (a) a bootstrap-origin post is retrieved by a scope with NO subscriptions;
    (b) a user-origin (custom) post is retrieved by its subscriber but NOT by a
    different scope (privacy).
  - >-
    RAG is decoupled from /follow-tag: SearchPostsTool no longer applies the
    scope_tag / tag_mode filter (SemanticSearchTool and GetReferencesTool already
    don't). A test proves a scope in EXPLICIT tag mode with a narrow followed-tag
    set still gets keyword hits outside those tags. EligiblePostQuery (the DIGEST)
    KEEPS the follow-tag filter — a test proves the digest still narrows.
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
implicit-bootstrap scoping in the four retrieval/digest queries, the affected
command surface (`/list-sources` shows bootstrap + own customs, `/unfollow-source`
opts a scope out of a bootstrap source, `/follow-all-sources` re-includes,
`/add-source` marks custom), the RAG/`follow-tag` decoupling, and the spec
amendment (D59). The user-facing *guidance copy* that teaches the model is the one
dependent piece split out — **M1-622** (`blocked_by` this ticket) — because it's
pure bilingual string work, independently reviewable, and ties to the pre-release
message-audit gate.

## Acceptance

See the YAML `acceptance:` list. In prose: add `source.source_origin`
(`bootstrap`|`user`, default `user`, existing→`bootstrap`) + a per-scope
exclusion representation via Flyway V59; mark origin in BootstrapLoader /
`/add-source`; change all four query scopes to "bootstrap (not excluded) OR my
subscriptions" so bootstrap is implicit and customs stay private; make
`/list-sources` show bootstrap + own customs (never others'), `/unfollow-source`
exclude a bootstrap source (or remove a custom subscription), `/follow-all-sources`
clear exclusions; decouple SearchPostsTool from `/follow-tag` while the digest
keeps it; and amend `commands.md`, `decisions.md` (D59), `schema.md`. Parity tests
and `mvn verify` stay green.

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
- **Exclusion representation is a plan-phase decision** — a boolean/kind on
  `source_subscription`, or a separate table. The ticket pins the BEHAVIOUR (a
  scope opts out of one bootstrap source; only that scope is affected; re-include
  restores it), not the storage. Whatever is chosen, the four queries apply it
  uniformly.
- **Dependent ticket M1-622** (guidance copy) is filed alongside this and
  `blocked_by` it; its welcome/hint strings describe this ticket's behaviour, so
  it lands after. Do not add those educational strings here.
- **Sizing:** this is a large cohesive feature. `files_budget: 18` is a first
  estimate; the clarity pre-flight / plan phase may bump it. If the diff genuinely
  cannot land as one reviewable change, decompose at implementation via the
  budget-breach escalation — with real knowledge — rather than pre-splitting.
- **Adjacent pattern:** mirror the existing `tag.source_origin='bootstrap'`
  (BootstrapLoader, `tag` table) for `source.source_origin`. The four query
  classes already share the `source_id IN (source_subscription …)` sub-select —
  the change is a consistent disjunction + exclusion across all four.
- **Determinism (D19) unaffected:** the predicate is deterministic SQL; no LLM in
  retrieval.
- Design authority: `docs/plan/subscription-model-redesign.md`.
