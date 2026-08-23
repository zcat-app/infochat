---
id: M1-912
title: "Bound digest render volume in FULL and degraded modes"
status: in-progress
created: 2026-08-23
last_updated: 2026-08-23
clarity_check: "start 2026-08-23: all file:line citations re-verified on this checkout (DigestRenderer:124/:256-263/:407-415/:459-463; SummaryProseGenerator:300-346; DigestWorker:226,244,289 + admission gates at :241-244; DegradedDigestRenderer:78-119; DigestRoundtripIT:110,192,225; DigestRetryConcurrencyIT:109,131; GoldenPathJourneyIT:348; spec commands.md:2126-2132 item-cap/overflow text standing); §Census re-run clean (eight digest render paths, eight rows); analysis pitfalls P1-P7 all landed in this ticket (P8-P14 are siblings); DigestRendererTest pin exists as renderSections_stripsAdminCommandTokens_beforePersistenceAndReplay (:228); plural-shape precedent reply.digest.categories.more verified in en.properties:889; no blocked_by, no replaces:"
flow: tick
reproduction: >-
  DigestRendererVolumeBoundTest.fullModeRendersAtMostItemCapClustersPerSectionAndAccountsForTheRest
  (to-be-written) and
  DigestRendererVolumeBoundTest.fullyDegradedFullDigestIsBoundedAndFlaggedDegraded
  (to-be-written) — converted at start: written first, run RED. Verified on
  this checkout (2026-08-23): DigestRenderer.java:124 states "Max clusters
  rendered per category section in FULL mode is unbounded"; the FULL arm
  (:407-415) generates prose for EVERY cluster of every surviving section
  and renders them all (:459-463) with no overflow accounting (the +N-more
  keys were deleted by M1-732); a failed/refused per-cluster call degrades
  to SummaryProseGenerator.degradedProseFor (:300-346), which lists EVERY
  member post as title — url (uid …); and DigestWorker.java:226,244,289
  sets is_degraded=true only on the admission/timeout branches, so a
  completed render whose every cluster degraded writes is_degraded=FALSE —
  the prod 2026-08-23 morning digest was 75,523 chars of reference lines
  over ~804 window posts with is_degraded=FALSE (brief-supplied prod
  measurement; analysis §Root cause 1).
analysis_ref: docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererVolumeBoundTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  - docs/spec/commands.md
  - docs/spec/security.md
  - docs/design/03-commands.md
  - docs/design/07-deployment.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The brief/normal hybrid body shape, the lead section's existence and
    size, section order, section membership, and the D62/D71 arithmetic —
    all unchanged. This ticket bounds how MUCH renders and makes the
    degradation flag honest; the v1-shape copy/header work is sibling
    M1-913 (blocked on this one; same renderer file).
  - >-
    `/summary`'s render forms. The item cap and the `/summary`-scoped
    `reply.summary.category.more` overflow line stay exactly as they are;
    the member-capped degraded variant is used ONLY on the digest render
    path (`renderSections` and the lead), never in `renderSummarySections`
    / `renderShortBody` / `ClusterBlockRenderer` — `/summary --full` is
    the interactive uncapped escape the reader asked for.
  - >-
    `infochat.summary.cluster-cap` and the collection window (M1-689
    ready_at semantics). The collector's LIMIT is a pre-clustering
    capacity guard; this ticket bounds the RENDER of what was collected.
  - >-
    The D17 admission gates themselves (`DigestWorker.java:241-244`
    window/budget checks) and M1-763's cancellation path. They are
    start-time overload gates, correctly so; this ticket bounds what a
    STARTED render can emit.
  - >-
    Removing or re-purposing the FULL mode. `/digest brief|normal|full`
    stays (docs/spec/commands.md §Conversation control); FULL becomes
    bounded, not deleted.
  - >-
    Any ranking/engagement change (M1-914) and any source-kind change
    (M1-915).
  - any other module
