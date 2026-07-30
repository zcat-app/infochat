---
id: M1-714
title: "Summary/digest headline renders a raw post title: unbounded for social sources, blank for Bluesky"
status: done
created: 2026-07-29
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseInjectionTest.java
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
  - >-
    Discarding a text-less post from retrieval instead of rendering it.
    A post with neither title nor body (1 of 9,236 in the live corpus)
    may still be an image-only or link-only Bluesky post whose url is
    the payload, and the reply-extension case that would justify
    keeping it is not representable: `post_reference.link_type` is
    `CHECK (link_type IN ('entity','semantic','repost'))` (V29) with no
    `reply` edge, and no fetcher emits one. Filtering here would drop
    exactly the reply-extensions worth keeping, with nothing in the
    data to tell them apart. Eligibility lives in `EligiblePostQuery` /
    `DigestPostCollector`, outside this presentation-only change; the
    omit-the-token behaviour above is forward-compatible with a later
    filter (the branch simply stops being reachable). Follow-up.
  - any other module
acceptance:
  - >-
    A new `DisplayHeadline` helper derives a one-line headline from a
    `Post`: the title when non-blank, else the body. The result is
    truncated to a bounded length with a trailing ellipsis when it was
    cut, and returned unchanged when it already fits. When title AND
    body are both blank the helper returns the empty string — there is
    no headline to render and no placeholder is invented.
  - >-
    The body fallback is sanitized on the same terms as the title:
    `LlmOutputSanitizer.sanitize` runs over whichever single field the
    helper selected. The body is source-authored text that reaches a
    group-broadcast line start for the first time via this change, so
    it inherits the M1-675 threat the title already carries. The
    sanitize unit stays ONE author's field per call (M1-697): the
    helper selects title OR body and sanitizes that, and must never
    concatenate the two into one sanitize input.
  - >-
    Truncation is codepoint-safe: cutting never splits a surrogate pair.
    A title whose cut point falls inside an astral-plane character (an
    emoji) yields a string that round-trips through
    `String.codePointCount` with no unpaired surrogate. 287 of 1868
    nitter titles in the live corpus carry emoji, so this is the common
    case, not an edge case.
  - >-
    Whitespace flattening happens BEFORE `LlmOutputSanitizer.sanitize`,
    never after (redteam 2026-07-30, medium/INJECTION). The sanitizer's
    `isTokenSeparator` is the ASCII whitespace set only and
    `canonicalizeForMatching` (NFKC + `stripBidiAndZeroWidth`) leaves
    U+2028/U+2029/U+0085 intact, so a rewrite applied AFTER sanitize can
    turn a token the sanitizer saw as unsplittable
    (`/quarantine<U+2028>approve <uuid>`) into a dispatchable multi-word
    command in the delivered bytes, with no `LLM_OUTPUT_SANITIZED` row.
    Flattening first means the sanitizer inspects exactly the bytes that
    are delivered. Runs are still REPLACED with a space, never deleted,
    so the reverse splice (`/list` + `-sources`) stays impossible. A test
    pins that a headline carrying U+2028 between a closed-list command
    word and its second word is redacted and audited.
  - >-
    No call-site javadoc states a pipeline order that contradicts
    `DisplayHeadline`'s (redteam 2026-07-30 round 2, low/INJECTION). The
    round-1 fix reordered the pipeline, but the same diff added
    "(sanitize, then flatten, then bound)" to `ClusterBlockRenderer`'s
    class javadoc — the previously-vulnerable order, asserted with no
    WHY, in the file carrying the M1-675 group-broadcast rationale a
    maintainer reads first. Behaviour is correct and pinned by the two
    U+2028 tests above, so this is a regression channel rather than a
    live defect: it invites a future change to "restore" the documented
    order and re-arm the splice, and leaves a maintainer reconciling the
    two javadocs with nothing to adjudicate on. Each of the three call
    sites either states the actual order (flatten, then sanitize, then
    truncate) or states no order at all and defers to `DisplayHeadline`
    — the load-bearing WHY stays in one place rather than being copied
    to three that can drift independently.
  - >-
    Truncation happens AFTER `LlmOutputSanitizer.sanitize`, never before.
    The sanitizer writes one `audit_log` row per hit
    (`LLM_OUTPUT_SANITIZED`, two hits → two rows); truncating first would
    drop the audit rows for the removed tail and narrow the sanitizer's
    input unit. A test pins that a title whose only flagged span sits
    beyond the truncation point still produces its audit row.
  - >-
    The body fallback is length-bounded BEFORE it reaches `sanitize`
    (redteam 2026-07-30, low/DOS). `post.title` is capped at
    `IngestTextNormalizer.TITLE_MAX_LENGTH` = 200 at the write boundary,
    but `post.body` has no cap at any write path, so passing a whole body
    to the sanitizer runs NFKC, the markdown-link regex, 24 closed-list
    matchers and 10 tokenizer scans over an unbounded input — on the
    digest scheduler thread, once per post in the window. The pre-bound
    applies ONLY to the body fallback and is generous relative to the
    display bound, so a flagged span anywhere near the visible region is
    still matched and audited; this does not weaken the
    sanitize-then-truncate rule above, which continues to govern the
    display cut. A test pins that an over-long body does not reach the
    sanitizer in full.
  - >-
    `truncate` never emits a partially-cut Stage 1 placeholder
    `[REDACTED:<id>]` (redteam 2026-07-30, out-of-model). Stage 1
    redactions live in the body, which is now a headline source, and
    `docs/spec/security.md` §Ingest pipeline commits the brackets and the
    `REDACTED:` literal as byte-identical so consumers recognise the
    marker by exact-match. The existing guard for the sanitizer's own
    `[redacted command]` marker generalises to both markers.
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
    On an empty helper result each of the three sites omits the headline
    token AND the separator that would have followed it, so no site
    emits a blank line or a dangling separator. The remaining operands
    are non-empty at every site, so each line still leads with real
    content: the cluster block keeps `[topic_id=…]`, covered-by, score,
    summary, classification and tags; `degradedProseFor` yields
    `<url> (uid <uid>)`; `DegradedDigestRenderer` yields
    `<sourceDisplayName>` with the url on the following line.
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
      title falls back to body; empty title AND empty body yields the
      empty string (no invented placeholder); a blank-title post's body
      is sanitized, and title and body are never concatenated into one
      sanitize input; emoji at the cut boundary is not split; a
      sanitizer replacement marker straddling the cut is not emitted
      partially; a Stage 1 `[REDACTED:<id>]` placeholder straddling the
      cut is not emitted partially either; U+2028 between a closed-list
      command word and its second word is redacted (flatten runs before
      sanitize) rather than surviving into a dispatchable line; an
      over-long body is bounded before it reaches the sanitizer.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
      — an empty-title post renders a non-blank headline line; a post
      with empty title AND empty body emits no headline line at all
      (the block still opens with `[topic_id=…]` and is followed
      directly by the covered-by line — no blank line between them); a
      flagged span positioned BEYOND the truncation point still yields
      its LLM_OUTPUT_SANITIZED audit row (pins sanitize-then-truncate
      order).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseInjectionTest.java
      — the summarizer prompt still carries a long title in FULL,
      untruncated. This assertion cannot live in
      `ClusterBlockRendererTest` as first drafted:
      `SummaryProseGenerator.buildPrompt` is package-private in
      `…provider.summary`, so only a test in that package can call it,
      and this file already owns the `buildPrompt` harness.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
      — an empty-title post renders a non-blank headline; a post with
      empty title AND empty body renders the source display name with no
      leading ` — ` separator; a long title is truncated with an
      ellipsis.
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
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      assertion_adequacy: WARN
    diff_stats:
      files: 14
      added: 1778
      removed: 41
    note: |
      ASSERTION-ADEQUACY WARN (advisory, not a rework item): the third
      consumer of the derived headline —
      `SummaryProseGenerator.degradedProseFor`'s empty-headline shape —
      has no end-of-path assertion. Making its `if (!lead.isEmpty())`
      append unconditional emits a leading space before `(uid …)` for an
      all-blank post, which the acceptance item forbids, and no test
      fails. The reviewer WARNed rather than FAILed because the mutation
      is cosmetic rather than security-bearing (the sanitize call, its
      one-field unit and its audit row are pinned elsewhere) and
      `test_plan` never promised a test at that site. The other two
      consumers DO have end-of-path assertions, so the M1-648
      assert-only-the-producer failure mode is avoided.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-30
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer — the closed-list pass
      matches against the canonical form, "the same representation the
      deterministic command parser consumes", and "Every match is
      audit-logged (per-occurrence, not throttled)."
    gap: |
      DisplayHeadline applies its whitespace rewrite AFTER sanitize, so
      the bytes the sanitizer inspected are not the bytes delivered.
      LlmOutputSanitizer.isTokenSeparator is the ASCII whitespace set
      only, and canonicalizeForMatching (NFKC + stripBidiAndZeroWidth)
      leaves U+2028/U+2029/U+0085 intact, so
      `/quarantine<U+2028>approve <uuid>` reaches sanitize as ONE token:
      no match, no LLM_OUTPUT_SANITIZED row. flattenToOneLine then
      rewrites it to the dispatchable `/quarantine approve <uuid>` at a
      group-broadcast line start. The helper's javadoc guarded
      splice-by-DELETION and missed splice-by-REPLACEMENT.
    repro: |
      Publish (or use a legacy blank-title row carrying) headline text
      `/quarantine<U+2028>approve <uuid>` or `/list-sources<U+2028>--all`.
      Body normalization keeps U+2028; the post goes READY and is
      selected into a digest or /summary cluster. sanitize matches
      nothing and writes no audit row; flattenToOneLine converts U+2028
      to a space; the group receives a copy-paste-dispatchable
      privileged command with no audit trail for an operator to notice.
    suggested_fix_class: input-sanitization
  - date: 2026-07-30
    category: DOS
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer — the flag tokenizer is
      "a single left-to-right scan ... linear in the reply length",
      because §Trust boundaries item 9 puts a hostile endpoint's reply in
      scope and "an in-cap reply must not be convertible into unbounded
      CPU."
    gap: |
      The linearity argument assumes a bounded input. Before this diff
      the only sanitize input on this render path was post.title, capped
      at IngestTextNormalizer.TITLE_MAX_LENGTH = 200 at the write
      boundary. The body fallback has no length cap at any write path,
      and the display bound is applied LAST, so the full sanitizer pass
      (NFKC, markdown-link regex, 24 closed-list matchers, 10 tokenizer
      scans) plus this diff's own whitespace rewrite run over an
      unbounded input before anything is truncated. The degraded digest
      renders every post in the window through the helper.
    repro: |
      A blank-title post with a multi-megabyte body enters a group's
      digest window; each render pays the full sanitizer cost over the
      whole body before the 200-char display bound is applied.
    suggested_fix_class: other
  - date: 2026-07-30
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer — "the group-visible
      echo surfaces (`/saved` reply, `/summary`, and the degraded group
      digest) are additionally passed through the closed-list
      `LlmOutputSanitizer` at **render**, where a title or tag whose
      canonical form is a privileged command renders as
      `[redacted command]`", and §"Canonical-form matching" — the pass
      matches "the **canonical** form of the candidate output ... which
      is the same representation the deterministic command parser
      consumes". Both commitments hold only if no transform runs between
      the sanitizer and delivery; that ordering is the control the
      round-1 INJECTION finding was about.
    gap: |
      The remediation reordered the pipeline to flatten -> sanitize ->
      truncate (`DisplayHeadline.java:97`, documented as load-bearing at
      `DisplayHeadline.java:25`), but the diff simultaneously ADDS a
      javadoc line at one of the three call sites asserting the
      opposite, previously-vulnerable order as the design intent:
      `ClusterBlockRenderer.java:46-47` — "Deriving the headline is a
      deterministic pure function (sanitize, then flatten, then bound)".
      That directly contradicts `DisplayHeadline.java:25` in the same
      diff, in the file carrying the M1-675 group-broadcast rationale a
      maintainer reads first. The security argument for the ordering —
      the sanitizer's token separators are ASCII-only and its canonical
      form preserves U+0085/U+2028/U+2029, so a post-sanitize whitespace
      rewrite manufactures a dispatchable token the sanitizer never
      adjudicated and never audited — appears only in `DisplayHeadline`;
      the caller's javadoc records the wrong order with no WHY at all,
      so a reader reconciling the two has nothing to adjudicate with.
    repro: |
      Not reachable against the code as it stands. The ordering is
      behaviourally correct and pinned by
      `DisplayHeadlineTest.unicodeSeparatorInsideAClosedListEntryIsRedactedNotRewrittenIntoACommand`
      and `...unicodeSeparatorInsideAFlagEntryIsRedacted`, so a reorder
      fails `mvn verify`. The exposure is a regression channel, not a
      live attack: a future change that "restores" the order this
      javadoc documents — e.g. moving `flattenToOneLine` into
      `truncate`, or applying it at the renderer after
      `DisplayHeadline.of` returns, which the caller's own javadoc
      describes as correct — re-arms the round-1 attack verbatim
      (`/quarantine<U+2028>approve <uuid>` reaches sanitize as ONE
      token, no match, no `LLM_OUTPUT_SANITIZED` row, then the rewrite
      emits a dispatchable privileged command at a group-broadcast line
      start). The two pinning tests would catch it, so the residual is
      the window in which a maintainer argues from the caller's javadoc
      that the failing test is the thing that is wrong.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-30
    verdict: FINDINGS
    base: 5319f3b14b14552b39d3189eea182ac64e157dcb
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-714-2026-07-30.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Gate audit ahead of review, against the uncommitted working tree.
      Both findings independently verified against the source before
      escalation; both are introduced by this diff rather than
      pre-existing. The load-bearing out-of-model item is that
      PostPersister.normalizeTitle already rewrites a blank title to
      "untitled" and caps titles at 200 chars at the sole post write path
      (M1-693), so both defects this ticket targets are already closed at
      ingest for newly written rows and MAX_LENGTH=200 equals
      TITLE_MAX_LENGTH — the premise holds only for legacy pre-M1-693
      rows. That bears on whether the body fallback should ship at all,
      which is a scope decision for the user, not an in-band fix.
      Not remediated in-branch; any remediation invalidates this audit
      and requires a re-audit.
  - date: 2026-07-30
    verdict: FINDINGS
    base: 7af31ef9a4f2b43fafb6eeab92187c5214bb8558
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-714-2026-07-30-r2.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Round-2 re-audit of the reworked diff, at the same gate. Both
      round-1 findings verified CLOSED against the current code rather
      than assumed closed: the pipeline is
      `truncate(sanitize(flattenToOneLine(source)))`
      (`DisplayHeadline.java:97`) so the sanitizer inspects the bytes
      delivered, and the body is pre-bounded at `BODY_SCAN_LIMIT`
      before sanitize (`DisplayHeadline.java:118-121`). The adversary
      re-derived both evasions rather than trusting the fix, and checked
      that neither the post-sanitize `truncate` nor the pre-sanitize
      body bound can itself be turned into a splice or an escape.
      One NEW low finding, verified independently in the main session:
      the diff adds a javadoc line at `ClusterBlockRenderer.java:46-47`
      documenting the previously-vulnerable order ("sanitize, then
      flatten, then bound"), contradicting `DisplayHeadline.java:25` in
      the same diff. Behaviour is correct and pinned by two
      `DisplayHeadlineTest` cases, so this is a regression channel, not
      a live attack; the fix is a text-only javadoc correction at the
      caller. The adversary's diff excluded `docs/plan/` per the
      fresh-context rule; round-1 findings were supplied via the
      re-audit prompt section. Line anchors in the verdict body are
      diff-file offsets — corrected source anchors are in this entry and
      in `redteam_findings`.
  - date: 2026-07-30
    verdict: CLEAN
    base: 7af31ef9a4f2b43fafb6eeab92187c5214bb8558
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-714-2026-07-30-r3.md
    out_of_model_count: 2
    note: |
      Round-3 re-audit closing the loop the round-2 low/INJECTION
      finding opened. The only code delta since round 2 is one javadoc
      hunk at `ClusterBlockRenderer.java:43-51`: the wrong ordering
      claim is dropped rather than corrected, so the load-bearing WHY
      lives only at `DisplayHeadline.java:25` and cannot drift across
      three call-site copies, with a pointer plus a "never re-apply one
      of them from a caller" clause closing the regression channel.
      The adversary was pushed to re-derive the pipeline order from the
      executable statements rather than any javadoc (trusting a comment
      is the failure this ticket already produced once) and to check
      that the fix did not delete the security rationale along with the
      wrong sentence. CLEAN was explicitly authorized in the prompt so
      an honest CLEAN could terminate the loop. `redteam_findings:` is
      deliberately NOT reset to `[]` — rounds 1 and 2 are historical
      record and this entry is the "audit ran, nothing found" signal.
      Full suite green on the same tree (`target/m1-tick-test-M1-714-r3.log`).
clarity_check:
  date: 2026-07-30
  verdict: WARN
  warnings:
    - >-
      Lint PASS (0 blockers, 0 warnings) both before and after the
      refine.
    - >-
      Census re-run at start: `grep -rn "\.title()" --include=*.java
      infochat-provider/src/main/java` returns exactly the 6 sites the
      §Census table disposes, at the cited lines. No seventh site, so
      the defect class has not grown.
    - >-
      Ticket-vs-code spot-check: every cited path:line verified —
      ClusterBlockRenderer:95, SummaryProseGenerator:179/:224,
      DegradedDigestRenderer:59, CategoryRollupGenerator:199,
      TopicCorpusBuilder:280, and the hand-construct sites
      SummaryCommandHandler:661 / RetryCommandHandler:339.
    - >-
      Self-check found the ticket self-contradictory on the all-empty
      post: §Notes nominated a bundle-keyed placeholder that
      files_scope did not admit. Resolved by refine commit 5319f3b1
      (start-gate scope correction) — no placeholder, scope unchanged
      at 7 files.
    - >-
      Control preservation: the body fallback is a NEW input to the
      sanitize call on a group-broadcast line start. Added as an
      explicit acceptance item in the same refine; the M1-697
      one-field-per-call unit is pinned in test_plan.
  blockers: []
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
  non-blank, else body, ellipsis when cut, empty string when neither
  field has text.
- Whichever single field is selected is sanitized; title and body are
  never concatenated into one sanitize input (M1-697 unit invariant).
- Codepoint-safe cutting — no split surrogate pairs (15.4% of nitter
  titles carry emoji).
- Truncation runs after `sanitize`, so per-hit `LLM_OUTPUT_SANITIZED`
  audit rows survive, and never emits a half-cut replacement marker.
- The three display sites use the helper; the empty-title case no longer
  renders a blank line.
- On an empty helper result each site drops the headline token and its
  separator, leaving the line to lead with its remaining operands.
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

- **The all-empty case — no placeholder (revised 2026-07-30).** One
  Bluesky post has neither title nor body. This ticket originally
  nominated a bundle-keyed placeholder, and that is now rejected on two
  counts. First, cost: the placeholder has to reach
  `degradedProseFor`, a public static with 10 main + 3 test call sites,
  so threading `BundleLoader` + language through it takes the ticket
  from 7 files to 17 and pulls in `SummaryCommandHandler.java` — the
  very file out-of-scope item 3 argues to keep out. Second, and
  decisive on merit: a placeholder carries no information the omission
  does not. `(untitled)` tells the reader nothing except that the
  renderer wanted to print something. The helper returns empty and each
  site drops the token plus its separator; every affected line still
  leads with real content, so this is not the blank line the ticket
  exists to remove. No new bundle key means D43's bilateral-keyset
  obligation is not engaged at all.

- **Why the post is still rendered rather than discarded.** Empty title
  + empty body does not mean an empty post: an image-only or link-only
  Bluesky post has both fields blank while its url is the payload.
  Discarding is also not this ticket's altitude (eligibility lives in
  `EligiblePostQuery`), and the case that would justify keeping such a
  post — it is a reply extending an original — cannot be detected
  today: `post_reference.link_type` admits only
  `entity | semantic | repost` (V29) and no fetcher emits a reply edge.
  A blunt filter would therefore discard precisely the
  reply-extensions worth keeping. See out-of-scope; forward-compatible
  either way.

