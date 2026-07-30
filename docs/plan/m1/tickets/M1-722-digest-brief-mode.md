---
id: M1-722
title: "Groups have no volume control between a full prose digest and no digest at all: add /digest brief"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 11
files_scope:
  - infochat-core/src/main/resources/db/migration/V66__group_digest_mode.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DigestCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `DigestRenderer.renderShortBody` itself (`DigestRenderer.java:328`)
    and `CategoryRollupGenerator`. Both exist, ship green, and are
    exercised today by `/summary --short` and `RetryCommandHandler.java:288`.
    This ticket WIRES the existing renderer to the periodic digest; a
    diff that modifies the renderer's output has left scope, because
    that would change `/summary --short` too.
  - >-
    `/summary`'s `--short` flag, its argument grammar and its render
    path. Unchanged.
  - >-
    The per-digest cluster budget (M1-721). `brief` reduces prose
    DEPTH — roll-up per category instead of a paragraph per cluster —
    while the budget reduces cluster COUNT. They compose and neither
    subsumes the other, but this ticket must not also change how many
    clusters are selected.
  - >-
    `infochat.digest.category-summary-enabled`. That flag PREFIXES a
    roll-up onto the existing per-cluster prose, making the digest
    longer; `brief` REPLACES the per-cluster prose. The flag's default
    (`false`) and behaviour are untouched, and a diff that repurposes
    it as the brief switch has left scope — it is a deployment-wide
    flag and this is a per-group setting.
  - >-
    The degraded (D17) fallback and the zero-posts fixed reply. Both are
    already single-message and mode-independent: a saturated slot
    degrades identically whether the group is `normal` or `brief`.
  - >-
    Per-group slot hours, `groups.timezone`, and `/group-timezone`.
    Still global per deployment (spec §Periodic group digests names
    per-group hour overrides a v2 candidate); this ticket adds a mode
    column, not a scheduling column.
  - any other module
