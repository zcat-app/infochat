---
id: M1-879
title: "Strip deletions must not join privileged command tokens"
status: done
created: 2026-08-17
last_updated: 2026-08-18
flow: tick
reproduction: >-
  ChatAgentTest.aPrivilegedCommandAssembledAcrossAStripDeletionNeverReachesTheReply
  (written and run RED 2026-08-17: the iteration-cap delivery path feeds the
  final text '/ba<|tool_call>call:x{old}n' and asserts the reply contains no
  '/ban'; on main the reply IS '/ban' — 4 failures across the reproduction
  and the three other failure-mode tests, the degrade guard green). The probe
  half was already run RED 2026-08-17 against main 36d034fb, zero repo changes, strip code byte-identical on current main 280c3194 (the 36d034fb..main delta is docs only),
  reflection into compiled infochat-provider/target/classes):
  ChatAgent.stripToolCalls("/ba<|tool_call>call:x{old}n") returns "/ban". The input contains NO
  contiguous "/ban"; the strip's deletion-join assembled it. On the
  delivery path the same shape ships as the reply: ChatAgent.java:580
  runs outputSanitizer.sanitize BEFORE stripToolCalls (:585), the
  closed-list pass matches no contiguous "/ban" in
  "/ba<|tool_call>call:x{old}n" (verified: the "/ban" pattern needs the
  literal contiguous — LlmOutputSanitizerCore.java:240-252 — and the
  bytes between "ba" and "n" are the native fragment), so nothing is
  redacted, no LLM_OUTPUT_SANITIZED row is written, and the delivered
  reply is "/ban" unredacted. Source: M1-875 round-1 review
  recommended-new-ticket (.scratch/tick-review-M1-875-r1.txt:105-124,
  carried verbatim at -r2.txt:43-59; TOUCHED-BY-THIS-DIFF: no —
  pre-existing single-pass joining), user filed 2026-08-17.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
  - docs/spec/security.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE DISPATCH GRAMMAR and TOOL_INSTRUCTIONS (M1-870 census) — dispatch
    requires the brace by design; only the final-reply strip's output is
    in scope.
  - >-
    LlmOutputSanitizer's pass set and CLOSED_LIST (frozen — M1-115,
    M1-789 P11): no addition/removal/reorder;
    LlmOutputSanitizerTest.matchSetEqualsSpecClosedList untouched. The
    fix is strip-side.
  - >-
    PIPELINE REORDERING: moving sanitize after the strip, re-running
    sanitize (or the closed-list pass) over the strip output, or a
    joint sanitize/strip fixpoint — rejected fix shapes (P1), nothing in
    this ticket moves the sanitize call at ChatAgent.java:580 or the
    strip's position at :585.
  - >-
    Removing the M1-875 fixpoint loop or the kept-openers hand-off — they
    stay unchanged as a backstop (P9); retiring them is a separate
    design change argued on its own evidence.
  - >-
    The other LLM-authored surfaces' detectors (CategoryRollupGenerator
    / SummaryProseGenerator refusal checks, the translate leg's
    TranslationPipeline sanitizer-2 at TranslationPipeline.java:276) —
    different pipeline stages, M1-791/M1-793 dispositioned; this ticket
    changes only stripToolCalls' output.
  - >-
    The streamed live-text surface (M1-849 owns it) — the terminal
    finalize carries the full post-pipeline text (security.md §Streamed
    surfaces), which inherits this fix; no streaming code is touched.
  - >-
    Collector surfaces (Stage1RegexSet, IngestTranslationWorker) —
    different boundary, no strip there.
