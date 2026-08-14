---
id: M1-848
title: "Wire the pivot/direct switch into the chat reply path"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  to-be-written: ChatAgentReplyModeTest#aDirectScopeSkipsTheDisplayLegAndPersistsEnglish
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/streaming-translation-switch.md). Probe:
  grep -n 'REPLY_LANGUAGE_DIRECTIVE' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  shows the unconditional English pin (:201-205, applied at :531) and
  grep -n 'translationPipeline.run' shows the unconditional non-en display
  leg (:566-573) — observed: no pipeline-mode concept exists, so a
  direct-mode scope today would have its scope-language reply declared
  English to the translator (the M1-778 defect class) and persisted
  non-English against the English-canonical memory rule
  (security.md:2208-2209).
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: [M1-845]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/
  - infochat-provider/src/main/resources/db/migration/
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
    bar-clearing registry is a code constant seeded from M1-844's record,
    exactly the LanguageRegistry.java:24-29 posture.
  - >-
    MULTILINGUAL chat_memory — memory stays English-canonical via the
    persist-time back-translate per the M1-845 amendment; if the user
    picked multilingual memory there, this ticket's memory half was
    escalated and re-decided — do not draft around it.
acceptance:
  - "ChatAgentReplyModeTest.aDirectScopeSkipsTheDisplayLegAndPersistsEnglish (the reproduction, written and run RED at start) passes — a direct-eligible scope (registry (model, language) pair + the override or deployment default set to direct) generates under the scope-language directive, skips TranslationPipeline, delivers the sanitized reply in the scope language, and persists the assistant turn in ENGLISH via the back-translate leg (M1-845's canonicity resolution)."
  - "Every pre-existing ChatAgentTest / InboundRouterChatModeIT / InboundRouterChatDeliveryOrderingIT case passes UNCHANGED — the default (pivot, no override) is byte-identical to today's behavior: English directive, display leg runs, memory persists the English `approved` text (§8: this ticket authorizes no pre-existing test modification; the end-state pin is that the pivot path is untouched, P15)."
  - "ChatAgentReplyModeTest.anUnclearedPairResolvesPivotEvenWithTheOverrideSet passes — FAILURE-MODE (P7/P8): a scope whose resolved chat model is NOT in the bar-clearing registry resolves pivot with a log line even when its override or the deployment default says direct; the wrong-language-with-no-note delivery the registry exists to prevent never ships by configuration."
  - "ChatAgentReplyModeTest.aFailedBackTranslatePersistsNothingNonEnglishAndNeverResends passes — FAILURE-MODE (P6/P17): a translator failure inside the post-delivery commit leaves the delivered reply standing, persists no non-English assistant turn, and never re-enters the delivery path (the runPostDeliveryCommit posture, InboundRouter.java:1414-1422)."
  - "ChatAgentReplyModeTest.queryAnchoringStillRunsInDirectMode passes — a non-en direct scope still issues the D58 X→EN translator call for retrieval (P9; the call is pinned on the pre-fetch path)."
  - "The deterministic accretions are mode-independent — asserted by ChatAgentReplyModeTest cases that pass: a direct turn appends the bundle-localized help block and the provenance notice exactly as pivot does (D43 two-path rule), and the emptied-reply degrade (llm.md:549-556) fires identically for a step-3c match and for a markers-only reply."
  - "The override command (name per the approved M1-845 wording) lands its full surface and its handler test passes: the commands.md index entry, the HelpCommandHandler.CATALOGUE entry, and the tier model together (CommandCatalogueParityTest green), bundle keys in all five bundles (the D43 completeness gate), the /lang-shaped permission gates (DM own scope; group admin), probation-allowed, zero audit rows, and the unsupported-value error listing the supported values (P13)."
  - "The scope_preferences migration adds the mode column (nullable = inherit the deployment default), the deployment-default key ships its committed default (pivot) in application.properties, and DocumentedConfigKeyParityTest passes with the M1-845 exemption entry removed (the code now builds the documented key) (P13)."
  - "The bar-clearing registry is a code constant seeded with exactly the (model, language) matrix M1-844's record produced, its comment cites the decisions.md D-row the M1-845 amendment added (§11 pointer discipline), and the registry-content test asserts the matrix content against the record."
  - "The translator-leg disclosure texts (prod/switch-llm.sh, SETUP_GUIDE.md) name the back-translate leg per the M1-845 enumeration entry (P17) — probe: grep -n 'back-translate\\|memory' prod/switch-llm.sh shows the new leg in the switch-time disclosure."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
    - the override command's handler test (LangCommandHandler-shaped)
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

# M1-848: Wire the pivot/direct switch into the chat reply path

## Context

M1-845's amendment authorizes the switch; this ticket builds it. Today the
English pin (ChatAgent.java:201-205) and the non-en display leg
(:566-573) are unconditional, and chat memory persists the English
`approved` text (:634-638). In direct mode the reply is generated in the
scope's declared language by a bar-cleared model, the display leg is
skipped, and memory stays English via a persist-time back-translate. The
mode resolves per scope (override, else deployment default — pivot) and
per (model, language) clearance; an uncleared pair resolves pivot.
Streaming is the sibling ticket (M1-849); delivery here stays the M1-607
stage-label shape. Shared analysis: `analysis_ref:`.

