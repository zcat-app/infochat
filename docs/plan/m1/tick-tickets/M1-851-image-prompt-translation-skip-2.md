---
id: M1-851
title: "Per-model image-prompt translation skip: flag + amendment"
status: done
created: 2026-08-14
last_updated: 2026-08-15
flow: tick
reproduction: >-
  ImageCommandHandlerTest.skipFlagOffSendsTheNativePromptUntranslated —
  written and run RED at start (2026-08-15: test-compile fails, cannot
  find symbol translatePrompt, exactly the documented gap): with the flag
  off on a cs scope, the StubTranslator is NEVER invoked (lastQuery stays
  null), `client.lastPrompt` equals the native prompt 'červené kolo'
  byte-for-byte, the completed echo carries it, and
  `notifier.publishedStages()` does NOT contain ProgressStage.TRANSLATING
  (P2 stage half — a published TRANSLATING would claim a leg that never
  ran). The leg at ImageCommandHandler.java:238-244 translates
  unconditionally whenever the scope language is not en (verified
  in-tree at start; grep for 'translate-prompt' over main sources
  returns nothing).
analysis_ref: docs/plan/m1/tick-analysis/image-prompt-translation-skip.md
blocked_by: [M1-850]
files_scope:
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
  - prod/scripts/4b-image.sh
  - SETUP_GUIDE.md
  - docs/design/future/image-generation.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    QueryAnchorTranslator itself (analysis P9 — the class is shared with
    the retrieval leg; M1-803 already froze it: the leg is reused as-is.
    The skip is a caller-side branch; QueryAnchorTranslator.java is absent
    from this diff).
  - >-
    Any per-user or per-scope toggle (analysis P5 — the §D6 shape
    constraint: per-MODEL/operator, wizard-baked; no command, no
    scope_preferences column, no bundle surface for users).
  - >-
    Removing the translate recommendation for Mage-Flow or Z-Image in
    the wizard's table (refined 2026-08-15: §D6's "mandatory" becomes
    RECOMMENDED — the wizard still writes true for them, and an operator
    override to false is a contemplated, disclosed posture, never a
    violation; the original PASS-cells-only seeding rule is superseded
    by the recorded krea_bf16 cs FAIL override: user judgment,
    2026-08-15). A model M1-850 did not measure still gets translate
    recommended (default true).
  - >-
    The chat-pipeline translation switch family (M1-844..849 — different
    pipeline, different spec surface; this ticket's decisions row and
    §Secrets handling bullet are disjoint from M1-845's, and whoever lands
    second rebases).
  - >-
    Re-running the wizard on existing deployments: an install that never
    re-runs step 4b keeps the shipped default ON = today's behavior (the
    safe posture); SETUP_GUIDE gains the adoption note, nothing more.
  - >-
    The ComfyUIClient template/validation surface and the committed
    reference template (the graph is unchanged — only the string placed in
    the one placeholder field changes language).