acceptance:
  - "ChatAgentTest.aPrivilegedCommandAssembledAcrossAStripDeletionNeverReachesTheReply (the reproduction, written and run RED at start) passes — feeds the delivery path (identity sanitizer seam, sanitizerOutput = null, ChatAgentTest.java:1901) the final text '/ba<|tool_call>call:x{old}n' and asserts the reply contains no '/ban'; on main the reply IS '/ban' (probe above). The same test asserts the reply is not blank (the assembled-token text is replaced, not emptied)."
  - "ChatAgentTest.aMultiWordClosedListEntryCannotBeAssembledAcrossAStripDeletion passes — FAILURE-MODE (P2): stripToolCalls('/invite' + '<|tool_call>call:x{old}' + ' create') returns '/invite… create' exactly, and the canonicalized output does not match the closed-list pattern for '/invite create' (LlmOutputSanitizerCore.CLOSED_LIST_PATTERNS at CLOSED_LIST.indexOf('/invite create')). Ground truth: the pre-strip text does NOT match (the bytes between the words are the fragment, not whitespace — compileClosedListPattern's \\s+ separator, LlmOutputSanitizerCore.java:247-251). A whitespace-separator or join implementation fails red."
  - "ChatAgentTest.aFlagEntryCannotBeAssembledAcrossAStripDeletion passes — FAILURE-MODE (P2): stripToolCalls('/list-sources' + '<|tool_call>call:x{old}' + ' --all') returns '/list-sources… --all' exactly, and the canonicalized output does not match the flag entry (redactFlagEntry/findCommandToken requires a separator after the command word, LlmOutputSanitizerCore.java:346-359; '…' is not one). Ground truth: the pre-strip text does NOT match either (the char after the command word is '<'); the SPACE after the span is the discriminating byte — the glued variant '/list-sources<span>--all' is safe even under a join (post-join '/list-sources--all' has no separator after the command word)."
  - "ChatAgentTest.anAssembledTokenSurvivesIntakeCanonicalizationSplit passes — FAILURE-MODE (P8): LlmOutputSanitizerCore.canonicalizeForMatching(ChatAgent.stripToolCalls('/ba<|tool_call>call:x{old}n')) contains no '/ban' — the copy-paste-then-dispatch property (security.md §LLM output sanitizer, canonical-form matching: a token 'parses as a privileged command the moment a reader copy-pastes the bot's line back in'). An invisible separator (zero-width, bidi control) fails red because the canonicalizer strips it and the join re-forms."
  - "ChatAgentTest.aMarkersOnlyReplyStillTakesTheEmptiedDegrade passes — GUARD (P5): final text '<|tool_call>call:searchPosts' degrades like an assistant failure (BundleKeys.ERROR_CHAT_UNAVAILABLE, null commit, null notice; llm.md §Failure handling) — a strip output holding nothing outside whitespace and elision separators counts as empty; a lone '…' must never deliver. The pre-existing sanitize-side pins aMarkersOnlyReplyIsNeverDeliveredEmptied (EmptyLlmReplyDeliveryIT.java:77-105) and aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure (ChatAgentTest.java:766-782) pass UNCHANGED."
  - "The M1-875/M1-870 exact-output strip pins are updated to the separator-era expectations and stay green — AUTHORIZED modifications (P11, named with new values in Out-of-scope): deletionJoinCannotAssembleToolCallMarkers ('TOOL_…CALL: y {}', '<|tool_…call>call:z', 'TOOL…_…CALL: y {}'), deletionJoinInADropThroughPassIsStillReScanned ('TOOL_…CALL: q {}', 'Quote <|tool_call>…call:y '), bareOpenerRulingSurvivesTheReScan ('Quote <|tool_call>…call:y'), stripReScanTerminatesOnAdversarialNestedInput (absence-of-marker assertions; spin leg '<|tool_call>'×49 + '…'), bracelessNativeCallMarkerIsStrippedFromFinalReplies ('Here you go.\n…'; persisted turn equals delivered), bracelessTokenStripRemovesExactlyOpenerCallAndName ('Answer.\n…\nMore prose here.'; the truncated '<|tool_call>call:' case still returns ''), bracelessTokenDoesNotSwallowALaterUnrelatedBrace ('A … then {json} later'), bracelessTokenAssembledBySanitizationIsStripped ('Clean text. …'). Probe: each named method green in mvn verify, and each updated leg red under a join-restoring mutation of the removal sites."
  - "The docs/spec/security.md §LLM output sanitizer amendment lands — RULE-TEXT ONLY (§12: no dates, ticket IDs, or report citations; the exact wording goes to the user for approval at implementation time; this item authorizes the work). Draft shape (protocol-token-detector paragraph): the TOOL_CALL: strip is itself a deleting pass whose removals are never joins — each removed span is replaced by an elision separator that is neither whitespace nor a letter, digit or hyphen, so a strip deletion can break a token apart but never build one and no command or marker can re-form across a removed span after the closed-list strip has run; a strip output holding nothing outside whitespace and elision separators counts as empty. Verify: grep -n 'elision' docs/spec/security.md plus mvn verify running ChatToolAllowlistSpecParityTest and DocumentedConfigKeyParityTest over the amended prose (the amendment names no infochat.* token)."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 records the separator rule next to the fixpoint sentence (:775-783) — probe: grep -n 'elision\\|separator' docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - ChatAgentTest: the five new named methods (acceptance items 1-5)
  modifies:
    - ChatAgentTest: the eight M1-875/M1-870 strip tests named in acceptance item 6 — authorized; every other pre-existing test is preserved
  preserves:
    - all tests currently green on main — in particular the sanitize-side markers-only pins, residualNativeDialectIsStrippedFromFinalReplies (contains/startsWith assertions, ChatAgentTest.java:418-459), bareOpenerInProseStaysByteIdentical and proseQuotingTheDialectOpenerIsNotDispatched (no removal, byte-identical), the ChatAgentToolArgsTest strip tests (absence-based, :86-120), matchSetEqualsSpecClosedList, the LlmOutputSanitizerAuditRowIT suite
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/llm.md §Failure handling
decision_refs:
  - D21
