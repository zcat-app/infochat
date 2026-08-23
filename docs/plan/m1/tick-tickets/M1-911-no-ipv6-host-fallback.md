---
id: M1-911
title: "No-IPv6-host fallback: image getaddrinfo IPv4 preference"
status: deferred
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  Ipv4PreferenceWiringTest.providerImageCarriesGetaddrinfoIpv4Preference
  (to-be-written — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/rootless-ipv6-deployment-surface.md; converted at
  `start` per workflow §0: written first, run RED against
  infochat-provider/src/main/docker/Dockerfile.jvm, which today writes no
  resolver configuration). The wrong behavior it states: on a host WITHOUT
  global IPv6 whose resolver answers AAAA-first for `*.simplex.im`, the pinned
  simplex-chat (v7.0.0, Dockerfile.jvm:47) tries the unreachable v6 address
  and never falls back to IPv4, so the bot wedges with zero SMP sessions at
  every fresh stack start (D-8 root cause,
  .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §10 :787-831) — and rootless v6
  enablement (M1-910) is impossible on that host class. Verified-absence probe
  on this checkout (2026-08-23): `grep -rn 'gai.conf' .` (excluding .git)
  returns ZERO matches — the image carries no getaddrinfo address-selection
  configuration.
analysis_ref: docs/plan/m1/tick-analysis/rootless-ipv6-deployment-surface.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/docker/Dockerfile.jvm
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/Ipv4PreferenceWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The M1-910 compose/overlay/script surface — sibling ticket; this ticket's
    mechanism is image-level and independent of it.
  - >-
    signal-cli and any JVM resolver knob (java.net.preferIPv4Stack etc.) —
    P12: no shared exposure on the live evidence (the full 2026-08 Signal
    campaign ran green in the wedged rootless container; signal-cli is a JVM
    distribution, Dockerfile.jvm:46, and the JVM does not read gai.conf
    anyway). Zero signal-side diff.
  - >-
    The extra_hosts pins and any resolve-at-start /etc/hosts writer — ruled
    never-ship / rejected (analysis options 5; the repo names no relay
    hostnames outside test fixtures, so a writer would have to discover them
    from agent state — the same rot one layer down). If the step-0 probe
    falsifies the mechanism this ticket ESCALATES; it does not substitute
    either (P6).
  - >-
    Any messaging-adapter source or test (P7 — the M1-889/890/901 detector
    stays the untouched safety net) and any compose/script change.
acceptance:
  - "STEP-0 GATE (P6, ASSUMPTION A2) — live wedge-reproduction probe, run before any file edit, recorded in the commit message (the M1-908 acceptance-2 pattern): against the PINNED simplex-chat v7.0.0 binary in a v6-disabled environment (e.g. a throwaway container with IPv6 disabled) behind AAAA-first DNS answers, (a) WITHOUT the change the wedge reproduces — zero SMP sessions established; (b) WITH the landed /etc/gai.conf v4-preference stanza the client establishes sessions over IPv4. If simplex-chat's resolver does NOT honor gai.conf (own DNS stub / getaddrinfo bypass), the mechanism is inert: STOP and escalate (the M1-890 P7 precedent) — do NOT substitute hostname pins or a hosts-writer; the fallback-of-fallback (documented limitation riding the M1-890 detector) is the user's decision at that point, not this diff's."
  - "REPRODUCTION, now passing: Ipv4PreferenceWiringTest.providerImageCarriesGetaddrinfoIpv4Preference (test_plan.adds) — a static pin of the landed runtime-stage stanza in Dockerfile.jvm (exact text from the step-0-verified form), placed in the runtime stage; a mutation deleting the stanza fails the test (non-vacuity anchor)."
  - "FAILURE-MODE (P11, negative): the test asserts the stanza is a PREFERENCE, not a disable — the landed form reorders RFC 6724 address selection (IPv4-mapped precedence raised) and the file carries NO form that suppresses AAAA results (a v6-only remote must still connect: no A record ⇒ v6 used); a mutation swapping the stanza for a v6-disabling form fails the build. The step-0 probe's recorded output includes the exact gai.conf line verified live."
  - "SCOPE PIN (P12, P7): `git diff` on Dockerfile.jvm shows exactly the one added stanza (the pinned-binary SHA-verified fetch layer and the -DskipTests build-stage line stay byte-untouched), and `git diff --name-only` over the ticket diff shows no messaging-adapter path, no compose file, and no prod/scripts path."
  - "DOCS (07-deployment.md, DISJOINT sections from M1-910 — run serially): record the image-wide v4-preferred getaddrinfo posture and why (AAAA-first resolvers + a no-fallback Haskell client; D-8 class), that JVM consumers (the Provider app, signal-cli) are unaffected, that v6-only remotes still resolve, and the interplay with M1-910's real-v6 enablement (complementary: the preference clears the wedge class everywhere; the overlay delivers real v6 where the host has it). Verification: `git diff --stat docs/` shows exactly docs/design/07-deployment.md."
  - "./mvnw -B -pl infochat-provider test -Dtest='Ipv4PreferenceWiringTest' is green AND mvn verify from the repo root is green (engineering-rules §5)."
  - "ROLLOUT EVIDENCE (recorded in the commit message or rollout notes, not a build test): with the rebuilt image on the test checkout, the extra_hosts pins are removed (P10 — ops action, never a diff) and the wedge stays gone across fresh stack starts with the M1-890 detector green; the no-v6-class proof is the step-0 probe's simulated environment (the test host HAS global v6, so the inert-by-preference property on v6 hosts is also recorded: sessions still establish, family choice per the verified stanza)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/Ipv4PreferenceWiringTest.java
      — providerImageCarriesGetaddrinfoIpv4Preference (reproduction; exact
      landed-stanza pin, runtime-stage placement) and the P11 negative layer
      (preference-not-disable form assertions)
  preserves:
    - all tests currently green on main
    - every pre-existing wiring-test class, byte-untouched (the M1-907 separate-class discipline)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.7.2
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
deferred_on: M1-919
deferred_reason: blocked-on-new-ticket
---

