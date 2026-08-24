---
id: M1-925
title: "Confirm the D-8 wedge on the real supervised simplex shape"
status: done
created: 2026-08-24
last_updated: 2026-08-24
flow: tick
reproduction: >-
  Probe (the record's E4 open row, unrun — template form "exact probe
  command + observed wrong output"; the standing wrong output is on
  record). OBSERVED (2026-08-24, docs/measurement/d8-wedge-reconciliation.md
  §3 E2/E4): against the pinned simplex-chat (sha256 393279f3…c8db91d,
  Dockerfile.jvm:49), every M1-919 leg used a SIMPLIFIED drive — fresh DBs,
  --user-display-name + -e "/c <single host>" — while the real supervised
  bot runs pre-provisioned DBs minted by prod/scripts/6b-simplex-provision.sh
  (:163-177) carrying 6 relay hosts (smp4/5/6 + xftp1/4/5, record §2) and is
  driven supervisor-style (simplex-chat -d <prefix> -p <port>, no stdin).
  WRONG BEHAVIOR this ticket exists to end: whether the REAL shape wedges
  (zero SMP sessions) on the live path (user-defined network, embedded DNS
  127.0.0.11, no pins) is UNTESTED — E4's open row — and the fresh-leg
  post-connect `NAME {nameErr = NOT_FOUND}` (E2b/E2c tails) is
  unattributed (fresh-profile artifact vs part of the defect). The
  ticket's executable: the supervised-shape drive below, ≥3 fresh starts,
  per-trial outcomes recorded.
analysis_ref: docs/plan/m1/tick-analysis/simplex-client-embedded-dns-response-compatibility.md
blocked_by: []
files_scope:
  - docs/measurement/embedded-dns-supervised-shape.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The real test/prod stacks and their DBs — NEVER mounted, copied, or
    ridden (P4): the leg mints its OWN fresh identity with the 6b command
    set inside a throwaway container; no live-bot state leaves the live
    hosts.
  - >-
    ANY production code, image, compose, or script edit — a promoted
    measurement record only (the M1-919 shape); the remedy is M1-926's
    lane.
  - >-
    The field isolation (OPT vs AA) — sibling M1-924; this ticket's
    live-path legs observe the embedded forwarder as-is and never claim
    field-level attribution (P2: co-occurrence is the recorded state).
  - >-
    Any detector change — the FAULT-vs-zero observation (acceptance 3) is
    OBSERVATION ONLY; if it indicts the detector, a NEW ticket is the
    user's call, never a rider here (P5).
  - >-
    COMMITTING working data (client logs, pcaps, /get subs captures stay
    under gitignored /tmp); only the promoted record commits.
