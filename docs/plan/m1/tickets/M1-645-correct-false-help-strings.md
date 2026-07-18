---
id: M1-645
title: "Correct three help/welcome strings that misstate the real command surface"
status: done
created: 2026-07-18
last_updated: 2026-07-18
clarity_check:
  date: 2026-07-18
  verdict: WARN
  warnings:
    - >-
      docs/design/03-commands.md is in files_scope and the Notes section directs
      updating it "in the same diff," but no acceptance item verifies it, and the
      design note's §3.11 Mode 1 text is already substantially stale (pre-D59
      wording, and no mention of /summary in its probation-unlock line at all).
    - >-
      The Notes section's list of pre-existing tests that may need fixture updates
      ends in an open-ended "and others." 11 files reference the affected
      BundleKeys constants (4 more than the 7 named), though none currently
      hardcode the false substrings this ticket corrects.
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
      files: 6
      added: 189
      removed: 17
files_budget: 8
files_scope:
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ProbationCommandListConsistencyTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryHelpFlagParityTest.java
  - docs/design/03-commands.md
complexity: low
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The two commands missing from the help catalogue entirely (/pending,
    /recover-pool) — that is M1-646, which needs NEW bundle keys. This ticket
    edits only the VALUES of keys that already exist in both locales, so the
    BundleLoaderTest keyset-equality check (D43) is untouched.
  - >-
    Command discoverability / suggestion quality — M1-647. This ticket does not
    change fuzzySuggest, add synonyms, or alter how an unknown command is
    answered.
  - >-
    The other help strings. Only the three confirmed misstatements below are in
    scope; do not sweep the remaining 120 help.* keys for style or accuracy.
  - >-
    Changing SummaryArgs to ACCEPT --since/--tag. The parser is correct and the
    help string is wrong; the fix is to the string. Do not widen the accepted
    flag set.
acceptance:
  - >-
    help.cmd.summary.short (en.properties:13, cs.properties:21) no longer
    advertises `--since` or `--tag`; it names only the argument shape
    SummaryArgs.parse accepts, matching help.cmd.summary.usage (en:71) which is
    already correct.
  - >-
    SummaryHelpFlagParityTest.advertisedSummaryFlagsAreAcceptedByParser passes —
    it extracts every `-`-prefixed token from help.cmd.summary.short and
    help.cmd.summary.usage in BOTH locales and asserts SummaryArgs.parse does
    not return a Failure for each one.
  - >-
    help.cmd.retry.short (en:29) and help.cmd.retry.usage (en:104) document the
    `--digest` sub-verb, including that it is group-scope-only and
    group-admin/bot-admin-gated, in both locales. The error keys proving the
    sub-verb exists are en.properties:619-624 and :769.
  - >-
    reply.welcome.dm_fresh (en:210, cs twin) no longer states that /summary
    unlocks when probation ends. CommandPermissions.ALLOWED contains "summary",
    and its javadoc states that same list seeds the welcome reply's {0}
    placeholder — so the message currently lists /summary as available and then
    denies it in the next sentence. The free-form-chat half of the claim is
    correct and stays.
  - >-
    ProbationCommandListConsistencyTest.welcomeReplyDoesNotContradictProbationAllowList
    passes — it asserts no command name in CommandPermissions.ALLOWED appears in
    reply.welcome.dm_fresh's "unlocks when probation ends" clause.
  - >-
    No bundle key is added or removed in either locale — `git diff` on
    en.properties and cs.properties shows value-only changes.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryHelpFlagParityTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ProbationCommandListConsistencyTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D43
---

# M1-645: Correct three help/welcome strings that misstate the real command surface

## Context

A 2026-07-18 audit of the help surface against the runtime found four drift
defects. Three are pure misstatements in existing bundle values — the help text
tells users things the code does not do — and are fixed here. The fourth (two
commands absent from the catalogue) needs new keys and is M1-646.

All three were verified against the implementation, not inferred:

**D1 — `/summary` advertises flags the parser rejects.**
`help.cmd.summary.short` reads `/summary [--since <duration>] [--tag <tag>]`,
but `SummaryArgs.parse` accepts only `-w` plus one bare positional tag; its
`else if (token.startsWith("-"))` branch folds every other dash-prefixed token
to `Failure(BUNDLE_WINDOW_OUT_OF_RANGE)`. A user who follows the help text gets
an error. The `.usage` value for the same command (`/summary [tag] [-w
<duration>]`) is already correct — the two strings contradict each other inside
one file, in both locales.

**D2 — `/retry --digest` is undocumented.** Neither `.short` nor `.usage`
mentions it, yet `error.retry.digest_group_only`,
`error.retry.digest_group_admin_required`,
`error.retry.digest_already_in_progress`, `error.retry.digest_no_prior`,
`error.retry.digest_rate_limited`, `reply.retry.digest_success`, and
`error.retry.digest_paused` all exist. A whole sub-verb with its own permission
and rate-limit story is invisible to `/help`.

**D3 — the welcome reply contradicts itself.** `reply.welcome.dm_fresh` renders
the probation-allowed command list into `{0}` and then says "Free-form chat and
your /summary digest unlock automatically when probation ends." But
`CommandPermissions.ALLOWED` includes `summary`, and that constant's own javadoc
names the welcome reply as one of the three surfaces it seeds. So the message
lists `/summary` as available and denies it two lines later. This is the same
defect class M1-624 fixed for the admin-claim welcome (see
`BundleKeys.REPLY_WELCOME_ADMIN_CLAIM` javadoc, which cites "would misstate a
claimed admin's access") — that fix split the admin path out; this one corrects
the probation path's remaining false clause.

## Acceptance

See `acceptance`. Three value edits across two locales, two guard tests, no new
keys.

## Out-of-scope

See `out_of_scope`. In particular: do NOT widen `SummaryArgs` to accept
`--since`/`--tag`. The narrow accepted set is deliberate (the parser comment
calls it "a single, narrow accepted set") and D1 is a documentation bug, not a
parser bug.

## Notes

**Why value-only edits matter.** Every key here already exists in both
`en.properties` and `cs.properties`. Editing values keeps `BundleLoaderTest`'s
bilateral keyset equality (D43) green with no cs-twin bookkeeping. Adding a new
key for any of these three would be the more expensive shape for no benefit.

**Tests pinning the old values.** Several tests reference these keys —
`HelpCommandHandlerTest`, `ProbationCommandListConsistencyTest`,
`SubscriptionGuidanceCopyTest`, `LangCommandIT`, `GoldenPathJourneyIT`,
`AdapterRouterIT`, `InviteIntakeRoundtripIT`, and others. Any assertion that
pins a corrected substring must be updated to the new value; that is an
authorized fixture change directly caused by this diff, not scope drift. If a
test asserts on the FALSE text specifically, updating it is the point of the
ticket.

**Design-note source.** `BundleKeys.REPLY_WELCOME_DM_FRESH` javadoc cites
`docs/design/03-commands.md` §3.11 Welcome messages, Mode 1 as the text's
source. Update that design note in the same diff so the source and the bundle
do not re-diverge.

**Guard-test shape.** `SummaryHelpFlagParityTest` should read the bundle values
via the same loader the handler uses, regex out `-`-prefixed tokens, and feed
each to `SummaryArgs.parse` with a plausible argument. Keep it scoped to
`/summary` — a generic across-all-39-commands flag-parity harness is a much
larger design problem (arguments are not uniformly shaped) and is not
authorized here.
