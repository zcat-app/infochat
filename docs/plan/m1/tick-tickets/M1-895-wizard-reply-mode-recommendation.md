---
id: M1-895
title: "Wizard: per-model chat reply-mode recommendation (D79)"
status: pending
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  LlamacppWiringTest.wizardAsksAndWritesChatReplyMode (to-be-written — `start`
  writes it and runs it RED before any fix code, workflow §0): a drive of the
  real prod/scripts/4-llm.sh (fake docker/curl, pinned-default llamacpp
  branch) answering the reply-mode prompt with bare Enter asserts
  `infochat.chat.reply-mode=translate` in the generated
  application.properties and the printed recommendation — RED today because
  no prod script reads or writes the key (probe: `grep -r 'reply-mode'
  prod/` returns nothing, verified 2026-08-20), so an operator can set the
  chat reply-mode deployment default only by hand-editing
  application.properties while the /image side has had its D78 wizard
  recommendation since M1-851.
analysis_ref: self
blocked_by: [M1-886]
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
  - SETUP_GUIDE.md
  - docs/spec/decisions.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    prod/switch-llm.sh — post-setup re-routes change the chat model without
    re-asking reply-mode; the key is operator-owned advice-driven state and
    survives a re-route (the recommendation is advice, never a gate, D79).
    A switch-llm ask is a possible follow-up ticket, not a rider (§1).
  - >-
    infochat-provider/src/main/resources/application.properties — the shipped
    default stays `translate` (the family P10 zero-drift rule, M1-885); the
    wizard writes operator intent into the RUNTIME file, the committed
    default does not move.
  - >-
    Any Provider/adapter Java production code — ChatReplyModeResolver
    already reads `infochat.chat.reply-mode` (ChatReplyModeResolver.java:20,
    :28); this ticket builds the wizard surface only.
  - >-
    docs/spec/llm.md, docs/spec/commands.md, docs/spec/security.md — the
    wizard-recommendation sentence lives in the D79 decisions row alone,
    mirroring D78 (whose wizard sentence exists only at decisions.md:97);
    security.md:2258-2273 is already mode-conditional and the recommendation
    changes no exposure class (native only ever SKIPS a translator leg).
  - >-
    docs/measurement/direct-chat-e2e.md — a frozen historical record; the
    table seeds FROM it, the record is never edited.
  - >-
    Fuzzy model-name matching (basename/case-fold/substring) in the table
    lookup — the falsified D79-registry shape; a lookup miss costs nothing
    but the conservative fallback.
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.wizardAsksAndWritesChatReplyMode (written and run RED at start) — the pinned-default llamacpp drive (one appended Enter for the new prompt) writes `infochat.chat.reply-mode=translate` (the pinned gemma-4-E4B GGUF is unmeasured, so the conservative translate is recommended), the output prints the recommendation naming the model, and exactly one `^infochat.chat.reply-mode=` line exists in the written props."
  - "Operator override (D79/D78 ownership): LlamacppWiringTest.replyModeOverrideWritesNative — answering `native` at the prompt writes `infochat.chat.reply-mode=native` even though the table recommends translate (the recommendation is advice; the operator owns the final value)."
  - "FAILURE-MODE (closed-set validation, the 4b-image.sh:338-342 shape, §7 boundary check): LlamacppWiringTest.replyModeInvalidAnswerFailsAndWritesNothing — feeding `maybe` at the prompt exits non-zero with a FAIL message naming the valid answers, and the runtime props carry NO reply-mode line (an invalid answer is never silently coerced to a default)."
  - "Re-run semantics (P5): LlamacppWiringTest.replyModeRerunDefaultsToRecommendationAndDisclosesCurrent — pre-seed the runtime application.properties with `infochat.chat.reply-mode=native`, drive with bare Enter at the prompt: the written value is the recommendation (translate — a stale value never survives, the 4b re-ask shape), the prompt output names the currently-set value (`native`) so a deliberate prior choice is never silently reverted, and set_prop idempotency keeps exactly one reply-mode line."
  - "Remote branch: RemoteLlmWiringTest.replyModeAskedAndWrittenForRemoteModel — the remote drive (one appended Enter) writes `infochat.chat.reply-mode=translate` (an operator-entered remote model is unmeasured), proving the single shared ask fires on every backend branch after chat-model selection."
  - "Every pre-existing LlamacppWiringTest and RemoteLlmWiringTest drive is green with EXACTLY ONE appended stdin line each (the reply-mode answer; P1 — the drives feed positional stdin and the script dies at EOF under set -e, LlamacppWiringTest.java:98-101). No pre-existing assertion is modified, weakened, or retargeted — the only edit to pre-existing test code is the appended input line."
  - "Spec amendment rides this diff (the M1-851 shape; §12 exact-wording approval at implementation BEFORE the script change lands): the D79 row (docs/spec/decisions.md:98 — the POST-M1-886 operator-owned decisive-switch text) gains the wizard-recommendation sentence matching D78's shape: the setup wizard recommends the value per measured chat model from the committed in-language measurement record — translate for a model the record fails in any measured language, and always for an unmeasured model — and the operator owns the final value. Rule-text only: no dates, ticket IDs, or report citations in the row's decision text (§12). Verify: `grep -n 'wizard' docs/spec/decisions.md` shows the sentence inside the D79 row, and `git diff docs/spec/` is confined to that row."
  - "SETUP_GUIDE.md §Step 4 documents the ask (mirroring the Step 4b 'Prompt translation (per model)' paragraph, SETUP_GUIDE.md:384-397): the per-model recommendation and its conservative rule, the operator override (edit `infochat.chat.reply-mode` in prod/runtime/application.properties + restart, or re-run the step, which re-asks and rewrites), and the existing-deployment adoption note (an install that never re-runs step 4 keeps the shipped default `translate` — today's behavior, the safe posture). Verify: `grep -n 'reply-mode' SETUP_GUIDE.md` shows the new paragraph."
  - "Zero-drift (P3): `git diff infochat-provider/src/main/resources/application.properties` is empty — the shipped `infochat.chat.reply-mode=translate` (application.properties:508) does not move."
  - "mvn verify from repo root is green (engineering-rules §5) — including DocumentedConfigKeyParityTest (the key is already built AND documented; no new key, no exemption entry)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  modifies:
    - >-
      LlamacppWiringTest — every pre-existing drive's stdin string gains
      exactly ONE appended answer line for the new reply-mode prompt
      (authorized by acceptance item 6: the drives feed positional stdin and
      a new unconsumed prompt kills the script at EOF under set -e, so the
      drives must supply the answer; no assertion changes), plus the four
      NEW methods named in acceptance items 1-4.
    - >-
      RemoteLlmWiringTest — the same one-appended-line drive updates
      (acceptance item 6), plus the NEW method named in acceptance item 5.
  notes:
    - >-
      The new tests land in the two existing wiring classes (the established
      drive-the-real-script harness, fake docker/curl, Linux-gated), so no
      new test file is added.