acceptance:
  - "REPRODUCTION closed (cap half): DigestRendererVolumeBoundTest.fullModeRendersAtMostItemCapClustersPerSectionAndAccountsForTheRest passes — a FULL-mode fixture with one section of 20 clusters at item-cap 12 renders exactly 12 per-cluster prose blocks (the prominence head — the fixture's prominence order is controlled so the test asserts WHICH 12), generates exactly 12 SummaryProseGenerator cluster calls, and appends ONE localized demotion line naming the 8 not shown and steering to /summary <tag> --full. The pre-change code renders all 20 with no demotion line (spec support: docs/spec/commands.md §Periodic group digests' still-standing item-cap/overflow text at :2126-2132; analysis P1)."
  - "DigestRendererVolumeBoundTest asserts prose is generated ONLY for shown clusters: the FULL arm's single SummaryProseGenerator.generate call receives the capped cluster list in exact render order, and the test asserts CONTENT PAIRING — each rendered prose block sits under its own cluster's position — not counts alone (analysis P1: a count-only assertion cannot catch a cross-cluster prose swap)."
  - "REPRODUCTION closed (degraded half): DigestRendererVolumeBoundTest.fullyDegradedFullDigestIsBoundedAndFlaggedDegraded passes — a FULL-mode fixture whose generator degrades every cluster renders each cluster's degraded reference list with at most infochat.digest.degraded-member-cap (default 3) member lines plus a '+M more' suffix when the cluster is larger, and DigestWorker writes the summary_cache row with is_degraded=TRUE because zero prose was generated (analysis P5). Pre-change: every member is listed and the flag is false."
  - "DegradedDigestRendererTest asserts the D17 whole-digest fallback renders at most infochat.digest.degraded-max-entries (default 50) post entries in collection (recency) order plus ONE localized '+M more — /summary' accounting line naming the remainder, and that output at or under the cap is byte-identical to today (analysis §Root cause 1: this path is the same unbounded-reference-list defect class — 804 posts would produce ~72k chars through the spec'd fallback)."
  - "DigestRendererVolumeBoundTest asserts the member-capped degraded arm keeps the per-member sanitize unit: a command-shaped title in a RENDERED member is redacted via DisplayHeadline.anchorFirst (one author's field per sanitize call, M1-697), a capped-out member never reaches the sanitizer, and no multi-member concatenation is sanitized as one input (analysis P3; the M1-694-r3 cross-post span shape)."
  - "DigestWorkerTest asserts is_degraded=true when a completed render generated ZERO synthesis (FULL: every rendered cluster degraded; brief/normal: every roll-up failed): renderSections returns a result record carrying the section list plus the degraded counts, via a REPLACED signature — RecordingDigestRenderer is updated so the stale stub is a compile error, not a silent mis-binding (analysis P4, the M1-722 pass-2 lesson). The admission/timeout branches and the missed-slot sentinel (empty content, flag true) are unchanged (analysis P5)."
  - "DigestRoundtripIT gains a zero-generated-prose arm asserting is_degraded=true and pinning /retry --digest for such a row under the corrected retry rule (user steer 2026-08-23): replay finishes interrupted deliveries of HEALTHY renders, so a degraded row — admission-gated, timed-out, or zero-prose — takes the RE-RUN leg over the frozen cluster set even though it DID persist sections; with the pool recovered the retry regenerates prose and replaces the cache row (is_degraded=false), and the arm asserts exactly that. The pre-existing is_degraded pins (DigestRoundtripIT:110,192,225; DigestRetryConcurrencyIT:109,131; GoldenPathJourneyIT:348) pass unmodified (analysis P5, as amended)."
  - "DigestRendererTest.renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay stays NON-VACUOUS under the cap — §8-authorized modification if the cap change requires touching it: the injected command-token prose must sit inside a RENDERED cluster, and the test keeps asserting sanitized-then-persisted bytes replayed verbatim at mode FULL (analysis P2)."
  - "DigestRendererTest's per-mode LLM call-count pins are §8-authorized updates that assert FULL generates one SummaryProseGenerator cluster call per SHOWN cluster (was one per cluster), brief/normal counts unchanged, and the M1-769 render-scoped budget draws shrink with the call count and are asserted — never un-metered (analysis §Controls)."
  - "BundleLoaderTest keyset parity passes with the new keys (FULL per-section demotion line, degraded-member '+M more' suffix, D17 '+M more entries' line) present in ALL FIVE bundles (en, cs, es, ru, tr) in this diff, using the {0,choice,...} plural shape of the existing reply.digest.categories.more (analysis P6)."
  - "DocumentedConfigKeyParityTest passes: infochat.digest.degraded-member-cap (default 3) and infochat.digest.degraded-max-entries (default 50) carry base declarations in the provider application.properties and rows in docs/design/07-deployment.md §7.4 in the same diff — probe: grep -n -E 'degraded-member-cap|degraded-max-entries' docs/design/07-deployment.md infochat-provider/src/main/resources/application.properties"
  - "The spec amendments land with wording to user approval (§12, rule-text only) — probe: grep -n 'item cap' docs/spec/commands.md: docs/spec/commands.md §Periodic group digests states the bounded FULL (item cap with the prominence head kept, per-section demotion line), the bounded degraded forms, and the zero-synthesis is_degraded rule, reconciling the stale :2124-2132 body description; docs/spec/security.md §Failure handling's degraded-form pin gains the entry bound (the FORM — headlines + URLs + UIDs, no LLM calls — is unchanged); docs/design/03-commands.md §3.12 is synced."
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererVolumeBoundTest.java
      — the two reproduction tests; content-pairing under the cap;
      sanitize-unit pins on the bounded degraded arm; brief/normal
      unaffected by the FULL cap; demotion line absent under the cap.
    - >-
      DegradedDigestRendererTest — over-cap renders exactly 50 entries +
      one accounting line; at-cap renders byte-identically to today.
    - >-
      DigestRoundtripIT — zero-generated-prose slot writes
      is_degraded=true; retry behavior pinned.
  modifies:
    - >-
      DigestRendererTest — per-mode call-count pins (FULL counts shown
      clusters only); the sanitize-before-persist pin kept non-vacuous
      (both §8-authorized in the acceptance items); the two renderSections
      call sites retargeted to the result record.
    - >-
      DigestRendererSectionsTest — renderSections call sites retargeted;
      FULL-mode shape assertions gain the cap/demotion expectation.
    - >-
      RecordingDigestRenderer — updated to the replaced signature (P4).
    - >-
      DigestWorkerTest — is_degraded assertions for the zero-prose rule;
      stub updates.
    - >-
      DigestRetryServiceTest — degradedRowWithPersistedSectionsReRuns
      InsteadOfReplaying (§8-authorized: the corrected retry rule makes
      leg selection read is_degraded; the pre-existing cases keep their
      expectations — degraded row without sections already re-ran).
  preserves:
    - >-
      DigestWorkerClockTest UNMODIFIED — the pin that the signature change
      was mechanical (the M1-722/M1-732 zero-diff evidence shape).
    - >-
      Every /summary render assertion (SummaryCommandHandlerTest,
      renderSummarySections/renderShortBody paths): the member cap and
      demotion lines never reach those paths.
    - >-
      DigestDeliveryTest, DigestRetryConcurrencyIT — delivery batching
      and slug accounting are untouched; both concurrency rows (healthy
      no-sections, degraded no-sections) keep their legs.
    - >-
      DegradedDigestRendererTest's pre-existing cases — under-cap output
      is byte-identical.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/security.md §Failure handling
  - docs/design/03-commands.md §3.12
