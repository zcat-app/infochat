---
id: M1-924
title: "Isolate the embedded-DNS response field that wedges simplex-chat"
status: done
created: 2026-08-24
last_updated: 2026-08-24
flow: tick
reproduction: >-
  Probe (the record's E3 discriminator, unrun — template form "exact probe
  command + observed wrong output"; the standing wrong output is already on
  record). OBSERVED (2026-08-24, docs/measurement/d8-wedge-reconciliation.md
  §3): against the pinned simplex-chat (sha256 393279f3…c8db91d,
  Dockerfile.jvm:49) on the live path, embedded 127.0.0.11 answers carrying
  an OPT additional record on a plain (non-EDNS) query (`1/0/1`) produce
  ZERO SYN to :443 and the client logs `SEND RSLV` then
  `NAME {nameErr = NOT_FOUND}` (E2a), while clean `1/0/0` answers — AA set,
  no OPT, real AAAA present — connect over IPv4 ~1 ms after the A answer
  (E2b/E2c). WRONG BEHAVIOR this ticket exists to end: the two candidate
  trigger fields (OPT-on-non-EDNS; absent AA bit) CO-OCCUR in every failing
  observation, so no controlled isolation exists — the defect's trigger
  field is unidentified and neither the remedy nor the upstream report can
  name it. The ticket's executable: the four-leg controlled-responder
  matrix below, run against the pinned binary (RED stands in the record's
  E2a/E2c arms; the matrix is the discriminator).
