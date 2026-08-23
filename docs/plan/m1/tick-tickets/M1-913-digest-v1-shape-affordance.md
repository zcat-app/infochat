---
id: M1-913
title: "Digest v1 shape: count header, 10 headlines, drill-down close"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  DigestRendererV1ShapeTest.normalDigestOpensWithWindowStoryCountAndClosesWithSummaryDrilldown
  (to-be-written) — converted at start: written first, run RED. Verified on
  this checkout (2026-08-23): a NORMAL digest opens directly with the lead
  or first category section (DigestRenderer.java:427-505 — no window-size
  header exists anywhere in renderSections), renders at most
  infochat.digest.category-headline-count=5 headlines per category
  (:134-135, :956), and closes with
  reply.digest.closing_affordance="@mention me to go deeper on any story,
  or ask about a topic you don't see here." (en.properties:890) — which
  never names the /summary <tag> drill-down the owner's v1 shape closes
  with (owner direction 3, 2026-08-23: "top stories + per-followed-tag
  wrap-up, with the affordance to drill into a category (/summary <tag>)
  or the individual post"). The v1-era healthy digests were 5.8–8.8k chars
  (brief-supplied prod measurement, summary_cache ids 51/52).
analysis_ref: docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md
blocked_by:
  - M1-912
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererV1ShapeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The volume bound itself (FULL cap, bounded degraded forms, is_degraded
    honesty) — sibling M1-912, the blocker. This ticket lands after it and
    rebases onto its render code (both touch DigestRenderer and the
    bundles).
  - >-
    Section structure: followed-tag keying (M1-867 — already renders one
    section per followed node), D62 assignment/order, the section cap
    (M1-721), the lead's existence/size/ordering (M1-725/D71). The v1
    "top stories" slot IS the lead; this ticket does not re-derive it.
  - >-
    The roll-up prompt and sentence bands (M1-728) — the per-category
    wrap-up synthesis stays as it is; this ticket changes what surrounds
    it (count header, headline count, closing copy).
  - >-
    brief and FULL mode shapes beyond what M1-912 already bounds; the
    window-count header is pinned for the NORMAL digest (the default
    mode); whether brief/FULL also carry it is decided at implementation
    against the approved spec wording, not guessed here.
  - >-
    `/summary`'s forms and footers (reply.summary.short.category_footer
    already steers to /summary <tag>; untouched).
  - >-
    The translation budget `infochat.digest.translation-max-per-render`
    (stays 5 — see P8; re-tuning it is a separate decision), any
    ranking/engagement change (M1-914), any source-kind change (M1-915),
    and any new config key beyond the default re-tune named below.
  - any other module
