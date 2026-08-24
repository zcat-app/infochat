---
id: M1-926
title: "Provider DNS remedy: client-compatible resolver on rootless embedded DNS"
status: abandoned
created: 2026-08-24
last_updated: 2026-08-24
flow: tick
reproduction: >-
  ProviderDnsWiringTest.providerCarriesClientCompatibleResolverWiring
  (to-be-written — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/simplex-client-embedded-dns-response-compatibility.md;
  converted at `start` per workflow §0: written first, run RED against the
  repo compose surface — verified-absence probe on this checkout
  (2026-08-24): docker-compose.yml (read in full, 420 lines) contains NO
  `dns:` key, NO `networks:` key, NO `extra_hosts` on any service, so no
  tracked surface gives the pinned simplex-chat a client-compatible
  resolver path). The wrong behavior it states: on the supported rootless
  posture (user-defined networks, embedded DNS at 127.0.0.11) the pinned
  simplex-chat client (v7.0.0 / sha 393279f3…c8db91d, Dockerfile.jvm:47-49)
  can wedge with ZERO SMP sessions whenever the embedded forwarder
  annotates its answers (observed non-deterministically,
  docs/measurement/d8-wedge-reconciliation.md §2-§3, E2a) — the D-8 class —
  and the only working bypass today is the never-ship extra_hosts pins
  living outside this repo, whose /etc/hosts short-circuit hides the
  surface rather than fixing it.
analysis_ref: docs/plan/m1/tick-analysis/simplex-client-embedded-dns-response-compatibility.md
blocked_by: [M1-924, M1-925]
files_scope:
  - docker-compose.dns.yml
  - prod/scripts/7-apps.sh
  - prod/scripts/apps.sh
  - prod/scripts/upgrade.sh
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ProviderDnsWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The falsified family mechanisms, permanently: /etc/gai.conf or ANY
    M1-911 image-level address-selection shape (the failure precedes
    address selection; the client is not plain getaddrinfo — record §3);
    rootless IPv6 enablement (M1-910 abandoned, wont-do-infeasible on
    gvisor-tap-vsock); the extra_hosts pins or any resolve-at-start
    /etc/hosts writer institutionalized in the repo (never-ship ruling;
    the repo names no relay hostnames to pin). A diff containing any of
    these fails review (P1).
  - >-
    Runtime host networking for any service — no `network_mode` anywhere
    (security.md §Trust boundaries 6-8; M1-810's wiring test pins its
    absence) — and no default-bridge placement for the provider, and no
    bind-mounted /etc/resolv.conf fighting Docker's managed file (P8:
    these shapes stay rejected even if both remedy arms fail — the answer
    is then the STOP arm, not a forbidden shape).
  - >-
    The M1-889/890/901 detector code and 8-verify.sh — zero diff (P5/P6);
    the remedy must not weaken or re-tune detection, and no verify leg is
    added.
  - >-
    The base docker-compose.yml's invariants (P7): the base file gains NO
    `networks:`, NO `dns:`, NO `extra_hosts` keys, and every existing key
    (loopback binds, M1-512 caps, image pins, M1-808/810 network keys,
    M1-905 serving keys) stays byte-untouched; the remedy lives in
    service-scoped overlay keys.
  - >-
    The Collector's DNS posture — its JVM fetch path was empirically
    healthy in the wedged environment (family analysis Ground truth); the
    remedy scopes to infochat-provider deliberately. Signal/signal-cli
    resolver knobs: no shared exposure on the evidence (P12 of the family
    analysis); zero signal diff.
  - >-
    The M1-911 reopen decision — M1-924's record supplies the input; only
    the user reopens. An escalated STOP arm here does not convert this
    ticket into any out-of-scope mechanism (the M1-911 P6 precedent).