spec_refs:
  - docs/spec/decisions.md §Decisions log
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs:
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

# M1-895: Wizard: per-model chat reply-mode recommendation (D79)

## Context

D78 shipped the /image half of the wizard-recommendation shape:
prod/scripts/4b-image.sh carries a per-model recommendation table
(`MODEL_TRANSLATE_RECOMMENDED`, 4b-image.sh:86-90), an ask after the model
pick (`choose_translate_prompt`, :327-343; bare Enter and `--defaults` take
the recommendation), and writes `infochat.image.translate-prompt` via
`set_prop` (:893 local, :948 remote). The chat side never got its half:
**no prod script reads or writes `infochat.chat.reply-mode`**
(`grep -r 'reply-mode' prod/` returns nothing, verified 2026-08-20), so an
operator can set the chat reply-mode deployment default only by hand-editing
application.properties. The D79 row (docs/spec/decisions.md:98, the
POST-M1-886 operator-owned decisive-switch text, present in this checkout)
makes the committed measurement record "advice for that choice" — but with
no wizard surface, that advice reaches the operator only if they go read the
record. User-directed fix: mirror the 4b pattern in prod/scripts/4-llm.sh —
recommend `native`/`translate` per chat model from the committed
bar-clearing record, operator confirms or overrides, the script writes the
key via `set_prop`; the D79 row gains the wizard-recommendation sentence in
the same diff (spec-first within the ticket, §12 wording approval). The
decision at M1-886's §12 wording review (2026-08-20) was arm (a) PLUS this
follow-up ticket: M1-886's D79 row lands with NO wizard sentence
(record-as-advice only), and the wizard-claim sentence rides THIS ticket's
diff alongside the script surface (acceptance item 7).

