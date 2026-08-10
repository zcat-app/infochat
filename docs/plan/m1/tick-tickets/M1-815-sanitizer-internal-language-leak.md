---
id: M1-815
title: "Strip internal config identifiers from LLM output"
status: done
created: 2026-08-10
last_updated: 2026-08-11
flow: tick
reproduction: >-
  LlmOutputSanitizerTest.configKeyTokensAreStrippedFromLlmOutput (written
  and run RED on main 2026-08-10) —
  feeds the E9-shaped adversarial refusal (text volunteering
  "infochat.probation.duration" mid-sentence, live test 2026-08-10
  E9) through the full sanitize() and asserts the dotted config token does
  not survive while the surrounding prose does. RED on main: no pass
  recognizes internal config identifiers (grep-verified: no such pass in
  LlmOutputSanitizerCore). Companion RED at start (written and run RED
  2026-08-10):
  HelpTopicCorpusTest.noTopicAnswerNamesARawConfigKey — the en/cs probation
  answers carry the raw token today.
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: []
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerPostconditionTest.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/HelpTopicCorpusTest.java
  - docs/spec/security.md
  - docs/plan/m1/sanitize-caller-census.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - THE ADMIN-COMMAND CLOSED LIST (P9). CLOSED_LIST, its patterns, and
    matchSetEqualsSpecClosedList are untouched; the new category is a
    SEPARATE pass, not a closed-list entry.
  - The D69 topic-delivery path (commands.md:1885-1890) — it bypasses the
    sanitizer BY DESIGN (topics must name user-tier CLOSED_LIST commands);
    the bundle rewording is what removes the key from that surface, and no
    change to the delivery mechanics ships here.
  - REWIRING IngestTranslationWorker (M1-789 P13 carried) — it composes
    its own passes; the new pass is OUTBOUND-ONLY, same posture as the
    scaffolding strip (LlmOutputSanitizer.java:58-59). Stored corpus text
    may legitimately quote a config key (a post about the deployment).
  - Free-form model paraphrase of scaffolding ("The system's appended
    answer block") — no deterministic strip reaches paraphrase; recorded as
    the amendment's accepted residual (analysis option 6), not a test gap.
  - Prompt wording changes (M1-779 out_of_scope carried).
  - The M1-792 follow-ups (TranslationPipeline conditions, empty-body
    guard) — untouched.
acceptance:
  - "LlmOutputSanitizerTest.configKeyTokensAreStrippedFromLlmOutput passes — REPRODUCTION (written and run RED at start): the E9-shaped refusal through sanitize() carries no dotted infochat token while the surrounding prose survives byte-identical (security.md §LLM output sanitizer — the new strip category; §Prompt-injection defenses: the LLM is a black box that can be coaxed into echoing internal framing)."
  - "HelpTopicCorpusTest.noTopicAnswerNamesARawConfigKey passes — written and run RED at start: no topic answer in any of the five bundles contains a dotted `infochat.` token (the class is unambiguous ONLY once product copy stops naming keys — analysis D-2). The rewording keeps the duration VALUE: HelpTopicCorpusTest.probationTopicDurationMatchesApplicationConfig passes UNEDITED (it pins the value, e.g. 24h, not the key name — verified at HelpTopicCorpusTest.java:299-324)."
  - "The five bundles move together (D43): topic.probation.answer reworded in en/cs/tr/es/ru with the key-name parenthetical gone and the stated duration kept — Verify: `grep -c 'infochat\\.' infochat-provider/src/main/resources/bundles/*.properties` prints 0 for every file, and BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle passes via mvn verify."
  - "LlmOutputSanitizerTest.emphasisJoinedConfigKeyIsStillStripped passes — FAILURE-MODE (P8 ordering): `infochat.prob**a**tion.duration` — the markdown downgrade joins the fragments FIRST, the config-key pass sees the joined token and strips it (a pass placed before the downgrade would let it through)."
  - "LlmOutputSanitizerTest.configKeyInsideAMarkerIdIsDroppedAndRowed passes — FAILURE-MODE (P8 ordering + audit-on-drop, M1-789 precedent): a scaffolding-marker line whose id carries a dotted infochat token is still dropped wholesale AND the token joins the call's aggregated matches (the config-key pass runs before the scaffolding strip, so it sees the token first; untrusted text must not choose whether the deployment records the token's presence)."
  - "LlmOutputSanitizerTest.plainMentionsOfInfochatSurvive passes — FAILURE-MODE (over-breadth): prose mentioning `infochat` without the dotted shape (`infochat is a news bot.`, `See infochat. The bot…`) survives byte-identical; the category is the dotted config-key shape, never the bare word."
  - "LlmOutputSanitizerAuditRowIT.aConfigKeyMatchRowsWithItsExactCountAlongsideClosedListMatches passes — to-be-written: one sanitize() call over text carrying one config-key token (twice) and one closed-list token asserts exactly one LLM_OUTPUT_SANITIZED row per distinct token, the exact occurrence count under details_json.match_count and the token under match_kind, aggregated in the same call's row set (audit emission joins the ONE pipeline, P10); the fail-loud INSERT path stays as pinned by auditWriteFailureAbortsSanitizeFailLoud, unedited."
  - "The docs/spec/security.md §LLM output sanitizer amendment lands recording the new category — RULE-TEXT ONLY (§12: no dates, ticket IDs, or report citations; exact wording to the user for approval at implementation; this item authorizes the work). Draft shape: a 'further strip category: internal configuration identifiers' paragraph stating the dotted-shape rule, the space replacement (inserts a separator — can break a token apart, never build one), the placement between the plain-text downgrade and the scaffolding strip, the audit-on-match joining the aggregated rows, and the two stated residuals (NFKC resurfacing on a closed-list match — same class as the ordering rule's existing residual; free-form paraphrase of scaffolding — accepted, the wrapper's shape is not secret, its id is, the M1-779 'not a D21 break' framing). Verify: `grep -n 'internal configuration identifiers' docs/spec/security.md` plus the build green through mvn -B -pl infochat-provider -am verify, which runs ChatToolAllowlistSpecParityTest and DocumentedConfigKeyParityTest over the amended prose (P10 family — the amendment names NO dotted infochat.* key token, M1-789 precedent)."
  - "The M1-792 machinery absorbs the new pass deliberately (P12): docs/plan/m1/sanitize-caller-census.md records the pass (postcondition: sanitize() may replace dotted config tokens with a single space — shrinkage on that token class, no token synthesis), and LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions gains the config-key shape pin — AUTHORIZED modification (this ticket changes sanitize()'s documented postconditions; the pin is updated deliberately per M1-792's design, never silently)."
  - "Ingest path untouched: `git diff --name-only | grep -c IngestTranslationWorker` prints 0."
  - "mvn -B -pl infochat-provider -am verify is green."
test_plan:
  adds:
    - LlmOutputSanitizerTest.configKeyTokensAreStrippedFromLlmOutput
    - LlmOutputSanitizerTest.emphasisJoinedConfigKeyIsStillStripped
    - LlmOutputSanitizerTest.configKeyInsideAMarkerIdIsDroppedAndRowed
    - LlmOutputSanitizerTest.plainMentionsOfInfochatSurvive
    - HelpTopicCorpusTest.noTopicAnswerNamesARawConfigKey
    - LlmOutputSanitizerAuditRowIT config-key case
  modifies:
    - LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions (the config-key shape pin — authorized by acceptance item 9)
  preserves:
    - matchSetEqualsSpecClosedList, the four straddling-redaction tests, adversarialFlagScanIsLinearNotQuadratic, scaffoldingMarkersAreStrippedAndTheWrappedTextSurvives, markdownEmphasisIsDowngradedAndThematicBreaksAreDropped, probationTopicDurationMatchesApplicationConfig, and all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D21
reviews:
  - round: 1
    date: 2026-08-11
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "15 files changed, 262 insertions(+), 25 deletions(-)"
    findings: "2 low rework items, 0 critical/high"
    verdict_file: .scratch/tick-review-M1-815-r1.txt
  - round: 2
    date: 2026-08-11
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "16 files changed, 315 insertions(+), 28 deletions(-)"
    findings: "0 rework items; both round-1 items dispositioned SATISFIED"
    verdict_file: .scratch/tick-review-M1-815-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked_at: 2026-08-10
  result: >-
    tick-lint 0 BLOCKER / 0 WARN. All file:line citations re-verified in-tree:
    bundle lines en:875/cs:666/tr:760/es:890/ru:856, LlmOutputSanitizer.java
    :246/:260-261/:285-338/:58-59, HelpTopicCorpusTest.java:299-324,
    security.md:721-744, commands.md:1885-1890, HelpTopicCorpus.java:247,
    AdapterRouterIT.java:219. Census grep re-run clean (exactly 5 hits, every
    path has a row). Analysis P8-P12 all landed in Pitfalls. No replaces:, no
    in-flight ticket (module collision impossible), blocked_by empty. No
    ambiguity.
---

# M1-815: Strip internal config identifiers from LLM output

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E9, WARN): an adversarial
chat refusal leaked internal-sounding language past the sanitizer —
`infochat.probation.duration` and "The system's appended answer block". No
secret or command was exposed, and every marker/command probe was otherwise
stripped and audited. Verified ground truth that reframes the finding
(analysis D-2): the leaked token is a REAL config key AND the bot's own
reviewed product copy — `topic.probation.answer` names it verbatim in all
five bundles, served deterministically over the D69 path that bypasses the
sanitizer by design; the model's context held the copy and the refusal
echoed it. The sanitizer has no internal-identifier category. Shared
analysis: `analysis_ref:` (pitfalls P8-P12 below match it).

