---
id: M1-817
title: "Image resolution floor and limit-error wording"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  to-be-written: ImageCommandParserTest.belowFloorResolutionIsRejectedBeforeAnyGate —
  parses `/image -r 1x1024 a cat` (a plausible typo; 1,024 px) against the
  floor 16,384 and asserts a Failure carrying IMAGE_ERROR_RESOLUTION_TOO_SMALL.
  RED on main: validateResolution (ImageCommandParser.java:96-118) rejects only
  dims < 1 and the pixel ceiling, so 1x1024 parses to Success today — the
  handler then charges the credit/cooldown gates and ComfyUI runs a FULL job
  (samplingDimsFor floors every sampling edge at 16, ComfyUIClient.java:179-181,
  and caps at MAX_SAMPLING_EDGE=4096, :59, so the graph stays valid and the
  template's 0.6 MP Krea sampling budget runs before ImageScale downscales to
  the stripe). It cannot compile today — the key does not exist (grep-verified:
  no resolution_too_small / min-output-pixels anywhere in tree). `start` writes
  the test and runs it RED before any fix code (workflow §0).
analysis_ref: self
blocked_by: [M1-811]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandParser.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandParserTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/design/future/image-generation.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - An aspect-ratio cap or per-edge minimum — considered and explicitly dropped
    by the user (2026-08-10); the pixel PRODUCT floor alone is the guard.
  - A confirmation round ("are you really sure") for small or odd resolutions —
    rejected by the user; the localized rejection message IS the UX.
  - The ceiling VALUE and its two config sites (M1-811); this ticket preserves
    the ceiling check verbatim and only rewords its error.
  - The -r converter graph (M1-812), queue-depth audit arms (M1-813), sweeper
    (M1-814), sanitizer (M1-815), e2e gate (M1-816).
  - ComfyUIClient internals — samplingDimsFor's floor-16/cap-4096 behavior is
    WHY the parser is the only gate; it is consumed, never edited.
  - The /help CATALOGUE texts — help.cmd.image.usage says "bounded by a
    server-side limit", which stays true with a floor; no change (verified
    en.properties:886-888 and four siblings).
  - The marked command index line (commands.md:224 is bare `/image`, no bounds
    text) and the eight-mode failure-contract enumeration (commands.md:638-644
    — the floor rejection is a parse-time bound like the prompt cap, named in
    the bounds prose, not in that enumeration).
