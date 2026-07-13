---
id: M1-621
title: "Subscription model foundation: source.source_origin + implicit-bootstrap retrieval/digest scoping + spec amendment"
status: pending
created: 2026-07-13
last_updated: 2026-07-13
blocked_by: []
files_budget: 15
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The command-SURFACE UX. This ticket changes RETRIEVAL/DIGEST scoping only.
    It does NOT change what /list-sources displays (still lists the scope's
    source_subscription rows), does NOT retire or repurpose /follow-all-sources,
    and does NOT add bootstrap-source EXCLUSIONS (a per-user opt-out of a single
    bootstrap source / unfollow-a-bootstrap tombstone). Those commands keep their
    current behaviour and become partly redundant under implicit bootstrap; the
    surface cleanup is a separate follow-up ticket. Bootstrap = ALL bootstrap for
    everyone here; no opt-out yet.
  - >-
    User-facing guidance/welcome copy (the "you're following all our sources; use
    /follow-tag to focus your digest — chat still searches everything" hints) and
    ANY new/changed bundle key. This ticket adds NO en/cs bundle keys. The
    guidance-copy layer is a separate follow-up (it also ties into the
    pre-release message-audit gate, docs/plan/v1-verification-truth.md §6).
  - >-
    The post-tagging pipeline. post.tags (the LLM tagger's per-post content tags)
    and source.bootstrap_tags (the deterministic fallback + vocabulary seed) are
    untouched; /follow-tag continues to filter the DIGEST on post.tags. This
    ticket only DECOUPLES the RAG keyword tool from follow-tag, it does not change
    how tags are produced or how the digest applies them.
  - >-
    Group auto-subscribe on /approve-group, registration-time behaviour, and the
    asset/price path. No change to group approval, the D45 probation flow, or any
    /zcash-style command. Implicit bootstrap applies uniformly to every existing
    scope via the query change; no per-scope fan-out or backfill of
    source_subscription rows is introduced.
acceptance:
  - >-
    A new Flyway migration V59__source_origin.sql adds a NOT NULL
    `source.source_origin` column, CHECK (source_origin IN ('bootstrap','user')),
    DEFAULT 'user' (the privacy-safe default — a forgotten insert is private, not
    public). It backfills every existing source row to 'bootstrap' (current rows
    are operator-seeded). The migration applies cleanly on a fresh DB and on a
    prod-shaped DB with existing sources; a migration/repository test asserts the
    column, CHECK, default, and backfill.
  - >-
    BootstrapLoader sets source_origin='bootstrap' on its source upsert (both
    insert and ON CONFLICT paths); /add-source (its source-insert path) sets
    source_origin='user' on a fresh insert. A test proves a bootstrap-loaded
    source has origin='bootstrap' and an /add-source'd new source has
    origin='user'.
  - >-
    Retrieval + digest scoping changes from "source_id IN (source_subscription
    WHERE scope=?)" to "source.source_origin='bootstrap' OR source_id IN
    (source_subscription WHERE scope=?)" in ALL FOUR queries: EligiblePostQuery
    (digest/summary), SemanticSearchTool, SearchPostsTool, GetReferencesTool
    (both post endpoints for GetReferences). Named behavioural tests prove: (a) a
    bootstrap-origin post is retrieved by a scope with NO source_subscription rows;
    (b) a user-origin (custom) post is retrieved by its subscriber but NOT by a
    different scope that has no subscription to it (privacy).
  - >-
    RAG is decoupled from /follow-tag: SearchPostsTool no longer applies the
    scope_tag / tag_mode filter (it currently does; SemanticSearchTool and
    GetReferencesTool already don't). A test proves a scope in EXPLICIT tag mode
    with a narrow followed-tag set still gets keyword-search hits outside those
    tags. EligiblePostQuery (the DIGEST) KEEPS the follow-tag filter unchanged — a
    test proves the digest still narrows by followed tags.
  - >-
    Spec updated: docs/spec/commands.md §Source management + §Per-scope tag
    preferences describe the model — bootstrap sources are an implicit public
    corpus every scope retrieves; custom (/add-source) sources are private to
    their subscribers; /follow-tag narrows the DIGEST only, chat/RAG stays broad.
    A new decision D59 in docs/spec/decisions.md records "implicit bootstrap
    corpus" and explicitly supersedes the prior "no auto-subscribe at registration
    / explicit per-scope opt-in" stance in commands.md §Source management.
    docs/spec/schema.md §Sources and tags gains the source_origin column.
  - >-
    No command-catalogue or closed-set change (no new/removed command), so
    CommandCatalogueParityTest and the LlmOutputSanitizer closed-set parity stay
    green. No bundle key added, so BundleLoaderTest is unaffected. `mvn verify` is
    green from the repo root.
test_plan:
  adds:
    - >-
      A migration/repository test: V59 applies on a fresh DB; source_origin is
      NOT NULL with the CHECK and 'user' default; pre-existing rows backfilled to
      'bootstrap'.
    - >-
      A retrieval-scope test proving bootstrap-origin posts are visible to a
      subscription-less scope, and a custom (user-origin) source's posts are
      visible only to its subscriber, across EligiblePostQuery + the three RAG
      tools.
    - >-
      A RAG/follow-tag decoupling test: SearchPostsTool returns hits outside a
      narrow EXPLICIT followed-tag set; EligiblePostQuery still narrows the digest
      by followed tags.
  modifies:
    - >-
      BootstrapLoader.java (source upsert sets origin='bootstrap') and the
      /add-source source-insert path (AddSourceCommandHandler or its
      SourceRepository insert) to set origin='user'; and their tests.
    - >-
      EligiblePostQuery.java, SemanticSearchTool.java, SearchPostsTool.java,
      GetReferencesTool.java (scoping predicate; SearchPostsTool also drops the
      follow-tag filter) and their tests.
    - >-
      docs/spec/commands.md, docs/spec/decisions.md (new D59), docs/spec/schema.md.
  preserves:
    - all tests currently green on main
    - >-
      the post-tagging pipeline (post.tags, bootstrap_tags fallback) and the
      DIGEST's follow-tag narrowing (EligiblePostQuery keeps it)
    - >-
      CommandCatalogueParityTest, the LlmOutputSanitizer closed-set parity, and
      BundleLoaderTest (no command or bundle-key change)
    - >-
      /list-sources, /follow-all-sources, /unfollow-source behaviour (surface
      cleanup is a separate follow-up)
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

# M1-621: Subscription model foundation — source.source_origin + implicit-bootstrap scoping

## Context

The v1 subscription/tag model has confusing overlap (source subscription vs.
`/follow-tag`) and a real UX gap: a new user with no subscriptions sees an empty
feed until they `/follow-all-sources`, there is no non-admin source browsing, and
`/add-source` overloads "create source" with "subscribe." A redesign was decided
2026-07-13 — full write-up in `docs/plan/subscription-model-redesign.md`, tracked
as a v1 gap in `docs/plan/v1-verification-truth.md` §6b. The converged model:
**bootstrap sources are an implicit public corpus everyone retrieves; custom
(user-added) sources are private to their subscribers; `/follow-tag` is the one
user knob and narrows the DIGEST only — chat/RAG stays broad.**

This ticket lands the **foundation**: the `source.source_origin` discriminator and
the implicit-bootstrap scoping in the four retrieval/digest queries, plus the spec
amendment (new decision D59 superseding the "no auto-subscribe / explicit opt-in"
stance). It is deliberately scoped to the query model + schema + spec — the
cohesive core the rest builds on — so it stays a reviewable size. The
command-surface cleanup (`/list-sources` browsing, `/follow-all-sources`,
bootstrap exclusions) and the user-facing guidance copy are separate follow-up
tickets (see §Notes).

## Acceptance

See the YAML `acceptance:` list. In prose: add a `source.source_origin`
(`bootstrap`|`user`) column via Flyway V59 (default `'user'`, existing rows
backfilled to `'bootstrap'`); have BootstrapLoader mark `'bootstrap'` and
`/add-source` mark `'user'`; change the scoping predicate in EligiblePostQuery and
the three RAG tools to `source_origin='bootstrap' OR source_id IN (this scope's
subscriptions)` so bootstrap is retrieved implicitly and customs stay private;
decouple SearchPostsTool from `/follow-tag` while keeping the digest's follow-tag
narrowing; and amend `commands.md`, `decisions.md` (D59), and `schema.md` to
document the model. All parity tests and `mvn verify` stay green.

## Out-of-scope

Prose in the YAML `out_of_scope:`. In short: no command-surface changes
(`/list-sources` display, `/follow-all-sources`, `/unfollow-source`, bootstrap
exclusions) — those commands keep current behaviour and are cleaned up in a
follow-up; no guidance/welcome copy and no bundle-key change; no post-tagging
change; no group-approval / registration / asset-path change. `/list-sources`
showing a subscription-less user "almost nothing" is an accepted intermediate
state until the surface follow-up lands (the bot has no production users yet).

## Notes

- **Privacy is the load-bearing property.** The new predicate must be exactly
  `source_origin='bootstrap' OR source_id IN (subs)`. A custom source
  (`origin='user'`) is reachable only through a `source_subscription` row, which
  `/add-source` writes only for the adder — so it never surfaces to another scope.
  The `'user'` default on the column is deliberate: a forgotten insert is private
  (fail-closed), never public.
- **Follow-up tickets (not filed here — no forward IDs to avoid clarity WARNs):**
  (1) command-surface UX — `/list-sources` shows the bootstrap catalogue + the
  caller's own customs (never others'), `/follow-all-sources` retires or becomes
  "reset my exclusions," `/unfollow-source` gains bootstrap-exclusion (tombstone)
  semantics; (2) subscription guidance copy — welcome + `/follow-tag` hints +
  empty-digest nudge in en+cs. This ticket is the shared foundation both depend on.
- **The `/summary` empty-case** (`no_subscriptions` vs `no posts yet`, commands.md
  §Content) subtly shifts once bootstrap is implicit — a scope always has the
  bootstrap corpus. Keep the existing replies functioning; the copy/logic
  refinement of that nudge belongs with the guidance-copy follow-up, not here.
  This ticket must not leave EligiblePostQuery throwing or mis-reporting on the
  empty window — only the scoping predicate changes.
- **Adjacent code / patterns:** the existing `tag.source_origin='bootstrap'`
  pattern (BootstrapLoader, `tag` table) is the precedent to mirror for
  `source.source_origin`. The four query classes already share the
  `source_id IN (source_subscription …)` sub-select shape — the change is a
  disjunction added to each; keep them consistent.
- **Determinism (D19) unaffected:** the scoping predicate is deterministic SQL; no
  LLM enters retrieval. `source_origin` is a static row attribute.
- Design authority: `docs/plan/subscription-model-redesign.md`.
