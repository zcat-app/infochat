# Live end-to-end verification plan

> **Status: living tracker, not spec.** Cross-session working plan for verifying
> infochat over *real* messaging transports (SimpleX + Signal) after the
> in-memory "dry run" work. Update the checkboxes and the Decisions log as we go.
> `process:` commit prefix (no ticket) for this file; the harness code/tests it
> describes are M1 tickets.

Last updated: 2026-07-01 · Owner: ubuntu5 + Claude

---

## 1. Why a live run at all (the targeting principle)

The in-memory IT suite is mature (281 provider + 140 collector + 79 adapter +
49 core test classes; `SummaryIT` is the reference scripted-E2E). It already
covers **business logic above the adapter SPI** — invite consume, probation,
vouch, ban intake, permission matrix, digests, `/forget`, scope isolation — and
`docs/spec/verification.md` *requires* named tests for those.

A live run does **not** re-prove that logic. It proves the things the in-memory
layer *structurally cannot*, because the in-memory adapter encodes the
**assumed** transport contract, not the **real** one:

1. **Adapter fidelity to real transport semantics.** This is where SimpleX
   reality diverged from the code's assumptions and forced rework *even after
   reading upstream docs*. The risk is **per-transport and does not transfer** —
   SimpleX lessons say nothing about whether `SignalAdapter` correctly models
   signal-cli. **Each transport needs its own real-transport verification.**
