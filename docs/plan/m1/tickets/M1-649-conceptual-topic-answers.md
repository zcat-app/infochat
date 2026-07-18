---
id: M1-649
title: "Conceptual help topics: answer the questions no command's usage string can"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-648
files_budget: 12
files_scope:
  - USER_GUIDE.md
  - infochat-provider/src/main/resources/help-topics/README.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpTopicCorpus.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndexBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/HelpTopicCorpusTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  - docs/spec/commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ADMIN_GUIDE.md. It cannot be embedded, whole or chunked, in this ticket.
    Lines 308-322 enumerate the closed privileged-command set while stating the
    output sanitizer and probation classifier key off it, and 293-306 spell out
    which destructive paths are and are not confirmation-gated. Admin-tier
    conceptual answers are a separate ticket with its own threat review.
  - >-
    docs/spec/** and docs/design/**. Developer-facing, and commands.md carries
    deliberate non-disclosure rules (:226 no admin-command existence leak, and
    the reasoning at :1243-1246 that reading defeats).
  - >-
    The command-intent index itself, the doc_embedding table, grants, or the
    match-not-assert composition path for COMMAND answers — all M1-648.
  - >-
    Rewriting USER_GUIDE.md. Only the two confirmed factual defects below are
    corrected; this is not a documentation-quality pass.
  - >-
    Serving conceptual answers to probation users. Chat mode is closed to them
    (InboundRouter:1451-1454) and this ticket does not change that gate.
acceptance:
  - >-
    A curated set of conceptual topic documents is embedded into doc_embedding
    with doc_kind='topic', covering at minimum: invite/access flow, what
    probation is and how it ends, chat-vs-command mental model, the chat
    assistant's read-only own-scope boundary, DM-vs-group semantics including
    the mention requirement, unfollow-vs-delete ownership, why /add-source
    requires tags, personal-view vs shared-source tags, /clear vs /forget, and
    what /forget does and does not erase.
  - >-
    Each topic document is a short curated answer stored under
    infochat-provider/src/main/resources/help-topics/, NOT a raw slice of
    USER_GUIDE.md. USER_GUIDE.md remains the human-facing narrative; the topic
    file is the runtime-served text, so the runtime never depends on a markdown
    file's heading structure staying put.
  - >-
    STALENESS GUARD — each topic document records the USER_GUIDE.md section it
    derives from plus a content hash of that section. HelpTopicCorpusTest.
    topicDerivationHashesMatchCurrentUserGuide fails the build when USER_GUIDE.md
    changes under a topic without the topic being revisited. This is the
    mitigation for the one risk M1-648's design avoids by construction and this
    ticket cannot: a conceptual answer is prose with no runtime source of truth
    to compose from, so drift must be caught by a check rather than made
    impossible.
  - >-
    HelpLookupTool returns topic answers alongside command answers, with topics
    carrying no HelpTier gate (every topic in the corpus is user-tier by
    construction) and an explicit test asserting no topic document mentions an
    admin-only command. HelpLookupToolTest.noTopicDocumentReferencesAdminSurface
    passes.
  - >-
    Asking "what is probation" or "why can't I post in the group" in chat yields
    the curated topic answer rather than an invented one, and below threshold the
    agent says it does not know and points at /help.
  - >-
    USER_GUIDE.md defect 1 corrected — line ~316-318 states supported currencies
    as usd/eur/czk/btc next to a Kraken example, but
    KrakenSnapshotSource.SUPPORTED_VS is Set.of("usd", "eur", "btc"); czk is
    Coingecko-only. The guide must state the per-source difference.
  - >-
    USER_GUIDE.md defect 2 corrected — /follow-all-sources and /list-sources are
    absent from the cheat sheet despite being non-admin commands, and the
    pagination list omits /export --page.
  - >-
    docs/spec/commands.md §Chat mode records that conceptual topics are served
    from a curated corpus with a derivation-hash guard, and names ADMIN_GUIDE.md
    as deliberately excluded.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/HelpTopicCorpusTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D64
---

# M1-649: Conceptual help topics: answer the questions no command's usage string can

## Context

M1-648 makes the bot able to answer "which command does X" without inventing
syntax, because every command answer is composed from the runtime help path. It
deliberately stops there.

A second class of question has no runtime source of truth to compose from:
"what is probation", "why can't I post in the group", "what does /forget
actually erase", "who can change a source's tags", "what's the difference
between unfollowing and deleting a source". A 2026-07-18 audit found twelve such
topics documented in `USER_GUIDE.md` and derivable from no `.usage` string. These
are the questions a confused user actually asks, and today the chat agent
answers them from general knowledge — i.e. makes them up.

This ticket is the drift-exposed half of the feature, and the design says so
plainly. M1-648 could guarantee correctness structurally: retrieved text matched,
runtime text asserted, so a stale index degrades to a missed match. Prose answers
cannot have that property — the retrieved text IS the answer. The mitigation is
therefore a build-time check rather than an architectural guarantee, and that
difference is the reason the two tickets are separate: M1-648 can ship and be
trusted on its own, and this one carries a maintenance obligation that should be
accepted deliberately rather than inherited silently.

## Acceptance

See `acceptance`. A curated topic corpus, a derivation-hash staleness guard, two
USER_GUIDE.md factual corrections, and an admin-surface exclusion test.

## Out-of-scope

See `out_of_scope`. `ADMIN_GUIDE.md` is excluded on threat-model grounds, not
effort grounds — it documents which strings the sanitizer keys on and which
destructive paths skip confirmation.

## Notes

**Curated topic files, not raw guide slices.** Chunking `USER_GUIDE.md` directly
would couple the runtime to a markdown file's heading structure and would serve
whatever the guide happens to say, including its defects. Writing short topic
answers as their own resources means the served text is reviewed as product copy,
and the guide stays a narrative document for humans.

**Why the hash guard, specifically.** `USER_GUIDE.md` is currently referenced by
no code — a pure repo file. That is exactly why its two defects survived a
documented claim-by-claim audit on 2026-06-30 (M1-509): nothing failed when it
drifted. Making it a derivation source without a check would recreate that
condition one layer deeper, where the stale text is now spoken by the bot. The
hash is what converts a silent rot into a red build.

**Fix the guide before deriving from it.** The two corrections are prerequisites,
not cleanup — deriving topic answers from text known to be wrong is how M1-645's
defect class gets laundered into a confident answer.

**Topics are tier-flat by construction.** Every topic in this corpus is user-tier,
so no `HelpTier` gate is needed on the topic path. That is a property to PIN, not
assume: the test asserting no topic document names an admin-only command is what
keeps a future topic addition from quietly widening the surface.

**Threshold sharing.** Topic documents are longer and more prose-like than
M1-648's one-sentence intent documents, so their similarity distribution differs.
Do not assume one threshold serves both; measure, and if they diverge, keep two
named constants with comments rather than splitting the difference.

**Plan sidecar.** complexity:high — the corpus authoring, the hash guard, and the
tool integration are separable; sequence the guide corrections first.