acceptance:
  - "REPRODUCTION closed: DigestRendererV1ShapeTest.normalDigestOpensWithWindowStoryCountAndClosesWithSummaryDrilldown passes — a NORMAL digest over a multi-section fixture opens with ONE localized header line naming the window's total story count and the number of followed-topic sections (new bundle key, {0,choice,...}/{1,choice,...} plural shapes), placed ahead of the lead section's content by riding the FIRST returned section's text (no new message — D63 delivery structure unchanged), and closes with the reworked closing affordance naming the /summary <tag> drill-down (item 3). Pre-change: no window header exists and the affordance steers only to @mention chat (en.properties:890). Spec support: docs/spec/commands.md §Periodic group digests as amended by item 7."
  - "DigestRendererSectionsTest asserts the 10-headline default: infochat.digest.category-headline-count's base declaration in application.properties, the @ConfigProperty defaultValue, and the docs/design/07-deployment.md §7.4 row all become 10 together in this diff, and DocumentedConfigKeyParityTest passes — probe: grep -n 'category-headline-count' infochat-provider/src/main/resources/application.properties docs/design/07-deployment.md. The translationMaxPerRender comment math (DigestRenderer.java:140-149, 8 × 5 = 40) is re-stated for the new default, recording that more headlines now render untranslated-BRACKETED past the 5-call generative budget for non-en scopes — the budget itself is NOT re-tuned (analysis P8)."
  - "DigestDeliveryTest's affordance-once and not-on-the-lead assertions pass against the reworked closing affordance: the new copy names /summary <tag> for category depth (the per-section footers already carry the concrete tag tokens) AND keeps the @mention chat steer for individual-post depth, in all five bundles; exact wording goes to user approval with the item-7 spec amendment (§12). Placement is untouched — folded into the LAST section's text, exactly once per digest."
  - "The pre-existing pins asserting the old closing-affordance copy or the 5-headline default are updated with §8 authorization — enumerated at start by grep -rn -E 'closing_affordance|category-headline-count' over the three test files in test_plan.modifies — and each updated test asserts the NEW copy or NEW count at the same strength (no assertion weakened to a vacuous contains-check, §8 semantic rules)."
  - "BundleLoaderTest keyset parity passes: the new window-header key and the reworked affordance land in ALL FIVE bundles (en, cs, es, ru, tr) in this diff, and the count-bearing header key carries per-language plural categories (cs/ru differ from en — reply.digest.categories.more is the shape template) (analysis P6)."
  - "FAILURE-MODE: DigestRendererV1ShapeTest.zeroOrOneSectionDigestStillClosesWithTheAffordanceExactlyOnce passes — a digest with a single section (no lead, below lead-minimum) carries the window header on that section and the affordance exactly once at its end: the header placement must not assume a lead exists, and the affordance fold must not double when header and affordance land on the same section."
  - "The spec amendment lands with wording to user approval (§12, rule-text only) — probe: grep -n 'headline' docs/spec/commands.md: docs/spec/commands.md §Periodic group digests states the v1 shape — the digest opens with a window-size line, each followed-topic section wraps up with a roll-up plus up to 10 headlines, and the closing affordance names the /summary <tag> drill-down and the @mention chat path; docs/design/03-commands.md §3.12 is synced."
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererV1ShapeTest.java
      — the reproduction test; the single-section failure-mode case; the
      window header's counts asserted against the fixture's true cluster
      totals (not the rendered subset); a non-en plural rendering of the
      header.
  modifies:
    - >-
      DigestRendererSectionsTest / DigestRendererTest /
      DigestDeliveryTest — copy and count pins updated per acceptance
      item 4 (§8-authorized); headline-count fixtures move to the 10
      default.
  preserves:
    - >-
      Every M1-912 volume-bound assertion (the blocker ticket's new
      tests) — this ticket changes copy/defaults, not bounds; its own new
      tests are written against the post-M1-912 END state (the M1-785
      fixture-calibration rule).
    - >-
      The M1-721 section-cap overflow line and M1-725 lead behavior
      assertions; D63 delivery message counts (no message is added or
      removed — the header rides an existing section).
    - >-
      DigestWorkerClockTest and RecordingDigestRenderer — untouched.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/design/03-commands.md §3.12
decision_refs:
  - D43
  - D62
  - D63
---

# M1-913: Digest v1 shape — count header, 10 headlines, drill-down close

## Context

Owner direction (2026-08-23): the digest returns to the simple v1 shape —
high information value up front (top stories + per-followed-tag wrap-up
with the top ~10 posts), and the affordance to drill into a category
(`/summary <tag>`) or the individual post. Most of that shape already
exists post-M1-725/M1-732/M1-867: the lead IS top-stories, sections key at
followed tags (DigestRenderer.java:326), the roll-up IS the wrap-up, and
per-section footers already steer to `/summary <tag>`
(en.properties:218). What is missing versus v1: nothing says how big the
window was (the honest "N posts matched — showing the top" signal the
owner asked for in defect 1), the headline depth is 5 not ~10, and the
closing affordance steers only to @mention chat (en.properties:890),
never naming the /summary drill-down v1 closed with. See
docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md.

## Root cause

The hybrid-body family (M1-721..M1-725, M1-732) rebuilt the category body
and lead but kept the pre-lead closing affordance copy and the 5-headline
default; no digest-open line was ever added (the per-section true-count
headers cover per-category shape, not the whole window). This is a
copy/default/composition gap, not a structural one.

## Pitfalls

- P6: Bundle parity — five languages (en/cs/es/ru/tr), same diff,
  {0,choice,...} plurals with per-language categories (D43 keyset gate,
  BundleLoaderTest).
- P7: Spec wording goes through user approval; rule-text only, no dates
  or ticket IDs in spec prose (engineering-rules §12).
- P8: The 5→10 headline default invalidates the translationMaxPerRender
  comment math (DigestRenderer.java:140-149) and pushes more headlines
  into untranslated-BRACKETED rendering past the 5-call budget for non-en
  scopes — state it, do not silently re-tune the budget (engineering-rules
  §1). Existing copy/count pins need §8-authorized updates, never
  weakening (§8 semantic rules).
- P9: The window header's counts must describe the WINDOW (the full
  categorized cluster totals pre-cap, matching the section headers'
  true-count semantics, M1-732), not the rendered subset — a header that
  says "40 stories" over a digest that shows 12 headlines is the honest
  signal; one that says "12" teaches the reader nothing is withheld.
