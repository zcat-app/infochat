---
id: M1-936
title: "/topic command and digest topics footer"
status: done
created: 2026-08-26
last_updated: 2026-08-29
flow: tick
reproduction: >-
  DigestTopicsFooterIT#fullModeDigestWithFreeTagsAppendsOneTopicsLineToLastSection
  (child of a 2+ decomposition — marker converted at /tick start
  2026-08-29: the IT was written and run RED against unmodified code,
  4 footer tests failing on the absent topics line, the byte-identity
  golden passing as the pre-fix fence; log in the session's
  .scratch/M1-936-red-it.txt).
  Intended wrong behavior it states: a full-mode group
  digest whose collected posts carry free tags (search_tags seeded on
  the posts, post-V87) renders NO topics line anywhere — verified
  in-tree: DigestRenderer.renderSectionsUnderBudget folds exactly three
  appended affordances into section text (the per-section demotion line
  DigestRenderer.java:495-498, the section-cap overflow line :534-538,
  the closing affordance :539-541) and the window line onto the first
  section (:544-554); grep for 'search_tags' over infochat-provider/
  src/main/java returns NOTHING (no digest-path read of the column
  exists). The test seeds a group digest whose posts carry
  search_tags=['czechia','prague'] etc., drives DigestWorker's render,
  and asserts the LAST delivered section's text ends with ONE appended
  topics line (top 5-7 free tags by weighted count + a "+N more"
  overflow count), positioned after the overflow line and BEFORE the
  closing affordance, with no new message and no change to section
  count, order, or the window line. Companion (converted at
  /tick start 2026-08-29, run RED against the handler shell; log
  .scratch/M1-936-red-unit.txt):
  TopicCommandHandlerTest#bareTopicListsWindowTopicsRankedByWeightedCount.
analysis_ref: docs/plan/m1/tick-analysis/category-tag-split.md
blocked_by:
  - M1-934
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/TopicCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/TopicArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestTopicsFooterIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TopicCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TopicCommandHandlerIT.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    D62 SECTION ARITHMETIC — the footer is never a section: no qualifying
    threshold, no section-cap slot, no category message, never counted in
    the window line's section count; D63 delivery structure and D65 replay
    are untouched BY CONSTRUCTION (the line rides the last section's
    rendered bytes). The degraded digest (D17) carries no footer (binding;
    analysis P1/P2).
  - >-
    /summary — its four render forms, anchors, /retry integration, and
    byte-pinned suites are untouched; /topic mirrors /summary's RENDER
    MACHINERY (renderSummarySections, prose, per-section delivery) without
    editing it beyond the shared projection gain. NO summary_anchor write
    for /topic (no /retry replay of topic runs — a deliberate omission,
    recorded here; a future ticket may add it).
  - >-
    /follow-tag, tag_mode, scope_tag, /get-tags, bootstrap admission —
    category-only surfaces, unchanged (binding user decisions; the rename
    tags->categories is deferred until after the split).
  - >-
    THE TOPICS searchPosts FILTER — M1-935; /topic's matching is its own
    tolerant query, not a tool call.
  - >-
    normal/brief FOOTER MODES — launch full mode first (binding); whether
    normal/brief digests carry the line is decided at implementation with
    this ticket's spec text recording full-mode only; the mechanism note
    (append to the single body, no new message) lands in the design note
    so the later decision is one edit.
  - >-
    THE EVAL LANE and golden-set fixtures (M1-928/929/930) — untouched;
    the footer//topic recall delta is owner-run reference.
  - >-
    EMBEDDING-INPUT ENRICHMENT — out (analysis P20).
