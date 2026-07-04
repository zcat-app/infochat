---
id: M1-561
title: Refusal-marker intercept runs on post-strip terminal text
status: done
created: 2026-07-04
last_updated: 2026-07-04
revisions:
  - date: 2026-07-04
    reason: redteam-finding refine, applied by the developer at the user's
      direction ("look to me you suggest refine", 2026-07-04) — the redteam
      audit (docs/plan/m1/redteam/M1-561-2026-07-04.md, medium INFO-LEAK)
      falsified acceptance item 1's "relocation is sufficient" premise;
      strip's unbalanced-fragment drop-through can eat the marker's closing
      `]`, so any endsWith conjunct re-opens a leak. The predicate is
      hardened to prefix-only on the post-strip text, which also closes the
      audit's out-of-model unterminated-marker case.
    snapshot:
      acceptance_item_1: "The M1-559 refusal intercept (trimmed text
        startsWith \"[REFUSAL:\" && endsWith \"]\") is relocated to run on
        the POST-stripToolCalls text instead of the raw tool-loop terminal
        text — a single check, still before sanitize/persist/delivery.
        Relocation is sufficient because stripToolCalls is the identity on
        a pure marker (no TOOL_CALL substring), so the pure-marker case
        M1-559 covered keeps intercepting; a mixed marker+fragment text,
        which evades the pre-strip check because the fragment breaks the
        endsWith/startsWith anchor, is caught after the fragment is
        stripped."
      out_of_scope_entry_2: "the refusal-marker protocol, the anchored
        predicate itself, the error.chat.refused bundle key, and the WARN
        logging shape — all stay exactly as M1-559 shipped them"
      test_plan_adds_entry: "ChatAgentRefusalInterceptTest mixed-output
        cases — brace-less fragment (in-loop) and balanced fragment both
        orders (post-cap)"
escalations:
  - date: 2026-07-04
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Round-1 review verdict was APPROVE (all checks PASS). The trigger
      is the /redteam M1-561 --in-progress medium INFO-LEAK finding
      (docs/plan/m1/redteam/M1-561-2026-07-04.md): the reorder makes
      stripToolCalls run before the anchored intercept, so a marker
      whose closing `]` follows an embedded `TOOL_CALL:` fragment
      (e.g. `[REFUSAL: TOOL_CALL: foo]`) is mangled by strip's
      drop-through-end-of-text path into `[REFUSAL: ` — which no longer
      ends with `]`, evades the post-strip check, and is delivered.
      Pre-diff the both-ends anchor caught that input: a regression the
      relocation introduces.
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 82
      removed: 14
  - round: 2
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 268
      removed: 40
