# Embedded-DNS trigger isolation: the controlled responder matrix

Which response field — the unrequested OPT/EDNS additional record, or the
absent AA bit — trips the pinned simplex-chat client that the rootless
daemon's embedded-DNS forwarder wedges on (the D-8 class, M1-919's E3 open
cell)? This record is the M1-924 answer, measured against the pinned binary
(sha256 re-verified below) with a controlled in-netns responder varying
exactly one field per leg. Evidence-only (the M1-860/M1-919 promotion
shape): no code, no compose, no spec; harness legs under gitignored
`/tmp/m1-924-*` (ephemeral — load-bearing lines inlined verbatim below).

**Status: FINAL — the failure-mode arm fired. ALL FOUR matrix legs connect,
including the exact recorded E2a wire shape; neither candidate field — nor
both together — trips the client under a controlled responder. The E2a
shape↔wedge correlation is contradicted under isolation and the trigger
remains unidentified (next discriminator named in §4; escalation recorded —
M1-926 does NOT ride this matrix). The origin discriminator (§5) is clean
PASSTHROUGH, so a `dns:`-shaped remedy can still control the client-visible
shape; the M1-911 reopen input (§7) is NO.**

## 1. Harness (built and pre-checked before any client leg)

- **Client image/binary:** `infochat-test-infochat-provider:latest`
  (digest `7770930b6727`, the image M1-919's E2 legs ran), binary
  re-verified in-container this session:
  `sha256sum /usr/local/bin/simplex-chat` →
  `393279f37a57ff7a63b92cffbd583d1d8abb5ea13e28f8caea74539d7c8db91d`
  (equals the Dockerfile.jvm:49 pin); self-report `SimpleX Chat v7.0.0.11`.
- **Responder, in-netns:** each leg runs a fresh user-defined network + a
  fresh client container + a sidecar sharing the client's netns
  (`--network container:`), which binds `127.0.0.1:53` and runs ONE of:
  dnsmasq (leg (a); `--no-resolv --no-hosts --host-record` for
  smp4/smp5/smp6 with their real A+AAAA values, AA set, never OPT — the
  E2c fixture shape, TTL 0) or a scriptable Python responder
  (`responder.py`, legs (a′)/(b)/(c)/(d); flags 0x8180/0x8580 and a
  type-41 OPT record as run parameters, TTL 300). The client's
  `/etc/resolv.conf` is rewritten to `nameserver 127.0.0.1`.
- **Wire pre-check (every leg, before the client drive):** from the
  sidecar, `dig +noedns +comments @127.0.0.1` — each leg's flags/counts
  verified on the wire before the drive; all pre-checks are inlined in
  §3. Answer byte-sizes match the recorded E2 shapes exactly: clean
  A 49 / AAAA 61 bytes, OPT-annotated A 60 / AAAA 72 bytes (d8 §3 E2a
  inlined `1/0/1 AAAA … (72)`, E2b `1/0/0 AAAA … (61)`).
- **Harness validation leg (a′):** the Python responder alone could
  malform answers in a way dnsmasq never would and false-positive the
  field legs — so it was first run in the control shape (aa=1, opt=0):
  the client connected (§3), proving the responder itself is
  client-compatible. Only then were (b)/(c)/(d) run, each differing from
  (a′) in exactly the named field.
- **Names served:** a fresh v7 profile bootstraps against a RANDOM
  default broker among smp4/smp5/smp6 (observed: leg (a) picked smp6,
  (a′)/(b)/(d) smp5, (c) smp4 — matching E2b's smp6 and E2c's smp4); the
  responder serves all three names with their real values, so the random
  pick never confounds a leg. The `/c smp5.simplex.im` target drew no
  separate query inside the `-t 75` window in any leg — same single-host
  log shape as E2's own drives; the per-leg signal is the bootstrap
  resolution.
- **Drive shape (P14 — the pinned E2 simplified form):**
  `simplex-chat -d /work/db/leg -p 5199 --user-display-name Probe924-<leg>
  -e "/c smp5.simplex.im" --execute-log messages -t 75`, fresh DB per leg,
  tcpdump `any port 53 or port 443` in-netns, client log captured.
  Disclosed deltas from the E2 legs: TTL (dnsmasq 0 / Python 300 vs
  embedded real TTLs) — TTL is not one of the two candidate fields and is
  held constant within the Python-leg isolation chain.

## 2. Environment (this session, 2026-08-24 ~09:43–10:05 UTC)

| Input | Value | Citation |
|---|---|---|
| binary pin | sha256 equal to Dockerfile.jvm:49 pin; self-report v7.0.0.11 | §1; `/tmp/m1-924-harness/pin-verify.txt` |
| daemon | rootless Docker 29.7.2 (unchanged since 2026-08-16, the d8 §2 row) | `docker info` |
| host resolver | systemd-resolved stub (`nameserver 127.0.0.53`); user-defined containers' embedded forwarder upstream = that stub (`# ExtServers: [host(127.0.0.53)]` in the probe's resolv.conf) | `/tmp/m1-924-freq/probe-resolv.txt` |
| default-bridge containers | NO usable resolver (docker drops the loopback stub) — unrelated apk fetch failures during harness bring-up are explained by this, not by a DNS outage | session note, §6 |
| relay real values | smp4 172.236.220.91 / 2600:3c17::2000:13ff:fe29:459d; smp5 172.236.193.240 / 2600:3c17::2000:25ff:fed0:453b; smp6 172.232.155.131 / 2600:3c09::2000:3eff:fe8f:f0bb | host `getent` this session (equals the d8-recorded values) |

