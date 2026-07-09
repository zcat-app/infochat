---
id: M1-593
title: "Provider: /summary distinguishes zero-subscriptions from empty-window, and the welcome steers a fresh user to follow a source"
status: done
created: 2026-07-08
last_updated: 2026-07-09
clarity_check:
  date: 2026-07-09
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Auto-subscribing a fresh user at registration. commands.md §Source
    management (D7) keeps subscription an explicit per-scope opt-in ("no
    auto-subscribe at registration"), and /follow-all-sources already exists as
    the bulk opt-in. This ticket is a MESSAGING/UX change only — it makes the
    empty-feed cliff legible, it does not remove it by auto-following.
  - >-
    /follow-all-sources, /add-source, /list-sources themselves, and the
    source_subscription write path. This ticket only READS a per-scope
    subscription count to pick which no-content reply to send; it changes no
    subscribe/unsubscribe behavior.
  - >-
    EligiblePostQuery's deterministic eligible-post selection, the cluster cap,
    the tag-mode / top-3-followed-tags logic, and the window filter. They are
    unchanged. The only addition is a per-scope subscription COUNT used solely to
    disambiguate the already-empty result — the determinism boundary
    (docs/spec/llm.md) is untouched (no LLM invocation on either empty branch).
  - >-
    Adding the subscription count to the happy (non-empty) /summary path. The
    count is computed ONLY when the eligible set is empty, so a normal /summary
    with posts pays no extra query.
  - >-
    The pre-scope early-outs in SummaryCommandHandler.handle() — the
    unknown-contact (no users row, ~line 190) and vanished-group race (~line 203)
    branches. They fire BEFORE a scope id exists, so there is nothing to count a
    subscription against; they keep emitting reply.summary.no_posts_yet
    unchanged. Only the post-fetch empty branch (~line 221, where a resolved
    scope id is in hand) gets the new distinction.
  - >-
    The periodic group digest path (SummaryCacheRepository / the scheduled
    pre-generated digest). This ticket is on-demand user /summary only.
  - >-
    Translation quality of the Czech twins beyond a faithful, D43-parity
    rendering of the two new/changed strings.
acceptance:
  - >-
    When on-demand /summary yields an empty eligible set AND the calling scope
    has ZERO active source subscriptions, SummaryCommandHandler emits a NEW,
    distinct bundle string (reply.summary.no_subscriptions, a new
    BundleKeys.REPLY_SUMMARY_NO_SUBSCRIPTIONS constant) whose text attributes the
    emptiness to having no followed sources and gives an actionable next step
    (naming /follow-all-sources as the way to subscribe to the available feeds) —
    NOT reply.summary.no_posts_yet, which misattributes the cause to the time
    window. No LLM invocation on this branch (unchanged determinism posture).
  - >-
    When /summary yields an empty eligible set but the calling scope HAS at least
    one active source subscription (subscribed, nothing arrived in the window),
    the reply is the existing reply.summary.no_posts_yet, unchanged. The
    subscription count is read ONLY on the empty branch (a resolved scope id in
    hand), so a /summary that returns posts runs no additional query.
  - >-
    The pre-scope early-out branches — unknown contact (no users row) and the
    vanished-group race — are unchanged: they still emit
    reply.summary.no_posts_yet (no scope id exists there to count subscriptions
    against).
  - >-
    The DM-fresh welcome message (reply.welcome.dm_fresh, en + cs) is reworded so
    a freshly-registered user (always in slow-start probation, D45) is given an
    accurate expectation instead of the current misleading "Try /summary -w 24h
    once a few sources are configured" line (which blames source configuration
    when the real gate is having NO subscriptions): content starts once they
    follow sources with /follow-all-sources (or add their own with /add-source),
    and those source-following commands unlock when the ~24h probation ends — they
    are NOT in the slow-start allowed set, so steering a probation user to follow
    a source NOW would dead-end just like /summary does. The probation-window
    notice and the reduced-command list are preserved (D45).
  - >-
    D43 bilateral keyset holds: reply.summary.no_subscriptions is added to BOTH
    en.properties and cs.properties (Czech twin), and the reworded
    reply.welcome.dm_fresh is updated in both — so BundleLoaderTest's keyset
    parity check stays green.
  - >-
    docs/spec/commands.md is amended to match: (a) §Content /summary — the text
    at lines ~261-268 that today says the zero-subscriptions case "returns the
    same 'no posts yet' reply ... covers both 'subscribed but nothing arrived'
    and 'nothing subscribed'" is revised to specify the DISTINCT no-subscriptions
    reply for the nothing-subscribed sub-case (deterministic localization-bundle
    string, still no LLM invocation); (b) §Onboarding — the D23 "steered toward
    an action that will not be empty" welcome intent is reflected as setting an
    accurate expectation for a fresh, zero-subscription (probation) user: content
    starts once they follow sources with /follow-all-sources, which unlocks after
    probation — i.e. steered toward an action that will not dead-end given the
    probation gate. Both edits are the spec coordinated with the code change (not
    a standalone spec edit).
  - >-
    NAMED TEST: SummaryCommandHandlerTest covers both empty-branch outcomes. The
    handler-tier RecordingEligiblePostQuery stub is extended with a settable
    subscription count whose DEFAULT is a positive ("subscribed") value, so every
    pre-existing empty-branch test that does not opt in keeps asserting
    reply.summary.no_posts_yet unchanged. (a) The pre-existing
    zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall test is REWRITTEN
    (renamed) to explicitly seed subscription count 0 and assert the reply equals
    the resolved reply.summary.no_subscriptions string (this is the empty+0-subs
    case; red-before/green-after on it) — the prose generator is still NOT
    invoked. (b) A case where the eligible set is empty but the subscription
    count is >0 asserts the reply is the unchanged reply.summary.no_posts_yet.
    The class-level Javadoc "Asserted invariants" bullet that today reads
    "Zero-subscriptions / empty-window branches: empty post list -> no_posts_yet
    reply" is corrected to distinguish zero-subscriptions (-> no_subscriptions)
    from empty-window-while-subscribed (-> no_posts_yet).
  - >-
    NAMED TEST (live DB): EligiblePostQueryIT gains a case that exercises the new
    countSubscriptions(scopeKind, scopeId) SQL against a live schema — mirroring
    how the sibling countFollowedTags is covered by EligiblePostQueryIT today. It
    seeds source_subscription rows for a scope and asserts the count is the number
    of rows, and asserts 0 for a scope with no subscriptions, so the real SQL
    (not only the handler-tier test double) is executed at least once.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
      — extend the inner RecordingEligiblePostQuery stub with a settable
      subscription count DEFAULTING to a positive ("subscribed") value (so the
      empty-window-while-subscribed tests keep asserting no_posts_yet).
      REWRITE the pre-existing zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall
      test (rename + seed count 0 + assert reply.summary.no_subscriptions; prose
      generator still not invoked) — this is the empty+zero-subscriptions case
      (red-before/green-after). Add the empty+has-subscriptions case (asserts
      reply.summary.no_posts_yet, no regression). CORRECT the class-level Javadoc
      "Asserted invariants" bullet at lines ~67-68 to distinguish
      zero-subscriptions (-> no_subscriptions) from empty-window-while-subscribed
      (-> no_posts_yet).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
      — add a live-DB case exercising the new countSubscriptions SQL (seed
      source_subscription rows for a scope, assert the count; assert 0 for a scope
      with none), mirroring the existing countFollowedTags IT coverage.
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest's D43 en/cs keyset-parity check (the new key + reworded
      welcome are mirrored in both bundles).
    - >-
      SummaryCommandHandlerTest's existing in-flight, rate-cap, unknown-tag, and
      terminal-summary assertions, plus the two generic no-posts tests
      (handlerNameIsLiteralSummary, inboundRouterDispatchesSummaryToHandlerExactlyOnce)
      and emptyWindowProducesNoPostsYetReplyWithoutLlmCall — these keep asserting
      no_posts_yet unchanged because the stub's default subscription count is
      positive ("subscribed"). NOT preserved:
      zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall and the class Javadoc
      invariant bullet, both rewritten per test_plan.modifies (they encode the
      pre-ticket behavior acceptance item 1 changes).
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D7
  - D23
  - D44
  - D45
