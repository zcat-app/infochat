---
id: M1-919
title: "Reconcile D-8 live wedge evidence with the controlled no-IPv6 rerun"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  The contradiction itself, observed by the corrected M1-911 step-0 probe
  (both legs valid, 2026-08-23). LIVE: the
  recorded D-8 outage (.scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §10
  :787-831) — on a host whose resolver answers AAAA-first for *.simplex.im
  with no container v6 route, the pinned simplex-chat wedged with zero SMP
  sessions at every fresh stack start, cleared only by extra_hosts IPv4
  pins; recorded as the root cause motivating M1-910 (abandoned,
  wont-do-infeasible) and M1-911 (deferred on this ticket). CONTROLLED: the
  corrected M1-911 step-0 rerun (artifacts /tmp/codex-m1-911-step0-no-gai-*
  and /tmp/codex-m1-911-step0-with-gai-*) — an EnableIpv6:false network, a
  DNS fixture logging both A and AAAA queries, and pinned v7.0.0.11 logging
  Agent connected / SMP host smp5.simplex.im / 2 queues subscribed on BOTH
  the no-gai.conf leg and the with-gai.conf leg. Wrong behavior this ticket
  exists to end: the D-8 root cause is UNCONFIRMED — no ticket may ship a
  remedy or record a spec posture against it while the live and controlled
  results contradict each other.
analysis_ref: docs/plan/m1/tick-analysis/rootless-ipv6-deployment-surface.md
blocked_by: []
files_scope:
  - docs/measurement/d8-wedge-reconciliation.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, image, compose, or script edit — this ticket
    produces evidence and a routing decision only. If D-8 re-confirms as
    client-side AAAA wedging, the remedy is M1-911's image-level gai.conf
    shape (deferred on this ticket; reopened when this is done).
  - >-
    Any daemon/net-driver change — rootless v6 enablement was evaluated and
    abandoned as not-fail-safe on this host class (M1-910's terminal
    record); do not resurrect it here.
  - >-
    COMMITTING rerun working data (gitignored /tmp and .bench artifacts);
    only the promoted record at docs/measurement/d8-wedge-reconciliation.md
    is committed.
acceptance:
  - "ENVIRONMENT DELTA TABLE: the record enumerates, side by side, the recorded D-8 live environment and the controlled rerun environment — simplex-chat image/version at outage time vs the rerun's v7.0.0.11 pin, resolver behavior (AAAA-first evidence in both), host/container v6 state, network driver, and every other input the two runs did not share — each row citing its source (plan §10 lines, rerun artifact paths, image digests), every citation re-checked with a `grep`/`git show` probe against the named source."
  - "DECISIVE EXPERIMENT: the record names and runs the minimal discriminating probe (docker-run legs under gitignored working data, e.g. the controlled leg re-run at the outage-time version, or the live host's exact resolver answers replayed to the fixture) and reports which explanation survives; an inconclusive probe run is recorded as such with the next discriminator named, never silently dropped."
  - "ROUTING DECISION, explicit: exactly one of (a) D-8 re-confirmed client-side AAAA wedging -> name M1-911 as the remedy and reopen it, (b) a different root cause identified -> name the fresh ticket/analysis brief it warrants, (c) unreproducible with the delta unresolved -> state what surveillance (e.g. the M1-890 detector) covers the class in the meantime. The chosen arm is justified against the evidence rows, not asserted."
  - "M1-911's deferred record is updated to point at this record's conclusion (its reopen condition named or its abandonment justified) — probe: `grep -n 'M1-919' docs/plan/m1/tick-tickets/M1-911-no-ipv6-host-fallback.md` returns the updated pointer; no dangling defer remains after this ticket is done."
  - "Controls preserved: zero diffs outside docs/measurement/ (`git diff --name-only` shows exactly the new record); no ticket-frontmatter edits except M1-911's reopen-condition pointer; mvn verify stays green (no code touched)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only: rerun harnesses live in gitignored working data; the
      promoted record is the single committed artifact (the M1-860/M1-844
      shape). mvn verify covers the preserves clause trivially.
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  - D8
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-919: Reconcile D-8 live wedge evidence with the controlled no-IPv6 rerun

