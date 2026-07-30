---
id: M1-722
title: "Digest categories render a prose paragraph per cluster: replace with count + roll-up + headlines, and add /digest brief|normal|full"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-721
  - M1-714
files_budget: 19
files_scope:
  - infochat-core/src/main/resources/db/migration/V66__group_digest_mode.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DigestCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/spec/decisions.md
  - docs/design/03-commands.md
  - docs/design/07-deployment.md
  - docs/spec/commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `CategoryRollupGenerator`'s prompt. Its input bound and scaled
    sentence budget are M1-728; this ticket calls the generator, it does
    not change what it asks for. The two tickets touch different files
    and compose in either order.
  - >-
    `/summary`'s render forms. `--short`, `--full`, `--flat` and the
    bare default keep today's behaviour exactly. `/summary` is a pull
    for a named tag; the hybrid shape is a push-surface decision.
    A diff that changes `SummaryCommandHandler`'s output has left scope.
  - >-
    The section cap (M1-721) and the lead section (M1-725). This ticket
    owns the BODY of a category section; those own how many sections
    render and what sits above them.
  - >-
    Prominence ordering (M1-724). The headlines this ticket renders are
    taken from the head of the section's existing order; when M1-724
    lands that order becomes prominence-based with no change here.
  - >-
    `CategoryRollupGenerator.buildPrompt`'s CONTENT — what the roll-up
    prompt says and how much it sends. That is M1-728. This ticket
    deletes the flag that gates whether the roll-up runs; it does not
    touch the prompt text. Both tickets edit
    `CategoryRollupGenerator.java`, which is why M1-728 is sequenced
    after this one.
  - >-
    The degraded (D17) fallback and the zero-posts fixed reply. Both are
    already single-message and mode-independent: a saturated slot
    degrades identically whatever the mode.
  - >-
    Per-group slot hours, `groups.timezone`, and `/group-timezone`.
    Still global per deployment; this ticket adds a mode column, not a
    scheduling column.
  - any other module
