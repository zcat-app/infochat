---
id: M1-700
title: "summary render forms --short/--full/--flat (4-mode)"
status: pending
created: 2026-07-26
last_updated: 2026-07-26
blocked_by: [M1-699]
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryRenderFormIT.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The periodic group digest's own render and delivery path (DigestWorker,
    DigestDelivery, DigestScheduler, DigestRetryService, DigestCategorizer,
    and DigestRenderer.render / renderSections as the digest calls them).
    The digest STILL caps at infochat.digest.category-item-cap (12) and STILL
    emits the group-worded "+N more — @mention me to see them" overflow line
    (reply.digest.category.more). This ticket adds /summary-side alternatives
    only; rewording the digest broadcast overflow line is a separate follow-up
    (see Notes).
  - >-
    The digest's "+N more — @mention me to see them" wording. The original
    live complaint was that this digest line steers to a bare @mention, which
    drops into chat-mode RAG and returns irrelevant posts. This ticket gives
    users real /summary-side paths (--short, --full) but does NOT reword the
    digest's own overflow/closing lines. That rewording is a follow-up ticket.
  - >-
    Chat-mode RAG (ChatAgent, searchPosts, semanticSearch). The "got 8
    week-old news" symptom was chat-mode retrieval with an LLM-chosen
    window, not a /summary path; this ticket does not touch chat mode.
  - >-
    ClusterBlockRenderer.java — unchanged. --flat renders it byte-identically
    to the pre-rename --full output; leaving it untouched is what makes the
    rename-equivalence assertion meaningful.
  - >-
    infochat.digest.category-item-cap (12), infochat.summary.summarizer-post-cap
    (50), and their profile overrides — values unchanged. The cap-skip on
    --full is a render-side switch, not a cap re-tune.
  - >-
    CategoryRollupGenerator's prompt, sanitize, or translate pipeline —
    reused as-is. --short is a new CALL SITE for the existing generator, not
    a behavior change to it.
  - >-
    The summary_anchor.render_form column and the /retry dispatch switch —
    those land in M1-699 (blocked_by). This ticket CONSUMES the 'short' and
    'full' values the M1-699 CHECK already permits; it adds no migration.