reviews:
  - round: 1
    date: 2026-07-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 227
      removed: 44
escalations:
  - date: 2026-07-09
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Grounding discovery (pre-implementation, via /m1-tick run halt): a DM-fresh
      user is always in slow-start probation (D45). The probation allowed-command
      set (CommandPermissions.ALLOWED) is {help, status, get-tags, get-sources,
      list-sources, summary, saved, export, forget, lang, stop} + asset commands.
      The source-following commands /follow-all-sources and /add-source are NOT in
      that set (commands.md: "/follow-all-sources ... Blocked during slow-start
      probation"), and /list-sources shows only the caller's own subscriptions
      (empty for a fresh user; --all is bot-admin only). So the ticket's suggested
      steer "/list-sources then follow one" (acceptance items 1, 4, 6b) dead-ends
      during probation exactly like /summary does — contradicting item 4's own
      goal ("an action that will NOT dead-end"). User resolution (AskUserQuestion,
      2026-07-09): refine to a probation-honest steer that names /follow-all-sources
      and notes it unlocks after probation.
overrides: []
revisions:
  - date: 2026-07-09
    reason: >-
      clarity-fail refine: two prose blockers (bounded self-refine via /m1-tick
      run, within existing scope) plus one user-directed scope refine (the
      countSubscriptions SQL-coverage warning, resolved via the run
      AskUserQuestion by adding a live-DB IT case).
    snapshot: |
      files_budget (pre-refine): 7; files_scope: 7 paths (EligiblePostQueryIT.java
        absent). clarity_check verdict 2026-07-09: FAIL, 2 blockers + 1 warning.
      Blocker 1 (TEST-CHANGES-AUTHORIZED): the pre-existing
        SummaryCommandHandlerTest.zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall
        (lines 175-188) and the class Javadoc "Asserted invariants" bullet
        (lines 67-68) assert no_posts_yet for the zero-subscription empty case —
        exactly what acceptance item 1 changes to no_subscriptions — yet
        test_plan.preserves claimed "SummaryCommandHandlerTest's existing
        no-posts ... assertions" survive unchanged. Confirmed verbatim against
        the file.
      Blocker 2 (default unstated): four tests seed seedNoPosts() expecting
        no_posts_yet; the new settable subscription-count field's default decides
        which flip. Default was unspecified.
      Warning (FILES-BUDGET-PLAUSIBLE): the new countSubscriptions SQL would ship
        covered only by the handler-tier test double, never run against a live DB
        (unlike the sibling countFollowedTags, covered by EligiblePostQueryIT).
      acceptance item 7 (verbatim, pre-refine): "NAMED TEST:
        SummaryCommandHandlerTest gains (a) a case where the eligible set is empty
        and the scope's subscription count is 0, asserting the reply equals the
        resolved reply.summary.no_subscriptions string; and (b) a case where the
        eligible set is empty but the subscription count is >0, asserting the
        reply is the unchanged reply.summary.no_posts_yet. The handler-tier
        RecordingEligiblePostQuery stub is extended with a settable subscription
        count so both branches are driven without a live DB. Red-before/green-after
        on (a)."
      resolution: (blocker 1) move the zeroSubscriptions test + class Javadoc into
        test_plan.modifies (rewrite → seed count 0 → assert no_subscriptions),
        narrow test_plan.preserves to name only the truly-unchanged assertions;
        (blocker 2) pin the stub's default subscription count to a positive
        ("subscribed") value so the empty-window-subscribed tests keep asserting
        no_posts_yet; (warning) add EligiblePostQueryIT.java to files_scope
        (files_budget 7→8) + a live-DB acceptance item for countSubscriptions.
        complexity / risk / round_cap / security_relevant / migration_touch
        unchanged.
  - date: 2026-07-09
    reason: >-
      premise-fail refine (user-directed via /m1-tick run halt): the steer text in
      acceptance items 1, 4 & 6b prescribed pointing a fresh user at "/list-sources
      then follow one", which dead-ends during slow-start probation — /follow-all-sources
      and /add-source are probation-blocked and /list-sources shows only the
      caller's own (empty) subscriptions. Reworded to a probation-honest steer.
    snapshot: |
      acceptance item 1 steer (verbatim, pre-refine): "... gives an actionable next
        step (naming /list-sources to see available sources and how to follow one) ..."
      acceptance item 4 (verbatim, pre-refine): "The DM-fresh welcome message
        (reply.welcome.dm_fresh, en + cs) is reworded so a freshly-registered user
        with zero subscriptions is steered toward an action that will not dead-end:
        it points at following a source (e.g. /list-sources then following one) as
        the way to start receiving content, rather than the current 'Try /summary
        -w 24h once a few sources are configured' line, which misleads because the
        gate for a fresh user is having NO subscriptions, not source configuration.
        The probation-window notice and the reduced-command list are preserved (D45)."
      acceptance item 6b (verbatim, pre-refine): "... (b) §Onboarding — the D23
        'steered toward an action that will not be empty' welcome intent is
        reflected as pointing a fresh, zero-subscription user at following a source."
      resolution: reword items 1/4/6b (and the matching body prose) so the
        no_subscriptions reply names /follow-all-sources as the subscribe lever and
        the welcome sets an accurate expectation that source-following unlocks after
        the ~24h probation. files_budget / files_scope / complexity / risk /
        round_cap / security_relevant / migration_touch all unchanged; no test change
        (the branch-selection and IT tests are wording-agnostic; the bundle-value
        assertions, if any, follow the new strings).
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-593: fresh-user onboarding guidance — a distinct "no subscriptions" reply and a welcome that points at following a source

## Context

Found 2026-07-08 during live testing. A freshly-registered user has **zero
source subscriptions** (bootstrap seeding creates `source` rows but no
subscriptions — the empty-feed cliff, commands.md §Source management / D7), so
**both** `/summary` and chat dead-end with nothing, and nothing guides the user
to follow a source:

1. The DM-fresh welcome (`reply.welcome.dm_fresh`) says *"Try `/summary -w 24h`
   once a few sources are configured"* — but a fresh user follows **no** source,
   so `/summary` returns *"No posts to summarize yet for that window."*
   (`reply.summary.no_posts_yet`). That message **misattributes** the emptiness
   to the **time window** when the real reason is **zero subscriptions**. A
   distinct message for the no-subscriptions case ("you're not following any
   sources yet — subscribe with `/follow-all-sources`") is clearer and
   actionable.

2. More broadly, onboarding gives a new user no clear "here's how to start
   getting content" path. The welcome steers toward `/summary`, which — for a
   user who follows nothing — is exactly the empty dead-end.

Today the handler collapses both no-content cases into one string. In
`SummaryCommandHandler.handle()` the empty-result branch (`~line 221`) emits
`reply.summary.no_posts_yet` regardless of whether the scope has subscriptions.
And commands.md §Content **deliberately** unifies them today:

> If the calling scope has zero active subscriptions, `/summary` returns the same
> "no posts yet" reply regardless of tag mode or window size — the
> empty-eligible-set path covers both "subscribed but nothing arrived" and
> "nothing subscribed."

So distinguishing the two is a behavior change that **contradicts the current
spec text** — this ticket amends that spec paragraph in lockstep with the code
(hence code+spec, one ticket).

## The fix

Scope: a UX/messaging improvement, no new capability.

- **(a) Distinguish the two empty cases.** On the post-fetch empty branch of
  `SummaryCommandHandler.handle()` (a resolved `(scope_kind, scope_id)` in hand),
  read the scope's active subscription count via a new
  `EligiblePostQuery.countSubscriptions(scopeKind, scopeId)` helper (a
  `SELECT COUNT(*) FROM source_subscription WHERE scope_kind = ? AND scope_id = ?`,
  mirroring the existing `countFollowedTags` shape). Count `0` → emit the new
  `reply.summary.no_subscriptions` string (new
  `BundleKeys.REPLY_SUMMARY_NO_SUBSCRIPTIONS`), which names the real cause and
  points at `/follow-all-sources` as the way to subscribe to the available feeds.
  Count `> 0` → the existing
  `reply.summary.no_posts_yet`, unchanged. The count query runs **only** on the
  empty branch, so the happy path pays nothing. No LLM call on either branch —
  the determinism boundary is untouched.

- **(b) Welcome sets an accurate expectation.** Reword `reply.welcome.dm_fresh`
  (en + cs) so a fresh user (always in slow-start probation, D45) learns that
  content starts once they follow sources with `/follow-all-sources` (or
  `/add-source`), which unlock when the ~24h probation ends — instead of the
  current "once a few sources are configured" line. Steering a probation user to
  follow a source *now* would dead-end (both follow commands are probation-blocked
  and `/list-sources` shows only their own empty subscriptions), so the welcome
  sets the timing expectation rather than prescribing an immediate action.
  Preserve the probation-window notice and the reduced-command list (D45). This
  is a bundle-string edit only — the welcome is emitted from the D43 bundle by
  `InboundRouter` (`BundleKeys.REPLY_WELCOME_DM_FRESH`), no router-code change.

- **(c) Spec.** Amend commands.md §Content (the /summary empty-window/zero-subs
  paragraph) to describe the distinct no-subscriptions reply, and §Onboarding
  (the D23 "steered toward an action that will not be empty" intent) to reflect
  the follow-a-source steer.

The subscription count is per `(scope_kind, scope_id)`, so a **group** that has
subscriptions but shows an empty window for a member correctly gets
`no_posts_yet`, and a DM/group with none gets `no_subscriptions`.

## Out-of-scope

See frontmatter. Notably: **no auto-subscribe at registration** (D7 keeps it an
explicit opt-in; `/follow-all-sources` already exists as the bulk lever), no
change to the subscribe/unsubscribe write path, no change to the deterministic
eligible-post selection (only a COUNT is added, only on the empty branch), the
pre-scope early-outs stay on `no_posts_yet`, and the periodic-digest path is
untouched.

## Notes

- **Provenance.** Live-test finding 2026-07-08 (fresh-user SimpleX walkthrough).
  Not a red-team finding.
- **Why code+spec.** commands.md §Content today explicitly says the
  zero-subscriptions case "returns the same 'no posts yet' reply." Shipping the
  distinct reply without amending that sentence would leave code contradicting
  the spec, so the spec edit is coordinated with the code per the workflow's
  code+doc = one-ticket rule.
- **Test tier.** The handler-tier `SummaryCommandHandlerTest` already stubs
  `EligiblePostQuery` via `RecordingEligiblePostQuery extends EligiblePostQuery`
  and drives empty vs non-empty `Result`s without a DB; extending that stub with
  a settable subscription count keeps the branch-selection logic a fast, DB-free
  unit test, consistent with the existing no-posts assertion. Because that stub
  overrides `countSubscriptions`, the real SQL never runs under the handler-tier
  test — so `EligiblePostQueryIT` gets a live-DB case for `countSubscriptions`,
  mirroring the `countFollowedTags` IT coverage, so the query itself is exercised
  once against a real schema.
- **Stub default.** The new settable subscription count on
  `RecordingEligiblePostQuery` defaults to a positive ("subscribed") value. The
  three pre-existing empty-branch tests that model "subscribed but empty window"
  keep asserting `no_posts_yet` unchanged; only the rewritten zero-subscription
  case explicitly seeds count 0 to reach the new `no_subscriptions` reply.
- **D43.** Adding `reply.summary.no_subscriptions` to `en.properties` requires
  its `cs.properties` twin (bilateral keyset), and the reworded
  `reply.welcome.dm_fresh` is updated in both — else `BundleLoaderTest` fails.
