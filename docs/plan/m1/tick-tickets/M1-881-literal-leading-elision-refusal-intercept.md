---
id: M1-881
title: "Preserve literal elisions at refusal intercept"
status: done
created: 2026-08-18
last_updated: 2026-08-18
flow: tick
reproduction: >-
  ChatAgentRefusalInterceptTest.literalLeadingElisionDoesNotTriggerRefusalIntercept
  — run RED on 2026-08-18 in the dedicated M1-881 worktree with
  ./mvnw -B -pl infochat-provider -am test. A terminal LLM reply of
  '…[REFUSAL: quoted]' has no tool-call span and must be delivered and
  persisted unchanged, but ChatAgent.java:647 globally removes U+2026 before
  the prefix check, so the reply degrades as error.chat.refused. The RED log
  records the expected reply, actual bundle key, and warning at
  .scratch/tick-test-M1-881-red.log:3831-3849.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    M1-879's U+2026 separator rule, tool-call strip grammars, and fixpoint
    behavior. Balanced, brace-less, and drop-through removal semantics stay
    unchanged.
  - >-
    The D21 marker literal, prefix-only anchor, error.chat.refused bundle
    response, WARN logging shape, persistence mechanism, and translation
    pipeline. Only the provenance of U+2026 used by the existing predicate is
    in scope.
  - >-
    Reordering or repeating LlmOutputSanitizer, including its audit rows;
    sanitize remains once before strip.
  - >-
    SummaryProseGenerator, CategoryRollupGenerator, streamed delivery, tool
    dispatch, and command parsing, which are separate detector or transport
    paths.
acceptance:
  - "ChatAgentRefusalInterceptTest.literalLeadingElisionDoesNotTriggerRefusalIntercept (the RED reproduction) passes: terminal text '…[REFUSAL: quoted]' is delivered exactly, has a non-null deferred commit, and commits the normal user/assistant pair. The visible literal U+2026 remains part of the anchored D21 predicate (docs/spec/security.md §Prompt-injection defenses)."
  - "ChatAgentRefusalInterceptTest.literalLeadingElisionStaysNonRefusalWhenToolStripAlsoAddsAnElision passes — FAILURE-MODE (P2): literal leading '…[REFUSAL: quoted]' plus a later strippable tool-call span is delivered rather than degraded and has a non-null deferred commit. The later removal proves the implementation cannot use a turn-wide generated-separator flag or erase every U+2026."
  - "ChatAgentRefusalInterceptTest.generatedLeadingElisionBeforeRefusalStillDegradesTheTurn passes — FAILURE-MODE (P3): a terminal post-cap response whose leading tool-call span strips to a generated U+2026 immediately before '[REFUSAL: quoted]' returns BundleKeys.ERROR_CHAT_REFUSED, leaks no marker, and has a null pending commit. This preserves M1-879's required generated-separator look-through and the D21 no-delivery/no-persistence consequence."
  - "ChatAgentRefusalInterceptTest.refusalMarkerReplacedWithBundleStringAndNothingPersisted, ChatAgentRefusalInterceptTest.midProseRefusalSubstringDeliveredUnchanged, ChatAgentRefusalInterceptTest.markerWithBracelessFragmentInterceptedAfterStrip, ChatAgentRefusalInterceptTest.postCapMarkerThenBalancedFragmentIntercepted, ChatAgentRefusalInterceptTest.postCapBalancedFragmentThenMarkerIntercepted, ChatAgentRefusalInterceptTest.embeddedFragmentInsideMarkerIntercepted, and ChatAgentRefusalInterceptTest.unterminatedMarkerIntercepted stay green unchanged in ./mvnw -B -pl infochat-provider -am test. They pin M1-561's prefix-only post-strip contract."
  - "./mvnw -B -pl infochat-provider -am test is green, followed by mvn verify from the repository root green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      ChatAgentRefusalInterceptTest.literalLeadingElisionDoesNotTriggerRefusalIntercept
      (already present in the dedicated worktree and RED at filing),
      literalLeadingElisionStaysNonRefusalWhenToolStripAlsoAddsAnElision, and
      generatedLeadingElisionBeforeRefusalStillDegradesTheTurn
  preserves:
    - >-
      all tests currently green on main, particularly existing D21 cases and
      ChatAgentTest's M1-879 separator, markers-only-degrade, and exact
      strip-output tests
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/llm.md §Failure handling
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D21
  - D37
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates: M1-879
reviews:
  - round: 1
    date: 2026-08-18
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (1 low, missing provenance-contract javadoc on StrippedToolCalls/StripPass), SCOPE PASS"
    diff_stats: "4 files, +352/-21"
    fix_items: 1
    verdict_file: .scratch/tick-review-M1-881-r1.txt
    fix_probes: >-
      1. grep -n -B6 '^    record StrippedToolCalls' ChatAgent.java →
      javadoc block naming the strip-inserted-separator contract
      (ChatAgent.java:1265-1269); grep -n -B3 '^    record StripPass'
      ChatAgent.java → javadoc naming the generated-elision component
      (ChatAgent.java:1261-1262); fix diff over round-1 tree 779e5aab is
      comment-only (only /** */ lines, ChatAgent.java only);
      ./mvnw -B -pl infochat-provider -am test-compile BUILD SUCCESS
      (2026-08-18T01:55:58+02:00); fixed tree
      .scratch/tick-fixes-M1-881.tree =
      843e8d1315c65c5a88034003b38576aa096fb3a3
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-18
  result: pass
  evidence: "Lint clean; cited source and all planned D21 test controls verified; no blockers or in-flight ticket conflicts."
