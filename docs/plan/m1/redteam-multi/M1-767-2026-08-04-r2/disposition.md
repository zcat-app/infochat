# Round-3 disposition (M1-767)

Audit: `/redteam-multi` re-audit (claude, opencode, codex), 2026-08-04,
diff = working tree vs fork `f9c068b8` (RE-AUDIT framing appended to the
rendered prompts per the /redteam re-audit rule; round-2 findings listed
and CLEAN explicitly authorised).

## Verdicts

- **claude**: FINDINGS — 0 critical, 0 high, 1 medium, 0 low; 2 out-of-model.
- **opencode**: CLEAN.
- **codex**: CLEAN (short-form reply only; verdict file carries the
  four-line reply, no structured body — accepted per the script's
  non-empty-file rule).

## Finding + disposition

**claude F1 (medium/DOS)** — the round-2 PRE-CHARGE gate
(`RetryCommandHandler.handleDigestRetry` consulting
`SystemLlmBudget.canStartRender()` before `retryDigest`) refuses the
retry's REPLAY leg — re-delivery of persisted, sanitized section bytes
with zero provider calls, zero render, no `DigestWorker` involvement — on
the strength of the deployment-wide counter, denying the only
delivery-recovery path while the PT24H window is at its ceiling. A
replay-row expiry then falls through to the fallback re-run, which the
same gate also refuses, losing the undelivered categories.

Single-auditor finding (opencode + codex CLEAN); falsification pass
verified it against the code: **REAL**. The gate fired before the
replay-vs-fallback decision (`DigestRetryService.retryDigest` lines
129-141).

**Fix (in-branch, round-3 rework):** the gate moves into
`DigestRetryService.fallbackRerun` — refusing ONLY the LLM-spending leg
before the worker and before the cooldown stamp, returning the new
`RetryResult.SYSTEM_BUDGET_REFUSED` — and `RetryCommandHandler` refunds
the per-user LLM token on that result (the group-cap-rejection refund
shape). The replay leg is never gated. The D47 per-group draw follows the
pre-existing conservative non-render-result convention (documented in
`RetryCommandHandler`'s gate comment). Refused re-runs record nothing, so
admission recovers as the window drains.

Tests: `DigestRetryServiceTest.fallbackRefusedWhenSystemBudgetExhausted`
(refusal, worker untouched), `DigestRetryServiceTest.
replayUnaffectedByExhaustedSystemBudget` (round-3 regression),
`RetryDigestCommandTest.retryDigest_refundsTokenWhenSystemBudgetRefused`
(token refund).

## Out-of-model (claude, advisory — not filed)

1. Refusal path adds 2-3 cheap DB round-trips per attempt while a group
   admin hammers during exhaustion (audit row + notifier UPSERT) — bounded
   by the D47 per-group command sub-bucket; the inline comment's
   "hammering stays audit-visible" is a visibility argument, not a
   bounding one. Flagged for the spec/ticket owner.
2. The distinct refusal string is a cross-tenant load oracle (a group
   admin can trace deployment-wide digest consumption by polling
   `/retry --digest`) — admin-tier, metadata-only; the per-user
   concurrency reply precedent accepts analogous disclosure. Noted for
   the spec owner.

## Round-2 fixes — verified closed

All three auditors verified: (a) the M1-763 slot-window cancellation leg
is named (javadoc, ticket acceptance, application.properties operator
note incl. the up-but-slow trigger); (b) the "only scheduled-route-only
entry point" claim is corrected in all four places and the retry policy
is decided and implemented; (c) the monitor-holding breach signal stays
structurally fixed.

## SUPERSEDED (2026-08-04, round-4 rework)

The round-3 MECHANISM described above (fallback-boundary gate in
`DigestRetryService.fallbackRerun` returning `SYSTEM_BUDGET_REFUSED` +
per-user token refund in the handler) was superseded the same day by the
round-4 rework: the round-4 multi-audit (opencode low, codex medium)
found the post-charge refusal deterministically burns the D47 per-group
token (no group-bucket refund exists) and that the fallback-gate →
executeSlot-gate interval spans DB reads, not milliseconds. The refusal
decision moved to a single PRE-CHARGE probe in `RetryCommandHandler`
(`DigestRetryService.retryLeg` + `SystemLlmBudget.canStartRender()`),
refusing before ANY charge; the fallback gate, `SYSTEM_BUDGET_REFUSED`
and the refund arm were removed. The round-3 FINDING itself (replay leg
must never be refused) stands and is preserved by the probe design.