- P10: Placement — the header rides the FIRST returned section's text so
  D63 delivery structure, the LEAD_TAG split (DigestDelivery) and
  digest_section persistence are untouched; a leadless digest must still
  carry it (the failure-mode acceptance item).

## Approach

Derived from spec_refs: the docs/spec/commands.md §Periodic group digests
amendment states the v1 shape (window line, roll-up + ~10 headlines per
followed-topic section, drill-down close); D43 supplies the bundle-parity
mechanics; D63 is preserved by riding existing sections.

- **Files to touch:** see files_scope.
- **Steps, in order:** (1) write the reproduction + failure-mode tests RED
  against the post-M1-912 tree; (2) compose the window-count header onto
  the first returned section (counts from the categorized totals, P9/P10);
  (3) headline-count default 10 + comment restatement (P8); (4) closing
  affordance copy in five bundles (P6); (5) §8-authorized test updates
  (P8); (6) spec/design amendments (P7).
- **Controls to preserve (engineering-rules §10):** the header
  interpolates integer counts and the affordance is static
  renderer-authored copy — no untrusted content enters either (the
  sanitizer surface is untouched, which is why security_relevant is false);
  the affordance-once/last-section placement and its DigestDeliveryTest
  pins; translation-budget behavior (comment updated, code untouched).
- **Pitfall→mitigation:** P6→acceptance 5; P7→acceptance 7; P8→acceptance
  2/4; P9→reproduction test asserts window totals; P10→failure-mode
  acceptance 6.

## Definition of done

Every acceptance item verified by its named test/probe, including the
single-section failure-mode case; mvn verify green from the repo root.

## Verification

- Reproduction → DigestRendererV1ShapeTest.normalDigestOpensWithWindowStoryCountAndClosesWithSummaryDrilldown
  — multi-section NORMAL fixture: header counts == window totals, header
  precedes lead content, closing affordance names /summary <tag> and
  appears exactly once.
- P6 → BundleLoaderTest keyset parity across the five bundles plus one
  non-en plural rendering of the window-header key; a key present in en
  but missing from tr fails the gate.
- P7 → the spec-amendment acceptance probe (grep -n 'headline'
  docs/spec/commands.md) confirms the amended section states the v1
  shape; the wording itself is user-approved at implementation, never
  pinned by a test.
- P8 → DigestRendererSectionsTest headline-count assertions at the 10
  default; the bracketing-past-budget behavior is NOT changed (the
  existing translation-budget tests pass unmodified — a silent budget
  re-tune would fail them).
- P9 → the reproduction's count assertions compare against fixture totals
  computed pre-cap, so a header counting only rendered items fails.
- P10 → DigestRendererV1ShapeTest.zeroOrOneSectionDigestStillClosesWithTheAffordanceExactlyOnce
  (failure-mode: no lead, one section — header and affordance share a
  section without duplication; a placement that assumes a lead fails
  here).

## Out-of-scope

Everything the blocker and siblings own (volume bound, ranking, kind
flip). No structural render change: section set, section order, lead,
roll-up content, and delivery message structure are all untouched — this
ticket is composition (one header line), one default re-tune, and copy.
No new config key is added (the headline-count key exists; only its
default changes). The FULL/brief treatment of the window header beyond
NORMAL is decided against the approved spec wording at implementation, not
guessed here. Test modifications are exactly the three files in
test_plan.modifies, each §8-authorized in acceptance item 4.

## Census

The class scoped here: every bundle locale must carry the new/changed
keys, and every pre-existing test pin on the changed copy/default must be
disposed. Enumerated:

| Site | Disposition |
|---|---|
| bundles/en.properties | **fix** — window-header key added; closing affordance reworked |
| bundles/cs.properties | **fix** — same keys, cs plural categories |
| bundles/es.properties | **fix** — same keys |
| bundles/ru.properties | **fix** — same keys, ru plural categories |
| bundles/tr.properties | **fix** — same keys |
| BundleKeys.java | **fix** — constant for the new header key |
| DigestRendererSectionsTest copy/count pins | **fix** — §8-authorized update (acceptance 4) |
| DigestRendererTest copy/count pins | **fix** — §8-authorized update (acceptance 4) |
| DigestDeliveryTest affordance pins | **fix** — §8-authorized update (acceptance 4) |
| DigestWorkerClockTest / RecordingDigestRenderer | unchanged — no render-mechanics change |