# M1-911: No-IPv6-host fallback: image getaddrinfo IPv4 preference

> **DEFERRED 2026-08-23 — blocked-on-new-ticket (M1-919).** The corrected
> step-0 rerun (valid artifacts on both legs: an EnableIpv6:false network,
> a DNS fixture logging both A and AAAA queries, and the pinned simplex-chat
> v7.0.0.11 logging `Agent connected` / SMP host `smp5.simplex.im` / 2
> subscribed queues) showed the no-gai.conf control leg connects over IPv4
> just as well as the with-gai.conf leg — the wedge this ticket exists to
> clear did NOT reproduce under control, contradicting the recorded D-8 live
> outage evidence. Shipping the gai.conf stanza now would be a fix with no
> confirmed failure; dropping the ticket would leave the live D-8 evidence
> unexplained. Paused until M1-919 reconciles the live-vs-controlled delta
> (image version at outage time, resolver environment, host state). If D-8
> re-confirms as client-side AAAA wedging, this ticket's image-level shape —
> cheap, host-class-agnostic, and after M1-910's abandonment the only
> remaining candidate — is the remedy; reopen via the driver once M1-919 is
> done.

## Context

M1-910 gives containers real outbound IPv6 where the host has it — but a host
WITHOUT global IPv6 still hits the D-8 wedge (AAAA-first resolver answers +
no v6 route + no IPv4 fallback in the Haskell simplex-chat), and rootless v6
is impossible there by definition. This ticket is the analysis's chosen answer
for that deployment class: an in-repo mechanism, not a documented limitation —
one `/etc/gai.conf` precedence stanza in the Provider image that makes glibc
`getaddrinfo` prefer IPv4, so an AAAA-first answer is harmless. It is generic
(zero per-host data, zero relay knowledge) and, if the gate verifies, clears
the wedge class on EVERY host — complementary to M1-910's real-v6 capability,
not a substitute for it. Analysis: `analysis_ref:`.

## Root cause

Proven live (plan §10 :799-808): the client consumes the resolver's
AAAA-first answer and never falls back. What is NOT yet proven (ASSUMPTION
A2): that the pinned simplex-chat v7.0.0 resolves through glibc `getaddrinfo`
and therefore honors gai.conf address selection. The eclipse-temurin:25-jre
runtime base (Dockerfile.jvm:26) is Ubuntu/glibc, so the mechanism exists in
the image; whether the Haskell binary uses it is the step-0 gate's question.
The ticket is safe to start because the gate runs before any file edit and a
falsified gate escalates with nothing shipped.

## Pitfalls

Numbered consistently with the analysis document.

