# Live E2E — Handoff & Progress Log

> **Lightweight session-to-session tracker** for the live end-to-end run.
> This file answers "where are we, what's next, what changed" at a glance.
> The full plan (phase checklists, transport strategy, reset/data strategy,
> differences checklist, load-bearing code facts, canonical decisions log) lives
> in [`README.md`](README.md) — that stays the source of truth; this file links
> into it. `process:` commit prefix (docs-only, no ticket, no `mvn verify`).

Last updated: 2026-07-04 (ticket board DRAINED — M1-550…557 all merged & pushed through ce6e21d0; F-live-3 RESOLVED by M1-551; two co-location verify flakes → both tests hardened; control-plane still RESET — admin unclaimed) · Owner: ubuntu5 + Claude

---

## ▶ START HERE (fresh session — next step)

**ALL 7 live scenarios are GREEN (2026-07-03).** F-live-6 fixed by **M1-548
(merged d9093c05)**: `OpenAiCompatibleProvider` now reads per-task
`infochat.llm.<task>.max-tokens` (optional Integer, default 1024 — a cap,
not absent-means-uncapped) and sends it as `max_tokens`; host values set in
`prod/runtime/application.properties` (chat=600, summarizer=400, next to the
F-live-5 timeout overrides); both images rebuilt + stack restarted. **s12
GREEN in 159.6 s** — llama task evidence: 236-token prompt, decode stopped
at exactly 600 tokens (cap fired, `finish_reason=length` path), 13.5 s
prefill + 143 s decode < 240 s timeout. The sizing invariant
(`cap × ~0.24 s + prefill < timeout-ms`) held as designed. Next steps:

1. ~~Ticket F-live-4~~ **DONE — M1-549 merged (be75f18d, 2026-07-03):**
   `provider_state` is now EXCLUDED from the reset (18-table list; the
   sentinel rows survive and no manual re-seed is ever needed).
   Host-validated live: reset from the branch → provider restart with NO
   re-seed → readiness UP, 0 boot-loop signatures, reconcilers caught up
   from the preserved cursor (no epoch page-through). **Side effect: the
   live control-plane is WIPED again** (validation reset) — LiveAdmin is
   UNCLAIMED (D50 token re-armed), group/subscription fixtures gone.
   Before any live scenario work: re-claim via the admin token DM, then
   rebuild fixtures per the recipes below.
2. ~~Decide F-live-5 / F-live-3~~ **DONE — both decided 2026-07-03, three
   tickets drafted (pending, clarity not yet run):**
   - **M1-550** (F-live-5): wizard step 4 prompts for chat+summarizer
     `timeout-ms` + `max-tokens` pairs with backend/profile-sized
     recommended defaults (user decision: wizard-visible values, not baked
     `%vps` profile defaults; in-app defaults 30000/1024 stay).
   - **M1-551** (F-live-3): ROOT-CAUSED — `@PostConstruct` rehydration
     races Stage1Worker's async `@Incoming` subscription; SmallRye default
     bounded BUFFER (128) throws SRMSG00034 when the race is lost and the
     backlog exceeds it (explains retry-always-clean AND
     drained-backlog-never-fails). Fix: attempt-counted
     `Emitter.hasRequests()` readiness gate before the first emit.
   - **M1-552** (F-live-6 follow-up): `CHAT_SYSTEM_PROMPT` gains a brevity
     hint derived from `infochat.llm.chat.max-tokens` (~45% of the cap in
     words, rendered once at construction) so replies finish
     `finish_reason=stop` under the cap instead of truncating at it (s12
     decoded to EXACTLY 600 — no length instruction exists today).
   **DONE 2026-07-04: all three merged** — M1-550 (507c7bea, + M1-553
   wiring tests 31b93042), M1-551 (1a842496 — F-live-3 fix; the running
   collector image predates it, applies at next rebuild), M1-552
   (b469cf38). The board also drained M1-554/555 (test-infra,
   2026-07-03) and M1-556/557 (flake-band + harness hardening,
   2026-07-04). **Ticket board: 0 pending — NEXT ACTION is item 3
   (admin re-claim + fixture rebuild, then 4b-4).**
3. Then **4b-4** (s10 latency evidence exists; s12 adds bounded-chat
   evidence; remaining: embedding-retrieval assertion — note the m1-537
   seed source has exactly ONE READY+embedded post, which is the shape the
   assertion needs — readiness/metrics/LLM-down checks) and **Phase 5**
   (Signal delta). REMEMBER: any live drive first needs the post-M1-549
   fixture rebuild — admin re-claim via the D50 token DM, then the
   fixture recipes in this file (reset №/subscription lessons above).

