---
id: M1-636
title: "Per-user cap on concurrent interruptible requests across scopes"
status: done
created: 2026-07-16
last_updated: 2026-07-17
blocked_by: [M1-638]
files_budget: 14
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A per-user FAIR SCHEDULER. Design 06-messaging.md §6.3 defers one to a later
    revision and that deferral STANDS: this ticket adds a BOUND (a ceiling on a
    sender's concurrent share), never an ordering policy. Queue order remains
    arrival order with no fairness ordering of its own.
  - >-
    The per-(user, scope) in-flight guard itself (M1-634). Its one-request-per-
    scope semantics, its reject text and its /stop cancellation-handle role are
    unchanged. This ticket adds a coarser, per-user bound DERIVED from the M1-638
    turn-lifecycle registry — a count of the sender's non-terminal turns across
    scopes — never a second structure carrying its own state to keep in sync.
  - >-
    Changing infochat.chat.dispatch.max-concurrency's VALUE (stays 4, per the
    2026-07-16 operator decision), the CallerRunsPolicy saturation path, and
    LlmRateCap / RateCapBucket values or windows.
  - >-
    Feedback for a QUEUED request (M1-635). This ticket's new reject path is a
    terminal reply, not a progress placeholder.
acceptance:
  - >-
    A test drives one user's interruptible requests across MORE THAN ONE distinct
    scope (e.g. a DM plus a group) concurrently, and asserts that requests beyond
    the configured per-user cap are rejected with fixed guidance rather than
    admitted — while a DIFFERENT user's request in its own scope is still
    admitted concurrently (the cap binds per sender, never globally).
  - >-
    A test asserts a cap rejection consumes NO LlmRateCap token and NO in-flight
    slot, so the sender's next permitted request still succeeds — matching the
    existing check-order discipline documented at SummaryCommandHandler.java:271.
  - >-
    A test asserts the per-user count is released when a request completes, when
    it fails, and when it is cancelled by /stop, so a user cannot leak its own
    budget to zero across repeated turns.
  - >-
    docs/design/06-messaging.md §6.3's sentence "one sender's share is bounded by
    its per-minute rate-cap budget rather than by a fair scheduler" is amended to
    state the concurrency dimension this ticket adds, and still records the
    fair-scheduler deferral as standing.
  - >-
    infochat.chat.dispatch.max-concurrency is documented in the Provider's
    application.properties with its default (4), its >= 2 boot validation, and
    the caller-runs ceiling (pool + equal-depth queue, then inline on the
    transport thread), so an operator can discover and tune it without reading
    InterruptibleDispatcher's source.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterPerUserCapIT.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
  - D43
  - D46
clarity_check:
  date: 2026-07-17
  verdict: PASS
  warnings:
    - >-
      Three open design decisions are deferred to /m1-tick start: (1) the cap's
      exact value and overflow shape (Notes lean: reject, default cap 2,
      configurable), (2) submit-time vs on-worker admission check (Notes lean:
      submit), (3) whether docs/spec/security.md §Rate limiting must also be
      amended to enumerate the new control. None block implementability — each
      carries a stated recommendation — but acceptance items 1-3's exact
      assertions and items 4/5's completeness depend on how they resolve, so
      the start step must settle all three before naming the new test file in
      test_plan.adds (currently empty by design).
    - >-
      blocked_by: [M1-638] is still set but M1-638 is done and merged on main
      (80d2778d, resynced by e3c268bd) — dependency satisfied, informational
      only.
  blockers: []
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 627
      removed: 14
  - round: 2
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 762
      removed: 14
