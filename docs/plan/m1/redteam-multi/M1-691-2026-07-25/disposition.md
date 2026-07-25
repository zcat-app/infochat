# Disposition — M1-691 redteam-multi (kimi + opencode), 2026-07-25

## Verdicts

- **kimi**: FINDINGS — 1 low (INJECTION @ `OutboundDelivery.java:142-144`,
  trust-boundary-tightening).
- **opencode**: CLEAN.
- Corroborated: 0. Single-auditor falsification candidate: the kimi finding.

## The kimi finding, assessed against the codebase

The finding's REPRO hypothesizes a future render path calling
`adapter.send/update/finalizeMessage` directly, bypassing the chokepoint
invisibly (the `](` break emits no audit row). A census of the provider
module refutes the *current* bypass: every `.send(`/`.update(`/`
.finalizeMessage(` call site lives in `OutboundDelivery` or
`DigestDelivery.RecordingAdapter`, and that decorator is *invoked by*
`OutboundDelivery.deliverSequenceToGroup`, not around it. The handlers
kimi names ("fixed-reply / admin-notification paths") all route through
`OutboundDelivery`. So there is **no current bypass**; the finding is
about future-proofing, not a present defect.

The legitimate kernel: nothing *mechanically* prevents a future caller
from calling `adapter.send` directly, and such a bypass would be
invisible (no test, no audit). The spec claim is already scoped to the
chokepoint ("carried once at OutboundDelivery", enumerating the four
path types), so it does **not** overclaim relative to today's reality.

## Decision

- **Do not soften the spec.** The `](` guarantee text is anchored to
  `OutboundDelivery` and the enumerated path types; it is honest today.
  Softening would weaken a true claim and (per user concern) risks
  becoming permanent.
- **File a follow-up ticket** for a chokepoint-routing guard (ArchUnit
  rule: only `OutboundDelivery` + `DigestDelivery.RecordingAdapter` may
  call `MessagingAdapter.send/update/finalizeMessage`) so the invariant
  becomes structural rather than conventional — same shape as the
  closed-list CI parity test the spec holds up as the bar. No ArchUnit
  dependency exists yet, so the ticket adds it.
- **Accept the Unicode out-of-model as in-model.** Both auditors flagged
  that the chokepoint does a raw-byte ASCII `replace("](", "] (")` with
  no canonicalization, so fullwidth `］（` (or bidi/zero-width-wrapped) in
  an unsanitized operand is not broken. The spec deliberately commits to
  the two-ASCII-character model and asserts no client renders fullwidth
  `］` as link syntax; this is a documented client-rendering assumption,
  not a gap introduced by this ticket.

## Outcome for M1-691

M1-691 ships as-is (APPROVE round 1). The follow-up ticket carries the
guard; the spec claim stays honest and scoped.