acceptance:
  - "ImageCommandHandlerTest.skipFlagOffSendsTheNativePromptUntranslated passes — REPRODUCTION (written and run RED at start): with `translatePrompt=false` and a cs scope, the StubTranslator is NEVER invoked (lastQuery stays null), `client.lastPrompt` equals the native prompt 'červené kolo' byte-for-byte, the completed echo carries it, and `notifier.publishedStages()` does NOT contain ProgressStage.TRANSLATING (P2 stage half — a published TRANSLATING would claim a leg that never ran)."
  - "Default-ON behavior pinned (P1): the pre-existing czechScopeTranslatesAndEchoesTheEnglishPrompt and translatorFailureSendsTheOriginalPrompt stay green; the ONE authorized pre-existing-test edit is the @BeforeEach fixture gaining `handler.translatePrompt = true;` (mirroring the shipped default — the fixture's own comment at ImageCommandHandlerTest.java:133 records that manual injection misses @ConfigProperty defaults; this ticket adds the flag, so the fixture sets the field like its five sibling config fields). Plus NEW englishScopeIsANoOpWithTheFlagOff: en scope + flag off → no translator call, prompt byte-identical (the flag never affects en scopes — security.md:2231-2232's strict-no-op posture holds in both modes)."
  - "FAILURE-MODE (P2 sanitizer half, §10): skipFlagOffEchoIsSanitizedWithTheEchoFieldUnit — flag off, cs scope, `/image -p a poster saying /grant-admin now` → the echo renders `[redacted command]`, never the raw token, and exactly the LLM_OUTPUT_SANITIZED audit row lands (the sanitize call and its echo-field unit are preserved on the skip path) — and auditRowIsContentFree's posture holds: no IMAGE_GENERATE row carries the prompt on either path."
  - "The Provider stays model-agnostic (P5/P6): the handler branches ONLY on the new config key — probe: `grep -n -i 'krea\\|mage\\|zimage' infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java` returns nothing, and `git diff --stat` shows QueryAnchorTranslator.java absent (P9)."
  - "Spec amendment, rule-text only, user-approved BEFORE any code lands (P3, §12 — the M1-803 item-7 shape; the exact wording goes to the user): docs/spec/commands.md §Content's /image paragraph (:623-635) gains the per-model conditional — a non-English scope's prompt is translated first UNLESS the deployment's image model carries the operator-baked translation-skip property, in which case the native prompt reaches the backend unmodified — and the echo rule becomes the prompt ACTUALLY SUBMITTED (English when the leg ran, the native prompt when it was skipped; the E1-vs-E2 'not translated' note fork is decided with the wording, analysis option E) — Verify: `grep -n 'skip\\|actually' docs/spec/commands.md` shows the amended sentences inside the /image entry, and `git diff docs/spec/` carries no dates, ticket IDs, or report citations in spec prose (§12)."
  - "docs/spec/decisions.md gains the per-model skip row (next free D-number — D78 as of draft; M1-845 may claim it first, the grep finds whichever): the flag as a per-model operator property baked by the setup wizard with the D73 capability-gating rationale (P5, §7), the measurement gate (the wizard table seeded only from a committed per-(tier,language) PASS matrix), the D75 echo resolution (the durable record is the echoed prompt actually submitted, either language), and the D77 exposure note (a skipping model shrinks the translator-leg qualified exposure; the /image leg then never leaves the deployment) — Verify: `grep -n '^| D7[89]\\|^| D8[0-9]' docs/spec/decisions.md` shows the new row."
  - "docs/spec/security.md §Secrets handling's /image bullet (:2223-2232) gains the conditional (the leg fires for a non-en scope only when the deployment's image model does NOT carry the skip property) and the exposure-shrink sentence, per the section's own propagation rule (:2233-2239, P8) — Verify: `grep -n -A6 'carries .image. prompts' docs/spec/security.md` shows the conditional wording."
  - "Wizard baking (P5/P6, M1-798's pattern, refined 2026-08-15): prod/scripts/4b-image.sh gains a per-model recommendation constant table beside the existing MODEL_* tables (:52-84) whose rows carry the operator-facing recommendation (krea both tiers → skip; the krea_bf16 cs FAIL cell overridden by user judgment 2026-08-15; mage/zimage → translate; a model M1-850 did not measure → translate, default true the safe posture), asks translate-on/off after the model pick with the recommendation as default (bare Enter and --defaults take the recommendation non-interactively), and writes `infochat.image.translate-prompt` on the local path (beside :836-839) and the remote path AFTER clear_image_props (:888-892) — probes: `grep -n 'translate-prompt' prod/scripts/4b-image.sh` hits the table and both set_prop lines; a krea pick writes false and a mage/zimage pick writes true on --dry-run; re-running 4b re-asks (the reset path). FAILURE-MODE: a krea→mage model SWITCH rewrites the key to true — probe: after switching, `grep -c '^infochat.image.translate-prompt=true' prod/runtime/application.properties` prints 1 (a stale false from the previous krea install must not survive)."
  - "Disclosure texts and doc gates (P8/P10, refined 2026-08-15): SETUP_GUIDE.md's translator leg list (:689-693, now :719-723) gains the conditional clause and its step-4b section documents the flag, the per-model recommendation and the operator override (the value the wizard writes and where to change it), and the re-run adoption note for existing installs (an existing krea install that never re-runs 4b keeps true — it pays ~1 s of translator latency per non-en prompt and loses nothing); prod/switch-llm.sh and prod/scripts/4-llm.sh are VERIFIED to carry no per-leg list needing an edit (Census); docs/design/future/image-generation.md gains the key's gate-values table row (:222-231) and its Translation paragraph (:315-317 'one code path, no per-model special-casing') is updated to the flag's reality — Verify: the Census greps, and mvn verify is green with DocumentedConfigKeyParityTest passing (the key is real AND documented in the same diff, so no exemptions-file entry — the M1-708 rule)."
  - "Bundle discipline (P4 fork): under E1 (recommended) NO new bundle key lands and `image.reply.echo` is reused unchanged; if the user picks E2 (native + note) at wording approval, the new key lands in ALL FIVE shipped bundles (en, cs, tr, es, ru — D43) enforced by BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle — Verify: `git diff --stat infochat-provider/src/main/resources/bundles/` matches the chosen variant exactly."
  - "mvn verify from repo root is green."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  modifies:
    - >-
      ImageCommandHandlerTest — the @BeforeEach fixture gains ONE line
      (`handler.translatePrompt = true;`, authorized by acceptance item 2:
      this ticket adds the flag whose shipped default is ON, and the
      fixture sets every config field explicitly because manual injection
      misses @ConfigProperty defaults), plus the three NEW methods named in
      acceptance items 1-3 (skip-mode branch, en no-op with flag off,
      skip-mode sanitized echo). No existing assertion is weakened or
      retargeted.
  notes:
    - >-
      The new tests land in the existing ImageCommandHandlerTest (plain
      JUnit, no Quarkus boot — the class's established shape), so no new
      test file is added.
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Secrets handling
  - docs/spec/decisions.md §Decisions log
decision_refs:
  - D43
  - D73
  - D75
  - D77
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
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "11 files, +237/-58"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-851-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-15: >-
    User refine at start, after reading M1-850's matrix: the wizard skip
    table becomes a per-model RECOMMENDATION the operator owns — krea
    (both tiers) skip recommended, the krea_bf16 cs FAIL cell overridden
    by user judgment as noise; mage/zimage translate recommended; a
    model M1-850 did not measure translate recommended (default true,
    the safe posture). Echo fork decided: E1 (the native prompt actually
    submitted; image.reply.echo reused unchanged). Wizard shape decided:
    an interactive ask at model pick with the recommendation as default
    (bare Enter and --defaults take it); re-running 4b re-asks (the
    reset path). Spec wording approved with four adjustments, applied.
escalation_reason:
---

# M1-851: Per-model image-prompt translation skip: flag + amendment

## Context

The 2026-08-14 user direction (future-features.md §D6:455-489) introduces a
per-model flag on the /image prompt-translation leg: ON (default, today's
behavior — translate first, mandatory for Mage-Flow/Z-Image) and OFF
(per-model, Krea only) so the native-language prompt reaches the text
encoder unmodified, skipping one translator round-trip on the
user-perceived path and shrinking the D77 qualified exposure. The spec
promises the leg unconditionally (commands.md:623-628;
security.md:2223-2232) and pins the durable record to the echoed ENGLISH
prompt (D75, decisions.md:94), so the amendment lands FIRST inside this
ticket's diff (analysis option F — the M1-803 item-7 shape, user direction
pre-recorded, wording approved per §12). M1-850's measurement record
supplies the per-(tier, language) PASS matrix the wizard's skip table
cites. Shared analysis: `analysis_ref:`.

