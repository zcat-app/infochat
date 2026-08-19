---
id: M1-885
title: "Land the decisive reply-mode switch (registry removal)"
status: pending
created: 2026-08-19
last_updated: 2026-08-19
flow: tick
reproduction: >-
  ChatAgentReplyModeTest#configuredNativeModeIsDecisiveForAnyModelAndLanguage
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/reply-mode-decisive-switch.md). On main the
  registry-gated resolver resolves TRANSLATE for every (model, language)
  pair the registry does not clear, so each NATIVE assertion fails. The
  wrong behavior was live-reproduced on the isolated test stack
  2026-08-18: the gate compared the short model ID `gemma-4-26b-a4b`
  against the configured GGUF filename
  `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf`, never matched, and logged fallback
  to translate for English and Czech while `/reply-mode` reported inherited
  native (.scratch/LIVE-E2E-REGRESSION-HANDOFF-2026-08-18.md §Source change
  made + §Live test status). The test exists in the working tree and passes
  there (7/7, handoff §Verification already done).
analysis_ref: docs/plan/m1/tick-analysis/reply-mode-decisive-switch.md
blocked_by: [M1-886]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatReplyModeResolver.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatReplyMode.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ReplyModeCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ReplyModeCommandHandlerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ReplyModeDispatchHopIT.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY docs/spec/** edit — M1-886 owns the four spec amendments and lands
    FIRST (analysis P1); this ticket's spec_refs cite the sections AS
    AMENDED by M1-886. Committing the working tree piecemeal before M1-886
    merges is the forbidden inversion.
  - >-
    FLIPPING the shipped default (analysis P10):
    `infochat.chat.reply-mode=translate` in application.properties stays
    byte-untouched; the test stack's `native` setting is runtime config,
    not repo state.
  - >-
    THE WIZARD / prod scripts (analysis P7) — no script reads or writes the
    key today; a 4-llm.sh recommendation ask, if the user wants one, is a
    follow-up ticket out of M1-886's wording review, not this diff.
  - >-
    ANY new eligibility surface (the retired family P8): no operator list,
    no router `languages`-key consult, no model-name or GGUF-filename
    parsing anywhere on the resolution path — the defect's own mechanism.
  - >-
    The D58 query-anchoring leg, the streaming/notifier path, the
    measurement record docs/measurement/direct-chat-e2e.md (frozen
    history), and the M1-848/M1-845 ticket files (frozen records that
    document the gate they built; this ticket's D79-rewrite linkage lives
    in the commit message, not in edited history).
acceptance:
  - "REPRODUCTION passes — ChatAgentReplyModeTest.configuredNativeModeIsDecisiveForAnyModelAndLanguage: configured native resolves NATIVE for any model and language (cs/es/ru asserted), a native override beats a translate deployment default, and an unset scope inherits the translate default. On main this test FAILS (the gate resolves TRANSLATE everywhere); it is the decisive-switch pin (analysis P9 — discriminating: the gate's resolve('native','cs',…) returned TRANSLATE)."
  - "Every reply-mode test passes — the reproduction's six siblings ChatAgentReplyModeTest.aNativeScopeSkipsTheDisplayLegAndPersistsTheTurnRaw, ChatAgentReplyModeTest.aTranslateScopeKeepsTodaysBehaviourExactly, ChatAgentReplyModeTest.queryAnchoringStillRunsInNativeMode, ChatAgentReplyModeTest.nativeTurnAppendsHelpBlockAndProvenanceLikeTranslate, ChatAgentReplyModeTest.anEmptiedNativeReplyDegradesLikeTranslate and ChatAgentReplyModeTest.aNativeWindowCompressesToAnEnglishCheckpoint (7/7 with the reproduction: native skips the display leg and persists the turn raw; translate is byte-identical to today; query anchoring runs in native mode; the help-block/provenance accretions are mode-independent; the emptied-reply degrade is identical; the window compresses to an English checkpoint), and the ITs 8/8 — ReplyModeCommandHandlerIT.translateWriteUpsertsAndConfirms, ReplyModeCommandHandlerIT.nativeWriteIsStoredAndConfirmedActive, ReplyModeCommandHandlerIT.bareInvocationReportsInheritedDefaultWhenUnset, ReplyModeCommandHandlerIT.bareInvocationReportsStoredNative, ReplyModeCommandHandlerIT.unsupportedValueListsSupportedValuesAndWritesNothing, ReplyModeCommandHandlerIT.writesZeroRowsToAuditLog, ReplyModeCommandHandlerIT.groupScopeWithoutAdminActorIsRejected and ReplyModeDispatchHopIT.aConfiguredNativeScopeSkipsTheDisplayLegAcrossTheHop (upsert+confirm, stored-native and inherited-default status reads, unsupported-value error, zero audit rows, group-gate rejection, and the intake-resolved mode surviving the M1-634 worker hop — M1-848 round-1 item 1's mechanism, carried)."
  - "FAILURE-MODE: the gate is gone, not dormant — grep -rn 'ChatReplyModeRegistry' over infochat-provider main+test sources returns nothing (the class, its M1-848-item-9 registry-content test, and its fallback log line are deleted); grep -ri 'inactive\\|uncleared' infochat-provider/src/main/resources/bundles/ returns nothing (the stored-but-inactive keys deleted from ALL FIVE bundles); BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle (BundleLoaderTest.java:82) passes — the D43 completeness gate proves the deletion landed in all five bundles and no BundleKeys constant dangles."
  - "§8 retirement authorization is explicit (analysis P5): this ticket body and the commit message state that anUnclearedPairResolvesTranslateEvenWithTheOverrideSet (including the M1-848-round-1-item-2 log-emission assertion) and the registry-content test are deleted because the D79 rewrite (M1-886) removes the gate they pinned — a deliberate spec change, not a test weakened to match the code. Verify: the named tests are absent and the authorization text is present in both places."
  - "The resolver carries no dead gate shape (analysis P3, §1 orphan cleanup): ChatReplyModeResolver.resolve shrinks to the decisive inputs (scope override → deployment default), the one call site (InboundRouter.java:1951, inside the guarded resolveReplyMode at :1942-1952 — the hand-wired-test guard at :1947 stays) and the javadoc are updated, and grep -n 'scopeLanguage\\|scopeKind' ChatReplyModeResolver.java returns nothing below the class javadoc. Verify: compile plus the reproduction test green with the shrunken signature."
  - "The /help usage text tells the decisive truth in ALL FIVE bundles (analysis P2): help.cmd.reply-mode.usage loses the 'takes effect only when the chat model clears that language' clause (en.properties:100, cs:102, es:118, ru:124, tr:116) in favor of the M1-886-approved wording's behavior — native takes effect when set. Verify: grep -n 'clears that language' bundles/*.properties returns nothing, BundleLoaderTest:82 green, and LangCommandIT.java:132 / AdapterRouterIT.java:194 pass UNTOUCHED (they pin only the gate-free HELP_CMD_REPLY_MODE_SHORT one-liner)."
  - "Stale gate citations are reworded (analysis P4, §11 — the Census below enumerates them): ReplyModeCommandHandlerIT.java:96 ('activates if the pair clears later'), ReplyModeDispatchHopIT.java:126 ('stored either way'), and LlmRouter.java:133 ('(the D79 registry posture)') each state the gate-free truth. Verify: the Census grep returns nothing."
  - "Zero drift (analysis P10): git diff infochat-provider/src/main/resources/application.properties is empty and no new config key, migration, or audit action is introduced. Verify: the diff stat."
  - "mvn verify from repo root is green (engineering-rules §5; risk: high → commit-time verify re-run). The 2026-08-19 campaign log (BUILD SUCCESS, 1,932 provider tests, 376 failsafe, 0 failures) is the pre-existing evidence for the working-tree state; the commit-time run is the gate of record."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java#configuredNativeModeIsDecisiveForAnyModelAndLanguage
  preserves:
    - all tests currently green on main
  modifies:
    - >-
      ChatAgentReplyModeTest — the gate-era failure-mode test
      anUnclearedPairResolvesTranslateEvenWithTheOverrideSet (with its
      log-emission assertion) is DELETED and the registry-content test file
      is DELETED, both superseded by
      configuredNativeModeIsDecisiveForAnyModelAndLanguage; authorized by
      acceptance item 4 (the D79 rewrite in M1-886 removes the gate they
      pinned — a deliberate spec change, per §8's deletion-explanation
      rule).
    - >-
      ReplyModeCommandHandlerIT / ReplyModeDispatchHopIT — the gate-era
      comment clauses at :96 / :126 reworded to the decisive truth
      (authorized by acceptance item 7); the native-write confirmation
      asserts the decisive configured mode. No assertion weakened or
      retargeted onto a stub.
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D43
  - D58
  - D78
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-885: Land the decisive reply-mode switch (registry removal)

## Context

The ChatReplyModeRegistry gate made the operator's configured native posture
silently inert on 100% of turns — a model-name string match that could never
match, failing closed toward translate with no signal, while `/reply-mode`
reported the configured posture (root cause and live evidence in the
analysis, `analysis_ref:`). M1-886 re-founds D79 on the operator-owned
decisive switch (the D78 shape: the operator owns the value; the measurement
record is advice, never a gate). This ticket lands the corresponding code:
the already-written, already-green working-tree change (configured mode
decisive; registry, gate messages/tests, and stored-but-inactive bundle keys
removed) plus the small cleanups the analysis surfaces (P2 help text, P3
signature, P4 stale comments).

## Root cause

Proven via the campaign record (the pre-fix code is deleted): the registry
keyed clearance on the deployment's short model ID while configuration
carried the GGUF filename; the comparison never matched and the fallback was
silent. Design-level: an indirection between an explicit operator choice and
behavior that added no safety the operator did not already own — the same
model-name-parsing shape D78 rejected for /image (decisions.md:97
motivation). The fix removes the indirection; it does not repair it.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4, P5,
P9, P10. The same analysis's spec-amendment pitfalls are M1-886's own and
are named in its Pitfalls section, not here.

- P1: spec-first inversion — this ticket is `blocked_by: [M1-886]`; do not
  commit the working tree piecemeal, and do not "catch the spec up" in this
  diff.
- P2: the stale `help.cmd.reply-mode.usage` clearance clause survives in all
  five bundles — user-facing text that is false the moment this lands;
  reword in all five in this diff, following M1-886's approved wording
  (BundleLoaderTest.java:82 is the completeness gate; the SHORT text pinned
  by LangCommandIT/AdapterRouterIT is gate-free and untouched).
- P3: dead gate shape in the resolver signature — `resolve()` still takes
  the removed lookup's `scopeLanguage`/`scopeKind`/`scopeId` inputs; §1
  makes their cleanup this diff's job (orphans your change created). Keep
  the InboundRouter guard seam (:1947) as-is.
- P4: stale gate citations in comments/tests (§11) — the Census below is the
  enumeration; each row states the gate-free truth.
- P5: §10 on the rerouted path — retire explicitly (the fallback log + its
  assertion, the registry-content test, the gate keys: §8 authorization in
  body + commit message) and carry exactly (the ChatAgent
  sanitize→strip→refusal→display-leg→help-block→degrade→provenance chain,
  CHAT_MODE and LLM_OUTPUT_SANITIZED audit rows, the deferred-commit gate,
  the D58 leg, D43 two-path, the command's zero-audit/group-gate/
  unsupported-value contract, the hop capture/re-seed, the live-text
  sanitizer regime on the widened native eligibility — `ChatLiveTextStreamer
  .eligible` consumes the mode, never the registry, and needs no edit).
- P9: fixture end-state calibration — the new pin discriminates (NATIVE
  exactly where the gate returned TRANSLATE); the hop IT asserts the
  no-display-leg body. Mutation check: re-adding a clearance call or
  dropping the override branch reds them.
- P10: zero drift — application.properties untouched; no default flip, no
  new key, no "while we're here" native push.

## Approach

- **Files to touch:** `files_scope`. The bulk exists in the working tree;
  the cleanups are P2/P3/P4.
- **Steps, in order:**
  1. Confirm M1-886 merged (blocked_by) and re-read its approved wording —
     the bundle help text (step 4) must agree with it.
  2. Run the reproduction RED against main-at-branch-point (workflow §0),
     then apply the working-tree change: the decisive resolver, the registry
     + registry-test + gate-key deletions, the hop wiring, the command
     handler, the three test files.
  3. P3: shrink `ChatReplyModeResolver.resolve` to the decisive inputs;
     update `InboundRouter.java:1951` and the javadoc.
  4. P2: reword `help.cmd.reply-mode.usage` in all five bundles to the
     decisive behavior.
  5. P4: reword the three Census-row comments.
  6. Full `mvn verify` from the repo root (risk: high → the commit-time
     re-run is the gate of record).
- **Controls to preserve (§10):** enumerated in P5 and acceptance item 2 —
  the change deletes exactly the gate and its dedicated surface; every
  carried control has a named green test. The retired controls are retired
  by name (acceptance item 4), never silently.
- **Pitfall→mitigation:** P1→step 1; P2→step 4; P3→step 3; P4→step 5;
  P5→step 2 + acceptance items 2/4; P9→acceptance items 1/2; P10→acceptance
  item 8.

## Definition of done

The reproduction and the full reply-mode surface (7/7 + 8/8) are green; the
gate is verifiably gone (greps, bundle completeness gate); the §8 retirement
authorization is in body and commit message; the resolver signature, the
five-bundle help text, and the three stale comments tell the gate-free
truth; application.properties is untouched; the diff stays inside
`files_scope`; mvn verify is green from the repo root.

## Verification

- P1 → `blocked_by` + acceptance item 8's diff confinement.
- P2 → acceptance item 6's greps + BundleLoaderTest:82 + untouched
  LangCommandIT/AdapterRouterIT.
- P3 → acceptance item 5's grep + compile + the reproduction test.
- P4 → acceptance item 7's Census grep.
- P5 → acceptance items 2 (carried controls) and 4 (named retirements).
- P9 → acceptance item 1 + the hop IT — both fail against any
  gate-restoring mutation.
- P10 → acceptance item 8.
- failure mode → acceptance item 3 is the hostile pin (the gate cannot be
  left dormant); items 1 and the ITs' unsupported-value / group-gate /
  zero-audit / emptied-reply cases feed hostile inputs and assert the
  protected behaviors.
- acceptance item 9 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the spec amendments (M1-886, landed first), the
shipped default and any config/migration surface, the wizard/scripts, any
new eligibility list or model-name parsing (the defect's own mechanism — an
operator who sets native has decided), the D58 leg and the streaming path,
and the frozen measurement record and family ticket files. Two pre-existing
test files have comment-only edits and one test file loses the two gate-era
tests — all authorized in `test_plan.modifies` + acceptance item 4; no
assertion is weakened or retargeted.

## Census

This ticket removes one multi-site class: **every citation of the removed
gate** (the registry, pair-clearing, stored-but-inactive) across code,
bundles, tests, and comments. Re-runnable enumeration:
`grep -rn -i 'ChatReplyModeRegistry\|bar-clearing registry\|pair clears\|clears that language\|stored either way\|stored but inactive\|uncleared' infochat-provider/src infochat-llm-adapter/src`.
Rows (verified at draft time):

- `ChatReplyModeResolver.java:32-45` — dead gate-input parameters + javadoc
  → FIX (acceptance item 5).
- `bundles/{en,cs,es,ru,tr}.properties` `help.cmd.reply-mode.usage`
  (en:100, cs:102, es:118, ru:124, tr:116) — stale clearance clause → FIX
  (acceptance item 6).
- `ReplyModeCommandHandlerIT.java:96` — "activates if the pair clears
  later" → FIX (acceptance item 7).
- `ReplyModeDispatchHopIT.java:126` — "(stored either way)" → FIX
  (acceptance item 7).
- `LlmRouter.java:130-137` — javadoc cites "(the D79 registry posture)" for
  the unrelated NATIVE_TOOL_TRANSPORT constant → FIX the pointer only
  (acceptance item 7); the constant itself is a different, legitimate gate
  and stays.
- `docs/spec/**` four sites — M1-886's, verified absent from this diff.
- `docs/measurement/direct-chat-e2e.md:13-14` and the M1-845/846/848 ticket
  files — frozen historical records → DISPOSED, no edit.
- Any further grep hit at implementation → disposed by the same rule
  (reword to the gate-free truth, or cite why it is history).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-885-reply-mode-decisive-switch.md
```