acceptance:
  - "SUPERVISED-SHAPE DRIVE (P14, analysis Root-cause cell 3): a throwaway leg on a user-defined network (embedded DNS, no pins, no host networking): (i) mint the DB shape with the EXACT 6b command set — docker compose run shape or a direct pinned-binary run, `-d <data-dir>/simplex_v1 -y --create-bot-display-name <name> -e /show_address`, `-e /ad`, `-e /auto_accept on`, `-e /show_address` (6b-simplex-provision.sh:163-177) — so the fresh DBs carry the DEFAULT relay set (the record inlines the hosts the client logs; expected 6, smp*/xftp* per record §2); (ii) drive SUPERVISOR-STYLE: `simplex-chat -d <data-dir>/simplex_v1 -p <port>` as a daemon with no stdin (the SimpleXSubprocess invocation shape); (iii) observe per trial: SMP sessions established (SYN/pcap + /proc/net/tcp + client agent log). ≥3 fresh starts (P2 — non-determinism); the record states per-trial outcomes; wedge (zero sessions) or no-wedge is recorded either way. Probe: `grep -cE 'trial [0-9]+' docs/measurement/embedded-dns-supervised-shape.md` returns ≥3 per-trial rows, each carrying its SYN/session-count/log-tail verdict, and the record inlines the exact 6b provisioning commands (6b-simplex-provision.sh:163-177) beside the client's relay-host log lines (6 expected — the default set)."
  - "CLEAN-RESOLVER CONTROL + NAME DISAMBIGUATION (analysis Root-cause cell 4): the same provisioned-DB shape driven behind the controlled clean responder (M1-924's harness, `qr aa rd ra`, no OPT): the record states whether the pre-provisioned shape establishes sessions AND reaches subscribed queues WITHOUT the fresh-DB legs' post-connect `NAME {nameErr = NOT_FOUND}` — deciding fresh-profile artifact (error gone once DBs are provisioned) vs part of the defect (error persists on provisioned DBs through a clean resolver). Verbatim client tail lines inlined for both arms. Probe: `grep -nE 'fresh-profile artifact|part of the defect' docs/measurement/embedded-dns-supervised-shape.md` returns the disambiguation verdict, both arms' verbatim tails inlined beside it."
  - "DETECTOR-VIEW OBSERVATION (P5 premise, observation only): while the leg is in the resolution-failure/wedged state, issue the M1-890 poll's exact command (`/get subs` over the loopback bot WS, the SESSION_POLL_ACK_TIMEOUT shape — SimpleXAdapter.java:135,:1395-1397) from the harness and record VERBATIM whether it ANSWERS with zero counts (the detector latches after its 3×30 s grace — covered) or ERRORS/never answers (the detector sees FAULT forever and never latches, SimpleXAdapter.java:1398-1401 — surveillance gap). The outcome feeds M1-926's runbook statement; no detector code changes here. Probe: `grep -n '/get subs' docs/measurement/embedded-dns-supervised-shape.md` returns the inlined verbatim capture with its zero-answer-vs-error reading."
  - "FAILURE-MODE / honesty (the M1-919 inconclusive rule): if the supervised shape does NOT wedge across the trials, the record says so WITH the trial count and names the consequence — the class's live-path frequency is bounded, the remedy's urgency downgrades, and that decision is the user's; a mixed outcome (some starts wedge, some do not) is recorded per-trial and is itself the finding (non-determinism made visible on the real shape). Never silently dropped. Probe: `grep -nE 'confirmed|refuted|mixed' docs/measurement/embedded-dns-supervised-shape.md` returns exactly one conclusion line naming the trial count — whichever arm fired."
  - "Controls (P2/P4/P5/P13): `git diff --name-only` shows exactly the new docs/measurement/ record (plus own frontmatter / STATUS-TICK regen); no compose/script/image/messaging-adapter/spec path in the diff; no recorded command line names a live-stack container or mounts live-bot data; working data gitignored; mvn verify from the repo root is green (no code touched)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only (the M1-860/M1-919 promotion shape): the drive harness
      lives in gitignored working data; the promoted record is the single
      committed artifact.
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-24
    verdict: REWORK
    checks:
      SPEC-TRUTHNESS-CHECK: WARN
      SECURITY-CHECK: WARN
      TEST-ADEQUACY-CHECK: NOT-APPLICABLE
      MAINTAINABILITY-CHECK: WARN
      SCOPE-CHECK: PASS
    diff_stats: "5 files, +1115/-7"
    verdict_file: .scratch/tick-review-M1-925-r1.txt
  - round: 2
    date: 2026-08-24
    verdict: APPROVE-WITH-FIXES
    checks:
      SPEC-TRUTHNESS-CHECK: WARN
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: NOT-APPLICABLE
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "fix hunks 2 files, +69/-5 (record + ticket bookkeeping)"
    verdict_file: .scratch/tick-review-M1-925-r2.txt
    dispositions: |
      Item 1 SATISFIED; Item 2 SATISFIED; Item 3 SATISFIED (all three
      EVALUATED-AS probes re-run green by the gate).
    fixes_applied: |
      FIX ITEM 1 applied via its option (a): both WS-capture lines (:123,
      :261) now carry the complete onion alternate hostname instead of the
      ",…onion" display abbreviation, so the declaration's "nothing
      shortened" claim is true of the committed text. Probe:
      `grep -c ',…onion' docs/measurement/embedded-dns-supervised-shape.md`
      = 0. Comment-only rule: zero executable lines touched; no Java file,
      no docs/spec, docs/design or root-level *.md touched — the edited
      file is the record itself (docs/measurement/), this evidence-only
      ticket's sole artifact. test-compile -pl <touched modules>:
      not applicable — the touched-module set is empty (docs-only diff);
      the round-2 full-suite verify remains the log of record
      (.scratch/tick-test-M1-925-r2.log; BUILD SUCCESS, 4246/0/0/10).
      Fixed-tree snapshot: .scratch/tick-fixes-M1-925.tree ->
      f4481a818f08b62e3a5d1673f506e7b8cd1707a7.
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-25: >-
    Start-time developer self-check (no blocking question): lint 0/0;
    citations re-verified (6b-simplex-provision.sh:163/:167/:172/:177,
    Dockerfile.jvm:49 sha 393279f3…c8db91d, SimpleXAdapter.java:135
    SESSION_POLL_ACK_TIMEOUT + :1398-1401 FAULT-never-zero,
    SimpleXSubprocess.java:189-190 "-d/-p" daemon form, record §2 six-host
    row). One execution note, not an ambiguity: acceptance 2 names
    "M1-924's harness" as the clean-responder tool, but M1-924 is pending
    and its harness is ephemeral /tmp working data (absent) — the leg
    rebuilds the clean responder to the same recorded contract (`qr aa rd
    ra`, no OPT, the E2c shape) and inlines its exact config in the record;
    no blocked_by violation (edge is empty by design).