acceptance:
  - "ImageCommandParserTest.belowFloorResolutionIsRejectedBeforeAnyGate passes — REPRODUCTION (written and run RED at start): `-r 1x1024` and `-r 4x4` against the floor 16,384 return a Failure carrying IMAGE_ERROR_RESOLUTION_TOO_SMALL, at the parser, before any gate (commands.md §Content: the bound lives at the parser — the prompt-cap sentence at :601-603 is the precedent shape; bench/livetest-10-08-26.md:101-104 verified the sibling rejections cost nothing)."
  - "ImageCommandParserTest.floorBoundaryIsExactPixelProduct passes: 128x128 (16,384 px) parses; 127x128 (16,256) is rejected with the floor key; 1x16384 parses and 1x16383 is rejected — the floor is the pixel PRODUCT, no per-edge check (user decision: no aspect-ratio cap; P10)."
  - "ImageCommandParserTest.zeroAndMalformedResolutionsStillSpeakTheGrammarError passes — FAILURE-MODE (P1): `-r 0x0`, `-r 0x512`, `-r foo` still return IMAGE_ERROR_BAD_RESOLUTION, never the floor key — the check order (shape → parse → dims≥1 → floor → ceiling) preserves the live-verified 0x0 behavior (bench/livetest-10-08-26.md:101-104)."
  - "ImageCommandParserTest.overCeilingRejectionAnswersWithTheLargestDimsAtTheRequestedRatio passes — FAILURE-MODE (P3; RED at start: today the Failure args are the raw pixel ceiling, Long.toString(maxOutputPixels) at ImageCommandParser.java:115): with ceiling 5,000,000 (the M1-811 END value — fixture calibration, P9), `-r 3000x3000` returns IMAGE_ERROR_RESOLUTION_TOO_LARGE whose interpolation args are 2236 and 2236, asserted in division form (suggestedW <= ceiling / suggestedH)."
  - "ImageCommandParserTest.hostileOverCeilingValuesNeverSuggestAnOverCeilingResolution passes — FAILURE-MODE (P2/P3): hostile/extreme ratios over the ceiling (9223372036854775807x2, 2x9223372036854775807, 5000001x1 at ceiling 5,000,000) yield suggested dims ≥ 1 and within the ceiling (division-form assertion — never a long product), with no exception: the suggestion arithmetic multiplies no two user-controlled longs."
  - "ImageCommandHandlerTest.belowFloorResolutionIsRejectedLocalizedAndFree passes — FAILURE-MODE (P6): a below-floor `/image` through the handler returns the localized floor message with zero credits drawn (assertFalse on both tryAcquireImageUserCredit and the group bucket), no client invocation, and NO IMAGE_GENERATE audit row — the E13-verified parse-rejection shape (bench/livetest-10-08-26.md:101-104), extended to the new arm."
  - "The floor value lands in BOTH config sites with the same number: `grep -n '^infochat.image.min-output-pixels=' infochat-provider/src/main/resources/application.properties` prints 16384, and `grep -n 'min-output-pixels' infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java` shows the matching @ConfigProperty defaultValue (a missing key must not fall back to a different guard — M1-811 item 2 precedent) (P7)."
  - "The five bundles move together (D43; P4/P5): image.error.resolution_too_small is present and non-empty in all five shipped bundles, and the rewritten image.error.resolution_too_large carries the two dim placeholders in all five — Verify: BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle, .everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe, and .noPlaceholderBearingValueCarriesAnUndoubledApostropheInAnyShippedBundle all green via mvn verify. The exact user-visible wording of BOTH texts goes to the user for approval at implementation."
  - "Rides-the-diff spec amendment (M1-779 precedent; M1-803 item 7 shape): docs/spec/commands.md §Content's /image entry names the pixel floor as the lower bound of -r, rejected at the parser before any gate runs — RULE-TEXT ONLY (no dates, ticket IDs, or report citations — §12), exact wording to the user for approval; this acceptance item authorizes the work, the user approves the wording (P8). Verify: `grep -n -F -A 70 '/image [--resolution' docs/spec/commands.md` dumps the amended /image entry with the lower-bound sentence inside it, and `grep -n -iE 'floor|minimum' docs/spec/commands.md` locates that sentence within the entry's line range; then `mvn -pl infochat-provider -am verify` runs the ONLY two tests that read docs/spec/commands.md (grep-verified across the test tree) — CommandCatalogueParityTest, which pins the marked command index including the bare /image line at :224 (this amendment does not touch it), and LlmOutputSanitizerTest.matchSetEqualsSpecClosedList, which pins the §Permission model closed list — both green. NO parity test pins the §Content /image prose itself, so the grep probes plus this module verify ARE the verification (repo-root mvn verify, item 12, subsumes it)."
  - "The design note stays the value's and wording's home of record (commands.md:644-645 delegates both there; P7): docs/design/future/image-generation.md's shipped-gate-values table gains an infochat.image.min-output-pixels row (16384, Bounds naming the --resolution parser check) and its Reply-wording key enumeration (:249-251) gains IMAGE_ERROR_RESOLUTION_TOO_SMALL — Verify: `grep -n 'min-output-pixels' docs/design/future/image-generation.md` plus DocumentedConfigKeyParityTest green via mvn verify (a documented key with no code site reds it)."
  - "Controls preserved (§10; P1/P2): ImageCommandParserTest.resolutionAboveThePixelCeilingIsRejected and .malformedResolutionValuesAreRejected pass with their assertions UNCHANGED — the only edit to pre-existing test bodies is the new fourth parse() argument at each call site, authorized here (§8 test-modification authorization); the ceiling's division-form check (ImageCommandParser.java:112-116) is preserved verbatim."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - ImageCommandParserTest.belowFloorResolutionIsRejectedBeforeAnyGate
    - ImageCommandParserTest.floorBoundaryIsExactPixelProduct
    - ImageCommandParserTest.zeroAndMalformedResolutionsStillSpeakTheGrammarError
    - ImageCommandParserTest.overCeilingRejectionAnswersWithTheLargestDimsAtTheRequestedRatio
    - ImageCommandParserTest.hostileOverCeilingValuesNeverSuggestAnOverCeilingResolution
    - ImageCommandHandlerTest.belowFloorResolutionIsRejectedLocalizedAndFree
  modifies:
    - ImageCommandParserTest's pre-existing methods — the new parse() argument ONLY, no assertion changes meaning (authorized by acceptance item 11)
  preserves:
    - all tests currently green on main (BundleLoaderTest's three invariant methods, DocumentedConfigKeyParityTest, ImageCommandHandlerTest's existing suite — its :133 `2_000_000L` is a test-local stub value exercising the rejection path, not a config pin, and stays)
