---
id: M1-845
title: "Spec: switchable translation pipeline (translate vs native)"
status: done
created: 2026-08-14
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe: docs/spec/llm.md §Translation flow:300-301 promises the display
  leg unconditionally ("For each user-visible reply, if the scope language
  is 'en' the raw text is sent unchanged. Otherwise it goes through
  TranslationProvider."), security.md:2217 promises "Chat memory
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
    pairing rests on: native-mode scopes generate in the delivered
    language.
  - >-
    WIDENING the display-leg conditionality beyond the chat reply (P19):
    digest prose, /summary, /saved and headline display legs stay
    unconditional; the summarizer language-aware shortcut
    (llm.md:302-305) and the ingest leg are untouched.
  - >-
    RETIRING or weakening any existing commitment: the translation sanity
    checks (llm.md:539-559), the D58 query-anchoring conditions, the D43
    two-path rule, and the declared-never-inferred rule all survive intact
    in both modes.
acceptance:
  - "docs/spec/llm.md §Translation flow gains the mode-conditional chat-reply rule (rule-text only; user approves wording): TWO modes — translate (today's behavior exactly: English-pinned generation, display leg translates) and native (the reply is generated in the scope's declared /lang language by a (model, language) pair with a committed bar-clearing measurement record; the display leg is skipped); the unconditional display-leg sentence is re-scoped to translate mode and the other LLM-authored surfaces (P19). Verify: grep -n 'pipeline' docs/spec/llm.md shows the mode rule inside §Translation flow."
  - "The reply-language contract is mode-conditional in the same section: translate mode keeps the English channel; native mode contracts the scope's DECLARED language (declared by channel, never inferred — D29's rule holds in both modes). Verify: grep -n 'declared' docs/spec/llm.md §Translation flow region shows the both-modes contract."
  - "Query anchoring (D58) is recorded as mode-independent: the X→EN query translation runs in BOTH modes because the embedding DB is English. Verify: grep -n 'query' docs/spec/llm.md §Translation flow region shows the both-modes sentence (P9)."
  - "docs/spec/decisions.md gains the switch decision row (next free D-number): the two modes, the deployment default (translate = today's behavior), the per-scope override, the registry gate as a measurement-backed CODE CONSTANT — never an operator key and never the router's languages capability key (P8) — the resolution-time native→translate fallback (an uncleared pair resolves translate mode, logged; no mid-turn mode flip), and the chat_memory canonicity resolution. Verify: grep -n '^| D7[89]\\|^| D8[0-9]' docs/spec/decisions.md shows the new row."
  - "chat_memory canonicity is decided IN this amendment and recorded: the user-approved resolution at start is window-raw, checkpoint-English (neither analysis option F1 nor F2) — native-mode assistant turns persist raw to the session window, symmetric with the user turns that already persist raw, and the chat_memory checkpoint stays English-canonical because the compressor writes it in English in both modes (the amendment states the commitment; the compressor pin lands with the M1-848 code). NO new translator leg is added — the §Secrets handling enumeration and the disclosure texts are byte-unchanged — and the canonicity sentence is reworded for both modes. Verify: grep -n 'English-canonical' docs/spec/security.md shows the both-modes wording, and git diff docs/spec/security.md shows no added leg entry."
  - "docs/spec/commands.md §Conversation control gains the per-scope override command (name approved at start: /reply-mode translate|native): DM = own scope, group = group admin or bot admin (the gate the /lang handler actually enforces), probation-allowed, zero audit rows (a user preference), an unsupported value lists the supported values (never a silent no-op), an uncleared native setting is stored and resolves translate until the pair clears (the confirmation says so — activation on a later-cleared pair needs no further command), a bare invocation names the scope's setting, confirmations from the D43 bundles. Verify: grep -n 'reply-mode' docs/spec/commands.md shows the entry."
  - "The deployment-default key is named for the documented-key surface with the D73-style capability-gating rationale (an operator deployment posture defaulting to translate mode, not a §7 feature flag — P14); because the code lands in M1-848, the documented key gets its documented-config-key-exemptions.txt entry citing M1-848 per the M1-708 rule. Verify: grep -n 'reply-mode\\|translation-mode' docs/spec/decisions.md docs/spec/llm.md names the key, and grep -n 'M1-848' infochat-provider/src/test/resources/documented-config-key-exemptions.txt shows the exemption."
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
reviews:
  - round: 1
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE (spec-only ticket), MAINTAINABILITY PASS, SCOPE PASS; 4 falsified-and-dropped findings; 1 RECOMMENDED-NEW-TICKET recorded under Review observations"
    diff_stats: "7 files changed, 143 insertions(+), 69 deletions(-)"
    verdict_file: .scratch/tick-review-M1-845-r1.txt

overrides: []
aborted_attempts: []
reopens: []
clarity_check: "2026-08-16 start pass — tick-lint 0 findings/0 BLOCKERs; analysis_ref cross-read: the ticket's pitfalls P6/P7/P8/P9/P14/P17/P19 all present and matching the analysis, P13's spec-side substance (documented-key surface, commands.md index entry) covered by acceptance items 6-7 plus the test_plan note, P15 referenced in test_plan.notes. Citations spot-checked: every substantive claim holds, with line drift since filing from later-landed spec edits — llm.md display-leg promise :289-290 now :300-301, sanity checks :528-548 now :539-559, summarizer shortcut :291-294 now :302-305; security.md English-canonical :2208-2209 now :2217-2218, new-leg rule :2233-2239 now :2250-2256 (drift only, the analysis's own :81-83 posture); the ticket's line refs updated to the current sites. ChatAgent.java:201-205/:531/:566-573 exact; LanguageRegistry.java:24-29 code-constant posture holds (bundle/LanguageRegistry.java); grep -ri 'pipeline mode|reply.mode|direct mode' docs/spec/ still empty; future-features.md §D5:386+ present. D-number: the analysis floated D78 but D78 is taken (the /image prompt-translation leg row); acceptance item 4 says 'next free D-number', which is D79 — no D79+ row exists. §Census N/A (spec amendment, not a defect-class fix or guard; template requires Census only for class tickets). blocked_by M1-844 added NO tests (test_plan.adds: []) so the preserves-trace is vacuous. The two open decisions (chat_memory canonicity, command/config-key naming) are §12 user-approval points by design, not ambiguities — no blocking question. 2026-08-16 user §12 refine at start: (1) the memory resolution is NEITHER analysis option F1 nor F2 — the user-directed shape is window-raw, checkpoint-English: native-mode assistant turns persist raw to the session window (symmetric with the user turns that already persist raw — ChatAgent.java:634-635 verified), and chat_memory canonicity is enforced at the checkpoint boundary by the compressor writing the checkpoint in English in both modes (COMPRESS_SYSTEM_PROMPT carries no language pin today — verified; the pin lands with the M1-848 code, whose files_scope covers provider/command/). Consequences: NO new translator leg (P17 void), the §Secrets handling enumeration and the disclosure texts are byte-unchanged, no rate-limit posture owed; honest residuals stated in the amendment — a native→translate flip hands the English-pinned model a non-English window (today's mixed-window class, user turns already persist raw), and a native scope's cross-checkpoint continuity rides an English summary of scope-language turns. Spec census verified: the only canonicity sentence in docs/spec is security.md:2217; no spec text promises session-window language or checkpoint language, so the reshape contradicts nothing. (2) Mode names: translate|native (the user-recommended pair; 'pivot'/'direct' were placeholders), applied consistently across the amendment and this ticket. (3) Wording fixes: the deployment-default sentence reworded ('set by the operator key'); the command gate stated as the code's actual shape — group admin OR bot admin (LangCommandHandler.java:125-128 verified; the /lang spec entry at commands.md:1157 says 'group admin only', a pre-existing drift noted here, not fixed by this ticket); the zero-audit claim kept without the /lang parenthetical (the posture lives in the LangCommandHandler javadoc + langWritesZeroRowsToAuditLog pin, not in the /lang spec entry)."
escalation_reason:
---

# M1-845: Spec: switchable translation pipeline (translate vs native)

## Context

The 2026-08-14 user direction schedules the §D5 switch: a deployment-level
default plus a per-scope override, shipping with default ON (translate
mode — today's behavior), native mode restricted to models that cleared the
in-language bar. The spec today promises the translate mode unconditionally
(llm.md:300-301) and English-canonical chat memory (security.md:2217), so
the amendment is
the first artifact — code that contradicts those lines may not be drafted
before it (spec-first rule). This ticket is the amendment vehicle; M1-844's
measurement record supplies the bar-clearing matrix and the
context-translation A/B verdict it cites. Shared analysis: `analysis_ref:`.

## Root cause

Not a defect: the spec's translation rules were written when translate
mode was the only mode (D29/D58 era), so every display-leg sentence is
unconditional. The amendment's content is fixed by the direction except two
genuinely open decisions it must encode: the chat_memory canonicity
resolution (I1 open decision 2 — resolved at start by the user's §12
decision as window-raw, checkpoint-English, neither analysis option F1 nor
F2) and the command/config surface naming (resolved at start:
/reply-mode translate|native, key infochat.chat.reply-mode). Both went to
the user with the wording (§12) before any spec edit.

## Pitfalls

Numbered per the analysis document; this ticket carries P6, P7, P8, P9,
P14, P17, P19.

- P6: English-canonical memory — resolved at start by the user's §12
  decision as window-raw, checkpoint-English (neither analysis option F1
  nor F2): native-mode assistant turns persist raw to the session window,
  exactly as user turns already persist raw, and the chat_memory
  checkpoint stays English-canonical because the compressor writes it in
  English in both modes (the amendment states the commitment; the
  compressor pin lands with the M1-848 code, whose files_scope covers
  provider/command/). No new translator leg, no disclosure-text
  consequence; the native→translate fallback stays memory-compatible
  because the checkpoint is English in both modes.
- P7: native mode has no mechanical language net — the amendment must state
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
- P17: void under the window-raw resolution — no back-translate leg
  exists, the §Secrets handling translator-leg enumeration and the
  disclosure texts are byte-unchanged, and no consequence rides M1-848;
  the canonicity mechanism is the compressor's own English generation,
  not a translator call.
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
     motivation names the measurement record descriptively — §12 keeps
     dates, ticket IDs and path citations out of spec prose entirely, the
     register is where history lives; the D78 row is the precedent).
  4. Draft the security.md §Secrets handling canonicity rewording
     (window-raw, checkpoint-English per the user's §12 resolution; no
     new leg — the enumeration and the disclosure texts stay
     byte-unchanged).
  5. Draft the commands.md §Conversation control command entry.
  6. Name the config key + write the exemptions entry citing M1-848.
  7. Show the user the exact proposed text with a plain-English account of
     each commitment added/removed/changed (§12); only an explicit yes
     lands it.
- **Controls to preserve (§10):** the translation sanity checks
  (llm.md:539-559) stay as the translate-mode display leg's backstop; the
  D58 conditions stay exact; D43 two-path untouched; no existing
  commitment weakened (the M1-663 acceptance-3 discipline — the
  reviewer's SPEC-TRUTHNESS check reads the diff against this).
- **Pitfall→mitigation:** P6→step 4's canonicity rewording; P7→the
  residual paragraph in the llm.md amendment; P8→the decisions row's
  code-constant rule; P9→the both-modes sentence; P14→the D73 rationale
  sentence; P17→void (no leg, no enumeration entry); P19→the re-scoped
  sentence's own boundaries.

## Definition of done

The five-section amendment lands (llm.md modes + conditional display leg +
declared-language contract + query anchoring; decisions.md row;
security.md canonicity; commands.md command; config key + exemption), as
rule-text only, with user approval recorded, the memory question resolved
(window-raw, checkpoint-English), and mvn verify green.

## Verification

- P6 → item 5's greps — the canonicity sentence covers both modes, and
  the security.md diff adds no leg entry.
- P7 → item 1's amendment text contains the accepted-residual statement —
  Verify: the wording review checklist names it.
- P8 → item 4's grep — the registry rule names the code-constant mechanism
  and excludes the operator-key shape.
- P9 → item 3's grep — both-modes query anchoring.
- P14 → item 7's grep — the capability-gating rationale is in the register.
- P17 → void under the window-raw resolution: git diff docs/spec/security.md
  shows no added leg entry and the disclosure texts are untouched.
- P19 → item 1's grep — the conditional is scoped to the chat reply.
- failure mode → not exercised: the user's §12 resolution is window-raw,
  checkpoint-English (neither F1 nor F2), which keeps every current
  promise, so no escalation fired.
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

## Review observations

From round 1 (verdict: APPROVE) — RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF:
no, no DECIDE-BEFORE; recorded here, filing is the user's call:

- The /lang entry's group-gate wording understates who can change a
  group's language. commands.md:1157-1158 says "Group: group admin only",
  but the handler enforces group admin OR bot admin
  (LangCommandHandler.java:126-127). Today a bot admin in a group runs
  `/lang cs` and the scope's language changes while the spec text says
  group admins only. Either the entry reads "Group: group admin or bot
  admin" (the wording /reply-mode and /group-timezone already use), or the
  handler narrows to group-admin-only — a spec-vs-code decision either
  way. The mismatch predates this diff; the new /reply-mode entry's
  truthful wording made the sibling inconsistency visible.
