---
id: M1-848
title: "Wire the translate/native switch into the chat reply path"
status: done
created: 2026-08-14
last_updated: 2026-08-16
flow: tick
reproduction: >-
  ChatAgentReplyModeTest#aNativeScopeSkipsTheDisplayLegAndPersistsTheTurnRaw
  (written and run RED at start 2026-08-16; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/streaming-translation-switch.md). Probe:
  grep -n 'REPLY_LANGUAGE_DIRECTIVE' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  shows the unconditional English pin (:221-225, applied at :551) and
  grep -n 'translationPipeline.run' shows the unconditional non-en display
  leg (:582-593) — observed: no pipeline-mode concept exists, so a
  native-mode scope today would have its scope-language reply declared
  English to the translator (the M1-778 defect class), and the
  window-raw persist + English-checkpoint canonicity shape D79 commits
  to (security.md §Secrets handling) does not exist.
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: [M1-845]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/
  - infochat-core/src/main/resources/db/migration/
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/resources/bundles/
  - docs/spec/commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    THE STREAMING PATH — M1-849 owns the notifier/SPI wiring. This ticket
    changes WHICH language the reply is generated in and how it persists;
    delivery stays the M1-607 stage-label shape in both modes.
  - >-
    THE QUERY-ANCHORING LEG (P9) — SemanticSearchTool /
    QueryAnchorTranslator / the D58 X→EN retrieval translation are
    retrieval-side and run in both modes; touching them breaks non-en
    retrieval (the embedding DB is English).
  - >-
    OTHER DISPLAY LEGS (P19) — digest prose, /summary, /saved, headline
    translation, and the summarizer language-aware shortcut stay
    unconditional; the mode governs the chat reply only.
  - >-
    THE MODEL/PROVIDER CONFIG SURFACE — no new provider, no router
    priority change, and NO operator-widenable eligibility list (P8): the
    bar-clearing registry is a code constant seeded from the record's
    restated matrix (M1-858), exactly the LanguageRegistry.java posture.
  - >-
    MULTILINGUAL chat_memory — memory stays English-canonical via D79's
    window-raw persist + English-checkpoint resolution (neither analysis
    option F1 back-translate nor F2 multilingual); the user rejected both
    at the M1-845 amendment — do not draft around it.
  - >-
    DISCLOSURE TEXTS (P17 void) — prod/switch-llm.sh and SETUP_GUIDE.md
    stay byte-unchanged: D79 adds NO translator leg, so there is no new
    leg to disclose (the M1-845 judgement, its acceptance item and
    clarity_check).