spec_refs:
  - docs/spec/commands.md §Content
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-817: Image resolution floor and limit-error wording

## Context

Two joined gaps in the `/image` resolution bounds, decided by the user in the
2026-08-10 design conversation (binding; this ticket grounds them, it does not
re-litigate them). **Gap 1 — no minimum.** `validateResolution` rejects only
dims < 1 and the pixel ceiling (ImageCommandParser.java:96-118), so `-r 1x1024`
(a plausible typo) or `-r 4x4` parses today, charges the credit/cooldown gates
(ImageCommandHandler.java:177-179), occupies a D35 slot (:165-166), and runs a
FULL GPU job: `samplingDimsFor` floors every sampling edge at 16 and caps at
MAX_SAMPLING_EDGE=4096 (ComfyUIClient.java:59, :170-181), so the graph stays
valid — ComfyUI samples the template's 0.6 MP Krea budget (4b-image.sh:154,
:175: latent 896x672; :481) and `ImageScale` downscales to the tiny target
(ComfyUIClient.java:140-146). Full cost for a useless stripe, no guard, no
message. **Gap 2 — limit errors speak raw pixel counts.** The over-ceiling
Failure interpolates the ceiling itself (`Long.toString(maxOutputPixels)`,
ImageCommandParser.java:115) into "That resolution exceeds the server limit
({0} pixels max)." (en.properties:902) — a user told "5000000 pixels max"
cannot answer "what resolution CAN I do?". The parser knows the requested WxH;
the error should answer with the largest allowed WxH at the user's own ratio
(3000x3000 at a 5 MP ceiling → "up to about 2236x2236"). USER DECISIONS
(binding): pixel floor = 128x128 = 16,384 px enforced at parse time; NO
confirmation round; NO aspect-ratio cap; the over-ceiling rewrite lands on the
FINAL ceiling value, hence `blocked_by: [M1-811]` (approved, uncommitted;
moves the same key and the same message argument); the floor is a NEW failure
mode the spec does not name, so a small rides-the-diff spec amendment
(rule-text only, wording to the user at implementation); scope identity — a
chat bot on a home box: the guard catches typos and stops waste, it does not
curate art. JOIN (user instruction): ONE ticket — both gaps live in
`validateResolution` + the `image.error` bundle keys, same files, same
behavior surface. `security_relevant: false` — the ceiling check and value are
untouched (the DOS bound is preserved verbatim); the floor is a waste guard,
and the new wording's interpolation args are numeric strings only (the prompt
never enters an error — D75). Threat model read was scaled to the surface:
security.md §Rate limiting's D35 enumeration (:1909-1922, names /image) and
the "dangerous direction" passage (:1896-1900 — M1-811's analogy for the
ceiling; this ticket moves no limit value). Prior-art corrections, verified:
(a) the block's "HelpTopicCorpusTest/BundleLoaderTest pin bundle invariants"
is imprecise — BundleLoaderTest pins the bundles (:82, :120, :158);
HelpTopicCorpusTest pins the ten-topic help corpus (:80-96 — no image topic)
and matters only as the read-main-application.properties precedent (:300);
(b) "no prior ticket ever proposed a minimum resolution" could not be checked
via git log in the analysis session (no shell) — verified in-tree instead: a
tree-wide grep for min-output-pixels / resolution_too_small / pixel floor
returns nothing, and the M1-797..M1-816 corpus contains no floor concept
(ASSUMPTION the implementor may confirm with `git log --grep resolution`).

