# D-8 wedge reconciliation: live outage vs controlled no-IPv6 rerun

Why the recorded D-8 live wedge (zero SMP sessions at every fresh stack
start, cleared only by `extra_hosts` IPv4 pins) did not reproduce in the
corrected M1-911 step-0 controlled rerun (pinned client connecting over
IPv4, both legs) — and which explanation survives. Evidence-only (the
M1-860 promotion shape): no code, no compose, no spec. The promoted record
for ticket M1-919; working data under gitignored `/tmp/m1-919-*` and the
prior `/tmp/codex-m1-911-step0-*` (ephemeral — load-bearing lines are
inlined verbatim below, per the house rule).

**Status: FINAL — routing arm (b) chosen: a different root cause class is
identified (client-side DNS-response incompatibility with the rootless
daemon's embedded-DNS forwarder; the recorded AAAA-first + no-fallback
mechanism is disproven on the pinned client). The follow-up analysis brief
is named in §4. M1-911 is not reopened on this evidence; its defer
resolves to a named reopen condition (§5).**

## 1. The two evidence sets being reconciled

- **LIVE (2026-08-19, ~23:10–01:10 UTC):** the test stack's supervised
  simplex-chat subprocess held zero TCP connections to SMP relays at fresh
  start; respawns (2x), provider recreates (2x), full compose down/up did
  not clear it; hardcoded `extra_hosts` IPv4 pins cleared it. Recorded
  root cause (an inference from environment, not from client-side error
  output): host resolver answers AAAA-first, the rootless container has no
  IPv6 route, the Haskell client does not fall back to IPv4. Source:
  `.scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md` §10 :787-831 (stack tuple
  at :783-786: commit `4ce8aea0` + uncommitted reply-mode fix).
- **CONTROLLED (2026-08-23):** the corrected M1-911 step-0 rerun — pinned
  simplex-chat v7.0.0.11 on a `EnableIPv6:false` bridge network, an
  in-netns dnsmasq fixture logging both A and AAAA queries and answering
  both, client logging `Agent connected` / SMP host `smp5.simplex.im` /
  2 queues subscribed on BOTH the no-gai.conf and with-gai.conf legs.
  Sources: `/tmp/codex-m1-911-step0-no-gai-*`, `-with-gai-*` (15 files);
  defer note in `docs/plan/m1/tick-tickets/M1-911-no-ipv6-host-fallback.md`.

## 2. Environment delta table

Every row cites its source; each citation was re-checked with a
`grep`/`git show`/`docker inspect` probe this session (2026-08-24).

