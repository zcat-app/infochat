---
id: M1-845
title: "Spec: switchable translation pipeline (pivot vs direct)"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  Probe: docs/spec/llm.md §Translation flow:289-290 promises the display
  leg unconditionally ("For each user-visible reply, if the scope language
  is 'en' the raw text is sent unchanged. Otherwise it goes through
  TranslationProvider."), security.md:2208-2209 promises "Chat memory
  stays English-canonical: only the delivered reply is translated", and
  grep -ri 'pipeline mode\|reply.mode\|direct mode' docs/spec/ returns
  nothing — the switchable pipeline the user directed on 2026-08-14
  (future-features.md §D5:386-453) cannot be implemented without
  contradicting these lines, so the amendment lands first. Observed
  in-tree: the unconditional English pin at ChatAgent.java:201-205
  (applied at :531) and the unconditional non-en display leg at
  ChatAgent.java:566-573.
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: [M1-844]
files_scope:
  - docs/spec/llm.md
  - docs/spec/commands.md
  - docs/spec/security.md
  - docs/spec/decisions.md
  - infochat-provider/src/test/resources/documented-config-key-exemptions.txt
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY code change — command handler, ChatAgent, migration, config key
    implementation, registry class. This ticket is a spec amendment only
    (the M1-663 shape); the implementation it authorizes is M1-848, and the
    user approves the exact wording before it lands (engineering-rules
    §12). If drafting reveals the contract is unimplementable, escalate —
    do not weaken the language to fit an implementation idea.
  - >-
    THE STREAMED-SURFACE REGIME — M1-846 owns security.md §LLM output
    sanitizer's streaming amendment and the messaging.md live-text mode.
    This ticket's only streaming content is the eligibility fact the
    pairing rests on: direct-mode scopes generate in the delivered
    language.
  - >-
    WIDENING the display-leg conditionality beyond the chat reply (P19):
    digest prose, /summary, /saved and headline display legs stay
    unconditional; the summarizer language-aware shortcut
    (llm.md:291-294) and the ingest leg are untouched.
  - >-
    RETIRING or weakening any existing commitment: the translation sanity
    checks (llm.md:528-548), the D58 query-anchoring conditions, the D43
    two-path rule, and the declared-never-inferred rule all survive intact
    in both modes.
acceptance:
  - "docs/spec/llm.md §Translation flow gains the mode-conditional chat-reply rule (rule-text only; user approves wording): TWO modes — pivot (today's behavior exactly: English-pinned generation, display leg translates) and direct (the reply is generated in the scope's declared /lang language by a (model, language) pair with a committed bar-clearing measurement record; the display leg is skipped); the unconditional display-leg sentence is re-scoped to pivot mode and the other LLM-authored surfaces (P19). Verify: grep -n 'pipeline' docs/spec/llm.md shows the mode rule inside §Translation flow."
  - "The reply-language contract is mode-conditional in the same section: pivot keeps the English channel; direct contracts the scope's DECLARED language (declared by channel, never inferred — D29's rule holds in both modes). Verify: grep -n 'declared' docs/spec/llm.md §Translation flow region shows the both-modes contract."
  - "Query anchoring (D58) is recorded as mode-independent: the X→EN query translation runs in BOTH modes because the embedding DB is English. Verify: grep -n 'query' docs/spec/llm.md §Translation flow region shows the both-modes sentence (P9)."
  - "docs/spec/decisions.md gains the switch decision row (next free D-number): the two modes, the deployment default (pivot = today's behavior), the per-scope override, the registry gate as a measurement-backed CODE CONSTANT — never an operator key and never the router's languages capability key (P8) — the resolution-time direct→pivot fallback (an uncleared pair resolves pivot, logged; no mid-turn mode flip), and the chat_memory canonicity resolution. Verify: grep -n '^| D7[89]\\|^| D8[0-9]' docs/spec/decisions.md shows the new row."
  - "chat_memory canonicity is decided IN this amendment and recorded: the recommended option is the persist-time X→EN back-translate (keeps 'Chat memory stays English-canonical' true in both modes and keeps the fallback memory-compatible); security.md §Secrets handling's translator-leg enumeration gains the new leg (a post-delivery leg carrying the reply text, user-private) with its rate-limit posture stated, per the section's own 'a new call site is a new leg' rule (:2233-2239). If the user picks multilingual memory instead, THAT is a promise change — this ticket escalates the memory half rather than drafting it (analysis §SPEC-GAP item 1). Verify: grep -n 'English-canonical' docs/spec/security.md shows the both-modes wording."
  - "docs/spec/commands.md §Conversation control gains the per-scope override command (placeholder name /reply-mode pivot|direct — final name approved with the wording): DM = own scope, group = group/bot admin (the /lang gate shape), probation-allowed, zero audit rows (a user preference, the /lang posture), an unsupported value lists the supported values (never a silent no-op), confirmations from the D43 bundles. Verify: grep -n 'reply-mode' docs/spec/commands.md shows the entry."
  - "The deployment-default key is named for the documented-key surface with the D73-style capability-gating rationale (an operator deployment posture defaulting to pivot, not a §7 feature flag — P14); because the code lands in M1-848, the documented key gets its documented-config-key-exemptions.txt entry citing M1-848 per the M1-708 rule. Verify: grep -n 'reply-mode\\|translation-mode' docs/spec/decisions.md docs/spec/llm.md names the key, and grep -n 'M1-848' infochat-provider/src/test/resources/documented-config-key-exemptions.txt shows the exemption."
  - "Rule-text only throughout: git diff docs/spec/ shows no dates, ticket IDs, or report citations in spec prose (§12) — the measurement record is cited from the decision register's motivation, not from rule text."
  - "mvn verify from repo root is green (spec edits run the doc gates; engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Spec-only ticket (M1-663 precedent): no JUnit surface; the doc gates
      (DocumentedConfigKeyParityTest, CommandCatalogueParityTest) must stay
      green — the command index/CATALOGUE land with the code in M1-848, and
      this ticket's commands.md entry is written so the parity gate's
      post-848 state holds (P15: draft the command entry against the end
      state, and if the gate cannot pass pre-code, the entry's code-side
      half is M1-848's acceptance, named here at draft time).
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Pipeline order (delivery direction)
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D29
  - D43
  - D58
  - D73
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for: docs/spec/llm.md §Translation flow
spec_amend_parent: M1-848
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-845: Spec: switchable translation pipeline (pivot vs direct)

## Context

The 2026-08-14 user direction schedules the §D5 switch: a deployment-level
default plus a per-scope override, shipping with default ON (pivot — today's
behavior), direct mode restricted to models that cleared the in-language
bar. The spec today promises the pivot unconditionally (llm.md:289-290) and
English-canonical chat memory (security.md:2208-2209), so the amendment is
the first artifact — code that contradicts those lines may not be drafted
before it (spec-first rule). This ticket is the amendment vehicle; M1-844's
measurement record supplies the bar-clearing matrix and the
context-translation A/B verdict it cites. Shared analysis: `analysis_ref:`.

## Root cause

Not a defect: the spec's translation rules were written when the pivot was
the only mode (D29/D58 era), so every display-leg sentence is
unconditional. The amendment's content is fixed by the direction except two
genuinely open decisions it must encode: the chat_memory canonicity
resolution (I1 open decision 2 — analysis option F) and the command/config
surface naming. Both go to the user with the wording (§12).

## Pitfalls

Numbered per the analysis document; this ticket carries P6, P7, P8, P9,
P14, P17, P19.

- P6: English-canonical memory — the amendment must pick F1
  (back-translate, recommended: keeps every current promise and makes the
  direct→pivot fallback memory-compatible) or escalate the memory half
  (F2 is a promise change).
- P7: direct mode has no mechanical language net — the amendment must state
  the accepted residual honestly: a whole-turn collapse delivers the wrong
  language with no note, and the controls are the registry gate plus the
  M1-844 evidence, not a runtime check.
- P8: registry vs. languages-key conflation — the clearance signal is a
  measurement-backed code constant (LanguageRegistry.java:24-29 posture),
  never `infochat.llm.<provider>.languages` (M1-716's named trap) and never
  an operator list.
- P9: query anchoring stays in both modes — the amendment says so
  explicitly; the D58 leg is retrieval-side.
- P14: §7 feature-flag tension — the flags are recorded as D73-style
  capability gates (operator posture, defaults = today's behavior, off is a
  supported vps posture), not change-avoidance flags.
- P17: the back-translate leg is a NEW translator leg — the §Secrets
  handling enumeration gains it with its privacy class (user-private reply
  text) and rate-limit posture; the disclosure-text consequence
  (prod/switch-llm.sh, SETUP_GUIDE) is named for M1-848.
- P19: over-widening — only the chat-reply leg becomes conditional; every
  other display leg stays unconditional.

## Approach

- **Files to touch:** `files_scope` — four spec files plus the exemptions
  file.
- **Steps, in order:**
  1. Read M1-844's record: the bar-clearing matrix and the A/B verdict the
     amendment cites (blocked_by).
  2. Draft the llm.md §Translation flow amendment (modes, conditional
     display leg, declared-language contract, query anchoring both modes).
  3. Draft the decisions.md row (D-number assigned at write time; the
     motivation cites the measurement record — §12 keeps citations out of
     rule text, the register is where history lives).
  4. Draft the security.md §Secrets handling entry (the back-translate
     leg) and the both-modes canonicity wording — or escalate per P6.
  5. Draft the commands.md §Conversation control command entry.
  6. Name the config key + write the exemptions entry citing M1-848.
  7. Show the user the exact proposed text with a plain-English account of
     each commitment added/removed/changed (§12); only an explicit yes
     lands it.
- **Controls to preserve (§10):** the translation sanity checks
  (llm.md:528-548) stay as the pivot display leg's backstop; the D58
  conditions stay exact; D43 two-path untouched; no existing commitment
  weakened (the M1-663 acceptance-3 discipline — the reviewer's
  SPEC-TRUTHNESS check reads the diff against this).
- **Pitfall→mitigation:** P6→step 4's escalation clause; P7→the residual
  paragraph in the llm.md amendment; P8→the decisions row's code-constant
  rule; P9→the both-modes sentence; P14→the D73 rationale sentence; P17→
  the enumeration entry; P19→the re-scoped sentence's own boundaries.

## Definition of done

The five-section amendment lands (llm.md modes + conditional display leg +
declared-language contract + query anchoring; decisions.md row;
security.md enumeration + canonicity; commands.md command; config key +
exemption), as rule-text only, with user approval recorded, the memory
question resolved or escalated, and mvn verify green.

## Verification

- P6 → item 5's grep — the canonicity sentence covers both modes, or the
  escalation is recorded in the ticket.
- P7 → item 1's amendment text contains the accepted-residual statement —
  Verify: the wording review checklist names it.
- P8 → item 4's grep — the registry rule names the code-constant mechanism
  and excludes the operator-key shape.
- P9 → item 3's grep — both-modes query anchoring.
- P14 → item 7's grep — the capability-gating rationale is in the register.
- P17 → item 5's grep — the new leg is enumerated with its data class.
- P19 → item 1's grep — the conditional is scoped to the chat reply.
- failure mode → item 5's escalation path is exercised if the user picks
  multilingual memory: the memory half of this ticket STOPS (never drafted
  around), and the rest of the amendment still lands.
- acceptance item 9 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: all code (M1-848), the streaming regime (M1-846),
any widening beyond the chat-reply leg, and any weakening of the sanity
checks / D58 conditions / D43. The ticket's diff touches the four spec
files plus documented-config-key-exemptions.txt and nothing else (`git
diff --stat` shows exactly those — the M1-663 item-4 shape).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-845-streaming-translation-switch-2.md
```