reviews:
  - round: 1
    date: 2026-08-18
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY FAIL, SCOPE PASS"
    diff_stats: "tracked: 6 files +168/-36 (4 files_scope paths + 2 workflow artifacts: ticket bookkeeping, STATUS-TICK)"
    rework_items: 2
    verdict_file: .scratch/tick-review-M1-879-r1.txt
  - round: 2
    date: 2026-08-18
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "round-2 fix hunks: 2 files +7/-8; full working tree: 6 files +201/-42"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-879-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-879: Strip deletions must not join privileged command tokens

## Context

The tool-call strip in ChatAgent can assemble a privileged slash-command
token across one of its own deletions, after the closed-list redaction has
already run on the pre-join text. The M1-875 round-1 reviewer recorded it
as a recommended-new-ticket (.scratch/tick-review-M1-875-r1.txt:105-124;
carried verbatim at -r2.txt:43-59; TOUCHED-BY-THIS-DIFF: no — pre-existing
single-pass joining), and the user filed it 2026-08-17. Verified probe
(reproduction frontmatter): `ChatAgent.stripToolCalls("/ba<|tool_call>call:x{old}n")`
returns `"/ban"` (main 36d034fb; strip code byte-identical on current main 280c3194) — hand-traced against ChatAgent.java:1160-1236:
the single pass appends `"/ba"`, skips the balanced native span, appends `"n"`;
the fixpoint wrapper (:1136-1152) re-scans, finds nothing, returns `"/ban"`.
On the delivery path (ChatAgent.java:580 → :585) the sanitizer saw
`"/ba<|tool_call>call:x{old}n"` — no contiguous `/ban`, no redaction, no
`LLM_OUTPUT_SANITIZED` row — and the delivered reply is `"/ban"`.

The residual bound the observation cites — dispatch still requiring
`is_admin=true`, the same bound security.md:597-609 accepts for the
across-fields split — is real, but it does not cover this case: the
across-fields residual is a property of the sanitize UNIT (two authors'
fields never share one sanitize input), while this assembly happens inside
ONE LLM reply, the unit the sanitizer already treats as single-author. The
pass-ordering rule's own rationale (security.md:757-781: "a pass placed
downstream of the redaction could assemble a privileged token out of text
the closed-list match never saw, and that token would ship unredacted AND
unaudited") describes exactly this pipeline shape — the strip is a
deleting pass downstream of the redaction — so this is a spec-promise
breach, not an accepted residual.

## Root cause

Verified, two halves:

