---
id: M1-722
title: "Digest categories render a prose paragraph per cluster: replace with count + roll-up + headlines, and add /digest brief|normal|full"
status: abandoned
abandoned_reason: decomposed
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-721
  - M1-714
files_budget: 26
files_scope:
  - infochat-core/src/main/resources/db/migration/V66__group_digest_mode.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DigestCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/StubGroupDataSource.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/spec/decisions.md
  - docs/design/02-schema.md
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
  - >-
    Any other module, with ONE carve-out: `AuditAction.java` and its
    `docs/design/02-schema.md` catalogue row, both in `infochat-core`.
    `audit_log.action` is a closed enum owned by core, so a new digest
    verb cannot be added anywhere else; the carve-out is exactly those
    two paths and one added enum constant plus one added table row. Any
    other `infochat-core` or `infochat-collector` edit is still out.
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
    A new `AuditAction.DIGEST_MODE_SET` verb is added, and each mode
    change writes one `audit_log` row with it, in the shape the handler
    already emits for `digest_enabled` (`DigestCommandHandler.java:109-120`)
    — audit-before-effect, same transaction as the UPDATE,
    `targetKind=GROUP`, `targetId`/`scopeId` = the group id — with
    `detailsJson` carrying the old and new mode. A no-op (already in the
    requested mode) writes NO row, matching `/digest on|off`.
    `docs/design/02-schema.md` §audit-action catalogue gains the matching
    row (`| DIGEST_MODE_SET | /digest brief|normal|full | group |`).
  - >-
    Reusing `DIGEST_ENABLE` for a mode change is FORBIDDEN and a test
    pins its absence. `DigestScheduler.latestDigestEnableTime` derives
    the paused-through-window carve-out from
    `WHERE action = 'DIGEST_ENABLE'` (`DigestScheduler.java:248`, and the
    convention is called load-bearing in `AuditAction.java:229-236`), so
    a `/digest brief` emitting `DIGEST_ENABLE` would move that boundary
    forward and silently suppress `digest_slot_missed` rows for every
    earlier window. The test asserts a mode change writes
    `DIGEST_MODE_SET` and leaves the group's `DIGEST_ENABLE` row set
    untouched.
  - >-
    Delivery in `normal` and `brief` is ONE outbound message, not one
    per category: the sections are joined on `"\n\n"` (the same join
    `DigestRenderer.render` already performs) into a single
    `OutboundMessage`. This narrows D63, whose per-category split exists
    to stop SimpleX's 4 000-byte chunker breaking mid-cluster — a real
    risk when a category was twelve prose paragraphs, not when it is
    five lines. `full` keeps per-category delivery, since its sections
    are still prose-sized. (The earlier "TWO messages" wording was
    wrong: `DigestWorker.executeSlot` makes exactly one delivery call
    per slot (`DigestWorker.java:303-325`) and the only candidate second
    message — the lead — is M1-725, which this ticket lists as
    out_of_scope.)
  - >-
    The batched message runs the existing TRANSIENT-retry /
    PERMANENT-abort ladder unchanged, and one digest slot still
    contributes at most ONE outcome to the per-group
    consecutive-permanent-failure counter (trivially so — there is one
    message). `renderSections` still RETURNS the per-category
    `List<RenderedSection>` in every mode, so `digest_section`
    persistence and its D65 byte-faithful replay are untouched; only
    `DigestDelivery` changes, gaining a batched send that posts the
    joined text once and, on the adapter's accept, records a
    `digest_category_delivery` row for EVERY section slug in the batch.
    All-slugs-or-none is what keeps `DigestRetryService.replayMissing`'s
    slug filter (`DigestRetryService.java:166-171`) correct: a delivered
    batch leaves nothing missing (the no-op-retry branch), a failed
    batch leaves every slug missing and the whole batch re-sends.
  - >-
    `/retry --digest` behaves correctly on both branches.
    `DigestRetryService` delegates the full re-run to
    `DigestWorker.execute(slot)` (`DigestRetryService.java:218`), which
    picks up the mode dispatch and re-renders in the group's CURRENT
    mode against the frozen cluster set (D17). The D65 byte-faithful
    replay re-posts the ORIGINALLY-RENDERED bytes and therefore stays in
    the mode the slot was rendered in. Two tests, one per branch.
  - >-
    The mode reaches the renderer by REPLACING
    `DigestRenderer.renderSections(List<Post>, String)` with a 3-arg
    form carrying the mode — NOT by adding an overload beside it. The
    test stub `RecordingDigestRenderer.java:46-47` `@Override`s the
    exact 2-arg method `DigestWorker.java:213` calls today, so an
    overload would leave the stub bound to a method nothing calls: every
    render in `DigestWorkerClockTest` and `DigestWorkerTest` would hit
    the real renderer's null `@Inject` fields and degrade, turning
    `DigestWorkerClockTest:86`'s `assertEquals(1, callCount())` red for a
    reason that reads like a clock bug. Replacing the signature makes
    the stub a COMPILE error instead — loud, and fixed in one line.
    `RecordingDigestRenderer` is updated to the 3-arg form; its call
    counting and blocking-latch behaviour are unchanged.
  - >-
    `StubGroupDataSource` becomes mode-aware. Its proxy `getString`
    returns `null` for every column but `adapter`/`upstream_group_id`/
    `language` (`StubGroupDataSource.java:63-71`) and the class is
    `final` with a 3-arg constructor, so acceptance items 5 and 9
    (per-mode LLM-call and per-mode delivery counts, both asserted at
    `DigestWorkerTest`) cannot be written without it. It gains a
    `digest_mode` column; the existing 3-arg constructor is kept
    defaulting to `normal` so `DigestWorkerClockTest` needs no edit and
    stays out of `files_scope`.
  - >-
    `DigestWorker.readGroupMetadata` is a SQL-deserialization boundary,
    so it states its own rule: a `digest_mode` that is NULL or not one
    of `brief`/`normal`/`full` resolves to `normal`, logged once at WARN.
    The CHECK constraint makes an out-of-range value unreachable through
    the app, but the column is read back through a `ResultSet` and the
    §"No defensive code" rule places validation exactly at boundaries
    like this one. A test pins the fallback.
  - >-
    Two existing assertions this ticket INVERTS are updated in place,
    not deleted, and each keeps asserting the same property under the
    new rule:
    `DigestRendererSectionsTest.proseAndRollupsCoverOnlySectionsThatSurviveTheCap:216`
    (`8 * 3` per-cluster prose calls — becomes 0 for `normal`, since
    category sections generate no per-cluster prose; the roll-up count
    of 8 is unchanged and is what still proves capped-out sections cost
    nothing) and
    `DigestWorkerTest.execute_multiSectionRenderProducesOneSendPerSection:363`
    (3 sends for 3 sections — becomes 1 batched send in `normal`, with
    the section ORDER assertion retargeted onto the joined body so the
    D62 ordering property survives, and a `full`-mode twin keeping the
    N-sends-for-N-sections assertion alive).
  - >-
    `docs/spec/commands.md` §Periodic group digests and §Conversation
    control document the three modes and the new category body, and the
    D63 row in `docs/spec/decisions.md` is amended for the batched
    delivery. The ONE new config key,
    `infochat.digest.category-headline-count` (default 5), is documented
    in `docs/design/07-deployment.md` §Configuration surface, and the
    retired `infochat.digest.category-summary-enabled` row is removed
    from the same section. The mode itself is a DB column, not a key,
    and `full`'s lifted item cap is `Integer.MAX_VALUE` in code, not a
    re-tune of `infochat.digest.category-item-cap`.
  - >-
    `DocumentedConfigKeyParityTest` (M1-708, merged 2026-07-30 after
    this ticket was authored) gates both key changes and neither key is
    in `documented-config-key-exemptions.txt`, so the doc edits and the
    code edits MUST land in the same diff: deleting the
    `@ConfigProperty` while a doc still names
    `infochat.digest.category-summary-enabled` fails the test, as does
    documenting `infochat.digest.category-headline-count` before its
    `@ConfigProperty` exists. No exemptions-file edit is needed or
    permitted — the gate is satisfied by the deletions and the addition
    the acceptance items above already require.
  - >-
    `infochat.digest.category-summary-enabled` and every line that
    exists only to serve it are DELETED, not left inert. The flag gated
    a roll-up PREFIX above per-cluster prose; under `normal` the roll-up
    IS the body, and `full` renders prose for every cluster and gains no
    prefix, so no caller remains. Enumerated from the census
    (§Census: retiring the flag), all of these go: the `@ConfigProperty`
    field (`CategoryRollupGenerator.java:103-104`), the short-circuit
    branch (`:126`), the gated wrapper `generateRollup` (`:125`) and its
    sole production call site (`DigestRenderer.java:134`), the two
    javadoc paragraphs describing the flag (`CategoryRollupGenerator.java:24`,
    `DigestRenderer.java:327`) and the stale default-instance comment
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
    (`CategoryRollupGeneratorTest.java:42,75,101,116,130`) and BOTH
    `generateRollup` stub overrides in `DigestRendererSectionsTest.java`
    (the anonymous one at `:148-153` and `RecordingRollupGenerator` at
    `:273-283` — the census previously named only one) are updated, not deleted — the
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
      fixture; normal and brief deliver ONE batched message carrying
      every section in D62 order, full delivers per category; a
      digest_mode of NULL or an unrecognized string resolves to normal;
      a saturated slot still takes the D17 degraded path in every mode;
      one failing roll-up degrades only its own section.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
      — the batched send posts one OutboundMessage and records a
      digest_category_delivery row for EVERY section slug in the batch;
      a failed batched send records NO slug, so replayMissing sees the
      whole batch as missing; the per-category path is unchanged.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
      — each of brief/normal/full sets the column and emits its
      DIGEST_MODE_SET audit row; a mode change writes NO DIGEST_ENABLE
      row (the DigestScheduler carve-out must not move); a no-op mode
      change writes no row at all; an unknown verb yields the localized
      usage error; `/digest off` then `/digest brief` leaves
      digest_enabled false; a non-admin group member and a DM caller are
      both rejected.
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
    - >-
      Every `DigestRendererTest` ASSERTION, including the
      sanitize-before-persist boundary pin
      (`renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay`).
      Its two `renderSections` call sites (`:206`, `:285`) gain the mode
      argument and nothing else — a signature-only edit, so it remains
      the byte-identity proof `DigestRendererSectionsTest`'s javadoc
      calls it.
    - >-
      `DigestWorkerClockTest` in full and UNMODIFIED — it is the pin
      that the mode work did not disturb the injected-clock
      degrade-vs-render decision. It reaches the renderer through
      `RecordingDigestRenderer` and the group row through
      `StubGroupDataSource`, both of which this ticket edits, so its
      staying green with a zero-line diff is the evidence those two
      edits were signature-only.
    - >-
      The `digest_section` / `digest_category_delivery` schema and the
      D65 byte-faithful replay path. `renderSections` returns the
      per-category list in every mode; batching happens at delivery
      only.
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
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-722: the hybrid digest body