redteam_findings:
  - date: 2026-07-04
    category: INFO-LEAK
    severity: medium
    promise: |
      security.md §Prompt-injection defenses — the structured refusal
      marker is a secret injection-defense convention (literal token in
      design notes only) that must never be delivered to the
      counterparty; the ChatAgent intercept exists to withhold it.
    gap: |
      The reorder runs stripToolCalls BEFORE the anchored intercept.
      When the marker's closing `]` sits AFTER an embedded `TOOL_CALL:`
      fragment, strip's drop-through-end-of-text path deletes the `]`,
      leaving text that starts with `[REFUSAL:` but no longer ends with
      `]` — the post-strip intercept misses it and the mangled marker
      flows to sanitize/persist/deliver. Pre-diff (intercept before
      strip) the same input WAS caught: a regression the reorder
      introduces.
    repro: |
      Coax CHAT_AGENT (via injected untrusted content) to emit the
      terminal text `[REFUSAL: TOOL_CALL: foo]`. TOOL_CALL_PATTERN
      requires `{`, so the loop does not dispatch and returns it
      in-line. stripToolCalls appends `[REFUSAL: `, finds no brace,
      drops the rest — the intercept sees text not ending with `]` and
      delivers `[REFUSAL:` to the user. Balanced-brace variants surface
      via the post-iteration-cap path.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-07-04
    verdict: FINDINGS
    base: 2908a71e
    head: m1/M1-561-refusal-intercept-post-strip@working-tree
    verdict_file: docs/plan/m1/redteam/M1-561-2026-07-04.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Pre-commit audit after round-1 APPROVE. One medium INFO-LEAK:
      post-strip-only anchoring misses markers whose `]` is eaten by
      strip's unbalanced-fragment drop-through — verified against the
      stripToolCalls source. Commit halted pending
      `/m1-tick escalate M1-561 redteam-finding`. Out-of-model
      (advisory, pre-existing): a marker emitted with no closing `]` at
      all has always evaded the two-sided anchor, before and after
      M1-559/M1-561.
  - date: 2026-07-04
    verdict: CLEAN
    base: 2908a71e
    head: m1/M1-561-refusal-intercept-post-strip@working-tree
    verdict_file: docs/plan/m1/redteam/M1-561-2026-07-04-reaudit.md
    out_of_model_count: 2
    note: |
      Re-audit after the redteam-finding refine (prefix-only predicate
      on post-strip text). Confirms the medium INFO-LEAK is closed —
      prefix-only strictly widens the intercepted set; the repro and
      the unterminated-marker case are both caught — and no new gap
      opened. The prior finding above is remediated by this branch's
      round-2 diff, not erased. Two advisory out-of-model items, both
      pre-existing and outside the documented model: mid-prose marker
      delivery (deliberate anchored-not-substring design; would need a
      spec-level commitment to change) and Unicode-lookalike evasion of
      the ASCII prefix literal (does not leak the genuine token).
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
  - the refusal-marker protocol, the error.chat.refused bundle key, and
    the WARN logging shape — all stay exactly as M1-559 shipped them
    (the predicate's anchoring is IN scope per acceptance item 1)
  - SummaryProseGenerator — it has no tool loop, so no strip step and no
    strip-mangling gap; its two-sided anchor keeps M1-559 semantics
  - the tool-loop iteration cap, tool dispatch, sanitize, translate, and
    persistence machinery
acceptance:
  - "The M1-559 refusal intercept is relocated to run on the
    POST-stripToolCalls text instead of the raw tool-loop terminal text —
    a single check, still before sanitize/persist/delivery — and its
    predicate is hardened from the two-sided anchor to prefix-only:
    trimmed post-strip text startsWith \"[REFUSAL:\". Prefix-only is
    required, not optional: strip's unbalanced-fragment drop-through can
    eat the marker's closing `]` (redteam M1-561 2026-07-04:
    '[REFUSAL: TOOL_CALL: foo]' strips to '[REFUSAL: '), so any endsWith
    conjunct re-opens the leak the intercept exists to close. Prefix-only
    is fail-closed — trimmed text leading with the protocol token must
    never be delivered regardless of what follows — and, because strip
    only deletes (never prepends), a post-strip prefix match cannot be
    manufactured from a mid-prose quotation, so the anchored-negative
    behavior is preserved."
  - "ChatAgentRefusalInterceptTest gains mixed-output cases asserting the
    bundle reply, no marker leak, and no persistence: (a) marker followed
    by a brace-less fragment ('[REFUSAL: x]\\nTOOL_CALL: searchPosts') —
    an ordinary in-loop terminal text, since TOOL_CALL_PATTERN requires a
    brace; (b) a post-cap response mixing the marker with a balanced
    fragment in either order (marker-then-fragment and
    fragment-then-marker); (c) the redteam repro — a fragment embedded
    INSIDE the marker ('[REFUSAL: TOOL_CALL: foo]', in-loop since no
    brace) whose closing bracket strip eats; (d) an unterminated marker
    with no closing bracket at all ('[REFUSAL: x'). The existing
    pure-marker and anchored-negative (mid-prose substring) cases keep
    passing unmodified."
  - The WARN log line (userId only, no LLM-authored reason text — D37)
    is unchanged apart from where it fires.
  - mvn verify is green.
test_plan:
  adds:
    - ChatAgentRefusalInterceptTest mixed-output cases — brace-less
      fragment (in-loop), balanced fragment both orders (post-cap),
      embedded fragment inside the marker (redteam repro), and
      unterminated marker (no closing bracket)
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

## Refine (2026-07-04, redteam remediation)

The pre-commit redteam audit
(docs/plan/m1/redteam/M1-561-2026-07-04.md, medium INFO-LEAK) falsified
the original "relocation is sufficient / predicate unchanged" premise:
when the marker's closing `]` sits after an embedded `TOOL_CALL:`
fragment (`[REFUSAL: TOOL_CALL: foo]`), strip's drop-through-end-of-text
path deletes the `]`, so the two-sided anchor misses the stripped
`[REFUSAL: ` and the secret prefix is delivered — an input the pre-diff
(pre-strip) check DID catch. A dual raw+stripped two-sided check was
considered and rejected: `[REFUSAL: TOOL_CALL: foo]\nTOOL_CALL: bar`
evades both sides (raw ends with the fragment; stripped loses the `]`).
The predicate therefore becomes prefix-only on the post-strip text,
which is fail-closed for every mixed shape and also closes the audit's
out-of-model unterminated-marker case. The mid-prose negative case is
unaffected: strip only deletes text, so a stripped text cannot begin
with the marker unless the raw text's first surviving content did.