1. **The strip deletes and joins.** `stripToolCallsSinglePass`
   (ChatAgent.java:1160-1236) appends the bytes before a matched span,
   skips the span, and continues scanning the ORIGINAL text — the bytes
   that become adjacent in the result were never adjacent in the input
   (:1184, 1200-1204 balanced native, 1210-1214 brace-less token,
   1224-1229 balanced shipped). The M1-875 fixpoint wrapper (:1136-1152)
   closes the marker-assembly half of that join (a joined marker is
   re-scanned and removed) but deliberately leaves the deletion-join in
   place — the marker is removed, and the bytes around it are still
   joined exactly where the removed span sat.
2. **The closed-list redaction runs upstream of the strip and never
   re-runs.** ChatAgent.java:580 `outputSanitizer.sanitize(finalText)` →
   :585 `stripToolCalls(sanitized)`. The sanitizer's own pass order is
   correct INSIDE `sanitize()` (every deleting pass before the closed-list
   strip, LlmOutputSanitizer.java:257-284); the gap is that the pipeline's
   last deleting pass — the strip — sits AFTER the last closed-list
   invocation, so a token its deletion assembles is never matched,
   redacted, or audited. The closed-list patterns cannot see it any other
   way: `/ban` requires the literal contiguous bytes
   (compileClosedListPattern, LlmOutputSanitizerCore.java:240-252), and
   in the repro the fragment bytes sit between "ba" and "n".

What is proven: the strip-level assembly (probe, hand-trace above), the
pipeline ordering (ChatAgent.java:577-585 read), the no-match claim
(pattern semantics, LlmOutputSanitizerCore.java:240-252) and the
no-row consequence (matches.isEmpty → no emitAuditRows,
LlmOutputSanitizer.java:299-301). Nothing remains unproven; the
reachability of the delivery path is pinned by the existing ordering test
(ChatAgentTest.java:449-458).

## Pitfalls

Numbered for this ticket; each maps to a Verification entry.

- P1: pipeline reordering as the fix shape — moving `sanitize` after the
  strip, or re-running sanitize / the closed-list pass over the strip
  output, reopens the security.md protocol-token-detector promise ("they
  run downstream of every deleting pass … a token a deletion or
  canonicalization assembles out of fragments is one they see",
  security.md:776-781): the closed-list pass itself deletes (the
  canonical-form bidi/zero-width strip and the markdown flatten inside
  applyClosedListStripWithMatches, LlmOutputSanitizerCore.java:956-989),
  so a marker it assembles post-strip would ship with no detector behind
  it — the class M1-791 closed. The M1-875 round-1 "pipeline ordering"
  pitfall names the same trap from the other side.
- P2: a whitespace separator does not close the hole — the closed list's
  multi-word entries match with `\s+` between words
  (compileClosedListPattern, LlmOutputSanitizerCore.java:247-251) and
  flag entries match by a whitespace tokenizer (redactFlagEntry,
  findCommandToken/findFlagToken, :298-387): `/invite<span> create` →
  `/invite  create` and `/list-sources<span> --all` →
  `/list-sources  --all` (a whitespace separator; a bare join yields the
  same with one space) still dispatch. The separator must be neither
  whitespace nor a letter/digit/hyphen. This is the M1-815 lesson
  generalized: the config-key strip's single-space replacement works only
  because a config token has no internal whitespace; the closed list does.
- P3: the M1-875 marker contract must be carried, not weakened — the
  updated deletion-join tests must keep asserting no contiguous
  `TOOL_CALL:` / `<|tool_call>` in the output; deleting them or flipping
  them to happy-path assertions is an §8 test-integrity violation.
- P4: drop-through and kept-opener semantics — unbalanced drop-throughs
  (ChatAgent.java:1206, 1231, 1233) truncate, they do not join, so they
  take no separator; the protectedOpeners hand-off (:1187-1194,
  :1215-1220) must keep its prose ruling across passes. A separator
  inserted at a truncation point, or a join-restoring mutation on the
  balanced-span path, must fail the updated tests.
- P5: the emptied-reply degrade is a control on this path — a reply that
  is ONLY removed spans must degrade (llm.md §Failure handling), never
  deliver a bare separator. A separator-only strip output counts as empty
  (contained in stripToolCalls or step 9c, ChatAgent.java:661-664) —
  otherwise the markers-only reply flips from "degrades" to "delivers '…'".