## Root cause

Verified against the tree on 2026-08-10. **Gap 1:** `validateResolution`
(ImageCommandParser.java:96-118) enforces exactly three things — `WxH` shape
(:97-100), numeric parse (:103-108), dims ≥ 1 (:109-111) — then the ceiling in
division form (:112-116). There is no lower bound, so any product ≥ 1 and
≤ ceiling parses (`Success` at :92); the handler charges the gates
(ImageCommandHandler.java:177-179) and dispatches, and the backend has NO
minimum of its own — `buildGraph(prompt, w, h)` (:130-148) samples at
budget-ratio dims floored at 16 (:179-181) and fits to the exact target via
`ImageScale` (:140-146), so the full sampling budget runs for any target. The
parser is the only gate. **Gap 2:** the over-ceiling arm passes
`Long.toString(maxOutputPixels)` as `{0}` (:114-115) and all five bundles
render it as a raw pixel count (en.properties:902, cs:693, es:917, ru:883,
tr:787) — actionable information (the requested WxH) is available in the same
function and discarded. Both gaps are one surface: the parse-time resolution
bounds and their user-visible wording.

## Pitfalls

This is a single-ticket decomposition (`analysis_ref: self`); numbering is
local to this ticket.

- P1: Check ordering is user-visible behavior. `-r 0x0` must keep returning
  IMAGE_ERROR_BAD_RESOLUTION (live-verified, bench/livetest-10-08-26.md:101-104);
  a floor check placed before the dims≥1 check would re-key it. §10: the old
  path's rejection ordering is an incidental obligation the new arm must carry
  across. Order stays: shape → parse → dims≥1 → floor → ceiling.
- P2: Overflow-safe arithmetic. The ceiling check's division form
  (ImageCommandParser.java:112 comment: "width * height would wrap long for
  hostile values") is a control that already bit once. The floor check and
  BOTH suggestion computations must multiply no two user-controlled longs:
  floor in division form (`width <= (minOutputPixels - 1) / height`),
  suggestions via double sqrt-ratio then division-form clamps.
- P3: The suggested WxH must never exceed the ceiling (and both dims ≥ 1).
  A suggestion that itself fails the ceiling sends the user into a rejection
  loop — worse than the raw count. Double rounding can land the scaled product
  one pixel over (or, at extreme ratios, one dim at 0); clamp deterministically
  in integer arithmetic: floor-dims for the ceiling suggestion, ceil-dims for
  the floor suggestion, each followed by a division-form correction against the
  bound. Tests assert in division form too (`suggestedW <= ceiling / suggestedH`).
- P4: D43 — the five bundles move together. The NEW key and the REWRITTEN key
  land in en, cs, es, ru, tr in the same diff. A key added only to en passes
  at runtime (en fallback masks it) and is exactly the hole
  BundleLoaderTest's own-keyset methods were built to catch (:82, :120 —
  M1-474/M1-475). M1-803 P20 precedent.
- P5: MessageFormat hazards. Both touched values carry placeholders, so any
  apostrophe must be doubled (`''`) or the quote swallows the rest of the
  pattern and ships `{0}` raw to users (M1-762 live defect;
  BundleLoaderTest.noPlaceholderBearingValueCarriesAnUndoubledApostropheInAnyShippedBundle
  :158-201 is the gate). Pass dims as strings (Long.toString) to dodge
  locale grouping (existing style, ImageCommandParser.java:115;
  ListSourcesCommandHandler.java:285 comment). Update the BundleKeys javadoc
  whose claim "{0} = the ceiling" (BundleKeys.java:2175) becomes false (§11:
  a stale comment is worse than none).