## Root cause

Feature gap with a spec promise in the way: no flag exists anywhere
(grep-verified), the leg at ImageCommandHandler.java:238-244 translates
unconditionally for non-en scopes, and three spec surfaces assert that
unconditionality. The mechanism is a caller-side branch on an operator
config key plus wizard baking; the gates are the amendment and the
measurement-seeded table.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4, P5,
P6 (wizard half), P8, P9, P10, P11, P12.

- P1: default-flip under no-CDI test construction — the fixture misses
  @ConfigProperty defaults (its own comment, ImageCommandHandlerTest.java:133);
  without the authorized fixture line the new boolean reads false and every
  pre-existing translation test silently exercises flag-OFF behavior.
- P2: §10 carry-over on the rerouted leg — the echo sanitizer (same call,
  echo-field unit, LLM_OUTPUT_SANITIZED row) runs on the NATIVE prompt in
  skip mode; the content-free IMAGE_GENERATE rows are untouched; TRANSLATING
  is published iff a leg actually runs.
- P3: spec-first ordering — amendment before code within the diff, rule-text
  only, user-approved (§12).
- P4: the D75 echo fork — the amendment names the echo's skip-mode content
  (recommended E1: the native prompt actually submitted; `image.reply.echo`'s
  "Prompt used: {0}" stays literally true); an echo suppressed in skip mode
  deletes the durable record and the failure explainer — rejected.
- P5: shape and §7 — per-model recommendation, operator-owned, never
  per-user (refined 2026-08-15: the wizard recommends, the operator
  decides); the D73 capability-gating rationale is recorded in the
  decisions row.