- P6: audit honesty — under this approach no `LLM_OUTPUT_SANITIZED` row
  fires for a prevented assembly, and that is CORRECT: the delivered bytes
  carry no command token, so no redaction happened and nothing is owed
  (every MATCH is audit-logged; there is no match). The reproduction must
  assert the delivered-bytes property, never a row — a row assertion would
  pin the rejected re-redaction approach (P1).
- P7: surgical scope — the fix is strip-internal; the dispatch grammar,
  TOOL_INSTRUCTIONS, the sanitizer's pass set, CLOSED_LIST
  (matchSetEqualsSpecClosedList), TranslationPipeline and the streaming
  surface are untouched (§1; the closed list is frozen per M1-115 /
  M1-789 P11).
- P8: the separator must survive intake normalization as non-joining — an
  invisible separator (zero-width space, bidi control) is stripped by the
  chat-intake normalization (security.md §Authorization model step 1.7),
  re-joining the fragments exactly at the dispatch boundary: a user
  copy-pasting the bot's line would dispatch the command. U+2026 "…" is
  safe: NFKC folds it to "..." (still non-whitespace, non-token) and the
  bidi/zero-width strip leaves it alone.
- P9: the M1-875 fixpoint loop becomes redundant under the separator (a
  pass can no longer assemble anything) — KEEP it: it is correct, a
  harmless one-extra-pass backstop, and removing it would silently widen
  this diff into a redesign of the M1-875 acceptance contract (§1, §3).
- P10: §12 spec discipline — the security.md amendment is rule-text only:
  no dates, ticket IDs, or report citations; the exact wording is shown to
  the user before it lands (the acceptance item authorizes the work, not
  the wording). This is a rides-the-diff record — the existing ordering
  text and the `](`-neutralization precedent already support the
  mechanism — not a SPEC-GAP.
- P11: §8 test-modification authorization — the eight M1-875/M1-870
  exact-output pins whose expectations change under the separator are
  named in `acceptance` with their new values; any other pre-existing test
  edit is unauthorized and must escalate.
- P12: discriminating fixtures — each failure-mode input must NOT already
  match pre-strip (a vacuous fixture passes with the bug in place): for
  all three inputs the pre-strip closed-list pass has no match (verified
  in acceptance items 2-3 ground truth; the `/invite` case has non-whitespace
  between the words, the `/list-sources` case has `<` after the command
  word) — the M1-785 lesson.
- P13: linearity — the separator costs O(1) per removal; no new scan
  passes, no per-line decomposition (security.md §Trust boundaries item 9:
  a hostile endpoint's in-cap reply must not buy unbounded CPU — the
  M1-789 P1 shape). The adversarial nested-input test keeps pinning
  termination.

## Approach

Derived from `spec_refs:` — §LLM output sanitizer's pass-ordering rule
("Deleting characters joins fragments … a pass placed downstream of the
redaction could assemble a privileged token … unredacted AND unaudited",
security.md:757-764) read against §Prompt-injection defenses' posture (the
LLM is a black box coaxed into attacker-chosen output): the strip is the
one deleting pass downstream of the redaction, so its removals must not
join.

**Solution options (prior-art disposition).**

