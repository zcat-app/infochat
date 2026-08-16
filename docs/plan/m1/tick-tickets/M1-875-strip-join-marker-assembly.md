---
id: M1-875
title: "Stop the strip from assembling tool-call markers"
status: pending
created: 2026-08-17
last_updated: 2026-08-17
flow: tick
reproduction: >-
  Probe (run RED 2026-08-17, observed output verbatim; zero repo
  changes — reflection into compiled
  infochat-provider/target/classes): input
  "TOOL_" + "<|tool_call>call:x{old}" + "CALL: y {}" passed to
  ChatAgent.stripToolCalls returns "TOOL_CALL: y {}". The input
  contains NO contiguous TOOL_CALL: anywhere; the strip's
  deletion-join assembled the exact shipped-dialect marker it exists
  to remove, and that text is the `approved` value delivered to the
  user (ChatAgent.java:580) — never re-sanitized, because the
  closed-list redaction pass (outputSanitizer.sanitize) runs BEFORE
  the strip on the pre-join text (ChatAgent.java:577). A privileged
  closed-list token landing in the joined span ships unredacted.
  Mechanism: stripToolCalls appends pre-marker bytes, skips the
  matched span, and continues scanning the ORIGINAL text; the
  assembled StringBuilder output is never re-scanned
  (ChatAgent.java:1129-1178). Source: M1-870 round-1 review
  recommended-new-ticket (TOUCHED-BY-THIS-DIFF: no — pre-existing,
  identical reachability pre-diff), confirmed live and filed by user
  decision 2026-08-17.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE dispatch grammar or TOOL_INSTRUCTIONS — dispatch requires the
    brace by design (M1-870 census); only the final-reply strip's own
    output is in scope.
  - >-
    outputSanitizer itself and the collector Stage1RegexSet surface
    (own boundary, M1-791 disposed) — this ticket fixes assembly IN
    the strip, not the sanitize closed list.
  - >-
    M1-871/M1-872 tool-transport work beyond keeping this diff
    conflict-free; if either lands first and touches stripToolCalls,
    rebase is execution, not drift.
acceptance:
  - "ChatAgentTest gains a RED-at-filing deletion-join test, green at done: stripToolCalls(\"TOOL_\" + \"<|tool_call>call:x{old}\" + \"CALL: y {}\") returns text containing NO contiguous 'TOOL_CALL:' and no '<|tool_call>' opener; the same test covers a shipped-joins-native variant and the joined-closed-list-token case from the observation — probe: the named test method in ChatAgentTest, green in mvn verify."
  - "The strip re-scans its own output: passes repeat while a pass removes any span, and termination is guaranteed without relying on input goodwill (a pass that removes nothing ends the loop; a bounded pass cap backstops) — probe: the loop in stripToolCalls plus a termination test with adversarial nested-fragment input."
  - "M1-870's five strip tests and all pre-existing delivery behavior stay byte-identical (bare opener stays quoted prose; brace-less exact strip with following prose preserved; balanced exact / unbalanced drop-through) — probe: mvn verify exit 0 with no test modifications outside the adds."
  - "Design 05 §5.4.6 records the fixpoint rule in place of (or alongside, if the landed fix differs and cleared the hurdle path) the residual note — probe: grep -n 'fixpoint\\|re-scan' docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - "ChatAgentTest: deletion-join assembly test (native-joins-shipped, shipped-joins-native, joined closed-list token); termination/backstop test for the re-scan loop."
  preserves:
    - all tests currently green on main
  notes: []
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs: []
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
clarity_check:
escalation_reason:
---

# M1-875: stop the strip from assembling tool-call markers

## Context

M1-870 landed the brace-less native strip with a round-1 review that
recorded (recommended-new-ticket, filing the user's call) a
pre-existing defect class: stripToolCalls' deletion concatenates the
bytes on either side of a removed span and never re-scans the
result. Confirmed live on merged main 2026-08-17 (see reproduction):
`TOOL_` + stripped span + `CALL: y {}` delivers `TOOL_CALL: y {}`.
The pipeline comment at ChatAgent.java:578-579 already treats
cross-stage assembly as real ("sanitize can assemble a fragment into
a full line, so the strip runs after it") — the strip's OWN assembly
is the unguarded direction, and the sanitize-before-strip ordering
means a joined privileged token ships unredacted.

## Root cause

stripToolCalls (ChatAgent.java:1129-1178) scans the ORIGINAL text
with a cursor while building a separate StringBuilder result; after
appending pre-marker bytes and skipping a matched span, the bytes now
adjacent in the result were never adjacent in the input and are never
re-examined. No post-pass asserts the delivered text is
marker-free.

## Pitfalls

- Unbounded re-scan: a loop until fixpoint must terminate on
  adversarial input — a pass that removes nothing ends it; a pass cap
  backstops (never rely on "each pass shrinks" alone across the
  drop-through return paths).
- Drop-through semantics: the unbalanced drop-through-end return
  exits mid-scan; the fixpoint wrapper must not re-process text the
  drop-through already decided to keep-by-truncation.
- Over-strip regression: a quoted bare opener adjacent to a removed
  span can newly look like a marker after a join; M1-870's
  bare-opener-stays-prose ruling must survive re-scan.
- Pipeline ordering: fixpoint-in-strip closes the joined-token leak
  because the assembled marker (and everything after it per strip
  semantics) is removed before delivery; do NOT move sanitize after
  strip instead — that reorders a fail-closed pipeline to chase one
  leak.

## Approach

- **Files to touch:** `ChatAgent.java` (stripToolCalls fixpoint
  wrapper), `ChatAgentTest.java` (deletion-join + termination
  tests), `docs/design/05-llm-and-embeddings.md` (§5.4.6 fixpoint
  rule).
- **Steps:** write the deletion-join test, run it RED (the
  reproduction pinned as JUnit); wrap the single-pass strip in a
  bounded repeat-while-removed loop (drop-through return short-
  circuits the wrapper); run the termination probe; update §5.4.6;
  full `mvn verify`.

## Definition of done

All acceptance probes green; the reproduction input's delivered text
contains no contiguous marker of either dialect; `mvn verify` green.

## Verification

- Reproduction flips: the JUnit deletion-join test RED at start,
  green at done (acceptance 1).
- Termination: adversarial nested-fragment input completes under the
  pass cap (acceptance 2).
- No-regression: M1-870's five strip tests untouched and green
  (acceptance 3); `mvn verify` exit 0 (acceptance 5).
- Design recorded: §5.4.6 grep probe (acceptance 4).
- Failure-mode legs (mandatory): the joined-closed-list-token case
  (privileged name assembled across a deletion — must NOT ship) and
  the nested-fragment spin case (must terminate).

## Out-of-scope

See `out_of_scope` — dispatch grammar, sanitizer closed list,
collector surface, and M1-871/872 transport work are out; rebase
against them is execution.

## Census

Single defect site: stripToolCalls' deletion-join (one method, one
call site at ChatAgent.java:580). The census grep from M1-870
(`grep -rn '\[REFUSAL:\|TOOL_CALL\|tool_call' infochat-provider/src/main/java infochat-collector/src/main/java`)
stays re-runnable; no other deletion site feeds delivery text
without its own boundary.