acceptance:
  - "STEP-0 REMEDY-SHAPE GATE (P9, run before any file edit, recorded in the commit message — the M1-908/M1-910 acceptance-1 pattern): on a throwaway user-defined network, apply each candidate mechanism and measure END-TO-END: (a) per-service `dns:` pointing the embedded forwarder at a controlled clean upstream — observe the CLIENT-VISIBLE shape at 127.0.0.11 (dig +noedns from inside) AND a pinned-binary drive establishing SMP sessions on the resulting path; (b) if (a) shows the forwarder relays upstream shape faithfully, validate the chosen production arm the same way (sidecar normalizer, or host-derived upstream resolvers). DECISION RULE: land the arm whose end-to-end leg is green. If NO arm clears the client-visible answers (the forwarder re-annotates regardless of upstream — M1-924's origin discriminator outcome), STOP and escalate: land NO mechanism; the documented-limitation disposition (acceptance 8's STOP paragraph + the upstream issue) is the user's decision, never a substituted shape. Probe: `git log -1 --format=%B | grep -iE 'step-0|STOP arm|client-visible'` returns the recorded gate outcome (the landed arm or the fired STOP)."
  - "REPRODUCTION, now passing: ProviderDnsWiringTest.providerCarriesClientCompatibleResolverWiring (test_plan.adds) — a static pin of the landed keys in the exact step-0-verified form (the M1-911 exact-landed-stanza pattern): the provider's resolver wiring (the landed `dns:`/overlay/service keys) plus, for the sidecar arm, the service stanza's obligations (acceptance 5); a mutation deleting the landed wiring fails the test. If the STOP arm fired, this item is DISCHARGED BY ESCALATION — the record shows no mechanism landed and the test is not written (the STOP record + user decision stand in)."
  - "FAILURE-MODE negatives (P3/P7/P8): ProviderDnsWiringTest asserts (a) base docker-compose.yml contains NO `networks:`, `dns:`, or `extra_hosts` key and renders byte-identically (a mutation adding any of the three to base fails); (b) NO service in any tracked compose file declares `network_mode` (M1-810 precedent); (c) the generic-surface scan: the remedy files and threaded scripts carry ZERO `simplex.im` strings, ZERO IP literals outside the ONE fixed generic reserved-subnet constant (identical for every deployment — the M1-910 ULA precedent's v4 analog; a host-derived or relay-derived value fails)."
  - "THREADING (P1-flapping guard, conditional): IF the landed arm requires the four provider-recreating sites to append an overlay (7-apps.sh:85/89, apps.sh:65, upgrade.sh:115/117, restore.sh:978/1042 — the M1-910 census), ProviderDnsWiringTest adds per-site fake-docker drives (the DoctorWiringTest restricted-PATH pattern) asserting each site's compose argv carries the overlay; a mutation dropping ONE site fails its drive (partial threading silently reintroduces the wedge on the next upgrade/restore). IF the landed arm is purely compose-declared (no script change), this item records that fact and its probe is `git diff --name-only` showing zero prod/scripts hunks."
  - "SIDECAR OBLIGATIONS (P10, sidecar arm only): ProviderDnsWiringTest asserts the new service's pinned image tag (M1-004), `restart: unless-stopped`, a healthcheck, an M1-512 deploy.resources cap row, and NO host port (compose-network-only reachability — security.md §Trust boundaries posture) — a mutation dropping any one obligation fails the test; dnsmasq-class config surface carries no query logging and no host-derived values. If the STOP arm or the no-sidecar arm landed, this item is recorded as not-applicable with the reason."
  - "DETECTOR UNWEAKENED + HONEST COVERAGE (P5): `git diff --name-only` shows zero messaging-adapter paths; the docs paragraph (acceptance 8) states, from M1-925's record, the detector's FAULT-vs-zero conditionality and the partial-wedge gap for this class, and that the remedy's own failure mode (e.g. a dead sidecar) surfaces only at the M1-890 detector's session granularity — no false comfort."
  - "8-VERIFY ZERO DIFF (P6): `git diff --name-only` shows no 8-verify.sh hunk; its WARN-only posture and exit contract are untouched."
  - "DOCS (P10/P13, docs/design/07-deployment.md only — disjoint from any sibling's sections; no docs/spec/ edit): a runbook paragraph recording the landed mechanism (what the operator's compose now does for provider resolution), the operator override (`INFOCHAT_DNS_COMPAT=off`, operator-exported env var — the INFOCHAT_LLAMACPP_GPU precedent, never a secrets.env key), the reversal, the detector-gap disclosure (acceptance 6), and — if the STOP arm fired — the documented-limitation paragraph instead: the constraint, the never-ship pins' standing as the live bypass, and the upstream issue pointer. Verification: `git diff --stat docs/` shows exactly docs/design/07-deployment.md. Probe: `grep -n 'docs/spec' <the diff>` is empty — no spec edit rides (§12)."
  - "UPSTREAM ISSUE (P12, ALWAYS rides this ticket whichever arm fired): the simplex-chat upstream issue is filed, claiming ONLY the pinned version + sha256, the trigger field(s) M1-924's matrix isolated, and the controlled-responder recipe that reproduces the client's `NAME {nameErr = NOT_FOUND}` — no claims about the client's resolver internals, other versions, or our deployment beyond the wire shapes. Its URL is recorded in the commit message. Probe: `git log -1 --format=%B | grep -iE 'upstream|github.com/simplex-chat'`."
  - "mvn verify from the repo root is green, and ./mvnw -B -pl infochat-provider test -Dtest='ProviderDnsWiringTest' is green (engineering-rules §5; skipped only on the escalated STOP arm, where no test was written)."
  - "ROLLOUT EVIDENCE (P2/P10, recorded in the commit message or rollout notes, not a build test; live-host facts re-verified there first — the analysis carried the pins-present fact as an ASSUMPTION from the record): on the TEST checkout the extra_hosts pins are removed (ops action, never a diff) and the stack is fresh-started ≥3 times (non-determinism — trial count recorded): the bot holds SMP sessions across the starts with the M1-890 detector quiet (no zero-session WARNs); one leg with the override OFF renders the pre-remedy shape (byte-identical argv if threading landed / service absent) proving the reversal is honest."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ProviderDnsWiringTest.java
      — providerCarriesClientCompatibleResolverWiring (reproduction;
      exact landed-form pin), the base-file negative layer (no
      networks:/dns:/extra_hosts in base, no network_mode anywhere), the
      generic-surface scan, plus (conditionally) the per-site threading
      drives and the sidecar-obligation pins per the landed arm.
  preserves:
    - all tests currently green on main
    - >-
      every pre-existing wiring-test class byte-untouched (DoctorWiringTest,
      RestoreWiringTest, UpgradeWiringTest, VerifyWiringTest,
      BuildHostNetworkWiringTest, StackScriptWiringTest, LlamacppWiringTest
      — the M1-907 separate-class discipline; new drives live only in the
      new class).
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/spec/deployment.md §Health and observability
  - docs/spec/security.md §Trust boundaries
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
abandoned_reason: wont-do-infeasible
abandoned_evidence: >-
  Premise chain collapsed 2026-08-24, three independent legs: (1) M1-924's
  controlled matrix (embedded-dns-trigger-isolation.md §3) — the exact live
  failing shape (leg (d), qr rd ra + OPT) does NOT wedge the pinned client;
  no response field trips it in isolation, so the honesty arm fired ("the
  remedy ticket does not ride a contradicted matrix"). (2) M1-925's
  supervised-shape record — wedge REFUTED 3/3 on the real 6b-minted drive,
  and the forwarder's OPT answers traced to the client's own EDNS queries,
  not non-deterministic annotation. (3) The host migrated ROOTLESS->ROOTFUL
  the same day (both stacks, prod verified green): the embedded forwarder
  environment this ticket was engineering around no longer exists on this
  box — rootful probe 12/12 clean answers, 10/10 supervised sessions, and
  both live stacks hold SMP sessions with the extra_hosts pins REMOVED
  (memory-local prod-stopped-for-rootful-migration-20260824.md). The root
  cause per M1-924 §4 (forwarder live dynamics under rootlesskit) is
  deleted, not remediated. User decision 2026-08-24.
---

# M1-926: Provider DNS remedy — client-compatible resolver on rootless embedded DNS

## Context

M1-919 proved the D-8 wedge mechanism on the live path (response-shape
sensitivity of the pinned simplex-chat), M1-924 isolates the trigger
field(s) and where the annotation originates, M1-925 confirms the real
supervised shape's exposure. This ticket lands the remedy — in the
record's descending preference: a per-service compose `dns:` remedy where
the origin discriminator allows one, an upstream simplex-chat issue
always, and explicitly NOTHING from the falsified family (gai.conf,
rootless v6, pins). Today the live stacks "work" only behind never-ship
`extra_hosts` pins that bypass the surface entirely; the supported
rootless posture has no tracked answer for this defect class. Shared
analysis: `analysis_ref:`.

## DECIDE BEFORE START (from M1-925 round-2 review — user decision owed)

The M1-890 detector indictment recorded in
`docs/measurement/embedded-dns-supervised-shape.md` §5 (observation only;
TOUCHED-BY-THIS-DIFF: no — pre-existing adapter behavior this record
revealed). The start precondition for THIS ticket: the user has decided
between (a) filing a NEW detector ticket — stable active=0/pending>0
after grace becomes a latching/notify condition — and (b) accepting the
documented surveillance silence in this ticket's runbook.

- WHAT: a detector change so sustained-pending counts as uncovered rather
  than present.
- WRONG: a bot wedged exactly like M1-925's leg t6 (zero transports,
  resolution-failure state) reports SESSIONS_PRESENT forever via `/get
  subs` (`activeSubs {} , pendingSubs {smp6…: 1}`) and no admin notify
  ever fires — SimpleXAdapter.java:1403-1406 treats any pending
  subscription as presence and clears the zero-counter.
