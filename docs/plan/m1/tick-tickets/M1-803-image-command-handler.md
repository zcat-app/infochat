---
id: M1-803
title: "/image command handler: gates, translation, echo, audit"
status: done
created: 2026-08-08
last_updated: 2026-08-10
flow: tick
reviews:
  - round: 1
    date: 2026-08-10
    verdict: MANUAL
    checks:
      SPEC-TRUTHNESS-CHECK: FAIL
      SECURITY-CHECK: FAIL
      TEST-ADEQUACY-CHECK: FAIL
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "round-1 full diff: 28 files changed, 2466 insertions(+), 60 deletions(-)"
  - round: 2
    date: 2026-08-10
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "round-2 fix hunks: 8 files changed, 487 insertions(+), 27 deletions(-); full diff: 30 files changed, 2933 insertions(+), 67 deletions(-)"
    notes: >-
      Post-refine round (round-1 MANUAL resolved by the user's escalate arm 1
      call: wire the converter + settle the DECIDE-BEFORE). All five round-1
      findings dispositioned SATISFIED with located passing probes; seven
      candidate findings falsified-and-dropped (verdict:
      .scratch/tick-review-M1-803-r2.txt). Full verify green
      (.scratch/tick-test-M1-803-r3.log). Live probe (/image -r 512x512
      against the M1-797 container) noted by the reviewer as outside the
      gate's evidence set — remains a deploy-time check.
  - round: 3
    date: 2026-08-10
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "round-3 verification full diff: 30 files changed, 2952 insertions(+), 67 deletions(-); code/test tree identical to the round-2 APPROVE'd tree (r2->r3 delta = the round-2 review record only)"
    notes: >-
      User-requested independent verification re-review (kimi reviewer)
      of the FULL diff, saved alongside the round-2 verdict
      (.scratch/tick-review-M1-803-r3.txt; mechanical report
      .scratch/tick-mech-M1-803-r3.txt). 0 rework, 0 critical/high, 7
      candidates falsified-and-dropped. One RECOMMENDED-NEW-TICKET
      (TOUCHED-BY-THIS-DIFF: yes, no DECIDE-BEFORE) recorded under
      "Review observations" — a regression-net hole, shipped code correct.
clarity_check: >-
  start 2026-08-10 pass — tick-lint 0 findings; blocked_by M1-800/801/802/805
  all done. Citations verified: InboundRouter.java:1803 (unknown-command body),
  :1044-1051 (per-user cap), :1190-1207 (isInterruptible), AuditAction.java:4-13,
  RateCapBucket maps/Settings/withers/sweep (:150-196/:244-341/:534-542/:641-655),
  commands.md:586-648 + marked index :206-248 (no /image line). Line-drift note:
  security.md citations predate later merges — the D35 enumeration now sits at
  :1909-1922 and the /image translator leg at :2190-2206; SETUP_GUIDE.md's
  leg-by-leg section at :678-712 (ticket said :593). Census re-run clean (all
  rows present; task-count "seven" mentions are the generative-task set, not
  translator legs, and stay). Analysis pitfalls P2/P4/P6/P10/P11/P12/P13/P14/P19/P20
  all landed; P16 discharged by recording the new gate values in the design notes
  (commands.md:643-644 commits they live there). blocked_by seam tests traced:
  ComfyUIClientTest/ImageSpoolTest/PngMetadataStripTest/OutboundDeliveryAttachmentTest
  pin classes this ticket INVOKES, never modifies, except one additive exception
  subtype on ComfyUIClient to tell breaker-open from unreachable (files_scope
  deviation, surfaces at review). Design tension resolved in self-check: the
  visible() config gate vs HelpCommandHandlerTest's unmodified CATALOGUE-iterating
  detail test ⇒ the config field is Optional<String> where null (no-CDI test
  construction) reads as configured; CDI always injects a non-null Optional
  (empty when unset), so production gating is exact and no pre-existing test
  changes. Refine 2026-08-10 (round-1 MANUAL, escalate arm 1, user decision):
  the ticket ABSORBS the converter guardrail the 2026-08-09 design decisions
  and the M1-798 relay assigned it (round-1 FINDING 1), plus round-1 FINDINGS
  2-5 as rework items. The relayed DECIDE-BEFORE (reanalysis :307-310,
  addendum decision 4) is RESOLVED by the user: (1) budget source — the
  client derives the baked sampling budget from the template's latent node
  (KSampler latent_image link) baked width/height at load; NO wizard key,
  M1-798 unamended, no key/template drift. (2) target landing — exact W/H:
  the serializer sets per-job latent dims (requested ratio at budget, /16)
  and swaps the fit node ImageScaleToTotalPixels -> ImageScale(width,height)
  for -r jobs; no-flag jobs keep the baked graph. (3) strategy — unified
  model-agnostic: sample at budget at the requested ratio, lanczos exact fit;
  recorded deviation from Final decision 3's "Mage samples directly at
  target" (Mage lanczos-fits from the 1 MP budget instead — user-approved).
  Resolution recorded in the design doc at addendum decision 4.