## Root cause

The mode concept is absent by construction: the pipeline was built
pivot-only (D29/D58 era), so the language contract rides one constant
(REPLY_LANGUAGE_DIRECTIVE) and one unconditional call site. The change is
small in mechanism — a mode resolver, a second directive, a conditional
display leg, a back-translate in the commit, a preference column, a command
— and large in the controls each of those carries (§10), which is why the
amendment landed first.

## Pitfalls

Numbered per the analysis document; this ticket carries P6, P7, P8, P9,
P13, P15, P17.

- P6: memory canonicity — the assistant turn persists English in both
  modes; the back-translate runs inside the deferred commit and its failure
  never persists non-English and never resends.
- P7: no mechanical language net in direct mode — the registry gate is the
  control; the amendment's stated residual is the honest cost.
- P8: registry conflation — a code constant, never an operator key, never
  the router languages key.
- P9: query anchoring stays — the D58 leg is pinned running in direct mode.
- P13: the gates — command index/CATALOGUE/tier together, five bundles,
  documented config key, exemption entry removed.
- P15: end-state calibration — the registry content is M1-844's end-state
  matrix; pre-existing chat tests are the pivot-path pin and are not
  modified.
- P17: the back-translate leg's disclosure surface — switch-llm.sh and
  SETUP_GUIDE name it.

## Approach

- **Files to touch:** `files_scope` (ChatAgent + ChatPromptBuilder, the new
  command handler beside LangCommandHandler, the Flyway migration,
  application.properties, the five bundles, the commands.md index entry,
  plus tests and the disclosure scripts per acceptance item 10).
- **Steps, in order:**
  1. Write the reproduction RED (a direct scope skips the display leg and
     persists English).
  2. The registry class (code constant, LanguageRegistry posture) seeded
     from M1-844's matrix, and the mode resolver (scope override →
     deployment default → registry gate → pivot fallback with a log line).
  3. The migration (nullable mode column) and the config key with its
     committed pivot default; remove the M1-845 exemption entry.
  4. ChatAgent: the mode-conditional directive (a second constant for
     direct mode, declared-language wording per the amendment) and the
     conditional display leg; the back-translate leg inside the commit with
     its failure posture.
  5. The override command with the full catalogue/bundle/index surface.
  6. The disclosure texts (item 10).
- **Controls to preserve (§10):** sanitize → strip → refusal → (pivot)
  translate+sanitizer-2+cache → help-block append → emptied-reply degrade →
  provenance append (ChatAgent.java:540-664); the CHAT_MODE audit row; the
  LLM_OUTPUT_SANITIZED rows; the deferred-commit delivery-outcome gate; the
  breaker-open pre-fetch skip; the D58 query-anchoring leg; the D43
  two-path rule; the per-user LLM bucket posture (the back-translate leg's
  metering per the M1-845 amendment).
- **Pitfall→mitigation:** P6→step 4 + acceptance items 1/4; P7/P8→step 2 +
  item 3; P9→item 5; P13→items 7/8; P15→item 2; P17→item 10.

## Definition of done

Direct mode generates in-language, skips the display leg, persists English
via the back-translate; pivot is byte-identical to today; the registry
gates clearance; the command, migration, config key, bundles, index, and
disclosure texts land with every gate green; the failure-mode tests (item
3's uncleared-pair fallback, item 4's failed back-translate) pass; mvn
verify is green from the repo root.

## Verification

- P6 → ChatAgentReplyModeTest.aFailedBackTranslatePersistsNothingNonEnglishAndNeverResends
  — feeds a translator failure into the commit and asserts no non-English
  persist and no resend.
- P7/P8 → ChatAgentReplyModeTest.anUnclearedPairResolvesPivotEvenWithTheOverrideSet
  — a hostile-to-the-gate config (override direct + uncleared model)
  asserts pivot behavior and the log line.
- P9 → ChatAgentReplyModeTest.queryAnchoringStillRunsInDirectMode — pins
  the D58 call on a non-en direct scope.
- P13 → CommandCatalogueParityTest / DocumentedConfigKeyParityTest / the
  D43 completeness gate run in `mvn verify` and fail the build on a
  one-sided change by construction.
- P15 → acceptance item 2 — the pre-existing suite unmodified.
- P17 → item 10's grep probe on the disclosure scripts.
- failure mode → items 3 and 4 are the hostile-input coverage beyond the
  reproduction; item 6's markers-only case re-pins the emptied-reply
  degrade in direct mode.
- acceptance item 11 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the streaming path (M1-849), the query-anchoring
leg, the other display legs, any operator-widenable eligibility list, and
multilingual memory (escalated via M1-845, never drafted around). The
en-scope behavior is mode-neutral by construction (no display leg either
way); a test asserting en scopes are unchanged in both modes belongs to
item 2's unmodified-suite pin, not to new code.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-848-streaming-translation-switch-5.md
```