decision_refs:
  - D17
  - D19
  - D62
  - D65
  - D71
---

# M1-912: Bound digest render volume in FULL and degraded modes

## Context

The 2026-08-23 morning digest on prod was 75,523 characters over ~804
window posts (previous normal 5.8–8.8k): dozens of clusters each rendered
as a per-member reference list (title + URL + uid) across 8+ chunked
messages — spam the user mutes, the opposite of the digest's "high
information value" purpose. The summary_cache row carries
is_degraded=FALSE. FULL mode renders every cluster of every surviving
section with the item cap lifted (DigestRenderer.java:124 comment,
:256-263, :407-415), and when per-cluster prose calls fail or are refused
mid-render (M1-769's per-call budget), each cluster degrades to
`degradedProseFor`, which lists EVERY member post with its uid
(SummaryProseGenerator.java:300-346). Nothing anywhere on the render path
bounds total volume, and the D17 admission gates
(DigestWorker.java:241-244) are start-time overload checks that a
started-then-degraded render never trips. See
docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md.

## Root cause

M1-732 lifted FULL's per-section cap to a local Integer.MAX_VALUE and
deleted the +N-more overflow accounting; M1-769 deliberately degrades
per-cluster in place once the render-scoped budget is exhausted ("it is
not a cheaper render" — its out_of_scope). The two compose: an admitted
FULL render over a large window emits an unbounded reference dump, and
is_degraded — set true only on the admission/timeout branches
(DigestWorker.java:226,244,289) — records nothing, because the render
"completed". The same unbounded shape exists in the D17 whole-digest
fallback (DegradedDigestRenderer lists every collected post) — same
defect class, disposed of here (Census below).

## Pitfalls

- P1: FULL's single generate() covers every cluster and the prose loop
  aligns positionally (DigestRenderer.java:407-412, :460-462). Cap the
  cluster list BEFORE generate() (D62: prose only for shown) and preserve
  exact render order, or one cluster's prose lands under another cluster.
- P2: The sanitize-before-persist pin
  (DigestRendererTest.renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay,
  mode FULL) must stay non-vacuous under the cap — the M1-722
  outline-fail lesson (engineering-rules §10 "Tests are controls too").
- P3: The member cap must not change the sanitize unit — one author's
  field per DisplayHeadline.anchorFirst call (M1-697; the 2026-08-05
  pair-unit redteam rule). Never concatenate members into one sanitize
  input (M1-694-r3).
- P4: Reporting degraded counts changes renderSections' return shape —
  REPLACE the signature, never overload; RecordingDigestRenderer's
  @Override is the seam that must become a compile error, not a silent
  mis-binding (M1-722 pass-2; DigestWorkerClockTest:86 is the failure
  shape to avoid).
- P5: is_degraded was write-only in production (no main-code reader —
  DigestRetryService.java:37-38 keyed replay on persisted sections only).
  AMENDED (user steer 2026-08-23, mid-implementation): the corrected retry
  rule gives the flag its first production reader — DigestRetryService's
  leg selection. Replay requires persisted sections AND is_degraded=FALSE;
  every degraded row re-runs over the frozen cluster set (uniform with the
  existing "degraded slot → retry regenerates" promise). The missed-slot
  sentinel (empty content, flag true) and the five IT pins must survive;
  a zero-prose slot that also died mid-broadcast re-runs instead of
  gap-filling — accepted deliberately: the re-run regenerates over the
  same frozen cluster set, so the reader gets prose, not a shifted digest
  (a documented trade of D65 gap-filling for degraded rows).
- P6: Bundle parity — five languages (en/cs/es/ru/tr), same diff,
  {0,choice,...} plurals with per-language categories (D43 keyset gate,
  BundleLoaderTest).
- P7: Spec wording (docs/spec/commands.md §Periodic group digests;
  docs/spec/security.md §Failure handling) goes through user approval —
  rule-text only, no dates/ticket IDs (engineering-rules §12).

## Approach

Derived from spec_refs: docs/spec/commands.md §Periodic group digests
still promises a per-section item cap with a "+N more" line for the
digest (:2126-2132) — this ticket makes the code keep that promise in
FULL; docs/spec/security.md §Failure handling pins the degraded FORM
(headlines + URLs + UIDs, no LLM), which an entry bound does not change.

- **Files to touch:** see files_scope.
- **Steps, in order:**
  1. Write the two reproduction tests RED.
  2. Replace renderSections' return shape with a result record (sections +
     degraded counts); fix the compiler-enumerated seams (P4). Zero
     behavior change in this step — DigestWorkerClockTest stays green
     unmodified as the evidence.
  3. FULL arm: cap each section's cluster list at categoryItemCap (the
     prominence head — sections are already prominence-ordered), build the
     single generate() input from the capped lists in render order, append
     the per-section demotion line for capped sections (P1).
  4. Bounded degraded forms: a member-capped degraded variant for the
     digest render path (P3), and the DegradedDigestRenderer entry cap +
     accounting line.
  5. DigestWorker: zero-generated-synthesis → is_degraded=true (P5 as
     amended); document the flag's semantics ("degraded render OR missed
     slot OR zero-prose render") where the schema/design describes
     summary_cache. DigestRetryService: the corrected retry rule — replay
     requires persisted sections AND is_degraded=FALSE; every degraded row
     re-runs over the frozen cluster set (user steer 2026-08-23).
  6. Bundles ×5, config keys + docs, spec amendments (P6/P7).
