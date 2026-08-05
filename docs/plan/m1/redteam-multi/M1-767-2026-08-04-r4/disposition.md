# Round-5 disposition (M1-767)

Audit: `/redteam-multi` re-audit (claude, opencode, codex), 2026-08-04,
diff = working tree vs fork `f9c068b8` (`diff.patch`, captured 00:52;
RE-AUDIT framing appended to the rendered prompts, round-4 finding + fix
listed, CLEAN authorised).

Recorded 2026-08-05, after the fact: the round-5 run completed at 01:07 but
its disposition was never written — the session ended mid-remediation. This
file reconstructs it from the verdict files, which are the primary evidence.

## Verdicts

- **claude**: CLEAN — 0 findings; 2 out-of-model.
- **opencode**: CLEAN — 0 findings; audit trail against each focus area of
  the round-5 brief.
- **codex**: FINDINGS — 0 critical, 0 high, 1 medium, 0 low; 0 out-of-model.

Both CLEAN auditors verified the round-4 root cause (post-charge gate
burning the D47 token on refusal) structurally closed, and the round-2/3
fixes re-verified.

## Finding + disposition

**codex (medium/DOS)** — the pre-charge probe is not an atomic
authorization of the leg it authorizes. `DigestRetryService.retryLeg`
reads the latest cache row and the persisted-section presence outside any
lock; `retryDigest` repeats those reads later. A concurrent scheduled
render persisting sections in that interval flips the would-be leg
FALLBACK → REPLAY, so a refusal decided on the stale FALLBACK result gates
an operation that would, at execution time, have consumed no LLM call —
against the stated never-gate-REPLAY invariant.

**Falsification pass (2026-08-05).** The MECHANISM is real and verified
against the code: the probe genuinely is a separate unlocked read. The
SEVERITY is not. The refusal returns at `RetryCommandHandler` before
`llmRateCap.tryAcquire` and `rateCapBucket.tryAcquireGroupLlm`, and never
enters `retryDigest`, so no per-user token, no D47 draw and no cooldown
stamp — it refuses FREE. The re-issued retry probes REPLAY and proceeds,
so the denial self-heals in one message round-trip. The repro also leans
on a counterfactual: on the refusal path `retryDigest` is never called, so
"before `retryDigest` can observe" compares against a timeline that does
not exist, and at the instant of the probe FALLBACK was the correct
answer. Nor is it attacker-timeable — the section-persist must land inside
the probe-to-return interval. Graded LOW, carried as a documented residual
rather than fixed: no ordering closes it, only a lock, and the refusal's
zero cost is what bounds it.

**Round-6 rework (2026-08-05).** Two changes, neither of them a fix TO the
codex finding:

1. **A regression the round-5 audit never saw was reverted.** At 01:08 —
   one minute after codex's verdict, and after `diff.patch` was captured —
   the handler's gate was reordered to `!canStartRender() && retryLeg(...)
   == FALLBACK` to shrink the probe-to-decision interval. But
   `canStartRender()` is not a pure predicate: on its false branch it calls
   `adminNotifier.notifyOnce(...)`, which unconditionally opens a pooled
   connection and UPSERTs (`ThrottledAdminNotifier`; the coalescing
   suppresses the emission, not the round-trip). Ordering it first made
   every `/retry --digest` reach it, so during an exhausted window a
   REPLAY-leg retry — the never-gated, zero-LLM leg — emitted the operator
   breach signal "scheduled digest degraded" for a digest that was not
   degraded, plus a DB write per attempt. Reverted to probe-first, which
   is the shape both CLEAN auditors saw. The flip bought nothing real: it
   never addressed the codex finding (the probe is non-atomic in either
   order), and the three DB reads it saved sit on an admin-only,
   D47-rate-limited command that already performs four round-trips before
   that point. Pinned by `RetryDigestCommandTest.
   retryDigest_replayLegProceedsWithoutConsultingTheSystemBudget`, which
   now asserts `canStartRenderCalls == 0`; the prior test overrode
   `canStartRender()` wholesale, which stubbed the side effect out of
   existence and is why the suite could not see it.
2. **The "never gated" claim was qualified at all nine live sites**, as
   "never gated in steady state", with the residual explained once at the
   canonical anchor (`DigestRetryService.retryLeg`). The 01:08 edit had
   qualified two sites and left nine asserting it flat — the same
   partial-correction failure round 2's R2-F2 flagged, where a load-bearing
   claim repeated in many places was corrected only where the author was
   already editing. Sites: `RetryCommandHandler` (field comment + gate),
   `DigestRetryService` (class javadoc + `retryLeg`), `DigestWorker`,
   `SystemLlmBudget`, `DigestRenderer`, `application.properties`,
   `RetryDigestCommandTest`, M1-767 `out_of_scope` x2, M1-769. The
   `redteam_audits:` round-3 note is left as written — it records what
   round 3 decided, correctly.

   One further stale claim surfaced in that sweep: M1-767's `out_of_scope`
   still described the ABANDONED round-3 mechanism ("gated at the fallback
   boundary in `DigestRetryService`, token refund on refusal"). Round 4
   removed the `fallbackRerun` gate, the `SYSTEM_BUDGET_REFUSED` enum value
   and the refund arm entirely. Corrected to the pre-charge shape.

`mvn verify` green after the rework (`.scratch/m1-tick-test-M1-767-r5.log`,
BUILD SUCCESS, 7 modules, 0 failures / 0 errors).

## Out-of-model (claude, advisory — not filed)

1. In-memory, per-JVM budget window: a restart, redeploy or crash-loop
   resets a 24-hour control to zero, and a multi-instance Provider would
   multiply the effective ceiling by the instance count. The threat model
   already accepts in-memory state for the circuit breaker (§Failure
   handling); restarts and deployment topology are operator actions, not
   external-adversary levers. Flagged for the spec owner.
2. Scope note: the accounting and fairness residuals (per-group share,
   deterministic `staggerOffset` starvation, exact per-call draw) are
   M1-769 by prior disposition, not new.

## Re-audit owed

This rework invalidates the audit it answers. Round 6 re-audits the NEW
working tree and must explicitly authorise CLEAN — see
`docs/process/redteam-prompt.md` and the re-audit rule in the redteam
skill.