- P6 (wizard half, refined 2026-08-15): the wizard's per-model table is a
  RECOMMENDATION the operator may override at the prompt and afterward —
  krea (both tiers) → skip recommended (the krea_bf16 cs FAIL cell is
  overridden by user judgment: noise; krea_small's fp8-scaled encoder
  remains a distinct measured artifact), mage/zimage → translate
  recommended, and a model M1-850 did not measure → translate recommended
  (default true, the safe posture). The override residual is stated in
  the amendment (bounded: quality-only, echo truthful, exposure only
  shrinks; D73 operator model-choice authority).
- P8: disclosure texts track the conditional leg — SETUP_GUIDE.md:689-693
  states it unconditionally today; security.md:2233-2239's propagation rule
  makes the enumeration the authority (see Census).
- P9: never edit QueryAnchorTranslator — shared with the retrieval leg.
- P10: parity/doc gates in the same diff — the design-doc gate-values row
  lands with the code (DocumentedConfigKeyParityTest; no exemption entry),
  and the stale "pipeline stays uniform" paragraph
  (image-generation.md:315-317) is updated in the same diff.
- P11: cache-residual honesty — today a non-en /image prompt sits ≤24h in
  the scope-partitioned QueryTranslationCache (QueryAnchorTranslator.java:306-307);
  the skip shrinks that residual; the upside lives in the decision row's
  motivation, never in rule text (§12).
