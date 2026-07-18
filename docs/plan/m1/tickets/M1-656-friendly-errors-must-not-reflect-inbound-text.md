---
id: M1-656
title: "Stop friendly errors reflecting unvalidated inbound text"
status: done
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceArgsTest.java
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
    AUTHORIZED PRE-EXISTING TEST CHANGES (tags). Two assertions pin the echo
    this ticket removes and MUST be rewritten:
    FollowTagCommandHandlerTest.followTagDmUnknownTagReturnsFuzzySuggestionError
    (line 185-186) and
    UnfollowTagCommandHandlerTest.unfollowTagDmUnknownTagReturnsFuzzySuggestionError
    (line 240-241), both currently
    `assertTrue(reply.text().contains(PREFIX + "notavocab"), "unknown-tag reply
    must echo the supplied tag")`. Each becomes the negation —
    `assertFalse(reply.text().contains("notavocab"), "unknown-tag reply must not
    echo the supplied tag")` — with the adjacent `contains("Did you mean")`
    assertion RETAINED unchanged. Net coverage is equal-or-stronger: a
    positive echo check is replaced by a negative one plus the untouched
    suggestion check. No other assertion in either test may be relaxed.
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
    AUTHORIZED PRE-EXISTING TEST CHANGES (asset). Two assertions pin the echoed
    token and MUST be rewritten: AssetHandlerTest.unknownSubVerbFuzzy (line 116)
    `assertTrue(reply.text().contains("Unknown sub-verb krakn"))` becomes an
    assertion that the reply does NOT contain the raw sub-verb "krakn" while
    still containing the bot-authored `Did you mean: kraken` (line 118,
    RETAINED); and AssetHandlerTest.unsupportedQuoteCurrency (line 148)
    `assertTrue(reply.text().contains("jpy") && reply.text().contains("not
    enabled"))` becomes `assertFalse(contains("jpy"))` with the
    `contains("not enabled")` half RETAINED. The available-values list and the
    `Did you mean: ` assertions stay — those interpolate the enabled asset
    registry, which is bot-authored. No other assertion may be relaxed.
  - >-
    docs/spec/commands.md is updated so the §Discovery paragraph M1-647 added
    stays true. It currently says the command surface "differs deliberately
    from the app's other friendly errors (unknown tag, unknown timezone),
    which do echo what the user typed" — after this ticket that sentence is
    false for the four fixed surfaces and true only for /summary.
  - >-
    No behaviour change to any successful command path; only the failure
    replies lose an interpolated token.
  - >-
    ADD-SOURCE (scope widened at round 1 by user-accepted redteam remediation).
    error.add_source.unknown_kind and error.add_source.unknown_category lose
    their {0} raw-token placeholder in en and cs, and AddSourceArgs.unknownKind
    / unknownCategory stop passing the supplied value into interpolationArgs
    (dropping the now-unused parameter rather than leaving it dead). Verified
    reachable: AddSourceArgs:106/117 and :123/133 pass the raw --type /
    --category token straight from the inbound token split with no charset
    filter and no length bound, and /add-source is open to any registered
    non-banned user in DM (commands.md §Sources).
  - >-
    AUTHORIZED PRE-EXISTING TEST CHANGES (add-source). AddSourceArgsTest
    line 138-140 asserts the unknown_kind failure carries
    "(suppliedValue, validKindsCommaList)" and line 156 the category
    equivalent. Both MUST be rewritten to assert the single remaining
    bot-authored argument, and to assert the supplied token is ABSENT from
    interpolationArgs. The bundleKey assertions on both lines are RETAINED
    unchanged. No other assertion in the file may be relaxed.
  - >-
    The REMAINING raw-echo surfaces are named explicitly in the spec as
    known-unfixed rather than left implied (error.recover_pool.not_found,
    error.quarantine.invalid_id, error.audit.unknown_action,
    error.invite.unknown_adapter and siblings), scoped by the bot-admin
    tier property rather than an exhaustive enumeration (round-2 rework).
    error.group_not_found is named as a KNOWN VIOLATION of that tier
    property — reachable below bot admin via /approve-group's
    parse-before-gate ordering defect (r2 audit) — tracked as M1-657 and
    excluded from the bot-admin-only claim until that fix lands. This
    ticket does NOT change the surfaces; it stops the spec claiming they
    do not exist.
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
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 123
      removed: 35
    note: >-
      Reviewer independently verified the four authorized pre-existing
      assertion rewrites (inverted, not weakened; retained assertions intact)
      and confirmed the four new absence-asserting tests are non-vacuous
      against pre-diff behaviour.
  - round: 2
    date: 2026-07-18
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 409
      removed: 57
    note: >-
      Two rework items, both accepted and fixed in round 3. (1) The spec's
      "five bot-admin-only errors" list claimed exhaustiveness and was short by
      at least one (error.invite.bot_contact_unknown_adapter). A full sweep of
      all 110 MessageFormat sites then found SEVEN such keys, confirming that
      enumerating them is a trap; the paragraph was rewritten to state the
      tier-scoped PROPERTY and explicitly disclaim exhaustiveness. (2) Three
      "# Token ..." comments in en.properties documented the pre-diff argument
      order and were falsified by this diff's renumbering — orphans the change
      itself created, and precisely the stale-index footgun the audit had to
      spend a pass ruling out. Corrected in en; cs verified to carry no
      equivalent per-template comments.
  - round: 3
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 466
      removed: 60
    note: >-
      Addresses only the two round-2 items. Must-shrink: files held equal at
      16, so growth is not along all three dimensions — convergent.