escalation_reason:
---

# M1-881: Preserve literal elisions at refusal intercept

## Context

M1-879 changed every balanced or brace-less tool-call removal to a visible
U+2026 elision separator, preventing strip deletion from joining a privileged
command or protocol token after closed-list redaction. To preserve D21 refusal
detection when a generated separator precedes the marker, the shipped code
removes every U+2026 before its post-strip prefix test. A normal LLM reply
beginning with literal `…[REFUSAL: quoted]` has no tool-call span, but is
changed only for that predicate and degrades as `error.chat.refused` instead
of being delivered and persisted. The worktree's RED test proves that outcome.

## Root cause

`ChatAgent` declares `ELISION_SEPARATOR` as U+2026 at
`ChatAgent.java:79-82`; its balanced and brace-less strip removal arms append
that same string at `:1295-1312` and `:1321-1328`. After `stripToolCalls`
produces `approved` at `:640`, the D21 check applies
`approved.replace(ELISION_SEPARATOR, "").trim()` at `:647` and tests the
prefix at `:648`. A literal U+2026 copied untouched through the strip is
therefore indistinguishable from one that the strip inserted. The reproduction
contains no removable span, proving the global replacement is the cause.

M1-879's preservation rationale is only partly true. It correctly requires a
refusal marker exposed at reply start by an elision that the strip itself
generated to remain detectable. It is false that removing the Unicode code
point globally preserves that control safely: the LLM is untrusted
(`docs/spec/security.md` §Prompt-injection defenses) and can emit identical
literal U+2026 text, so code-point equality establishes no origin.

## Pitfalls

- P1: Simply retaining U+2026 in the predicate fixes the RED case but leaks a
  genuine D21 marker preceded by an elision the strip inserted. The protocol
  detector must guard delivered bytes (`security.md` §LLM output sanitizer),
  so M1-879's generated-separator detection is not optional.
- P2: A turn-wide "separator generated" flag, or global replacement whenever
  any span was stripped, still misclassifies a literal leading ellipsis when a
  later span was removed. Origin must be per occurrence and survive the
  fixpoint re-scan. This re-parameterizes a path control, so
  engineering-rules §10 requires its controls to be enumerated and tested.
- P3: A private sentinel materialized to U+2026 at the end is not provenance
  if raw model text can contain it. The endpoint chooses all reply bytes
  (`security.md` §Trust boundaries item 9), so no character value is reserved.
- P4: Do not move sanitizer after strip or run it twice. Deleting sanitizer
  passes must precede closed-list redaction, and a second run changes
  closed-list/audit semantics (`security.md` §LLM output sanitizer; M1-791 and
  M1-879).
- P5: A refusal remains a degraded turn: no LLM reason in the WARN, no
  persistence, no translation, and no provenance notice. These controls are
  at `ChatAgent.java:649-654` and `docs/spec/llm.md` §Failure handling.
- P6: Keep scope surgical. M1-561 made the post-strip predicate prefix-only
  because drop-through can consume `]`; changing that grammar, other
  generators, dispatch, or strip semantics is unrelated (engineering-rules
  §§1, 3, and 8).

## Approach