- P12: end-state calibration — the new tests pin the END state (flag
  exists, default ON); the pair is complete, no fixture pins an
  intermediate state.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Read M1-850's record: the per-(tier, language) PASS matrix the wizard
     table cites (blocked_by). If Krea failed wholesale, STOP — abandon
     this ticket and tell the user with the record in hand (a skip flag
     with no qualifying model is a config surface without purpose).
  2. Draft and land the amendment FIRST (P3): commands.md §Content /image
     paragraph (conditional leg + the echo rule), decisions.md row (next
     free D-number), security.md §Secrets handling /image bullet. Rule-text
     only; the exact wording and the E1/E2 echo fork go to the user (§12).
  3. Code (P1, P2, P9): ImageCommandHandler gains
     `@ConfigProperty(name = "infochat.image.translate-prompt",
     defaultValue = "true") boolean translatePrompt` (final key name
     approved with the wording); the leg condition becomes
     `if (translatePrompt && !language.equalsIgnoreCase("en"))`; rename the
     `englishPrompt` local to a truthful name (it may hold the native
     prompt — §11's stale-claim rule applied to identifiers) and update its
     two-line comment (:241-242); the TRANSLATING publish stays inside the
     leg branch; the echo path is byte-identical.
  4. Tests: the RED reproduction first (workflow §0), then the en-no-op and
     skip-mode-sanitized-echo methods, plus the authorized fixture line.
   5. Wizard (P5, P6, refined 2026-08-15): the per-model recommendation
      table (seeded from step 1's matrix, krea_bf16 cs FAIL overridden by
      the recorded user judgment), the translate-on/off ask after the
      model pick (recommendation as default; --defaults takes it
      non-interactively), and the set_prop writes on the local and remote
      paths, after the remote path's clear_image_props; the switch path
      re-asks and rewrites the key per the new pick.
  6. Docs (P8, P10): SETUP_GUIDE leg-list clause + step-4b flag section;
     the design-doc gate-values row and the Translation paragraph update;
     verify switch-llm.sh / 4-llm.sh need no edit (Census).
- **Controls to preserve (§10):** enumerated in the analysis §Controls —
  the echo sanitize call + unit + audit row (P2), the content-free
  IMAGE_GENERATE rows, the D76 refund boundary (the branch sits after
  charging, before submit; no charge/refund arm changes), progress-surface
  truthfulness, the graph one-string-field contract (ComfyUIClient.java:48,
  :593-599+), every gate's ordering, and the shared translator untouched.
- **Pitfall→mitigation:** P1→step 4 + item 2; P2→step 3 + items 1/3;
  P3→step 2 + item 5; P4→item 5's echo sentence + item 10; P5→item 6's
  rationale + item 4's model-agnostic grep; P6→step 5 + item 8; P8→step 6 +
  Census; P9→step 3's boundary + item 4's diff-stat probe; P10→step 6 +
  item 9; P11→item 6's motivation (register, not rule text); P12→items 1-3
  pin the end state.

## Definition of done

The reproduction and its two sibling tests green with the one authorized
fixture edit; the three-file spec amendment landed rule-text-only with user
approval recorded (echo fork decided); the decisions row carries the
capability-gating rationale, the measurement gate, the echo resolution, and
the D77 exposure note; the wizard bakes the key per model from M1-850's
matrix including the switch-rewrite; the disclosure texts and design doc
agree with the new behavior; DocumentedConfigKeyParityTest and the full
repo verify green.

## Verification

- P1 → item 2: the pre-existing translation tests green with the authorized
  fixture line; deleting the line reds them (the fixture proves the
  shipped default).
- P2 → item 1 (no TRANSLATING in skip mode) and item 3 (FAILURE-MODE: a
  privileged-command string in a skipped native prompt is redacted in the
  echo, with the LLM_OUTPUT_SANITIZED row; the IMAGE_GENERATE row stays
  content-free).
- P3 → item 5: implementation order + the §12 approval record; the
  reviewer's SPEC-TRUTHNESS check reads post-diff spec against post-diff
  code.
- P4 → item 5's echo sentence + item 1's echo assertion (the native prompt
  IS echoed) + item 10's bundle fork check.
- P5/P6 → item 4's model-agnostic grep, item 6's decisions-row rationale,
  item 8's wizard probes incl. the switch-rewrite FAILURE-MODE.
- P8 → the Census greps.
- P9 → item 4's `git diff --stat` probe (QueryAnchorTranslator.java absent).
- P10 → item 9: DocumentedConfigKeyParityTest green with the design-doc row
  present; the "stays uniform" paragraph no longer contradicts the flag.
- P11 → item 6: the motivation names the residual shrink; `git diff
  docs/spec/` carries no report citations (§12).
- P12 → items 1-3 assert the end state directly.
- Non-vacuity: a branch that skips the leg but still publishes TRANSLATING
  fails item 1; an unsanitized skip-mode echo fails item 3; a wizard that
  leaves a stale false on a krea→mage switch fails item 8's failure-mode
  probe; a missing design-doc row reds DocumentedConfigKeyParityTest.

## Out-of-scope

Named in `out_of_scope`: QueryAnchorTranslator internals; any per-user or
per-scope toggle; Mage-Flow/Z-Image skip rows and any tier M1-850 did not
measure; the chat-pipeline switch family (M1-844..849); wizard re-runs on
existing deployments (default ON is the safe posture); the ComfyUIClient
template surface. One pre-existing test file is modified — the
ImageCommandHandlerTest fixture line, authorized by acceptance item 2; no
existing assertion is weakened or retargeted. If the user picks the E2 echo
variant at wording approval, the bundle additions it implies are IN scope
and gated by item 10.

## Census

This ticket guards one multi-site class: **every text that states the
/image translator leg unconditionally.** security.md:2233-2239 makes the
§Secrets handling enumeration the authority the disclosure surfaces are
corrected against; the leg becomes conditional in this diff, so every site
that enumerates or paraphrases it must move or be verified true as-is.
Re-runnable enumeration:
`grep -rn -i 'image' SETUP_GUIDE.md prod/switch-llm.sh prod/scripts/4-llm.sh | grep -i -i 'prompt\|translat\|leg'`.
Rows (verified at draft time):

- `docs/spec/security.md:2223-2232` — the /image bullet states the leg
  unconditionally → FIX (acceptance item 7): gains the conditional and the
  exposure-shrink sentence.
- `SETUP_GUIDE.md:689-693` — "if a chat or group's `/lang` is not English,
  the prompt you type for `/image` is translated to English before the
  image backend runs" → FIX (item 9): gains the conditional clause ("unless
  the deployment's image model carries the translation-skip property").
- `prod/switch-llm.sh:395-422` — verified at draft time: the comment counts
  EIGHT legs and the printed two-facts block names no per-leg list; the
  leg still EXISTS (conditionally), so both stay true → DISPOSED, no text
  change; re-verify at implementation.
- `prod/scripts/4-llm.sh` — M1-803's census verified it carries no leg
  enumeration (its "seven" mentions count generative tasks) → DISPOSED, no
  text change; re-verify at implementation.
- `docs/design/future/image-generation.md:315-317` — "The pipeline stays
  uniform … one code path, no per-model special-casing" goes stale → FIX
  (item 9): records the per-model flag and its wizard-baked default.
- Any other grep hit from the enumeration command → reviewed at
  implementation against the same rule (state-conditionally-or-stay-silent);
  none known at draft time.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-851-image-prompt-translation-skip-2.md
```
