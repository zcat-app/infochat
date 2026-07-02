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

**Next step = Phase 4b: SimpleX live drive on the host.** This is **host-validated
work (D-live-9)** — it needs real simplex-chat identities + the app running, is NOT
CI-testable, and has **no `/m1-tick` ticket to run**. Drive it directly; do not try
to force it into a `mvn verify` ticket (that mistake was made and retracted — see
the running log).

**Load first (in order):** this file → `README.md` (full plan: §1 targeting
principle, §Phase 4b checklist, §6 SimpleX↔Signal differences, §8 decisions) → the
`simplex-live-frame-capture` memory (how to capture real WS frames; **async events
go ONLY to the controlling connection**) → `.scratch/simplex-spike-findings.md`
(real simplex-chat command quirks: exit-0-on-error, `/ad`, `/auto_accept`,
idempotency) → the reuse targets: `SimpleXWebSocketClient` + `SimpleXMessageCodec`
(infochat-messaging-adapter) and `ConversationBackend` / `ScenarioRunner` /
`InMemoryConversationBackend` (infochat-provider/src/test/.../live/).

**First three moves:**
1. **Bring the stack up on the host** [user/host]: `prod/setup.sh` (numbered
   scripts 0→7). `6b-simplex-provision.sh` (run by `7-apps.sh`) provisions the
   **bot** identity — profile + contact address (`/ad`) + `/auto_accept on`. Confirm
   `/q/health/ready` is green and the bot surfaced a contact link. NOTE: the
   simplex-chat binary lives **only inside the Provider image**, not on the host.
2. **Provision the 2 client identities** (admin + user) [host — NEW tooling]:
   `6b` covers the bot ONLY. The harness needs its own client simplex-chat
   instances on **separate data-dirs + ws-ports**, using the baked binary
   (extract from the image, or run a throwaway container). Establish the client→bot
   channel via the bot's `/ad` contact link (bot auto-accepts). This handshake is
   the first thing a fake could never prove (D-live-9).
3. **Start `SimpleXConversationBackend`** [Claude + host]: reuse
   `SimpleXWebSocketClient` for transport+decode and `SimpleXMessageCodec` for the
   `/_send` shape (D-live-9: ONE wire-shape source of truth — do not fork a second
   encoder). Drive one DM to the bot, capture the real reply frames (frame-capture
   memory), and get a DM round-trip. That round-trip — over real simplex-chat — is
   the acceptance for 4b-2; there is no green-CI substitute.

Signal (2 numbers, bot + admin) is Phase 5, only after SimpleX proves out.

**Definition of done for the next step (4b-2):** a `SimpleXConversationBackend`
that, against a real client simplex-chat, sends a DM to the live bot and observes
the bot's real reply (round-trip + captured latency), reusing the adapter's codec.
Capture the real frame shapes seen — they seed the *later* FakeSimpleXProcess
regression IT (a real ticket then, not now).

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
| **4b — SimpleX live drive** | real simplex-chat drive + LLM latency + embedding retrieval | ❌ NOT STARTED |
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

## Environment facts / constraints (host-side)

- **Signal capacity: 2 numbers** → 1 bot + 1 admin client, **no spare user client**
  (User, 2026-07-02). ⇒ Signal scope = bot↔admin round-trip + ACI-admin bootstrap +
  §6 differences checklist. Multi-party Signal group scenarios stay InMemory/IT-only;
  SimpleX carries the full multi-party lifecycle.
- **Constrained host (assumed 16 GB laptop):** default laptop/ollama profile is
  tight (llama3.1:8b ×5 tasks + 3b judge + nomic-embed-text + Postgres/Ollama + 2
  JVMs). Live-smoke override: drop the 8b tasks to `llama3.2:3b` (already pulled for
  the judge → zero extra download) but **keep `nomic-embed-text`** (retrieval
  assertions depend on it). Keep smoke assertions behavioural, not output-quality.
  Full note: README §4b "Constrained-host model note". (Confirm actual host specs.)

## Decisions settled here (canonical records in [`README.md`](README.md) §8)

- **D-live-8 (2026-07-02):** scheduler firing is tested via **seeded timestamps +
  config-aimed digest windows, NOT a profile-gated live clock.** The digest is a
  poll-and-decide loop whose window comes from config (`morning/evening-slot-hour`,
  `window-width-minutes`); aim it at wall-clock-now to fire a live digest. Firing
  logic is already IT-tested via `tickAt(Instant)` + the injected clock. A movable
  prod clock is rejected — time gates security decisions and a prod-reachable
  override weakens the "prod Clock = hardcoded `systemUTC`" property. Full rationale
  in README §8/§9.

## Running log

### 2026-07-02
- M1-541 merged (last ticketed CI work; readiness-barrier hardening). `/m1-tick`
  board now shows `pending: 0` — expected, because the remaining live work
  (Phase 4b+) is host-dependent and deliberately un-ticketed.
- Reviewed the roadmap: confirmed the live run has **never executed** — substrate
  only (Phases 0–4a). SimpleX live drive, Signal delta, and real LLM/embedding
  evaluation are all still ahead.
- **Signal capacity confirmed: 2 numbers** (bot + admin). Recorded in README §9 +
  Phase 5.
- **Settled the scheduler-firing question → D-live-8** (no live clock; seeded
  timestamps + config-aimed windows). Recorded in README §8, §9 resolved.
- Created this handoff file for simpler session-to-session tracking.
- Drafted then **retracted** an M1-542 "SimpleXConversationBackend + fake-backed IT"
  ticket. Falsification (the fake would pass green on the two behaviours most likely
  to diverge — contact handshake + async-per-connection; fakes already hid
  M1-508/510/511) showed a fake-backed CI IT gives false assurance on exactly the
  fidelity risk the live run exists to catch. → **D-live-9**: the backend is
  host-validated, reuses the reality-reconciled codec, and any fake regression IT is
  a post-capture follow-up. M1-542 draft deleted; next ticket ID free again.
