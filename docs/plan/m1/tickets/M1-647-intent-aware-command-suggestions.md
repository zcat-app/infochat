---
id: M1-647
title: "Intent-aware command suggestions: synonyms + non-prefix matching on the /help path"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-645
  - M1-646
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandIntentSynonyms.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/CommandIntentSynonymsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The five other fuzzySuggest copies — UnfollowTagCommandHandler:530,
    FollowTagCommandHandler:408, AssetHandler:191, EligiblePostQuery:465,
    GroupTimezoneCommandHandler:204. They suggest over tag / asset / timezone
    vocabularies, not commands; a shared abstraction across four unrelated
    corpora is a bigger design question. Leave them exactly as they are.
  - >-
    Czech-language synonym terms. Command names are English-only, so v1 maps
    English intent words. A cs-language intent vocabulary is a follow-up; the
    new "no close match" reply string still needs its cs twin (D43).
  - >-
    Chat mode. This ticket changes only the slash-command miss path and
    /help <unknown>. It adds no chat tool and no LLM call.
  - >-
    Renaming or aliasing any command. The vocabulary gap is closed by mapping
    synonyms onto existing names, not by adding handlers.
  - >-
    Semantic / embedding-based intent matching — M1-648. This ticket is a
    deterministic static map with no embeddings and no model call.
acceptance:
  - >-
    A new CommandIntentSynonyms type maps natural intent words onto command
    names. At minimum it resolves: mute/block/hide/silence/ignore →
    unfollow-source; bookmark/keep/star/favorite → save; bookmarks/library/
    favorites → saved; subscribe/feed/rss/watch → add-source; feeds/
    subscriptions/sources → list-sources; news/digest/catchup/brief/recent →
    summary; topics/categories/interests → get-tags; cancel/abort → stop;
    language/locale → lang; privacy/data/download → export; wipe/reset → clear.
    CommandIntentSynonymsTest pins each mapping.
  - >-
    HelpCommandHandler suggestion ranking is no longer shared-prefix-only. A
    query sharing no prefix with any command (e.g. "mute") returns the
    intent-mapped command, not the alphabetically-first entries.
    HelpCommandHandlerTest.suggestsUnfollowSourceForMute passes.
  - >-
    When no candidate clears the match threshold, the reply names no commands
    and points at /help instead. Today fuzzySuggest scores every non-matching
    query 0 and the alphabetical tie-break returns the first five visible names,
    so the user is confidently offered irrelevant commands.
    HelpCommandHandlerTest.noCloseMatchOffersNoCommandList passes, asserting the
    reply contains none of the catalogue names.
  - >-
    Synonym resolution respects HelpTier. A non-admin whose query maps to a
    BOT_ADMIN command receives the no-close-match reply, NOT the command name —
    the suggestion path must not become an admin-command existence oracle
    (docs/spec/commands.md:226).
    HelpCommandHandlerTest.synonymForAdminCommandLeaksNothingToNonAdmin passes.
  - >-
    The unknown-slash-command path (InboundRouter:1423, error.unknown_command)
    uses the same resolver, so /mute and /help mute give consistent guidance.
  - >-
    Probation users reach this path: "help" is in CommandPermissions.ALLOWED, so
    the improved suggestions are available before probation ends.
    HelpCommandHandlerTest covers a probation caller.
  - >-
    One new bundle key (no-close-match reply) with an en+cs twin. No behaviour
    change to any command's execution.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/CommandIntentSynonymsTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Permission model
decision_refs:
  - D43
---

# M1-647: Intent-aware command suggestions: synonyms + non-prefix matching on the /help path

## Context

`/help <cmd>` returns good per-command usage and examples (M1-573), but reaching
it requires already knowing the command's name. A 2026-07-18 audit measured the
gap: of 39 catalogue commands, roughly 12 are guessable from the first word a
user would naturally reach for. The rest require the product's vocabulary.

The failure is not silence — it is confident misdirection. `fuzzySuggest` scores
candidates by `sharedPrefixLength` alone and breaks ties with
`a.name.compareTo(b.name)` ascending. Any query sharing no first character with
any command scores 0 across the board, so the alphabetical tie-break returns the
first five visible names. A plain DM user typing `/mute` is told:

> Unknown command `/mute`. Did you mean one of: add-source, clear, compress,
> export, follow-all-sources? See /help for the commands available to you.

None of those is what they asked for, and the phrasing asserts they might be.

The `-source` family makes this concrete: `add-source`, `remove-source`,
`unfollow-source`, `follow-all-sources`, `source-enable`, `source-disable` are
split across two prefixes, so no single typed prefix reaches them all — typing
`source` scores 0 against `add-source`.

There is no synonym, alias, or intent map anywhere in the codebase. The one
"alias" (`/get-sources` for `/list-sources`) is a hand-written duplicate handler
with its own catalogue entry.

## Acceptance

See `acceptance`. A deterministic synonym map, non-prefix ranking, an honest
no-match reply, and a pinned tier-leak guard.

## Out-of-scope

See `out_of_scope`. The other five `fuzzySuggest` copies stay untouched — they
rank over different vocabularies and unifying them is a separate design
question. No embeddings, no LLM, no chat-mode changes.

## Notes

**Why this is worth doing even though M1-648 also addresses discovery.** M1-648
routes through chat mode, and probation users' non-slash input fails closed at
`InboundRouter:1451-1454` — the sentinel `"chat-mode"` is deliberately absent
from `CommandPermissions.ALLOWED`. So the users least likely to know the command
vocabulary are exactly the ones a chat-based answer can never reach. This ticket
lives on the `/help` path, which probation users CAN use. The two are
complementary, not redundant; a future reader should not delete this map as dead
once M1-648 ships.

**Tier filtering is the security crux.** A synonym map is an attractive
existence oracle: if `/makeadmin` suggests `grant-admin` to a non-admin, the
suggestion path leaks the admin surface that `visible()` exists to hide. Resolve
synonyms FIRST, then filter through the same `visible()` predicate, then decide
whether anything survives — never the other way around.

**Matching approach.** Two stages: exact synonym-map hit, then a fallback
distance measure over the visible vocabulary for typos (`/sumary` → `summary`),
which shared-prefix already half-handles. A threshold below which nothing is
suggested is what turns the current confident-wrong behaviour into an honest
"no close match". Keep the measure simple — normalized edit distance or token
overlap is enough; this does not need a scoring framework.

**Seed corpus for M1-648.** The synonym map doubles as the hand-written seed for
the semantic intent index. Keeping it a plain declarative structure (not logic
scattered through the handler) is what makes it reusable there.

**Spec.** `docs/spec/commands.md` §Discovery should record that unknown-command
guidance is intent-aware and that suggestions are tier-filtered — the second
half is a security property, not a UX detail.
