---
id: M1-656
title: "Stop friendly errors reflecting unvalidated inbound text"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    SummaryCommandHandler / SummaryArgs. VERIFIED SAFE 2026-07-18 and
    deliberately untouched: SummaryArgs:94-100 validates at PARSE time
    (TagNormalizer.normalize then isValid, returning error.summary.tag_malformed
    on failure) and stores only the normalized value, so args.tag() can only
    ever be ^[a-z0-9][a-z0-9-]{0,47}$. Its echo is provably constrained. It is
    this ticket's PRECEDENT, not a target; changing it would be churn with no
    security gain. The resulting asymmetry (summary echoes, the four fixed
    surfaces do not) is intentional and must be recorded, not "fixed".
  - >-
    HelpCommandHandler / InboundRouter / CommandIntentSynonyms. M1-647 already
    removed the interpolation on the command-unknown surface. Do not revisit.
  - >-
    Adding a sanitizer pass over deterministic command output, or amending
    security.md §LLM output sanitizer's deterministic-output exemption. The
    exemption question is real but separate; this ticket makes the four
    surfaces conform to the exemption's premise, which is the cheaper half.
  - >-
    The duplicate unknown-tag key definition (BundleKeys.ERROR_SUMMARY_UNKNOWN_TAG
    is never read; SummaryArgs:153 re-declares the same "error.summary.unknown_tag"
    string as its own literal). Noted during investigation, cosmetic, unrelated
    to the vulnerability. Leave it.
  - >-
    Replacing the five fuzzySuggest copies with a shared abstraction. Same
    reasoning M1-647 gave: four unrelated corpora, a bigger design question.
acceptance:
  - >-
    No user-visible reply on the four listed surfaces interpolates
    inbound-derived text. Concretely, the {0} placeholder carrying the user's
    own token is removed from error.follow_tag.unknown_tag,
    error.unfollow_tag.unknown_tag, error.group_timezone.invalid_zone and
    error.asset.unknown_sub_verb (and the {0} of
    error.asset.unsupported_quote_currency), in BOTH en and cs (D43), with the
    remaining placeholders renumbered. Suggestions and available-value lists
    stay: those are bot-authored, drawn from the controlled vocabulary / the
    enabled asset registry / the IANA zone set.
  - >-
    NET ZERO new bundle keys. The fix is removing a placeholder from existing
    templates, not adding no-echo variants. This is deliberate: it needs no
    per-surface validity predicate, which matters because no viable one exists
    for timezones (see the next item).
  - >-
    FollowTagCommandHandlerTest and UnfollowTagCommandHandlerTest each gain a
    test proving `/follow-tag /grant-admin` (resp. /unfollow-tag) produces a
    reply containing neither the raw token nor the substring "grant-admin",
    while a legitimate unknown tag still receives its suggestion list.
  - >-
    GroupTimezoneCommandHandlerTest gains the equivalent test. NOTE the
    validity-gated-echo approach used elsewhere is NOT available here: IANA
    zone names legitimately contain "/" (Europe/Prague), so no charset
    predicate can separate a real zone from an injected command string. This
    is why the ticket removes the echo rather than validating it.
  - >-
    AssetHandlerTest gains the equivalent test for both the unknown-sub-verb
    and the unsupported-quote-currency replies. This surface has the WIDEST
    reachability of the four: CommandPermissions:80 admits any asset command
    during slow-start probation, so a probation user reaches it wherever the
    operator has enabled an asset.
  - >-
    docs/spec/commands.md is updated so the §Discovery paragraph M1-647 added
    stays true. It currently says the command surface "differs deliberately
    from the app's other friendly errors (unknown tag, unknown timezone),
    which do echo what the user typed" — after this ticket that sentence is
    false for the four fixed surfaces and true only for /summary.
  - >-
    No behaviour change to any successful command path; only the failure
    replies lose an interpolated token.
  - mvn verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/commands.md §Discovery
decision_refs:
  - D43
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations: []
---