escalations:
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Review was APPROVE (round 1, all five checks PASS). The /redteam audit
      returned FINDINGS (medium=1). The audit verified the CODE fix is sound —
      placeholder renumbering correct per-template against every call site in
      both languages, and both deliberately-unchanged claims (SummaryArgs,
      error.asset.sub_verb_not_enabled) hold. The finding is against the SPEC
      TEXT this diff adds: "The one deliberate exception is /summary" is false.
      AddSourceArgs.unknownKind:228-231 and unknownCategory:233-236 still
      interpolate a raw inbound token, reachable by any registered user in DM,
      plus five bot-admin-only twins. Aggravating: commands.md:530-532 already
      documents /add-source's unknown values as taking "the same path as an
      unknown tag argument", a cross-reference this diff made stale in the
      unsafe direction. User resolved the escalation as `refine` and directed
      that /add-source be fixed in this ticket rather than deferred.
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Review was APPROVE (round 3, all five checks PASS). The r2 /redteam
      audit returned FINDINGS (medium=1): error.group_not_found, which the
      diff's rewritten spec paragraph names as bot-admin-only, is reachable
      WITHOUT bot admin — ApproveGroupCommandHandler interpolates
      parseGroupIdRaw(rawText) at :124-128 and RETURNS before the admin
      gate at :146-151 (inside executeApprove, reached only when the UUID
      parse succeeds). An authorization-ORDERING defect in a handler this
      ticket never touched; the sibling RejectGroupCommandHandler orders it
      correctly (:129-132 gate before :149 parse). Full record:
      docs/plan/m1/redteam/M1-656-2026-07-18-r2.md.
      User-directed resolution (HANDOFF-output-reflection-20260718.md,
      acted on 2026-07-18): do NOT fold the code fix into this ticket — the
      round cap (3/3) is spent and the defect deserves its own review. The
      ordering fix is filed as M1-657 and the recurrence guard as M1-658;
      this ticket's spec text drops the false tier claim and names the
      tracked violation. Docs-only correction; the reviewed code is
      byte-identical to the round-3 APPROVE diff.