- EXPECTED: after the detector's grace window, a stable active=0 /
  pending>0 state either latches like a zero reading or raises its own
  operator signal.
- WHY HERE: this ticket's runbook paragraphs (acceptance items covering
  the runbook and the detector-coverage statement) must say what the
  detector does and does not cover for this defect class; what they say
  depends on the user's (a)/(b) call. Until decided, do NOT
  `/tick start` this ticket.

## Root cause

Proven (record §3 + M1-924's matrix once run): the client wedges on a DNS
response SHAPE the embedded forwarder emits non-deterministically; the
failure precedes address selection (the client is not plain getaddrinfo —
it emits resolver-level `nameErr` itself). NOT proven until this ticket's
step-0: whether per-service `dns:` (the embedded forwarder's UPSTREAM, on
user-defined networks — the client's endpoint stays 127.0.0.11) changes
the client-visible shape (analysis P9: the documented model says dns:
re-points the forwarder's upstream, not the resolver the client queries;
viability is exactly the origin-discriminator question). The ticket is
safe to start because step-0 runs before any file edit and its STOP arm
lands nothing.

## Pitfalls

Numbered consistently with the analysis document.

- P1: prior-art adoption — gai.conf/M1-911, rootless-v6/M1-910, and
  pins/hosts-writers are falsified or never-ship; an escalated gate does
  not convert this ticket into any of them (the M1-911 P6 precedent).
- P3: never-ship boundary — no relay hostnames, no host-derived IPs on
  shipped surfaces; the ONLY address literal allowed is the one fixed
  generic reserved-subnet constant (identical every deployment).
- P5: detector unweakened (zero messaging-adapter diff) and its real
  coverage honestly documented (FAULT conditionality + partial-wedge gap
  from M1-925's record).
- P6: 8-verify.sh zero diff (WARN-only posture, exit contract).
- P7: base-compose invariant (M1-744) — base gains no networks:/dns:/
  extra_hosts; the base render stays byte-identical and startable
  everywhere; remedy keys are service-scoped in an overlay/generated file.
- P8: runtime host networking forbidden (security.md §Trust boundaries
  6-8; M1-810 pinned network_mode absence) — also no default bridge, no
  resolv.conf bind-mount fights.
- P9: the `dns:` semantics trap — every arm's viability is gated on the
  step-0 end-to-end probe; `dns:` needs IP literals (a compose service
  name is not accepted), so the sidecar arm needs the fixed generic
  subnet or a bring-up-generated overlay in gitignored prod/runtime/;
  landing a mechanism without the probe is untested-change risk.
- P10: sidecar obligations — pinned tag, restart, healthcheck, M1-512
  cap, no host port, no query logging; the failure direction inverts
  (sidecar death = provider DNS outage, detector-visible only at session
  granularity); docs disclose it.
- P11: fixture calibration — the wiring tests pin ONLY the landed end
  state (exact step-0-verified form); nothing pins a pre-remedy state.
- P12: upstream-issue honesty — only the isolated trigger + recipe +
  pin; no internals or version-generalization claims.
- P13: no spec edit; the runbook is docs/design/07-deployment.md; even
  the STOP arm is a design-note record, not a spec change.

## Approach

Derived from spec_refs: deployment.md §Deployment scenarios commits the
four wizard-driven single-host shapes to running — the remedy keeps that
promise on the supported rootless posture; §Health and observability is
the detector stance the remedy must leave intact; security.md §Trust
boundaries 6-8 forbid new exposure and runtime host networking; §SSRF's
allowlist gates resolved IPs and is resolver-agnostic, so changing which
resolver produces addresses weakens nothing (blocklist applies per hop
regardless).

- **Files to touch:** `files_scope:` — a NEW tracked overlay
  (`docker-compose.dns.yml`, sidecar arm) or a runtime-generated overlay
  in gitignored `prod/runtime/` (derived-upstream arm) plus threading at
  the four provider-recreating sites; one new test class; one docs file.
  Which arm's files actually change is decided by step-0 — the plan
  covers both; departure from the plan is a hurdle, and the STOP arm
  changes none of them.
- **Steps, in order:**
  1. STEP-0 GATE (P9): run acceptance 1's end-to-end candidate matrix on
     a throwaway network (reusing M1-924's harness); record the green
     arm or fire the STOP arm — before any file edit.
  2. Write ProviderDnsWiringTest's reproduction + negative layers — run
     RED on main (workflow §0; no remedy keys exist — verified this
     session).
  3. Land the chosen arm in the step-0-verified form: sidecar arm →
     `docker-compose.dns.yml` (service + fixed generic reserved-subnet
     network + the provider's `dns:` in the overlay's provider stanza;
     operator override `INFOCHAT_DNS_COMPAT=off` honored at bring-up);
     derived-upstream arm → the bring-up scripts derive the host's
     non-loopback resolvers and thread the generated overlay at all four
     sites (partial threading is the P1-flapping trap — every site or
     none). Echoed compose argv per the §7.7.1 script shape.
  4. Threading drives / obligations pins per the landed arm
     (acceptances 4-5).
  5. Docs runbook (acceptance 8) + the upstream issue (acceptance 9).
  6. Module test run + `mvn verify`; rollout evidence on the test
     checkout (acceptance 11) after re-verifying the live-host facts
     (the pins-present fact was carried as an ASSUMPTION — analysis
     Ground truth).
- **Controls to preserve (§10):** base-compose invariants byte-untouched;
  the M1-808/810 network keys and guidance texts unmodified; every
  pre-existing wiring test byte-untouched (new class only); the detector
  family and 8-verify.sh zero-diff; secrets.env never sourced (the
  override is an operator-exported env var); D37 hygiene for any new log
  text; the echoed-before-run script shape for any threaded argv.
- **Pitfall→mitigation:** P1→out_of_scope + review gate; P3→acceptance 3's
  scan; P5/P6→acceptances 6-7 diff guards; P7/P8→acceptance 3's negatives;
  P9→step 1 + the STOP arm; P10→acceptance 5 + the runbook disclosure;
  P11→acceptance 2 pins only the landed form; P12→acceptance 9's scope;
  P13→acceptance 8's no-spec probe.

## Definition of done

Either: the step-0-validated remedy is landed in its exact verified form,
pinned by the new wiring test (with the base-file negatives, surface scan,
and — per arm — threading drives and sidecar obligations all green), the
runbook records mechanism/override/reversal/detector-gaps, the upstream
issue is filed with its URL in the commit, rollout evidence shows the test
checkout pin-free across ≥3 fresh starts with the detector quiet and the
override's reversal honest, and mvn verify is green. Or: the STOP arm
fired — no mechanism landed, the escalation + documented-limitation
paragraph + upstream issue stand as the record, and the user decision is
logged. Nothing from the falsified family ships in either ending.

## Verification

- P1 → out_of_scope enumeration + review gate on the diff (no gai.conf /
  enable_ipv6 / extra_hosts anywhere).
- P3 → acceptance 3's generic-surface scan (mutation: a relay hostname or
  host-derived literal fails it).
- P5 → acceptance 6's adapter-diff guard + the runbook's coverage
  statements sourced from M1-925's record.
- P6 → acceptance 7's zero-diff probe.
- P7/P8 → acceptance 3's base-file and network_mode negatives (mutations
  fail).
- P9 → acceptance 1's recorded end-to-end gate (client-visible dig +
  session-establishing drive); STOP arm recorded with its decision rule.
- P10 → acceptance 5's obligation pins + the runbook's failure-direction
  disclosure.
- P11 → acceptance 2 pins exactly the landed form (the M1-911 pattern).
- P12 → acceptance 9's issue-scope rule + commit probe.
- P13 → acceptance 8's docs-diff scope probe.
- Reproduction → acceptance 2 (run RED first at `start`).
- Failure-mode → acceptance 3's three negative layers (each names its
  mutation); acceptance 11's override-off leg (the reversal is proven,
  not asserted).