## 3. The four-leg matrix + control (acceptance 1)

Per leg: the varied field, the wire answer to the client's own plain
query, SYN to the A address, and the client's agent-level outcome.

| Leg | Responder shape (flags / OPT) | Wire answer to client | SYN to A:443 | Agent-level outcome |
|---|---|---|---|---|
| leg (a) — control, dnsmasq | `qr aa rd ra`, no OPT | `1/0/0 A … (49)` | **YES** (~0.2 ms after answer) | post-connect `NAME NOT_FOUND` tail (the E4 fresh-profile artifact, as in E2b/E2c) |
| leg (a′) — harness validation, Python, same shape as (a) | `qr aa rd ra`, no OPT | `1/0/0 A … (49)` | **YES** | same tail |
| leg (b) — AA bit isolated | `qr rd ra`, no OPT | `1/0/0 A … (49)` | **YES** | same tail |
| leg (c) — OPT isolated | `qr aa rd ra` + OPT | `1/0/1 A … (60)` | **YES** | same tail |
| leg (d) — both, the live failing shape | `qr rd ra` + OPT | `1/0/1 A … (60)` | **YES** (full TLS+SMP exchange, 75 :443 packets) | same tail |

Verbatim evidence per leg (tcpdump marks authoritative answers with `*`;
pre-check dig flags line; SYN line; client log):

