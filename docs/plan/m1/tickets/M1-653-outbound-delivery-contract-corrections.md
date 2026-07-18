---
id: M1-653
title: "Correct the outbound delivery contracts: correlationId javadoc and §6.3.5"
status: done
created: 2026-07-18
last_updated: 2026-07-18
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 70
      removed: 21
blocked_by: []
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/OutboundMessage.java
  - docs/design/06-messaging.md
  - docs/spec/messaging.md
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Building any dedup or idempotency mechanism. This ticket changes
    documentation and one javadoc block only; no behavior changes, no new
    table, no adapter change. Gap-filling redelivery for digests is M1-652.
  - >-
    Changing OutboundMessage's shape. The record's four components stay as
    they are, and correlationId stays non-null; only the javadoc claim about
    its STABILITY is corrected.
  - >-
    Changing any correlationId minting site. The 40 UUID.randomUUID() sites
    and the 3 stable-id sites are all left exactly as they are — this ticket
    documents what they do, it does not converge them. If convergence is
    wanted it is a separate ticket.
  - >-
    The retry ladder, its backoff, max-attempts, or the per-group
    permanent-failure counter.
acceptance:
  - >-
    OutboundMessage's class javadoc no longer claims the correlation id is
    "stable across retries of the same logical outbound". Verified 2026-07-18
    that this claim is false: ALL BUT THREE `new OutboundMessage(...)` sites
    in infochat-provider/src/main pass UUID.randomUUID().toString(), so a
    fresh id is minted on every construction. (Re-measured 2026-07-18 at
    implementation time: 47 sites, 44 random, 3 stable. The structural claim
    — exactly three stable-id sites — is the load-bearing one and is what the
    replacement text must state; the absolute counts drift as handlers are
    added and MUST NOT be written into the javadoc or the spec.) The
    replacement text states what is actually guaranteed (non-null) and that
    stability is per-site and NOT something a consumer may rely on.
  - >-
    docs/design/06-messaging.md §6.3.5 no longer states adapters SHOULD
    deduplicate by correlationId over a 60-second window. Verified 2026-07-18
    that no adapter does: grep correlationId across
    infochat-messaging-adapter/src/main/java returns 10 hits (re-measured
    2026-07-18 at implementation time; the ticket as filed said 8), all
    javadoc or record-field plumbing, ZERO lookups — the zero is the
    load-bearing figure; SimpleXAdapter.java:562 stores the id
    into the returned handle and never reads it back, and
    SignalJsonRpcClient.java:430 and InMemoryAdapter.java:129 send
    unconditionally. §6.3.5 instead states v1's actual position.
  - >-
    §6.3.5 records why the 60-second window was unusable independent of
    whether anyone implemented it: backoff sleeps total under 750 ms
    (retry.base-delay-ms=250, growth-factor=2.0, max-attempts=3), but wall
    clock is dominated by SimpleX's 30 s per-send ack timeout
    (SimpleXAdapter.java:91), so three attempts reach ~90 s — past the window
    — and a chunked send multiplies that per chunk
    (SimpleXAdapter.java:552-556).
  - >-
    docs/spec/messaging.md §Failure handling states the v1 delivery guarantee
    honestly: outbound delivery is at-least-once, a retry after an ambiguous
    send failure may duplicate a message, and no component suppresses that.
    The reason is recorded — the adapter is the only layer that could observe
    whether an ambiguous transmit landed, so no chokepoint-level mechanism can
    provide the guarantee.
  - >-
    A new decision D64 records the position. D62 is the highest decision in
    docs/spec/decisions.md (verified 2026-07-18) and D63 is claimed by M1-642,
    so D64 is the next free number.
  - >-
    No behavior changes. No non-javadoc line of OutboundMessage.java is
    modified, and no test is added, changed or deleted.
  - mvn verify is green
test_plan:
  adds: []
  preserves:
    - >-
      All existing tests. This ticket changes one javadoc block and three
      documentation files; nothing it touches is executable. mvn verify runs
      because a .java file is in the diff and must still compile.
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/design/06-messaging.md §6.3.5
decision_refs:
  - D64
---

# M1-653: Correct the outbound delivery contracts

## Context

