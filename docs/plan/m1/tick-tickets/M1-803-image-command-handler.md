---
id: M1-803
title: "/image command handler: gates, translation, echo, audit"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
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
blocked_by: [M1-800, M1-801, M1-802]
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
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandParserTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCreditGateTest.java
  modifies:
    - the InboundRouter interruptible test class (the image case joining the D35 table — authorized by acceptance item 6)
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
