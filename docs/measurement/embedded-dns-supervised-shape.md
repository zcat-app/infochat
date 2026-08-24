# Supervised-shape confirmation: the D-8 wedge on the real 6b-minted bot

Whether the REAL supervised bot shape — DBs minted by the exact
`6b-simplex-provision.sh` command set, driven supervisor-style
(`simplex-chat -d <prefix> -p <port>`, no stdin, no `-e` tail) on a
user-defined network with the embedded DNS forwarder (127.0.0.11) and no
`extra_hosts` pins — reproduces the D-8 wedge (zero SMP sessions). This is
the M1-919 record's open E4 row. Evidence-only (the M1-860/M1-919 promotion
shape): no code, no compose, no spec; the drive harness lives under
gitignored `/tmp/m1-925-*` (ephemeral — load-bearing lines inlined verbatim
below). The promoted record for ticket M1-925; shared analysis
`docs/plan/m1/tick-analysis/simplex-client-embedded-dns-response-compatibility.md`.

**Status: FINAL — the fresh-mint supervised shape did NOT wedge in 3/3
fresh starts (plus 2 supplementary legs); the E2 fresh-DB post-connect
`NAME {nameErr = NOT_FOUND}` does not appear on the provisioned shape in
either resolver arm (fresh-profile artifact); and the M1-890 detector's
`/get subs` poll ANSWERS in the induced resolution-failure state, but with
`pendingSubs > 0`, which routes to SESSIONS_PRESENT — the zero-latch never
fires, so the wedge shape is silent to the detector by masking, not by
FAULT. See §6 for the exact scope of the refutation (the live stacks'
6-host migrated DBs were not sampled by a fresh mint).**

## 1. Environment and harness

Every fact below observed 2026-08-24 on this host, throwaway legs only —
zero prod/test-stack contact (no live-stack container named or mounted in
any command; identities minted fresh in the leg; P4).

- Engine: rootless Docker 29.7.2 (context `rootless`), daemon up since
  2026-08-16 — same daemon class as the M1-919 record's environment rows.