```
leg (a)  pre-check: ;; flags: qr aa rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0
leg (a)  wire: 09:50:04.617678 lo In IP 127.0.0.1.53 > 127.0.0.1.59508: 11552* 1/0/0 A 172.232.155.131 (49)
leg (a)  wire: 09:50:04.617707 lo In IP 127.0.0.1.53 > 127.0.0.1.59508: 18983* 1/0/0 AAAA 2600:3c09::2000:3eff:fe8f:f0bb (61)
leg (a)  SYN:   09:50:04.617859 eth0 Out IP 172.24.0.2.45620 > 172.232.155.131.443: Flags [S], seq 3798345839, ...
leg (a)  client: smp agent error: SMP {serverAddress = "smp://…=@smp6.simplex.im,…", smpErr = NAME {nameErr = NOT_FOUND}}

leg (a′) wire: 09:52:10.545516 lo In IP 127.0.0.1.53 > 127.0.0.1.39625: 2104* 1/0/0 A 172.236.193.240 (49)
leg (a′) wire: 09:52:10.545645 lo In IP 127.0.0.1.53 > 127.0.0.1.39625: 37948* 1/0/0 AAAA 2600:3c17::2000:25ff:fed0:453b (61)
leg (a′) SYN:   1 x [S] > 172.236.193.240.443
leg (a′) client: smp agent error: SMP {serverAddress = "smp://hpq7_4gGJiilmz5Rf-CswuU5kZGkm_zOIooSw6yALRg=@smp5.simplex.im,jjbyvoemxysm7qxap7m5d5m35jzv5qq6gnlv7s4rsn7tdwwmuqciwpid.onion", smpErr = NAME {nameErr = NOT_FOUND}}

leg (b)  pre-check: ;; flags: qr rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0
leg (b)  wire: 09:53:39.727170 lo In IP 127.0.0.1.53 > 127.0.0.1.40962: 19312 1/0/0 A 172.236.193.240 (49)
leg (b)  wire: 09:53:39.727311 lo In IP 127.0.0.1.53 > 127.0.0.1.40962: 4988 1/0/0 AAAA 2600:3c17::2000:25ff:fed0:453b (61)
leg (b)  SYN:   1 x [S] > 172.236.193.240.443
leg (b)  client: smp agent error: SMP {serverAddress = "smp://hpq7_4gGJiilmz5Rf-CswuU5kZGkm_zOIooSw6yALRg=@smp5.simplex.im,jjbyvoemxysm7qxap7m5d5m35jzv5qq6gnlv7s4rsn7tdwwmuqciwpid.onion", smpErr = NAME {nameErr = NOT_FOUND}}

leg (c)  pre-check: ;; flags: qr aa rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1 / ; EDNS: version: 0 …
leg (c)  wire: 09:55:09.449411 lo In IP 127.0.0.1.53 > 127.0.0.1.52763: 42712* 1/0/1 A 172.236.220.91 (60)
leg (c)  wire: 09:55:09.449504 lo In IP 127.0.0.1.53 > 127.0.0.1.52763: 39397* 1/0/1 AAAA 2600:3c17::2000:13ff:fe29:459d (72)
leg (c)  SYN:   1 x [S] > 172.236.220.91.443
leg (c)  client: smp agent error: SMP {serverAddress = "smp://u2dS9sG8nMNURyZwqASV4yROM28Er0luVTx5X1CsMrU=@smp4.simplex.im,o5vmywmrnaxalvz6wi3zicyftgio6psuvyniis6gco6bp6ekl4cqj4id.onion", smpErr = NAME {nameErr = NOT_FOUND}}

leg (d)  pre-check: ;; flags: qr rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1 / ; EDNS: version: 0 …
leg (d)  wire: 09:56:55.497979 lo In IP 127.0.0.1.53 > 127.0.0.1.48427: 46435 1/0/1 A 172.236.193.240 (60)
leg (d)  wire: 09:56:55.498049 lo In IP 127.0.0.1.53 > 127.0.0.1.48427: 38526 1/0/1 AAAA 2600:3c17::2000:25ff:fed0:453b (72)
leg (d)  SYN:   2026-08-24 09:56:55.498158 eth0 Out IP 172.24.0.2.33326 > 172.236.193.240.443: Flags [S], seq 2189556290, win 64240, options [mss 1460,sackOK,TS val 2610947141 ecr 0,nop,wscale 10], length 0  (then 75 further :443 packets: TLS handshake + SMP data)
leg (d)  client: smp agent error: SMP {serverAddress = "smp://…=@smp5.simplex.im,…", smpErr = NAME {nameErr = NOT_FOUND}}
```

(The E2a contrast, from the committed d8 record §3: same binary, answers
`1/0/1` from the embedded forwarder → pcap held ONLY the 2 DNS answers,
zero 443 traffic, `SEND RSLV` → `NAME {nameErr = NOT_FOUND}` at the
resolution stage.)