acceptance:
  - >-
    SummaryArgs.parse accepts --short, --full, and --flat as mutually exclusive
    form flags (at most one per invocation; supplying two is a Failure). All
    three combine with a positional tag and -w. SummaryArgsTest adds cases for
    each flag alone, each flag + tag, and each flag + -w. The pre-existing
    tagWithLeadingHyphenIsMalformed and windowOutOfRangeIsRejected stay green
    unmodified — a dash-prefixed token other than -w and the three form flags
    still folds to Failure(error.summary.window_out_of_range).
  - >-
    SummaryCommandHandlerTest.shortFormEmitsRollupPerCategoryNoClusterProse
    passes: /summary --short renders ONE CategoryRollupGenerator roll-up line
    per category header, emits NO ClusterBlockRenderer per-cluster labels (no
    "[topic_id=", no "covered by:", no "summary:", "classification:", "tags:"),
    and makes NO SummaryProseGenerator call (one CategoryRollupGenerator call
    per category instead). The roll-up sees ALL clusters in the category
    including past-cap ones (CategoryRollupGenerator's existing contract).
  - >-
    SummaryCommandHandlerTest.fullFormCategorizedUncappedSkipsSectionCap
    passes: /summary --full renders categorized sections (uppercase category
    headers present) showing ALL clusters with NO 12-per-section cap and NO
    "+N more" overflow line. The summarizer-post-cap (50) degraded fallback
    still applies beyond 50 posts (existing gate at SummaryCommandHandler:285
    unchanged). Per-cluster LLM prose IS generated (unlike --short).
  - >-
    SummaryCommandHandlerTest.flatFormRendersByteIdenticalToLegacyFull passes:
    /summary --flat output is byte-identical to the pre-rename /summary --full
    flat ClusterBlockRenderer output (the seven-field per-cluster block form).
    This is the rename-equivalence pin — the only behavior change is the flag
    name.
  - >-
    RetryCommandHandlerTest.shortAnchorReplaysRollupNotClusterProse passes:
    /retry against a render_form='short' anchor re-runs CategoryRollupGenerator
    per category and emits NO per-cluster prose (SummaryProseGenerator is not
    called on this replay path). The re-rolled roll-up is a fresh LLM
    generation over the same anchored cluster set.
  - >-
    RetryCommandHandlerTest.fullAnchorReplaysCategorizedUncapped passes:
    /retry against a render_form='full' anchor replays categorized sections
    with ALL clusters (no 12-cap), matching the /summary --full shape. The
    dispatch reads render_form (the M1-699 column); this ticket adds the
    'short' and 'full' branches to the switch M1-699 structured.
  - >-
    SummaryCommandHandler writes render_form='short' for --short, 'full' for
    --full, 'flat' for --flat, 'bare' for the default (the M1-699 column;
    this ticket populates the two new values).
  - >-
    New bundle keys added as en+cs twins (D43 bilateral completeness,
    BundleLoaderTest green): a --short per-category footer steering to
    /summary <tag> and /summary <tag> --full for real categories, and an
    OTHER NEWS alternatives footer ("no tag detected, cannot expand only this
    category, alternatives: /summary, /summary --full") for the Other bucket.
  - >-
    Spec amended: docs/spec/commands.md §Content documents the four render
    forms (--short roll-up; bare categorized-capped; --full
    categorized-uncapped; --flat flat per-cluster) and notes --flat is the
    renamed legacy --full. docs/spec/decisions.md D62's "/summary adopts
    this same categorized structure... keeping the flat per-cluster format
    behind an explicit --full flag" clause is amended to reflect the rename.
  - >-
    mvn -pl infochat-provider verify is green.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryRenderFormIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D62
---

# M1-700: summary render forms --short/--full/--flat (4-mode)

## Context

The periodic digest caps each category at 12 clusters and appends
"+N more — @mention me to see them" (`en.properties:833`). That affordance
is a lie: @mentioning the bot drops into chat-mode RAG
(`searchPosts`/`semanticSearch` with an LLM-chosen window — the tool-doc
example is `P7D`, `ChatAgent.java:76`), which retrieves semantically-
relevant posts independent of the digest's truncated clusters. A live user
@mentioned after a digest and got 8 week-old posts instead of the 13 the
digest hid. There is no "next page" and no way to expand a capped category.

This ticket gives `/summary` three explicit render forms that close that
gap WITHOUT touching the digest broadcast: `--short` (a cheap roll-up
overview reusing the existing `CategoryRollupGenerator`), `--full`
(reclaimed to mean categorized-but-uncapped — all clusters under category
headers, no 12-cap), and `--flat` (the renamed legacy `--full`, flat
per-cluster blocks). The 12-cap survives only on bare `/summary` (the
reasonable default). It consumes the `render_form` anchor column +
typed `/retry` dispatch that M1-699 (blocked_by) lands first, populating
the `short`/`full` values the M1-699 CHECK already permits.

Cites `docs/spec/commands.md §Content` (the `/summary` render-form
contract) and decision D62 (which records the `--full` clause this ticket
amends).

## Acceptance

The behavioral contract is the YAML `acceptance:` list. "Done" means:

1. **Parsing** — `SummaryArgs` carries a 4-valued form (none/short/full/
   flat), mutually exclusive, combinable with tag + `-w`.
2. **`--short`** — one `CategoryRollupGenerator` roll-up per category
   header, no per-cluster prose, no ClusterBlockRenderer labels. Sees
   past-cap clusters. Zero `SummaryProseGenerator` calls; one
   `CategoryRollupGenerator` call per category.
3. **`--full` (new)** — categorized sections with ALL clusters, no 12-cap,
   no "+N more" line. `summarizerPostCap` (50) degraded fallback still
   bounds the extreme case. Per-cluster prose IS generated.
4. **`--flat` (renamed)** — byte-identical to legacy `--full` flat
   `ClusterBlockRenderer` output. Only the flag name changes.
5. **`/retry` on `--short`** — re-rolls the roll-up via
   `CategoryRollupGenerator`; skips `SummaryProseGenerator`.
6. **`/retry` on `--full`** — replays categorized-uncapped (no 12-cap).
7. **Anchor writes** — `render_form` populated for all four forms
   (M1-699's column; this ticket fills `short`/`full`).
8. **Bundle keys** — en+cs twins for the `--short` per-category footer and
   the OTHER NEWS alternatives footer.
9. **Spec** — `commands.md §Content` amended; D62 `--full` clause amended.
10. **Green** — `mvn -pl infochat-provider verify`.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing boundaries:

- **The digest broadcast is untouched.** It still caps at 12 and still
  emits the group-worded "+N more — @mention me to see them". Rewording
  that line to steer to `/summary <tag> --full` is a follow-up ticket;
  this ticket only gives users the `/summary`-side destinations that make
  such a reword honest.
- **Chat-mode RAG is untouched.** The "8 week-old posts" symptom was
  chat-mode retrieval; this ticket doesn't touch `ChatAgent`/`searchPosts`/
  `semanticSearch`.
- **`ClusterBlockRenderer` is byte-identical.** `--flat` renders it
  unchanged; that's what makes the rename-equivalence test meaningful.
- **The `render_form` column + dispatch switch land in M1-699.** This
  ticket adds no migration — it consumes the `short`/`full` values the
  M1-699 CHECK already permits.

### Authorized test retargeting (test-integrity §8 disclosure)

The pre-existing `SummaryArgsTest` and `SummaryCommandHandlerTest` cases
that assert `--full` semantics are RETARGETED by this ticket, not
preserved:

- `SummaryArgsTest` cases asserting `--full` → flat parsing are renamed to
  `--flat` with the same assertions (the flag name changes; the parsed
  form's meaning for the flat path is unchanged).
- `SummaryCommandHandlerTest` cases asserting `--full` → flat
  `ClusterBlockRenderer` output are retargeted to `--flat`.
- New cases assert `--full` → categorized-uncapped (no 12-cap, headers
  present) and `--short` → roll-up.

These retargetings are the direct, intended consequence of the rename.
Disclosed per engineering-rules §8 (unauthorized test edits are
test-integrity violations; these are authorized because they track the
documented behavior change).

## Notes

**The 4-mode model.** This is the design settled in the conversation that
filed this ticket:

| flag | cap | format |
|---|---|---|
| `--short` | n/a (roll-up) | one thematic line per category |
| *(bare)* | 12/section | categorized, per-cluster prose |
| `--full` | none (≤50) | categorized, per-cluster prose |
| `--flat` | none (≤50) | flat per-cluster blocks |

All four combine with a tag. The 12-cap survives only on bare `/summary`
(the reasonable default); `--full` and `--flat` are the two "everything"
shapes (categorized vs flat); `--short` is the cheap scan. `--flat` is
the renamed legacy `--full` (M1-694); `--full` is reclaimed for
categorized-uncapped.

**Why `--flat` not `--all-posts`.** Both uncapped modes show "all posts";
the difference is the render SHAPE (flat blocks vs categorized headers).
Naming one `--all-posts` describes the wrong axis (post count, not
format) and confuses the flag list. `--flat` describes the render shape.

**`--full` cap-skip.** `DigestRenderer.renderSummarySections` applies
`categoryItemCap` internally (`Math.min(section.clusters().size(),
categoryItemCap)`). For `--full`, pass a cap-skip (boolean, overload, or
`Integer.MAX_VALUE` — implementer's choice) to the `/summary`-side entry
point ONLY. The digest's own `render`/`renderSections` entry points are
NOT changed (out-of-scope); the cap-skip applies only to the `/summary`
path.

**`--short` reuses `CategoryRollupGenerator` as-is.** The generator
already produces one 1–2 sentence thematic roll-up per category, sees
ALL clusters including past-cap ones, and runs sanitize+translate
(`CategoryRollupGenerator.java:38-41`). `--short` is a new CALL SITE
that emits ONLY the roll-up (no per-cluster prose), not a behavior
change to the generator. One LLM call per category; zero
`SummaryProseGenerator` calls.

**`--short` delivery shape.** The roll-up overview is short enough to be
a single router-sent message (like `--flat`), not per-section (the bare
categorized form's M1-695 per-section delivery is for long sections).
The per-category footer lines ride in the single message. Implementer's
call if a per-section variant reads better; acceptance pins only the
content, not the message count, for `--short`.

**`--short` + tag.** `/summary ai --short` is valid (roll-up for one
category) but thin and not advertised in any footer. It falls out of the
parser naturally; no special handling.

**The OTHER NEWS footer.** The Other bucket has `tag == null`
(`DigestRenderer.java:300-304`); "Other" is not in the controlled
vocabulary, so `/summary other` hits `error.summary.unknown_tag`. The
`--short` Other section therefore steers to bare `/summary` and
`/summary --full` (no tag), not to a per-tag expansion. Real categories
steer to `/summary <tag>` (categorized-capped) and
`/summary <tag> --full` (categorized-uncapped). The exact copy is a
bundle key; the implementer refines the wording, the structure
(per-category footer + special Other footer) is pinned by acceptance.

**Follow-up: digest overflow rewording.** Once this ticket lands,
`/summary <tag> --full` is a real destination, so the digest's
"+N more — @mention me to see them" can honestly be reworded to
"+N more — /summary ai --full to see them". That rewording is a
separate ticket (digest broadcast surface, en+cs bundle, its own
acceptance) and is explicitly out-of-scope here.