reproduction: >-
  to-be-written: ImageCommandHandlerTest.unconfiguredBaseUrlYieldsTheUnknownCommandReply —
  the intended test invokes `/image -p foo` with `infochat.image.base-url`
  unset and asserts the reply is exactly the router's unknown-command body
  (BundleKeys.ERROR_UNKNOWN_COMMAND, InboundRouter.java:1803) and that /help
  does not list it; it cannot compile today because no ImageCommandHandler
  exists (grep-verified: no infochat.image.* key anywhere; the marked command
  index at docs/spec/commands.md:206-248 has no /image line). `start` writes
  the test and runs it RED before any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: [M1-800, M1-801, M1-802, M1-805]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/
  - infochat-provider/src/main/resources/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - docs/spec/commands.md
  - docs/spec/security.md
  - prod/switch-llm.sh
  - prod/scripts/4-llm.sh
  - SETUP_GUIDE.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The attachment SPI, codecs, spool, strip, and backend client
    (M1-799..M1-802 — this ticket ASSEMBLES them).
  - Adding /image to CommandPermissions.ALLOWED (D73: deliberately absent
    from the slow-start set; adding it is a spec amendment — analysis P19).
  - A retry path for failed generations (design: none; the user retypes).
  - A per-request or chat-level model flag (design: operator-level switching
    via the wizard, M1-798).
  - Any change to QueryAnchorTranslator itself (the leg is reused as-is).
