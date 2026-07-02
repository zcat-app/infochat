# Live E2E — Handoff & Progress Log

> **Lightweight session-to-session tracker** for the live end-to-end run.
> This file answers "where are we, what's next, what changed" at a glance.
> The full plan (phase checklists, transport strategy, reset/data strategy,
> differences checklist, load-bearing code facts, canonical decisions log) lives
> in [`README.md`](README.md) — that stays the source of truth; this file links
> into it. `process:` commit prefix (docs-only, no ticket, no `mvn verify`).

Last updated: 2026-07-02 · Owner: ubuntu5 + Claude

---

## ▶ START HERE (fresh session — next step)

**Next step = `/m1-tick run M1-545` (drafted, pending, clarity NOT yet run),
then draft+run M1-546, then the live 4b-3 scenario run.** Phase 4b-3 is in
progress; grounding, fixtures, and design are DONE (2026-07-02, this section):

- **The 15-scenario enumeration is now persisted** in `README.md` §Phase 1
  (recovered from the audit transcript). Live set: S3 invite mint→consume,
  S4 un-invited DM rejected, S7 group pending→approve→auto-promote,
  S10 /summary + group digest, S11 /zcash, S12 chat mode, S15 full happy path.
- **Host fixtures DONE:** LiveUser is now CONNECTED to the bot (bot appears as
  contact "Admin-Reno" in BOTH client DBs — resolve by that display name).
  Bot address re-queried via one-shot `/show_address` (provider stopped
  briefly, then restarted; address in `.scratch/bot-address.txt`). LiveAdmin
  is claimed bot admin. Stack UP and healthy.
- **M1-545 (drafted, pending):** scenario grammar `capture <name> <regex>` +
  `${name}` substitution in send text/addresses — S3/S15 need cross-step data
  flow (invite code from a reply sent in a later step); the M1-539 grammar
  cannot express it. CI-provable on InMemory (extend ScenarioRunnerIT with a
  declarative invite mint→consume flow). Superset grammar; existing scenarios
  unchanged.