acceptance:
  - >-
    `V66__group_digest_mode.sql` adds
    `groups.digest_mode TEXT NOT NULL DEFAULT 'normal' CHECK (digest_mode
    IN ('brief','normal','full'))`. An additive NOT NULL column with a
    default is metadata-only on PostgreSQL 11+ (the argument
    `V44__group_digest_enabled.sql:15` makes for `digest_enabled`), so no
    table rewrite. No new grants — `groups` is already Provider-writable.
  - >-
    A category section in `normal` renders, in order: the UPPERCASE
    category header with the section's TRUE story count; the
    `CategoryRollupGenerator` synthesis; up to
    `infochat.digest.category-headline-count` (default 5) bare
    headlines, each a `DisplayHeadline` title plus its URL and NO prose;
    and the existing `reply.summary.short.category_footer` expand
    affordance. Per-cluster prose is NOT generated for category
    sections.
  - >-
    The story count in the header is the section's full cluster count,
    not the number of headlines shown. A test pins a 13-cluster section
    rendering "13" while showing 5 headlines — the count is what tells a
    reader the shape of what they are not being shown.
  - >-
    `brief` renders the same structure with NO headlines (header +
    count + roll-up + footer). `full` keeps today's per-cluster prose
    under each header with the item cap lifted to `Integer.MAX_VALUE`.
    `normal` is the hybrid above and is the default.
  - >-
    LLM call counts are asserted per mode against one 8-category /
    40-cluster fixture: `brief` and `normal` each issue exactly one call
    per surviving section (the roll-up) and ZERO
    `SummaryProseGenerator` calls for category sections; `full` issues
    one per rendered cluster. This is the assertion proving the hybrid
    is cheaper and not merely shorter.
  - >-
    Headlines add no LLM call and no new truncation rule — they reuse
    M1-714's `DisplayHeadline` helper, so a blank Bluesky title falls
    back to its body and a 24 000-character nitter title is truncated by
    the same code path as every other display surface.
  - >-
    `/digest brief|normal|full` sets the mode; `/digest on|off` keeps its
    exact current meaning against `digest_enabled` and is NOT folded into
    the mode column. A test pins that `/digest off` then `/digest brief`
    leaves the group paused. Permissions, the DM rejection and the
    no-op-when-unchanged branch all match `/digest on|off` exactly by
    reusing its existing checks.
  - >-
    Each mode change writes one `audit_log` row in the shape the handler
    already emits for `digest_enabled` (`DigestCommandHandler.java:118`),
    with `detailsJson` carrying the old and new mode.
  - >-
    Delivery is TWO messages, not one per category: the categories are
    batched into a single outbound message. This narrows D63, whose
    per-category split exists to stop SimpleX's 4 000-byte chunker
    breaking mid-cluster — a real risk when a category was twelve prose
    paragraphs, not when it is five lines. The batched message still
    runs the existing TRANSIENT-retry / PERMANENT-abort ladder, and one
    digest slot still contributes at most ONE outcome to the per-group
    consecutive-permanent-failure counter. `full` keeps per-category
    delivery, since its sections are still prose-sized.
  - >-
    `/retry --digest` behaves correctly on both branches.
    `DigestRetryService` delegates the full re-run to
    `DigestWorker.execute(slot)` (`DigestRetryService.java:218`), which
    picks up the mode dispatch and re-renders in the group's CURRENT
    mode against the frozen cluster set (D17). The D65 byte-faithful
    replay re-posts the ORIGINALLY-RENDERED bytes and therefore stays in
    the mode the slot was rendered in. Two tests, one per branch.
  - >-
    `docs/spec/commands.md` §Periodic group digests and §Conversation
    control document the three modes and the new category body, and the
    D63 row in `docs/spec/decisions.md` is amended for the batched
    delivery. Both new config keys are documented in
    `docs/design/07-deployment.md` §Configuration surface.
  - >-
    `infochat.digest.category-summary-enabled` and every line that
    exists only to serve it are DELETED, not left inert. The flag gated
    a roll-up PREFIX above per-cluster prose; under `normal` the roll-up
    IS the body, and `full` renders prose for every cluster and gains no
    prefix, so no caller remains. Enumerated from the census
    (§Census: retiring the flag), all of these go: the `@ConfigProperty`
    field (`CategoryRollupGenerator.java:103-104`), the short-circuit
    branch (`:126`), the gated wrapper `generateRollup` (`:125`) and its
    sole production call site (`DigestRenderer.java:125`), the two
    javadoc paragraphs describing the flag (`CategoryRollupGenerator.java:24`,
    `DigestRenderer.java:307`) and the stale default-instance comment
    (`DigestRenderer.java:46-47`).
  - >-
    `generateRollupUnconditional` is renamed to `generateRollup`,
    reclaiming the plain name. "Unconditional" only ever distinguished
    it from the gated sibling being deleted, so leaving it would name a
    contrast that no longer exists. This is an orphan THIS change
    creates, so cleaning it up is in scope; it is the only rename in the
    diff.
  - >-
    The rename carries to its two SUBCLASS overrides outside the digest
    package — `RecordingCategoryRollupGenerator` in
    `RetryCommandHandlerTest.java:806` and in
    `SummaryCommandHandlerTest.java:1656`, plus each one's javadoc
    `{@link ...#generateRollupUnconditional}` (`:778`, `:1624`). Both
    carry `@Override`, so leaving them is a COMPILE break, not a style
    nit. Mechanical rename only: no assertion, no stub behaviour and no
    call count in either file changes.
  - >-
    The five test sites setting `gen.categorySummaryEnabled = true`
    (`CategoryRollupGeneratorTest.java:42,75,101,116,130`) and the
    `generateRollup` stub override in
    `DigestRendererSectionsTest.java:150` are updated, not deleted — the
    behaviour each asserts (determinism across calls, localization,
    sanitization, failure handling) still holds; only the flag setup
    goes. A test that disappears with the flag would be coverage lost,
    not dead code removed.
  - >-
    The ONE exception is `CategoryRollupGeneratorTest
    .flagOffYieldsNoRollupAndNoLlmCall` (`:56-66`, including its
    `// categorySummaryEnabled left at its default false.` comment at
    `:60`), which is DELETED. Its whole subject is the flag's off-state
    — "flag off → no roll-up, no LLM call" — so once the gate is gone
    there is no behaviour left for it to assert. It is dead code removed,
    not coverage lost; the other five keep every assertion.
  - >-
    `docs/spec/commands.md` §Periodic group digests loses its "Optional
    per-category roll-up" paragraph (`:1982`), which documents the flag,
    its default and its ship-off rationale, and `docs/design/03-commands.md:465-466`
    loses the "bypassing the digest's `category-summary-enabled` flag"
    aside describing `--short`'s bypass of a gate that no longer exists.
    The D62/D63 rows in `docs/spec/decisions.md` are checked for the
    same reference.
  - >-
    Verification is a re-runnable grep, not an eyeball:
    `grep -rn "category-summary-enabled\|categorySummaryEnabled\|generateRollupUnconditional"
    --include=*.java --include=*.properties --include=*.md . | grep -v
    '^./.bench' | grep -v '^./docs/plan'` returns ZERO hits. `docs/plan`
    is excluded because closed tickets (M1-642, M1-700) are a historical
    record and are not rewritten.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — a normal-mode section renders header+count+rollup+5
      headlines+footer in that order; the count is the full cluster
      count not the headline count; a section with fewer clusters than
      the headline count renders only what it has and no filler; brief
      renders no headlines; full renders per-cluster prose; headlines
      carry no prose and reuse DisplayHeadline for a blank title and an
      over-long one.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
      — per-mode LLM call counts against one 8-category/40-cluster
      fixture; normal and brief deliver two messages, full delivers per
      category; a saturated slot still takes the D17 degraded path in
      every mode; one failing roll-up degrades only its own section.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
      — each of brief/normal/full sets the column and emits its audit
      row; an unknown verb yields the localized usage error; `/digest
      off` then `/digest brief` leaves digest_enabled false; a non-admin
      group member and a DM caller are both rejected.
  preserves:
    - >-
      Every existing `DigestCommandHandlerTest` assertion for `/digest
      on|off`, including the no-op-when-already-in-that-state branch
      (`DigestCommandHandler.java:99`) and its friendly reply.
    - >-
      `/summary --short`'s behaviour and its one-call-per-category
      count — `renderShortBody` and the renamed `generateRollup` keep
      their existing callers working unchanged.
    - >-
      `DigestRetryServiceTest`, `DigestRetryConcurrencyIT` and the
      per-group serialization of `/retry --digest`.
    - >-
      The D63 partial-failure attribution rule: one slot contributes at
      most one outcome to the consecutive-permanent-failure counter, so
      a transport blip cannot soft-remove a healthy group.
    - >-
      `DegradedDigestRendererTest` in full.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Conversation control
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D17
  - D62
  - D63
  - D65
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-722: the hybrid digest body