acceptance:
  - "REPRODUCTION closed: DigestTopicsFooterIT.fullModeDigestWithFreeTagsAppendsOneTopicsLineToLastSection passes — the seeded digest renders its categories unchanged (same section count, order, window line) plus EXACTLY ONE appended topics line on the LAST section, after the overflow line and before the closing affordance, listing the top free tags by weighted count with a +N more overflow count when more topics exist (spec: docs/spec/commands.md §Periodic group digests as amended by this ticket; decisions D62/D63/D65 untouched — analysis P1, P2)."
  - "Byte-identity for free-tag-less renders (analysis P3): a digest whose posts carry NO search_tags renders byte-identically to today — pinned by re-rendering an existing DigestRendererSectionsTest/DigestRendererTest fixture shape as a golden-bytes test in DigestTopicsFooterIT.noFreeTagsRendersByteIdentically, AND by the existing digest suites passing UNMODIFIED (probe: git diff names no pre-existing digest test; the seed-safe-by-construction pattern, M1-867 acceptance 2)."
  - "PERSISTENCE/REPLAY (analysis P2): the topics line is computed INSIDE renderSectionsUnderBudget from the render-input posts and rides the persisted bytes — DigestTopicsFooterIT.persistsAndReplaysWithTheFooter: the digest_section rows for the slot contain the line, and a /retry --digest replay of an interrupted delivery re-delivers it byte-identically with ZERO recomputation (decision D65; no diff hunk may compute the footer at delivery or per message)."
  - "RANKING (analysis P16, D71 denominator variant, D19): the footer and both listing forms rank by weightedCount = postCount + corroboration, corroboration = the integer round of 100 × (distinct sources carrying the tag ÷ digest/window-wide active sources) — integer arithmetic only, computed from the render/window post set; ties break by postCount DESC then name ASC; NEVER alphabetical-primary — pinned by DigestTopicsFooterIT.rankingFollowsWeightedCountNotAlphabet on a hand-seeded corpus where corroboration flips the order (3 posts from 3 sources outrank 5 posts from 1 source) and an alphabetical-primary mutation fails."
  - "FOOTER CAPS (binding user decisions): the footer line lists at most 7 topics (profile-driven infochat.digest.topics-footer-size, default 7, floor 5 documented in the design note) and one +N more overflow count of the remaining topics with >=1 post — pinned by a >7-topic fixture asserting exactly 7 rendered + N counted; a digest with ZERO free tags appends NO line (acceptance 2)."
  - "/topic COMMAND (binding shape): TopicCommandHandlerTest.bareTopicListsWindowTopicsRankedByWeightedCount passes — /topic with no argument in the window (group default: the period since the previous digest boundary via SummaryCacheRepository.findPreviousBoundary; DM default: 24h, SummaryArgs.DEFAULT_WINDOW; -w overrides; every window keys on ready_at, commands.md §Content What-the-window-measures) renders the listing: bare = top 5-7 by acceptance-4 ranking with +N more; --full = floor >=2 posts, hard cap ~top 50 (profile-driven), plus one '+N more single-post topics' overflow count line — deterministic, bundle-localized, NO LLM call, no rate-cap token, no in-flight slot (the /summary guard-branch posture)."
  - "/topic <tag> DRILL-DOWN mirrors /summary's render form: posts = window ∩ D59 world ∩ prefix-tolerant match (TagNormalizer + escaped LIKE over unnest(search_tags), the M1-935 discipline), clustered by ClusterTraversal, per-cluster prose via SummaryProseGenerator (same InFlightTracker slot + LlmRateCap token + summarizer-post-cap degraded form + per-section delivery + --full uncapped as /summary's forms) — pinned by TopicCommandHandlerIT.topicDrillDownRendersClustersOverPrefixMatchedPosts ('czech' matches czechia-tagged posts; posts outside the world or window never surface; --full skips the item cap)."
  - "TOLERANT ADMISSION, never bounded-vocab (analysis P14): an unknown or zero-match tag gets a fuzzy/zero-match friendly reply (distinct bundle keys reply.topic.no_match / suggestions over the window's actual free-tag set via the EligiblePostQuery.fuzzySuggest shape) — NEVER error.summary.unknown_tag, NEVER tag-tree expansion — pinned by TopicCommandHandlerTest.unknownTopicGetsFuzzyReplyNotVocabularyError and probe: grep -n 'unknown_tag' over TopicCommandHandler.java returns nothing."
  - "CANONICAL RENDER SAFETY (analysis P5): every topic token rendered in footer or listing is a stored canonical value (or a normalized user echo in the no-match reply) — the [a-z0-9-] class means no token can contain '/', whitespace, or case, so no line can forge a command token; the reply composes from bundle templates with the tokens as MessageFormat arguments — pinned by a render test seeding a hostile-topic corpus and asserting the delivered lines match the bundle template exactly (defense-in-depth under the D67/D69 post-sanitize posture; no new sanitizer call — the class is the control)."
  - "COMMAND PARITY (engineering-rules §8 guard; analysis P17): the canonical index gains the /topic line (commands.md marked block), the TopicCommandHandler bean's name() equals 'topic', HelpCommandHandler.CATALOGUE gains the entry, and help.cmd.topic.* + every new reply.topic.*/reply.digest.topics* key ships in ALL FIVE bundles — probes: CommandCatalogueParityTest green; the bundle-completeness CI green; grep -c 'topic' docs/spec/commands.md returns the index line + the §Content entry."
  - "WINDOW COHERENCE (analysis P15): the footer's topics are computed over the SAME posts the digest renders (the render-input list — the projection gains searchTags, EligiblePostQuery.Post + DigestPostCollector SELECTs extended with a compat-constructor, the established chain pattern); /topic's group default window equals the boundary the footer's digest covered — pinned by a clock-pinned test pair (footer period boundary == /topic default cutoff; DM leg 24h)."
  - "Spec amendments ride the diff (engineering-rules §12; wording user-approved at implementation; rides-the-diff shape): docs/spec/commands.md — §Content gains the /topic entry (shapes, windows, tolerant matching, the never-bounded-vocab rule); the canonical index gains /topic; §Periodic group digests gains the topics-footer paragraph (one capped topics footer line of top free tags by weighted count appended to the last section's text ahead of the closing affordance; full mode; never a section, never counted, never a message; degraded digests carry none) — probes: grep -n '/topic' docs/spec/commands.md returns the canonical-index line and the §Content entry; grep -n 'topics footer' docs/spec/commands.md returns the §Periodic group digests paragraph; the diff's added lines in docs/spec/commands.md match neither 'M1-' nor a YYYY-MM-DD token (no date/ticket-id tokens in the added prose — a whole-file grep is wrong: commands.md carries pre-existing M1- mentions at :840/:2218). docs/design/03-commands.md records the arg grammar, caps, and the normal/brief mechanism note — probe: grep -n '/topic' docs/design/03-commands.md returns the note."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestTopicsFooterIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TopicCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/TopicCommandHandlerIT.java
  modifies:
    - >-
      DigestPostCollectorTest.java:405-410 — §8 authorization: acceptance
      11's projection gain (DigestPostCollector SELECTs gain p.search_tags)
      forces the stub ResultSet to know the new column; stub returns the
      DB's NOT NULL DEFAULT '{}' shape (empty array); no assertion touched.
    - >-
      LangCommandIT.java:118-121 — §8 authorization: acceptance 10's
      parity-mandated HelpCommandHandler.CATALOGUE entry forces the cs
      /help golden listing to gain the /topic line in CATALOGUE order; no
      assertion weakened.
  preserves:
    - >-
      all tests currently green on main — explicitly DigestRendererTest,
      DigestRendererSectionsTest, DigestWorkerTest, DigestDeliveryTest,
      DigestRoundtripIT, FollowTopDigestIT, SummaryCommandHandlerTest,
      RetryCommandHandler suites, TagModeRoundtripIT — UNMODIFIED (their
      fixtures carry no search_tags, so the footer never fires: the
      acceptance-2 fence).
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Surface conventions
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D43
  - D59
  - D62
  - D63
  - D65
  - D71
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-29
    verdict: REWORK
    checks: SPEC-TRUTHNESS PASS; SECURITY PASS; TEST-ADEQUACY FAIL;
      MAINTAINABILITY WARN; SCOPE PASS
    diff_stats: 22 files, +1926/-20
    findings: 3 low (over-cap drill-down branch untested; missing §8
      authorization record for the two modified pre-existing tests; unused
      TranslationPipeline field in the handler)
    verdict_file: .scratch/tick-review-M1-936-r1.txt
  - round: 2
    date: 2026-08-29
    verdict: APPROVE
    checks: SPEC-TRUTHNESS PASS; SECURITY PASS; TEST-ADEQUACY PASS;
      MAINTAINABILITY WARN; SCOPE PASS
    diff_stats: fix hunks 206 lines; full diff 22 files, +1970/-25
    findings: none; round-1 items dispositioned SATISFIED (mutation
      executed and reverted in-session, 9/9 then full verify green)
    verdict_file: .scratch/tick-review-M1-936-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-28: all file:line citations re-verified on main 52e3014a
  (renderSectionsUnderBudget affordance folds :489-554; summary vocab gate
  :254-263; identity keying :392-397; commands.md index :206-251 has no
  /topic; V87 + TaggerWriter exist per M1-934). M1-935 pending — not a
  blocker (blocked_by is M1-934 only); /topic owns its tolerant query per
  out_of_scope. M1-938 files_scope overlaps this module but its board
  status is pending with an idle worktree — no in-flight collision.
  files_scope deviation noted: HelpCommandHandler lives at
  messaging/HelpCommandHandler.java, not command/. Census grep re-run:
  23 paths returned, grouped rows cover the digest families; completed
  table with live output lands at review time (the M1-867 pattern);
  seed-safety verified (grep search_tags over provider src/test is
  empty). No blocking ambiguity.