- **M1-546 (to draft next):** live backend v2, all test-scope —
  (a) rework `LiveSimpleXClient` to a SINGLE raw java.net.http WebSocket
  connection whose every frame is fed through the production
  `SimpleXMessageCodec.decode()` (static, package-private — accessible from
  the bridge's package) for everything the codec models (Inbound,
  GroupCandidate, SendAck/corrId, CommandError); drop the
  SimpleXWebSocketClient wrapper (its pending-futures complete ONLY on send
  acks, and async events go to one connection only — the M1-544 side-socket
  workaround does not scale to group/edit observation);
  (b) harness-side parse of `chatItemUpdated` frames ONLY (the codec has no
  case for it — bot-side never consumes edits; progress-notified replies
  (/summary, chat, digest) finalize via item EDIT, so S10/S12/S15 are
  unobservable without it — union it into awaitReply like
  InMemoryConversationBackend#finalizedBodies);
  (c) GROUP binding in `SimpleXConversationBackend` (scenario group tokens →
  per-client group ids resolved via a raw corrId `/groups` query; group send =
  codec `encodeSendCommand(ScopeRef.Group)`);
  (d) a harness-side MENTION envelope for group sends: the bot's mention
  recognition is STRUCTURED-ONLY (D51: `mentions{}` memberId must byte-equal
  botMemberId — SimpleXGroupHandler.java:70; plain-text "@Name" is silently
  dropped). The adapter encoder has no mention support (bot never mentions),
  so the harness composes it; exact wire shape is a LIVE-discovery item —
  best-guess in CI, validate/fix on the host;
  (e) the 7 live `.scenario` resources + a gated suite IT (same
  `-Dinfochat.live.simplex=true` gate as LiveSimpleXRoundTripIT).
- **Live-run notes for after M1-546:** group fixtures via raw corrId commands
  from the harness connection (`/g`, invite bot from LiveAdmin — the M1-515
  provider gate decides the join); scenario timeouts must be generous (llama
  on 4 vCPU: chat/summary can take 60-120 s); S3 needs an UNREGISTERED user →
  run `prod/live-reset.sh` first (control-plane reset; admin-token re-arms by
  design D-live-7, re-claim then drive); /summary needs seeded READY corpus
  (`prod/live-seed.sh`) + a subscription.


**Prior context (all DONE):** F-live-2 fixed by M1-542 (dfbb86ca); F-live-1
fixed by M1-543 (8ed35718 — dispatch-thread TCCL vs `@ConfigProperty` at lazy
ARC create; `AdapterRegistry` classloader pin) and live-verified (bot replies).

**Completed live actions (kept for reference):**
1. ~~Present the admin token~~ **DONE (2026-07-02):** LiveAdmin claimed
   bootstrap admin via the D50 token — DB verified (`is_admin=t`, `vouched`,
   `probation_until=NULL`). Operator hygiene per security.md §Per-adapter
   admin threat profile: consider UNSETTING `INFOCHAT_SIMPLEX_ADMIN_TOKEN` in
   `prod/runtime/secrets.env` now that the first admin exists (a still-set
   token re-arms on `/revoke-admin`). NOT done — operator's call.
2. ~~4b-2 `SimpleXConversationBackend`~~ **DONE (2026-07-02): M1-544 merged
   (1a2be05e).** `LiveSimpleXClient` (provider test scope, simplex impl
   package) composes SimpleXSubprocess + SimpleXWebSocketClient +
   SimpleXMessageCodec; `SimpleXConversationBackend` binds scenario DM tokens
   to live clients; `LiveSimpleXRoundTripIT` (gated
   `-Dinfochat.live.simplex=true`, skips in CI) drove `/help` LiveAdmin→bot
   through the unmodified ScenarioRunner: **matched in 591 ms, real relays.**
   Host run: `mvn -pl infochat-provider test -Dtest=LiveSimpleXRoundTripIT
   -Dinfochat.live.simplex=true` (client WS port 5226; needs stack UP and no
   CLI one-shot holding the admin DB).
3. 4b-3 IN PROGRESS — see §START HERE at the top (M1-545 pending, M1-546 to
   draft, LiveUser now joined; the grammar-gap / GROUP / item-edit / mention
   analysis lives there).

**Repro command (stack is UP):**
`prod/runtime/simplex-clients/bin/simplex-chat -d prod/runtime/simplex-clients/admin/simplex_v1 -y -t 6 -e "@Admin-Reno <text>"`
then `docker logs --since 90s infochat-infochat-provider-1 | grep 'inbound handler threw'` (expect NO hits post-M1-543).

**HOST STATE:**
- **App stack is UP (all 5 containers)** — resumed 2026-07-02 21:20 for the
  M1-543 round-trip with the provider image rebuilt from main (M1-542+M1-543).
  Health: `docker exec infochat-infochat-provider-1 sh -c 'curl -s 127.0.0.1:8081/q/health/ready'`.
- **Do NOT run image builds / `mvn verify` while the stack is up** (06-28
  throttle condition); stop collector+provider first.
- **Swap enabled** (8 GiB, swappiness=10) — the missing 06-28 safety margin is in place.
- **Clients provisioned:** `LiveAdmin` + `LiveUser` under `prod/runtime/simplex-clients/{admin,user}/` (native baked binary v6.5.4.1 at `.../bin/`). Admin is connected to the bot (contact "Admin-Reno"). Bot `/ad` link was transient (not saved); the clients are already joined, so re-query only if adding a new client.

**Clean verify-monitoring (user wants a complete report next run):** the M1-542
attempt's monitor subagent hung on a self-referential `pgrep -f 'verify-serialized.sh'`
(its own cmdline matched the pattern → loop never exited). Next run, either (a) let a
background bash sampler loop forever and **stop it from the main thread** on the
verify's completion notification, or (b) bracket-trick the pattern (`pgrep -f '[c]lean verify'`)
or poll the build log for `BUILD SUCCESS|FAILURE`. See `[[clean-verify-monitoring]]` memory.
Also: `mvn verify` leaks ~5 DevServices Postgres containers each run — clean with
`docker rm -f $(docker ps --filter label=org.testcontainers=true -q)` after; a
DevServices-reuse optimization is a candidate follow-up ticket.

**After M1-543 lands:** ONE live deploy (provider rebuilt with M1-542+M1-543) →
re-send the admin DM → confirm the bot REPLIES (round-trip). That is the real
acceptance; there is no green-CI substitute for the round-trip (D-live-9). Then
resume the original Phase 4b: `SimpleXConversationBackend` + the 7 transport-relevant
scenarios. Signal (3 numbers — bot + admin automatable + phone-driven user) is Phase 5.

**Full plan / reuse targets (unchanged):** `README.md` (§1 targeting, §Phase 4b
checklist, §6 differences, §8 decisions) → `simplex-live-frame-capture` memory →
`.scratch/simplex-spike-findings.md` → `SimpleXWebSocketClient` + `SimpleXMessageCodec`
(infochat-messaging-adapter) + `ConversationBackend`/`ScenarioRunner`/
`InMemoryConversationBackend` (infochat-provider/src/test/.../live/).

---

## Current state (one line)

The substrate for the live run is **built and green** (Phases 0–4a done). **Nothing
has run against a real transport yet** — the first real live work is Phase 4b.

## Where we are

| Phase | What | Status |
|---|---|---|
| 0 — Framing & decisions | targeting principle, transport strategy | ✅ DONE |
| 1 — Coverage audit | 15 scenarios; 7 transport-relevant, 0 logic gaps | ✅ DONE |
| 2 — Load-bearing assumptions | admin-token re-arm, SSRF strict, FK trap, caps | ✅ DONE |
| 3 — Reset & data harness | `prod/live-reset.sh`, `live-seed.sh`, `live-inject-adversarial.sh` | ✅ DONE (M1-536/537/538) |
| **4a — scenario runner substrate** | `Scenario`, `ConversationBackend` SPI, `ScenarioRunner`, InMemory backend, `ScenarioRunnerIT` | ✅ DONE (M1-539) |
| **4b — SimpleX live drive** | real simplex-chat drive + LLM latency + embedding retrieval | 🔶 IN PROGRESS — stack up, clients provisioned, admin↔bot handshake done, channel proven; first inbound surfaced F-live-1 |
| **5 — Signal delta** | round-trip + ACI bootstrap + §6 differences | ❌ NOT STARTED |
| 6 — (optional) `/testcase` skill | wrap the runner once 2–3 scenarios pass | ❌ not started |

**Concrete "done vs not" marker:** the only `ConversationBackend` implementation on
disk is `InMemoryConversationBackend`. There is **no `SimpleXConversationBackend`
and no `SignalConversationBackend` yet** — that binding is the Phase 4b/5 boundary.

## Next actions (in order)

1. [ ] **4b-1** — Provision 3 simplex-chat identities. **Bot: scripted** via
       `prod/scripts/6b-simplex-provision.sh` (run by `7-apps.sh`; profile + `/ad`
       address + `/auto_accept on`). **Admin + user clients: NEW tooling** — the
       harness needs its own client instances (separate data-dirs + ws-ports, baked
       binary from the Provider image), joined to the bot via its `/ad` link. Admin
       becomes admin by DMing the `admin-token` (not a pre-set address). Host-side.
2. [ ] **4b-2** — Write `SimpleXConversationBackend` behind the existing
       `ConversationBackend` SPI and **validate it against real simplex-chat on the
       host** — drive the WS API (corrId command/response + async inbound), reusing
       the reality-reconciled `SimpleXMessageCodec` / `SimpleXWebSocketClient` (one
       wire-shape source of truth, no forked encoder). **Host-validated, NOT a
       fake-backed CI ticket** (D-live-9): fakes have hidden real SimpleX bugs here
       (M1-508/510/511), and the contact handshake + async-per-connection receive
       are exactly what a fake can't model. A FakeSimpleXProcess regression IT is a
       *later* ticket, seeded with frames captured on the first real run.
3. [ ] **4b-3** — Run the 7 transport-relevant scenarios (3,4,7,10,11,12,15) over
       real SimpleX via the runner.
4. [ ] **4b-4** — Assert real LLM latency captured; embedding-retrieval assertion
       (ask a topic covered by exactly one seeded post → assert it's retrieved);
       readiness/liveness, per-adapter metrics, LLM-down = degraded.
5. [ ] **5** — Signal delta (bot + 1 admin; see constraint below).

## Live findings (Phase 4b — real transport)

### F-live-1 (HIGH) — RESOLVED by M1-543 (merged 8ed35718, 2026-07-02)

**Root cause (revealed by M1-542's cause-chain logger on a rebuilt-image live
probe):** `Caused by: java.lang.IllegalArgumentException` at
`SmallRyeConfigProviderResolver.get` — "no config for classloader". The
`simplex-inbound-dispatch` virtual thread (created lazily by a JDK-internal
HttpClient thread) carries a context classloader with no registered
MicroProfile `Config`; `@ConfigProperty` injection at lazy ARC creation of
`SimpleXAdminClaim` resolves Config by TCCL → throws → ARC wraps in a bare
`RuntimeException` → D37 catch drops the message. Green in CI because every
`@QuarkusTest` dispatches from the JUnit thread (TCCL = app classloader) — the
D-live-9 thesis exactly. **Fix:** `AdapterRegistry` pins the application
classloader (finally-restored) around all three adapter callback lambdas.
Regression test: `InboundDispatchForeignContextClassLoaderTest` (red-before /
green-after). Round-trip verified live 2026-07-02 21:22 (bot replied).
Diagnosis gotcha for next time: overlaying a rebuilt jar into the provider
image via bind mount does NOT take effect for classes present in
`/app/quarkus/transformed-bytecode.jar` (fast-jar loads those first) — a
full image rebuild was needed to run the M1-542 logger live.

Original finding (for the record):
On the FIRST DM ever sent to the bot over real simplex-chat, `InboundRouter.onMessage`
(InboundRouter.java:504 → `SimpleXAdminClaim.claim(...)`) throws a bare
`java.lang.RuntimeException` **at ARC bean instantiation** (`SimpleXAdminClaim_Bean.create`),
BEFORE `claim()` runs. `SimpleXAdapter.onInbound` catches it and **silently drops the
message per D37** — the sender gets no reply. **Deterministic**: reproduced on every
inbound (18:42:09 and 18:49:12); ARC re-attempts create each inbound.
- **Live-only** (the D-live-9 thesis landing): `SimpleXAdminClaimTokenTest` is a
  `@QuarkusTest` that injects `InboundRouter`, so ARC creates the SAME bean the SAME
  way — and that suite is green on main. Green CI, live crash.
- **Falsified hypotheses** (don't re-chase): (a) config-expansion of
  `@ConfigProperty infochat.adapters.simplex.admin-token` — env `INFOCHAT_SIMPLEX_ADMIN_TOKEN`
  IS present in the container (len 8) and passed through in docker-compose.yml:189, and
  the token is 8 clean letters (no `$`/`{`/`}`/`:` metachars), so `${...}` expansion
  can't fail; (b) injected beans — `RegisteredContactSet` is `@Startup` and boot
  succeeded (so it was created OK at boot), `AuditLogWriter`/`DataSource` are fine (DB
  health UP). By elimination the create-time work that throws is not any of these →
  the true cause is masked (see F-live-2).
- **Repro**: `prod/runtime/simplex-clients/bin/simplex-chat -d prod/runtime/simplex-clients/admin/simplex_v1 -y -t 6 -e "@Admin-Reno <text>"` then
  `docker logs --since 90s infochat-infochat-provider-1 | grep 'inbound handler threw'`.

### F-live-2 (MEDIUM, diagnosability) — RESOLVED by M1-542 (merged 2026-07-02)
`SimpleXAdapter.stackWithoutMessage()` renders only the top throwable's class + stack
frames, NOT `getCause()` — so the real `Caused by:` of F-live-1 is absent from the log,
leaving a bare `RuntimeException` with no reason. Cause class names + `StackTraceElement`s
carry no user content (same D37 argument that already justifies logging the top frames),
so the cause chain can be appended safely. Fixing this REVEALS F-live-1's root cause and
every future inbound-handler bug. This is the natural first CI ticket.

**Recommended fix path (CI tickets, not host work):** (1) fix `stackWithoutMessage` to
append the content-free cause chain (reveals the root cause; standalone diagnosability
win). (2) With the cause visible, reproduce F-live-1 in a `@QuarkusTest` that matches the
live wiring (the current test is green, so the repro must capture whatever differs) and
fix it + regression test. Both are green-CI-verifiable, so they go through `/m1-tick`.

**STATUS (2026-07-02):** step (1) DONE — **M1-542 merged** (commit dfbb86ca). The D37 stack
logger now walks the full cause chain (class names + content-free frames, "Caused by:"
per level, depth-capped at 5 with a truncation marker, D37 suppression preserved). A
redteam pass caught unbounded depth (low DOS); remediated in-branch with the cap, re-audit
CLEAN. Step (2) = **M1-543** (F-live-1 fix, skeleton, blocked_by M1-542 now satisfied).
Per user direction ("fix both, run once"), NEXT is to try reproducing F-live-1 in a
`@QuarkusTest` offline; only if that fails do we deploy M1-542 to read the live cause. The
single live round-trip verification happens after BOTH fixes land. NOTE: the live app stack
(collector + provider) is currently PAUSED (stopped for the M1-542 verifies); restart via
`docker compose ... --profile prod up -d` before the live verification.

## Environment facts / constraints (host-side)

- **Signal capacity: 3 numbers** → 1 bot + 1 admin client (harness-automatable) +
  1 **user driven MANUALLY from the phone** (existing personal account, NOT
  harness-scriptable) (User, 2026-07-02, revised). ⇒ Signal scope = bot↔admin
  round-trip + ACI-admin bootstrap + §6 differences checklist, PLUS an optional
  human-in-the-loop bot↔user round-trip and a minimal 3-party group
  (bot+admin+user). The ScenarioRunner can't automate the phone user's turns, so
  the *automated* multi-party Signal lifecycle still stays InMemory/IT-only;
  SimpleX carries the automated multi-party lifecycle.
- **Constrained host — CONFIRMED specs (2026-07-02):** 4 vCPU / **15 GiB** / this
  is the `vps` profile, NOT laptop, and the LLM runtime is **llama.cpp** (two baked
  GGUF servers: chat `llamacpp:8080`, embeddings `llamacpp-embeddings:8080`), NOT
  ollama. The laptop/ollama live-smoke override below is therefore moot for this
  host — the vps stack is already what runs. Steady-state with all 5 containers
  resident leaves ~8 GiB free, so RAM is not the pressure; CPU + iowait under
  co-located builds is (see swap note). Retrieval assertions still depend on the
  embeddings server staying up.
- **Swap ENABLED (2026-07-02):** the host ran with **zero swap** — the exact
  condition behind the 06-28 provider-throttle incident (M1-512). Now provisioned
  per `07-deployment.md` §7.8.7: 8 GiB `/swapfile`, fstab-persisted,
  `vm.swappiness=10` (so llama weights stay RAM-resident — swap is a transient
  margin, NOT where the LLM lives). Operational rule that actually prevents a
  repeat: **do NOT run image builds / `mvn verify` on this VPS while the live stack
  is up** — that CPU+iowait co-location, not swap, is what got us throttled.
  (Superseded live-smoke note: README §4b "Constrained-host model note".)

## Decisions settled here (canonical records in [`README.md`](README.md) §8)

- **D-live-8 (2026-07-02):** scheduler firing is tested via **seeded timestamps +
  config-aimed digest windows, NOT a profile-gated live clock.** The digest is a
  poll-and-decide loop whose window comes from config (`morning/evening-slot-hour`,
  `window-width-minutes`); aim it at wall-clock-now to fire a live digest. Firing
  logic is already IT-tested via `tickAt(Instant)` + the injected clock. A movable
  prod clock is rejected — time gates security decisions and a prod-reachable
  override weakens the "prod Clock = hardcoded `systemUTC`" property. Full rationale
  in README §8/§9.

## Live findings (continued)

### F-live-3 (LOW, transient — NOT yet investigated) — collector startup race under RAW backlog
On 2026-07-02 22:08, restarting the collector after the M1-544 verify failed once:
`Failed to start quarkus`, the `OutboxRehydrator` startup observer threw, and many
`Stage1Worker: evaluation failed ... left RAW for re-enqueue` lines showed
`IllegalStateException: ArC container not initialized ... or a wrong class loader
was used` on `quarkus-virtual-thread-*` threads. The immediate retry booted clean
(healthy). Hypothesis: the rehydrator re-enqueues a large RAW backlog during the
startup event and Stage-1 virtual threads race deployment completion — and the
"wrong class loader" wording smells adjacent to the F-live-1 TCCL family. Repro
likely needs a large RAW backlog + restart. Un-ticketed; investigate before
relying on unattended collector restarts.

## Running log

### 2026-07-02 (4b-3 session)
- **4b-3 grounding + fixtures done; substrate split into M1-545 + M1-546.**
  Recovered and persisted the 15-scenario enumeration (README §Phase 1) from
  the audit transcript — it had never been written down. Joined LiveUser to
  the bot (one-shot `/show_address` with provider briefly stopped; `/c <link>`
  from the user client; bot auto-accepted — contact "Admin-Reno" in both
  client DBs). Identified and recorded the four substrate gaps for the live
  scenario set (grammar capture, single-connection raw-WS client rework,
  chatItemUpdated observation, structured-mention envelope) — details in
  §START HERE. M1-545 drafted (pending, clarity not yet run); session ended
  for a context clear before implementation.

### 2026-07-02 (later)
- **Admin token presented — LiveAdmin is bot admin** (D50 claim, DB-verified
  bootstrap shape). Welcome reply's "probation ~24h" text is the shared bundle
  string (reused per D50 by design); the admin row itself skips probation.
- **M1-544 MERGED (1a2be05e) — Phase 4b-2 DONE, host-validated** (591 ms /help
  round-trip via ScenarioRunner over real SimpleX; see START HERE item 2).
  Full cycle: clarity PASS → verify green (stack stopped for it, min-avail
  3.0 GiB, 5 DevServices leaks cleaned) → review r1 APPROVE → commit → merge.
  Discovered en route: the production WS client completes corrId futures only
  for send acks — fixture queries need a side socket (recorded in ticket).
- **F-live-3 observed** (transient collector startup failure on restart under
  RAW backlog; retry clean — see Live findings). Un-ticketed, low.
- **M1-543 MERGED (8ed35718) — F-live-1 FIXED and live-verified** via
  `/m1-tick run M1-543`. Full cycle: clarity FAIL → bounded self-refine
  (provisional acceptance made binding, concrete out_of_scope,
  security_relevant→true) → clarity WARN → diagnosis (live cause read via a
  compose-run probe of a main-rebuilt image: TCCL/no-config-for-classloader at
  `SimpleXAdminClaim` create) → repro test red → `AdapterRegistry` TCCL pin →
  repro green → full verify green (8:19 min, min-avail 3.2 GiB, swap peak
  2.2 GiB, no OOM — clean monitor report; 5 DevServices containers cleaned) →
  review r1 APPROVE → redteam CLEAN (1 out-of-model advisory: nothing
  structurally forces a FUTURE MessagingAdapter callback setter through the
  registry pin — awareness note for SPI evolution) → commit → squash-merge.
- **ONE live round-trip DONE (21:22):** stack resumed with the rebuilt image,
  admin DM → bot replied `Access requires an invitation.` — routed, not
  dropped. No `inbound handler threw` in the log. Stack left UP.
- Jar-overlay diagnosis dead-end recorded: classes in
  `transformed-bytecode.jar` shadow `lib/main`, so a bind-mounted rebuilt
  module jar silently doesn't load — rebuild the image instead.

### 2026-07-02
- M1-541 merged (last ticketed CI work; readiness-barrier hardening). `/m1-tick`
  board now shows `pending: 0` — expected, because the remaining live work
  (Phase 4b+) is host-dependent and deliberately un-ticketed.
- Reviewed the roadmap: confirmed the live run has **never executed** — substrate
  only (Phases 0–4a). SimpleX live drive, Signal delta, and real LLM/embedding
  evaluation are all still ahead.
- **Signal capacity confirmed: 2 numbers** (bot + admin). Recorded in README §9 +
  Phase 5. **REVISED same day → 3 numbers:** a 3rd (existing personal account) is
  usable as a **user, driven manually from the phone** — not harness-scriptable.
  Adds an optional human-in-the-loop bot↔user round-trip + minimal 3-party group;
  automated multi-party still SimpleX-only. Updated in README §9/Phase 5 + the
  constraint above.
- **Settled the scheduler-firing question → D-live-8** (no live clock; seeded
  timestamps + config-aimed windows). Recorded in README §8, §9 resolved.
- Created this handoff file for simpler session-to-session tracking.
- **Phase 4b move 1 DONE — stack brought up on the host.** `7-apps.sh` rebuilt both
  images, `6b` provisioned the SimpleX bot identity (profile + `/ad` address +
  auto-accept), and all 5 containers are up: postgres/collector/provider healthy,
  llamacpp + llamacpp-embeddings healthy. Provider `/q/health/ready` = **UP**
  (`messaging-adapters.simplex=true`, DB UP) — the M1-541 SimpleX-connect barrier is
  green. Collector→provider pipeline is live (real posts evaluated, cursor
  advancing). Provider listens on **127.0.0.1:8081 inside the container** (mgmt port,
  not host-published) — reach health via `docker exec … curl 127.0.0.1:8081/...`.
  Fresh bot contact link surfaced by 6b (copy from the run; not persisted).
- **Phase 4b move 2 DONE — client identities + handshake.** Extracted the exact baked
  simplex-chat (v6.5.4.1) from the running Provider image; it runs natively on the host
  (client↔bot traffic is via SMP relays, so no docker-network coupling needed). Created
  two client identities with the native binary — `LiveAdmin` and `LiveUser` — under
  `prod/runtime/simplex-clients/{admin,user}/simplex_v1_*.db` (infochat-owned, not
  root). Connected the admin client to the running bot via its `/ad` link; the bot
  auto-accepted (bot-side handshake frames `newChatItems-without-items` /
  `unknown-resp-type` seen in the Provider log at connect time — the exact
  contact-lifecycle events a fake can't produce, D-live-9). Channel proven: admin DM →
  bot **intake reached** the Provider.
- **M1-542 MERGED (commit dfbb86ca)** — F-live-2 fixed via `/m1-tick run M1-542`.
  Full cycle: clarity WARN → impl → verify → review r1 APPROVE → redteam FINDINGS
  (1 low DOS: unbounded cause-chain depth) → user chose refine → remediate (depth cap
  5 + truncation marker) → verify → review r2 APPROVE → re-audit CLEAN → commit →
  squash-merge. The D37 stack logger now walks the full cause chain (bounded, D37-safe),
  so the next live-only inbound bug shows its real cause. NEXT: M1-543 (F-live-1).
- The M1-542 r2-verify monitor subagent hung on a self-referential `pgrep` (its own
  cmdline matched `verify-serialized.sh`); killed it, recovered the data (build min-avail
  ~2.6 GB, swap absorbed it, no OOM). Lesson recorded → `[[clean-verify-monitoring]]`;
  next verify uses the clean approach for a complete report.
- **First real inbound surfaced F-live-1 + F-live-2** (see Live findings above): the
  inbound handler crashes at `SimpleXAdminClaim` bean creation and the DM is silently
  dropped; the cause is masked by the D37 stack logger dropping the cause chain. This is
  the live run paying off in its first five minutes — a green-CI, live-only crash.
  NEXT: user decides — pursue the fix now (CI ticket for F-live-2 then F-live-1) or keep
  driving more live DMs first to batch findings. The reply-round-trip (4b-2 done-def) is
  BLOCKED on F-live-1 (bot can't reply to a DM it drops).
- **Swap enabled + constrained-host specs confirmed** (see Environment facts above).
  Host was zero-swap (the 06-28 incident condition); now 8 GiB swapfile +
  swappiness=10, fstab-persisted. NEXT after move 1 = Phase 4b move 2 (provision the
  2 client identities: admin + user).
- **FOLLOW-UP (surface swap to operators) — verified gap, not yet done.** Swap is a
  *required* prod step per `docs/design/07-deployment.md` §7.8.7 (M1-512), but it is
  only in the design tier: it is **absent from `SETUP_GUIDE.md` / `ADMIN_GUIDE.md` /
  `README.md`** and **not checked by `prod/scripts/0-doctor.sh`** (which already
  checks RAM/disk/ports). That is why THIS host passed setup and still went live with
  zero swap — the exact 06-28 incident condition. Two concrete fixes, both cheap:
  (1) add a swap recommendation to `SETUP_GUIDE.md` §"What kind of computer you need"
  (it already covers disk + free memory — natural home). NOT `ADMIN_GUIDE.md`: swap
  is host provisioning, a setup-role concern; admin is a runtime/user-management role
  (User, 2026-07-02). (2) add a swap check to `0-doctor.sh` alongside the RAM/disk
  checks so a zero-swap host WARNs at preflight with the §7.8.7 remedy. Fix (2) is the
  one that would have actually caught this. Pure-doc for (1) → `process:` commit;
  (2) touches a wizard script → real change (its own commit, doctor is shell only).
- Drafted then **retracted** an M1-542 "SimpleXConversationBackend + fake-backed IT"
  ticket. Falsification (the fake would pass green on the two behaviours most likely
  to diverge — contact handshake + async-per-connection; fakes already hid
  M1-508/510/511) showed a fake-backed CI IT gives false assurance on exactly the
  fidelity risk the live run exists to catch. → **D-live-9**: the backend is
  host-validated, reuses the reality-reconciled codec, and any fake regression IT is
  a post-capture follow-up. M1-542 draft deleted; next ticket ID free again.