- **Controls to preserve (§10):** per-author-field sanitize units on every
  degraded arm; the post-sanitize persist boundary and its pin (P2); the
  M1-691 `](` guarantee at OutboundDelivery (no URL sanitizing added);
  M1-769 render-scoped metering (fewer calls = fewer draws, asserted);
  D65 section persistence/byte-faithful replay (the result record carries
  counts alongside the identical section list); D63 delivery attribution
  and the LEAD_TAG split.
- **Pitfall→mitigation:** P1→step 3 + content-pairing test; P2→acceptance
  item 8; P3→acceptance item 5; P4→step 2; P5→acceptance items 3/6/7;
  P6→item 10; P7→item 12.

## Definition of done

Every acceptance item above, each verified by its named test/probe,
including the failure-mode items (hostile-title sanitize-unit test;
over-cap DegradedDigestRenderer boundary). mvn verify green from the repo
root.

## Verification

- P1 → DigestRendererVolumeBoundTest.fullModeRendersAtMostItemCapClustersPerSectionAndAccountsForTheRest
  — 20-cluster section at cap 12: 12 prose calls, the prominence head
  rendered, demotion line names 8; content-pairing asserted, so a
  cross-cluster prose swap fails the test.
- P2 → DigestRendererTest.renderSections_stripsAdminCommandTokensBeforePersistenceAndReplay
  — the injected token is provably inside the rendered set; a
  vacuously-passing variant (token capped out) is exactly what the
  §8-authorized modification forbids.