> **ABANDONED — decomposed 2026-07-30.** Three consecutive plan passes returned
> `OUTLINE FAILED`, each naming a *different* unenumerated seam
> (`generateRollupUnconditional`'s `@Override`s → `RecordingDigestRenderer
> .renderSections` → the six `DigestRendererTest.render()` sites plus
> `DigestRetryService`). That pattern reads as a ticket too wide for its budget
> rather than one imprecisely worded, so it was split four ways rather than
> refined a third time:
>
> | Child | Scope | Depends on |
> |---|---|---|
> | M1-731 | Retire `category-summary-enabled`; rename `generateRollupUnconditional` | — |
> | M1-732 | `groups.digest_mode` (V66) + the hybrid category body | M1-731 |
> | M1-733 | `/digest brief\|normal\|full` + `AuditAction.DIGEST_MODE_SET` | M1-732 |
> | M1-734 | Narrow D63: batch brief/normal delivery into one message | M1-732 |
>
> This file is kept as the audit trail. Its censuses were re-verified live
> against the tree at `2e47e3ec` and are accurate; each child carries forward
> the part that applies to it. Nothing here is open work.

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
| `CategoryRollupGenerator.java:135,146` | **update** — javadoc + the declaration, renamed |
| `DigestRenderer.java:134` | **delete** — the prefix call, `generateRollup`'s only production caller |
| `DigestRenderer.java:46-47`, `:327` | **delete** — comments describing the gate |
| `DigestRenderer.java:315`, `:442` | **update** — two javadoc `@link`s, renamed |
| `DigestRenderer.java:361` | **update** — the `--short` call site, renamed |
| `CategoryRollupGeneratorTest.java:42,75,101,116,130` | **update** — drop the flag setup, keep every assertion |
| `CategoryRollupGeneratorTest.java:56-66` (incl. `:60`) | **delete** — `flagOffYieldsNoRollupAndNoLlmCall` asserts only the flag's off-state |
| `DigestRendererSectionsTest.java:140-153` | **update** — the anonymous `generateRollup` override and its comment |
| `DigestRendererSectionsTest.java:273-283` | **update** — `RecordingRollupGenerator`, the SECOND `generateRollup` override |
| `RetryCommandHandlerTest.java:778,806` | **update** — `@Override` renamed with the method |
| `SummaryCommandHandlerTest.java:1624,1656` | **update** — `@Override` renamed with the method |
| `docs/spec/commands.md:1982` | **delete** — the "Optional per-category roll-up" paragraph |
| `docs/design/03-commands.md:466` | **update** — the `--short` bypass aside |
| `docs/plan/m1/tickets/M1-642`, `M1-700` | **leave** — closed tickets are a historical record |

After the deletions `generateRollupUnconditional` is the only entry point
and reclaims the plain name.

## Census: the dispatch seams

The two censuses above are keyed on **identifiers**, and an identifier grep
cannot see a subclass that overrides a method it never names in text. That
blind spot cost this ticket two gates: the `generateRollupUnconditional`
`@Override`s in `RetryCommandHandlerTest` / `SummaryCommandHandlerTest` (first
refine), then `RecordingDigestRenderer`'s `renderSections` `@Override` (the
outline fail). So the seam census is keyed on **the method signatures this
ticket changes**, and the enumeration is a subtype search, not a text search:

```bash
# every production method whose signature this diff changes
for m in renderSections generateRollupUnconditional generateRollup; do
  grep -rn "class .* extends \(DigestRenderer\|CategoryRollupGenerator\)" \
    --include=*.java infochat-provider/src
  grep -rn "$m(" --include=*.java infochat-provider/src
done
```

| Changed signature | Subclass / caller | Disposition |
|---|---|---|
| `DigestRenderer.renderSections(List,String)` → 3-arg | `RecordingDigestRenderer.java:46-47` (`@Override`) | **fix** — take the mode param |
| ″ | `DigestWorker.java:213` | **fix** — pass `meta.digestMode()` |
| ″ | `DigestRenderer.java:73` (`render`) | **fix** — thin join delegates with `normal` |
| ″ | `DigestRendererTest.java:206,285` | **fix** — call sites, mode arg added; assertions untouched |
| ″ | `DigestRendererSectionsTest.java:75,95,127,160,176,197,211` | **fix** — call sites, mode arg added |
| `CategoryRollupGenerator.generateRollup` (gated) → deleted | `DigestRendererSectionsTest.java:148-153`, `:273-283` (`@Override`) | **fix** — rebind to the renamed method |
| `generateRollupUnconditional` → `generateRollup` | `RetryCommandHandlerTest.java:806`, `SummaryCommandHandlerTest.java:1656` (`@Override`) | **fix** — rename with it |

**The signature is REPLACED, never overloaded.** An overload leaves every
`@Override` above silently bound to a method nothing calls, and the resulting
failures surface as unrelated-looking degrades (`DigestWorkerClockTest:86`), not
as compile errors. Replacing makes the compiler enumerate the seams for free —
which is the durable version of this census.

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
one. So `normal` and `brief` batch their categories into a single
message; `full`, whose sections are still prose-sized, keeps the
per-category split and the reason D63 was written.

One message, not two. An earlier draft of this ticket said "TWO
messages", which is not reachable here: `DigestWorker.executeSlot` makes
exactly one delivery call per slot, and the only plausible second
message — the lead — is M1-725, which this ticket lists as out of scope.
When M1-725 lands the digest becomes lead + body; until then it is body
alone.

The partial-failure attribution rule is unchanged and load-bearing: one
slot contributes at most one outcome to the per-group
consecutive-permanent-failure counter, whose threshold of 3 was
calibrated for one message per slot. Batching makes that trivially true
rather than merely enforced.

Batching is a DELIVERY change only. `renderSections` keeps returning the
per-category list in every mode, so `digest_section` persistence and the
D65 byte-faithful replay are untouched; `DigestDelivery` gains a batched
send that posts the joined text once and records a delivery row for every
slug in the batch, keeping `replayMissing`'s slug filter honest
(all-delivered → no-op retry; batch failed → every slug missing, whole
batch re-sends).

## Notes

`infochat.digest.category-summary-enabled` is retired here, and retired
means deleted — see §Census. Its purpose was to gate a roll-up PREFIX
above per-cluster prose; in `normal` the roll-up is the body, so a flag
that switched it off would leave a category with a header, headlines and
no synthesis, and `full` renders prose for every cluster and wants no
prefix. That leaves no caller. A config key kept "just in case" after its
last caller goes is a feature flag by another name, which
`CLAUDE.md` §"No defensive code" forbids outright in a greenfield M1.

### Why a new audit verb, and why the module carve-out

`audit_log.action` is a closed enum in `infochat-core`, so "write an audit
row for the mode change" is not implementable without touching that
module — hence the single named carve-out in `out_of_scope`. Reuse was
considered and rejected on evidence, not taste: `DigestScheduler` reads
`WHERE action = 'DIGEST_ENABLE'` to derive the paused-through-window
carve-out, and `AuditAction.java:229-236` states that convention is
load-bearing. A `/digest brief` writing `DIGEST_ENABLE` would move that
boundary forward and suppress `digest_slot_missed` rows for every earlier
window — a silent, spec-visible regression on a `security_relevant`
ticket, and the kind of incidental control §10 of the engineering rules
exists to catch. `DIGEST_MODE_SET` is a new verb precisely so no existing
reader's `WHERE` clause changes meaning.

## OUTLINE FAILED (pass 3) — OPEN

Recorded by `/m1-tick start` (plan-writer subagent, 2026-07-30), verbatim.
Every cited site was independently re-verified in the main session before
escalating.

REASON: No outline exists that satisfies both `acceptance` and
`test_plan.preserves`. Acceptance item 4 makes `full` lift the per-section item
cap to `Integer.MAX_VALUE`, and items 2/5 make `normal`/`brief` render **zero**
per-cluster prose. `DigestRendererTest` — which `test_plan.preserves` guarantees
keeps "Every … ASSERTION", with `:206`/`:285` taking "the mode argument and
nothing else" — contains six *unenumerated* `render(posts, "en")` call sites
whose assertions are per-cluster-prose and item-cap assertions. Under `normal`
three of them fail; under `full` two of them still fail, because the cap lift
deletes the `+N more` line and the prose-call count they assert. There is no
mode assignment for `DigestRenderer.render` (`DigestRenderer.java:73`, whose
only callers are these tests plus `DigestRendererSectionsTest:130` — it has
**no** production caller) under which those tests stay green, so the diff must
rewrite or delete pre-existing assertions the ticket affirmatively forbids
touching. This is the same virtual-dispatch/behaviour-inversion census class the
ticket already refined for twice (the `generateRollupUnconditional`
`@Override`s, then `RecordingDigestRenderer.renderSections`), and it comes with
a security-control side effect: under `normal` the sanitize-before-persist pin
`renderSections_stripsAdminCommandTokens_beforePersistenceAndReplay`
(`DigestRendererTest.java:183-216`) passes **vacuously**, since the injected
`/grant-admin` prose never enters a section — the "byte-identity proof"
`test_plan.preserves` names would keep its name and lose its teeth
(engineering-rules §10, "Tests are controls too"). Separately, `/retry --digest`
cannot honour the batched delivery: `DigestRetryService.replayMissing`
(`:196-197`) calls the per-category `DigestDelivery.deliver`,
`DigestRetryService.java` is not in `files_scope`, `GroupReplayMeta` carries no
mode, and `files_scope` already lists exactly 26 paths against
`files_budget: 26` — zero headroom for a 27th file. So a failed `normal` batch
(acceptance item 11: "every slug missing and the whole batch re-sends")
re-sends as N per-category messages, the exact shape acceptance item 10 removes.

SUGGESTED ESCALATION: refine

EVIDENCE:

- **Unenumerated `DigestRendererTest` inversions (hard conflict with
  `test_plan.preserves`).**
  - `:102-120` `overflowLineNowSteersToSummaryFull` — sets
    `renderer.categoryItemCap = 2;` then asserts
    `result.contains("+2 more — /summary ai --full to see them")` and
    `assertEquals(2, proseGenerator.callCount())`. `full` (cap
    `Integer.MAX_VALUE`) emits no overflow line and 4 prose calls;
    `normal`/`brief` emit no overflow line and 0 prose calls. Fails in every
    mode.
  - `:122-141` `overflowForOtherBucketSteersToBareSummaryFull` — same shape
    (`categoryItemCap = 2`, `+2 more — /summary --full to see them`). Fails in
    every mode.
  - `:52-68` `render_producesLocalizedProse` —
    `assertTrue(result.contains("LLM digest summary for cluster"))` +
    `assertTrue(proseGenerator.callCount() > 0)`. Fails if `render` delegates
    with `normal`, which is exactly what §"Census: the dispatch seams" row
    `DigestRenderer.java:73 (render)` prescribes ("thin join delegates with
    `normal`").
  - The refine must (a) pick the mode `render(List,String)` delegates with and
    say so in `acceptance`, (b) give these inversions their own acceptance item
    in the same shape item 17 already uses for `DigestRendererSectionsTest:216`
    and `DigestWorkerTest:363`, and (c) state whether
    `renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay` (`:206`)
    and the degraded-arm test (`:285`) pass `full` — they are non-vacuous only
    in `full`.
- **Orphaned control the census missed.** With `full`'s cap lifted and
  `normal`/`brief` rendering no items, `DigestRenderer.java:153-165` is the sole
  producer of `reply.digest.category.more` / `reply.digest.category.more_other`
  (`en.properties:836,838`, `cs.properties:634,636`,
  `BundleKeys.REPLY_DIGEST_CATEGORY_MORE` / `_MORE_OTHER`). Both keys lose their
  last caller in every mode. The ticket enumerates the `category-summary-enabled`
  orphans exhaustively but not these; the refine should state delete-or-keep,
  since all three files are already in `files_scope`.
- **Replay delivery shape (out-of-scope, no budget).**
  `DigestRetryService.java:196-197` →
  `digestDelivery.deliver(adapter, meta.upstreamGroupId(), groupId, coords.slotFiredAt, missing)`;
  slug filter at `:166-171`; `fallbackRerun` at `:218`.
  `DigestRetryService.java` is absent from `files_scope`, and `files_scope`
  holds exactly 26 entries against `files_budget: 26`. Refine must either add
  `DigestRetryService.java` (+ raise the budget) so replay batches in
  `normal`/`brief`, or add an acceptance item stating the per-category replay is
  an accepted residual.
- **Mode type has no file.** With the budget fully claimed, a `DigestMode` enum
  cannot be a new file; it must nest in an in-scope class (precedents:
  `DigestWorker.SlotOutcome` at `DigestWorker.java:127`,
  `DigestRenderer.RenderedSection` at `DigestRenderer.java:477`). Worth stating
  so the developer does not create a 27th file.
- **Two non-resolving doc references (fix while refining).**
  `docs/design/07-deployment.md` has no §"Configuration surface" heading — its
  headings are `## 7.3 Configuration sources and precedence` (`:117`) and
  `## 7.4 Canonical application.properties` (`:131`), and the sibling key
  `infochat.digest.max-categories` sits at `07-deployment.md:300`. And
  acceptance item 22's "the retired `infochat.digest.category-summary-enabled`
  row is removed from the same section" targets a row that does not exist: a
  `grep` over `docs/` excluding `docs/plan` finds the key only at
  `docs/spec/commands.md:1983` and `docs/design/03-commands.md:466`, both
  already owned by acceptance item 24.

Confirmed sound and expected to survive a refine unchanged: `audit_log.action`
is `TEXT` with no CHECK (`V5__identity_audit.sql:256,272-275`), so
`DIGEST_MODE_SET` needs no migration; `V66` is free; `DisplayHeadline.of(Post,
LlmOutputSanitizer)` is `public static`, reachable from `provider.digest`, and
already carries the M1-697 sanitize-per-author-field control, with
`DegradedDigestRenderer.java:65-68` as the empty-headline idiom;
`DigestWorkerClockTest` does stay zero-diff; and `DocumentedConfigKeyParityTest`
behaves exactly as acceptance item 23 describes.

## OUTLINE FAILED — RESOLVED by the refine of 2026-07-30

**Status: resolved.** Every blocker below was addressed in the
`outline-fail rework` refine commit; the block is kept as the audit trail
of what the ticket got wrong, not as open work. Fix map: (1) audit verb →
`DIGEST_MODE_SET`, `files_scope` carve-out, anti-reuse acceptance item;
(2) render stub → signature REPLACED not overloaded, plus the new
§Census: the dispatch seams; (3) group stub → `StubGroupDataSource` in
scope, null/unknown mode rule stated; (4) "TWO messages" → one batched
message with the `DigestDelivery` slug-recording rule; (5) "both config
keys" → one key named; (6) census drift → line numbers corrected, both
`DigestRendererSectionsTest` overrides and `DigestRendererTest`'s two
call sites enumerated, the two inverted assertions given their own
acceptance item.

Recorded by `/m1-tick start` (plan-writer subagent, 2026-07-30), verbatim.

REASON: No implementable outline exists inside this ticket's `files_scope` /
`out_of_scope`. Two independent, ground-truthed blockers: (1) the audit
acceptance item cannot be satisfied without a new `audit_log` verb, and every
verb lives in `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`
— a different module, which `out_of_scope`'s final entry ("any other module")
forbids outright and `files_scope` does not list. Reusing the nearest existing
verb is not a fallback: `DigestScheduler.latestDigestEnableTime` derives the
paused-through-window carve-out from `WHERE action = 'DIGEST_ENABLE'`
(`DigestScheduler.java:248`), so emitting `DIGEST_ENABLE` for `/digest brief`
would silently suppress `digest_slot_missed` rows for every earlier window — a
spec-behaviour regression on a `security_relevant` ticket. (2) Dispatching on
the mode forces `DigestWorker:213` to call a mode-carrying
`DigestRenderer.renderSections` overload, but `RecordingDigestRenderer.java:47`
`@Override`s the exact 2-arg method the worker calls today and is the stub
`DigestWorkerClockTest` counts render calls through; leaving it on the old
signature turns every render into an NPE-driven degrade
(`DigestWorkerClockTest:86` asserts `callCount()==1`), and both files are
outside `files_scope`. This is the same defect class the ticket already refined
for once (the `generateRollupUnconditional` `@Override`s at
`RetryCommandHandlerTest.java:806` / `SummaryCommandHandlerTest.java:1656`),
which the census caught only by grepping identifiers, not virtual-dispatch
seams. Two acceptance items are also internally unsatisfiable and should be
fixed in the same refine so the developer does not burn a round on them.

SUGGESTED ESCALATION: refine

EVIDENCE:

- **Audit verb (hard `out_of_scope` conflict).** Acceptance: "Each mode change
  writes one `audit_log` row … (`DigestCommandHandler.java:118`)".
  `AuditLogWriter.write` takes an `AuditAction`
  (`RedactionHook.AuditRow.action(...)`, `DigestCommandHandler.java:113`);
  `AuditAction` enumerates no group-settings verb (`SET_LANG`/`SET_TIMEZONE`
  are the shape precedent, both in `infochat-core`). Harm of reuse verified at
  `DigestScheduler.java:168-179,244-257`. Refine must add
  `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`
  (+ the verb-catalogue row at `docs/design/02-schema.md:453-454`) to
  `files_scope` and carve them out of "any other module".
- **Render stub (silent red in an out-of-scope test).**
  `infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java:46-47`
  — `@Override public List<RenderedSection> renderSections(List<Post> posts,
  String langCode)`; consumers `DigestWorkerClockTest:43,52,86` and
  `DigestWorkerTest:55,67`. Add `RecordingDigestRenderer.java` to `files_scope`
  (and confirm whether `DigestWorkerClockTest.java` needs a mode-aware group
  stub).
- **Per-mode group stub.** `DigestWorkerTest:82` wires
  `new StubGroupDataSource(ADAPTER, UPSTREAM_GROUP_ID, "en")`;
  `StubGroupDataSource.groupResultSet()` returns `null` for any column but
  `adapter`/`upstream_group_id`/`language` (`StubGroupDataSource.java:63-71`),
  and the class is `final` with a 3-arg constructor. Acceptance items 5 and 9
  (per-mode LLM-call and per-mode delivery counts) need mode control, so either
  `StubGroupDataSource.java` joins `files_scope` or the ticket states that
  `DigestWorkerTest` nests its own stub. The `digest_mode` read in
  `DigestWorker.readGroupMetadata` (`GROUP_META_SQL`,
  `DigestWorker.java:381-387`) additionally needs a stated null/unknown-value
  rule at the SQL-deserialization boundary.
- **"TWO messages" is unachievable as written.** Acceptance: "Delivery is TWO
  messages, not one per category: the categories are batched into a single
  outbound message"; `test_plan.adds` repeats "normal and brief deliver two
  messages". `DigestWorker.executeSlot` makes exactly ONE delivery call per slot
  (`DigestWorker.java:303-325`), and the second message's only plausible source
  — the lead — is `M1-725` (`status: pending`), named in `out_of_scope`. The
  refine should state ONE batched message, and state whether the batched
  delivery persists ONE `digest_section` row (required for D65 replay to stay
  in-mode: `DigestRetryService.replayMissing:166-171` filters per
  `DigestSectionRepository.slugOf`, and `DigestDelivery` — not in `files_scope`
  — records one slug per correlationId).
- **"Both new config keys" names only one.** Acceptance documents "Both new
  config keys … in `docs/design/07-deployment.md`" but only
  `infochat.digest.category-headline-count` (default 5) appears anywhere in the
  ticket. Name the second key or drop to one.
- **Census line-drift and two unenumerated rename sites** (fix while refining,
  so the acceptance grep can pass): the verification grep must reduce
  `generateRollupUnconditional` to zero, but the census omits
  `DigestRenderer.java:315` and `:442`; the cited `DigestRenderer.java:125`/`:307`
  have drifted to `:134`/`:327` post-M1-721; `DigestRendererSectionsTest` has TWO
  `generateRollup` overrides (`:148-153` and `:273-283`), not the one at `:150`;
  and `DigestRendererSectionsTest.proseAndRollupsCoverOnlySectionsThatSurviveTheCap:216`
  (`8 * 3` prose calls) plus
  `DigestWorkerTest.execute_multiSectionRenderProducesOneSendPerSection:363` both
  assert behaviour this ticket inverts — neither is named in
  `test_plan.preserves` or `§Notes`.