- P5: nothing box-specific ships — the stanza is identical for every
  deployment; no IPs, no hostnames.
- P6: ASSUMPTION A2 — gai.conf honored by the pinned client unverified;
  falsified ⇒ STOP and escalate; never substitute the extra_hosts pins or an
  agent-state-hostname hosts-writer.
- P7: M1-889/890/901 detector untouched (zero messaging-adapter diff).
- P10: the test-checkout pins are the verification fixture, never the diff;
  their removal is recorded rollout evidence.
- P11: the stanza is a PREFERENCE, never a disable — v6-only remotes must
  still connect (RFC 6724 reorder semantics), and the JVM is unaffected; docs
  record the posture.
- P12: no Signal scope creep — no shared exposure on the live evidence; no
  signal-cli/JVM change.

## Approach

Derived from spec_refs: spec/deployment.md §Deployment scenarios commits to
the wizard-driven single-host containerized deployment working on the
supported host classes; the image is the one tracked artifact every host class
shares, so the fallback belongs there rather than in per-host config.

- **Files to touch:** `files_scope:` (Dockerfile.jvm, one new test class, one
  docs file).
- **Steps, in order:**
  1. STEP-0 GATE (P6): run the acceptance-1 wedge-reproduction probe against
     the pinned binary; record both legs (without/with the stanza) and the
     exact verified stanza in the commit message. Falsified ⇒ STOP, escalate.
  2. Write Ipv4PreferenceWiringTest — run RED (workflow §0; the ticket's
     reproduction).
  3. Dockerfile.jvm runtime stage: the verified gai.conf stanza, with a
     one-line comment carrying the stable pointer (the §11 discipline — the
     design-doc section, not a chronicle).
  4. Docs (acceptance 5).
  5. Rebuild the image on the test checkout, remove the pins (ops action),
     fresh-start, record the rollout evidence (acceptance 7).
  6. Module test run + `mvn verify`.
- **Controls to preserve (§10):** the pinned-binary SHA-verified fetch layer
  and its pinned tags (Dockerfile.jvm:47-60), the build-stage `-DskipTests`
  exception line, and every other byte of the Dockerfile; pre-existing wiring
  tests byte-untouched (new class, M1-907 discipline); zero messaging-adapter,
  compose, and prod/scripts diff.
- **Pitfall→mitigation:** P6→step 1 + acceptance 1; P11→acceptance 3's
  negative layer + step 1's verified stanza; P12/P7→acceptance 4's diff
  guards; P5→the stanza is one fixed literal, acceptance 2 pins it; P10→
  acceptance 7.

## Definition of done

The step-0 probe is recorded with both legs and the exact verified stanza; the
image carries the preference (never-disable) stanza, pinned by the new test;
the scope guards show a one-stanza Dockerfile diff and nothing else; the docs
record the posture and the M1-910 interplay; rollout evidence shows the wedge
gone with pins removed and the M1-890 detector green; module run + `mvn
verify` green.

## Verification

- P6 → acceptance 1 (recorded live probe, both legs); falsified ⇒ escalation,
  no mechanism ships.
- P11 → acceptance 3's negative assertions — a v6-disabling mutation fails;
  the step-0 record carries the verified line.
- P5 → acceptance 2's exact-literal pin.
- P7/P12 → acceptance 4's `git diff` guards.
- P10 → acceptance 7 rollout evidence.
- Failure-mode (negative, beyond the reproduction) → acceptance 3's
  preference-not-disable layer: a mutation swapping the stanza for a
  v6-suppressing form fails the build, and the absence of the v4-preference
  stanza fails acceptance 2's exact-literal pin; on the falsified-gate path
  (acceptance 1) the client must never connect over the unreachable family —
  the probe's without-stanza leg asserts the wedge reproduces (zero sessions),
  so a silently-inert mechanism cannot pass as a fix.
- Reproduction → acceptance 2.

## Out-of-scope

Named in `out_of_scope:` — the M1-910 compose/script surface; signal-cli and
all JVM resolver knobs; the extra_hosts pins and any /etc/hosts writer (an
escalated gate does NOT convert this ticket into one of those — the
documented-limitation fallback is the user's decision at escalation); all
messaging-adapter sources. No pre-existing test is modified — if one fails
for a reason not named here, escalate rather than edit it (§8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-911-no-ipv6-host-fallback.md
```