| Input | LIVE outage (08-19) | CONTROLLED rerun (08-23) | Same? | Citation |
|---|---|---|---|---|
| simplex-chat binary | pin `v7.0.0` at the outage commit (`ENV SIMPLEX_CHAT_VERSION=v7.0.0`, SHA `393279f3…c8db91d`) — pin in repo since `f140de81` 2026-08-15 (M1-388's `v6.5.4` was the pre-v7 pin, `d4814208`) | same pin class: client self-reports `SimpleX Chat v7.0.0.11`; my re-run binary's sha256 `393279f3…c8db91d` equals the outage commit's pin | **SAME** (the exact 08-19 image digest is unrecoverable — tag rebuilt 2026-08-23 00:26 as `sha256:7770930b…604f013`; version pinned by commit provenance instead) | `git show 4ce8aea0:infochat-provider/src/main/docker/Dockerfile.jvm` (:47,:51-52); `git log -S SIMPLEX_CHAT_VERSION -- …Dockerfile.jvm`; `/tmp/codex-m1-911-step0-version.txt`; `/tmp/m1-919-legA-resolver.txt` (sha256 line) |
| resolver ENDPOINT | daemon embedded DNS `127.0.0.11` (compose default user network; no `dns:` override — `git show 4ce8aea0:docker-compose.yml` has no `dns:` key) | in-netns dnsmasq fixture at `127.0.0.1`, `no upstream servers configured` | **DIFFERENT — the decisive delta** | §10 :783-786 stack tuple; rerun `-client-resolver.txt` (`nameserver 127.0.0.1`); rerun `-dns.log` (dnsmasq banner) |
| resolver ANSWERS | host resolver holds real A + real global AAAA and orders AAAA first for host consumers; embedded DNS returns both families on the wire (re-verified live 08-24: `dig A/AAAA @127.0.0.11` → NOERROR 1/1) | dnsmasq config answers A `172.236.193.240` + AAAA `2001:db8::1` (documentation prefix), both logged answered | family set equivalent; VALUES differ (docs-prefix vs real); response SHAPE differs — see §3 E3 | §10 :799-808; `/tmp/codex-m1-911-step0-no-gai-dns.log`; §3 E1 artifacts |
| response packet shape | embedded forwarder: `qr rd ra` (no AA), real TTLs, and — non-deterministically — an OPT/EDNS additional record on answers to non-EDNS queries | dnsmasq config answers: `qr aa rd ra` (AA set), TTL 0, never an unrequested OPT | **DIFFERENT** | §3 E3 (`dig +noedns +comments` both endpoints, inlined below); rerun `-dns.log`; my tcpdump captures |
| host v6 | host HAS global SLAAC v6 + default v6 route (analysis session probe 2026-08-23; re-confirmed 08-24: `2a02:8308:19:ef00:…/64 scope global`, `default via fe80::… dev eno1`) | same host, same state | SAME | analysis §Ground truth "Session probe"; 08-24 `ip -6 addr/route` capture |
| container v6 | none — only `::1/128` on lo | none — `EnableIPv6:false`, `GlobalIPv6Address: ""` | SAME | §10 :801-803; rerun `-network.json` / `-network-inspect.json` |
| network driver / daemon | rootless Docker 29.7.2, `--net=gvisor-tap-vsock`, daemon up since 2026-08-16 21:18 (pre-outage, unchanged through today — excludes engine drift across the whole window) | same daemon; fixture network `Driver: bridge`, `EnableIPv6: false` | SAME | `docker info`; `ps` rootlesskit/dockerd start times; rerun `-network-inspect.json`; M1-910 abandoned record |
| client drive shape | provider-supervised subprocess (`simplex-chat -d <dbs> -p <port>`, no stdin), pre-provisioned v7-migrated agent DBs, 6 relay hosts (smp4/5/6 + xftp1/4/5) | standalone binary, fresh DBs, single host (smp5), scripted `/c` | **DIFFERENT** (harness family; residual — §3 E4) | §10 :787-831; `6b-simplex-provision.sh` per analysis Ground truth; rerun client logs |
| extra_hosts pins | absent at outage (introduced as the recovery, :809-815; still present in the test checkout's dirty compose today) | absent | SAME | §10 :809-815; `git -C /home/infochat/infochat-test diff docker-compose.yml` |
| in-container curl contrast | recorded: v6 tried → `Network is unreachable` → v4 `172.236.x:443` established (:804-806) | not exercised | reproduced verbatim 08-24 (E1) | §10 :804-806; E1 inline below |

## 3. Decisive experiments (all run 2026-08-24, this host, throwaway
docker-run legs under `/tmp/m1-919-*`; zero prod/test-stack contact)

### E1 — the live resolver path today (validates the §10 environment record)

Host `getent ahosts smp5.simplex.im` → AAAA `2600:3c17::2000:25ff:fed0:453b`
listed before A `172.236.193.240` (host-side AAAA-first confirmed). In a
fresh container on a user-defined network (the live path):

```
# dig AAAA smp5.simplex.im @127.0.0.11   (alpine leg, wire truth)
;; ->>HEADER<<- opcode: QUERY, status: NOERROR … ANSWER: 1
smp5.simplex.im. 7020 IN AAAA 2600:3c17::2000:25ff:fed0:453b

# curl -v https://smp5.simplex.im/  (same netns, provider image)
*   Trying [2600:3c17::2000:25ff:fed0:453b]:443...
* Immediate connect fail for 2600:3c17::2000:25ff:fed0:453b: Network is unreachable
*   Trying 172.236.193.240:443...
```

The §10 curl contrast reproduces verbatim: the embedded DNS delivers both
families on the wire, glibc consumers receive the AAAA (and get instant
ENETUNREACH — no v6 route), happy-eyeballs falls back to v4. Every
recorded live environment fact holds today. (`getent ahosts/ahostsv6`
inside such containers prints v4-only — a getent/AI_ADDRCONFIG-family
artifact, NOT the wire or getaddrinfo truth for ordinary consumers; the
M1-911 rerun's `getent` artifacts therefore cannot attest what the client
saw, in either direction.)

### E2 — the discriminating matrix (same image, same netns, same client;
only the resolver endpoint/response shape varies)

