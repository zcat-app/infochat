---
id: M1-647
title: "Intent-aware command suggestions: synonyms + non-prefix matching on the /help path"
status: done
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
    NET ZERO new bundle keys. error.help.no_close_match is NOT added: with no
    echo (next item) the no-match reply is exactly the existing
    error.unknown_command. error.help.unknown_command loses its {0} name
    placeholder and renders suggestions only, in en and cs. No behaviour
    change to any command's execution.
  - >-
    NO INBOUND TEXT IN COMMAND-UNKNOWN OUTPUT (redteam remediation, second
    audit 2026-07-18). Neither the /help <unknown> reply nor the bare
    unknown-slash reply reflects the requested name back. The requested name
    is still used for RESOLUTION; it never reaches output. Output is composed
    solely of fixed bundle text plus caller-visible catalogue names.
    Rationale: the first remediation (an [a-z0-9-] echo filter) was the wrong
    SHAPE — `grant-admin` is itself inside that alphabet, so the audit broke
    it in one pass, and any denylist patch invites the next bypass. This
    surface is uniquely dangerous among the app's friendly errors because its
    template alone renders `/{0}`, synthesising a copy-pasteable command from
    a bare inbound word; the tag/timezone templates render `{0}` with no
    slash and cannot. Removing the interpolation makes
    security.md §LLM output sanitizer's deterministic-output exemption sound
    by construction for this surface rather than by assumption.
    HelpCommandHandlerTest.commandUnknownReplyNeverReflectsInboundText passes.
  - >-
    Byte-identical indistinguishability. Because nothing is echoed, a
    hidden-but-real command, a nonexistent name, and any unmatched query all
    produce the SAME BYTES for a given caller — strengthening
    docs/spec/commands.md §Permission model's no-existence-leak property from
    same-shape to same-bytes. hiddenTierCommandIsIndistinguishableFromUnknown
    asserts byte equality against the nonexistent-name control. Every
    pre-existing assertion this replaces is strengthened, never weakened: a
    substring check on the echoed name becomes an equality check on the whole
    reply.
  - >-
    The added docs/spec/commands.md §Discovery sentence claiming /mute and
    /help mute give consistent guidance is corrected: it holds for
    non-probation callers only. A probation caller's bare /mute is stopped by
    the step-5 probation gate (CommandPermissions.allowedDuringProbation
    fails closed on unknown names) and never reaches step 6, so it yields
    error.probation.blocked while /help mute yields the suggestion.
  - >-
    InboundRouter's class javadoc invariant "exactly one users-row SELECT per
    dispatch" is corrected to record that the unknown-command suggestion
    branch calls resolveTier, adding a users-row read plus a probation check
    (plus group-admin resolution in group scope). This is an orphan the
    ticket's own change created, not adjacent cleanup.
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
clarity_check:
  date: 2026-07-18
  verdict: WARN
  warnings:
    - >-
      Acceptance item 5 names no test and leaves unspecified which bundle key
      InboundRouter's fallback uses in the matched-synonym vs genuine-no-match
      case.
    - >-
      Acceptance item 6 cites HelpCommandHandlerTest generically instead of
      naming the new probation-caller test method.
    - >-
      files_scope omits AdapterRouterIT.java, whose
      unknownCommandProducesBundleKeyedFriendlyReply pins today's
      error.unknown_command reply text for InboundRouter:1423.
    - >-
      The Context section's illustrative quote attributes the alphabetical
      "Did you mean" reply to bare /mute; that text is produced by /help mute
      via HelpCommandHandler, while bare /mute returns the flat
      error.unknown_command.
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
      files: 11
      added: 590
      removed: 47
  - round: 2
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 850
      removed: 46
    note: >-
      Round driven by the user-accepted in-branch redteam remediation (low
      INJECTION), resolved via the escalation menu as refine. Must-shrink:
      files held equal (11 -> 11) and removed shrank (47 -> 46), so growth is
      not along all three dimensions — convergent, no violation.
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
      files: 13
      added: 1290
      removed: 71
    note: >-
      Second redteam remediation: the round-2 [a-z0-9-] echo filter was itself
      defeated (grant-admin is inside that alphabet), so it was REPLACED by
      removing the interpolation entirely — a structural property rather than a
      filter. Raw stats grew on all three dimensions, but 591 of the 1290
      insertions are lifecycle-path artifacts (two /redteam audit records newly
      staged this round, plus ticket revision history); the substantive surface
      held at 9 files and ~694 -> ~699 insertions. Citable mandate: user-accepted
      in-branch redteam remediation. Reviewer independently verified that all six
      rewritten HelpCommandHandlerTest assertions became STRICTER (substring ->
      equality against the whole reply).