2. **Real LLM latency / throughput** (fake-LLM tests can't measure it).
3. **Real embedding / RAG retrieval quality** (fake embedder makes this vacuous).
4. **Operator setup / provisioning UX** (the wizard, bootstrap, data-dirs).
5. **Operational safety** — readiness/liveness, per-adapter up/down, LLM-down =
   degraded not dead, log redaction of contact-ids, single-instance advisory
   lock, Collector-migrates-first ordering.

Corollary: security *correctness* (SSRF, injection quarantine, output
sanitizer, DB-role separation) stays in ITs + `/redteam` — it's often
transport-**invisible** (you can't observe "no DB query happened after the ban
check" from a Signal client).

## 2. Transport strategy

- **SimpleX = full workflow.** Identities are cheap, disposable, and scriptable
  over the simplex-chat WS API. Iterate the whole lifecycle here.
- **Signal = smaller delta task.** Identities are scarce and **manually
  registered (phone + captcha), not scriptable** (`docs/spec/deployment.md`).
  Scope Signal to: (a) prove comms round-trip, (b) prove the ACI-admin bootstrap,
  (c) exercise the behaviours that differ from SimpleX (§6 checklist).

## 3. Data & reset strategy

Two independent state layers — reset one, preserve the other:

- **Data-plane (PRESERVE):** `source`, `tag`, `post` (+ embeddings),
  `post_reference`, `post_entity`, `price_snapshot`, `bootstrap_meta`.
  Expensive, externally-sourced. Do **not** re-fetch every run (politeness +
  determinism + speed).
- **Control-plane (RESET):** `users`, `groups`, `group_membership`,
  `invite_code`, `invite_code_attempt`, `source_subscription`, `chat_message`,
  `chat_session`, `chat_memory`, `summary_anchor`, `summary_cache`,
  `saved_post`, and (specially) `audit_log`.

**Adapter data-dirs (PRESERVE, always):** the bot + client simplex-chat /
signal-cli identity dirs. Wiping them re-issues the bot address (breaks every
client contact) and, for Signal, is effectively unrecoverable. A control-plane
DB reset already re-arms the app-level "from scratch" state without touching
transport identity.

**Reset mechanism — the cross-plane FK trap (verified 2026-07-01).** Only two
DATA-plane tables reference the control-plane: `source.{added_by,deleted_by} →
users` (ON DELETE SET NULL) and `tag.created_by → users` (RESTRICT). This makes
the naive `TRUNCATE users CASCADE` **WRONG**: `TRUNCATE … CASCADE` cascades on
the *FK-constraint graph*, not row values, so it truncates `source` and `tag`
(and therefore `post` via `post.source_id`) — nuking the data-plane. Nulling the
columns first does **not** help (TRUNCATE ignores row values). Two correct
options:
- **(A) DELETE-based, in-place:** disable `trg_users_last_admin_{delete,update}`;
  `UPDATE tag SET created_by=NULL` (RESTRICT child); TRUNCATE the control-plane
  tables that have no data-plane dependents; `DELETE FROM users`
  (`source.added_by/deleted_by` SET NULL auto-resolve; `users.banned_by` is NO
  ACTION so a single all-rows DELETE is fine); re-enable triggers. Owner role.
- **(B) drop-recreate + data-plane snapshot (recommended for the harness):**
  one-time, after the "now" fetch, `pg_dump --data-only` the data-plane tables
  **with the cross-plane FK columns already NULL** (else restore FK-violates
  against the empty fresh `users`). Each reset = drop DB → Collector runs Flyway
  → restore the snapshot → bootstrap beans re-seed. Sidesteps triggers/roles/FK
  ordering entirely; most robust.

audit_log's append-only guard is row-level (`FOR EACH ROW`), so it does **not**
fire on TRUNCATE — no special handling needed there. Only the owner role (ran
Flyway) can TRUNCATE control-plane tables; `infochat_provider/collector` cannot.
Full FK graph + per-table plane classification: see the DB-reset findings.

**now/future corpus (dissolves the SSRF fixture problem):**
- **"now" corpus:** fetch real feeds **once** (real endpoints, real SSRF guard —
  proves the fetch path), snapshot the data-plane. Never re-fetch.
- **"future"/synthetic corpus:** seed posts directly. For deterministic
  summary/embedding assertions, seed `READY` posts with controlled
  `published_at`. For **malicious-detection** assertions, seed at the **RAW /
  pre-eval stage** so the real Stage-1/Stage-2 + real LLM pipeline runs on them
  (inserting as `READY` bypasses the very thing under test).
- Time-relative behaviour (probation/invite expiry, TTL, digest windows) is
  driven by **seeded row timestamps**, not a movable clock — prod `Clock` is
  hardcoded `Clock.systemUTC()`. A profile-gated configurable live clock is an
  *optional* add-on (only if we need to test scheduler *firing* deterministically).

## 4. Setup matrix (small, not combinatorial)

Testing all {profile × adapters × files} combos is infeasible and mostly
redundant (profiles are tuning values; AdapterRegistry's 7-gate validation is
unit-tested). Cover, all on `laptop`:

- [ ] `simplex` only
- [ ] `signal` only
- [ ] `simplex,signal` together (exercises cross-adapter identity isolation)
- [ ] 1–2 negative setup cases (empty admin union; unreadable data-dir)

## 5. Phases (trackable)

### Phase 0 — Framing & decisions — DONE
- [x] Agree targeting principle (§1) and transport strategy (§2)
- [x] Draft data/reset strategy (§3) and differences checklist (§6)
- [x] Record load-bearing code facts (§7)

### Phase 1 — Coverage audit — DONE (2026-07-01)
- [x] Enumerate the 15 workflow scenarios.
- [x] Map each to existing IT coverage → **all 15 covered, all DONE, zero gaps.**
      `GoldenPathJourneyIT` runs 13 of 15 end-to-end via InMemoryAdapter;
      probation/invite/expiry have dedicated Clock-pinned ITs.
- [x] Classify each. **8 transport-invisible** (1,2,5,6,8,9,13,14 — stay IT-only,
      NOT in the live run) / **7 transport-relevant** (3,4,7,10,11,12,15).
- [x] Fill logic gaps as ITs → **none needed** (no gaps).
- **⇒ The live-run scenario set = the 7 transport-relevant ones, and only
  because they're currently proven against *InMemory*, not real transports.**

### Phase 2 — Verify load-bearing assumptions — DONE (2026-07-01)
- [x] SimpleX admin-token: single-use gated purely by `WHERE NOT EXISTS(…
      is_admin=TRUE)` for the simplex adapter; no schema marker, no in-memory
      latch (`SimpleXAdminClaim.java`). **Control-plane reset RE-ARMS it.**
      Harness action: **keep the token configured** each run (deliberately skip
      the prod "unset after first claim" hygiene) so every reset re-arms.
- [x] `audit_log` reset: row-level append-only trigger → does NOT fire on
      TRUNCATE. Reset needs the **owner role**; provider/collector can't TRUNCATE.
- [x] Data/control split + FK graph mapped; naive `TRUNCATE users CASCADE` is a
      trap (see §3) → use option (A) or (B).
- [x] SSRF guard is **strict by construction, not relaxable** (loopback-permit
      ctor is package-private/test-only, `IpBlocklist.java`). Locks in fetch-once.
- [x] Capability flags captured (see §6 table) — SimpleX vs Signal differ on
      membership-events, typing, code-formatting, and edit-failure fallback.

### Phase 3 — Reset & data harness — DONE (2026-07-01)
Reconciled against `docs/testing/USER_TEST_PLAN.md` (7 delivered deliverables):
the READY corpus (M1-413 `seed-ready-posts.sql`), dev harness (M1-414), golden
path (M1-415), ingest smoke (M1-416), observability + adversarial-input kit
already exist. `setup.sh --reset` is a FULL teardown; a preserve-data reset did
not exist — Phase 3 closed that gap. With in-place reset (option A) the fetched
"now" corpus persists in the live DB, so no snapshot file is needed. All three
tools are prod-side, owner-role, idempotent, and documented in
`docs/testing/USER_TEST_PLAN.md`.
- [x] **M1-536** (done): live workflow reset — clears control-plane, preserves
      data-plane, FK-safe (no `TRUNCATE users CASCADE`), owner role, idempotent.
      → `prod/live-reset.sh` + `prod/sql/reset-control-plane.sql`.
- [x] **M1-537** (done): live synthetic-corpus seed loader — idempotent,
      timestamp-parameterized, reuses M1-413 row shapes.
      → `prod/live-seed.sh` + `prod/sql/seed-synthetic-corpus.sql`.
- [x] **M1-538** (done): RAW-stage adversarial injection — inserts an
      adversarial-input-kit §A1 post at `status='RAW'`; the real
      Stage1Worker reaper (or a collector restart via OutboxRehydrator)
      re-enqueues it and the real Stage-1/2 + real LLM quarantines it; a polled
      check proves the non-READY terminal state + redaction. Idempotent,
      self-contained source. → `prod/live-inject-adversarial.sh` +
      `prod/sql/inject-adversarial-raw.sql`.

### Phase 4 — SimpleX live-smoke driver (full workflow)

Split into **4a** (the CI-testable substrate — ticketed) and **4b** (the
host-dependent live drive — needs real transports + real LLM, cannot be
@QuarkusTest-covered).

**Phase 4a — scenario runner substrate — TICKET DRAFTED (M1-539, 2026-07-01).**
- [ ] **M1-539** (draft): backend-agnostic scenario format + runner core +
      InMemory backend binding, proven by an IT that runs a golden-path smoke
      scenario through `InMemoryAdapter` and captures per-step latency. Reuses the
      InMemoryAdapter conversation shape so the SAME scenario runs on both
      backends (caveat: in-memory is sync/`finalizedBodies()`, live is
      async/observed client-side → the `ConversationBackend` SPI abstracts it with
      a poll-until-match-or-timeout wait). D-live-4: build the runner first.

**Phase 4b — SimpleX live drive on a host (not ticketed; needs the host).**
- [ ] Provision 3 simplex-chat identities: **bot** + **admin client** + **user
      client** (admin becomes admin by DMing `admin-token`; not a pre-set address).
- [ ] Add the SimpleX `ConversationBackend` binding (drive the real simplex-chat
      subprocess over the WS API; corrId command/response + async inbound).
- [ ] Drive the full lifecycle on SimpleX (scope = the 7 transport-relevant
      scenarios 3,4,7,10,11,12,15 per D-live-5).
- [ ] Assert real LLM latency captured; embedding-retrieval assertion (ask about
      a topic covered by exactly one seeded post; assert that post is retrieved).
- [ ] Assert readiness/liveness (`/q/health/ready`), per-adapter metrics
      (`adapter.connection.status`), LLM-down degraded.
- **Constrained-host model note.** On a 16 GB laptop, the default laptop/ollama
      profile (llama3.1:8b for chat/tagger/summarizer/entity/translator ~4.7 GB +
      llama3.2:3b judge ~2 GB + nomic-embed-text ~0.3 GB, plus the Postgres/Ollama
      images + two JVMs) is tight. The model per task is a plain config property
      (`infochat.llm.<task>.model`) and `prod/scripts/4-llm.sh` pulls whatever the
      active profile records — so a live-smoke run can override the 8b tasks down
      to `llama3.2:3b` (already pulled for the judge → zero extra download, one
      model family) while **keeping** `nomic-embed-text` (retrieval-quality
      assertions depend on the embedder). Trade-off: 3b summarizes/tags worse than
      8b, so keep the smoke assertions behavioural (reply matched, latency
      captured, injection contained), not production-grade output quality — which
      is already the live-run posture (§1: latency/throughput + retrieval presence,
      not quality scoring). Even leaner: the llama.cpp backend's gemma QAT-Q4 (§4a
      of `prod/scripts/4-llm.sh`) but that switches backends — not needed for a
      smoke run.

### Phase 5 — Signal delta verification (smaller)
- [ ] Register bot + 1–2 test Signal accounts (manual; preserved dirs).
- [ ] Prove comms round-trip + ACI-admin bootstrap.
- [ ] Walk the §6 differences checklist.

### Phase 6 — (optional) promote to `/testcase` skill
- [ ] Only after 2–3 scenarios run cleanly. Skill wraps the Phase-4 runner
      (`/testcase <name> run`). `process:` commit.

## 6. SimpleX ↔ Signal differences to verify (Signal task scope)

Confirmed from spec/code (design differs by construction):
- [ ] **Admin bootstrap:** SimpleX single-use claim-token (`admin-token`,
      first-DM) vs Signal pre-seeded ACI (`admin`, validated at startup). [D50]
- [ ] **Identity:** SimpleX per-connection id, profile address self-asserted &
      unverified; Signal ACI is a real crypto account id.
- [ ] **Bot contact-id derivation:** SimpleX queried from running simplex-chat;
      Signal read from signal-cli identity store (drives mention recognition).
- [ ] **Provisioning:** SimpleX scriptable (profile + address + auto-accept);
      Signal manual (phone + captcha), not scriptable.
- [ ] **Transport:** SimpleX WebSocket ↔ simplex-chat subprocess; Signal TCP
      JSON-RPC ↔ signal-cli daemon (framing, reconnect, supervision differ).
- [ ] **Config keys:** simplex.{binary,data-dir,ws-port,admin-token} vs
      signal.{binary,data-dir,account,endpoint,allow-non-loopback-endpoint,admin}.

Declared capability flags (verified 2026-07-01 — hardcoded constants, not
runtime-derived):

| Capability | SimpleX | Signal | InMemory |
|---|---|---|---|
| supportsMentionByContactId | true | true | true |
| supportsMembershipEvents | **false** | **true** | true |
| supportsCodeFormatting | **false** | **true** | true |
| supportsMarkdownLinks | false | false | false |
| supportsMessageEdit | true | true | true |
| supportsTypingIndicator | **false** | **true** | true |
| maxInboundMessageBytes | 16384 | 16384 | 100000 |
| maxSendsPerSecond | 5 | 5 | 10000 |
| minEditInterval | 600 ms | 600 ms | 0 ms |

Behavioural — the real "hallucinated-reality" risk surface (verify live):
- [ ] **Membership events:** SimpleX declares `false`, Signal `true` → group
      join/leave detection flows differently; verify group state tracking on each.
- [ ] **Typing / code-formatting:** SimpleX `false`, Signal `true` → progress
      rendering + code-block output differ; confirm the degraded path on SimpleX.
- [ ] **Message-edit fallback:** both declare edit=true, but Signal has a
      documented edit-failure fresh-send fallback (2 frames); confirm SimpleX
      edit behaviour on failure (may block, no fallback).
- [ ] **Contact-acceptance model:** SimpleX explicit contact-request + auto-accept
      vs Signal "anyone can message a number" (no request) — how the invite gate
      + un-invited-rejection behaves on each.
- [ ] **Mention encoding:** Signal ACI + body-range offsets vs SimpleX's own rep.
- [ ] **Group model:** creation, admin/roles, `upstream_group_id` mapping,
      in-group mention semantics.
- [ ] **Inbound size cap / attachments** (both 16 KB inbound; confirm behaviour
      at the cap).
- [ ] **Async receive model & ordering / dedup.**
- [ ] **Outbound rate limiting:** both cap at 5 sends/sec via `OutboundRateLimiter`;
      confirm pacing holds under real transport.
- [ ] **Failure/reconnect surfaces:** subprocess crash + daemon-down semantics.

## 7. Load-bearing code facts (verified 2026-07-01)

- Prod `Clock` = hardcoded `Clock.systemUTC()` (`ThrottledAdminNotifier
  .systemUtcClock()`); injected-clock override is `@QuarkusTest`-only.
  → live runs use seeded timestamps, not a movable clock.
- `audit_log` append-only via row-level trigger `trg_audit_log_append_only`
  (V5) — does **not** fire on TRUNCATE; V43 shows reset is role/grant-scoped.
  → control-plane reset must special-case audit_log.
- Adapter selection is pure config (`infochat.adapters` CSV);
  `(adapter, contact_id)` keys all identity; D46: `inmemory` XOR
  `{simplex,signal}`.
- SimpleX/Signal both spawn a supervised native CLI subprocess; bot identity is
  derived from the data-dir at startup, **not** an operator-typed property.
- SimpleX admin claim-token single-use = live `is_admin` row for the simplex
  adapter only (`WHERE NOT EXISTS`); no marker/latch. Control-plane reset
  re-arms it (`SimpleXAdminClaim.java`).
- SSRF blocklist is `final` + strict; loopback-permit ctor is package-private,
  test-only (`infochat-ssrf/…/IpBlocklist.java`). No prod/profile relaxation.
- Cross-plane FKs into `users`: `source.{added_by,deleted_by}` (SET NULL),
  `tag.created_by` (RESTRICT). `TRUNCATE users CASCADE` truncates `source`+`tag`
  → data-plane loss; use §3 option (A) or (B).
- All 15 lifecycle scenarios already IT-covered (`GoldenPathJourneyIT` +
  per-scenario ITs); the live run adds transport/LLM/embedding fidelity only.

## 8. Decisions log

- **D-live-1:** Signal gets its own real-transport verification; adapter-fidelity
  risk is per-transport and does not transfer from SimpleX. (User, 2026-07-01)
- **D-live-2:** Reset = control-plane truncate + preserve data-plane; never wipe
  adapter data-dirs. (User, 2026-07-01)
- **D-live-3:** Deterministic testing uses seeded synthetic posts + seeded
  timestamps; real data fetched once and preserved. Malicious-detection fixtures
  enter at the RAW/pre-eval stage. (User + refinement, 2026-07-01)
- **D-live-4:** Build scenario format + runner first; promote to a `/testcase`
  skill only after it proves out. (2026-07-01)
- **D-live-5:** No business-logic backfill for the live run — Phase 1 found all
  15 scenarios IT-covered. Live-run scope = the 7 transport-relevant scenarios
  (3,4,7,10,11,12,15) over real transports + real LLM/embedding. (2026-07-01)
- **D-live-6:** Reset via drop-recreate + data-plane snapshot (option B), because
  `TRUNCATE users CASCADE` would destroy the data-plane through the source/tag
  cross-plane FKs. Snapshot is taken with cross-plane FK columns NULLed.
  (2026-07-01)
- **D-live-7:** Keep the SimpleX admin-token configured across resets so it
  re-arms each run (opposite of prod hygiene, correct for the test loop).
  (2026-07-01)

## 9. Open questions for the human

- [~] Which real feeds form the one-time "now" corpus? — a valid nostr `npub`
      has been added to `bootstrap.json` (2026-07-01). Note: nostr is a
      StreamSource (WS relays), a different ingest path than RSS Fetchers — good
      cross-path coverage; the live fetch will exercise relay connect + Nostr
      signature/kind-filter + cross-relay dedup, not just HTTP fetch.
- [ ] Signal: how many test numbers can you register (bot + N clients)?
- [ ] Do we need deterministic **scheduler-firing** tests (→ justifies a
      profile-gated live clock), or is seeded-timestamp state enough?