redteam_findings:
  - date: 2026-07-17
    category: DOS
    severity: low
    promise: |
      "Per-user interruptible concurrency (M1-636) — not a rate bucket: a
      ceiling on one sender's CONCURRENT interruptible requests (the same
      three surfaces as the LLM bucket above, queued + running) across all
      scopes ... Checked at intake before any token draw or slot
      acquisition" (docs/spec/security.md §Rate limiting, the bullet this
      diff adds). The referenced LLM bucket's surface list is "chat replies
      + on-demand /summary + /retry re-rolls".
    gap: |
      The cap check runs only inside the isInterruptible branch, and
      isInterruptible deliberately classifies /retry --digest as
      non-interruptible (D35), so that LLM-triggering re-roll is dispatched
      inline with no admission check and is never counted against the
      sender's ceiling. Delivery covers two-and-a-half of the "same three
      surfaces" the new bullet claims: a sender at cap can still originate
      one additional concurrent LLM-triggering request. Bounded in practice
      (inline dispatch serializes on the adapter's single transport thread,
      can never take a pool worker — the "cannot occupy every dispatch
      worker" clause holds — and LlmRateCap still meters it), hence low:
      the amended spec text promises more than the code enforces.
    repro: |
      1) Registered user sends a DM chat message and a group @mention;
      both admitted, cap 2 reached, both RUNNING. 2) With a frozen digest
      anchor in a third scope, send /retry --digest there. 3)
      isInterruptible returns false, the M1-636 check never executes, the
      summarizer call runs inline — cap+1 concurrent LLM-triggering
      requests.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-07-17
    verdict: FINDINGS
    base: 9e0b7304156ee5bf5b6012a0c1099e872174d8cf
    head: working-tree@m1/M1-636-per-user-cap-on-concurrent-int
    verdict_file: docs/plan/m1/redteam/M1-636-2026-07-17.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One low (spec-text overpromise: the new security.md bullet claims
      three-surface parity with the LLM bucket, but /retry --digest is D35
      non-interruptible, takes no pool worker and is not cap-checked;
      enforcement matches the ticket's pool-share design). Two out-of-model:
      cross-scope activity disclosure in the group-delivered reject reply
      (same class as the accepted rate-cap reply, undocumented), and no
      boot coupling between per-user-cap and max-concurrency (an operator
      setting cap >= pool voids the single-sender property; config is
      trusted per the threat model). RESOLVED via escalate → refine: the
      spec bullet's surface claim narrowed in-branch to the D35
      interruptible class (--digest exclusion stated) and the disclosure
      accepted-and-documented in the same bullet; out-of-model item 2 and
      coherent inline-LLM bounding routed to a post-merge follow-up
      ticket.
  - date: 2026-07-17
    verdict: CLEAN
    base: 9e0b7304156ee5bf5b6012a0c1099e872174d8cf
    head: working-tree@m1/M1-636-per-user-cap-on-concurrent-int (refine 9623381e + remediation)
    verdict_file: docs/plan/m1/redteam/M1-636-2026-07-17.md
    out_of_model_count: 2
    note: |
      Re-audit after the in-branch remediation: CLEAN. The prior low DOS
      finding verified remediated — the amended security.md bullet scopes
      the claim to the D35 interruptible class with the --digest exclusion
      stated and matches the enforcement predicate exactly. Two
      out-of-model notes: the cap-vs-pool boot-coupling question
      re-flagged (already routed to the post-merge follow-up ticket so it
      is not silently dropped), and colluding-identity pool saturation
      (two identities can jointly hold all four workers) — a documented
      residual: the promise is per-sender share, per-user fairness is an
      explicit v1 non-commitment and extra identities cost admin-issued
      invites (Sybil non-commitment).
escalations:
  - date: 2026-07-17
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Review round 1 was APPROVE (all checks PASS). The escalation trigger
      is the /redteam M1-636 --in-progress audit (verdict FINDINGS: 1 low,
      2 out-of-model — docs/plan/m1/redteam/M1-636-2026-07-17.md): the new
      security.md §Rate limiting bullet claims the cap covers "the same
      three surfaces as the LLM bucket", but /retry --digest is D35
      non-interruptible (inline, no pool worker) and is not cap-checked —
      the spec text overpromises; the enforcement matches the ticket's
      pool-share design.
revisions:
  - date: 2026-07-17
    reason: >-
      redteam-finding refine (post-APPROVE r1, pre-commit). Narrow the NEW
      docs/spec/security.md §Rate limiting bullet this diff adds so its
      surface claim matches the shipped control (low finding): the cap
      covers the D35 interruptible class — chat replies, on-demand
      /summary, /retry re-rolls EXCEPT --digest, which is D35
      non-interruptible, runs inline on the transport thread, takes no
      pool worker, self-serializes to at most one, and stays metered by
      the per-minute LLM bucket. Same edit documents the reject-reply
      cross-scope disclosure as accepted (out-of-model item 1, same class
      as the per-user rate-cap reply). Out-of-model item 2 (cap >= pool
      coupling) and the coherent bounding of inline LLM-triggering work
      (counting /retry --digest) go to a follow-up ticket filed after
      merge — NOT bolted on here. Code is unchanged by this refine: the
      enforcement already matches the ticket's pool-share design.
      Pre-refine frontmatter snapshot below; the full verbatim pre-refine
      ticket is the parent of the refine commit on the branch.
    snapshot:
      files_budget: 14
      files_scope: []
      acceptance_count: 6
      out_of_scope_count: 4
      note: >-
        Round-1 review APPROVEd the pre-refine diff (13 files, +627/-14)
        before the redteam gate surfaced the 1 low DOS finding (spec-text
        overpromise) + 2 out-of-model items.
---

# M1-636: Per-user cap on concurrent interruptible requests across scopes

## Context

`InFlightTracker` keys its slot on `(userId, scopeKind, scopeId)`
(the `ScopeKey` record), which bounds a sender to one in-flight
interruptible request **per scope** — not per sender. A user who is a member of
several groups holds one slot in each, plus one in their DM. With
`InterruptibleDispatcher`'s pool at its default of 4, a single user present in
three groups plus a DM can occupy every worker on their own, and the next
sender's turn waits behind them.

Nothing collapses when that happens — request 9 degrades to caller-runs, the
transport's bounded inbound queue back-pressures exactly as M1-634 documented,
and `LlmRateCap` still bounds the sender to 10/min (20 under `%remote-llm`). The
cost is latency borne by other users, and the ceiling is low enough (4 workers)
that ordinary group membership reaches it without any hostile intent.

**Blocked on M1-638.** M1-638 replaces the thread-keyed slot with a turn
lifecycle that exists from submit, keyed by turn identity and indexed by scope.
This ticket's cap becomes a *query* over that registry — count a sender's
non-terminal turns across scopes — rather than a third structure standing beside
`InFlightTracker`. Filing order is the only reason this ticket predates that
model; the 2026-07-16 design review that produced M1-638 established it, and two
of the notes below were written against the pre-M1-638 constraints and are
revised accordingly.

This is a known boundary, not a discovered bug: M1-634's `out_of_scope` states
"a sender's share stays bounded by `LlmRateCap`", and `06-messaging.md` §6.3
defers a fair scheduler. The 2026-07-16 concurrency review re-opened it because
`LlmRateCap` bounds **rate**, not **concurrency**, and the pool made concurrency
a scarce shared resource for the first time. A per-minute budget does not stop
one sender from holding every worker at one instant.

The distinction this ticket rests on: a **cap** is not a **scheduler**. Adding a
ceiling on a sender's concurrent share leaves arrival order untouched and does
not implement fairness — so §6.3's deferral of a per-user-fair scheduler stands,
while its claim about what bounds a sender's share needs the concurrency
dimension added.

This ticket also carries the operator-facing documentation of the concurrency
bounds (see §Acceptance): `infochat.chat.dispatch.max-concurrency` currently
exists **only** as a `@ConfigProperty` default at
`InterruptibleDispatcher.java:79` — it appears in neither the Provider's
`application.properties` nor `prod/runtime/application.properties`, so an
operator cannot discover it without reading the source. Per the 2026-07-16
operator decision the value stays 4; it must become tunable-by-discovery.

## Acceptance

See the YAML `acceptance:` list. In prose: requests beyond a per-user cap across
scopes are rejected with fixed guidance while other senders stay admitted; a
rejection consumes neither an LLM token nor a slot; the count is released on
completion, failure and `/stop`; §6.3 is amended; the concurrency knob is
documented with its default, its boot validation and the caller-runs ceiling;
and `mvn -pl infochat-provider -am verify` is green.

## Out-of-scope

Covered in the YAML `out_of_scope:`. Most importantly this is **not** the fair
scheduler §6.3 defers, and it does not touch the per-scope guard's semantics or
its `/stop` cancellation-handle role. The `max-concurrency` VALUE stays 4 — this
ticket documents that knob, it does not retune it.

## Notes

**Resolved at `/m1-tick start` (2026-07-17, operator):** all three open
decisions taken on their recommended leans — (1) overflow REJECTS with fixed
guidance (new bundle key `error.chat.per_user_cap` + cs twin), (2) default cap
**2**, shipped as config knob `infochat.chat.dispatch.per-user-cap`, (3) the
check runs **at submit** on the transport thread, before `registerQueued`, so a
rejection spends no pool slot and leaves no registry entry; the per-SCOPE
guard's reject stays on the worker untouched. Additionally the operator chose
to amend `docs/spec/security.md` §Rate limiting with the new control (the
closing-bullet question below). `test_plan.adds` names the two-sender admission
test accordingly.

**Redteam remediation (refine, 2026-07-17, operator-accepted):** the audit
(`docs/plan/m1/redteam/M1-636-2026-07-17.md`, 1 low DOS) found the NEW
security.md §Rate limiting bullet claims "the same three surfaces as the LLM
bucket", but `/retry --digest` is D35 non-interruptible — dispatched inline,
never takes a pool worker, self-serializes to one, still metered by the
per-minute bucket — and is deliberately NOT cap-checked. The remediation is
doc-only, in-branch: scope the bullet's surface claim to the D35 interruptible
class with the `--digest` exclusion stated, and document the reject-reply
cross-scope disclosure as accepted (out-of-model item 1). The follow-up work —
whether inline LLM-triggering dispatch should be counted/bounded coherently,
plus the cap-vs-pool boot-coupling question (out-of-model item 2) — is filed as
a separate ticket after merge, per the operator's option-1 decision. The
06-messaging.md §6.3.7 amendment already says "interruptible turns" and needs
no change.

**Open-decision (resolve with the operator at `/m1-tick start`): the cap's value
and its overflow behaviour.**

- *Reject with guidance* (Lean) — consistent with the per-scope guard's existing
  "request already in progress" reply, cheap, and it tells the user something
  true. Needs a new bundle key plus its cs twin (D43 bilateral keyset — a missing
  cs key fails `BundleLoaderTest`).
- *Queue instead* — invisible to the sender, but it re-creates the silent wait
  M1-635 exists to remove, and it needs a fairness policy to decide who goes
  first, which is exactly what §6.3 defers. Not recommended.

**Lean: reject, default cap 2, configurable.** A cap of 2 lets a user hold a DM
turn and one group turn concurrently while leaving half the default pool for
other senders. The value should be a config knob documented alongside
`max-concurrency`, not a constant.

**Check-order discipline is load-bearing.** `SummaryCommandHandler.java:271`
documents why the in-flight slot is checked *before* the LLM bucket: "neither
check consumes anything on a rejection — an already-in-flight rejection takes no
bucket token, and a rate-cap rejection records no timestamp, so the next
permitted request still succeeds." A cross-scope cap is a third check and must
join that order without consuming anything on its own rejection. The second
acceptance item pins this.

**Release discipline is where this will break if it breaks.** A standalone
counter has no identity check, so a double-decrement or a missed decrement on the
`/stop` path silently corrupts the budget in the sender's favour — or locks the
sender out permanently. Deriving the count from M1-638's registry removes that
hazard by construction: the turn reaching a terminal state IS the release, so
there is no second number that can drift from the first. The third acceptance
item still pins all three release paths (complete, fail, `/stop`), because
"derived" is a design intention until a test proves it. The stale-safety pattern
to match wherever state is freed remains `InFlightTracker.release`'s identity
comparison — post-M1-638 it reads the held handle back inside a `computeIfPresent`
and clears it only when it is the same object (`entry.running == handle`), so a
late release by a superseded worker cannot evict a newer holder. (M1-638 replaced
the former two-arg `ConcurrentHashMap.remove(key, value)` idiom: the map's value
is now the per-scope entry rather than the handle itself, so identity is compared
inside the lambda instead of by the map.)

**Where the check runs is now OPEN — M1-638 removed the constraint.** This note
previously read that admission "must run on the worker", because `tryAcquire`
captures `Thread.currentThread()` as the cancellation target; the consequence was
that a rejected request still spent a pool slot to discover its own rejection,
and the cap bounded concurrent *LLM work* rather than concurrent *submissions*.
M1-638 gives a turn an identity at submit that does not depend on a thread, so
the count is readable on the transport thread and this cap MAY reject before a
slot is spent. Resolve at `/m1-tick start`: rejecting at submit is the lean (it
spends nothing and bounds submissions), but it must not drag the per-SCOPE
guard's reject off the worker with it — M1-638's `out_of_scope` pins that reject
to its current timing and text.

**Test-plan shape.** `InFlightTrackerTest` is modified ADD-ONLY: new methods
pinning the per-user cross-scope count query and its release on completion,
failure and `/stop`. No existing assertion changes — `out_of_scope` entry 2 pins
the per-(user, scope) guard's observable semantics, so nothing already asserted
there may move. `test_plan.adds` is deliberately empty: the file carrying
acceptance items 1-2's two-sender admission test depends on the submit-vs-worker
fork above, so it is named at `/m1-tick start` once that fork is resolved rather
than guessed at filing time.

**Alternative considered: do nothing.** `LlmRateCap` at 10–20/min already bounds
a sender's total cost, and with the current 4-user population the ceiling is
unreachable in practice. The case for acting now is that the exposure scales with
group membership rather than with malice, and this is the control that has to
exist before the bot is opened to senders who are not known personally.

- Adjacent code: the M1-638 turn-lifecycle registry (its post-merge shape) —
  this ticket's per-user count is a query over it, so read it before designing
  the cap rather than reaching for a counter of your own.
- Adjacent code: `LlmRateCap.java:14` — the javadoc explaining why chat,
  `/summary` and `/retry` deliberately share ONE bucket ("a caller cannot bypass
  the cap by switching surfaces"). Any new cap must not reopen that bypass by
  binding to only one surface.
- Design note to amend: `docs/design/06-messaging.md` §6.3, the "Per-user
  fairness is **not** implemented in v1" paragraph.
- Whether `docs/spec/security.md` §Rate limiting must also enumerate the new
  control is for the clarity gate / start prompt to settle; the design-note
  amendment is required either way because the ticket makes its current sentence
  false.
