---
id: M1-561
title: Refusal-marker intercept runs on post-strip terminal text
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
remediates: M1-559
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - stripToolCalls / matchBrace behavior — the strip semantics are correct
    and stay byte-identical; only the position of the refusal check
    relative to the strip moves
  - the refusal-marker protocol, the anchored predicate itself, the
    error.chat.refused bundle key, and the WARN logging shape — all stay
    exactly as M1-559 shipped them
  - SummaryProseGenerator — it has no tool loop, so no strip step and no
    equivalent gap
  - the tool-loop iteration cap, tool dispatch, sanitize, translate, and
    persistence machinery
acceptance:
  - "The M1-559 refusal intercept (trimmed text startsWith \"[REFUSAL:\"
    && endsWith \"]\") is relocated to run on the POST-stripToolCalls
    text instead of the raw tool-loop terminal text — a single check,
    still before sanitize/persist/delivery. Relocation is sufficient
    because stripToolCalls is the identity on a pure marker (no
    TOOL_CALL substring), so the pure-marker case M1-559 covered keeps
    intercepting; a mixed marker+fragment text, which evades the
    pre-strip check because the fragment breaks the endsWith/startsWith
    anchor, is caught after the fragment is stripped."
  - "ChatAgentRefusalInterceptTest gains mixed-output cases asserting the
    bundle reply, no marker leak, and no persistence: (a) marker followed
    by a brace-less fragment ('[REFUSAL: x]\\nTOOL_CALL: searchPosts') —
    an ordinary in-loop terminal text, since TOOL_CALL_PATTERN requires a
    brace; (b) a post-cap response mixing the marker with a balanced
    fragment in either order (marker-then-fragment and
    fragment-then-marker). The existing pure-marker and
    anchored-negative (mid-prose substring) cases keep passing
    unmodified."
  - The WARN log line (userId only, no LLM-authored reason text — D37)
    is unchanged apart from where it fires.
  - mvn verify is green.
test_plan:
  adds:
    - ChatAgentRefusalInterceptTest mixed-output cases — brace-less
      fragment (in-loop) and balanced fragment both orders (post-cap)
  preserves:
    - the existing ChatAgentRefusalInterceptTest cases unmodified
      (pure marker intercepted; mid-prose substring delivered unchanged)
    - the full pre-existing suite, in particular ChatAgentTest's
      stripToolCalls coverage
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D21
  - D37
---

## Context

Redteam out-of-model item from the M1-559 pre-commit audit
(docs/plan/m1/redteam/M1-559-2026-07-04.md), verified 2026-07-04 by
executing the shipped stripToolCalls/predicate logic against the leak
inputs. M1-559 placed the refusal intercept on the raw tool-loop
terminal text, BEFORE stripToolCalls. When the model emits the D21
marker mixed with a tool-call fragment, the fragment breaks the
anchored predicate (text no longer starts and ends with the marker), so
the intercept skips; stripToolCalls then removes the fragment and the
bare `[REFUSAL: ...]` string reaches the user — the exact protocol-surface
leak M1-559 closed for well-formed refusals.

Verification found the gap is reachable on the ORDINARY turn path, not
just the post-cap path the audit named: `TOOL_CALL_PATTERN` requires an
opening brace, so `[REFUSAL: x]\nTOOL_CALL: searchPosts` (brace-less
fragment) does not dispatch — it is returned as in-loop terminal text,
evades the pre-strip check, and strips down to the bare marker. Balanced
fragments (`[REFUSAL: x]\nTOOL_CALL: getPost {...}` or the reverse
order) leak the same way via the post-iteration-cap response, which is
returned without tool-call parsing. Mixed marker+fragment output is the
model-misbehavior class F-live-9 came from (small local models); the
system prompt forbids it, but the intercept exists precisely because
the model's compliance is untrusted.

## Fix shape

Move the M1-559 intercept block from step 5 (pre-strip) to after the
stripToolCalls call, checking the stripped text. One check covers all
cases: strip is the identity on a pure marker, catches mixed output
after the fragment is removed, and an unbalanced fragment after a
marker strips to the bare marker which the relocated check then
catches. The anchored (not substring) predicate is unchanged, so the
mid-prose quoting behavior M1-559's negative case pins keeps holding.
