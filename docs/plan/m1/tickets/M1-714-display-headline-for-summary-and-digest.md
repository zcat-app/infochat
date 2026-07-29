---
id: M1-714
title: "Summary/digest headline renders a raw post title: unbounded for social sources, blank for Bluesky"
status: pending
created: 2026-07-29
last_updated: 2026-07-29
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The two LLM-PROMPT call sites, which must keep receiving the full
    untruncated title: `SummaryProseGenerator.java:179` (summarizer
    prompt) and `CategoryRollupGenerator.java:199` (digest rollup
    prompt). Truncating either would have the model summarize a
    fragment. `CategoryRollupGenerator.java` is deliberately absent from
    `files_scope` so a diff cannot reach it; a diff that touches
    `SummaryProseGenerator.java:179` has left scope.
  - >-
    `post.title` itself, the fetchers that write it, and the ingest
    pipeline. This is a presentation-layer change only. Trimming at
    ingest would corrupt the stored source content and change what
    embeddings and entity extraction operate on.
  - >-
    Making the length limit operator-configurable. `ClusterBlockRenderer`
    is hand-constructed at `SummaryCommandHandler.java:661` and
    `RetryCommandHandler.java:339`, so a `@ConfigProperty` would have to
    be threaded through both handlers — two files this ticket has no
    other reason to touch. Ships as a constant; see §Notes for the
    follow-up.
  - >-
    Translating the headline. Whether source-derived headline text
    should reach `TranslationPipeline` for non-`en` scopes is a spec
    question (`docs/spec/llm.md` §Translation flow commits that source
    post bodies are never translated), not a rendering fix. Untouched
    here.
  - >-
    `post.body_summary`, which is never written and is a candidate
    headline source that therefore does not work. That is M1-715; this
    ticket must not start populating it.
  - >-
    `TopicCorpusBuilder.java:280`. That `title()` is a help `Topic`, not
    a `Post` — same method name, unrelated type.
  - any other module
acceptance:
  - >-
    A new `DisplayHeadline` helper derives a one-line headline from a
    `Post`: the title when non-blank, else the body. The result is
    truncated to a bounded length with a trailing ellipsis when it was
    cut, and returned unchanged when it already fits.
  - >-
    Truncation is codepoint-safe: cutting never splits a surrogate pair.
    A title whose cut point falls inside an astral-plane character (an
    emoji) yields a string that round-trips through
    `String.codePointCount` with no unpaired surrogate. 287 of 1868
    nitter titles in the live corpus carry emoji, so this is the common
    case, not an edge case.
  - >-
    Truncation happens AFTER `LlmOutputSanitizer.sanitize`, never before.
    The sanitizer writes one `audit_log` row per hit
    (`LLM_OUTPUT_SANITIZED`, two hits → two rows); truncating first would
    drop the audit rows for the removed tail and narrow the sanitizer's
    input unit. A test pins that a title whose only flagged span sits
    beyond the truncation point still produces its audit row.
  - >-
    The truncator never emits a partially-cut
    `LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT` marker: if the cut
    point falls inside a marker the output ends before it.
  - >-
    `ClusterBlockRenderer.java:95` renders via the helper. A cluster
    whose first post has an empty title (all 729 Bluesky posts in the
    live corpus) no longer emits a blank headline line.
  - >-
    `SummaryProseGenerator.degradedProseFor` (`:224`) and
    `DegradedDigestRenderer` (`:59`) render via the same helper, so the
    three display surfaces cannot drift.
  - >-
    `SummaryProseGenerator.java:179` and `CategoryRollupGenerator.java:199`
    still append the FULL title. A test asserts the summarizer prompt
    contains a long title in full.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
      — short title unchanged; long title truncated with ellipsis; empty
      title falls back to body; empty title AND empty body yields a
      stable non-blank placeholder rather than an empty line; emoji at
      the cut boundary is not split; a sanitizer replacement marker
      straddling the cut is not emitted partially.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
      — an empty-title post renders a non-blank headline line; a flagged
      span positioned BEYOND the truncation point still yields its
      LLM_OUTPUT_SANITIZED audit row (pins sanitize-then-truncate order);
      the summarizer prompt still carries the untruncated title.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
      — an empty-title post renders a non-blank headline; a long title is
      truncated with an ellipsis.
  preserves:
    - >-
      Every existing `ClusterBlockRendererTest` assertion, including the
      M1-675 closed-list sanitization of the headline and the M1-697
      sanitize-unit invariant (one author's field per sanitize call —
      the helper must not concatenate several posts' bytes into one
      sanitize input).
    - >-
      `DegradedDigestRendererTest` in full, including its degraded-path
      assertions.
    - >-
      `SummaryProseGeneratorTest` / `TranslationPipelineIT` LLM
      call-count assertions — the helper adds no LLM call.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/llm.md §Translation flow
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-714: Summary/digest headline renders a raw post title

## Context

`ClusterBlockRenderer.appendClusterBlock` opens every cluster with the
first post's title, verbatim:

```java
out.append(llmOutputSanitizer.sanitize(first.title())).append("\n");
```