analysis_ref: docs/plan/m1/tick-analysis/simplex-client-embedded-dns-response-compatibility.md
blocked_by: []
files_scope:
  - docs/measurement/embedded-dns-trigger-isolation.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, image, compose, or script edit — this ticket
    produces a promoted measurement record only (the M1-919 shape). A
    remedy that rides the matrix outcome is M1-926's lane.
  - >-
    The supervised pre-provisioned-DB drive — sibling M1-925 (E4's cell);
    this ticket's drives use the E2 simplified shape deliberately, because
    the matrix varies the resolver, not the drive.
  - >-
    Reopening M1-911 — this ticket SUPPLIES the reopen input (acceptance
    item 4); the reopen decision is the user's. No edit to M1-911's record
    rides this diff.
  - >-
    COMMITTING rerun working data (responder configs, pcaps, client logs
    stay under gitignored /tmp, ephemeral); only the promoted record is
    committed (P4).
  - >-
    The M1-889/890/901 detector surface — zero messaging-adapter diff
    (P5); 8-verify.sh — zero diff (P6).
acceptance:
  - "MATRIX (P2, analysis Root-cause cell 1): four controlled-responder legs + control against the pinned binary (sha256 re-verified in the record, Dockerfile.jvm:49), each leg a fresh throwaway user-defined network with the responder in-netns (dnsmasq where the shape is expressible, else a scriptable responder — working data under gitignored /tmp), varying EXACTLY ONE field: (a) control `qr aa rd ra`, no OPT (the E2c shape); (b) `qr rd ra`, no OPT (AA bit isolated); (c) `qr aa rd ra` + OPT on non-EDNS queries (OPT isolated); (d) `qr rd ra` + OPT (the live failing shape). Per leg the promoted record states: the wire answer shape (dig +noedns +comments and/or tcpdump inline), SYN present/absent to the A address, and the client's agent-level outcome (verbatim log line where one fires). Probe form: the recorded leg commands + outputs ARE the verification (mvn covers nothing here — evidence-only)."
  - "ORIGIN DISCRIMINATOR (P9, feeds M1-926): a leg on a user-defined network whose probe container carries per-service `dns:` pointing at a controlled CLEAN upstream responder, observing the CLIENT-VISIBLE shape at 127.0.0.11 (dig +noedns from inside the container): the record states whether the embedded forwarder relays the upstream's clean shape faithfully (clean reaches the client) or re-annotates (OPT appears regardless). This outcome decides whether ANY `dns:`-shaped remedy can work — it is named as M1-926's step-0 input."
  - "FAILURE-MODE / honesty (the M1-919 inconclusive rule): if ALL four legs connect (no isolated field trips the client — contradicting the E2a/E2b arms), or if both (b) and (c) trip while (a) does not (both fields independently fatal), the record says exactly that with the per-leg evidence and names the next discriminator; the remedy ticket does not ride a contradicted or ambiguous matrix — escalation instead. A single live-path observation is never conclusive (P2): live-path claims carry trial counts. Probe: the record labels its four matrix rows 'leg (a)'-'leg (d)' — `grep -n 'leg (a)' docs/measurement/embedded-dns-trigger-isolation.md` (likewise (b)/(c)/(d)) resolves each label to its row — and when an honesty arm fired, `grep -nE 'escalat|next discriminator' docs/measurement/embedded-dns-trigger-isolation.md` returns the paragraph naming it, with M1-926 left blocked."
  - "M1-911 REOPEN INPUT (analysis Root cause): the record states whether any matrix outcome satisfies M1-911's named reopen condition (getaddrinfo address-family selection re-implicated — the wedge reproducing through a client-compatible resolver), with the reasoning grounded in the legs: expected NO — a shape-field trigger fails in response parsing upstream of address-list construction (the only layer gai.conf reorders), and E2b/E2c already observed the client consuming clean answers and choosing IPv4. The one conceivable re-implicating arm (a clean-responder leg where the client consumes addresses and then wedges on family choice) is named so the implementor recognizes it if it fires."
  - "Live-path frequency observation (P2, non-load-bearing): ≥10 plain-query trials against 127.0.0.11 on a throwaway user-defined network, recording how many answers carry the unrequested OPT — bounds the live-path exposure honestly; per-trial outcomes summarized in the record. Probe: `dig +noedns +comments @127.0.0.11 <relay-host> A` from a container on the throwaway network, ≥10 times; each trial's header counts inlined (the d8 record's '1/0/1'/'1/0/0' answer-count notation, :95-97) — `grep -cE '1/0/[01]' docs/measurement/embedded-dns-trigger-isolation.md` counts ≥10 trial rows — and the summary states the OPT-carrying fraction with the trial count."
  - "Controls (P3/P4/P5/P6/P13): `git diff --name-only` shows exactly the new docs/measurement/ record (plus own frontmatter / STATUS-TICK regen); no compose/script/image/messaging-adapter/spec path in the diff; relay hostnames appear only as evidence inside the measurement record (the M1-919 precedent); working data gitignored; mvn verify from the repo root is green (no code touched). Spec cite: the legs measure the supported rootless posture docs/spec/deployment.md §Deployment scenarios commits to; this diff edits no spec path."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only (the M1-860/M1-919 promotion shape): harness legs live
      in gitignored working data; the promoted record is the single
      committed artifact.
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-24
    verdict: REWORK
    checks: SPEC-TRUTHNESS WARN (sole rework source), SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS
    diff_stats: "3 files changed, 515 insertions(+), 7 deletions(-)"
    verdict_file: .scratch/tick-review-M1-924-r1.txt
  - round: 2
    date: 2026-08-24
    verdict: APPROVE
    checks: SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS; rework item 1 SATISFIED (probes + byte-match vs retained working data)
    diff_stats: "3 files changed, 532 insertions(+), 7 deletions(-)"
    verdict_file: .scratch/tick-review-M1-924-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  start 2026-08-24 pass — tick-lint 0 findings; no blocking ambiguity.
  Citations re-verified: Dockerfile.jvm:49 sha pin 393279f3…c8db91d (full
  sha read at infochat-provider/src/main/docker/Dockerfile.jvm:49); d8
  record §3 E2a/E2b/E2c rows and the 1/0/1·1/0/0 notation at :95-97 exact.
  Analysis pitfalls P1-P5, P13-P14 all landed (P1/P3 as standing guards).
  blocked_by empty (no sibling-test tracing). Parallel precondition:
  zero in-flight tick tickets (frontmatter grep + worktree list), files_scope
  docs-only (no Maven module) → no overlap. Environment pre-verified:
  rootless Docker 29.7.2 up, E2-era image 7770930b6727 present for
  sha re-verification at leg time.
escalation_reason:
---

# M1-924: Isolate the embedded-DNS response field that wedges simplex-chat

## Context

M1-919 (merged 2c34d57a) routed the D-8 wedge class to arm (b): the pinned
simplex-chat client fails in its own DNS-response handling when the
rootless daemon's embedded-DNS forwarder annotates answers — but its E3
cell recorded the trigger as INCONCLUSIVE: the unrequested OPT record and
the absent AA bit co-occur in every failing observation
(docs/measurement/d8-wedge-reconciliation.md §3 E3). Until one field is
isolated, the remedy cannot be designed honestly, the upstream report
cannot name the trigger, and the M1-911 reopen condition cannot be
evaluated. This ticket runs the controlled isolation matrix plus the
origin discriminator that decides whether any per-service `dns:` remedy is
even possible. Shared analysis: `analysis_ref:`.

## Root cause

Proven (record §3): connect-vs-wedge follows the DNS response SHAPE (E2a
OPT → zero SYN + `NAME {nameErr = NOT_FOUND}`; E2b/E2c clean → IPv4 TLS+SMP
with the real AAAA present); the recorded AAAA-first/no-fallback mechanism
is disproven on the pin. NOT proven — this ticket's cells: (1) WHICH field
trips the client (OPT vs AA — never isolated, only co-observed); (2) WHERE
the annotation originates (forwarder re-serialization vs upstream
passthrough — same-endpoint non-determinism proves upstream-influence but
not `dns:`-swap efficacy); (3) whether any matrix leg can satisfy M1-911's
reopen condition (expected no — analysis Root cause). Safe to start: the
ticket is evidence-only; nothing ships on an unrun gate.

## Pitfalls

Numbered consistently with the analysis document.

- P2: live-path non-determinism — isolation MUST vary one field at a time
  under a controlled responder; live-path claims carry trial counts
  (≥10 frequency observation); a single green/red live leg proves nothing.
- P4: measurement discipline — throwaway legs only, never the prod/test
  stacks; working data gitignored under /tmp (ephemeral — load-bearing
  lines inlined in the record); only the promoted record commits.
- P5: the detector surface is untouched (zero messaging-adapter diff); the
  FAULT-vs-zero observation is M1-925's cell, not this ticket's.
- P13: no spec edit; the record is docs/measurement/ evidence.
- P14: this ticket deliberately keeps the E2 SIMPLIFIED drive shape (the
  matrix varies the resolver, not the drive); the supervised shape is
  M1-925's. Do not conflate the cells.
- (P1/P3 apply as standing guards: no retired mechanism rides this ticket;
  relay names only as record evidence.)

## Approach

- **Files to touch:** `files_scope:` — the promoted record only.
- **Steps, in order:**
  1. Build the responder harness under gitignored working data (dnsmasq
     config where the shape is expressible; a scriptable UDP responder for
     OPT-on-non-EDNS and AA-cleared shapes dnsmasq cannot emit). Verify
     each shape on the wire with `dig +noedns +comments` from a probe
     container BEFORE any client leg (a responder that does not emit the
     claimed shape invalidates the matrix silently — the cheap pre-check
     prevents that).
  2. Run the four-leg matrix + control (acceptance 1) against the pinned
     binary (sha re-verified; the E2 drive shape: fresh DB, single relay
     host, `-t` timeouts, tcpdump on 53/443, client log captured).
  3. Run the origin-discriminator leg (acceptance 2) and the live-path
     frequency observation (acceptance 5).
  4. Write the promoted record: matrix table, per-leg evidence inlined,
     origin outcome, M1-911 reopen input, next discriminator if any leg
     contradicts E2.
  5. `mvn verify` (trivially green) + the diff-scope probe (acceptance 6).
- **Controls to preserve (§10):** nothing in the repo reroutes — the
  guards are the diff scopes: no compose/script/image/adapter/spec path;
  working data never committed; relay hostnames only inside the
  measurement record.
- **Pitfall→mitigation:** P2→step 1's wire pre-check + acceptance 1/5's
  per-leg/per-trial evidence; P4→acceptance 6; P5→acceptance 6's
  adapter-diff guard; P13→no spec path; P14→step 2's pinned drive shape.

## Definition of done

The promoted record exists at `docs/measurement/embedded-dns-trigger-isolation.md`
with: the four-leg matrix and its per-leg wire/client evidence; the named
trigger field(s) — or the explicitly recorded contradiction with E2 and the
next discriminator; the origin-discriminator outcome (relay-faithful vs
re-annotating) named as M1-926's step-0 input; the M1-911 reopen-input
statement; the live-path frequency count. Zero diffs outside
docs/measurement/ (own frontmatter/board regen aside); working data
gitignored; mvn verify green.

## Verification

Every declared pitfall maps to the acceptance item + probe that
discharges it (P1/P3 are the standing guards the Pitfalls section
carries):

- P1 → acceptance 6's diff shows no image/script/compose hunk that could
  carry a retired mechanism, and `grep -nE 'gai.conf|enable_ipv6|extra_hosts' docs/measurement/embedded-dns-trigger-isolation.md`
  matches, if anything, only the falsified-family framing the record
  itself retires — no candidate row resurrects gai.conf/M1-911,
  rootless-v6/M1-910, or pins.
- P2 → acceptance 1 (exactly one field varied per leg, wire shape
  pre-checked per Approach step 1) + acceptance 5's frequency probe
  (`grep -cE '1/0/[01]' docs/measurement/embedded-dns-trigger-isolation.md`
  ≥ 10 per-trial rows); a single live leg is never load-bearing.
- P3 → acceptance 6: relay hostnames appear only as evidence inside the
  measurement record (the M1-919 precedent) — the diff carries no shipped
  surface that could embed them.
- P4 → acceptance 6: `git diff --name-only` shows exactly the new record
  (own frontmatter / STATUS-TICK regen aside); working data stays under
  gitignored /tmp; per-leg dig/tcpdump output and client log lines are
  inlined in the record, each tracing to a named leg (the M1-919
  probe-re-run discipline).
- P5 → acceptance 6's same `git diff --name-only` shows zero
  messaging-adapter paths; the FAULT-vs-zero cell is M1-925's, never
  probed here.
- P13 → acceptance 6: no spec path in the diff — docs/spec/deployment.md
  §Deployment scenarios is the cited posture, measured, not amended.
- P14 → acceptance 1's legs keep the pinned E2 drive shape (fresh DB,
  single relay host, -t timeouts, tcpdump on 53/443 — Approach step 2);
  the (a)-(d) leg rows differ only in the resolver-shape field.
