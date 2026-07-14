---
id: M1-622
title: "Subscription guidance copy: welcome + /follow-tag hints + empty-digest nudge (en+cs)"
status: pending
created: 2026-07-13
last_updated: 2026-07-14
blocked_by:
  - M1-621
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any behaviour change. M1-621 owns the subscription model (implicit bootstrap,
    private customs, exclusions, the RAG/follow-tag decoupling). This ticket adds
    ONLY user-facing educational strings and the points at which they are
    surfaced — no query change, no schema change, no new command, no change to
    which posts a scope retrieves.
  - >-
    The pre-release message-AUDIT of every existing bundle string
    (docs/plan/v1-verification-truth.md §6 item 5). That is a separate release
    gate; this ticket only ADDS the new subscription/tag guidance strings (which
    the audit will then also cover).
acceptance:
  - >-
    The new-user welcome/greeting states, in plain language, that the user is
    already following all of the bot's sources and can use `/follow-tag <topic>`
    to focus their digest — and that chat still searches everything. The exact
    behaviour it describes is M1-621's (bootstrap implicit; follow-tag narrows the
    digest only).
  - >-
    The `/follow-tag` (and `/unfollow-tag`) reply includes a one-line clarifier
    that the change affects the DIGEST only and chat/RAG still searches all the
    scope's sources — so a user who narrows tags is not surprised that chat stays
    broad.
  - >-
    The empty-window /summary reply (reply.summary.no_posts_yet — the
    subscribed-but-empty-window path, which under D59's implicit bootstrap is the
    normal empty case for every scope) nudges the user toward `/follow-tag`,
    consistent with the implicit-bootstrap model (a scope always has the bootstrap
    corpus, so the old "follow no sources" framing no longer applies). The
    separate zero-visible-sources reply (reply.summary.no_subscriptions) already
    steers to /follow-all-sources and is NOT in scope here.
  - >-
    Every new string is added as a bilateral en.properties AND cs.properties key
    (D43 — a one-sided key fails BundleLoaderTest), routed through the localization
    bundle (never inline), and kept short/clear (setup-script style). `mvn verify`
    is green from the repo root.