acceptance:
  - "ImageCommandHandlerTest.unconfiguredBaseUrlYieldsTheUnknownCommandReply passes — REPRODUCTION (written and run RED at start): D73 config gating is RUNTIME (analysis P11) — the bean, the HelpCommandHandler.CATALOGUE entry, and the marked-index /image line land TOGETHER in this diff (commands.md:645-648), and CommandCatalogueParityTest stays green; absence is the config gate in help's visible() filter plus the unknown-command body on invoke."
  - "Parser (commands.md:586-601): bare args are the prompt; --prompt|-p captures the remainder verbatim as the LAST flag; --resolution|-r <WxH> parses as an output size bounded by a server-side ceiling and the adapter's maxOutboundAttachmentBytes; the prompt length cap (profile-driven) rejects OVER CAP BEFORE ANY GATE RUNS — Verify: ImageCommandParserTest.overCapPromptIsRejectedBeforeAnyGate (FAILURE-MODE: a maximally-sized prompt never reaches cooldown/credit/queue state) plus grammar cases."
  - "Control gates, deterministic, in spec order (commands.md:602-608, D76): per-user cooldown in DM AND group; per-user AND per-group hourly credit buckets charged on attempt (the gate is an AND — both must yield, a refund returns both); global queue-depth gate refusing immediately. Verify: ImageCreditGateTest.cooldownAppliesInDmAndGroup (fixed Clock — §9, analysis P13), .creditGateIsAnAndAndRefundReturnsBoth, .queueOverBudgetRefusesImmediatelyAndRefunds."
  - "Refund boundary is exact (D76, analysis P12): refund iff the GPU never ran — backend unreachable, breaker open, queue over budget, timeout before start; NO refund once the GPU ran, including adapter-send failure and over-limit attachment. Verify: ImageCommandHandlerTest.adapterSendFailureDoesNotRefund (FAILURE-MODE) and .backendUnreachableRefunds."
  - "The supportsOutboundAttachments flag is checked BEFORE charging (analysis P2 — it is static): ImageCommandHandlerTest.falseFlagAdapterAnswersTextFallbackWithoutCharging (FAILURE-MODE: zero credits drawn, client never invoked)."
  - "D35 enrollment (commands.md:609-613; analysis P10): InboundRouter.isInterruptible admits image, so the per-user concurrency ceiling and /stop apply — Verify: InboundRouterInterruptibleTest (or its home class) gains the image case asserting the per-user-cap reject when the sender is at ceiling; and ImageCommandHandlerTest.stopCancelsARunningGeneration asserts the /stop interrupt reaches the client's cancel."
  - "Rides-the-diff spec amendment (M1-779 precedent; analysis D-1): docs/spec/security.md §Rate limiting's D35 enumeration gains /image — RULE-TEXT ONLY (no dates, ticket IDs, or report citations — §12), exact wording to the user for approval; the marked-index /image line in docs/spec/commands.md lands under the same approval. Verify: `grep -n 'image' docs/spec/security.md` shows the enumeration entry; CommandCatalogueParityTest green."
  - "Translation leg with disclosure (commands.md:619-624, security.md:2132-2148; analysis P14): a non-en scope's prompt goes through QueryAnchorTranslator (en is a strict no-op); Verify: ImageCommandHandlerTest.czechScopeTranslatesAndEchoesTheEnglishPrompt and .translatorFailureSendsTheOriginalPrompt (FAILURE-MODE: the inherited fallback ships the untranslated prompt — degraded adherence, not an error). The disclosure texts name the new leg per the Census below: switch-llm.sh's leg-count comment and SETUP_GUIDE.md's leg-by-leg list gain the /image leg; 4-llm.sh is verified to carry no leg enumeration (its two-facts text stays true) — Verify: the Census greps."
  - "Sanitized echo (commands.md:625-629; analysis P6): the echoed English prompt passes through LlmOutputSanitizer.sanitize with the ECHO FIELD as the redaction unit before interpolation — Verify: ImageCommandHandlerTest.promptContainingAPrivilegedCommandStringIsRedactedInTheEcho (FAILURE-MODE: `/image -p a poster saying /grant-admin …` renders [redacted command] and writes the LLM_OUTPUT_SANITIZED audit row)."
  - "Content-free audit (D75; analysis P4): AuditAction.IMAGE_GENERATE (one enum constant, no migration — the V5-open-ended column, AuditAction.java:4-13) records actor, scope, outcome — never the prompt, never a hash — Verify: ImageCommandHandlerTest.auditRowIsContentFree; plus a review-time grep that no log statement on any success/error/timeout path carries the prompt."
  - "Failure contract (commands.md:637-643; analysis P20): all eight enumerated modes return localized text, never silence — backend unreachable · breaker open · queue over budget · credit exhausted · cooldown not elapsed · timeout (after cancelling) · adapter cannot carry attachments · attachment exceeds the platform limit; the new bundle keys land in EVERY shipped bundle (en, cs, tr, es, ru — D43), enforced by BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle — Verify: ImageCommandHandlerTest.failureContractCoversAllEightModes plus that BundleLoaderTest method."
  - "Coarse ETA in progress + refusal messages (design addendum 2026-08-07): the GENERATING stage string and the queue-depth refusal interpolate an integer ETA computed as (queue position + 1) × the per-model steady-state constant from operator config (the key M1-798's setup step seeds from the container re-measurement; unset constant → position shown without an ETA, no lie) — Verify: ImageCommandHandlerTest.generatingStageShowsTheEtaFromQueueDepthAndConfigConstant (fixed values, no wall clock) and .queueRefusalCarriesTheBacklogEstimate."
  - "Converter guardrail (round-1 FINDING 1; design Final decision 7, addendum decision 4 with the 2026-08-10 DECIDE-BEFORE resolution): --resolution IS the output contract — the submitted graph carries per-job dims: the latent node's width/height = the requested ratio scaled to the template's baked budget (derived at load via the KSampler latent_image link), rounded /16, and the fit node swapped ImageScaleToTotalPixels -> ImageScale with the exact target W/H; no-flag jobs keep the baked graph untouched. Verify: ImageCommandHandlerTest.resolutionReachesTheSubmittedGraphAsPerJobDims (stub backend's /prompt JSON carries the per-job latent + fit dims for -r 512x512) plus ComfyUIClientTest buildGraph cases (ratio at budget /16; exact fit dims; default graph unchanged)."
  - "Round-1 FINDING 2 fix: /stop landing DURING SUBMIT (POST /prompt in flight) does NOT refund — an unreadable job state is conservatively started (design-notes refund table; D76 refunds only what is KNOWN never to have run). Verify: ImageCommandHandlerTest.stopDuringSubmitDoesNotRefund (generateThrow = InterruptedException -> stopped terminal + assertFalse(tryAcquireImageUserCredit))."
  - "Round-1 FINDING 3 fix: the per-user-cap REJECT promised by item 6 gains its image case. Verify: InboundRouterPerUserCapIT.imageRequestBeyondCapRejectedLikeAnyOtherInterruptible (hold the sender's two turns, deliver /image a cat, assert ERROR_CHAT_PER_USER_CAP — the cap fires at intake before the D73 config gate)."
  - "Round-1 FINDING 4 fix: the timeout-before-start refund arm gains its assertion. Verify: failureContractCoversAllEightModes mode 6 asserts assertTrue(tryAcquireImageUserCredit) after the timeout reply (deleting the refund call turns it red)."
  - "Round-1 FINDING 5 fix: the queue-depth-read interrupted arm writes the content-free stopped row like its sibling arms. Verify: ImageCommandHandlerTest.stopDuringQueueDepthReadWritesStoppedAuditRow (queueDepthThrow = InterruptedException -> stopped terminal AND one IMAGE_GENERATE row with outcome stopped)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandParserTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCreditGateTest.java
  modifies:
    - the InboundRouter interruptible test class (the image case joining the D35 table — authorized by acceptance item 6)
    - InboundRouterPerUserCapIT (the image cap-reject case — refine round 2, FINDING 3)
    - ComfyUIClientTest (the converter's buildGraph cases — refine round 2, FINDING 1)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Discovery
  - docs/spec/decisions.md (D73, D75, D76)
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Slow-start tier
decision_refs:
  - D73
  - D75
  - D76
---

# M1-803: /image command handler: gates, translation, echo, audit

## Context

The assembling ticket: the `/image` CommandHandler bean, its parser and
gates, the translation leg, the sanitized echo, the content-free audit row,
and the eight-mode failure contract — everything the commands.md:586-648
entry promises — plus the marked-index line, the CATALOGUE entry, the D35
enrollment, and the disclosure-text updates that make the spec's
§Secrets handling enumeration true in the operator-facing texts. The SPI,
codecs, spool/strip, and client land in M1-799..M1-802. Shared analysis:
`analysis_ref:`.

## Root cause

Feature gap — with two verified spec/consistency notes this ticket carries:
(D-1) security.md §Rate limiting's D35 enumeration (:1851-1857) does not list
/image although commands.md:610-613 commits it to the class — a rides-the-diff
amendment fixes the record; and the disclosure texts lag the §Secrets handling
enumeration (switch-llm.sh:395 says SEVEN legs; SETUP_GUIDE.md:593 lists
seven; the enumeration now has eight).

## Pitfalls

Numbered consistently with the analysis document.

- P2: the capability-flag gate is static — check before charging; a charged
  generation that can never be delivered is a D76 violation by construction.
- P4: no prompt on any log line (error/timeout arms included), content-free
  audit only, no hash.
- P6: echo sanitized at render, redaction unit = the echo field alone
  (M1-694 granularity lesson); breakLinkAdjacency applies downstream for
  free.
- P10: without the isInterruptible edit, /image runs on the transport
  thread — no per-user ceiling, no /stop, and GPU-length work blocking
  inbound dispatch (redteam finding 4).
- P11: runtime gating, never a build-time-conditional bean — the parity
  test's CDI bean graph would desync; bean + CATALOGUE + index line land
  together.
- P12: AND-gate with paired refund; the refund-iff-GPU-never-ran boundary
  exact; new RateCapBucket maps enroll in the eviction sweep with
  effective threshold ≥ refill window (M1-222 lesson).
- P13: cooldown is decision-time logic on the injected Clock, DM and group
  alike; never split write/read across clocks (§9).
- P14: the leg reuses QueryAnchorTranslator unchanged — en no-op,
  failure-fallback ships the original prompt, cache residual already
  disclosed; the disclosure texts must name the leg or the spec's
  own warning (security.md:2144-2148) bites (see Census).
- P19: probation fails closed for free — do not touch
  CommandPermissions.ALLOWED.
- P20: eight modes, localized in every shipped bundle (D43), never silent,
  never carrying the prompt.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Parser + prompt cap (rejects before any gate; resolution bounded by
     server ceiling AND the adapter ceiling).
  2. Credit/cooldown gate: two new RateCapBucket maps (+ Settings/withers/
     sweep enrollment) and the per-user cooldown, injected Clock.
  3. ImageCommandHandler: config gate → unknown-command body; flag gate
     (pre-charge); gates in spec order; dispatch through the interruptible
     path; translation leg; client call (M1-802); fetch → strip → spool →
     deliver (M1-801); sanitized echo; content-free audit; the eight-mode
     failure mapping to bundle keys (all five shipped bundles).
  4. InboundRouter.isInterruptible: admit image (one-line table edit with
     its test).
  5. HelpCommandHandler.CATALOGUE entry (HelpTier.USER) + visible() config
     filter; marked-index /image line (user-approved, with item 7).
  6. AuditAction.IMAGE_GENERATE.
  7. The security.md D35-enumeration amendment (rule-text only, user
     approval, §12) and the Census disclosure-text updates.
  8. (Refine round 2) Converter guardrail: extend the client's template
     validation with the latent node (KSampler latent_image link, baked
     numeric width/height = the sampling budget) and the fit node
     (ImageScaleToTotalPixels); for -r jobs buildGraph sets per-job latent
     dims (requested ratio at budget, /16) and swaps the fit node to
     ImageScale(width,height); the handler passes the parsed resolution
     through generate(). No-flag jobs keep the baked graph.
  9. (Refine round 2) Round-1 FINDINGS 2-5: no refund on
     interrupt-during-submit; the per-user-cap IT image case; the
     timeout-before-start refund assertion; the stopped audit row on the
     queue-depth-read interrupted arm.
- **Controls to preserve (§10):** the probation gate order (step 5 before
  dispatch) is untouched; the step-1.5 transport bucket and per-group
  command/LLM buckets still fire first; the per-user concurrency check at
  InboundRouter.java:1044-1051 fires unchanged once isInterruptible admits
  image; QueryAnchorTranslator's no-content logging posture inherited;
  the M1-794/M1-795 delivery guards unaffected (the failure replies are
  ordinary deterministic bundle bodies).
- **Pitfall→mitigation:** P2→step 3's flag-first ordering + item 5;
  P4→steps 3/6 + item 10; P6→step 3 + item 9; P10→step 4 + item 6;
  P11→steps 3/5 + item 1; P12→step 2 + items 3-4; P13→step 2; P14→steps
  3/7 + item 8 + Census; P19→no-touch, verified by existing probation tests;
  P20→step 3 + item 11.

## Definition of done

Every acceptance item green: the reproduction (config gate), parser,
gates, refund boundary, flag-first ordering, D35 enrollment + amendment,
translation leg + Census disclosures, sanitized echo, content-free audit,
the eight-mode localized failure contract, and full repo verify.

## Verification

- P2 → .falseFlagAdapterAnswersTextFallbackWithoutCharging (asserts zero
  credit draw and zero client calls).
- P4 → .auditRowIsContentFree + review grep over the diff's log statements.
- P6 → .promptContainingAPrivilegedCommandStringIsRedactedInTheEcho
  (failure-mode; asserts the audit row too).
- P10 → the isInterruptible table test + .stopCancelsARunningGeneration.
- P11 → the reproduction + CommandCatalogueParityTest (it IS the guard).
- P12 → ImageCreditGateTest's AND/refund/eviction-threshold methods.
- P13 → .cooldownAppliesInDmAndGroup under a fixed Clock.
- P14 → .czechScopeTranslatesAndEchoesTheEnglishPrompt /
  .translatorFailureSendsTheOriginalPrompt + the Census greps.
- P19 → existing probation tests stay green (no edit).
- P20 → .failureContractCoversAllEightModes + BundleLoaderTest's
  every-shipped-bundle method.
- Non-vacuity: charging before the flag check fails item 5; a refund on
  adapter-send failure fails item 4; skipping isInterruptible fails item 6;
  an unsanitized echo fails item 9; a missing bundle key fails item 11's
  BundleLoaderTest gate.

## Out-of-scope

Named in `out_of_scope`: everything M1-799..M1-802 own; the probation
ALLOWED list; retry; model flags; QueryAnchorTranslator internals. Two
pre-existing test surfaces are touched, both authorized in `acceptance`:
the isInterruptible table test (item 6) and the spec files under §12
approval (item 7). No other pre-existing test is modified.

## Census

This ticket guards two multi-site classes; every site is enumerated and
disposed below.

**Class 1 — translator-leg disclosure texts.** security.md:2144-2148 makes
the §Secrets handling enumeration the authority both disclosure surfaces are
corrected against; the /image leg (security.md:2132-2141) ships in this
diff, so every text that enumerates or counts legs must move. Re-runnable
enumeration: `grep -rn -iE 'seven|leg' prod/switch-llm.sh prod/scripts/4-llm.sh SETUP_GUIDE.md`.
Rows (verified at draft time):

- `prod/switch-llm.sh:395` — comment claims translator "carries SEVEN
  distinct legs" → FIX: becomes eight (comment accuracy; the printed
  two-facts block at :409-422 names no per-leg list and stays true).
