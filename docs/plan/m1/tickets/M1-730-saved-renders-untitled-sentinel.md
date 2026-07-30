---
id: M1-730
title: "/saved renders the ingest \"untitled\" sentinel to a reader"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The four chat tools that also emit a bare `title`
    (`ListSavesTool`, `SearchPostsTool`, `SemanticSearchTool`,
    `GetReferencesTool`). Their output is JSON reinjected into the chat
    prompt, not text shown to a reader, and `"untitled"` versus `""`
    conveys the same nothing to the model. That they return no body at
    all is a separate content-visibility question, not this ticket's.
  - >-
    The two LLM prompt builders (`SummaryProseGenerator.buildPrompt`,
    `CategoryRollupGenerator.buildPrompt`). `DisplayHeadline`'s javadoc
    states it is display-only and must never feed prompt input — a
    bounded headline would have the model summarize a fragment. Both
    already append the body on the line after the title.
  - >-
    Removing the `IngestTextNormalizer.UNTITLED_TITLE` sentinel from the
    write path. That decision carries a retention-window complication
    (see §Notes) and is not taken here.
  - >-
    Backfilling `saved_post` rows snapshotted with the sentinel, and any
    change to what `/save` snapshots. `saved_post.body` is already
    written by `SaveCommandHandler`; this ticket only reads it.
  - infochat-collector/**
  - infochat-core/**
acceptance:
  - >-
    A `/saved` line for a post saved with no upstream title shows text
    derived from the saved body instead of the word `untitled`.
  - >-
    `SavedCommandHandler` obtains that text from the shared
    `DisplayHeadline` derivation rather than a second inline fallback,
    so `/saved` cannot drift from the three surfaces M1-729 fixed.
  - >-
    CONTROL PRESERVATION (engineering-rules §10). The path being
    replaced is `SavedCommandHandler.java:275`, which today calls
    `llmOutputSanitizer.sanitize` on the title before interpolating it
    into the bundle line template. The replacement must keep that
    redaction, and must keep its UNIT at ONE author's field per call
    (M1-697) — title OR body, never a concatenation. A test asserts a
    command-shaped saved BODY promoted to the `/saved` line is redacted,
    which is the newly reachable leg: before this ticket the body could
    not reach that line at all.
  - >-
    The `{3}` tags placeholder at `SavedCommandHandler.java:278` keeps
    its own separate `sanitize` call. It is a second field and must not
    be folded into the headline derivation.
  - >-
    A saved post with neither title nor body still renders a stable
    line — no blank where the headline was, and no leaked sentinel.
  - >-
    `SavedCommandHandlerTest`'s existing title-rendering assertions are
    updated in step, not deleted.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  preserves:
    - >-
      M1-729's `DisplayHeadline` behaviour and every existing test in
      `DisplayHeadlineTest` — this ticket may ADD an entry point for
      callers holding a title/body pair rather than an
      `EligiblePostQuery.Post`, but must not change the derivation.
    - >-
      The `saved_post` snapshot contract (Invariant 6): `/saved` reads
      the snapshot columns and never re-resolves against `post`.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
escalation_reason:
---

# M1-730: `/saved` renders the ingest "untitled" sentinel to a reader

## Context

M1-729 (merged 2026-07-30) made the three digest/summary render surfaces
recognise `IngestTextNormalizer.UNTITLED_TITLE` as "no title" so
`DisplayHeadline`'s body fallback fires. It deliberately stopped there:
its acceptance named only `/summary`, the periodic digest and the
degraded digest.

`/saved` was left behind. `SavedCommandHandler.java:81` selects
`post_uid, title, url, snapshot_tags, personal_tags, saved_at` — no
`body` — and interpolates the title into the `REPLY_SAVED_LINE` bundle
template at line 275. For a post saved from Bluesky, Nostr, or a Reddit
item with no title, that line reads `untitled` while the post's real
text sits unread in `saved_post.body`, a column V15 declares and
`SaveCommandHandler.java:144` already writes.

This is the only *human-facing* surface still showing the sentinel. It
is what stands between the current state and deleting the sentinel
outright — see §Notes.

## Census

Every provider site that reads a stored `title` column, enumerated
mechanically:

    grep -rn 'p\.title\|p2\.title\|, title,\|"title"\|\.title()' \
      --include='*.java' infochat-provider/src/main/java

| Site | Disposition |
|---|---|
| `command/SavedCommandHandler.java:81,238,275` | **fix** — human-facing list; renders the sentinel with no body available |
| `chat/tool/ListSavesTool.java:89,130` | out-of-scope — JSON to the model; `""` and `"untitled"` are equally uninformative |
| `chat/tool/SearchPostsTool.java:147,183` | out-of-scope — same, and its entries are under a `MAX_RESULT_BYTES` budget |
| `chat/tool/SemanticSearchTool.java:150,231` | out-of-scope — same |
| `chat/tool/GetReferencesTool.java:91` | out-of-scope — same |
| `chat/tool/GetPostTool.java:62,82` | not affected — selects `p.body` alongside |
| `command/SaveCommandHandler.java:115,144,343` | not affected — selects and snapshots both title and body |
| `command/RetryCommandHandler.java:90,445` | not affected — selects `p.title, p.url, p.body` |
| `summary/EligiblePostQuery.java:220,291,302` | not affected — carries body; feeds `DisplayHeadline` (M1-729) |
| `digest/DigestPostCollector.java:112,139,157` | not affected — same |
| `summary/SummaryProseGenerator.java:181` | out-of-scope — LLM prompt input, body appended on the next line |
| `digest/CategoryRollupGenerator.java:199` | out-of-scope — same |
| `help/TopicCorpusBuilder.java:280` | not affected — a help topic's title, not a post's |

## Acceptance

See the YAML `acceptance:` list. In prose: `/saved` stops printing the
storage sentinel and shows saved body text instead, obtained from the
shared `DisplayHeadline` derivation rather than a second inline copy;
the existing per-field `sanitize` control on that line survives the
change with its one-field unit intact; and the no-title-no-body case
still renders a stable line.

## Out-of-scope

The four chat tools and the two prompt builders are listed above with
reasons. The distinction is deliberate and worth restating: those six
sites hand `title` to a *model*, and `DisplayHeadline`'s own javadoc
forbids using it for prompt input because a bounded headline makes the
model summarize a fragment. Only `/saved` renders a stored title
straight to a person.

Removing the sentinel from `PostPersister` is also excluded. It is the
natural next step once this ticket lands, but it is a decision with its
own complication (§Notes) rather than a mechanical follow-through, and
folding it in here would put a collector-side and a core-side file into
a provider-scoped ticket.

## Notes

- Adjacent code: `DisplayHeadline.of(Post, LlmOutputSanitizer)` takes an
  `EligiblePostQuery.Post`, which `/saved` does not have — its rows come
  from `saved_post`. An overload taking the title/body pair keeps one
  derivation without forcing a synthetic `Post`; that shape is a
  suggestion, not a commitment.

- **The open decision this unblocks.** Once no human-facing surface
  renders the sentinel, `PostPersister.normalizeTitle` could store `""`
  instead, and the column would record what the source actually
  reported — the rule M1-723 states for `likes`/`reposts` (NULL means
  "not reported", which is not 0). Two things need deciding first, and
  neither is settled here. (1) `post` retention is 30 days (14 on `pi`),
  so rows already carrying the sentinel outlive the change; the
  reader-side match in `DisplayHeadline` would have to stay until they
  age out, which reads like the backwards-compatibility shim CLAUDE.md
  forbids even though it is really live-data handling. (2)
  `saved_post` rows are snapshots and are NOT retention-bounded, so a
  sentinel snapshotted today is permanent — which is a second reason
  this ticket's fallback reads the body rather than trying to fix the
  stored value.

- M1-729's commit message (`3737adb4`) records the full route analysis
  and the consumer split this census refines.