acceptance:
  - >-
    `V66__group_digest_mode.sql` adds
    `groups.digest_mode TEXT NOT NULL DEFAULT 'normal' CHECK (digest_mode
    IN ('brief','normal','full'))`. Additive nullable-free column with a
    default is metadata-only on PostgreSQL 11+ (the same argument
    `V44__group_digest_enabled.sql:15` makes for `digest_enabled`), so
    no table rewrite. The migration grants no new privileges — `groups`
    is already Provider-writable.
  - >-
    `/digest brief|normal|full` sets the mode; `/digest on|off` keeps
    its exact current meaning against `digest_enabled` and is NOT
    folded into the mode column. The two are orthogonal: an `off` group
    in `brief` mode stays off. A test pins that `/digest off` followed
    by `/digest brief` leaves the group paused.
  - >-
    Permissions match `/digest on|off` exactly — group admin or bot
    admin, group scope only, friendly error in DM. The new verbs
    inherit the existing check rather than introducing a second
    authorization path; a test asserts a non-admin group member's
    `/digest brief` is rejected with the same localized error as their
    `/digest off`.
  - >-
    Each mode change writes one `audit_log` row in the same shape the
    handler already emits for `digest_enabled`
    (`DigestCommandHandler.java:118`), with `detailsJson` carrying the
    old and new mode. A test pins the row.
  - >-
    `brief` renders via `DigestRenderer.renderShortBody` and is
    delivered as ONE message, not one per category — it has no
    per-category section bytes for D63's delivery loop to split on.
    `normal` keeps today's per-category delivery unchanged. `full`
    renders per-category with the item cap lifted
    (`Integer.MAX_VALUE`), matching `/summary --full`.
  - >-
    A `brief` digest issues exactly one LLM call per category (the
    roll-up) and ZERO `SummaryProseGenerator` calls. A test asserts
    both counts against a 4-category / 30-cluster fixture — this is the
    assertion that proves `brief` is cheaper and not merely shorter.
  - >-
    A roll-up failure in `brief` mode degrades that category to its
    headlines rather than dropping it or failing the digest, mirroring
    the flag-off behaviour the spec already commits to for
    `category-summary-enabled` ("a roll-up failure yields that
    category's message WITHOUT a prefix ... never a degraded or blocked
    digest"). A test pins a single failing category leaving the other
    three intact.
  - >-
    `/retry --digest` behaves correctly on BOTH of its branches.
    `DigestRetryService` delegates the full re-run to
    `DigestWorker.execute(slot)` (`DigestRetryService.java:218`), so
    that branch picks up the mode dispatch for free and re-renders in
    the group's CURRENT mode against the frozen cluster set (D17). The
    D65 byte-faithful replay of an undelivered category re-posts the
    ORIGINALLY-RENDERED bytes and therefore stays in the mode the slot
    was rendered in — a mode changed between the slot and the retry does
    NOT rewrite stored bytes. Two tests, one per branch; the replay test
    pins that a `normal`→`brief` change between slot and retry still
    replays the stored per-category bytes.
  - >-
    `docs/spec/commands.md` §Periodic group digests and §Conversation
    control document the three modes, and the D63 row in
    `docs/spec/decisions.md` is amended to state that per-category
    delivery applies to `normal` and `full` only — D63 currently reads
    as unconditional for any non-degraded digest with at least one
    post, which `brief` falsifies.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
      — each of brief/normal/full sets the column and emits its audit
      row; an unknown verb yields the localized usage error; `/digest
      off` then `/digest brief` leaves digest_enabled false; a
      non-admin group member is rejected; the same command in DM is
      rejected.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
      — a brief-mode group produces exactly one outbound message; LLM
      call counts are one-per-category rollup and zero per-cluster
      prose; a normal-mode group is byte-identical to today; a
      full-mode group renders every cluster with no overflow line; one
      failing rollup degrades only its own category; a saturated brief
      slot still takes the D17 degraded path.
  preserves:
    - >-
      Every existing `DigestCommandHandlerTest` assertion for `/digest
      on|off`, including the no-op-when-already-in-that-state branch
      (`DigestCommandHandler.java:99`) and its friendly reply.
    - >-
      Every existing `DigestWorkerTest` and `DigestRoundtripIT`
      assertion. Groups default to `normal`, so every pre-existing
      fixture must produce byte-identical output — this is the
      regression guard for the whole ticket.
    - >-
      `DigestRendererTest` and `CategoryRollupGeneratorTest` in full;
      the renderer is not modified.
    - >-
      `/summary --short` assertions in `SummaryCommandHandlerTest` —
      `generateRollupUnconditional`'s existing callers keep their
      behaviour.
    - >-
      `DigestRetryServiceTest`, `DigestRetryConcurrencyIT` and the
      per-group serialization of `/retry --digest`.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Conversation control
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D17
  - D62
  - D63
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-722: `/digest brief` — a volume control that is not a kill switch

## Census

The defect class is "a production call site that renders a periodic
digest body". Enumerated rather than assumed:

```bash
grep -rn "renderSections\|renderShortBody\|degradedRenderer.render" \
  --include=*.java infochat-provider/src/main/java
```

| Site | Disposition |
|---|---|
| `DigestWorker.java:213` (`renderSections`) | **fix** — dispatch on `groups.digest_mode` |
| `DigestWorker.java:209`, `:233` (`degradedRenderer.render`) | unchanged — D17 degraded path is mode-independent |
| `DigestRetryService.java:218` (`digestWorker.execute`) | inherits the dispatch; no separate change |
| `SummaryCommandHandler.java:332`, `:410`, `:513`, `:522` | out of scope — `/summary`, driven by its own flags |
| `RetryCommandHandler.java:288`, `:349`, `:355` | out of scope — `/summary` anchor replay, driven by `summary_anchor.render_form` |

The single production dispatch point is `DigestWorker.java:213`. That is
what makes this a wiring ticket: one call site chooses between three
renderers that all already exist.

## Context

A group's only lever over digest volume today is
`/digest on|off` (`DigestCommandHandler.java:49-53`, toggling
`groups.digest_enabled`). The spec's own framing of the alternatives is
`/unfollow-tag` and `/unfollow-source` — both of which narrow what the
group can *retrieve*, not merely what it is *sent*, because D59 scopes
`/summary` and chat search to the same subscription world.

So a group that finds the twice-daily digest too long has three options:
lose topics from every surface, turn the digest off entirely, or put up
with it. There is nothing in between.

## What already exists

`DigestRenderer.renderShortBody(List<Cluster>, String)`
(`DigestRenderer.java:328`) renders one `CategoryRollupGenerator`
synthesis per category header — a 1–2 sentence "Three supply-chain
attacks, an OpenSSL DoS, and a WordPress RCE" per section — with no
per-cluster prose and no flat blocks. It ships green behind
`/summary --short` and is already called from
`RetryCommandHandler.java:288`.

The periodic digest never calls it. `DigestWorker.java:213` goes
straight to `renderSections`. The compact digest is a wiring change, not
a rendering one.

## Why a per-group column and not a config key

`infochat.digest.category-summary-enabled` is deployment-wide, and the
groups on one deployment differ: a 5-person security group wants every
paragraph, a 200-person general group wants four lines. The mode belongs
next to `digest_enabled` on `groups`, set by the same admins, audited
the same way.

## The D63 interaction

D63 commits that a non-degraded digest with at least one post is
delivered as one message per category. `brief` produces a single body
with no per-category message boundary, so it is single-message like the
degraded and zero-posts paths. That is a genuine narrowing of D63 and is
amended in the decision row rather than left as an undocumented
exception — a reader of D63 today would predict per-category delivery
for a brief digest and be wrong.

## Notes

`full` is included because the column's CHECK constraint costs nothing
to widen now and the renderer already accepts `Integer.MAX_VALUE`
(`DigestRenderer.java:226`). It is the honest complement to `brief`: a
group that wants everything should be able to say so rather than
relying on the per-category cap's default happening to be generous.