acceptance:
  - "ChatAgentReplyModeTest.aNativeScopeSkipsTheDisplayLegAndPersistsTheTurnRaw (the reproduction, written and run RED at start) passes — a native-eligible scope (registry-cleared (model, language) pair + the override or deployment default set to native) generates under the declared-language directive, skips TranslationPipeline, delivers the sanitized reply in the scope language, and persists the assistant turn RAW (scope language) to the session window — the window-raw half of D79's canonicity resolution, symmetric with the user turns that already persist raw."
  - "Every pre-existing ChatAgentTest / InboundRouterChatModeIT / InboundRouterChatDeliveryOrderingIT case passes UNCHANGED — the default (translate, no override) is byte-identical to today's behavior: English directive, display leg runs, memory persists the English `approved` text (§8: this ticket authorizes no pre-existing test modification; the end-state pin is that the translate path is untouched, P15)."
  - "ChatAgentReplyModeTest.anUnclearedPairResolvesTranslateEvenWithTheOverrideSet passes — FAILURE-MODE (P7/P8): a scope whose resolved chat model is NOT in the bar-clearing registry resolves translate with a log line even when its override or the deployment default says native; the wrong-language-with-no-note delivery the registry exists to prevent never ships by configuration. The override is stored either way and activates without a further command once the pair clears (commands.md §Conversation control)."
  - "ChatAgentReplyModeTest.aNativeWindowCompressesToAnEnglishCheckpoint passes — D79 canonicity's checkpoint half: the compressor writes the chat_memory checkpoint in English in BOTH modes; COMPRESS_SYSTEM_PROMPT declares the English output (it carries no language pin today — CompressCommandHandler.java:78-83 — and a native window's scope-language assistant turns would otherwise drift the summary; the pin lands with this ticket's code per the M1-845 amendment)."
  - "ChatAgentReplyModeTest.queryAnchoringStillRunsInNativeMode passes — a non-en native scope still issues the D58 X→EN translator call for retrieval (P9; the call is pinned on the pre-fetch path)."
  - "The deterministic accretions are mode-independent — asserted by ChatAgentReplyModeTest cases that pass: a native turn appends the bundle-localized help block and the provenance notice exactly as translate does (D43 two-path rule), and the emptied-reply degrade (llm.md §Failure handling) fires identically for a step-3c match and for a markers-only reply."
  - "The /reply-mode command lands its full surface per commands.md §Conversation control and its handler test passes: the commands.md index entry, the HelpCommandHandler.CATALOGUE entry, and the tier model together (CommandCatalogueParityTest green) — DM: own scope; group: group admin OR bot admin (the code's actual gate shape, verified at M1-845 refine); probation-allowed, zero audit rows; the stored-either-way override whose uncleared-pair confirmation names the registry requirement and that the setting takes no effect yet; the bare-invocation status read naming an uncleared native setting as stored but inactive; bundle keys in all five bundles (the D43 completeness gate); confirmations and errors are D43 localization-bundle strings; and the unsupported-value error lists translate and native (P13)."
  - "The scope_preferences migration (V81, infochat-core — no sibling worktree holds V81+) adds the reply_mode column (nullable, CHECK IN ('translate','native'), NULL = inherit the deployment default), the deployment-default key infochat.chat.reply-mode ships its committed default (translate) in application.properties, and DocumentedConfigKeyParityTest passes with the M1-845 exemption entry removed (the code now builds the documented key) (P13)."
  - "The bar-clearing registry is a code constant seeded with exactly the (model, language) matrix the record's restated end-state produces (docs/measurement/direct-chat-e2e.md §Bar-clearing matrix — the M1-858 restatement: gemma × cs/ru/tr clear, en/es FAIL), its comment cites D79 (§11 pointer discipline), and the registry-content test asserts the matrix content against the record. The A/B verdict the record names also settles the native-mode prompt's context arm: retrieved context stays English, untranslated (D79)."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
    - the /reply-mode command's handler test (LangCommandHandler-shaped)
    - the registry-content test
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/commands.md §Chat mode
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D29
  - D43
  - D58
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
reviews:
  - round: 1
    date: 2026-08-16
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "26 files, +1499/-119"
    rework_items: 3
    verdict_file: .scratch/tick-review-M1-848-r1.txt
  - round: 2
    date: 2026-08-16
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "26 files, +1748/-115 (round-2 fix hunks: 7 ticket files; rework items 1-3 all SATISFIED)"
    fix_items: 1
    fix_probes: "grep 'Before the re-seed landed' ReplyModeDispatchHopIT.java -> no matches; ./mvnw -B -pl infochat-provider -am test-compile -> BUILD SUCCESS (2026-08-16 23:29, log .scratch/tick-fixes-M1-848-test-compile.log)"
    verdict_file: .scratch/tick-review-M1-848-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: "2026-08-16 start pass — tick-lint 0 findings/0 BLOCKERs (after copying the gitignored tick-analysis/streaming-translation-switch.md into this worktree, the M1-846/847 pattern); branch fast-forwarded to main 02ca356c before the check. Self-check FALSIFIED the draft's premise: the ticket (filed 2026-08-14) encoded the analysis's RECOMMENDED option F1 (persist-time back-translate, pivot/direct names), but the M1-845 amendment as committed (678c6253, user §12-approved) settled the opposite — D79: translate|native names (pivot/direct explicitly rejected in the M1-845 commit), canonicity window-raw + checkpoint-English, NO back-translate leg, P17 void, disclosure texts byte-unchanged. Hurdle reported; user chose refine 2026-08-16. Refine applied: translate/native naming throughout; reproduction renamed (persists the turn RAW, not English); back-translate acceptance items replaced by the window-raw persist (item 1) + the compressor English-checkpoint pin (item 4 — COMPRESS_SYSTEM_PROMPT carries no language pin today, CompressCommandHandler.java:78-83 verified); disclosure item dropped (P17 void per M1-845); command gate stated as group admin OR bot admin (commands.md:1161-1179); default translate; migration path corrected to infochat-core (the provider path in the draft does not exist; V81 free — no sibling worktree holds V81+); D79 added to decision_refs. Remaining citations re-verified against 02ca356c (line drift only, semantics hold): ChatAgent.java English pin :221-225 applied :551, display leg :582-593, window persist :654-656; InboundRouter.java runPostDeliveryCommit :1401-1424 (provider/messaging/, not provider/router/); LanguageRegistry.java code-constant posture (provider/bundle/); security.md both-modes canonicity :2252-2258; exemptions ledger infochat.chat.reply-mode entry cites M1-848; docs/measurement/direct-chat-e2e.md §Bar-clearing matrix present. blocked_by M1-845 added NO tests (spec-only) so the preserves-trace is vacuous; replaces: empty and no worktree holds a superseded implementation of this surface."