Legs driven as `simplex-chat -d <fresh-db> -p <port> --user-display-name
Probe919* -e "/c smp5.simplex.im" --execute-log messages -t <n>` on the
pinned binary (sha256 equals the `4ce8aea0` pin), tcpdump on `any` port
53/443, `/proc/net/tcp{,6}` read for established sockets.

| Leg | Resolver | DNS answers on the wire (tcpdump) | Client transport | Agent-level outcome |
|---|---|---|---|---|
| E2a | embedded `127.0.0.11` | `A/AAAA … 1/0/1` — **with an OPT additional record** on answers to the client's plain (non-EDNS) queries | **NO SYN — zero TCP sessions** (pcap: only the 2 DNS answers; no 443 traffic) | `SEND RSLV` → `NAME {nameErr = NOT_FOUND}` — **the D-8 wedge observable reproduced** |
| E2b | embedded `127.0.0.11` | `A/AAAA … 1/0/0` — clean, no OPT | SYN to the A address ~1 ms after the A answer; full TLS + SMP exchange over IPv4 (`172.232.155.131:443`), **with the real AAAA present in the same answer set** | still ends in an agent-level `NAME NOT_FOUND` (see E4) |
| E2c | in-netns dnsmasq `127.0.0.1`, REAL values (`A 172.236.220.91`, `AAAA 2600:3c17::2000:13ff:fe29:459d`) | `1/0/0` clean, AA set | SYN → TLS → SMP data over IPv4 (`172.236.220.91:443`) | same agent-level tail as E2b |

Verbatim, the two arms (client debug log, E2a):

```
[INFO … Agent/Client.hs:1893] A (1) --> smp6.simplex.im,bylepyau3….onion :  SEND RSLV
[INFO … Agent/Client.hs:919]  Agent connected to smp6.simplex.im,… (user 1)
[INFO … Agent/Client.hs:1893] A (1) <-- smp6.simplex.im,… :  SMP {serverAddress = "smp://PQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo=@smp6.simplex.im,…", smpErr = NAME {nameErr = NOT_FOUND}}
```

and the wire difference between the failing and working embedded-DNS legs
(E2a pcap vs E2b pcap):

```
E2a: IP 127.0.0.11.53 > 127.0.0.1.45351: 4328 1/0/1 AAAA 2600:3c17::… (72)   <- OPT
E2b: IP 127.0.0.11.53 > 127.0.0.1.34944: 37053 1/0/0 AAAA 2600:3c09::… (61)  <- clean, then SYN
```

**Conclusion E2:** connect-vs-wedge follows the DNS response SHAPE, not
the presence of the AAAA. Given both families answered cleanly — including
the real global AAAA — the pinned v7.0.0.11 reaches IPv4 and completes
TLS+SMP (E2b, E2c): the recorded "client does not fall back to IPv4"
mechanism is **disproven on the current pin**. Conversely, when the
embedded forwarder annotates its answers with an OPT record the client did
not ask for, the client's resolver rejects the response and no transport
session is ever attempted (E2a) — a concrete zero-session wedge mechanism
on the exact live path, reproduced today.

This also dissolves the original contradiction symmetrically: the M1-911
fixture (dnsmasq) could only ever produce clean authoritative answers, so
its "connects over IPv4" result was real but uninformative about the live
path's failure layer — the two evidence sets ran different resolver
endpoints with different response shapes.

### E3 — narrowing the response-shape trigger (recorded as inconclusive;
next discriminator named)

`dig +noedns +noall +comments` against both endpoints, same netns:

```
embedded: flags: qr rd ra;  … ADDITIONAL: 0   (TTL 4953; with-EDNS queries: ADDITIONAL: 1 OPT)
dnsmasq:  flags: qr aa rd ra; … ADDITIONAL: 0 (TTL 0)
```

Two candidate triggers remain: (i) an OPT/EDNS additional record on a
non-EDNS query (observed live in E2a), and (ii) the absent AA bit. They
co-occur in every observation so far; isolating which one the client's
resolver rejects requires a controlled responder varying one field at a
time — that probe is named as the first cell of the follow-up brief (§4).
Recorded as inconclusive per the acceptance rule; it does not affect the
routing decision, which rests on E2's outcome-level result.

### E4 — harness residual (does not bear on the routing decision)