- P6: Parse rejections stay pre-gate and content-free: no audit row, no
  ComfyUI prompt, no credit/cooldown/queue consumption — the shape
  bench/livetest-10-08-26.md:101-104 verified for the two existing rejections.
  The new arm is a new return path inside `parse()`; nothing downstream of it
  changes, and its interpolation args are numeric only (the prompt never
  reaches an error message — D75). §10: do not create an arm that writes a row
  where its siblings write none (the E13 audit asymmetry was precisely that).
- P7: Config/doc parity in one diff. The new key lands in THREE sites together:
  the @ConfigProperty site (ImageCommandHandler.java:110-111 pattern),
  application.properties, and the design-table row — DocumentedConfigKeyParityTest
  gates documented→real (:103-131), so a design row without the code key reds
  the build, and the defaultValue must equal the shipped value (a missing key
  must not fall back to a different guard — M1-811 item 2 precedent). The
  design doc's Reply-wording key enumeration (:249-251) gains the new constant
  or the note stops being truthful.
- P8: §12 discipline for the rides-the-diff amendment — rule text only (no
  dates, ticket IDs, or report citations in the spec prose), exact wording
  shown to the user for approval before it lands; the acceptance item
  authorizes the WORK, never the wording. M1-779 precedent, M1-803 item 7 shape.