- **Redteam 2026-07-30 (`docs/plan/m1/redteam/M1-714-2026-07-30.md`).**
  Three of the four items fold into this ticket's acceptance above and
  are confined to `DisplayHeadline` + its test: flatten before sanitize
  (medium/INJECTION), pre-bound the body fallback (low/DOS), and guard
  the Stage 1 placeholder against a partial cut (out-of-model). The
  fourth does not — see below.

- **What this ticket does NOT fix: the `"untitled"` headline (M1-729).**
  M1-693 (landed 2026-07-25) normalizes a blank `post.title` to the
  literal `"untitled"` at `PostPersister`, the sole `post` write path,
  and `BlueskyResponseParser.java:115` still passes `null`. So every
  Bluesky post now stores `title="untitled"` with its real text in
  `body`: the blank-headline defect changed shape rather than closing,
  and this ticket's `title.isBlank()` fallback cannot fire for any row
  written after that date. The fallback still serves rows written
  before it (`post` retention is 30 days, 14 on `pi`), and the shared
  helper still stops the three render surfaces drifting, so this ticket
  remains worth shipping on its own.
  Making the fallback fire for new rows is deliberately NOT done here.
  Both available routes leave this ticket's altitude: changing what
  ingest stores would alter the input to three LLM/embedding consumers
  (`TaggerWorker.java:391`, `ClassifierWorker.java:303`,
  `EmbeddingWorker.java:486`), which is the exact hazard out-of-scope
  item 2 fences; and matching the sentinel at display time would span
  `infochat-core` + `infochat-collector` + `infochat-provider` merely to
  share a constant, while re-deciding M1-693's stated principle that the
  chosen representation is applied once at ingest. That is a design
  decision owed its own acceptance criteria. Filed as M1-729; this
  ticket does not block on it.

- **Follow-up, not committed to here.** Making the limit a config key is
  genuinely useful for a multi-instance deployment where different
  audiences want different headline lengths, but it requires threading
  `@ConfigProperty` through `SummaryCommandHandler` and
  `RetryCommandHandler`, which hand-construct `ClusterBlockRenderer`.
  File it separately if wanted.