**Matrix conclusion (the honesty arm, stated exactly):** leg (d) served
the recorded failing shape — `qr rd ra` + OPT on a plain query, answer
sizes byte-identical to E2a — and the client SYN'd 0.11 ms after the
later of the two answers (SYN .498158 vs AAAA answer .498049) and
completed TLS+SMP. ALL FOUR legs connect. No isolated field,
and no combination of the two, trips the pinned client. The E2a/E2b
correlation (OPT-annotated ↔ wedge, clean ↔ connect, both at 127.0.0.11)
does not survive controlled isolation: the two candidate fields the d8
record named are EXONERATED, and the defect's trigger field remains
UNIDENTIFIED. This is recorded as the failure-mode arm the ticket
pre-named — the matrix contradicts the E2a/E2b arms, the remedy ticket
(M1-926) does not ride a contradicted matrix, and the next discriminator
is named rather than guessed.

## 4. Next discriminator (named; escalation)

With the response shape exonerated, the surviving delta between the
wedging observation (E2a) and every connecting leg here is the resolver
ENDPOINT and its live dynamics — the embedded forwarder at 127.0.0.11
versus an in-netns responder at 127.0.0.1 — not any field of the answer
itself. The next cell is therefore:

1. **Live-endpoint trial-counted client drives:** repeated fresh-start
   client legs on user-defined networks with the DEFAULT embedded path
   (no resolv.conf rewrite), tcpdump + client log per trial, N ≥ 10
   (P2), recording per-trial answer shape AND per-trial connect outcome
   — correlating shape and outcome on the very endpoint that produced
   E2a, instead of across endpoints. E2a/E2b were single trials of a
   non-deterministic path; that correlation has never been tested with a
   trial count.
2. **Timing/order emulation:** an in-netns responder that emulates the
   forwarder's live dynamics (upstream-latency-delayed answers, A/AAAA
   answer order swapped or split) to hold the endpoint constant and vary
   timing — the one client-visible property this matrix held constant.

If (1) shows live-path trials never wedging across shapes, E2a becomes
non-reproducible and the class re-opens at daemon/environment level (the
E2a morning's daemon state, upstream resolver behavior through the
127.0.0.53 stub). Until one of these fires, no remedy shape is
designable — hence escalation, not M1-926.

## 5. Origin discriminator (acceptance 2 — M1-926's step-0 input)

Per-service `dns:` on a user-defined network sets the embedded
forwarder's UPSTREAM (documented model; confirmed on the wire: the
probe's resolv.conf carries `nameserver 127.0.0.11` with
`# ExtServers: [172.31.117.53]`). Leg: clean upstream = the dnsmasq
fixture at a fixed IP (real values, AA set, never OPT), probe container
created with `--dns 172.31.117.53`; client-visible shape observed with
`dig +noedns +comments @127.0.0.11`:

```
upstream direct:  ;; flags: qr aa rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0  (TTL 0)
trial 1..5 @127.0.0.11 (A + AAAA, 2 s apart):
  ;; flags: qr aa rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0
  smp5.simplex.im. 0 IN A 172.236.193.240      (AAAA: 2600:3c17::2000:25ff:fed0:453b)
```

**Outcome: FAITHFUL PASSTHROUGH — 5/5 trials.** The forwarder relayed
the clean upstream's answers verbatim (AA bit preserved, TTL 0
preserved, no OPT added): the embedded forwarder does NOT re-annotate a
clean upstream. Consequences: (i) a per-service `dns:` remedy CAN
control the client-visible answer shape end-to-end (that is M1-926's
step-0 input — the lane is open even though this matrix escalated the
trigger question); (ii) read with §3, the OPT in E2a's answers most
plausibly ORIGINATED UPSTREAM (the 127.0.0.53 stub chain), since the
forwarder demonstrably passes shape through.

## 6. Live-path frequency observation (acceptance 5 — non-load-bearing)

12 plain-query trials, 2 s apart, 2026-08-24 10:04 UTC, fresh probe on a
throwaway user-defined network, default upstream chain (embedded
forwarder → host stub):