## Out-of-scope

Named in `out_of_scope:` — the falsified family mechanisms (gai.conf,
rootless v6, pins/hosts-writers); runtime host networking, default-bridge
placement, resolv.conf bind-mounts (rejected shapes, not fallbacks); the
detector code and 8-verify.sh (zero diff); the base compose file's
invariants; the Collector's DNS posture and any signal-cli/JVM resolver
knob; the M1-911 reopen decision (user's). No pre-existing test is
modified — new drives live in the new class only; if one fails for a
reason not named here, escalate rather than edit it (§8).

## Census

Class: tracked deployment-surface sites that shape the provider
container's DNS resolution (re-runnable:
`grep -n 'dns:\|networks:\|extra_hosts\|network_mode' docker-compose*.yml`
— zero hits on main, verified this session).

| Site | Disposition |
|---|---|
| docker-compose.yml infochat-provider (:173-251) | FIXED via overlay keys (this ticket; base file itself gains nothing — P7) |
| docker-compose.yml collector/llamacpp/ollama/postgres blocks | out-of-scope: JVM/other-client paths empirically healthy in the wedged environment (family analysis Ground truth) |
| New dns sidecar service (sidecar arm) | ADDED (this ticket, overlay only, obligations pinned) |
| prod/scripts provider-recreating sites (7-apps.sh:85/89, apps.sh:65, upgrade.sh:115/117, restore.sh:978/1042 — the M1-910 census) | THREADED iff the landed arm requires an overlay flag (acceptance 4); else zero-diff recorded |
| prod/scripts 3-postgres/4-llm/4b-image/stack.sh sites | out-of-scope: never recreate the provider (M1-910 census correction) |
| Test-stack/prod-checkout extra_hosts pins | out-of-scope: live outside this repo; their removal is rollout evidence (acceptance 11), never a diff |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-926-simplex-embedded-dns-remedy.md
```
