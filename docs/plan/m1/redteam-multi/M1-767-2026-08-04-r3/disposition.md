# Round-4 disposition (M1-767)

Audit: `/redteam-multi` re-audit (opencode, codex; claude UNAVAILABLE —
session rate limit 429, no verdict written), 2026-08-04, diff = working
tree vs fork `f9c068b8` (RE-AUDIT framing appended to the rendered
prompts; round-3 finding + fix listed, CLEAN authorised).

## Verdicts

- **opencode**: FINDINGS — 0 critical, 0 high, 0 medium, 1 low; 2 out-of-model.
- **codex**: FINDINGS — 0 critical, 0 high, 1 medium, 0 low; 0 out-of-model.
- **claude**: UNAVAILABLE (session rate limit mid-audit; reply carries no
  conclusion).

## Findings + disposition

**opencode (low/DOS)** — the round-3 post-charge fallback gate burns the
D47 per-group token on `SYSTEM_BUDGET_REFUSED`: the group sub-bucket is
drawn before the service is consulted and `RateCapBucket` exposes no
group-token refund, so each refusal drains the group's shared LLM budget
for an LLM call that never happened; repeat refusals (no cooldown stamped)
are bounded only by that very bucket. Also: the round-3 acceptance's
"millisecond-scale" race claim is wrong — the fallback-gate→executeSlot-gate
interval spans the cache-boundary read, post collection and group-metadata
read.

**codex (medium/DOS)** — the two-gate race: a re-run admitted by the
fallback gate at `fallbackRerun` is independently re-checked (and degraded)
by `executeSlot`; when the window fills between the checks, the retry
returns SUCCESS with the cooldown stamped and both the per-user and D47
draws retained, though no LLM call happened. Repeatable → a group admin can
consume the shared group retry capacity without any fallback provider call.

Both independently converge on the same root cause: **any gate that
refuses or degrades AFTER the charges burns the group's shared budget for
a system-level denial.** Both verified against the code — REAL.

**Fix (in-branch, round-4 rework):** the post-charge gate/refund shape is
ABANDONED. The refusal decision moves to a single PRE-CHARGE probe in
`RetryCommandHandler.handleDigestRetry`:
- new `DigestRetryService.retryLeg(groupId)` → `REPLAY | FALLBACK | NO_PRIOR`
  (same reads `retryDigest` performs; documented as an estimate),
- FALLBACK leg + `SystemLlmBudget.canStartRender()` false → distinct
  bundle reply, and NO charge of any kind: no per-user token, no D47
  draw, no cooldown stamp, `retryDigest` untouched.
- The `fallbackRerun` gate, the `SYSTEM_BUDGET_REFUSED` enum value and the
  handler's refund arm are REMOVED.
- The `executeSlot` admission gate remains the authoritative check on both
  routes: a window that fills after the pre-charge check DEGRADES the
  re-run (delivers a degraded digest, SUCCESS) and the already-drawn
  tokens follow the pre-existing conservative non-render-result
  convention — documented honestly, with the interval (spanning
  `retryDigest`'s reads and the worker's collection) stated instead of
  "millisecond-scale". The refusal path draws nothing, so it is race-free
  by construction and cannot burn a token.

## Out-of-model (opencode, advisory — not filed)

1. In-memory per-JVM window (restart resets; multi-instance would multiply
   the ceiling) — spec accepts in-memory state for the breaker; flagged.
2. Refusal path's `notifyOnce` JDBC UPSERT per attempt + the distinct
   refusal string as a cross-tenant load oracle — bounded by the D47
   per-group COMMAND sub-bucket, admin-tier, metadata-only; flagged for
   the spec owner.

## Prior-round fixes — verified closed by this audit's auditors

Round-3 fix (replay leg never refused) verified closed by both auditors;
round-2 fixes (cancellation leg named; false claim corrected; monitor
fix) re-verified. The round-3 MECHANISM (post-charge gate) is superseded
by this round's pre-charge probe — see the round-3 disposition file for
the superseded record.
