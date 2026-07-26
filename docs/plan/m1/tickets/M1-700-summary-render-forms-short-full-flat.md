---
id: M1-700
title: "summary render forms --short/--full/--flat (4-mode)"
status: done
created: 2026-07-26
last_updated: 2026-07-26
clarity_check:
  date: 2026-07-26
  verdict: PASS
  warnings:
    - "line-cite drift: DigestRenderer Other-bucket tag==null check is at :316 (ticket cites :300-304, the RenderedSection record docstring) — cosmetic, no premise impact"
    - "--short must bypass CategoryRollupGenerator.categorySummaryEnabled flag gate (generateRollup short-circuits when flag off, default false); acceptance test shortFormEmitsRollupPerCategoryNoClusterProse forces emission — flag-bypassing call site is consistent with out_of_scope (protects prompt/sanitize/translate pipeline only)"
  blockers: []
redteam_findings:
  - date: 2026-07-26
    auditor: kimi (redteam-multi r1; cross-exam: opencode CLEAN)
    category: DOS
    severity: low
    promise: |
      security.md §Failure handling, Provider-side LLM failures: "/summary
      ... summarizer unreachable → fall back to the same degraded form as a
      saturated periodic digest (D17): headlines + URLs + post UIDs ... The
      friendly notice is a localization-bundle string (D43); the user is not
      shown a hung response."
    gap: |
      --short's failure containment was inherited verbatim from
      CategoryRollupGenerator's optional-prefix path: when the summarizer
      is down and every roll-up returns empty, renderShortBody shipped bare
      category headers + steering footers with NO roll-up, NO D17 degraded
      form, and NO D43 notice — unmeeting the §Failure handling promise for
      the --short surface (low severity: not a hung response).
    remediation: |
      r1 in-band — DigestRenderer.renderShortBody returns a ShortResult
      (body, anyRollupMissing); SummaryCommandHandler (--short branch) and
      RetryCommandHandler (short replay) emit REPLY_SUMMARY_DEGRADED_NOTICE
      when any roll-up came back empty. NOTE: this closed only the NOTICE
      half; r2 found the D17 degraded-CONTENT half still open (see next).
    status: superseded-by-r2
  - date: 2026-07-26
    auditor: kimi + opencode (redteam-multi r2; CORROBORATED — both
             independently found the same gap; the v1 parser split them
             into two clusters only because kimi omitted a file cite)
    category: DOS
    severity: low
    promise: |
      security.md §Failure handling (same as r1) — BOTH halves: the D43
      friendly notice AND the D17 "headlines + URLs + post UIDs degraded
      form."
    gap: |
      The r1 remediation closed only the notice half. On a full summarizer
      outage the --short reply was notice + bare headers + footers — never
      the D17 degraded content (headlines/URLs/UIDs) the promise names as
      the actual fallback. Both auditors independently identified this
      identical gap.
    remediation: |
      r2 in-band — renderShortBody's roll-up-empty branch now renders the
      deterministic D17 degraded prose for each cluster in the failed
      category via SummaryProseGenerator.degradedProseFor (which carries
      the M1-697 title redaction), so the --short degraded path delivers
      headlines + URLs + UIDs under the category header alongside the
      notice. Covered by shortFormEmitsDegradedNoticeWhenRollupFails and
      shortAnchorReplayEmitsDegradedNoticeWhenRollupFails (both assert the
      headlines now appear).
    status: remediated (r3 CLEAN — both auditors, 0 findings)
  - date: 2026-07-26
    auditor: kimi + opencode (redteam-multi r3; re-audit after the r2 src/ remediation per the step-5 "invalidation runs in both directions" rule)
    category: n/a
    severity: n/a
    note: CLEAN — 0 findings from both auditors. Both halves of §Failure handling now honored on the --short surface.
    status: clean
  - date: 2026-07-26
    auditor: opencode (redteam-multi r1/r2/r3; out-of-model, not a finding — consistent across all three rounds)
    category: DOS
    severity: informational
    note: |
      --short skips the summarizer-post-cap (>50) gate, so a wide window
      drives a CategoryRollupGenerator roll-up prompt whose input is
      unbounded by the 50-post ceiling. All rounds agreed this is NOT a
      threat-model violation (the per-user rate-bucket still applies; the
      retrieval cluster-cap bounds the absolute prompt size; the roll-up
      degrades gracefully on context-overflow). The two candidate fixes
      (apply the >50 gate, or cap the roll-up input) both contradict pinned
      acceptance (item 2: roll-up sees ALL clusters including past-cap
      ones; --short's purpose is the cheap scan that skips the per-cluster
      cap). Accepted as out-of-model residual.
    status: accepted-out-of-model
blocked_by: [M1-699]
files_budget: 19
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
  # M1-700 refine: the rename broke three pre-existing ITs that used
  # /summary --full for the flat per-cluster form; they retarget to --flat
  # (same authorized-retargeting class as SummaryIT).
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/dev/DevTerminalHarnessRoundtripIT.java
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
    renamed legacy --full; §Periodic group digests' "keeps its flat
    per-cluster format behind --full" clause is amended to reflect the
    rename. docs/spec/decisions.md D62's "/summary adopts this same
    categorized structure... keeping the flat per-cluster format behind an
    explicit --full flag" clause is amended to reflect the rename.
    docs/design/03-commands.md §3.5 documents all four render forms (the
    bare/short/full renderers + the renamed --flat) and the 6-arg
    forSummaryRendering seam. (M1-700 refine: 03-commands.md §3.5 was stale
    after the rename and is amended alongside the spec files.)
  - >-
    Help text amended (M1-700 refine): help.cmd.summary.short and
    help.cmd.summary.usage (en+cs twins) document the four render forms —
    the pre-rename help advertised [--full] as "one detailed block per
    story", which after the rename actively misdirected users (that is now
    --flat; --full is categorized-uncapped; --short is undocumented). The
    rename is incomplete without the /help surface reflecting it.
  - >-
    --short failure handling (redteam M1-700 kimi r1 remediation): when a
    category's CategoryRollupGenerator roll-up comes back empty (LLM
    outage, empty response, REFUSAL), the --short reply is prefixed with
    the D43 degraded_notice (REPLY_SUMMARY_DEGRADED_NOTICE) on BOTH the
    /summary --short path and the /retry short-anchor replay — so a
    summarizer outage surfaces a notice rather than a silent wall of empty
    headers, honoring security.md §Failure handling. Covered by
    shortFormEmitsDegradedNoticeWhenRollupFails and
    shortAnchorReplayEmitsDegradedNoticeWhenRollupFails.
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
reviews:
  - round: 1
    date: 2026-07-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 21
      added: 1526
      removed: 231
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
- `SummaryIT`, `SummaryAdapterScopeIT`, `SummaryGroupScopeIT`, and
  `DevTerminalHarnessRoundtripIT` invocations of `/summary --full` (used to
  exercise the flat per-cluster blocks whose fields they assert on) are
  retargeted to `/summary --flat`. These are the same rename consequence as
  the SummaryArgsTest/SummaryCommandHandlerTest retargetings; the four
  integration tests were not enumerated in the original ticket and surfaced
  when the r1 `mvn verify` went red. (M1-700 refine.)
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