- **Option A — re-run the closed-list match over the strip output**
  (the review verdict's first EXPECTED shape). What it changes: pipeline
  becomes sanitize → strip → closed-list#2; the joined `/ban` is then
  redacted and rowed. Rejected: (1) it breaks the protocol-token-detector
  promise — the strip no longer evaluates the final delivered bytes, so a
  marker assembled by closed-list#2's own canonicalization/flatten
  (LlmOutputSanitizerCore.java:956-989) ships (P1; the M1-791 class
  reopens); (2) it either doubles sanitize cost and audit row-sets per
  turn or, to restore the marker property, requires a joint
  sanitize/strip fixpoint whose termination is not obvious — exactly the
  "do NOT move sanitize after strip" trap M1-875's own pitfall names;
  (3) M1-787's straddled-second-pass lesson: a second redaction over
  already-transformed text must re-derive row/redaction/unit semantics.
- **Option A′ — strip before sanitize.** Rejected outright: sanitize's
  own deleting passes and canonicalization would then assemble markers
  downstream of the detector (the M1-789/M1-791 assembly class), and the
  existing ordering pin (ChatAgentTest.java:449-458) forbids it.
- **Option B′ — drop the whole line containing a removed span** (the
  scaffolding strip's own precedent, security.md:643-652: "dropping the
  line cannot"). Rejected: the tool-call strip's contract is span-scoped,
  not line-scoped — M1-870/M1-875 pinned that quoted prose on the same
  line as a removed span survives (bareOpenerRulingSurvivesTheReScan,
  bracelessTokenStripRemovesExactlyOpenerCallAndName); line-dropping eats
  it and rewrites the strip contract.
- **Option B — CHOSEN: the strip's removals are never joins.** Each
  removed span (balanced native, brace-less token, balanced shipped) is
  replaced by a non-whitespace, non-token elision separator (U+2026 "…");
  drop-through truncations are not joins and take no separator; a strip
  output holding nothing outside whitespace and separators counts as
  empty. The fixpoint loop and kept-openers hand-off stay unchanged (P9).
  Spec grounding: the `](`-neutralization precedent — the one pass allowed
  after the redaction "only INSERTS a space: it can break a token apart
  but never build one" (security.md:767-770) — generalized to the strip;
  the config-identifier strip's replacement-with-separator reasoning
  (security.md:727-745, the M1-815 diff); the scaffolding strip's stated
  hazard ("deletion joins fragments, so extraction could assemble a new
  marker", security.md:649-652). It AGREES with M1-875's fixpoint (kept,
  demoted to backstop) and with M1-815's separator machinery, and
  DISAGREES with M1-815's choice of SPACE: space is a token separator, and
  the closed list's multi-word/flag entries (the M1-115 shape) re-form
  across it (P2). Cost/risk: a visible "…" where scaffolding was removed
  — cosmetic, on a surface that only ever fires when the model emitted
  scaffolding, and within the category's accepted prose-loss posture
  (security.md:672-674); the eight authorized pinned-test updates; the
  emptied-separator handling (P5).

- **Files to touch:** `files_scope` — ChatAgent.java (stripToolCalls'
  removal sites + the separator-only-empty collapse, contained in the
  strip or step 9c), ChatAgentTest.java (five new tests + eight
  authorized updates), design 05 §5.4.6 (the fixpoint sentence),
  security.md (the rides-the-diff amendment).
- **Steps, in implementation order:**
  1. Write the reproduction RED (delivery path, identity sanitizer seam)
     plus the four failure-mode tests RED where the current code fails
     them (multi-word, flag, canonicalization, and — written GREEN-on-main
     — the markers-only degrade guard).
  2. In `stripToolCallsSinglePass`: replace the three span-removal
     outcomes (balanced native, brace-less token, balanced shipped) with
     separator insertion at the removal point; leave the three
     drop-through returns and the kept-opener arm byte-identical in
     behavior (P4).
  3. Add the separator/whitespace-only → empty collapse on the fixpoint
     output (P5) so step 9c's existing `isBlank()` degrade fires.
  4. Update the eight authorized pinned tests to the separator-era
     expectations (acceptance item 6).
  5. Update design 05 §5.4.6 (:775-783): the fixpoint sentence gains the
     separator rule.
  6. Draft the security.md amendment (rule-text only, P10) and show the
     exact wording to the user (§12) before it lands.
  7. Full `mvn verify` from the repo root.
- **Controls to preserve (§10):** the sanitize call at ChatAgent.java:580
  and the strip's position at :585 (the ordering); the `LLM_OUTPUT_SANITIZED`
  aggregated WARN + audit rows on every sanitize match (no second row
  path); the refusal intercept (:590-597); the emptied-reply degrade
  (:661-664, llm.md §Failure handling); the persist of the approved
  text (:674-678); the translate leg and the deterministic help-block
  accretion (append-only bytes); the `](`-free outbound chokepoint;
  `matchSetEqualsSpecClosedList`. Tests pinning them: the ordering pin
  (ChatAgentTest.java:449-458), the sanitize-side markers-only pins, the
  M1-796 degrade pins, the LlmOutputSanitizerAuditRowIT suite.
- **Pitfall→mitigation:** P1→step 2 (strip stays downstream, joins
  removed at the source); P2→step 2's non-whitespace separator +
  acceptance 2-3; P3/P4→acceptance 6 + the join-restoring mutation check;
  P5→step 3 + acceptance 5; P6→reproduction asserts bytes, not rows;
  P7→out_of_scope + `git diff --name-only`; P8→acceptance 4; P9→steps
  keep the loop; P10→step 6 + acceptance 7; P11→acceptance 6 + Out-of-scope
  naming; P12→the pre-strip no-match ground truth in acceptance 2-3;
  P13→acceptance 6's termination leg + the linear single scan.

## Definition of done

Every `acceptance:` item green by its named test/verification: the
reproduction passes (delivered reply carries no assembled `/ban`); the
multi-word, flag and canonicalization failure-modes pass; the markers-only
degrade guard passes; the eight authorized test updates land with the
separator-era values and stay red under a join-restoring mutation; the
security.md amendment lands rule-text-only with user-approved wording; the
design note records the separator rule; `mvn verify` green from the repo
root.

## Verification

- reproduction → `ChatAgentTest.aPrivilegedCommandAssembledAcrossAStripDeletionNeverReachesTheReply`
  — delivery path over `/ba<|tool_call>call:x{old}n`; asserts no `/ban`
  in the reply and a non-blank reply (RED on main: reply IS `/ban`).
- P1 → the pipeline order is pinned by the pre-existing ordering test
  (ChatAgentTest.java:449-458, preserved unchanged — the strip evaluates
  post-sanitize text) plus `git diff` showing the sanitize call at
  ChatAgent.java:580 untouched; a marker assembled by a reordered
  sanitize would red the preserved
  bracelessTokenAssembledBySanitizationIsStripped leg (updated to
  `"Clean text. …"`).
- P2 → `aMultiWordClosedListEntryCannotBeAssembledAcrossAStripDeletion`
  and `aFlagEntryCannotBeAssembledAcrossAStripDeletion` — exact outputs
  `/invite… create` / `/list-sources… --all` plus canonical-form no-match
  assertions against the real CLOSED_LIST patterns; a whitespace-separator
  or join implementation fails red (verified by construction: `\s+` and
  the tokenizer accept the whitespace-joined forms).
- P3/P4/P9/P13 → the updated M1-875/M1-870 legs (acceptance 6) —
  absence-of-marker assertions intact; kept-opener and drop-through legs
  pinned with separator-era values; the termination leg holds; each leg
  red under a join-restoring mutation of the removal sites.
- P5 → `aMarkersOnlyReplyStillTakesTheEmptiedDegrade` — markers-only
  final text degrades; red under a separator-only-output implementation
  that delivers the separator.
- P6 → the reproduction's absence assertion (no row assertion anywhere in
  the new tests — a row would pin the rejected re-redaction approach).
- P7 → `git diff --name-only` names exactly the four `files_scope` paths;
  `matchSetEqualsSpecClosedList` passes unedited.
- P8 → `anAssembledTokenSurvivesIntakeCanonicalizationSplit` —
  canonicalizeForMatching(strip output) contains no `/ban`; an invisible
  separator fails red (the canonicalizer strips it).
- P10 → acceptance 7's grep + the parity tests over the amended prose.
- P11 → acceptance 6 names each modified test with its new expected
  value; the §8 authorization is the acceptance item, not a retroactive
  note.
- P12 → the pre-strip no-match ground truth (acceptance 2-3) — each
  failure-mode input is verified non-matching before the strip, so the
  test discriminates.
- acceptance 8 → grep on design 05; acceptance 9 → `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: dispatch grammar, TOOL_INSTRUCTIONS, the
sanitizer pass set and frozen CLOSED_LIST, pipeline reordering (all four
rejected fix shapes), fixpoint-loop removal, the digest/summary/translate
detector surfaces, the streamed surface, and collector surfaces.

**Authorized pre-existing test modifications (§8), with new expected
behavior:** this ticket changes the strip's removal from a join to an
elision separator, so every exact-output assertion on a span-removal case
gains the separator at the removal point. Named tests and their new
expectations: `deletionJoinCannotAssembleToolCallMarkers` legs →
`"TOOL_…CALL: y {}"`, `"<|tool_…call>call:z"`, `"TOOL…_…CALL: y {}"`
(absence assertions kept); `deletionJoinInADropThroughPassIsStillReScanned`
→ `"TOOL_…CALL: q {}"` and `"Quote <|tool_call>…call:y "`;
`bareOpenerRulingSurvivesTheReScan` → `"Quote <|tool_call>…call:y"`;
`stripReScanTerminatesOnAdversarialNestedInput` → absence-of-marker
assertions (nested leg output `"TOOL_…"×24 + "CALL: y {}"×24`; spin leg
`"<|tool_call>"×49 + "…"`); `bracelessNativeCallMarkerIsStrippedFromFinalReplies`
→ `"Here you go.\n…"` (persisted turn equals delivered);
`bracelessTokenStripRemovesExactlyOpenerCallAndName` →
`"Answer.\n…\nMore prose here."` (the truncated `"<|tool_call>call:"`
case still returns `""` via the separator-only collapse);
`bracelessTokenDoesNotSwallowALaterUnrelatedBrace` → `"A … then {json} later"`;
`bracelessTokenAssembledBySanitizationIsStripped` → `"Clean text. …"`.
Tests with only absence/contains/startsWith assertions
(residualNativeDialectIsStrippedFromFinalReplies, the ChatAgentToolArgsTest
strip tests, bareOpenerInProseStaysByteIdentical,
proseQuotingTheDialectOpenerIsNotDispatched) hold without edits. Any other
pre-existing test edit is unauthorized — escalate.

## Census

Class: post-redaction character-deleting transforms on the chat delivery
path (a transform downstream of the closed-list strip whose deletions can
join fragments). Re-runnable enumeration:
`grep -n 'stripToolCalls\|outputSanitizer.sanitize\|translationPipeline.run' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java`
(returns the three transform sites below; the refusal intercept, the
step-9b accretion and the OutboundDelivery chokepoint are enumerated by
reading doHandle steps 7-9c and are rewrite-free or insertion-only).

| Site | Disposition |
|---|---|
| ChatAgent.java:585 stripToolCalls | FIXED here — removals become non-joining separators |
| ChatAgent.java:580 outputSanitizer.sanitize | unchanged — the redaction, upstream of the strip by design |
| ChatAgent.java:610 translationPipeline.run | out-of-scope — the translate leg's output runs its own sanitizer-2 (TranslationPipeline.java:276), a different pipeline stage (M1-793 dispositioned) |
| ChatAgent.java:590-597 refusal intercept | no rewrite — detector only, prefix check (not a grep hit; doHandle step 8) |
| ChatAgent.java:633-656 step-9b help-block accretion | append-only deterministic bytes — no deletion, no join (not a grep hit; doHandle step 9b) |
| OutboundDelivery breakLinkAdjacency | insertion-only chokepoint (M1-691) — no deletion, no join (outside ChatAgent.java) |

No other post-redaction deleting transform exists on this path (the only
other LLM-authored surfaces' detectors — CategoryRollupGenerator,
SummaryProseGenerator — carry no strip and are dispositioned by M1-791).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-879-strip-deletion-join-privileged-command.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-879-r1.txt):

1. Finding 1: drop the "that leaves text on both sides" qualifier from the
   amended sentence at docs/spec/security.md:781-784 so the rule states
   that each removed span is replaced by an elision separator (drop-through
   truncations remain the stated exception); show the exact corrected
   wording to the user per §12 and record the approval in the round-2
   mechanical report. Verified by
   `grep -n 'leaves text on both sides' docs/spec/security.md` returning
   nothing plus ChatToolAllowlistSpecParityTest and
   DocumentedConfigKeyParityTest green in the round-2 mvn verify.
2. Finding 2: rewrite the stale header comment at
   ChatAgentTest.java:687-689 to the separator-era mechanism (pass 1
   replaces every fragment with a separator; no marker re-forms, so the
   confirming pass removes nothing). Verified by
   `grep -n '25 removing passes' infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java`
   returning nothing plus stripReScanTerminatesOnAdversarialNestedInput
   green in the round-2 mvn verify.