# M1-656: Stop friendly errors reflecting unvalidated inbound text

## Context

M1-647 removed the requested-name echo from the command-unknown reply after
two red-team audits: the reply interpolated inbound text into a template
rendering `` `/{0}` ``, so `/help grant-admin` put the literal string
`/grant-admin` into bot output — to a whole group, bot admins included — on a
surface `security.md` §LLM output sanitizer exempts from the admin-command
strip. An intermediate `[a-z0-9-]` filter was tried and defeated (`grant-admin`
is inside that alphabet); the fix that held was removing the interpolation, so
the exemption's premise — that deterministic output is bot-authored — became
true by construction.

Four sibling surfaces still reflect raw inbound text. All verified 2026-07-18:

| Surface | Echo site | Value | Reachable by |
|---|---|---|---|
| `/follow-tag` | `FollowTagCommandHandler:187` | `suppliedTag`, RAW | any registered non-probation user (DM) |
| `/unfollow-tag` | `UnfollowTagCommandHandler:240` | `parsed.positionalTag`, RAW | same |
| `/group-timezone` | `GroupTimezoneCommandHandler:116` | `tzArg`, RAW | group admin / bot admin only |
| `/<asset>` | `AssetHandler:118`, `:123` | `args.subVerb`, `vsCurrency`, RAW (lowercased only) | **any user, including during probation** |

The tag handlers are the clearest illustration: both compute a validated
`normalizedTag` and then echo the *unvalidated* `suppliedTag` — the same
check-one-string-echo-another shape M1-647 had to fix in
`slashMissSuggestion`. `/follow-tag /grant-admin` yields:

> Unknown tag `/grant-admin`. Did you mean one of: …?

`parsePositionalTag` returns the first whitespace-delimited token with no
length bound, so the echo is capped only by `infochat.command.body-cap` (8192
default): up to 8k of attacker-chosen non-whitespace, relayed into a group.

## Why removal, not validation

The obvious fix — echo the validated value — was tried on paper and rejected
on evidence:

- `TagNormalizer.normalize` is trim + NFC + lowercase only. It does **not**
  strip `/`. Echoing `normalizedTag` would still emit `/grant-admin`. The gate
  would have to be on `TagNormalizer.isValid`, not on the variable swap.
- Even gated, the approach does not generalise. IANA zone names legitimately
  contain `/` (`Europe/Prague`), so no charset predicate separates a real zone
  from an injected command string. `/group-timezone` cannot be fixed this way.

Removing the placeholder needs no predicate, works identically on all four
surfaces, adds no bundle key, and matches the precedent M1-647 just set.
Where a *safe* echo already exists — `/summary`, which validates at parse — it
is left alone; see `out_of_scope`.

## Notes

**This is not an oversight being corrected; it is a deliberate choice being
revisited.** `FollowTagCommandHandler:172-178` records M1-489's decision to
fold char-class failures into the unknown-tag path rather than give them their
own reply ("An input that fails the char-class cannot be in the
(char-class-constrained) vocabulary, so it folds into the same unknown-tag
path"). That fold is preserved here — this ticket does not add a malformed-tag
branch; it only stops the shared path from echoing.

**Severity is low and should be stated as low.** Executing any admin command
still requires `is_admin = true`, and the attacker controls only a single
whitespace-free token inside a fixed, bot-authored error frame — they cannot
add persuasive wrapping the way a prompt-injected LLM could. Two red-team
audits rated the equivalent command-surface issue low. It is worth fixing
because it is cheap, because the surfaces are ordinary-user-reachable, and
because leaving siblings unfixed after M1-647 is incoherent — not because it
is urgent.

**Provenance.** Raised as out-of-model item in both M1-647 audits
(`docs/plan/m1/redteam/M1-647-2026-07-18.md`, `-r2.md`); the third audit
(`-r3.md`) confirmed the scope exclusion and independently corrected the
severity argument, noting the slash-synthesis distinction is "an argument
about copy-pasteability, not about reflection — those surfaces do still
reflect inbound bytes."