escalation_reason:
---

# M1-936: /topic command and digest topics footer

## Context

M1-934 stores free tags and backfills them; M1-935 lets the chat agent
filter on them. What remains is DISCOVERY: a group receiving its digest
has no idea which specific topics the period actually carried, and a
reader who wants "everything on czechia" in a readable summary form has
no command — /summary's positional tag is bounded-vocabulary-only
(unknown-tag error against the tree, SummaryCommandHandler.java:254-263).
Binding user decisions: footer 5-7 topics, one line; /topic mirrors
/summary's render form; /topic --full = ranked/floored/capped listing;
digest footer advertises digest-window topics; /topic defaults to that
window. Shared context: `analysis_ref:` (analysis doc, Pitfalls P1-P4,
P13-P19).

## Root cause

Verified: `DigestRenderer.renderSectionsUnderBudget` appends exactly
three affordances and the window line (DigestRenderer.java:489-554) —
no code path reads search_tags anywhere under infochat-provider
(`grep -rn search_tags infochat-provider/src/main/java` returns
nothing); the command catalogue has no topic entry (commands.md index
:206-251); /summary's admission control is the controlled vocabulary
(SummaryCommandHandler.java:254-263, fuzzy suggestions over the TREE).
Not a defect — the missing user-facing half of the split.