## Root cause

Two layers, both verified. (1) Source: product copy names a raw config key
— en.properties:875, cs.properties:666, tr.properties:760,
es.properties:890, ru.properties:856 (`topic.probation.answer`), pinned by
HelpTopicCorpusTest.probationTopicDurationMatchesApplicationConfig
(:299-324) and delivered over D69's post-sanitize path
(commands.md:1885-1890). (2) Enforcement: `sanitize()` composes link strip
→ markdown downgrade → scaffolding strip → closed-list strip
(LlmOutputSanitizer.java:246-270); no pass recognizes a dotted config-key
token, so a model echoing one ships verbatim. The second E9 item ("The
system's appended answer block") is free-form paraphrase — no deterministic
pass can reach it; it is recorded as the amendment's accepted residual, not
fixed.

## Pitfalls

Numbered consistently with the analysis document.

- P8: pass ordering is a security property (security.md:721-744) — the new
  pass runs AFTER the plain-text downgrade (so it sees emphasis-joined
  tokens) and BEFORE the scaffolding strip (which stays last among them, so
  it still sees markers a prior pass touched), all before the closed-list
  strip. The space replacement inserts a separator — it can break a token
  apart, never build one — but the placement keeps the ordering rule's
  invariant intact by construction.
- P9: the closed list is frozen (M1-789 P11) — no addition/removal/reorder
  in CLOSED_LIST; matchSetEqualsSpecClosedList unedited. The new category
  is a separate pass, not a closed-list entry.
- P10: one audit pipeline (counted, never throttled; aggregated per
  distinct token per call; fail-loud on INSERT failure —
  LlmOutputSanitizer.java:285-338). The new matches join the merged list at
  :260-261; no second row path, no second WARN shape.
- P11: the D69 path bypasses the sanitizer by design — the bundle rewording
  removes the key from that surface; all FIVE bundles move together (D43);
  the duration-value pin survives (it pins the value, not the key name).
- P12: the M1-792 census + postcondition pins absorb new passes
  deliberately — updated in this diff with authorization, never silently.

## Approach

Derived from `spec_refs:` — §Prompt-injection defenses' posture (the LLM
is a black box coaxed into attacker-chosen output) read against §LLM
output sanitizer's mandate over the full set of LLM-authored output
surfaces: an internal configuration identifier in delivered prose is
scaffolding rendering as content, the same defect class the scaffolding
category closes for wrapper markers.

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write both reproduction tests RED (sanitizer + corpus pin).
  2. Reword `topic.probation.answer` in all five bundles: the key-name
     parenthetical becomes a plain reference to the deployment's probation
     setting; the stated duration value stays (the value pin is
     load-bearing and unedited).
  3. `LlmOutputSanitizerCore`: add the internal-identifier pass — match
     `infochat` followed by two or more dot-separated lowercase segments
     (`[a-z][a-z0-9-]*` per segment, first char a letter; left boundary so
     `xinfochat.…` does not match), replace each token with a single
     space, return the matches; linear single scan (the §Trust boundaries
     item-9 hostile-reply posture — no backtracking hazard on an in-cap
     reply).
  4. `LlmOutputSanitizer.sanitize()`: compose the pass between the
     plain-text downgrade and the scaffolding strip; merge its matches into
     the existing list (WARN + rows fall out of the existing pipeline).
     Static delegate in the bean, matching the existing shape.
  5. The failure-mode tests (ordering ×2, over-breadth ×1) + the audit-IT
     case.
  6. The security.md amendment (rule-text only, §12 user approval of the
     exact wording at implementation).
  7. Census doc + postcondition pin updates (authorized).
  8. Full module verify.
