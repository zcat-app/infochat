---
id: M1-663
title: "Spec: deterministic-delivery contract for chat help text"
status: pending
created: 2026-07-19
last_updated: 2026-07-19
blocked_by: []
files_budget: 1
files_scope:
  - docs/spec/security.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any code change. This ticket is a spec amendment only; the implementation
    that honors it is M1-665, and the retrieval surface it constrains is
    M1-664. If writing the amendment reveals the contract is unimplementable,
    escalate — do not weaken the language to fit an implementation idea.
  - >-
    The tool-allowlist table (security.md:287-298). The `helpLookup` row is
    M1-664's acceptance; this ticket touches §LLM output sanitizer only.
  - >-
    The sanitizer match-set derivation, CLOSED_LIST, or the per-occurrence
    audit-logging commitment. The amendment adds a delivery-ordering contract
    and tightens the exemption's scope; it removes or relaxes nothing.
  - >-
    docs/spec/commands.md §Chat mode. Describing the deterministic delivery
    mechanism there is M1-665's job, once the mechanism exists. This ticket
    pins the security contract, not the feature.
acceptance:
  - >-
    docs/spec/security.md §LLM output sanitizer states the delivery-ordering
    contract: any command-usage or help text delivered into a chat-mode reply
    must be EITHER (a) deterministic end-to-end — deterministic code decides
    both WHETHER it is delivered and WHAT it contains, driven by the parsed
    user request, never by a model-elected tool call — OR (b) passed through
    the sanitizer like any other LLM-authored output. Both the
    "whether" and the "what" clauses appear explicitly; neither is left
    implied.
  - >-
    The exemption for deterministic command output is tightened so that
    deterministic BYTES alone no longer qualify: the section explicitly names
    the non-qualifying shape — output whose content is bot-authored and
    deterministic while the decision to emit it is the model's — and states
    that this shape is inside the sanitizer's mandate, not the exemption. The
    motivating finding (docs/plan/m1/redteam/M1-648-2026-07-19-r2.md) is cited
    as a stable in-repo anchor.
  - >-
    No existing sanitizer commitment is weakened: the enumerated LLM-authored
    surfaces, the "never passes through an LLM is not what makes it safe"
    reasoning, the bot-authored/no-inbound-interpolation condition, the
    match-set derivation paragraph, the per-occurrence audit-logging sentence,
    and the residual-risk paragraph on non-error deterministic output all
    survive the amendment intact.
  - >-
    The diff touches docs/spec/security.md and nothing else (`git diff
    --stat` shows exactly one file).
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
decomposed_from: M1-648
spec_amend_for: docs/spec/security.md §LLM output sanitizer
spec_amend_parent: M1-665
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-663: Spec: deterministic-delivery contract for chat help text

## Context

M1-648 died on a delivery defect, not a retrieval defect. Its `/redteam`
audit (`docs/plan/m1/redteam/M1-648-2026-07-19-r2.md`, medium INJECTION)
found that composed help text was appended to the chat reply AFTER the
sanitizer ran, and that the LLM elected both whether the append fired and
which privileged command's syntax it carried. The implementer's defense —
"the appended bytes are deterministic bundle text, so the deterministic-
output exemption applies" — was byte-true and contract-false: the spec's
exemption covers surfaces where a deterministic handler decides both
whether to emit and what to emit. When only the bytes are deterministic
and the emission decision belongs to a model whose context is
attacker-influenced (the D28 pre-fetch re-injects feed-derived text every
turn), the result is exactly what §LLM output sanitizer exists to prevent:
a chat reply carrying a real, copy-pasteable `/grant-admin` usage block at
an injected model's election, with no `LLM_OUTPUT_SANITIZED` audit row.

The current spec text does not close this reading. The exemption sentence
("It does **not** apply to deterministic command output") names a surface,
and the safety condition attached to it ("bot-authored — interpolates no
inbound-derived text") speaks only to byte provenance. Nothing states that
the *emission decision* must be deterministic too. This ticket amends the
section so the loophole M1-648 walked through is named and closed at spec
level, before M1-665 builds the compliant delivery path against it.

## Acceptance

See `acceptance`. In prose: §LLM output sanitizer gains the two-sided
delivery-ordering contract (deterministic end-to-end OR sanitized), the
exemption is tightened to require a deterministic emission decision and
not merely deterministic bytes, the non-qualifying shape is named
explicitly with the r2 audit cited as the motivating finding, and every
existing commitment in the section survives unweakened.

## Out-of-scope

See `out_of_scope`. This is a contract, not a feature: no code, no
allowlist-table row, no commands.md description of a mechanism that does
not exist yet. The one hard rule: if the contract seems too strict to
implement, the answer is escalation, never softer language.

## Notes

**Suggested wording skeleton** (non-binding; the implementer owns the final
prose): after the existing exemption sentence, add a paragraph along the
lines of —

> The exemption requires that the *emission decision* be deterministic,
> not merely the bytes. Command-usage or help text delivered into a
> chat-mode reply is exempt only when deterministic code decides both
> whether it is delivered and what it contains, driven by the parsed user
> request — never by a model-elected tool call. Output whose content is
> bot-authored but whose emission is elected by the model is LLM-authored
> for the purposes of this section and passes through the sanitizer like
> any other model output. (Motivating finding:
> docs/plan/m1/redteam/M1-648-2026-07-19-r2.md — a post-sanitize,
> model-elected append of privileged command usage.)

**Why "driven by the parsed user request" is the right trigger condition.**
The caller's own inbound text is the same trust grade `/help` itself runs
on — delivering help keyed off what the *caller* typed is the deterministic
command path's existing posture. The model's context, by contrast, is
attacker-influenced by the threat model's own admission (§Prompt-injection
defenses, `semanticSearch` row). The line between the two is exactly where
the M1-648 injection lived.

**Downstream consumers.** M1-665 implements the compliant chat delivery
path for command usage; M1-649 (conceptual help topics) inherits the same
contract for topic answers. Getting the language right here is what makes
those two tickets reviewable — their redteam gates audit against this
section.