## Context

The D-8 SimpleX wedge drove two tickets. Both are now paused on this one's
answer: M1-910 (rootless v6 compose enablement) was abandoned 2026-08-23 —
wont-do-infeasible, its step-0 live probe falsified the premise (rootlesskit
`--ipv6` unsupported on gvisor-tap-vsock; outbound v6 blackholed; branch 2
not fail-safe) — and M1-911 (image gai.conf IPv4 preference) was deferred
the same day when its corrected step-0 rerun showed pinned v7.0.0.11
connecting over IPv4 WITHOUT gai.conf in a controlled no-v6 + AAAA-first
environment, contradicting the recorded live outage. Until the live and
controlled results are reconciled, the wedge class has no confirmed root
cause and no ticket may ship a remedy for it.

## Root cause

Not yet established — that is the ticket. The two evidence sets disagree:
live D-8 (plan §10 :787-831) shows an AAAA-first answer with no fallback
wedging the client; the controlled rerun (both legs, valid artifacts) shows
the same-pinned client succeeding over IPv4 in the same DNS shape. The
explanation space includes at least: a version drift between the outage-time
image and v7.0.0.11; a resolver-behavior difference the fixture did not
reproduce (answer ordering, TTL, SERVFAIL interplay); and an environmental
input of the live host the controlled namespace lacked. Which one holds
decides whether M1-911 reopens, a different fix is warranted, or the class
is declared unreproducible-under-surveillance.

## Approach

Evidence reconciliation in the M1-860 measurement shape: build the
environment delta table from the cited sources, run the decisive
discriminating experiment(s) under gitignored working data, promote the
record with the explicit routing decision to
`docs/measurement/d8-wedge-reconciliation.md`, and leave M1-911's defer
pointing at a named conclusion.

## Pitfalls

- P1: confirming the wedge under control and shipping gai.conf anyway —
  the controlled run DID connect without it; a remedy shipped against
  unconfirmed evidence re-introduces exactly the untested-change risk the
  tick flow exists to prevent. The routing decision must cite the delta
  rows, not the recorded outage narrative alone.
- P2: asymmetric evidence standards — the live outage was recorded under
  duress (partial observability, no fixture); the controlled rerun has
  fixtures but may not reproduce the live resolver's full behavior
  (ordering, TTL, SERVFAIL interplay). A delta row is only closed when the
  discriminating probe ran, not when the two narratives are reconciled on
  paper.

## Definition of done

The promoted record exists at
`docs/measurement/d8-wedge-evidence-reconciliation.md` with the delta
table, the decisive-probe results, and exactly one routing arm chosen and
justified; M1-911's deferred record points at the conclusion; zero diffs
outside `docs/measurement/`.

## Verification

Every acceptance item by its named probe: the delta-table citations
re-checked (`grep`/`git show`), the discriminating probe's artifacts named
in the record, the routing arm justified against the rows, M1-911's
pointer updated, and `git diff --name-only` showing exactly the new
record. FAILURE-MODE: a promoted record whose routing arm cites rows the
probe runs did not produce fails review — each quoted number must trace
to a named artifact. Pitfalls P1/P2 are discharged by the routing-decision
and delta-row closure rules above.

## Census

Evidence surface swept (2026-08-23):

| Source | Role |
|---|---|
| .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §10 :787-831 | the recorded live D-8 outage (AAAA-first wedge, extra_hosts pins clearing it) |
| /tmp/codex-m1-911-step0-no-gai-* and -with-gai-* | the controlled rerun artifacts, both legs valid |
| M1-910 ticket record (abandoned 2026-08-23) | the rootless-host probe results: gvisor-tap-vsock, blackholed outbound v6 |
| M1-911 ticket record (deferred 2026-08-23) | the rerun's contradiction with the live evidence — this ticket's cause |
| docs/measurement/* | existing measurement records (the M1-860 promotion shape this ticket follows) |