escalation_reason:
---

# M1-848: Wire the translate/native switch into the chat reply path

## Context

M1-845's amendment (D79) authorizes the switch; this ticket builds it.
Today the English pin (ChatAgent.java:221-225) and the non-en display leg
(:582-593) are unconditional, and chat memory persists the English
`approved` text (:654-656). In native mode the reply is generated in the
scope's declared language by a bar-cleared model and the display leg is
skipped; memory stays English-canonical by D79's window-raw +
checkpoint-English resolution — the assistant turn persists raw to the
session window (symmetric with the user turns that already persist raw)
and the compressor writes the chat_memory checkpoint in English in both
modes. The mode resolves per scope (override, else deployment default —
translate) and per (model, language) clearance; an uncleared pair resolves
translate at resolution time, logged, and the mode never flips mid-turn.
Streaming is the sibling ticket (M1-849); delivery here stays the M1-607
stage-label shape. Shared analysis: `analysis_ref:`.

## Root cause

The mode concept is absent by construction: the pipeline was built
translate-only (D29/D58 era), so the language contract rides one constant
(REPLY_LANGUAGE_DIRECTIVE) and one unconditional call site. The change is
small in mechanism — a mode resolver, a second directive, a conditional
display leg, a raw persist, a checkpoint language pin, a preference
column, a command — and large in the controls each of those carries
(§10), which is why the amendment landed first.

## Pitfalls

Numbered per the analysis document; this ticket carries P6, P7, P8, P9,
P13, P15 (P17 void — below).

- P6: memory canonicity — D79's resolution, not the analysis's F1: the
  assistant turn persists raw (scope language in native mode, English in
  translate mode — the generated text) and the chat_memory checkpoint
  stays English because the compressor declares English output in both
  modes. NO back-translate leg exists: the deferred commit carries no
  translator call, so its failure posture is the existing
  runPostDeliveryCommit one (log, never re-enter the delivery path) —
  unchanged by this ticket.
- P7: no mechanical language net in native mode — the registry gate is
  the control; the amendment's stated residual is the honest cost.
- P8: registry conflation — a code constant, never an operator key, never
  the router languages key.
- P9: query anchoring stays — the D58 leg is pinned running in native
  mode.
- P13: the gates — command index/CATALOGUE/tier together, five bundles,
  documented config key, exemption entry removed.
- P15: end-state calibration — the registry content is the record's
  restated end-state matrix (M1-858: gemma × cs/ru/tr cleared);
  pre-existing chat tests are the translate-path pin and are not
  modified.
- P17: void under the window-raw resolution — D79 adds no translator leg,
  the §Secrets handling enumeration gained no leg entry, and the
  disclosure texts (prod/switch-llm.sh, SETUP_GUIDE.md) stay
  byte-unchanged (the M1-845 judgement; named in `out_of_scope`).

## Approach

- **Files to touch:** `files_scope` (ChatAgent + ChatPromptBuilder, the
  new /reply-mode command handler beside LangCommandHandler,
  CompressCommandHandler for the checkpoint language pin, the Flyway
  migration V81 in infochat-core, application.properties, the five
  bundles, the commands.md index entry, plus tests).
- **Steps, in order:**
  1. Write the reproduction RED (a native scope skips the display leg and
     persists the turn raw).
  2. The registry class (code constant, LanguageRegistry posture) seeded
     from the record's restated matrix (M1-858), and the mode resolver (scope override →
     deployment default → registry gate → translate fallback with a log
     line; no mid-turn flip).
  3. The migration (nullable reply_mode column on scope_preferences, V81)
     and the infochat.chat.reply-mode config key with its committed
     translate default; remove the M1-845 exemption entry.
  4. ChatAgent: the mode-conditional directive (a second constant for
     native mode, declared-language wording per the amendment) and the
     conditional display leg; the commit's persist operand is the
     generated text in both modes (unchanged in translate mode — it is
     already the English `approved` text; raw scope language in native
     mode). CompressCommandHandler: COMPRESS_SYSTEM_PROMPT declares the
     English checkpoint output (both modes).
  5. The /reply-mode command with the full catalogue/bundle/index surface,
     the stored-either-way override, and the bare-invocation status read.
     The catalogue entry mechanically changes the /help listing two
     existing ITs assert verbatim (LangCommandIT, AdapterRouterIT); each
     gets the one-line insertion of the new command at its CATALOGUE
     position — an assertion-preserving surface update, not an item-2
     modification (the translate-path pins stay untouched).