## Pitfalls

- P1: the footer must not become a D62 section (no threshold, no cap
  slot, no message, not counted; degraded digests carry none; folded
  ahead of the closing affordance — the digest still ENDS with the
  affordance).
- P2: compute inside the render pass; the line persists in
  digest_section/summary_cache bytes and replays verbatim (D65);
  delivery-time recomputation would diverge from replay.
- P3: byte-identity for free-tag-less renders — the footer fires only
  when >=1 render-input post carries a free tag; every existing digest
  fixture renders byte-identically and every digest suite passes
  UNMODIFIED (the seed-safe pattern; without this the ticket is a mass
  §8 edit).
- P4: free tags never touch digest arithmetic or category surfaces
  (D62's input stays post.tags; /follow-tag stays category-only).
- P14: /topic never speaks bounded-vocab errors; no tree expansion;
  fuzzy/zero-match reply over the window's actual free tags.
- P15: window coherence — footer over the digest's period; /topic group
  default = that same boundary; DM 24h; ready_at rule.
- P16: deterministic integer ranking (weightedCount = postCount +
  corroboration with the D71 Other-bucket denominator variant); never
  alphabetical-primary.
- P17: command parity (index + bean + CATALOGUE + help keys ×5
  bundles); engineering-rules §8's guard reds on any side drifting.
- P18: commands.md amendments are rule text, user-approved wording.
- P19: fixtures seed search_tags from the start (END state).

## Approach

- **Files to touch:** `DigestRenderer` (footer fold + ranking helper),
  `EligiblePostQuery`/`DigestPostCollector` (projection gains
  searchTags via a compat constructor — the established chain), new
  `TopicCommandHandler` + `TopicArgs`, `HelpCommandHandler.CATALOGUE`,
  `BundleKeys` + five bundles, commands.md + design 03, tests.
- **Pre-decided shapes:**
  1. **Ranking helper** (one mechanism, three consumers): per free tag
     over a post set — postCount, distinctSources, corroboration =
     round(100 × distinctSources ÷ activeSources-in-set), weightedCount
     = postCount + corroboration; ties postCount DESC, name ASC. Pure
     integer arithmetic over in-memory posts (D19; the SQL stays the
     post fetch).
  2. **Footer** (full mode): inside `renderSectionsUnderBudget`, after
     the section loop decides the last section, compute the ranking
     over the render-input posts; if >=1 free tag exists, append
     "\n\n" + bundle line (header + up to 7 "name (count)"-style
     tokens + "+N more") to the LAST section's text BEFORE the closing
     affordance is appended (order: overflow line → topics line →
     affordance). Zero free tags → no line. Degraded render → no line
     (DegradedDigestRenderer untouched).
  3. **/topic** (singular — the verb drills into ONE topic; the
     listing is its no-argument form; the analyst recommendation over
     /topics): `TopicArgs` parses `[tag] [-w <duration>] [--full]`
     (SummaryArgs grammar reuse). Bare: the ranking over the window's
     world-visible READY posts, top 5-7 + "+N more" (bundle-localized).
     --full: floor >=2, cap 50, "+N more single-post topics". `<tag>`:
     tolerant prefix query (TagNormalizer + escaped LIKE over
     unnest(search_tags)) on the caller's scope; cluster → prose →
     `renderSummarySections` identity-keyed (Map.of(), the positional
     /summary idiom, SummaryCommandHandler.java:392-397) → per-section
     delivery; --full uncapped; the same in-flight/rate-cap/over-cap
     degraded guards as /summary; no anchor write.
  4. **Bundles**: reply.digest.topics_footer (+overflow variant),
     reply.topic.listing_header, reply.topic.line, reply.topic.more,
     reply.topic.more_single_post, reply.topic.no_match,
     reply.topic.suggestions_footer, help line + help.cmd.topic.usage/
     .examples — MessageFormat templates, all five languages.
- **Steps:** RED ITs first (footer reproduction + byte-identity golden)
  → projection gain → ranking helper + footer → /topic handler + args +
  catalogue/index/help keys → spec + design amendments (user wording)
  → full verify.
- **Controls to preserve (engineering-rules §10):** D62/D63/D65
  controls byte-for-byte for the no-free-tag path (acceptance 2's
  golden + unmodified suites); the `other` slug sentinel and M1-727's
  personal gate untouched; /summary's own path untouched (shared
  machinery reused, not edited — only the projection gains a field);
  InFlight/LlmRateCap/statement_timeout/progress-notifier lifecycles
  mirrored exactly for the drill-down; D43 bundle-only strings; §9
  injected Clock for every window cutoff.