escalation_reason:
---

# M1-925: Confirm the D-8 wedge on the real supervised simplex shape

## Context

M1-919 proved the wedge mechanism (response-shape sensitivity) with a
SIMPLIFIED drive, and its E4 row is explicitly open: the real supervised
bot — pre-provisioned DBs with 6 relay hosts, daemon-style invocation —
has never been driven on the live DNS path without pins (the live stacks'
`extra_hosts` short-circuit bypasses the whole surface, which is exactly
why they look healthy). Every fresh-DB leg in the record ALSO ended in an
agent-level `NAME NOT_FOUND` after a successful transport connect, and the
M1-911 harness only ever reached "subscribed 2 queues" with pre-created
queues — unattributed. This ticket closes E4: reproduce (or refute) the
wedge with the real shape, disambiguate the post-connect error, and record
what the M1-890 detector actually sees in the wedged state. Shared
analysis: `analysis_ref:`.

## Root cause

Proven (record §3): the client's transport fate follows the DNS response
shape; the supervised shape's EXPOSURE is the open cell — the real DB set
(6 hosts) multiplies resolution attempts (6 chances per start to draw an
OPT-annotated answer under non-determinism), and the supervisor's no-stdin
daemon drive has no `-e` command tail, so its failure state differs from
the E2 legs'. NOT proven — this ticket's cells: (1) does the real shape
wedge on the live path; (2) is the post-connect NAME error a fresh-profile
artifact (gone on provisioned DBs behind a clean resolver) or part of the
defect; (3) does `/get subs` answer zero or error in the wedged state (the
M1-890 detector's coverage premise — SimpleXAdapter.java:1398-1401 treats
a failed poll as FAULT, never zero). Safe to start: evidence-only; every
outcome is recorded, none ships.

## Pitfalls

Numbered consistently with the analysis document.