- **Controls to preserve (§10):** sanitize → strip → refusal →
  (translate) translate+sanitizer-2+cache → help-block append →
  emptied-reply degrade → provenance append (ChatAgent.java:560-700);
  the CHAT_MODE audit row; the LLM_OUTPUT_SANITIZED rows; the
  deferred-commit delivery-outcome gate; the breaker-open pre-fetch skip;
  the D58 query-anchoring leg; the D43 two-path rule; the per-user LLM
  bucket posture.
- **Pitfall→mitigation:** P6→step 4 + acceptance items 1/4; P7/P8→step 2
  + item 3; P9→item 5; P13→items 7/8; P15→item 2; P17→void (out_of_scope).

## Definition of done

Native mode generates in-language, skips the display leg, persists the
turn raw with the checkpoint staying English via the compressor's
declared-English output; translate is byte-identical to today; the
registry gates clearance; the command, migration, config key, bundles,
and index land with every gate green; the failure-mode test (the
uncleared-pair fallback) passes; mvn verify is green from the repo root.

## Verification

- P6 → ChatAgentReplyModeTest.aNativeScopeSkipsTheDisplayLegAndPersistsTheTurnRaw
  (window-raw half) + aNativeWindowCompressesToAnEnglishCheckpoint
  (checkpoint half) — no back-translate leg exists to test (D79).
- P7/P8 → ChatAgentReplyModeTest.anUnclearedPairResolvesTranslateEvenWithTheOverrideSet
  — a hostile-to-the-gate config (override native + uncleared model)
  asserts translate behavior and the log line.
- P9 → ChatAgentReplyModeTest.queryAnchoringStillRunsInNativeMode — pins
  the D58 call on a non-en native scope.
- P13 → CommandCatalogueParityTest / DocumentedConfigKeyParityTest / the
  D43 completeness gate run in `mvn verify` and fail the build on a
  one-sided change by construction.
- P15 → acceptance item 2 — the pre-existing suite unmodified.
- P17 → void: git status shows prod/switch-llm.sh and SETUP_GUIDE.md
  untouched.
- failure mode → item 3 is the hostile-input coverage; item 6's
  markers-only case re-pins the emptied-reply degrade in native mode.
- acceptance item 10 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the streaming path (M1-849), the query-anchoring
leg, the other display legs, any operator-widenable eligibility list,
multilingual memory (rejected at the M1-845 amendment, never drafted
around), and the disclosure texts (P17 void). The en-scope behavior is
mode-neutral by construction (no display leg either way); a test
asserting en scopes are unchanged in both modes belongs to item 2's
unmodified-suite pin, not to new code.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-848-streaming-translation-switch-5.md
```

## Round 1 rework

REWORK ITEMS (from .scratch/tick-review-M1-848-r1.txt, appended verbatim):

1. Finding 1: capture the resolved ChatReplyMode at submit (InboundRouter.java:1084-1099) and re-seed it in runSeededDispatchStage next to setOperationId (:1154), so the worker-side ChatAgent reads the mode resolved at intake; evaluated via a hop-exercising probe (dispatchChat-override rig on a real pooled dispatcher, or a QuarkusMock'd ChatReplyModeRegistry IT) that is RED before and GREEN after, plus full `mvn verify`.
2. Finding 2: assert the uncleared-pair log emission (ListAppender on ChatReplyModeResolver's logger) in ChatAgentReplyModeTest.anUnclearedPairResolvesTranslateEvenWithTheOverrideSet; evaluated via that test failing when the log.info at ChatReplyModeResolver.java:63-65 is deleted and passing with it present.
3. Finding 3: reword ChatReplyModeRegistry.java:53's javadoc to drop the usage reference; evaluated via `grep -n "for the registry-content test" infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatReplyModeRegistry.java` returning nothing.