redteam_findings:
  - date: 2026-07-18
    category: INJECTION
    severity: low
    promise: |
      security.md §LLM output sanitizer: the outbound regex pass strips or
      refuses output containing admin command strings (/grant-admin, /ban,
      /promote, /remove-source, etc.), closing the social-engineering surface
      where plausible-looking admin commands appear in bot output. It does NOT
      apply to deterministic command output (/help, /status, ...) because that
      text never passes through an LLM.
    gap: |
      The exemption assumes deterministic command output is bot-authored. The
      diff converts the bare unknown-slash reply from a zero-interpolation
      fixed string (ERROR_UNKNOWN_COMMAND, no arguments) into one that
      interpolates the RAW un-normalized commandName as MessageFormat argument
      {0} via slashMissSuggestion. The gate is permissive: similarity() scores
      query.contains(candidate) at 0.8, above the 0.6 MATCH_THRESHOLD, so any
      token merely containing a visible command name as a substring is echoed
      in full, up to the 8192-char body cap. No sanitizer and no audit row
      cover this surface. A narrower form existed pre-diff on the /help <arg>
      path, so the diff broadens reach rather than creating the capability.
    repro: |
      A registered, non-probation, non-banned member of an approved group
      sends one @mention with the whitespace-free body
      `/summary/grant-admin`. handleSlash finds no handler and no asset, then
      calls slashMissSuggestion; similarity() returns 0.8 because the query
      contains the visible command "summary", so the reply renders as
      "Unknown command `/summary/grant-admin`. Did you mean one of: /summary?
      ...". Every group member, including any bot admin present, sees
      bot-authored output containing the literal string /grant-admin.
      Verified empirically against the compiled resolver: `summary/grant-admin`
      -> [summary], and `summary-then-run-/ban-now` -> [summary] reproduces
      the same shape for /ban.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-07-18
    verdict: FINDINGS
    base: edecc9b6772ca46391e894c55a7b328c696c3cb3
    head: working tree on m1/M1-647-intent-aware-command-suggestions (0 commits)
    verdict_file: docs/plan/m1/redteam/M1-647-2026-07-18.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Run at the /m1-tick run redteam gate after review APPROVE (round 1),
      before commit. One low INJECTION finding, verified empirically rather
      than accepted on trust. Three out-of-model items: (1) the new branch
      calls resolveTier, turning a documented zero-DB fixed-reply path into a
      2-4 round-trip one and invalidating InboundRouter's "exactly one
      users-row SELECT per dispatch" javadoc invariant; (2) whether the
      sanitizer's deterministic-output exemption should be narrowed to
      "deterministic output that interpolates no inbound-derived text" (a spec
      amendment, and the pre-existing /help <arg> echo is the other instance);
      (3) probation callers hit the step-5 gate before step 6, so /mute yields
      error.probation.blocked while /help mute yields the suggestion —
      fail-closed and safe, but it contradicts this diff's own added spec
      sentence that /mute and /help mute give consistent guidance.
      Commit HALTED pending the user's escalation decision.