That is correct for an RSS article, where `title` is a headline. It is
wrong for the social sources, because for those `title` is not a
headline — it is the post.

Measured over the 9,236 posts in the live-test corpus:

| kind | posts | avg title | max title | avg body |
|---|---|---|---|---|
| rss | 6,158 | 74 | 39,407 | 1,037 |
| **nitter** | **1,868** | **334** | **24,776** | 646 |
| **bluesky** | **729** | **0** | **0** | 172 |
| youtube | 315 | 70 | — | 0 |
| odysee | 166 | 59 | — | 2,445 |

Two distinct defects fall out of that table.

**Unbounded headlines.** A nitter title averages 334 characters, with
444 posts over 400 and the longest at 24,776 — X allows very long posts
for paying accounts, so this is the upstream format, not corruption.
Sample: *"I never did this for money though. When I started I
desperately wanted to do something with malware, but I didn't know how
to get into the field. My first job paid me $11.50/hr…"* (1,191 chars).
The headline line is meant to be a scannable anchor above the summary
prose; at that length it buries the block it labels.

**Blank headlines.** All 729 Bluesky posts have `length(title) = 0` —
not short, empty. The append is unconditional, so a cluster whose first
post is from Bluesky emits an empty line between `[topic_id=…]` and the
covered-by line, and the block leads with nothing identifying it. 728 of
those 729 have usable body text (avg 172 chars), so a body fallback
resolves essentially all of them.

nitter + bluesky are 2,597 of 9,236 posts here (28%); in the production
`bootstrap-sources.json` they are 31 of 79 sources.

## Census

The defect class is "a `src/main` site that renders `Post.title()` into
user-visible output". Enumerated rather than assumed:

```bash
grep -rn "\.title()" --include=*.java infochat-provider/src/main/java
```

Six sites, in three dispositions:

| Site | Disposition |
|---|---|
| `command/ClusterBlockRenderer.java:95` | **fix** — display headline |
| `summary/SummaryProseGenerator.java:224` | **fix** — display, `degradedProseFor` |
| `digest/DegradedDigestRenderer.java:59` | **fix** — display, degraded digest |
| `summary/SummaryProseGenerator.java:179` | **must not touch** — summarizer PROMPT input |
| `digest/CategoryRollupGenerator.java:199` | **must not touch** — rollup PROMPT input |
| `help/TopicCorpusBuilder.java:280` | not-a-post — help `Topic.title()`, unrelated type |

The prompt/display split is the whole reason this ticket is scoped to a
shared helper called from three places rather than a change to `Post` or
to the fetcher. A trim applied at either of those altitudes would hit
the prompt sites too, and the resulting damage — the summarizer silently
summarizing a truncated post — produces no error and no failing test.

Re-run at `start`. A seventh site means the class grew and this scope is
wrong.

## Acceptance

- `DisplayHeadline` derives a bounded one-line headline: title when
  non-blank, else body, ellipsis when cut.
- Codepoint-safe cutting — no split surrogate pairs (15.4% of nitter
  titles carry emoji).
- Truncation runs after `sanitize`, so per-hit `LLM_OUTPUT_SANITIZED`
  audit rows survive, and never emits a half-cut replacement marker.
- The three display sites use the helper; the empty-title case no longer
  renders a blank line.
- The two prompt sites still pass the full title, pinned by a test.
- `mvn verify` from the repo root is green.

## Out-of-scope

The prompt call sites, `post.title` and the fetchers, operator
configuration of the limit, translation of headline text, and
`body_summary` (M1-715). See frontmatter for the reasoning on each.

## Notes

- **`security_relevant: true`, deliberately.** The diff changes the
  input to a sanitize call on a group-broadcast path. M1-675 added that
  sanitization because a line-start title shaped like a privileged
  command reflects straight into a broadcast reply, and M1-697 pinned
  the sanitize-unit invariant (one author's field per call). Ordering
  truncation around the sanitizer is exactly the kind of "preserve the
  controls of a path you replace" change engineering-rules §10 governs:
  the granularity of the sanitize input is part of the control. It wants
  a `/redteam` pass.

- **Sanitize-then-truncate, not the reverse.** Truncating first would be
  *safer* in the narrow sense that flagged text beyond the cut never
  reaches the user — but it would silently drop that hit's audit row and
  shrink what the sanitizer sees, which is a control regression. The
  order in the acceptance criteria is the deliberate one.

- **The all-empty case.** One Bluesky post has neither title nor body.
  The helper must return something stable and non-blank for it rather
  than reintroducing the empty line this ticket exists to remove; a
  bundle-keyed placeholder is the natural fit, and the bundle needs the
  `cs` twin (D43 bilateral keyset) if a new key is added.

- **Follow-up, not committed to here.** Making the limit a config key is
  genuinely useful for a multi-instance deployment where different
  audiences want different headline lengths, but it requires threading
  `@ConfigProperty` through `SummaryCommandHandler` and
  `RetryCommandHandler`, which hand-construct `ClusterBlockRenderer`.
  File it separately if wanted.