- P9: Fixtures calibrate to the family END state. The wording examples pin the
  ceiling 5,000,000 (M1-811's final value) as a test-local parameter — writing
  them against today's shipped 2,000,000 would force a sibling edit when
  M1-811 lands (livetest analysis P14 precedent; M1-816's blocked_by shape).
  Same reason the design-table row lands AFTER M1-811 rewrites the
  max-output-pixels row (value + Bounds wording) — same table, serial edits.
- P10: Scope identity — the floor is the pixel PRODUCT, 16,384 px; NO
  aspect-ratio cap, NO per-edge minimum, NO confirmation round (all considered
  and dropped by the user). The implementation must not "helpfully" add edge
  checks: 64x256 (= 16,384 px) and 1x16384 pass BY DESIGN. §1: every changed
  line traces to the acceptance criteria or the user's stated request.

## Approach

Derived from `spec_refs:` — commands.md §Content already commits to the
surface both changes ride: `-r` is "an *output* size, bounded by a server-side
ceiling" (:597-600), the prompt cap is "rejected over cap before any gate
runs … so the bound lives at the parser" (:601-603 — the precedent sentence
for a parse-time bound), every failure mode returns a localized explanation
(:638-644), and "Argument shapes, defaults, … reply wording live in design
notes" (:644-645). The floor is an ADDITIVE bound on the same committed
surface — it breaks no existing promise (every previously-accepted `-r` above
the floor still parses; the rejection shape exists and is live-verified) — so
the amendment rides this diff recording it (M1-779 shape), not a SPEC-GAP.
Rejected alternatives (for the commit message's `Alternatives considered:`):
(a) floor message stating raw pixels ("16384 pixels min") — repeats gap 2's
sin, unactionable; (b) floor message naming "128x128 minimum" — misleading for
non-square ratios the product floor admits; (c) split tickets for the two gaps
— same function, same bundle block, same test class; the wording half is
blocked on M1-811 either way, and splitting would put two concurrent edits in
one 25-line function. Chosen: both limit errors answer with WxH AT THE USER'S
OWN RATIO — the ceiling error the largest allowed, the floor error the smallest
allowed (4x4 → "at least about 128x128"; 1x1024 → 4x4096).

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Inert surfaces first: the BundleKeys constant IMAGE_ERROR_RESOLUTION_TOO_SMALL
     (+ javadoc; fix the TOO_LARGE javadoc's `{0}` claim), the five bundle
     entries (new key + rewritten key, user-approved wording, doubled
     apostrophes), the @ConfigProperty `infochat.image.min-output-pixels`
     (defaultValue 16384) and the application.properties key. Nothing calls
     them yet; BundleLoaderTest and DocumentedConfigKeyParityTest go green
     immediately (P4/P5/P7).
  2. Write the reproduction + the failure-mode tests RED (workflow §0).
  3. Parser: `parse()` gains the `minOutputPixels` parameter, and the parse
     javadoc at :33-34 ("both bounds are config") is updated to cover the
     third bound (§11: the comment must state the NEW truth); `validateResolution`
     gains the floor check AFTER dims≥1 and
     BEFORE the ceiling (P1), division form (P2); the over-ceiling arm computes
     the largest dims at the requested ratio — double sqrt-ratio scale, floor,
     `max(1)`, `min(ceiling)`, one division-form clamp (P2/P3) — and passes
     them as string args; the new floor arm computes the smallest allowed dims
     symmetrically (ceil + division-form lift to the floor product).
  4. Handler wiring: pass the config value through at the single call site
     (ImageCommandHandler.java:133-134). No other handler line changes.
  5. The spec amendment (user-approved wording first — §12, P8) and the
     design-note row + Reply-wording enumeration entry (P7/P9).
  6. Full `mvn verify` (§5).
- **Controls to preserve (§10):** the ceiling's division-form check
  (ImageCommandParser.java:112-116) verbatim — M1-811 P1 names it a DOS-bound
  control; the rejection ordering (P1); parse rejections pre-gate, content-free,
  rowless (P6, bench-verified); string interpolation args, prompt never in an
  error (D75); BundleLoaderTest's three invariant methods and
  DocumentedConfigKeyParityTest unedited; every pre-existing
  ImageCommandParserTest assertion unchanged in meaning (call sites gain the
  new argument only — acceptance item 11).
- **Pitfall→mitigation:** P1→step 3's ordering + item 3; P2→step 3's
  division forms + item 5; P3→step 3's clamp recipe + items 4-5; P4→step 1 +
  item 8; P5→step 1 + item 8's apostrophe gate; P6→steps 3/4's parser-local
  change + item 6; P7→steps 1/5 + items 7/10; P8→step 5 + item 9; P9→blocked_by
  + step 5's ordering + items 4/10; P10→step 3's product-only check + item 2.

## Definition of done

Every acceptance item green by its named verification: the reproduction passes
(below-floor `-r` rejected at the parser with the new key); the floor boundary
is the exact pixel product with no edge checks; zero/malformed values still
speak the grammar error; the over-ceiling error answers with the largest dims
at the requested ratio and never suggests an over-ceiling resolution, even for
hostile values; the handler-level below-floor path is localized and free
(nothing consumed, no row); the floor value sits in both config sites and the
design row; the five bundles move together under all three BundleLoaderTest
gates; the spec amendment is rule-text-only and user-approved; the design note
stays truthful; the pre-existing parser tests pass with assertions unchanged;
full repo verify green.

## Verification

- P1 → ImageCommandParserTest.zeroAndMalformedResolutionsStillSpeakTheGrammarError
  — feeds `-r 0x0`, `-r 0x512`, `-r foo`; asserts IMAGE_ERROR_BAD_RESOLUTION,
  never the floor key.
- P2 → ImageCommandParserTest.hostileOverCeilingValuesNeverSuggestAnOverCeilingResolution
  — feeds 9223372036854775807x2 and siblings at ceiling 5,000,000; asserts no
  exception and division-form bounds (a `width * height` product anywhere in
  the suggestion math wraps or throws on these inputs).
- P3 → ImageCommandParserTest.overCeilingRejectionAnswersWithTheLargestDimsAtTheRequestedRatio
  (3000x3000 → args 2236/2236 at 5,000,000; deleting the clamp reds the
  division-form assertion on rounding-overshoot fixtures) + the hostile-values
  method's `suggestedW <= ceiling / suggestedH` and `≥ 1` assertions.
- P4 → BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle
  + .everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe — a key missing
  from any one bundle reds them (en fallback cannot mask the own-keyset read).
- P5 → BundleLoaderTest.noPlaceholderBearingValueCarriesAnUndoubledApostropheInAnyShippedBundle
  (an undoubled apostrophe in either touched pattern reds it) + a review read
  of the BundleKeys javadocs against the new arg lists.
- P6 → ImageCommandHandlerTest.belowFloorResolutionIsRejectedLocalizedAndFree —
  below-floor `/image` through the handler; asserts the localized message,
  zero credit draws, zero client calls, zero IMAGE_GENERATE rows.
- P7 → acceptance item 7's two greps + DocumentedConfigKeyParityTest green
  (documenting the key with no code site reds it; the defaultValue grep pins
  the fallback).
- P8 → acceptance item 9's two greps over docs/spec/commands.md +
  `mvn -pl infochat-provider -am verify` (the two commands.md-reading parity
  tests green) + a review read of the amended prose for §12 violations
  (no dates/ticket IDs/report citations).
- P9 → the blocked_by ordering itself + item 4's 5,000,000 fixture (reverting
  the ceiling parameter to 2,000,000 changes the expected suggestion — the
  test discriminates the END state, not today's shipped value).
- P10 → ImageCommandParserTest.floorBoundaryIsExactPixelProduct — 1x16384
  parses while 1x16383 is rejected (an added per-edge check reds it).
- P14 → inherited cross-reference, NOT a local pitfall: livetest-image-defects.md
  analysis P14 is the fixture-calibration trap this ticket's P9 carries. Folded
  into the P9 entry above — caught by the same probe (item 4's 5,000,000
  fixture discriminates the family END state; reverting to 2,000,000 changes
  the expected suggestion).
- P20 → inherited cross-reference, NOT a local pitfall: M1-803 P20 is the
  D43 all-bundles-localized trap this ticket's P4 carries. Folded into the P4
  entry above — caught by the same probe (BundleLoaderTest's own-keyset gates
  red on a key missing from any of the five bundles; item 8).
- Non-vacuity: removing the floor check reds the reproduction; leaving the
  ceiling arm's old pixel-count arg reds item 4; clamping with a decrement
  loop instead of division form hangs or reds on the hostile inputs; shipping
  the new key to four bundles reds item 8's gates.

## Out-of-scope

Named in `out_of_scope`: the user-dropped guards (aspect-ratio cap, per-edge
minimum, confirmation round) — the floor is the pixel product alone, and a
1-pixel-wide 16,384-px strip passes by design; the ceiling value (M1-811 owns
it; this ticket preserves the check verbatim and only rewords its error); the
other livetest tickets' surfaces (M1-812..M1-816); ComfyUIClient internals
(its floor-16 sampling is WHY the parser is the only gate — consumed, never
edited); the /help texts (considered: "bounded by a server-side limit" stays
true — verified en.properties:886-888); the marked index line and the
eight-mode failure-contract enumeration (the floor rejection is a parse-time
bound named in the bounds prose, like the prompt cap, not an operational
failure mode). Pre-existing tests modified: ImageCommandParserTest's eleven
`parse(...)` call sites gain the new fourth argument ONLY — no assertion
changes meaning (authorized by acceptance item 11 per §8). No other
pre-existing test is modified; the spec files are touched only under §12
approval (acceptance item 9).

## Census

This ticket guards a two-site class; both sites are enumerated.

**Class 1 — resolution entry points.** Re-runnable enumeration:
`grep -rn 'Resolution\|-r ' infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandParser.java`.
Rows: `validateResolution` (:96-118) — the ONLY bounds site, FIXED by this
ticket; `resolutionOf` (:120-124) — re-parses the value only AFTER
validateResolution returned null (:61-65), no independent bound needed
(out-of-scope: unreachable without a passing validation); the `-r` token arm
(:54-66) — routes through validateResolution, unchanged. No other file parses
a user resolution (grep-verified: no other caller of `ImageCommandParser.parse`
exists besides ImageCommandHandler.java:133 and the parser test).

**Class 2 — touched bundle keys × shipped bundles.** Re-runnable enumeration:
`grep -c '^image\.error\.' infochat-provider/src/main/resources/bundles/*.properties`
returns equal counts across all five files after the diff (two keys move:
resolution_too_small added, resolution_too_large rewritten). Rows: en:901-902,
cs:692-693, es:916-917, ru:882-883, tr:786-787 (pre-change line numbers) —
disposed by BundleLoaderTest's three gates going green (a missing or
apostrophe-broken value in any bundle reds them).

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-817`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-817-image-resolution-floor-and-limit-wording.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