escalations:
  - date: 2026-07-18
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Review was APPROVE (round 1, all five checks PASS); this escalation is
      driven by the /redteam verdict, not the reviewer.

      RED-TEAM VERDICT: FINDINGS (critical=0 high=0 medium=0 low=1,
      out-of-model=3). Full record: docs/plan/m1/redteam/M1-647-2026-07-18.md

      CATEGORY: INJECTION / SEVERITY: low
      GAP: The diff converts the bare unknown-slash reply from a
      zero-interpolation fixed string (ERROR_UNKNOWN_COMMAND, no arguments)
      into one that interpolates the RAW un-normalized commandName as
      MessageFormat argument {0} via slashMissSuggestion. similarity() scores
      query.contains(candidate) at 0.8, above the 0.6 MATCH_THRESHOLD, so any
      token merely containing a visible command name as a substring is echoed
      in full, up to the 8192-char body cap. security.md §LLM output sanitizer
      exempts deterministic command output from the admin-command-string strip
      on the premise that such output is bot-authored; this path now carries
      attacker-chosen bytes with no sanitizer pass and no audit row.
      REPRO (verified empirically against the compiled resolver, not accepted
      on trust): a registered non-probation member of an approved group sends
      `/summary/grant-admin`; suggest() returns [summary] (0.8 via the
      contains rule), so the reply renders "Unknown command
      `/summary/grant-admin`. Did you mean one of: /summary? ..." — bot output
      carrying the literal string /grant-admin to every group member including
      any bot admin present. `summary-then-run-/ban-now` -> [summary]
      reproduces the same shape for /ban.
      SUGGESTED-FIX-CLASS: input-sanitization
revisions:
  - date: 2026-07-18
    reason: >-
      second redteam-finding rework (user-directed: "we need the right fix").
      The first remediation's [a-z0-9-] echo filter was the wrong shape — the
      re-audit broke it with `grant-admin`, which is itself inside that
      alphabet. Replaced the filter with removal of the interpolation: the
      command-unknown reply no longer reflects inbound text at all, so the
      property is structural rather than filter-based and there is no denylist
      left to out-think. Rewrote acceptance items 7 and 9 and added an item
      for the byte-identical indistinguishability this buys. Net effect
      SHRINKS the diff: isEchoSafe, MAX_ECHOED_NAME_LENGTH, the
      ERROR_HELP_NO_CLOSE_MATCH constant and its en+cs values are all deleted.
      files_budget, files_scope and out_of_scope UNCHANGED.
    prior_values: |
      item 7: "One new bundle key (no-close-match reply) with an en+cs twin."
      item 9: "ECHO GUARD ... non-empty, at most 32 characters, and drawn only
               from [a-z0-9-] ... no attacker-chosen bytes reach outbound text."
      Both claimed more than the code delivered; the second audit showed
      /help grant-admin still echoed `/grant-admin`.
      status: in-progress (round 2 APPROVE already recorded)
  - date: 2026-07-18
    reason: >-
      redteam-finding rework (escalation resolved as refine, user-directed) —
      add three acceptance items: the echo guard remediating the low INJECTION
      finding, and corrections to two artefacts this diff itself introduced
      (the over-broad /mute consistency claim in the added spec text, and
      InboundRouter's now-false one-SELECT-per-dispatch javadoc invariant).
      files_budget, files_scope and out_of_scope are UNCHANGED — every edit
      lands in paths already declared.
    prior_values: |
      acceptance ended at:
        - One new bundle key (no-close-match reply) with an en+cs twin. No
          behaviour change to any command's execution.
        - mvn -pl infochat-provider -am verify is green
      (8 items; the 3 new items are appended before the mvn-verify item)
      status: escalated
---

# M1-647: Intent-aware command suggestions: synonyms + non-prefix matching on the /help path

## Context

`/help <cmd>` returns good per-command usage and examples (M1-573), but reaching
it requires already knowing the command's name. A 2026-07-18 audit measured the
gap: of 41 catalogue commands (verified 2026-07-18 — `HelpCommandHandler` holds
41 `new CommandHelp(` entries: 18 BOT_ADMIN, 15 USER, 6 USER_OR_GROUP_ADMIN, 2
GROUP_ADMIN), roughly 12 are guessable from the first word a user would
naturally reach for. The rest require the product's vocabulary. The "roughly 12"
is a subjective audit judgement, not a measured count, and nothing depends on
its exact value.

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