- P2: non-determinism — ≥3 fresh starts with per-trial outcomes; a single
  green start does not refute the class (it bounds frequency); live-path
  legs never claim field attribution (that is M1-924's matrix).
- P4: measurement discipline — the live stacks' DBs are never mounted or
  copied; the leg mints its own identity with the 6b commands inside a
  throwaway container; working data gitignored; /tmp is ephemeral so
  load-bearing lines are inlined in the record.
- P5: the detector is OBSERVED, not touched — zero messaging-adapter diff;
  the FAULT-vs-zero outcome is recorded for M1-926's runbook, and any
  indictment of the detector becomes a NEW user-filed ticket.
- P14: shape fidelity — the DBs must come from the exact 6b command set
  (default relay set acquired, not hand-written host lists) and the drive
  must be the supervisor's `-d <prefix> -p <port>` daemon form; a
  simplified re-drive re-opens E4 instead of closing it.
- (P13 standing: no spec path; the record is measurement evidence.)

## Approach

- **Files to touch:** `files_scope:` — the promoted record only.
- **Steps, in order:**
  1. Build the leg harness under gitignored working data: throwaway
     user-defined network + volume; provision via the 6b command set
     against the pinned binary; verify the client log names the expected
     6 default relay hosts BEFORE the drive (a hand-shortened host list
     invalidates the shape).
  2. Run ≥3 fresh starts of the supervised drive on the live path
     (acceptance 1), capturing per trial: DNS answers on the wire, SYN
     presence, session count, client log tail.
  3. Run the clean-resolver control leg on the same provisioned shape
     (acceptance 2), capturing the queue-subscription tail — the NAME
     disambiguation.
  4. While wedged, capture the `/get subs` answer verbatim (acceptance 3).
  5. Write the promoted record: per-trial table, control-leg outcome,
     detector-view capture, and the honest conclusion (wedge confirmed /
     refuted-with-count / mixed).
  6. `mvn verify` (trivially green) + the diff-scope probe (acceptance 5).
- **Controls to preserve (§10):** the repo reroutes nothing — the guards
  are diff scopes (no compose/script/image/adapter/spec path), the
  never-mount rule for live-bot data, and the observation-only stance
  toward the detector.
- **Pitfall→mitigation:** P2→step 2's trial count + acceptance 4's honest
  arms; P4→acceptance 5's no-live-container probe; P5→acceptance 3's
  observation-only wording + the diff guard; P14→step 1's host-set
  verification + the daemon invocation form.

## Definition of done

The promoted record exists at
`docs/measurement/embedded-dns-supervised-shape.md` with: the ≥3-trial
supervised-drive table (per-trial wire/session/log evidence inlined); the
clean-resolver control outcome deciding fresh-profile-artifact vs defect
for the post-connect NAME error; the verbatim `/get subs` capture with its
detector-coverage reading; the explicit conclusion (confirmed / refuted
with count / mixed). Zero diffs outside docs/measurement/ (own
frontmatter/board regen aside); no live-stack contact; mvn verify green.

## Verification

Every declared pitfall maps to the acceptance item + probe that
discharges it:

- P2 → acceptance 1's ≥3 fresh starts, per-trial rows probe
  (`grep -cE 'trial [0-9]+' docs/measurement/embedded-dns-supervised-shape.md`
  ≥ 3) + acceptance 4's honest arms; a single green start bounds
  frequency and never claims field attribution (M1-924's matrix owns
  that).
- P4 → acceptance 5: `git diff --name-only` shows exactly the new record
  (own frontmatter / STATUS-TICK regen aside); no recorded command line
  names a live-stack container or mounts live-bot data; load-bearing
  lines inlined (gitignored /tmp is ephemeral).
- P5 → acceptance 3's observation-only capture (probe: `grep -n '/get subs' docs/measurement/embedded-dns-supervised-shape.md`)
  plus acceptance 5's zero-messaging-adapter-path guard; any detector
  indictment becomes a NEW user-filed ticket, never a rider.
- P13 → acceptance 5: no spec path in the diff; the record is
  docs/measurement/ evidence.
- P14 → acceptance 1's exact 6b command set
  (6b-simplex-provision.sh:163-177) and the daemon `-d <prefix> -p
  <port>` invocation form; Approach step 1 verifies the 6-host default
  set in the client log BEFORE the drive, and the record inlines the
  provisioning commands beside the logged host list.
- Supervised drive → acceptance 1: per-trial evidence lines each trace to
  a named leg; the 6-host log excerpt is inlined (shape proof).
- NAME disambiguation → acceptance 2: both arms' client tails inlined;
  the artifact-vs-defect conclusion names which arm fired (probe:
  acceptance 2's grep returns the verdict).
- Detector view → acceptance 3: the verbatim subs answer (or fault) plus
  the SimpleXAdapter.java:1398-1401 reading (FAULT never latches).
- Failure-mode → acceptance 4: the refuted/mixed arms are recorded with
  counts and the user-decision consequence named — never dropped (probe:
  acceptance 4's grep returns exactly one conclusion line).
- Controls → acceptance 5 probes (`git diff --name-only`; mvn verify).
- Reproduction → this ticket's executable IS the drive; the standing RED
  observation is E4's open row cited in `reproduction:`.

## Out-of-scope

Named in `out_of_scope:` — the real test/prod stacks and their DBs (never
mounted or ridden; fresh identities minted in the leg); any
production/compose/script/image edit; the field isolation (M1-924); any
detector change (observation only; an indictment becomes a NEW
user-filed ticket); committing working data. No pre-existing test is
touched (evidence-only; mvn verify covers the preserves clause trivially).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-925-simplex-embedded-dns-supervised-shape-confirmation.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-925-r1.txt):

1. FINDING 1 — declare or correct the queue-address provenance in the WS-capture blocks (docs/measurement/embedded-dns-supervised-shape.md:123,:242): either paste true per-leg bodies from /tmp/m1-925-work/ or add the stand-in declaration beside the captures; evaluated via `grep -n 'stand-in\|masked' docs/measurement/embedded-dns-supervised-shape.md` returning the declaration OR `grep -c 'PQUV2eL0t7OStZOoAsPEV2QYWt4' docs/measurement/embedded-dns-supervised-shape.md` returning 0.
2. FINDING 2 — inline t5's verbatim client tail as a fenced block in §4 (replacing the arrow-chain paraphrase at :206-208; honesty note if the raw log is gone); evaluated via `awk '/^## 4\. Clean-resolver/,/^## 5\./' docs/measurement/embedded-dns-supervised-shape.md | grep -c '^```'` ≥ 6.
3. FINDING 3 — relabel "(§3 trial 4)" to "(§3, leg t4)" at :87; evaluated via `grep -c 'trial 4' docs/measurement/embedded-dns-supervised-shape.md` returning 0 with `grep -cE 'trial [0-9]+' docs/measurement/embedded-dns-supervised-shape.md` still ≥ 3.

## Review observations

(From the round-1 verdict's RECOMMENDED-NEW-TICKET entries; recorded per
the driver disposition rules — filing is the user's call.)

- The record's §5 observation indicts the M1-890 detector's coverage:
  because the poll's answer carries pendingSubs > 0, the presence test at
  SimpleXAdapter.java:1403-1406 (`activeSubscriptions() > 0 ||
  pendingSubscriptions() > 0`) routes to SESSIONS_PRESENT and clears the
  zero-counter, so a resolution-failure wedge on this shape can hold the
  zero-latch off indefinitely while zero transports exist — surveillance
  silence by masking, not by FAULT. TOUCHED-BY-THIS-DIFF: no.
  DECIDE-BEFORE: M1-926 (its runbook paragraphs must state detector
  coverage from this record; what they say depends on this decision).
  → Relayed to the user at round 1.
- The memory entry `.agents/memory/rootless-docker-port-split.md` carries
  a falsified premise: the "fixed 40000-band" publish-port allocation no
  longer holds after the daemon's restart (allocator climbs from the
  bottom again), per this ticket's verify history (record §7).
  TOUCHED-BY-THIS-DIFF: no. Driver-recordable; no ordering constraint.
  A `process:` memory-update commit is the user's call, separate from
  this ticket's branch.
- (Round 2 verdict, RECOMMENDED-NEW-TICKET) The record's controls section
  still cites the round-1 log as the log of record: §7 says "log at
  `target/tick-test-M1-925-r1.log`", but after round-1 rework the log of
  record is `tick-test-M1-925-r2.log`. One-line citation update;
  TOUCHED-BY-THIS-DIFF: no; user's call whether to fold it into commit
  bookkeeping or a follow-up docs-only touch. → Recorded; carried as a
  one-liner into the commit body.