## Census

The defect class is "a production call site that renders a periodic
digest body":

```bash
grep -rn "renderSections\|renderShortBody\|degradedRenderer.render" \
  --include=*.java infochat-provider/src/main/java
```

| Site | Disposition |
|---|---|
| `DigestWorker.java:213` (`renderSections`) | **fix** — dispatch on `groups.digest_mode` |
| `DigestWorker.java:209`, `:233` (`degradedRenderer.render`) | unchanged — D17 path is mode-independent |
| `DigestRetryService.java:218` (`digestWorker.execute`) | inherits the dispatch |
| `SummaryCommandHandler.java:332`, `:410`, `:513`, `:522` | out of scope — `/summary` |
| `RetryCommandHandler.java:288`, `:349`, `:355` | out of scope — `/summary` anchor replay |

One production dispatch point.

## Census: retiring the flag

`grep -rn "category-summary-enabled\|categorySummaryEnabled\|generateRollupUnconditional"
--include=*.java --include=*.properties --include=*.md .` outside `.bench` and
`docs/plan` — the same three alternatives the verification acceptance item runs,
so the table below is exactly what that grep must reduce to zero:

| Site | Disposition |
|---|---|
| `CategoryRollupGenerator.java:103-104` | **delete** — the `@ConfigProperty` field |
| `CategoryRollupGenerator.java:126` | **delete** — the short-circuit branch |
| `CategoryRollupGenerator.java:125` `generateRollup` | **delete** — the gated wrapper |
| `CategoryRollupGenerator.java:24` | **delete** — javadoc describing the flag |
| `DigestRenderer.java:125` | **delete** — the prefix call, `generateRollup`'s only production caller |
| `DigestRenderer.java:46-47`, `:307` | **delete** — comments describing the gate |
| `CategoryRollupGeneratorTest.java:42,75,101,116,130` | **update** — drop the flag setup, keep every assertion |
| `CategoryRollupGeneratorTest.java:56-66` (incl. `:60`) | **delete** — `flagOffYieldsNoRollupAndNoLlmCall` asserts only the flag's off-state |
| `DigestRendererSectionsTest.java:140-150` | **update** — the stub override and its comment |
| `RetryCommandHandlerTest.java:778,806` | **update** — `@Override` renamed with the method |
| `SummaryCommandHandlerTest.java:1624,1656` | **update** — `@Override` renamed with the method |
| `docs/spec/commands.md:1982` | **delete** — the "Optional per-category roll-up" paragraph |
| `docs/design/03-commands.md:466` | **update** — the `--short` bypass aside |
| `docs/plan/m1/tickets/M1-642`, `M1-700` | **leave** — closed tickets are a historical record |

