---
id: M1-593
title: "Provider: /summary distinguishes zero-subscriptions from empty-window, and the welcome steers a fresh user to follow a source"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
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
    (naming /list-sources to see available sources and how to follow one) —
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
    a freshly-registered user with zero subscriptions is steered toward an action
    that will not dead-end: it points at following a source (e.g. /list-sources
    then following one) as the way to start receiving content, rather than the
    current "Try /summary -w 24h once a few sources are configured" line, which
    misleads because the gate for a fresh user is having NO subscriptions, not
    source configuration. The probation-window notice and the reduced-command
    list are preserved (D45).
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
    an action that will not be empty" welcome intent is reflected as pointing a
    fresh, zero-subscription user at following a source. Both edits are the spec
    coordinated with the code change (not a standalone spec edit).
  - >-
    NAMED TEST: SummaryCommandHandlerTest gains (a) a case where the eligible set
    is empty and the scope's subscription count is 0, asserting the reply equals
    the resolved reply.summary.no_subscriptions string; and (b) a case where the
    eligible set is empty but the subscription count is >0, asserting the reply
    is the unchanged reply.summary.no_posts_yet. The handler-tier
    RecordingEligiblePostQuery stub is extended with a settable subscription
    count so both branches are driven without a live DB. Red-before/green-after
    on (a).
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
      — add the empty+zero-subscriptions case (asserts reply.summary.no_subscriptions)
      and the empty+has-subscriptions case (asserts reply.summary.no_posts_yet,
      no regression); extend the inner RecordingEligiblePostQuery stub with a
      settable subscription count.
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest's D43 en/cs keyset-parity check (the new key + reworded
      welcome are mirrored in both bundles).
    - >-
      SummaryCommandHandlerTest's existing no-posts, in-flight, rate-cap,
      unknown-tag, and terminal-summary assertions.
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D7
  - D23
  - D44
  - D45
reviews: []
escalations: []
overrides: []
revisions: []
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
   sources yet — use `/list-sources` / follow a source") is clearer and
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
  points at `/list-sources` and following a source. Count `> 0` → the existing
  `reply.summary.no_posts_yet`, unchanged. The count query runs **only** on the
  empty branch, so the happy path pays nothing. No LLM call on either branch —
  the determinism boundary is untouched.

- **(b) Welcome points at following a source.** Reword `reply.welcome.dm_fresh`
  (en + cs) so a fresh, zero-subscription user is steered to follow a source
  (e.g. `/list-sources` then follow one) instead of the current "once a few
  sources are configured" line. Preserve the probation-window notice and the
  reduced-command list (D45). This is a bundle-string edit only — the welcome is
  emitted from the D43 bundle by `InboundRouter`
  (`BundleKeys.REPLY_WELCOME_DM_FRESH`), no router-code change.

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
  a settable subscription count keeps the new branch a fast, DB-free unit test,
  consistent with the existing no-posts assertion.
- **D43.** Adding `reply.summary.no_subscriptions` to `en.properties` requires
  its `cs.properties` twin (bilateral keyset), and the reworded
  `reply.welcome.dm_fresh` is updated in both — else `BundleLoaderTest` fails.