Two documented contracts describe outbound delivery behavior that does not
exist. Both were load-bearing in real ticket decisions, and correcting them is
the durable half of what the M1-652 investigation produced.

**1. `OutboundMessage`'s javadoc promises stability the callers do not
provide.** Current text (`OutboundMessage.java:8-15`):

> `correlationId` ties an outbound reply back to its inbound trigger so
> adapters that deduplicate on retry (§6.3.5) can do so deterministically.
> When the outbound is not a reply (e.g., a scheduled digest) the correlation
> id is adapter-defined; the SPI commitment is that the id **is non-null and
> stable across retries of the same logical outbound**.

The non-null half holds. The stability half does not: 40 of the 43
`new OutboundMessage(...)` sites in `infochat-provider/src/main` pass
`UUID.randomUUID().toString()` — every command handler, `InboundRouter:1374`
for chat replies, `StageProgressNotifier:348` for progress. Only three mint a
stable id: `DigestWorker.java:212`, `ApproveGroupCommandHandler.java:260`,
`RejectGroupCommandHandler.java:304`.

**This sentence caused two ticket defects.** M1-642 was filed asserting the
outbound chokepoint "already does its idempotency/dedup work per message".
M1-652 was then filed to build that consumer, on the premise that the contract
existed and only the consumer was missing. Both authors read this javadoc,
both believed it, neither checked the call sites. M1-652's original design was
withdrawn once they were checked.

**2. §6.3.5 specifies adapter dedup that no adapter implements**, over a
window too short to have worked anyway. Verified 2026-07-18: `grep
correlationId` across `infochat-messaging-adapter/src/main/java` returns 8
hits, all javadoc or record-field plumbing, zero lookups.

Neither correction changes behavior. The value is that the next person to
build on these contracts reads what is true.

## Acceptance

See `acceptance`.

## Out-of-scope

See `out_of_scope`. Notably this does NOT converge the 40 random-id sites and
the 3 stable-id sites, and does NOT add any dedup mechanism.

## Notes

**Why not just fix the code to match the docs.** Because the guarantee is not
implementable where it was documented. The duplicate that reaches a user is
the ambiguous ack — the adapter transmits, the 30 s SimpleX ack times out
(`SimpleXAdapter.java:91`), the ladder re-sends, the recipient gets two
copies. Suppressing that requires knowing whether the ambiguous transmit
landed, which only the adapter or the transport can know. A Provider-side
chokepoint records success only after the adapter reports it, so it is blind
to exactly this case. v1 accepts at-least-once and says so; a real fix is
adapter/transport-level work and is not filed.

**Why the three stable-id sites are left alone.** Nothing consumes
`correlationId` for dedup, so a stable id is inert rather than harmful today.
Changing them would be behavior change in a ticket whose whole point is that
it has none. Their existence is documented so the asymmetry is not mistaken
for a guarantee — that mistake is what this ticket exists to prevent.

**Relationship to M1-652.** M1-652 (gap-filling redelivery for per-category
digests) is the behavioral follow-on and is blocked on M1-642. This ticket is
independent of both: it can ship immediately and gates nothing.

**Count correction applied at implementation time (2026-07-18).** Two figures
in the acceptance items were stale when re-measured against the tree at
`edecc9b6`, and were corrected in place: call sites `43`→`47` and random-id
sites `40`→`44` (item 1), and messaging-adapter `correlationId` grep hits
`8`→`10` (item 2). **No requirement changed** — this is a factual correction
to the justification prose, not a scope, budget or acceptance change, so it
did not route through `escalate → refine`. Every structural claim survived
re-measurement unchanged: exactly three stable-id sites (`DigestWorker:212`
verbatim, `ApproveGroupCommandHandler:260`, `RejectGroupCommandHandler:304`)
and zero adapter-side correlationId lookups. `SimpleXAdapter.java:562`,
`SignalJsonRpcClient.java:430` and `InMemoryAdapter.java:129` were each
re-read and are exact.

The correction is itself an instance of what this ticket documents: a count
baked into prose rots, and the two figures had already drifted between filing
and implementation on the same day. That is why the shipped javadoc, §6.3.5
and D64 state "all but three call sites" rather than any absolute number —
the invariant is stable, the count is not.