All my fresh-profile drives end in an agent-level `NAME {nameErr =
NOT_FOUND}` AFTER a successful transport connect+TLS (E2b/E2c) or in the
resolution-stage failure (E2a); I did not reproduce the M1-911 harness's
terminal `subscribed 2 queues` state (their DBs held pre-created queues;
my re-subscribe attempt produced no server activity). The drive-shape
delta row (§2) stays open for the follow-up brief: whether the
pre-provisioned supervised flow also hits the post-connect NAME error on
the live path is untested here — today's real bot runs behind the
extra_hosts pins, whose /etc/hosts short-circuit bypasses this entire
surface (which is exactly why the pins "work").

## 4. Routing decision — arm (b): different root cause class

Exactly one arm is chosen: **(b) a different root cause is identified**,
and the fresh analysis brief it warrants is named:
`simplex-client-embedded-dns-response-compatibility`.

Justification against the rows, not the narratives:

1. The version row is SAME (pin since 2026-08-15; binary sha256 equal to
   the outage pin) — version drift cannot explain live-wedge vs
   controlled-connect. The daemon row is SAME (up since 08-16) — engine
   drift cannot either. The container-v6 row is SAME. Those three close
   the "environmental input" explanation space the ticket named.
2. The resolver-ENDPOINT row is DIFFERENT and decisive: the controlled
   rerun's dnsmasq can only emit clean AA answers (and its AAAA was a
   inert-ish docs-prefix value whose family membership never mattered);
   the live path's embedded forwarder emits RA answers that — observed
   non-deterministically today — carry an OPT record on non-EDNS queries.
   E2 reproduced BOTH outcomes on the live path itself by varying only
   that shape: OPT-annotated → zero TCP sessions + client `NAME
   NOT_FOUND` (the D-8 observable); clean → IPv4 connect + TLS with the
   real AAAA present.
3. Therefore the recorded root-cause MECHANISM ("AAAA-first answer, no
   IPv4 fallback in the client") is disproven on the pinned client: the
   §10 inference generalized from curl's address-family behavior to the
   Haskell client's, but the client fails at a different layer — its own
   DNS response handling — and only when the response shape trips it.

The follow-up brief's scope (for the user to file; no ticket is created by
this record): first cell = the E3 field-isolation responder (OPT-vs-AA);
then the supervised pre-provisioned-DB drive (E4's open row); remediation
candidates to analyze — a per-service `dns:` override pointing the
provider at a client-compatible resolver, or an upstream simplex-chat
report — explicitly NOT `/etc/gai.conf` (the failure precedes address
selection, and the client's resolution is not plain getaddrinfo); the
M1-890 zero-SMP detector remains the standing safety net for the class;
the never-ship `extra_hosts` pins remain the working bypass on the test
and prod checkouts.

## 5. Disposition of the paused family

- **M1-910** (rootless v6 enablement): stays abandoned — wont-do-
  infeasible per its own terminal record; nothing here resurrects it (the
  wedge does not require container v6 at all).
- **M1-911** (gai.conf IPv4 preference): NOT reopened on this evidence.
  Its step-0 falsification clause is the live outcome: the wedge this
  ticket existed to clear does not reproduce as an address-family-
  selection failure on the pinned client (E2b/E2c connect with the real
  AAAA present), and gai.conf acts at a layer the observed failure never
  reaches. Reopen condition (named): the follow-up brief's discriminator
  re-implicates getaddrinfo address-family selection — i.e. the wedge
  reproduces through a client-compatible resolver on the current pin.
  Until then the defer is discharged with this record as its conclusion;
  see the updated pointer in
  `docs/plan/m1/tick-tickets/M1-911-no-ipv6-host-fallback.md`.

## 6. Probes re-run list (acceptance: every citation re-checked)

`git show 4ce8aea0:…Dockerfile.jvm` (version pin + SHA); `git log -S
SIMPLEX_CHAT_VERSION` (f140de81 2026-08-15 v7.0.0; d4814208 v6.5.4);
`git show 4ce8aea0:docker-compose.yml` (no `dns:` key; provider `build:`);
`docker inspect infochat-test-infochat-provider:latest` (sha256:7770930b…,
built 2026-08-23T00:26); `docker info` + `ps` (engine 29.7.2, gvisor-tap-
vsock, daemon since 2026-08-16 21:18); `git -C …/infochat-test diff
docker-compose.yml` (pins present, TEST-RUNTIME-ONLY comment); §10 :787-831
read; all 15 `/tmp/codex-m1-911-step0-*` files read; M1-910/M1-911 ticket
records read; host `ip -6`, `getent`, in-container `dig`/`curl`/`getent`,
five tcpdump'd client legs (`/tmp/m1-919-*`).
