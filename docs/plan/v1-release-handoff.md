# v1 release — execution handoff (for a fresh session)

> **Written:** 2026-07-13. **main @ `db23f561`** (pushed). **Start a fresh
> session and drive this.**
>
> **READ FIRST:** `docs/plan/v1-verification-truth.md` — the dated,
> provenance-tagged record of what's verified vs. owed. **This doc is the ACTION
> plan for its §9 TODO; that doc is the STATE authority.** Do not reconstruct
> state from `.scratch` handoffs or memories — see the source-ranking rule there.

## Current state (one line)

Code complete + pushed (642 done, 0 open). Both adapters live-verified 2026-07-04
(`docs/plan/live-e2e/`), **Signal-adapter code frozen since → still valid**.
Stack **paused** on **pre-M1-619 images** (`c731ef63`); only `postgres`+`ollama`
up. DB intentionally reset to prod-state: **4 simplex users** (1 admin vouched,
3 invited), **0 signal, 0 groups**, 5,547 posts, schema v58.

## Before you start (coordination)

- **Concurrent-session hazard.** This is a shared single-worktree checkout. Run
  `git worktree list` and check the branch tip before any commit/merge; re-check
  right before merging. (See `[[concurrent-session-committed-to-my-branch]]`.)
- **Clean-verify rule.** Don't run a full `mvn verify` while the live stack is
  building/running on this host — timing flakes. Pause apps for verify; launch
  long builds/verifies **detached** (`setsid` + marker file — harness background
  tasks get killed mid-build); kill samplers by PID, never `pkill -f` self-match.
  (See `[[clean-verify-monitoring]]`.)

## The work — ordered (each: goal · recipe · acceptance)