- **Controls to preserve (§10):** the closed-list strip + aggregated WARN +
  LLM_OUTPUT_SANITIZED rows (same path, same unit — one sanitize call);
  the scaffolding strip's line-drop + audit-on-drop; the markdown
  downgrade's residuals; the four straddling tests; the fail-loud audit
  INSERT; the outbound-only posture (IngestTranslationWorker untouched);
  the D69 delivery mechanics; `matchSetEqualsSpecClosedList`.
- **Pitfall→mitigation:** P8→steps 3/4 + the two ordering failure-modes;
  P9→step 3 is a separate pass + the preserved parity test; P10→step 4's
  merged matches + the audit-IT case; P11→step 2 + the corpus pin;
  P12→step 7 + acceptance item 9.

## Definition of done

Every acceptance item green by its named test/verification: both
reproductions pass; the five bundles are key-free with the value pin
intact; the three ordering/over-breadth failure-modes pass;
aConfigKeyMatchRowsWithItsExactCountAlongsideClosedListMatches passes (the
audit row joins the one pipeline); the amendment lands rule-text-only; the
census and postcondition pins absorb the pass deliberately; the ingest path
is untouched; module verify green.

## Verification

- reproduction → configKeyTokensAreStrippedFromLlmOutput (RED on main) +
  noTopicAnswerNamesARawConfigKey (RED on main).