test_plan:
  adds:
    - >-
      A bundle test asserting the updated welcome, the /follow-tag + /unfollow-tag
      digest-vs-chat clarifier, and the empty-window nudge guidance strings
      resolve in both en and cs and carry the expected guidance (the
      implicit-following welcome framing, the digest-only distinction, the
      /follow-tag nudge).
  modifies:
    - >-
      bundles/en.properties AND bundles/cs.properties ONLY (D43 bilateral):
      extend the VALUES of existing keys in place — reply.welcome.dm_fresh
      (item 1, replacing the stale "Content starts once you follow sources with
      /follow-all-sources" clause left by M1-621's implicit-bootstrap switch),
      the tag-narrowing success replies reply.follow_tag.success_from_all /
      reply.follow_tag.success_in_place / reply.unfollow_tag.success_from_all /
      reply.unfollow_tag.success_in_place (item 2 digest-vs-chat clarifier), and
      reply.summary.no_posts_yet (item 3 nudge). NO new BundleKeys constants, NO
      handler changes, NO reply concatenation: the welcome / follow-tag /
      unfollow-tag / summary handlers already render these keys, so editing the
      value alone surfaces the new copy AND keeps every existing exact-equality
      test green (each derives its expected string from the same key via
      bundleLoader.get(...) or a key-derived FakeBundleLoader stub).
  preserves:
    - all tests currently green on main
    - >-
      M1-621's behaviour — this ticket adds strings only and must not alter any
      retrieval/subscription logic
    - >-
      the deterministic-UI-string path (D43): guidance comes from the bundle by
      key, never from the translator or an inline literal
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Content
decision_refs:
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-07-14
    reason: >-
      clarity-fail refine (run decision-C bounded self-refine — prose-only,
      scope-shrinking, first attempt). The clarity pre-flight FAILed on
      TEST-CHANGES-AUTHORIZED: test_plan.modifies listed BundleKeys.java + handler
      mods, implying new bundle keys concatenated onto existing composed replies,
      which would break the exact-equality assertions in
      FollowTagCommandHandlerTest, UnfollowTagCommandHandlerTest,
      SummaryCommandHandlerTest, and InboundRouterIntakeOrderingTest (none
      authorized to change) and violate test_plan.preserves "all tests green on
      main". A ground-truth read of those four tests confirmed each derives its
      expected string from the SAME bundle key (bundleLoader.get(...) or a
      key-derived FakeBundleLoader.stubFor(...)), so editing the key VALUE in
      place surfaces the new copy with zero test churn.
    snapshot: |
      test_plan.modifies (pre-refine): the welcome/greeting handler,
        FollowTagCommandHandler / UnfollowTagCommandHandler (clarifier line),
        the /summary empty-case reply path, PLUS BundleKeys.java; and
        bundles/{en,cs}.properties ("the new guidance keys, bilateral").
      test_plan.adds (pre-refine): "render the new keys".
      acceptance item 3 (pre-refine): nudge toward /follow-tag "where
        appropriate" (soft qualifier — clarity WARN).
      resolution: switched to value-edit-in-place strategy (arm a) — extend the
        VALUES of existing keys reply.welcome.dm_fresh,
        reply.follow_tag.success_{from_all,in_place},
        reply.unfollow_tag.success_{from_all,in_place}, and
        reply.summary.no_posts_yet; dropped BundleKeys.java + handler edits +
        reply concatenation from scope. Footprint now bundles/{en,cs}.properties
        + 1 bundle test (~3 files, well under files_budget: 6). acceptance item 3
        pinned to reply.summary.no_posts_yet + the concrete D59 empty-window
        condition ("where appropriate" dropped). files_budget / complexity /
        risk / round_cap / security_relevant / migration_touch unchanged.
clarity_check: {}
---

# M1-622: Subscription guidance copy — welcome + /follow-tag hints + empty-digest nudge

## Context

M1-621 makes the subscription model implicit-bootstrap + private-customs +
digest-only follow-tag. That model is only good UX if it's *explained* — the
single most confusing point (established while designing it) is "`/follow-tag`
narrows my digest but NOT my chat." Per the design decision
(`docs/plan/subscription-model-redesign.md` §UX-guidance principle), the wording
is a first-class part of the feature: short, clear, setup-script-style in-band
hints so users are not lost. This ticket adds those strings; it carries no
behaviour and is `blocked_by` M1-621 because the copy describes M1-621's
behaviour.

## Acceptance

See the YAML `acceptance:` list. In prose: add three pieces of guidance —
(1) a welcome/greeting line ("you're following all our sources; use
`/follow-tag <topic>` to focus your digest — chat still searches everything"),
(2) a `/follow-tag`/`/unfollow-tag` clarifier that narrowing affects the digest
only, and (3) an empty/narrowed-digest nudge toward `/follow-tag` — each as a
bilateral en+cs bundle key, routed through the localization bundle, short and
clear. No behaviour change; `mvn verify` green.

## Out-of-scope

Prose in the YAML `out_of_scope:`. No behaviour/query/schema/command change (that
is all M1-621); not the pre-release audit of the *existing* bundle strings (a
separate release gate) — this ticket only ADDS the new guidance strings.

## Notes

- **Copy is reviewed like code.** The exact wording matters — it's the fix for the
  digest-vs-chat confusion. Keep each string one or two short lines.
- **Bilateral keyset (D43):** every new key needs its `cs.properties` twin or
  `BundleLoaderTest` fails; scope both bundle files.
- **Deterministic UI path (D43):** guidance strings come from the bundle by key —
  never inline literals, never the translator (mixing paths is a
  determinism/sanitizer-bypass risk).
- Design authority: `docs/plan/subscription-model-redesign.md` §UX-guidance
  principle.