After the deletions `generateRollupUnconditional` is the only entry point
and reclaims the plain name.

## Context

Today every cluster a digest renders costs a prose paragraph, and a
group's only volume control is `/digest on|off` — the alternatives the
spec names, `/unfollow-tag` and `/unfollow-source`, narrow what the group
can *retrieve*, not merely what it is *sent*, because D59 scopes
`/summary` and chat search to the same world. So a group that finds the
digest too long can lose topics everywhere, switch it off, or endure it.

## The shape

```
SECURITY — 13 stories
  Three supply-chain attacks, an OpenSSL DoS, and a WordPress RCE.
  · CVE-2026-1234 — OpenSSL heap overflow  <url>
  · npm chalk/debug compromise  <url>
  · [3 more headlines]
  /summary security to expand
```

Roughly five lines and one LLM call per category, whether the category
holds three stories or three hundred. Depth lives in the lead (M1-725);
breadth lives in the headlines, which cost a line each and no LLM call at
all, since a headline is a `DisplayHeadline` title plus a URL already
carried on the cluster.

Measured against the same 8-category fixture:

| | Lines | LLM calls |
|---|---|---|
| today (`full`, cap 12) | ~380 | ~96 |
| `normal` | ~64 + lead | 8 + lead |
| `brief` | ~32 | 8 |

## Why the count is the full section size

A reader seeing five headlines under "SECURITY" cannot tell whether that
is all of it or a fifth of it. The count is the cheapest possible signal
of what is being withheld, and it is what makes the `/summary` footer an
informed choice rather than a guess.

## Delivery, and why D63 narrows

D63 delivers one message per category to stop SimpleX's 4 000-byte
line-based chunker splitting inside a cluster. That was a live concern
when a category was twelve prose paragraphs. A five-line category cannot
split mid-cluster, and nine notifications for one digest is worse than
two. So `normal` and `brief` batch their categories into a single
message; `full`, whose sections are still prose-sized, keeps the
per-category split and the reason D63 was written.

The partial-failure attribution rule is unchanged and load-bearing: one
slot contributes at most one outcome to the per-group
consecutive-permanent-failure counter, whose threshold of 3 was
calibrated for one message per slot.

## Notes

`infochat.digest.category-summary-enabled` is retired here, and retired
means deleted — see §Census. Its purpose was to gate a roll-up PREFIX
above per-cluster prose; in `normal` the roll-up is the body, so a flag
that switched it off would leave a category with a header, headlines and
no synthesis, and `full` renders prose for every cluster and wants no
prefix. That leaves no caller. A config key kept "just in case" after its
last caller goes is a feature flag by another name, which
`CLAUDE.md` §"No defensive code" forbids outright in a greenfield M1.