The selected solution implements the cited specs without a spec amendment.
D21 requires a leading marker surviving the post-sanitize strip to degrade,
while the sanitizer contract says U+2026 is visible output inserted for a
strip removal, not a reclassification rule for ordinary prose. The chat-mode
spec requires degraded replies to carry no provenance notice.

Options considered:

- Stop looking through U+2026. Rejected: it passes the literal reproduction
  but violates P1 by delivering a real refusal exposed by a generated
  separator.
- Keep the global replacement or condition it on any removal. Rejected: both
  fail P2 because origin belongs to an occurrence, not a turn.
- Use only an internal sentinel. Rejected: untrusted model text can produce
  the same character and it recreates the ambiguity (P3).
- Chosen: retain the identical delivered strip `String`, but have the strip's
  private result/pass state carry occurrence-level provenance for separators
  it appends across all fixpoint passes. Derive a refusal-comparison view that
  omits only metadata-marked generated U+2026 occurrences; copied source
  characters, including literal U+2026, remain in that view. The current
  trimmed prefix-only predicate uses this view only; the ordinary strip text
  remains the input to token accounting, translation, persistence, and
  delivery. This is the smallest implementation that preserves both D21 paths.

Implementation order:

1. Extend private strip result/pass state in `ChatAgent.java` to propagate
   generated-elision origin positions (or equivalent per-character provenance)
   while preserving the observable `stripToolCalls(String)` output.
2. At step 8, replace the global `String.replace` comparison with the
   provenance-derived comparison view; retain `approved` for every later path.
3. Add the RED reproduction and P1/P2 end-of-path tests, then run targeted
   provider tests and the root verification gate.

Controls to preserve (engineering-rules §10): the one sanitizer call and its
aggregated WARN/audit emission stay at `ChatAgent.java:627-635`; strip stays
after sanitizer at `:637-640`; the refusal WARN logs only `userId` at
`:649-651`; refusal returns the bundle response with null commit and notice at
`:652-654`; non-refused `approved` text still drives tokens, translation,
deterministic blocks, empty-reply handling, persistence, and delivery
(`:656-720`). No authorization or validation path is rerouted.

Pitfall mapping: P1 → generated-leading failure test and comparison view;
P2 → occurrence provenance and mixed-origin test; P3 → metadata rather than
sentinel; P4 → unchanged sanitize/strip order and audit path; P5 → end-path
bundle/non-leak/null-commit assertions; P6 → two-file scope and unchanged
existing D21 cases.

## Definition of done

The RED literal-elision reproduction passes; the two failure-mode tests prove
literal and generated U+2026 values remain distinguishable even in one reply;
existing D21 cases retain their behavior; sanitizer/audit, content-free
logging, null-commit, and normal-persistence controls remain intact; targeted
tests and root `mvn verify` are green.

## Verification

- P1 →
  `ChatAgentRefusalInterceptTest.generatedLeadingElisionBeforeRefusalStillDegradesTheTurn`:
  feed a post-cap final response whose leading span strips to generated U+2026
  before `[REFUSAL: quoted]`; assert bundle response, marker non-leak, and
  null commit. An implementation that stops separator look-through fails.
- P2 →
  `ChatAgentRefusalInterceptTest.literalLeadingElisionStaysNonRefusalWhenToolStripAlsoAddsAnElision`:
  feed a literal leading U+2026 and a later removable span; assert normal reply
  and non-null commit. A global replacement or turn-level flag fails.
- P3 → the P1 and P2 tests together prove a code point is not provenance; a
  sentinel-only treatment fails one literal case.
- P4 → preserve the existing post-sanitize D21 cases named in acceptance and
  inspect the diff for no new `outputSanitizer.sanitize` call or reordering;
  `./mvnw -B -pl infochat-provider -am test` runs the provider test surface.
- P5 → P1 asserts bundle reply, marker non-leak, and null commit; literal
  cases commit and assert the normal two persisted rows.
- P6 → `git diff --name-only` is confined to `files_scope`; named existing D21
  cases remain unmodified and green.
- Final acceptance → `mvn verify` from repository root.

## Out-of-scope

This ticket does not alter M1-879's visible separator, grammar, fixpoint, or
markers-only empty-degrade semantics; any sanitizer pass or audit behavior;
the D21 prefix-only grammar and outcome; or refusal handling outside
`ChatAgent`. No pre-existing assertion is weakened or re-targeted. The RED
reproduction already in the dedicated worktree and the two named end-of-path
cases are additions to `ChatAgentRefusalInterceptTest`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-881-literal-leading-elision-refusal-intercept.md
```