- **Pitfall→mitigation:** P1→footer shape in step 2 + acceptance 1's
  position/count assertions; P2→acceptance 3's persist/replay IT; P3→
  acceptance 2's golden + fence probe; P4→no digest-path write of any
  kind (read-only aggregation); P14→acceptance 8 + the grep probe;
  P15→acceptance 11's clock-pinned pair; P16→acceptance 4's flip
  fixture; P17→acceptance 10's parity probes; P18→acceptance 12;
  P19→test_plan fixture discipline.

## Definition of done

Every acceptance item holds: the footer reproduction passes (one line,
last section, right position, right caps); byte-identity for
free-tag-less digests with all existing digest suites unmodified;
persist+replay carries the line verbatim; the ranking is pinned
(corroboration flips order; alphabetical mutation fails); the footer
caps pin (7 + overflow; zero topics = no line); bare /topic lists
ranked with the coherent window default; /topic <tag> drill-down
mirrors /summary over prefix-matched, world-and-window-bounded posts
with --full uncapped; unknown tags get the fuzzy reply (grep probe
clean); canonical render safety pinned; command parity + five-bundle
completeness green; the commands.md amendments carry user-approved
rule text; mvn verify green.

## Verification

- Reproduction → acceptance 1.
- P1 → acceptance 1 (position: after overflow, before affordance; no
  new message; section count/order/window line unchanged).