## Root cause

Feature gap, not a defect: the D78 wizard pattern was built only for
4b-image.sh (M1-851's wizard half). Verified ground truth:

- **The key and its reader exist.** `ChatReplyModeResolver.CONFIG_KEY =
  "infochat.chat.reply-mode"`, default `translate`
  (ChatReplyModeResolver.java:20, :28); shipped default
  `infochat.chat.reply-mode=translate` (application.properties:508). The
  runtime file the wizard generates overrides the shipped default — the
  wizard write is the only missing piece.
- **Where the ask belongs.** 4-llm.sh selects the chat model inside each
  backend branch: ollama — profile-derived `$chat_model` (:390-396, written
  :456-457); llamacpp — the generative GGUF `$gen_file` (:486-508); remote —
  the operator-entered `$remote_model` (:764-772). All three branches
  converge on the shared tail (:842-881) that asks the four timing questions
  via `prompt_timing` and writes them with `set_prop`. The ask belongs in
  that tail, after chat-model selection in every branch — one shared site,
  fed by a per-branch captured model variable, placed AFTER the four timing
  prompts so the existing positional-stdin drives each gain exactly one
  trailing answer line (P1).
- **Wizard re-run conventions.** 4-llm.sh has NO keep/switch/disable gate
  (contrast 4b-image.sh:448-485): a re-run re-provisions and re-asks
  everything — the timing prompts re-ask with the recommendation as default,
  and `set_prop` (:97-104) replaces rather than duplicates each line. So the
  reply-mode ask fires on every re-run, and the default is the per-model
  recommendation (both precedents: `prompt_timing` :110-121 defaults to the
  recommendation; 4b's choose_translate_prompt :327-343 defaults to the
  table). 4b's comment states the rule: "A re-run re-asks … a stale value
  never survives" (:324-326). Because 4-llm.sh re-asks on EVERY re-run
  (including same-model re-provisions, where 4b's gated flow would have
  exited at `keep`), the prompt additionally prints the currently-set value
  when one exists and differs from the recommendation — a deliberate
  operator choice is disclosed, never silently reverted by a bare Enter (P5).
- **The recommendation-table seed format** (the `MODEL_TRANSLATE_RECOMMENDED`
  analogue): a `declare -A MODEL_REPLYMODE_RECOMMENDED` beside the other
  constants, keyed by the EXACT model string the wizard writes per branch
  (`$chat_model` / `$gen_file` / `$remote_model`), with the conservative
  fallback `:-translate` in the lookup (4b's `:-true` shape, :329).
- **How the record's FAIL cells map to translate.** The committed
  bar-clearing matrix (docs/measurement/direct-chat-e2e.md:547-555):
  gemma-4-26b-a4b × cs/ru/tr PASS, × en/es FAIL. `infochat.chat.reply-mode`
  is ONE deployment-wide default and the wizard cannot know the deployment's
  scope languages, so the mapping is the D78 conservative shape: a model the
  record fails in ANY measured language gets `translate` recommended, and an
  unmeasured model always gets `translate` recommended. Seed:
  `[gemma-4-26b-a4b]=translate` — behaviorally equal to the fallback, seeded
  anyway (the 4b table does the same: mage/zimage seed `true`, equal to
  their fallback) because the table is the record's committed mapping and
  the place a future measurement updates deliberately. The printed
  recommendation for a seeded model names the per-language detail
  (cs/ru/tr PASS, en/es FAIL) so an operator with Czech/Russian/Turkish
  scopes can make an INFORMED override — advice, never a gate.
- **No wizard default hits the measured model.** The pinned llamacpp GGUF
  is gemma-4-E4B (4-llm.sh:58-60), the ollama chat models are the §5.7
  profile tags (llama3.1:8b / llama3.2:3b / llama3.2:1b), remote models are
  operator-entered — none is the measured gemma-4-26b-a4b, so every default
  path recommends translate today. That is the conservative shape working as
  intended, not a bug; the ask still matters because it records operator
  intent (the live deployment chose native against advice — the D78
  krea-override shape).
- **The D79 row has no wizard sentence yet.** `grep -n 'wizard'
  docs/spec/decisions.md` hits D49/D54/D56/D57/D77/D78, not D79 — D78's
  wizard sentence lives only in its row (:97), so the mirrored sentence goes
  in the D79 row and nowhere else in spec prose.

Brief-vs-tree discrepancies (noted per ground-truth discipline): the
prior-art block lists M1-851 under `docs/plan/m1/tickets/`; it actually
lives at `docs/plan/m1/tick-tickets/M1-851-image-prompt-translation-skip-2.md`
(read there). The measurement matrix rows name the model "gemma"; the full
ID gemma-4-26b-a4b is the deployment's model ID per the record's lock
section and the M1-885/886 analysis. `docs/plan/m1/redteam/` does not
exist — no redteam findings to carry.

## Pitfalls

- P1: **Positional-stdin drives die at EOF.** LlamacppWiringTest and
  RemoteLlmWiringTest drive the real script with exact positional stdin;
  every drive ends with `ACCEPT_TIMING_DEFAULTS` ("\n\n\n\n") and
  "every wizard drive must supply them or the script dies at EOF under set
  -e" (LlamacppWiringTest.java:98-101; M1-826's no-new-stdin-prompt lesson,
  the switch-llm-stdin-is-positional memory). A new unconsumed prompt fails
  ~30 pre-existing drives far from the edit. The prompt goes LAST in the
  flow (after the timing block), each drive gains exactly one appended
  answer line, and the ticket carries the §8 test-modification
  authorization (acceptance item 6).
- P2: **Wizard-claim truthfulness / spec-first (M1-886's P7).** The D79
  row must not claim a wizard recommendation that does not exist; the
  sentence and the script land in the SAME diff, amendment drafted first
  (the M1-851 shape), exact wording user-approved (§12), rule-text only —
  no dates, ticket IDs, or report citations in the row's decision text.
- P3: **Conservative mapping + zero-drift default (the family zero-drift
  rule, M1-885).** A FAIL cell in any measured language → translate;
  unmeasured → translate; the shipped default `application.properties:508`
  stays `translate` — flipping it in the same diff is scope creep (§1) and
  silently changes every existing deployment.
- P4: **Model-name string matching.** The table lookup is EXACT-match
  against the per-branch model variable, with the `:-translate` fallback.
  No basename/case-fold/substring matching to make the seed entry hit GGUF
  filenames — that is the falsified D79-registry shape, and here a miss
  costs nothing but the conservative fallback (advice, not a gate).
- P5: **Re-run silent-revert.** 4-llm.sh has no keep-gate, so the ask
  fires on every re-run; a bare Enter takes the recommendation, which
  would silently revert a deliberate `native` on an unrelated re-run.
  Mitigation: the recommendation stays the default (both precedents), and
  the prompt prints the currently-set value when set and differing — the
  script's established current-state-echo style ("using
  INFOCHAT_LLM_API_KEY from secrets.env (already recorded)", :778).
- P6: **--defaults non-interactive path.** `--defaults` reaches only the
  ollama branch (llamacpp and remote refuse it, :467-469 / :667-669);
  there the ask takes the recommendation non-interactively and echoes it —
  4b's choose_translate_prompt `--defaults` arm (:330-334).
- P7: **Wrong key namespace.** The key is `infochat.chat.reply-mode`
  (Provider-side, ChatReplyModeResolver.java:20), NOT `infochat.llm.chat.*`
  — the timing block's namespace. Writing `infochat.llm.chat.reply-mode`
  would be a dead line no code reads (the M1-387 phantom-key class).
- P8: **Recommendation-table rot (§11).** The table's comment states the
  conservative rule and points at the record + D79 with one stable
  reference each (no chronicle), so a future bar-clearing re-measurement
  re-seeds deliberately rather than the table silently drifting from the
  record it claims to cite.

Prior-finding disposition (the prior-art rule): M1-851's refined wizard
lessons CARRIED (recommendation-not-mandatory, operator override is a
disclosed contemplated posture, re-run re-asks, unmeasured → safe
posture); M1-826's positional-stdin finding CARRIED as P1; M1-886's P7
resolved as arm (a) PLUS this follow-up ticket (the wizard-claim sentence
rides this diff — acceptance item 7); M1-380/383/387's wizard contracts
CARRIED (set_prop idempotency, §7.7.1 script shape, real-keys-only);
M1-885/886's zero-drift rule CARRIED as P3, their end-state-calibration
lesson applied (the new tests pin the END state — key written per the
decisive switch, no intermediate gate behavior exists to pin).

## Approach

Derived from `spec_refs`: the D79 row (as amended by M1-886) owns the
operator-owned decisive switch with the record as advice; D78
(decisions.md:97) is the ownership+wizard shape being mirrored; §7.7.2 is
the wizard-step contract the script implements.

- **Files to touch:** `files_scope` — one script, two existing test
  classes, the operator guide, the one spec row.
- **Steps, in order:**
  1. Draft the D79-row wizard sentence (acceptance item 7) and take it to
     the user with a plain-English account (§12): it adds a
     wizard-recommendation commitment matching D78's, changes nothing else
     in the row. Lands in the same diff as the script (P2).
  2. Script (P3, P4, P7): add `MODEL_REPLYMODE_RECOMMENDED`
     (`[gemma-4-26b-a4b]=translate`, conservative-rule comment citing D79 +
     the record path) beside the model constants; in each backend branch
     capture the chat-model string into one shared variable
     (`reply_mode_model="$chat_model"` / `="$gen_file"` / `="$remote_model"`);
     add `choose_reply_mode` mirroring `choose_translate_prompt`
     (4b-image.sh:327-343): exact-match lookup with `:-translate` fallback,
     `--defaults` arm echoes and takes the recommendation (P6), closed-set
     validation `native|translate` with a FAIL on anything else, and the
     P5 current-value disclosure when the key is already set and differs.
  3. Tail (P1): call `choose_reply_mode` AFTER the four `prompt_timing`
     reads, then `set_prop infochat.chat.reply-mode` beside the timing
     writes (:877-880).
  4. Tests (workflow §0 — the RED reproduction first): the five new
     methods named in acceptance items 1-5, then the one-appended-line
     update to every pre-existing drive (item 6, the §8 authorization).
  5. SETUP_GUIDE.md §Step 4 paragraph (item 8).
- **Controls to preserve (engineering-rules §10):** the change ADDS a prompt
  and a write to the tail; it reroutes nothing. Enumerated anyway: the four
  timing prompts and their recommendation defaults are byte-untouched; the
  remote-branch privacy disclosure block (:696-729) and its print-before-
  commit ordering are untouched; `set_prop`/`set_secret`/`dotenv_escape`
  semantics unchanged; no audit/sanitize surface exists in a setup script;
  every pre-existing wiring-test assertion survives (only stdin strings are
  extended); DocumentedConfigKeyParityTest stays green with no new key and
  no exemption.
- **Pitfall→mitigation:** P1→step 3's trailing placement + item 6; P2→step
  1 + item 7; P3→step 2's table/fallback + item 9; P4→step 2's exact-match
  lookup + item 1/2 (a fuzzy match would still pass item 1, so item 2's
  override and the custom-URL drive pin the exact-key behavior); P5→step
  2's disclosure + item 4; P6→step 2's `--defaults` arm (covered by the
  ollama path being drive-free — the `--defaults` echo mirrors
  `prompt_timing`'s skipped-prompt posture); P7→item 1 asserts the exact
  key string; P8→step 2's comment discipline.

Alternatives considered (rejected): per-branch asks (three prompt sites —
drift risk, triple the stdin churn; rejected for the single tail ask);
default-to-existing-value on re-run (a stale value survives a model
switch; deviates from both precedents; rejected for rec-default +
disclosure); fuzzy model matching (P4); adding the ask to switch-llm.sh
(out_of_scope, §1); seeding `native` for gemma on PASS-majority (violates
the conservative shape — en/es FAIL, and the key is deployment-wide while
the wizard cannot know scope languages); script-only with no spec sentence
(M1-886 P7 arm (b) pairs the sentence with the surface).

## Definition of done

The reproduction and its four sibling wiring tests green; every
pre-existing drive green with exactly one appended stdin line and no
assertion touched; the D79 row carries the user-approved wizard sentence
(rule-text only, diff confined to that row); SETUP_GUIDE §Step 4 documents
the ask, the override, and the existing-deployment adoption note;
application.properties untouched; `bash -n prod/scripts/4-llm.sh` and full
`mvn verify` green.

## Verification

- P1 → acceptance item 6 (all pre-existing drives green) — a drive missing
  the new answer dies at EOF with rc != 0, failing loudly.
- P2 → item 7's greps + the §12 approval record.
- P3 → item 1 (unmeasured pinned model → translate), item 9 (empty
  application.properties diff); mutation check: a native-recommending
  table entry for gemma-4-26b-a4b reds item 1's seeded-model sibling
  assertion in item 4's drive.
- P4 → item 2 (explicit operator `native` written verbatim) and the custom
  -GGUF-URL drives (item 6) asserting translate for an arbitrary filename.
- P5 → item 4: pre-seeded `native` + bare Enter → translate written, the
  output names the previous `native`, exactly one key line.
- P6 → covered by construction (only ollama takes `--defaults`); the
  `--defaults` arm is the 4b shape verified by read at review.
- P7 → item 1 asserts `infochat.chat.reply-mode` exactly;
  `grep -n 'infochat\.llm\.chat\.reply-mode' prod/scripts/4-llm.sh`
  returns nothing.
- P8 → review reads the table comment against §11 (one stable reference,
  no chronicle).
- Failure-mode coverage (mandatory class): item 3 feeds the hostile input
  (`maybe`) and asserts loud failure + nothing written; item 4 feeds the
  re-run edge (conflicting stored value) and asserts the protected
  behavior (recommendation default + disclosure); item 5 feeds the
  unmeasured-remote-model edge.
- acceptance items 8-10 → their named greps / `git diff` / the verify log.

## Out-of-scope

Named in `out_of_scope`: switch-llm.sh (the operator-owned key survives
re-routes; a switch-side ask is a follow-up); the shipped
application.properties default (P3); all Java production code (the reader
exists); llm.md / commands.md / security.md (the sentence lives in the D79
row alone, D78's shape; security.md:2258-2273 is already mode-conditional
and a native recommendation only ever SKIPS a translator leg — exposure
never grows); the frozen measurement record; fuzzy matching. Pre-existing
test modification, authorized per §8: ONLY the appended stdin answer line
in the two wiring classes' pre-existing drives (acceptance item 6) — a new
prompt the script now reads must be supplied or `read` hits EOF and
`set -e` kills the script; no assertion is added to, removed from, or
altered in any pre-existing test method.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-895-wizard-reply-mode-recommendation.md
```