revisions:
  - date: 2026-07-18
    reason: >-
      redteam-finding rework (user-directed scope widening). files_budget
      14 -> 16 and files_scope +2 (AddSourceArgs.java, AddSourceArgsTest.java)
      to fold in the user-reachable /add-source reflection the audit found.
      Three acceptance items added: the add-source fix, authorization for the
      two pre-existing AddSourceArgsTest assertions it breaks, and a
      requirement that the five remaining bot-admin-only raw-echo surfaces be
      NAMED in the spec as known-unfixed instead of the false
      "one deliberate exception" claim. Root cause of the false claim: a scope
      boundary was asserted without first enumerating every interpolating
      call site; that enumeration has now been done across all three
      interpolation forms (inline MessageFormat, the Failure/interpolationArgs
      pattern, and the format(KEY, ...) helper).
    prior_values: |
      files_budget: 14
      files_scope: 11 entries (no AddSourceArgs.java, no AddSourceArgsTest.java)
      acceptance: 11 items; the three add-source / remaining-surfaces items
      did not exist. spec text claimed /summary was "the one deliberate
      exception".
  - date: 2026-07-18
    reason: >-
      clarity-fail rework (bounded self-refine, 1 of 1 permitted). The clarity
      pre-flight returned FAIL with 4 blockers, all one class: four
      PRE-EXISTING test assertions pin the echo this ticket removes, and the
      ticket listed the files under test_plan.modifies without authorizing the
      specific assertion changes. All four were verified to exist at the cited
      lines before refining. Prose-only fix: two acceptance items added naming
      each assertion, its current text, its replacement, and which adjacent
      assertions must be RETAINED. files_budget, files_scope and out_of_scope
      are UNCHANGED — every affected test file was already in scope; only the
      authorization language was missing.
    prior_values: |
      acceptance had 9 items; the two AUTHORIZED PRE-EXISTING TEST CHANGES
      items (tags, asset) did not exist. No other field changed.
  - date: 2026-07-18
    reason: >-
      r2 redteam-finding rework (user-directed via the 2026-07-18 handoff,
      docs-only). The spec paragraph and the matching acceptance item
      claimed error.group_not_found is bot-admin-only; the r2 audit
      disproved this (reachable below admin via /approve-group's
      parse-before-gate ordering). The spec text now names it a known
      violation tracked as M1-657, and the acceptance parenthetical is
      corrected the same way. No code, test, bundle, files_scope or
      files_budget change; the round cap is untouched because no reviewed
      code changed.
    prior_values: |
      acceptance item "The five REMAINING raw-echo surfaces..." claimed
      "All five are bot-admin-only commands"; the spec text listed
      error.group_not_found among the bot-admin-only examples with no
      violation note.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-18
    verdict: CLEAN
    base: d07b2a95
    head: working tree on m1/M1-656 (r3 re-audit)
    verdict_file: docs/plan/m1/redteam/M1-656-2026-07-18-r3.md
    out_of_model_count: 1
    note: |
      r3 re-audit after r2's FINDINGS (r1 and r2 records:
      docs/plan/m1/redteam/M1-656-2026-07-18.md and -r2.md; those runs
      predate the redteam_audits index). Confirms the corrected spec text
      states only true properties and that M1-657/M1-658 faithfully carry
      the r2 finding; reviewed code byte-identical to the round-3 APPROVE
      diff. One out-of-model advisory: the nitter_host_type_conflict
      URI-host echo is provably constrained and becomes an M1-658
      baseline entry, not a fix.
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

## Round 2 rework

1. **Spec list claimed exhaustiveness and was wrong.** `docs/spec/commands.md`
   named "five bot-admin-only errors" as the remaining raw-echo set. A sweep of
   all 110 `MessageFormat.format` sites in the provider found seven
   (`error.group_not_found` via both approve and reject, `error.audit.unknown_action`,
   `error.quarantine.invalid_id`, `error.invite.unknown_adapter`, and the two
   `error.invite.bot_contact_*` keys). Rewritten to state the tier-scoped
   property — "any friendly error reachable below bot admin must not reflect
   inbound text" — and to disclaim exhaustiveness explicitly.

2. **Three stale `# Token ...` comments.** `en.properties` :538, :542 and :650
   documented the pre-diff argument order for templates this diff renumbered.
   Corrected, with a line recording that the supplied value is deliberately not
   interpolated. `cs.properties` verified to carry no per-template equivalents.

## Redteam r2 resolution

The r2 audit's finding (error.group_not_found reachable below bot admin via
/approve-group's parse-before-gate ordering) is resolved OUTSIDE this
ticket, per the user-endorsed 2026-07-18 handoff: the round cap is spent
and the defect lives in a handler this diff never touched. M1-657 carries
the ordering fix (and restores the key to the spec's bot-admin-only
examples); M1-658 carries the enforcement guard the handoff recommends.
After the finding, this ticket's diff changed only in docs/spec/commands.md
— the false tier claim became a named, tracked violation — so the reviewed
code is byte-identical to the round-3 APPROVE.