- Client binary: local image `infochat-test-infochat-provider:latest`
  (digest sha256:7770930b…, the M1-919 record's rebuilt-test-image digest),
  in-image `/usr/local/bin/simplex-chat` re-verified in-leg:
  `393279f37a57ff7a63b92cffbd583d1d8abb5ea13e28f8caea74539d7c8db91d` —
  equal to the repo pin (`infochat-provider/src/main/docker/Dockerfile.jvm:49`);
  self-reports `SimpleX Chat v7.0.0.11` (one sha, two labels, per the
  analysis ground truth).
- Network: `docker network create m1-925-net` (user-defined, default
  options) — embedded DNS at 127.0.0.11, no pins, no host networking.
- Capture: tcpdump sidecar sharing the client container's netns
  (`--network container:<leg>`, `-i any`, filter `port 53 or port 443`).
- WS probe: a stdlib-python masked-frame WebSocket client sending the
  M1-890 poll's exact command shape
  (`{"corrId":…,"cmd":"/get subs"}`, `SimpleXMessageCodec.encodeGetSubsCommand`)
  against `ws://127.0.0.1:<port>` with a 10 s ack deadline
  (`SESSION_POLL_ACK_TIMEOUT`, SimpleXAdapter.java:135).
- Harness-capture artifact (honest limitation): on the live path the
  client's outbound DNS *queries* never appear in the netns capture
  (rootless embedded-DNS intercept point sits ahead of the capture
  surface; answers materialize on `lo`, queries do not — observed in all
  three trials). Client query FORM is therefore only directly observable
  behind a plain in-netns listener (§4, §5), where both directions
  capture.

## 2. Provisioning — the exact 6b command set (P14)

DBs minted by replicating `prod/scripts/6b-simplex-provision.sh:163-177`
verbatim (direct pinned-binary runs in throwaway containers on
`m1-925-net`, replacing only the script's `docker compose run` wrapper —
the ticket's "or a direct pinned-binary run" arm; data-dir bind-mounted at
`/data`, so the DB prefix is `/data/simplex_v1` and the DBs land inside
the mount as `simplex_v1_chat.db` / `simplex_v1_agent.db`, the 6b shape):

```
simplex-chat -d /data/simplex_v1 -y --create-bot-display-name <name> -t 3 -e "/show_address"
simplex-chat -d /data/simplex_v1 -y -t 10 -e "/ad"
simplex-chat -d /data/simplex_v1 -y -t 4 -e "/auto_accept on"
simplex-chat -d /data/simplex_v1 -y -t 4 -e "/show_address"
```

All four exited 0 with no `^bad chat command|^simplex-chat: ` marker lines
(the script's own failure detector, 6b:144-148). The `/ad` output created
the address on `smp6.simplex.im` (contact link withheld here — bot
identity data stays in `/tmp` working files; D37 posture).

**Relay-host set — divergence from the expected 6.** The ticket expected
the fresh DBs to carry 6 relay hosts (smp4/5/6 + xftp1/4/5, the live
stacks' row in the M1-919 record §2). Ground truth of the fresh mint
(sqlite3 on the minted `simplex_v1_agent.db`):

```
servers:      smp6.simplex.im,bylepyau3….onion   (one entry: clearnet + onion alternate)
xftp_servers: (empty)
```

and the drive logs (§3, leg t4) show the agent connecting to exactly one
relay host, `smp6.simplex.im` (`Agent connected to
smp6.simplex.im,bylepyau3….onion (user 1)`). The 6-host set recorded for
the live bot describes its v6→v7 **migrated** DBs; a fresh 6b mint on the
pinned v7 binary acquires the v7 default preset — one SMP relay host, no
XFTP entries. Consequence recorded in §6: this leg samples the
fresh-mint shape, not the migrated 6-host shape, and the analysis's
"6 resolution chances per start" multiplication does not apply to fresh
mints. No host list was hand-shortened or hand-extended (P14: the DB came
from the command set above, nothing else).

## 3. The supervised live-path drive (acceptance 1)

Drive form = the supervisor's exact invocation
(`SimpleXSubprocess.commandFor`, SimpleXSubprocess.java:186-191):
`simplex-chat -d /data/simplex_v1 -p 5225` (5225 = `DEFAULT_WS_PORT`,
SimpleXConfig.java:50), no stdin, as a daemon. Per trial: fresh container
(same provisioned DBs — the real bot's respawn shape), capture sidecar up
BEFORE the daemon starts, ~95 s observation window (the M1-890 detector's
full 3×30 s grace plus margin). "Fresh start" = new container + new
daemon process on the same network (provider-recreate analogue).

### trial 1 (t1) — no-wedge

- DNS answers (pcap, verbatim): `127.0.0.11.53 > 127.0.0.1.60798:
  17538 q: AAAA? smp6.simplex.im. 1/0/1 AAAA 2600:3c09::2000:3eff:fe8f:f0bb
  ar: . OPT UDPsize=65494 (72)` and `… 61825 q: A? smp6.simplex.im. 1/0/1
  A 172.232.155.131 ar: . OPT UDPsize=65494 (60)` — both answers carry an
  OPT additional record.
- SYN: `11:38:21.943434 … 172.18.0.2.36956 > 172.232.155.131.443: Flags
  [S]` — 0.1 ms after the answers; SYN-ACK back; full TLS+SMP exchange.
- Sessions (`/proc/net/tcp`): one ESTABLISHED to `839BE8AC:01BB`
  (= 172.232.155.131:443); stable across the window (same inode at 40 s
  and ~3 min).
- Log-tail verdict: daemon stdout EMPTY (0 bytes — see below).
- `/get subs` (healthy arm): ANSWERED,
  `{"resp":{"type":"agentSubs","activeSubs":{"smp://PQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo=@smp6.simplex.im,bylepyau3ty4czmn77q4fglvperknl4bi2eb2fdy2bh4jxtf32kf73yd.onion":1},"pendingSubs":{},"removedSubs":{}}}`.
  Capture provenance (applies to EVERY WS-capture block in this record,
  here and in §5): the blocks are raw wire transcripts — no queue-address
  body is masked, replaced, or shortened; the record's single redaction
  is the withheld bot contact link (§2). The server body
  `smp://PQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo=@smp6.simplex.im,…`
  is smp6's PRESET PUBLIC server key, not per-mint material — it is
  byte-identical across independently minted identities by construction:
  this leg's own provisioning output (§2) embeds the same key in the
  freshly minted address link, and the merged M1-919 record's E2a
  `serverAddress` (d8-wedge-reconciliation.md §3, a different mint)
  carries it for the same reason. Its presence here is therefore not a
  copy from that record and not a leak of leg-specific state.
- Same-moment forwarder sample (E3-style, from the trial netns):
  `dig +noedns` → `flags: qr rd ra; … ADDITIONAL: 0` (clean; plain
  queries were NOT annotated at that moment); `dig` (EDNS) →
  `ADDITIONAL: 1 OPT udp: 65494`.

### trial 2 (t2) — no-wedge

- DNS answers: same shape as t1 — `45209 … 1/0/1 A 172.232.155.131 ar: .
  OPT UDPsize=65494 (60)`; `29594 … 1/0/1 AAAA 2600:3c09::… ar: . OPT
  UDPsize=65494 (72)`.
- SYN at `11:42:35.024107` (t+0 of the daemon); one ESTABLISHED session
  to `839BE8AC:01BB` at the 95 s mark.
- Log-tail verdict: daemon stdout EMPTY.
- `/get subs`: ANSWERED, `activeSubs {smp6…: 1}` (identical shape to t1).
- `dig +noedns` sample: `qr rd ra; ADDITIONAL: 0`.

### trial 3 (t3) — no-wedge

- DNS answers: `39077 … 1/0/1 A … OPT UDPsize=65494`; `6568 … 1/0/1 AAAA
  … OPT UDPsize=65494`.
- SYN at `11:44:23.883186`; one ESTABLISHED session to `839BE8AC:01BB`.
- Log-tail verdict: daemon stdout EMPTY.
- `/get subs`: ANSWERED, `activeSubs {smp6…: 1}`.
- `dig +noedns` sample: `qr rd ra; ADDITIONAL: 0`.

### Daemon-form logging (why the log tails are "EMPTY")

The exact supervisor form passes only `-d … -p …`; on it the daemon's
stdout is silent by default (0 bytes in all three trials — and the real
supervisor DISCARDS the streams anyway: SimpleXSubprocess drains and
drops stdout/stderr per D37, SimpleXSubprocess.java:483-517). The
agent-level log channel therefore does not exist on the real shape; the
WS (`/get subs`) is the only agent-level observable the operator has.
For the agent-line evidence arms the supplementary legs below add
observation-only flags (`-l info --log-agent --log-hosts -c`) — logging
is orthogonal to transport, and the resolver/drive semantics are
unchanged.

### Supplementary leg t4 (live path, log-enabled) — the agent tail

```
[NOTE … Transport/Server.hs:246] binding to 127.0.0.1:5225
[INFO … Agent/Client.hs:919] Agent connected to smp6.simplex.im,bylepyau3….onion (user 1)
[INFO … Agent/Client.hs:1893] A (1) --> smp6.simplex.im,… : MSBx SUB
[INFO … Agent.hs:1633] subscribed 1 queues
```

No `NAME {nameErr = NOT_FOUND}` anywhere; the loopback WS bind
(`binding to 127.0.0.1:5225`) holds on the daemon form — the
security.md trust-boundary-7 guarantee holds on this pin.

## 4. Clean-resolver control leg (acceptance 2) — t5

Same provisioned-DB shape (fresh copy of the minted DBs), same daemon +
log flags, behind a controlled clean responder in the same netns: dnsmasq
(listen 127.0.0.1, bind-interfaces, **no upstream**, real values from
today's wire, TTL 0 — the E2c/M1-924 control shape). The ticket names
"M1-924's harness" as the tool; M1-924 is still pending and its harness
is ephemeral `/tmp` working data (absent), so the responder was rebuilt
to the same recorded contract — its exact config, inlined:

```
listen-address=127.0.0.1
bind-interfaces
no-resolv
address=/smp6.simplex.im/172.232.155.131
address=/smp6.simplex.im/2600:3c09::2000:3eff:fe8f:f0bb
log-queries
```

(The client's `/etc/resolv.conf` is a bind-mounted `nameserver
127.0.0.1` — the daemon's `--dns` flag is ignored for `--network
container:` joins on this rootless daemon, observed directly.) Pre-drive
resolver check: `flags: qr aa rd ra`, A/AAAA real values, TTL 0.

- Sessions: one ESTABLISHED to `839BE8AC:01BB` — the clean arm connects.
- Client tail (verbatim, ANSI color escapes stripped as in §3):

```
[NOTE 2026-08-24 09:50:26 +0000 src/Simplex/Messaging/Transport/Server.hs:246] binding to 127.0.0.1:5225
[INFO 2026-08-24 09:50:26 +0000 src/Simplex/Messaging/Agent/Client.hs:919] Agent connected to smp6.simplex.im,bylepyau3ty4czmn77q4fglvperknl4bi2eb2fdy2bh4jxtf32kf73yd.onion (user 1)
[INFO 2026-08-24 09:50:26 +0000 src/Simplex/Messaging/Agent/Client.hs:1893] A (1) --> smp6.simplex.im,bylepyau3ty4czmn77q4fglvperknl4bi2eb2fdy2bh4jxtf32kf73yd.onion : MSBx SUB
[INFO 2026-08-24 09:50:26 +0000 src/Simplex/Messaging/Agent.hs:1633] subscribed 1 queues
```

  **No NAME error.**
- `/get subs`: ANSWERED, `activeSubs {smp6…: 1}`.
- **Client query form on the wire** (the plain listener captures both
  directions — the fact the live path cannot give):

```
11:50:26.753025  127.0.0.1.54240 > 127.0.0.1.53: 28811+ [1au] A? smp6.simplex.im. ar: . OPT UDPsize=1232 [COOKIE 0f7f5aecbafdc4d9] (56)
11:50:26.759723  127.0.0.1.34307 > 127.0.0.1.53: 48481+ [1au] AAAA? smp6.simplex.im. ar: . OPT UDPsize=1232 [COOKIE f345e14f0f79fb8c] (56)
11:50:26.837562  127.0.0.1.44617 > 127.0.0.1.53: 65046+ A? smp6.simplex.im. (33)      <- plain, no OPT
11:50:26.837575  127.0.0.1.44617 > 127.0.0.1.53: 14107+ AAAA? smp6.simplex.im. (33)   <- plain, no OPT
```

  The pinned client sends **EDNS queries first** (OPT, UDPsize 1232, DNS
  COOKIE), then a **plain pair** ~80 ms later; dnsmasq answers each form
  correctly (`1/0/1` with OPT to the EDNS queries, `1/0/0` clean to the
  plain ones) and the client connects regardless. In t6 (§5) the FIRST
  queries of the start were plain — the query form varies across starts.

## 5. Detector view (acceptance 3) — the `/get subs` capture

The natural live path never entered a wedged state (3/3 healthy), so the
resolution-failure state was **induced deterministically** for this
capture: leg t6 = same provisioned shape behind a clean responder
configured to answer NXDOMAIN for the relay host
(`address=/smp6.simplex.im/` with empty target — no-resolv, nothing
else). This induces resolution failure WITHOUT touching the OPT/AA
trigger question (M1-924's cell; no field attribution claimed — P2).

t6 outcome: zero ESTABLISHED :443 sessions across the window (the
wedge-class transport observable); DNS wire shows plain `A?`/`AAAA?`
queries answered `NXDomain … 0/0/0`; client log carries no NAME/RSLV
lines at all (the daemon shape is silent — only a startup
`subcription batch result for replaced SMP client, resubscribing` WARN,
the client's own typo included).

`/get subs` over the loopback bot WS in that state (95 s in):

```
HANDSHAKE: HTTP/1.1 101 WebSocket Protocol Handshake
SENT: {"corrId": "m1925-t6", "cmd": "/get subs"}
RECV: {"corrId":"m1925-t6","resp":{"type":"agentSubs","activeSubs":{},"pendingSubs":{"smp://PQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo=@smp6.simplex.im,bylepyau3ty4czmn77q4fglvperknl4bi2eb2fdy2bh4jxtf32kf73yd.onion":1},"removedSubs":{}}}
VERDICT: ANSWERED
```

(Capture provenance per §3's note: raw wire transcript, nothing masked;
the server body is smp6's preset public key, identical across mints.)

Re-probed at ~4 min: byte-identical counts (ANSWERED, active 0 / pending
1), still zero sessions — the state is stable, not transitional.

Reading against the M1-890 detector (observation only — P5):

- The poll **is answered**, so the FAULT arm
  (SimpleXAdapter.java:1398-1401 — a failed poll is FAULT, never a
  zero-session reading, and FAULT never latches) does **not** fire in
  this state: no surveillance gap of the errors-instead-of-answers form.
- But the answer reports the relay subscription as **pending**:
  `activeSubs {} , pendingSubs {smp6…: 1}`, and the detector's
  presence test is `activeSubscriptions() > 0 || pendingSubscriptions()
  > 0` (SimpleXAdapter.java:1403-1407) → SESSIONS_PRESENT, zero-counter
  reset, `zeroSessionSince` cleared — **the zero-latch can never fire
  while the agent holds the subscription pending**, which in this state
  it does indefinitely.
- Net detector statement for M1-926's runbook: on this shape a
  resolution-failure wedge is INVISIBLE to the M1-890 detector — not
  because the poll faults, but because `pendingSubs` masks the zero
  active count. The detector-indictment boundary (any change is a NEW
  user-filed ticket, never a rider) is respected: this paragraph claims
  observation, nothing ships.

## 6. Conclusion

**Conclusion — refuted for the fresh-mint supervised shape, 3/3 trials
(plus two supplementary legs): the real 6b-minted, supervisor-driven bot
did not wedge on the live path** — every fresh start established its SMP session
within ~0.1 s of the DNS answers, including starts whose answers carried
an OPT additional record, and answered `/get subs` with a non-zero
active count.

Scope and consequences (the honest arms, acceptance 4):

1. This bounds the class's live-path frequency for the fresh-mint shape;
   per the acceptance rule, the remedy-urgency downgrade that follows is
   the user's decision, not this record's.
2. The refutation does NOT extend to the live stacks' v6→v7 migrated
   6-host DBs (§2's divergence): that shape was not sampled here, and
   the original D-8 live outage stands as its only sample. A drive of
   the real migrated DBs is M1-926's rollout-evidence lane (the test
   checkout's pins removed), not this ticket's.
3. Two facts land in M1-924's isolation matrix as inputs, not
   conclusions: (a) the pinned client's DNS query form VARIES per start
   (EDNS-first with COOKIE in t5, plain-first in t6) and OPT-annotated
   answers to EDNS queries are accepted (t1-t3 all connected on `1/0/1`
   answers) — so "OPT present" alone cannot be the wedge trigger, and
   the E2a "OPT on plain queries" attribution now has a simpler
   competing explanation (answers to the client's own EDNS queries);
   (b) the embedded forwarder answered same-moment plain `dig +noedns`
   probes cleanly in every sample (6/6 across t1-t3), while the client's
   answers carried OPT 6/6 — consistent with the client's EDNS queries
   drawing legitimate OPT reflections. Attribution stays M1-924's cell;
   this record contributes the wire facts only.
4. The E2 fresh-DB post-connect `NAME {nameErr = NOT_FOUND}` is a
   **fresh-profile artifact**: absent on the provisioned shape in every
   arm — live-path t4 and clean-resolver t5 both end `subscribed 1
   queues` with no agent error (and the exact-form trials never entered
   the state at all). It is NOT part of the defect: it does not persist
   on provisioned DBs through a clean resolver (nor through the embedded
   one). Both arms' verbatim tails: E2b/E2c's are inlined in the M1-919
   record §3; t4/t5's are §3/§4 above.

## 7. Controls and working data (acceptance 5)

- Repo surface: `git diff --name-only` shows exactly this record (plus
  the ticket frontmatter/board regen) — no compose, script, image,
  messaging-adapter, or spec path touched; no detector line changed.
- No recorded command line names a live-stack container or mounts
  live-bot data; identities were minted in the leg (§2); measurements
  never rode prod or test-stack containers.
- `mvn verify` from the repo root: green (no code touched — log at
  `target/tick-test-M1-925-r1.log`; 4246 tests, 0 failures, 0 errors).
  Three preceding attempts died red in the documented rootless
  publish-port race environment class
  (`.agents/memory/rootless-docker-port-split.md` —
  `RootlessKit PortManager.AddPort(): … address already in use` before
  any test logic, different victim module each time; zero relation to
  this docs-only diff). A concurrent session's probe showed the daemon's
  publish allocator drawing inside the host ephemeral band again after
  its 2026-08-16 restart (the memory's "fixed 40000-band" premise does
  not hold post-restart); remediated host-side by advancing the
  allocator past the band with short-lived auto-publish throwaway
  containers (nothing held, nothing mounted, daemon not restarted) —
  the green run followed immediately.
- Working data (gitignored, ephemeral `/tmp/m1-925-work/`): provisioning
  outputs, per-trial pcaps (t1-t6), client logs, dig samples, WS-probe
  transcripts, dnsmasq configs/logs. Load-bearing lines are inlined
  above; the bot's contact link stays there, not here.
- Leg-harness residue on the host (harmless, local-only): three
  alpine-derived helper images (`m1-925-cap`, `m1-925-python`,
  `m1-925-dnsmasq`) and seven stopped capture sidecars the rootless
  daemon cannot signal (`--network container:` sidecars whose netns
  holder was removed first — a daemon quirk, not a running process);
  they hold no networks and clear at the daemon's next restart, which
  was deliberately NOT done mid-measurement (daemon uptime is a
  recorded environment invariant).