- P3 → DigestRendererVolumeBoundTest hostile-title case — feeds a
  command-shaped feed title inside a rendered member and asserts
  redaction in the bounded degraded output; a capped-out member never
  reaches the sanitizer; no multi-member sanitize input exists.
- P4 → the compiler enumerates the seams (RecordingDigestRenderer and
  every renderSections caller fail to compile until updated);
  DigestWorkerClockTest green UNMODIFIED is the evidence the change was
  mechanical.
- P5 → DigestRoundtripIT's new zero-prose arm asserts is_degraded=true;
  DigestRetryConcurrencyIT/GoldenPathJourneyIT is_degraded pins and the
  missed-slot sentinel shape pass unmodified — a regression in either
  direction fails one of these.
- P6 → BundleLoaderTest keyset parity across the five bundles; one non-en
  plural rendering of a count-bearing key asserted.
- P7 → the spec-amendment acceptance probe (grep -n 'item cap'
  docs/spec/commands.md) confirms the amended section states the bound;
  the wording itself is user-approved at implementation, never pinned by
  a test.
- Failure-mode beyond the reproductions: the hostile-title sanitize test
  (P3) and the DegradedDigestRenderer over-cap/at-cap boundary pair —
  an implementation that renders one entry past the cap, or drops the
  accounting line, fails them.

## Out-of-scope

The v1-shape copy work (opening window-count line, headline-count default,
closing-affordance wording) is sibling M1-913 — it lands after this ticket
because both touch DigestRenderer and the bundles. This ticket's new tests
must NOT pin the closing-affordance copy or the headline count (M1-913
changes both; the M1-785 lesson — an earlier sibling must not pin what a
later sibling is mandated to change). /summary keeps its forms and its
DM-worded overflow line; the member-capped degraded variant is digest-only.
No ranking, no source-kind, no collector change. No pre-existing test is
modified except the four files named in test_plan.modifies, each with its
§8 authorization stated in the acceptance items.

## Census

The defect class: "a digest-content render path whose output grows without
bound in the window size". Enumerated by reading renderSections end to end
plus its two sibling renderers:

| Site | Disposition |
|---|---|
| FULL per-cluster prose loop (DigestRenderer.java:459-463) | **fix** — item cap + demotion line (acceptance item 1) |
| FULL/lead degraded member listing (via degradedProseFor) | **fix** — member cap + suffix, digest path only (items 3, 5) |
| DegradedDigestRenderer per-post listing (:78-119) | **fix** — entry cap + accounting line (item 4) |
| NORMAL headlines (appendHeadlines) | already bounded (category-headline-count) — unchanged |
| brief/normal roll-up | prompt-bounded (M1-728 rollup-prompt-char-budget) — unchanged |
| Section count | bounded (max-categories, M1-721) — unchanged |
| Collection size | bounded (cluster-cap) — out of scope by decision |
| /summary render forms | pull surface — out of scope |