- `SETUP_GUIDE.md:593` — "seven separate things reach it" + the
  six-plus-one bullet structure at :594-616 → FIX: gains the /image-prompt
  bullet (a /lang-gated leg, user-authored private text) and the count
  becomes eight.
- `prod/scripts/4-llm.sh:514-522` — prints the same two-facts translator
  block; verified at draft time: it contains NO leg enumeration or leg
  count (its "seven" mentions at :15/:69/:171/etc. count the seven
  generative TASKS, a different set) → DISPOSED, no text change; its
  pointer to SETUP_GUIDE.md (:522) now resolves to the corrected list.
- Any other grep hit for "leg" in those three files → reviewed at
  implementation against the same rule (enumerate-or-stay-silent); none
  known at draft time.

**Class 2 — the eight failure-contract modes × shipped bundles.** Every
mode's reply is a BundleKeys constant, and D43's gate
(BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle)
requires every constant in all five shipped bundles (en, cs, tr, es, ru).
Re-runnable enumeration: the eight modes are listed verbatim at
commands.md:640-643; each maps to one new BundleKeys constant, and
`grep -c '^image\.' infochat-provider/src/main/resources/bundles/*.properties`
returns the same count for all five files. Rows: eight modes → eight keys →
five bundles each, disposed by BundleLoaderTest going green (a missing key
in any bundle reds it).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-803-image-command-handler.md
```

## Review observations

Round-3 (verification re-review) RECOMMENDED-NEW-TICKET, driver-recorded
per review.md §5 (TOUCHED-BY-THIS-DIFF: yes, no DECIDE-BEFORE — filing is
the user's call): the "no refund once the GPU ran" boundary is untested on
the timeout//stop arms for a job the backend already started — every test
feeds JobTimeoutException/JobCancelledException with jobStarted=false
(ImageCommandHandlerTest.java:574), so mutating the handler's
`if (!e.jobStarted())` guard to an unconditional refund leaves the suite
green while violating D76. The shipped code is correct; only the
regression net has the hole. Suggested probe: generateThrow =
JobTimeoutException("timed out", true) → assertFalse
(rateCapBucket.tryAcquireImageUserCredit(userId)). Full text:
.scratch/tick-review-M1-803-r3.txt.