**s12 re-run lessons (2026-07-03, for future chat-scenario runs):**
- The chat call is starved when the collector is chewing a RAW backlog
  (3–4 llama slots on eval prefills at 0.45–12 tok/s) — the first post-fix
  s12 attempt timed out on contention alone. Isolate: stop the collector,
  wait for `all slots are idle`, run, restart collector after.
- ChatAgent with NO subscribed sources produces a thin/garbage sub-80-char
  reply (76 chars of gibberish observed) — the scenario's `(?s).{80,}`
  match needs the s10 fixture: `source_subscription` row for the admin-DM
  scope → m1-537 seed source (owner-role INSERT; reset №2 had wiped it).
  With 1 READY embedded post of context the reply is substantive and s12
  passes.

Single-scenario drive command (surefire form used for the whole run —
runs ONLY the suite class, no module unit tests co-located with the stack):

```
mvn -pl infochat-provider test -Dtest='LiveSimpleXScenarioSuiteIT#<method>' -Dinfochat.live.simplex=true
```

- **Live corrections landed (test-scope, see running log):** D51 mention
  envelope = `mentions{<displayName>: <numeric local groupMemberId>}` (the
  best-guess `{memberId}` object shape is REJECTED by v6.5.4.1); s03/s15
  scenarios gained the `/invite create --open confirm` leg (the --open mint
  is confirm-gated per spec §Admin; the InMemory twin used --contact which
  isn't, so CI never saw it). `chatItemUpdated` body path and
  `/members <groupName>` form were confirmed AS GUESSED (s10 matched both
  finalized bodies live).
- **Fixture recipes that worked (for re-runs):** see running log 2026-07-03
  (live 4b-3 run) — reset→provider_state re-seed→restart→re-claim; group
  build via one-shot CLI (`/g`, `/a`, `/j`, `/ms`); vouch via `/vouch <cid>`;
  scope subscriptions via owner-role `source_subscription` INSERTs (the
  `/add-source` path SSRF-probes the identifier, unusable for the synthetic
  seed source); digest window aimed per D-live-8 with stagger computed from
  `groups.id` msb (`staggerOffset = |msb % width|`).
- **The 15-scenario enumeration is persisted** in `README.md` §Phase 1.
  Live set: S3 invite mint→consume, S4 un-invited DM rejected, S7 group
  pending→approve→auto-promote, S10 /summary + group digest, S11 /zcash,
  S12 chat mode, S15 full happy path.
- **Client fixtures:** LiveAdmin + LiveUser both CONNECTED to the bot
  (contact "Admin-Reno" in BOTH client DBs) AND to each other; group
  `live-group` exists in all three SimpleX client DBs (control-plane rows
  were cleared by the pre-s15 reset — the bot's app-level group state is
  gone, but transport-level membership persists in the data-dirs).


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
3. ~~4b-3 substrate~~ **DONE (2026-07-03): M1-545 (grammar capture) +
   M1-546 (live backend v2) both merged** — see §START HERE and the running
   log. What remains of 4b-3 is the live scenario RUN itself (host fixtures
   + drive), which is deliberately un-ticketed host work.

**Repro command (stack is UP):**
`prod/runtime/simplex-clients/bin/simplex-chat -d prod/runtime/simplex-clients/admin/simplex_v1 -y -t 6 -e "@Admin-Reno <text>"`
then `docker logs --since 90s infochat-infochat-provider-1 | grep 'inbound handler threw'` (expect NO hits post-M1-543).

**HOST STATE:**
- **Stack is UP — all 5 containers healthy** (2026-07-03, restarted for the
  M1-546 host validation and left up for the live 4b-3 run). **The restart
  command REQUIRES the env-file** (the bare `--profile prod up -d` form fails:
  collector exits 1 with SCRAM/no-password because the DB secrets live in
  `prod/runtime/secrets.env`, fed via `--env-file` by every wizard script):
  `docker compose --env-file prod/runtime/secrets.env --profile prod up -d --wait --wait-timeout 300`.
  F-live-3 recurred on this restart (2nd observation: OutboxRehydrator
  SRMSG00034 under RAW backlog; immediate retry booted clean — still
  un-ticketed, pattern holds).
  Health once up: `docker exec infochat-infochat-provider-1 sh -c 'curl -s 127.0.0.1:8081/q/health/ready'`.
- **Do NOT run image builds / `mvn verify` while the stack is up** (06-28
  throttle condition); stop collector+provider first.
- **Swap enabled** (8 GiB, swappiness=10) — the missing 06-28 safety margin is in place.
- **Clients provisioned:** `LiveAdmin` + `LiveUser` under `prod/runtime/simplex-clients/{admin,user}/` (native baked binary v6.5.4.1 at `.../bin/`). Admin is connected to the bot (contact "Admin-Reno"). Bot `/ad` link was transient (not saved); the clients are already joined, so re-query only if adding a new client.

**Clean verify-monitoring (2026-07-03 update — M1-546 lessons):** harness
*background tasks* running the verify were KILLED twice mid-build (whole
process tree died; no OOM, memory healthy — cause harness-side, unknown).
Working pattern: launch fully DETACHED —
`setsid nohup bash -c 'scripts/verify-serialized.sh > .scratch/<log> 2>&1; ec=$?; mkdir -p target && cp .scratch/<log> target/<log>; echo VERIFY_EXIT=$ec >> .scratch/<marker>' &`
— then watch the marker file with a Monitor until-loop. Remember the
`mkdir -p target` INSIDE the detached wrapper (the build's clean deletes root
`target/`; the M1-546 wrapper omitted it and the copy silently failed). The
sampler-kill step also re-hit the self-match trap in *pkill* form
(`pkill -f <pattern>` matched the killing shell's own cmdline → exit 144):
kill the sampler by PID only. See `[[clean-verify-monitoring]]` memory.
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

Substrate built and green (Phases 0–4a done); **all 7 live SimpleX scenarios
GREEN (2026-07-03)**; ticket board drained (2026-07-04: 581 done / 0 pending).
Remaining: 4b-4 assertions (needs the post-M1-549 fixture rebuild + admin
re-claim first) and Phase 5 (Signal delta).

## Where we are

| Phase | What | Status |
|---|---|---|
| 0 — Framing & decisions | targeting principle, transport strategy | ✅ DONE |
| 1 — Coverage audit | 15 scenarios; 7 transport-relevant, 0 logic gaps | ✅ DONE |
| 2 — Load-bearing assumptions | admin-token re-arm, SSRF strict, FK trap, caps | ✅ DONE |
| 3 — Reset & data harness | `prod/live-reset.sh`, `live-seed.sh`, `live-inject-adversarial.sh` | ✅ DONE (M1-536/537/538) |
| **4a — scenario runner substrate** | `Scenario`, `ConversationBackend` SPI, `ScenarioRunner`, InMemory backend, `ScenarioRunnerIT` | ✅ DONE (M1-539) |
| **4b — SimpleX live drive** | real simplex-chat drive + LLM latency + embedding retrieval | 🔶 IN PROGRESS — **all 7 scenarios GREEN 2026-07-03** (s12 green post-M1-548); remaining: 4b-4 assertions only |
| **5 — Signal delta** | round-trip + ACI bootstrap + §6 differences | ❌ NOT STARTED |
| 6 — (optional) `/testcase` skill | wrap the runner once 2–3 scenarios pass | ❌ not started |

**Concrete "done vs not" marker:** `SimpleXConversationBackend` exists and is
host-validated (M1-544 DM-only → M1-546 GROUP + finalized-union), with the 7
live `.scenario` resources on disk behind `-Dinfochat.live.simplex=true`. What
does NOT exist yet: any completed live run of those 7 scenarios (the 4b-3 run
is the next step), and **no `SignalConversationBackend`** (the Phase 5
boundary).

## Next actions (in order)

1. [x] **4b-1** — Provision 3 simplex-chat identities. **Bot: scripted** via
       `prod/scripts/6b-simplex-provision.sh` (run by `7-apps.sh`; profile + `/ad`
       address + `/auto_accept on`). **Admin + user clients: NEW tooling** — the
       harness needs its own client instances (separate data-dirs + ws-ports, baked
       binary from the Provider image), joined to the bot via its `/ad` link. Admin
       becomes admin by DMing the `admin-token` (not a pre-set address). Host-side.
2. [x] **4b-2** — Write `SimpleXConversationBackend` behind the existing
       `ConversationBackend` SPI and **validate it against real simplex-chat on the
       host** — drive the WS API (corrId command/response + async inbound), reusing
       the reality-reconciled `SimpleXMessageCodec` / `SimpleXWebSocketClient` (one
       wire-shape source of truth, no forked encoder). **Host-validated, NOT a
       fake-backed CI ticket** (D-live-9): fakes have hidden real SimpleX bugs here
       (M1-508/510/511), and the contact handshake + async-per-connection receive
       are exactly what a fake can't model. A FakeSimpleXProcess regression IT is a
       *later* ticket, seeded with frames captured on the first real run.
3. [ ] **4b-3** — Run the 7 transport-relevant scenarios (3,4,7,10,11,12,15) over
       real SimpleX via the runner. **Substrate DONE (M1-545 + M1-546); the
       run itself is next — see §START HERE.**
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

### F-live-4 (MEDIUM, tooling) — RESOLVED by M1-549 (merged be75f18d, 2026-07-03)
Fixed by EXCLUDING `provider_state` from the reset (the finding's first
option): it is the Provider's cursor over the PRESERVED data-plane, its
sentinel rows are Flyway-first-boot-only, and keeping it skips the epoch
page-through — the re-seed alternative would have duplicated the V9/V21
sentinel shape in a third place and broken the script's emptiness
assertion. `CONTROL_PLANE_TABLES` is now 18 tables. Host-validated live
(evidence in the ticket §Host validation). Original finding:

Found on the first 4b-3 reset (2026-07-03): `prod/live-reset.sh` TRUNCATEs
`provider_state` (it is in the 19-table control-plane list), but the
`new_post` / `quarantine_review` sentinel rows are inserted only by Flyway
(V9/V21 first-boot INSERTs), which never re-runs — so the next Provider boot
throws `IllegalStateException: provider_state row for channel='new_post' is
missing` (`NewPostReconciler.runCatchUp`) and the container boot-loops. The
reset script's own epilogue ("restart the Provider to re-seed") covers only
the admin bootstrap. **Host workaround used (owner role):** re-INSERT both
rows with the V9/V21 sentinel shape (`'epoch'::TIMESTAMPTZ, '', ''`,
`ON CONFLICT DO NOTHING`) after every reset, before the Provider restart.
The epoch cursor makes the reconciler page through the preserved data-plane
posts once (~3.7k rows, no subscribers → no side effects). **Fix pending
(ticket):** either exclude `provider_state` from the reset or have the reset
SQL re-seed the sentinels itself.

### F-live-5 (MEDIUM, config) — 30s per-task LLM timeout default unachievable on the vps host
Both LLM providers default `infochat.llm.<task>.timeout-ms` to 30000 when the
key is unset; only `security` sets it explicitly. On the 4-vCPU llama.cpp
host, prose tasks (chat, summarizer) need 60-300 s — the documented scenario
budgets — so every such call died in `HttpTimeoutException` before M1's first
live prose ever completed (s12 first failure; DigestWorker degraded-path
stacks). The failed calls also produce a **cancel storm**: the collector's
30s eval calls time out against the shared server and retry, keeping all 4
slots busy with doomed prefills. **Host fix applied and KEPT:**
`infochat.llm.chat.timeout-ms=240000` + `summarizer` twin in
`prod/runtime/application.properties`. **Decision pending (ticket):** raise
the `%vps` profile defaults (or have the wizard size them from the profile).

### F-live-6 (HIGH for chat, product) — RESOLVED by M1-548 (merged d9093c05, 2026-07-03)
Fixed: per-task `infochat.llm.<task>.max-tokens` (optional, default 1024,
`requirePositiveMaxTokens`-guarded) sent as `max_tokens` in the request
body, mirroring `timeout-ms` and `AnthropicProvider`. Host values chat=600
summarizer=400; s12 GREEN live (cap fired at exactly 600 tokens,
143 s decode + 13.5 s prefill < 240 s). Original finding:

Original: OpenAiCompatibleProvider sends no max_tokens; chat generation is unbounded
s12 (chat mode) fails even at 240000 ms with an idle collector: llama.cpp
logs show the chat task healthy at ~4.5 tok/s having generated 1033+ tokens
(prompt 487) when the client timeout cancels it — the model simply never
finishes, because the request body is only `{model, messages}`
(`OpenAiCompatibleProvider.doCall`) with no `max_tokens`, and `TaskConfig`
has no such key to configure. `AnthropicProvider` already sends
`cfg.maxTokens()` — the OpenAI-compatible path needs the same per-task
`max-tokens` config. NOT a reasoning-cutoff (gemma-4 instruct, `peg-gemma4`
format, no thinking channel; llama.cpp reports `truncated = 0` throughout).
**s12 stays red until this lands; everything s12 uniquely exercises except
the ChatAgent reply itself (finalized-edit observation) is already proven by
s10.**

### F-live-3 (LOW, transient) — RESOLVED by M1-551 (merged 1a842496, 2026-07-04) — collector startup race under RAW backlog
**RESOLVED:** root cause confirmed as drafted — `@PostConstruct` rehydration
races Stage1Worker's async `@Incoming` subscription; SmallRye's bounded
BUFFER (128) throws SRMSG00034 when the race is lost and the backlog exceeds
it. Fix: `OutboxRehydrator` waits on an attempt-counted **per-emit**
`Emitter.hasRequests()` readiness gate (per-emit extension per the M1-551
refine). NOTE: the running live-stack collector image predates the fix — it
applies from the next image rebuild. History below kept for reference.
**3rd observation 2026-07-03** (collector restart after ~15 min stopped for
the s12 isolation test): identical signature, immediate retry booted clean.
The pattern is now 3-for-3 → worth its ticket.
**2nd observation 2026-07-03** (restart for the M1-546 host validation):
identical signature — `OutboxRehydrator.onStartup` → `EvalQueueProducer.emit`
→ `SRMSG00034: Insufficient downstream requests to emit item`, exit 1;
immediate retry booted clean. The retry-clean pattern holds; still
un-ticketed.
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

### 2026-07-04 (board drained: M1-550…557 merged; F-live-3 fixed; co-location rule re-learned)

- **All pending tickets landed and pushed** (origin at ce6e21d0): M1-550
  (wizard LLM timing prompts, 507c7bea) + M1-553 (its wiring tests,
  31b93042); **M1-551 (F-live-3 fix, 1a842496)** — OutboxRehydrator
  per-emit `hasRequests()` readiness gate; M1-552 (chat brevity hint,
  b469cf38); M1-554 (Dev Services container leak: repo-tracked
  `quarkus.datasource.devservices.reuse=false`, f386acb2); M1-555
  (Nostr drain race, b318fd26); M1-556 (Stage1WatchdogIT band 10×→50×
  after 101/102 ms flakes, e1135504); M1-557 (SignalReconnectTest
  inbound push retry, ce6e21d0). Board: 581 done / 10 deferred /
  0 pending.
- **Co-location rule violated, then re-learned:** two M1-556 verify
  attempts ran WITH the live stack up (against §Environment facts'
  "do NOT run builds while the live stack is up") and flaked two
  *different* signal-fake tests (MultiAdapterProductionIT 2000 ms probe
  timeout; SignalReconnectTest `SocketException: Socket closed`) at
  ~29 min wall; quiet-host re-runs were green in 12:19–12:32. Claude's
  memory index now surfaces the pause-first rule at verify launch. Both
  flaky tests are hardened (M1-556 band; M1-557 push retry — analysis
  confirmed test-harness-only: the production reconnect path
  catches/classifies all transport IOExceptions, max-restart exhaustion
  degrades to FAILED adapter + admin notify, no whole-app crash path).
- **Live stack:** collector+provider paused for each verify, restarted
  after; all 5 containers up & healthy at session end. NOTE: running
  images predate today's merges — M1-551's startup fix and M1-552's
  brevity hint apply from the next image rebuild. Control plane
  unchanged today: still RESET, LiveAdmin UNCLAIMED.
- **NEXT (fresh session):** admin re-claim via the D50 token DM →
  fixture rebuild per the recipes above → 4b-4 assertions; then
  Phase 5 (Signal delta).

### 2026-07-03 night (F-live-5/3 decisions + M1-550/551/552 drafted)
- **F-live-5 decided (user):** wizard-collected with recommended defaults →
  **M1-550 drafted (264cdd4e)**. Recommendations keyed backend-then-profile
  (local vps/laptop: 240000/600 chat, 240000/400 summarizer — host-proven;
  pi provisional 480000; remote: 60000/1024 so outages aren't hidden).
  Timeout + max-tokens collected as a pair (sizing invariant).
- **F-live-3 investigated (user chose investigate-first) and ROOT-CAUSED**
  via code survey: `OutboxRehydrator` `@PostConstruct` emits the RAW
  backlog synchronously while `Stage1Worker`'s `@Incoming("eval-queue")`
  subscription wires up asynchronously; the emitter has no `@OnOverflow`
  and no `mp.messaging.*` config → SmallRye default bounded BUFFER (128)
  throws SRMSG00034 when the race is lost and backlog > ~128. Explains all
  observations: retry-always-clean (re-rolled race), drained-backlog-clean
  (can't overflow), ArC/classloader noise = teardown fallout. Severity:
  operational only, zero data risk (outbox at-least-once). →
  **M1-551 drafted (d358bf7c)**: attempt-counted `Emitter.hasRequests()`
  readiness gate (100 × 100 ms) before the first emit, loud ISE on
  exhaustion; unbounded-buffer and priority-reorder alternatives rejected
  in the ticket.
- **M1-552 drafted (b2929243)** (F-live-6 UX follow-up, user-approved
  design): chat brevity hint derived from `max-tokens` (placeholder
  template rendered once; integer-only, injection surface untouched;
  600 → "under about 270 words"). Derive-don't-add-a-knob: the M1-550
  wizard value automatically sizes the prompt.
- Stack untouched this session (still UP; control-plane still wiped,
  admin still unclaimed — fixture rebuild remains a pre-req for any live
  drive).

### 2026-07-03 late evening (M1-549 — F-live-4 fixed; control-plane reset)
- **M1-549 MERGED (be75f18d)** — live-reset preserves provider_state
  (F-live-4) via `/m1-tick run M1-549`. Full cycle: draft (640939ef) →
  clarity PASS (0/0) → implement (3 impl files exactly at files_budget:
  provider_state out of the SQL TRUNCATE + why-preserved comment, out of
  `CONTROL_PLANE_TABLES` (19→18), USER_TEST_PLAN.md cleared→preserved
  move) → diff FULLY INERT (no Java/config/DB), mvn verify N/A per the
  M1-379/M1-272 inert-diff gate (round logs carry the note) → review r1
  REWORK (1 item: the host validation — by-design post-verify, M1-546
  precedent) → **host validation GREEN** (see below) → review r2 APPROVE
  (all checks PASS; must-shrink convergent — growth was the mandated
  ticket-body evidence) → commit e788d7f1 → squash-merge be75f18d.
  Board: done=573, pending=0.
- **Host validation detail:** pre-reset both `provider_state` rows
  present; `live-reset.sh` (branch) OK — 18 tables empty, 3764 posts
  unchanged; rows SURVIVED the reset; provider restarted with NO manual
  re-seed → single clean boot, readiness UP, 0
  `IllegalStateException`/missing-row log hits;
  `NewPostReconciler: caught up 0 posts in 1 page(s)` from the PRESERVED
  cursor (no 3.7k epoch page-through — the designed benefit) and cursor
  advancing live afterwards.
- **Live-state consequence: control-plane is WIPED** (the validation
  reset). LiveAdmin unclaimed (D50 token re-armed since
  `INFOCHAT_SIMPLEX_ADMIN_TOKEN` is still set), groups/subscriptions
  gone; transport-level client↔bot connections persist in the SimpleX
  data-dirs. Re-claim + fixture recipes: §START HERE + the 4b-3 log.
- Stack left UP and healthy (all 5 containers; provider restarted as part
  of the validation, collector untouched — no F-live-3 window).

### 2026-07-03 evening (M1-548 + s12 GREEN — 7/7 COMPLETE)
- **M1-548 MERGED (d9093c05)** — per-task max-tokens for
  OpenAiCompatibleProvider (F-live-6) via `/m1-tick run M1-548`. Full
  cycle: draft (d66b2c0e) → clarity PASS (0/0) → implement (3 impl files,
  exactly at files_budget: configFor `orElse(1024)` +
  `requirePositiveMaxTokens`, `root.put("max_tokens", ...)` in doCall,
  TaskConfig gains maxTokens, 3 new wire-pinning tests, design-note doc) →
  full verify green r1 (11:52, detached setsid + marker, collector+provider
  stopped, 5 DevServices leaks cleaned) → review r1 APPROVE (all checks
  PASS, 0 rework) → verify-reuse user-approved → commit 661cfdc1 →
  squash-merge d9093c05. Board: done=572, pending=0. Known stale-comment
  follow-up recorded in the commit body (AnthropicProviderTest's
  "Anthropic-only" guard comment).
- **Host deploy:** max-tokens keys added to
  `prod/runtime/application.properties` (chat=600, summarizer=400 with the
  sizing-invariant comment); both images rebuilt (compose build, apps
  stopped); stack up healthy with `--env-file`. NO F-live-3 on either
  collector restart (backlog drained — supports the RAW-backlog-race
  hypothesis).
- **s12 attempt 1 red (contention, not product):** collector chewing the
  RAW backlog accumulated during the build window kept 3–4 llama slots on
  eval prefills (0.45–12 tok/s); chat call starved past 240 s.
- **s12 attempt 2 red (fixture, not product):** collector stopped, server
  idle — the bot REPLIED in 14 s (22 tokens, truncated=0, M1-548 working)
  but with 76 chars of gibberish: ChatAgent had zero subscribed sources
  (reset №2 wiped the s10 `source_subscription` rows), so the scenario's
  `(?s).{80,}` never matched. Re-inserted the admin-DM → m1-537-seed-source
  subscription (owner role, user-approved).
- **s12 attempt 3 GREEN (159.6 s):** llama task 63109 — prompt 236 tokens,
  decode stopped at EXACTLY the 600-token cap (finish_reason=length path),
  13.5 s prefill + 143 s decode. The cap converts the F-live-6 total loss
  into a delivered reply, precisely the design intent. **All 7
  transport-relevant scenarios now GREEN over real relays.**
- Collector restarted, all 5 containers healthy, stack left UP.

### 2026-07-03 (THE LIVE 4b-3 SCENARIO RUN — 6/7 GREEN)
- **First-ever live scenario drive over real SimpleX relays. Result: s04,
  s03, s07, s10, s11, s15 GREEN; s12 blocked on F-live-6.** Real-LLM latency
  evidence captured (s10: /summary matched in 217 s, group digest in 293 s —
  the 4b-4 latency item). Sequence and discoveries, in order:
- **Reset №1** (s04/s03 fixture) surfaced **F-live-4**: provider boot-looped
  on the truncated `provider_state`; re-seeded both sentinel rows manually
  (SQL shape in the finding), restart clean, LiveAdmin re-claimed via the
  D50 token (DB-verified `is_admin=t`, vouched).
- **s04 GREEN** (1270 ms) on the first attempt.
- **s03 first attempt red — scenario bug, not product:** `/invite create
  --open` is confirm-gated (spec §Admin; only `--contact` mints immediately —
  which is what the InMemory twin uses, so CI never exercised the gate). The
  bot correctly replied with the confirm prompt; the scenario expected
  `Invite code:` directly. **Fixed s03 + s15** (added the
  `/invite create --open confirm` leg). **s03 GREEN** (3 steps, ~1 s each) —
  M1-545 capture/substitution proven live across contacts.
- **s07 fixture built via one-shot CLI:** `/vouch 7`; LiveAdmin↔LiveUser
  connected (`/connect` + `/c <link>`, two online passes to complete the
  async handshake); `/g live-group`; `/a live-group Admin-Reno` → **bot
  auto-joined** (M1-515 registered-inviter gate, `auto_joined_group` row);
  `/a live-group LiveUser` + `/j live-group` (join needs a prior online pass
  to RECEIVE the invite event — a one-shot `/j` straight away sees
  "no group").
- **s07 first attempt red — D51 mention envelope live-corrected (the
  declared discovery item landing):** the composed `mentions{name:
  {memberId}}` object shape gets `commandError` ("bad chat command: Failed
  reading: empty"). Probed raw `/_send` forms on the real CLI: the accepted
  value is the **sender-local NUMERIC groupMemberId** (`mentions{"Admin-
  Reno": 3}`); a WS probe of `/members live-group` confirmed member objects
  carry `groupMemberId` alongside `memberId`. Fixed `LiveSimpleXClient`
  (GroupMember record + collect + encode) and re-pinned
  `LiveSimpleXHarnessFrameTest` (5/5 green hermetically). **s07 GREEN**
  (all 5 steps ~1 s: pending reply → /list-groups capture → approve → group
  reply → auto-promote observable via /group-timezone).
- **s12 first attempt red → F-live-5:** ChatAgent `HttpTimeoutException` at
  the 30 s default. Raised chat+summarizer to 240 s in
  `prod/runtime/application.properties` (kept — see finding).
- **s10 orchestration (D-live-8 in practice):** digest window aimed at
  `evening-slot-hour=14`, `width=20` (stagger pre-computed from `groups.id`
  msb → fire ≈13:51); `/digest off` first (approval had defaulted it ON —
  an early fire would have been lost before step 2's watermark);
  re-seeded with `--offset-minutes 0` at 13:50 because the digest collects
  posts published SINCE windowStart (no prior boundary on a fresh control
  plane) — the default 30-min-old seed rows would have yielded the no-posts
  body. **s10 first attempt red — fixture gap, not product:** `/summary`
  replied "No posts to summarize yet" instantly; tags only NARROW a
  source-based set, and the seed subscribes only its own synthetic scope →
  inserted `source_subscription` rows for the admin-DM and group scopes
  (owner role; `/add-source` SSRF-probes the identifier so it can't
  subscribe to the synthetic source). **s10 GREEN** (217 s / 293 s, both
  bodies via the chatItemUpdated finalized-union path — discovery item
  confirmed as guessed).
- **s12 re-attempts red → F-live-6** (unbounded generation; evidence in the
  finding). Verified NOT a reasoning cutoff (user hypothesis checked:
  `truncated=0`, no thinking channel, task cancelled mid-healthy-decode).
- **Reset №2** (F-live-4 workaround again) → re-claim → **s15 GREEN**
  (all 6 steps sub-second; confirm-gated mint → capture → register →
  /help → /zcash → empty-window /summary).
- **s11 GREEN** (322 ms, source + bare URL attribution).
- **F-live-3 3rd observation** on the collector restart after the s12
  isolation stop; retry clean.
- **Stack left UP and healthy** (collector + provider + postgres + 2×llama).
  Digest-window aim REVERTED; LLM timeout override KEPT (F-live-5).
- **M1-547 MERGED (f073e37d)** — the 4 test-scope live corrections went
  through the full `/m1-tick` cycle per user choice: clarity WARN (2
  advisory: security_relevant justification, spec_ref anchor tightening) →
  implementation restored from stash onto the branch → full verify green
  (r1, detached setsid + marker pattern, stack apps stopped, 5 DevServices
  leaks cleaned) → review r1 APPROVE (all checks PASS, 0 rework) →
  verify-reuse user-approved (log postdates all candidates) → commit
  e00056e9 → squash-merge f073e37d. Board: done=571, pending=0.
  F-live-3 hit TWICE more on collector restarts during this cycle (3rd and
  4th observations; SRMSG00034 each time, immediate retry clean each time).

### 2026-07-03 (M1-546 run + merge — 4b-3 substrate COMPLETE)
- **M1-546 MERGED (c9fcdc20) — SimpleX live backend v2** via
  `/m1-tick run M1-546`. Full cycle: clarity WARN (files_budget 12 tight vs
  13-14 plausible — final diff landed at exactly 12 implementation files) →
  plan-writer outline PASS (6 risks) → implement (LiveSimpleXClient single
  raw-WS rework, SimpleXConversationBackend GROUP + finalized-union,
  LiveSimpleXHarnessFrameTest, LiveScenarioParseTest, gated
  LiveSimpleXScenarioSuiteIT, 7 live .scenario resources) → verify r1 green
  (11:02 min; clean monitor report: min-avail 1850 MiB, swap Δ ≈ +325 MiB
  over baseline, no OOM; 5 DevServices leaks cleaned) → review r1 REWORK
  (1 item: the host validation — by-design post-verify) → stack restarted
  (--env-file lesson + F-live-3 recurrence, both recorded in §HOST STATE) →
  **host validation GREEN: LiveSimpleXRoundTripIT over the v2
  single-connection client, matched in 733 ms on real relays** → evidence in
  ticket body → review r2 APPROVE (verify reuse user-approved,
  blob-hash-verified unchanged testable tree) → commit 3ff4c5f8 →
  squash-merge c9fcdc20. Board: done=570, pending=0.
- **Verify-runner lesson:** harness background tasks running the verify were
  killed twice mid-build; detached `setsid` + marker-file Monitor worked
  (details in §Clean verify-monitoring). `pkill -f` self-match trap re-hit
  (kill the sampler by PID only).
- **Stack left UP** for the live 4b-3 run — next session starts at
  §START HERE (host fixtures, then drive the 7 scenarios).

### 2026-07-03 (M1-545 run + M1-546 draft)
- **M1-545 MERGED (c8a6ff29) — grammar capture/substitution extension** via
  `/m1-tick run M1-545`. Full cycle: clarity FAIL (one prose blocker — a
  mistyped spec_refs anchor "§Test tiers" → corrected to "§Test layers") →
  bounded self-refine (4b7db7f4) → clarity WARN → implement (4 test-scope
  files: Scenario.java capture directive + Step.captures, ScenarioRunner
  ${name} substitution into text+addresses with loud unbound/no-match
  failures, ScenarioCaptureIT 5 tests, invite-mint-consume.scenario) →
  verify green (9:10 min; clean monitor report: min-avail 1199 MiB, swap
  Δ ≈ 2.1 GiB over a 3.7 GiB pre-existing baseline, no OOM; 5 DevServices
  leaks cleaned) → review r1 APPROVE (all checks PASS, 0 rework) → commit →
  squash-merge. Stack's collector+provider stopped for the verify (left
  stopped — see HOST STATE).
- **M1-546 DRAFTED (ce3000c4)** — the full live-backend-v2 design moved from
  this handoff into the ticket (single raw-WS codec-fed client, item-edit
  observation, GROUP binding, D51 mention envelope, 7 gated live scenarios;
  complexity: high, files_budget 12, round_cap 3). Clarity not yet run.
  Next: `/m1-tick run M1-546`.

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