| Trial | AN/NS/AR | Status |
|---|---|---|
| 1 | 1/0/0 | NOERROR |
| 2 | 1/0/0 | NOERROR |
| 3 | 1/0/0 | NOERROR |
| 4 | 1/0/0 | NOERROR |
| 5 | 1/0/0 | NOERROR |
| 6 | 1/0/0 | NOERROR |
| 7 | 1/0/0 | NOERROR |
| 8 | 1/0/0 | NOERROR |
| 9 | 1/0/0 | NOERROR |
| 10 | 1/0/0 | NOERROR |
| 11 | 1/0/0 | NOERROR |
| 12 | 1/0/0 | NOERROR |

**Summary: 0/12 answers carried the unrequested OPT in this window
(12/12 clean).** Honest bounds: this is one ~25 s window on one morning;
the d8 record observed the annotation non-deterministically across ITS
legs the same day — absence here bounds nothing about other windows, it
only records that the annotation was not active at 10:04 UTC. (Session
note: a first 12-trial run was INVALID and discarded — the probe
container's lifetime had expired and exec failures were misread as
timeouts by the loop; the run above used a fresh probe with explicit
exec-error detection. Also recorded so future sessions do not misread
it: default-BRIDGE containers on this host have no usable resolver at
all — docker drops the loopback stub from the host resolv.conf — which
explains any bare `docker run alpine apk …` DNS failure and is NOT a
live-path outage.)

## 7. M1-911 reopen input (acceptance 4)

**NO matrix outcome satisfies M1-911's named reopen condition** (the
wedge reproducing through a client-compatible resolver, re-implicating
getaddrinfo address-family selection). Grounding: every leg's client
CONSUMED the served addresses and SYN'd to the IPv4 A address with the
real global AAAA present in the same answer set — including under the
exact E2a shape (leg (d)). A response-shape trigger would fail in
parsing upstream of address-list construction (the only layer gai.conf
reorders), and no leg reproduced any family-choice wedge at all. The one
conceivable re-implicating arm the analysis named — a clean-responder
leg where the client consumes addresses and then wedges on family
choice — did not fire in any of the five legs (each SYN'd immediately;
the failure this family actually exhibits sits at or before transport
setup, not at family selection). M1-911's deferral stands, now on
stronger evidence than the d8 record had: the wedge is not even
shape-reproducible under control, let alone an address-family mechanism.
(gai.conf / rootless-v6 / pins remain the falsified-or-never-ship set;
nothing here resurrects any of them.)

## 8. Probes re-run list (every claim re-checkable)

`sha256sum` of the pinned binary in the leg image (== Dockerfile.jvm:49);
`docker info` (29.7.2); host `getent ahostsv4/ahostsv6` for smp4/5/6
(real values); per-leg pre-check `dig +noedns +comments` (§3 inlines);
per-leg tcpdump text captures + client logs (§3 inlines); origin-leg
upstream-direct dig + 5 × `@127.0.0.11` dig through the `dns:`-pinned
forwarder (§5); 12 × `@127.0.0.11` plain dig on the default chain (§6);
`grep -nE 'gai.conf|enable_ipv6|extra_hosts' docs/measurement/embedded-dns-trigger-isolation.md`
matches only this section's falsified-family framing (the P1 guard).
Working data: `/tmp/m1-924-{a,aprime,b,c,d,orig,freq,harness}/`
(ephemeral by design; the load-bearing lines are inlined above).

## 9. Disposition

- **M1-926 stays blocked** and does not ride this matrix (its step-0
  input from §5 — passthrough — keeps the `dns:` lane OPEN, but the
  trigger the remedy would target is unidentified; a remedy would be
  built against a hypothesis §3 just falsified). The next discriminator
  (§4) gates any remedy work.
- **Escalation to the user** per the ticket's failure-mode rule: the
  recorded E2a/E2b correlation is contradicted under controlled
  isolation; whether to commission the §4 discriminator (a new
  analysis), treat the wedge as non-reproducible pending live evidence
  (M1-925's supervised-shape cell may still produce a live wedge), or
  close the trigger hunt on the origin finding is the user's call.
- **M1-911**: deferral stands (§7); no edit to its record rides this
  diff.