- P8 → failure-mode ordering tests: emphasisJoinedConfigKeyIsStillStripped
  (a pass placed before the downgrade fails it) and
  configKeyInsideAMarkerIdIsDroppedAndRowed (a pass placed after the
  scaffolding strip loses the row).
- P9 → matchSetEqualsSpecClosedList unedited; `git diff -- infochat-core`
  shows no CLOSED_LIST hunk.
- P10 → the LlmOutputSanitizerAuditRowIT config-key case (one row per
  distinct token, exact count, aggregated with closed-list matches of the
  same call).
- P11 → the five-bundle grep (0 hits) + probationTopicDurationMatchesApplicationConfig
  unedited + BundleLoaderTest green.
- P12 → the census doc row + the postcondition pin update (deleting either
  reds the meta test's enumeration).
- failure-mode (over-breadth) → plainMentionsOfInfochatSurvive — prose
  mentioning the bare word survives byte-identical; a greedy `infochat\S*`
  regex fails it.
- Non-vacuity: removing the pass reds the reproduction; moving it after
  the closed-list strip reds nothing visible BUT is barred by the
  composition-order assertion the ordering tests pin through their joined
  inputs; rewording fewer than five bundles reds the per-file grep.

## Out-of-scope

Named in `out_of_scope`: the closed list, the D69 delivery mechanics, the
ingest translator (outbound-only pass), free-form paraphrase (accepted
residual), prompt wording, and the M1-792 follow-ups. Two pre-existing
test surfaces are touched, both authorized in `acceptance`: the
postcondition pin (item 9) and — by rewording the bundles it reads —
HelpTopicCorpusTest's probation pin is left UNEDITED and green (the value
assertion survives by construction; verified at :299-324). No other
pre-existing test is modified.

## Census

Class: user-facing surfaces carrying raw `infochat.*` config-key tokens.
Re-runnable enumeration:
`grep -rn 'infochat\.' infochat-provider/src/main/resources/bundles/`
(re-run at start; 2026-08-10 result: exactly 5 hits).

| Site | Disposition |
|---|---|
| en.properties:875 topic.probation.answer | FIXED here — reworded, value kept |
| cs.properties:666 topic.probation.answer | FIXED here — reworded, value kept |
| tr.properties:760 topic.probation.answer | FIXED here — reworded, value kept |
| es.properties:890 topic.probation.answer | FIXED here — reworded, value kept |
| ru.properties:856 topic.probation.answer | FIXED here — reworded, value kept |
| HelpTopicCorpus.java:247 CodeFactPin pinKey | out-of-scope — internal code label, never served to a user |
| AdapterRouterIT.java:219 assertion message | out-of-scope — test text, never delivered |
| Operator docs (SETUP_GUIDE.md, design notes) | out-of-scope — operator-facing surfaces legitimately name keys; this ticket's class is user-facing chat text and LLM-authored output |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-815-sanitizer-internal-language-leak.md
```

## Round 1 rework

REWORK ITEMS (verbatim from `.scratch/tick-review-M1-815-r1.txt`):

1. FINDING 1: Replace the ASCII-only left boundary at `LlmOutputSanitizerCore.java:876-877` with the documented letter/digit boundary and add the named Unicode-prefix assertion, evaluated by `LlmOutputSanitizerTest.unicodeLetterBeforeInfochatDoesNotStartToken`.
2. FINDING 2: Add the final adapter-delivery assertion for a model reply containing the dotted config token, evaluated by `InboundRouterChatModeIT.configKeyTokenIsAbsentFromFinalizedChatReply`.