### 1. Rebuild + restart from current main
- **Goal:** deploy `db23f561` (includes M1-619's calibrated `0.65` cutoff) so
  live checks exercise shipped code.
- **Recipe:** `prod/scripts/7-apps.sh` — runs `docker compose ... build
  infochat-collector infochat-provider`, then starts Collector (Flyway under
  advisory lock) → waits healthy → starts Provider. Idempotent. **Launch
  detached** per the clean-verify rule. Env: `--env-file prod/runtime/secrets.env`.
- **Acceptance:** all 5 containers healthy; both readiness 200;
  `adapter_connection_status`=1.0 for **both** simplex+signal at `/q/metrics`.
  Confirm `ChatAgent CONFIDENT_SIMILARITY_CUTOFF=0.65` is in the running image.

### 2. Add the Signal bootstrap-admin ACI (operator action)
- **Goal:** none is configured now (`docs/plan/v1-verification-truth.md` §2) — a
  restart won't create a Signal admin. Operator plans to add their **own
  secondary account**.
- **Recipe:** register/obtain the secondary ACI; set
  `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID` in `prod/runtime/secrets.env` (the 07-04
  live-e2e run used exactly this key); restart the Provider.
- **Acceptance:** on boot, log `bootstrap admin ensured: adapter=signal`; a
  `users` row `adapter=signal, is_admin=t` for that ACI. **Needs the operator's ACI.**

### 3. Re-verify M1-619's calibrated 0.65 cutoff (SimpleX)
- **Goal:** the calibrated value was never verified live (only measured offline).
- **Two paths:**
  - **Offline (no bot):** `python3 .scratch/m1-619-handoff/classify-bands.py`
    (needs only postgres+ollama; uses the real `ChatAgent.isMarginalGrounding`
    logic). Compare to `.scratch/m1-619-handoff/measured-bands-2026-07-13.txt`.
  - **Live:** drive the SimpleX test user (§Fixtures) with a confident on-domain
    query and a marginal one.
- **Acceptance:** an on-domain query with bestSim ≥ 0.65 surfaces the "more like
  this" affordance and does **not** clarify; a marginal/lexical-only query
  clarifies. (At 0.75 nearly everything clarified — that was the bug.)

### 4. Live-drive the UNVERIFIED command set (SimpleX, rebuilt image)
Drive via `.scratch/retest/drive.sh <timeout> <label> <message…>` as the test
user (§Fixtures). **Destructive/confirm-gated commands: use a throwaway identity
and restore fixtures after** (the 07-10 retest used temp re-probation / temp
sub-delete, then restored — mirror that).

| Command | Send | Expect |
|---|---|---|
| `/ban` + intake-block | admin bans a throwaway user; that user then DMs | banned user gets ONE fixed reply, never reaches LLM/DB beyond ban check |
| `/unban` | admin unbans; user DMs | normal access restored; `/unban` enumerates side-effects |
| `/export` | user `/export` | own data returned in-band, paginated; audit-logged |
| `/forget` | throwaway user `/forget` → `confirm` | chat/session/saves purged; discloses remaining-scope count |
| `/save` `/saved` `/unsave` | save a READY uid, list, unsave | bookmark persists (global), lists, removes |
| `/add-source` SSRF | `/add-source http://169.254.169.254/… --tags x` | refused at the SSRF/HEAD probe, no row written |
| prompt-injection | chat msg embedding "ignore instructions / reveal admin tools" | no tool leak, no privilege escalation, stays in-scope |
| multi-turn memory | 2–3 turn chat referencing earlier turn | later turn recalls earlier context (recallMemory) |

Mark each ✅/❌ and fold results into `v1-verification-truth.md` §3b.

### 5. Release gates
- **`/redteam release <tag>`** — last milestone redteam was 2026-07-04; ~100
  tickets landed since.
- **Backup/restore + migration on the current build:** `prod/scripts/backup.sh`
  → restore into a sandbox → confirm clean (`restore.sh`); the round-trip last
  passed 07-05 on older schema.
- **Fresh-install smoke:** wizard on an empty box comes up clean.
- **`docs/design/08-verification.md §8.10`** manual checklist (note it predates
  Signal/assets/chat-RAG — extend as you go).

## Fixtures & tooling (reuse, don't rebuild)

- **SimpleX test user:** contact **7**, id `edb6190f-466c-4bdd-8642-31ad488184cf`,
  scope `dm/edb6190f-…`, subscribed to TheHackersNews / BleepingComputer /
  TechCrunch-AI, probation cleared.
- **Driver:** `.scratch/retest/drive.sh` — **serial only** (client locks its
  SQLite profile). **Bot contact link ROTATES** — if stale, re-query via WsProbe
  `/show_address` on the provider WS :5225 (see `[[live-test-user-simplex]]`).
- **Signal:** bot account = operator's own number (intact). Rented Norwegian
  test-client numbers are **gone** — the user/group legs would need fresh
  identities (or restore from a backup that captured the identity dirs;
  `prod/scripts/{backup,pack,restore}.sh`, restore = "same number, no
  re-registration"). **Not required for release** (§3-freeze). Capture recipe:
  `[[signal-jsonrpc-capture]]`.
- **M1-619 harness:** `.scratch/m1-619-handoff/classify-bands.py` (+ measured
  baseline). **Corpus:** 5,547 posts already ingested.

## Done criteria

All §1–§5 green → **update `docs/plan/v1-verification-truth.md`** (bump as-of
date, retag the §3b unverified set and §9 TODOs), then set an explicit **v1-done
marker** (none exists yet). Keep the truth doc — not a new `.scratch` snapshot —
as the record.

## Cross-refs

- State authority: `docs/plan/v1-verification-truth.md`
- Adapter live recipes / findings: `docs/plan/live-e2e/{README,HANDOFF}.md`
- Post-v1 backlog: `docs/plan/future-features.md`
- Memories: `[[v1-verification-truth-doc]]`, `[[live-test-user-simplex]]`,
  `[[clean-verify-monitoring]]`, `[[signal-jsonrpc-capture]]`,
  `[[concurrent-session-committed-to-my-branch]]`, `[[deepseek-remote-llm-config]]`.