- Matrix → acceptance 1: per-leg dig/tcpdump output and client log lines
  inlined in the record.
- Origin discriminator → acceptance 2: the client-visible dig output at
  127.0.0.11 with the dns: override in place, inlined.
- Failure-mode → acceptance 3: the all-connect / both-fatal arms are
  recorded honestly with escalation, never harmonized into E2's narrative
  (probe: acceptance 3's greps).
- Reopen input → acceptance 4: the statement cites the matrix legs, names
  the one conceivable re-implicating arm.
- Frequency → acceptance 5: N≥10 with the count.
- Controls → acceptance 6 probes (`git diff --name-only`; mvn verify).
- Reproduction → this ticket's executable IS the matrix; the standing RED
  observation is the record's E2a leg cited in `reproduction:`.

## Out-of-scope

Named in `out_of_scope:` — any production/compose/script/image edit; the
supervised-shape drive (M1-925); any remedy work (M1-926); edits to
M1-911's record (the reopen decision is the user's); committing working
data; the detector surface and 8-verify.sh (zero diff). No pre-existing
test is touched (no test is added — evidence-only; mvn verify covers the
preserves clause trivially).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-924-simplex-embedded-dns-trigger-isolation.md
```

## Round 1 rework

Verdict REWORK (round 1, 2026-08-24; .scratch/tick-review-M1-924-r1.txt).
REWORK ITEMS, verbatim:

1. Finding 1: restore the per-leg verbatim client-log lines for legs (a′)/(b)/(c) and ground (or restate) the leg-(d) "~0.4 ms" SYN-latency claim with its timestamped capture line in docs/measurement/embedded-dns-trigger-isolation.md §3 — sourced from the retained /tmp/m1-924-* working data or a disclosed re-run, never reconstructed — evaluated via `grep -c 'NAME {nameErr' docs/measurement/embedded-dns-trigger-isolation.md` = 6 and `grep -nE 'leg \(d\)  SYN:.*Flags \[S\]' docs/measurement/embedded-dns-trigger-isolation.md` returning a timestamped line.