- P2 → acceptance 3 (digest_section bytes + /retry --digest replay).
- P3 → acceptance 2 (golden re-render + the unmodified-suite fence).
- P4 → reviewer diff check: no digest-path write; footer reads only.
- P16 → acceptance 4 (flip fixture; alphabetical mutation fails).
- Footer caps → acceptance 5 (>7 fixture; zero-topic fixture).
- P15/P17/P14 → acceptances 6, 10, 8 respectively (clock-pinned window
  pair; parity greps; fuzzy reply + no-unknown_tag grep).
- Drill-down → acceptance 7 (prefix match, isolation, --full uncapped).
- Render safety → acceptance 9 (hostile-topic corpus; template match).
- P18 → acceptance 12's probes + the user-approval record (rule text
  only — the added commands.md/design-03 lines carry no date or
  ticket-id tokens, per the added-lines probe there).
- P19 → every new fixture seeds search_tags from the start (the footer
  reproduction and ranking fixtures seed them directly on the posts —
  the post-V87 END state, never a shape a sibling removes); reviewer
  diff check over the three new test files' seeded rows.
- Spec/design → acceptance 12's greps + user-approval record.
- acceptance 13 → mvn verify exit 0.

## Out-of-scope

See `out_of_scope:` — D62 arithmetic and D63/D65 delivery untouched by
construction; /summary and its anchors untouched (no /retry for /topic
— deliberate, recorded); category-only surfaces unchanged; M1-935's
tool untouched; normal/brief footer modes deferred with the mechanism
note landed (binding launch-full-first); eval lane untouched;
embedding enrichment out. No pre-existing test is modified EXCEPT the two
§8-authorized entries under `test_plan.modifies` (the collector stub's
search_tags column, forced by the projection gain; the cs /help golden's
/topic line, forced by the CATALOGUE entry) — every preserves-listed suite
is untouched.

## Census

Class-scoped (the digest byte-pin family — every suite that asserts
digest render bytes must be disposed as unmodified-because-seed-safe,
per P3). Mechanical enumeration at start:
`grep -rln 'renderSections\|renderSummarySections\|DigestRenderer'
infochat-provider/src/test/java` — every returned file gets a row:

| Site | Disposition |
|---|---|
| DigestRendererTest / DigestRendererSectionsTest | **Unchanged** — fixtures carry no search_tags; footer never fires (P3) |
| DigestWorkerTest / DigestDeliveryTest / DigestRoundtripIT | **Unchanged** — same seed-safe argument |
| FollowTopDigestIT / EligiblePostQueryTopExpansionIT | **Unchanged** — tree fixtures, no free tags |
| SummaryCommandHandlerTest / RetryCommandHandler suites | **Unchanged** — /summary path byte-identical (projection gain is additive; compat constructor keeps construction sites compiling) |
| DigestCategorizerTest / DigestCategorizerFollowedLevelTest | **Unchanged** — categorizer untouched |

The completed table (with the live grep output) is recorded in this
ticket at review time, the M1-867 pattern.

## Round 1 rework

1. FINDING 1: add TopicCommandHandlerTest.overCapDrillDownRendersDegradedForm
   (summarizerPostCap=2, 3 seeded drill posts; assert the too-large notice,
   zero prose calls, zero rate-cap draws, per-section degraded delivery, no
   anchor), evaluated by deleting the branch at TopicCommandHandler.java:640
   failing that test, plus a green full `mvn verify`.
2. FINDING 2: record the §8 test-modification authorization for
   DigestPostCollectorTest.java:405-410 and LangCommandIT.java:118-121 in
   the round-2 commit body (and optionally test_plan.modifies), evaluated
   by `git log -1 --format=%B | grep -e DigestPostCollectorTest -e
   LangCommandIT` matching both names.
3. FINDING 3: delete the unused TranslationPipeline field, import, and test
   assignment from TopicCommandHandler, evaluated by `grep -c
   translationPipeline` over the handler returning 0 and
   `mvn -pl infochat-provider test-compile` green.
