# v1 release — execution handoff (for a fresh session)

> **Written:** 2026-07-13. **Updated:** 2026-07-13 (§1 done; **M1-620
> IMPLEMENTED + MERGED @`cf48bc8c`** — review APPROVE r1 + redteam CLEAN; the
> pause is CLEARED, release plan resumes at §2). Origin still `a47b4786`; local
> main is ahead (unpushed). **Rebuild owed to make M1-620 live** — it is merged
> to main but NOT yet in the running provider bytecode (stack was restarted on
> the pre-M1-620 images after the merge).
>
> **READ FIRST:** `docs/plan/v1-verification-truth.md` — the dated,
> provenance-tagged record of what's verified vs. owed. **This doc is the ACTION
> plan for its §9 TODO; that doc is the STATE authority.** Do not reconstruct
> state from `.scratch` handoffs or memories — see the source-ranking rule there.

## Current state (one line)

**643 done, 0 pending** (M1-620 merged @`cf48bc8c` 2026-07-13). Both adapters
live-verified 2026-07-04 (`docs/plan/live-e2e/`), **Signal-adapter code frozen
since → still valid**. **§1 DONE 2026-07-13: stack REBUILT + UP on `c12c3e03`**
(M1-619's `0.65` cutoff confirmed in the running bytecode). All 4 containers up
(collector+provider+postgres+ollama), both readiness UP, `adapter_connection_status`=1.0
for simplex+signal. DB unchanged from prod-state: **4 simplex users** (1 admin
vouched, 3 invited), **0 signal, 0 groups**, 5,547 posts, schema v58. **The
running images are still pre-M1-620** — a rebuild is owed before `/invite
bot-contact` can be live-driven (§4).

## ✅ M1-620 landed — pause cleared (2026-07-13)

The operability gap that paused the plan is fixed and merged. DM-only,
bot-admin-only **`/invite bot-contact`** now returns the bot's own connect
contact in-band (SimpleX **live current** URL / Signal number), with an
optional single-target `--adapter <name>` override — so onboarding no longer
needs shell access. Merged @`cf48bc8c` (review APPROVE r1, redteam CLEAN,
full verify green twice). **Add it to the §4 live-drive matrix** once the
rebuild lands: as the admin, `/invite bot-contact` should return the SimpleX
short-link URL (matches `.scratch/bot-address.txt`); a group-scope or
non-admin call must be refused. Original pause context (now historical):

While reviewing this handoff the operator hit an operability gap and asked to
**pause the release plan and land a fix before continuing**: a bot admin has no
in-band way to get the bot's own connect address to onboard new people — the
SimpleX contact URL is only printed to the terminal at provisioning (D37), so
onboarding a new SimpleX contact currently needs **shell access to the server**.
(The address is NOT classified secret in `security.md`; D37 only forbids
*persisting* it to logs/config — not an admin viewing it in-band. Verified.)

- **Filed:** `M1-620` — DM-only, bot-admin-only **`/invite bot-contact`**
  subcommand that returns the bot's own connect contact in-band: **SimpleX →
  live current contact URL**, **Signal → registered number**. Ticket at
  `docs/plan/m1/tickets/M1-620-invite-bot-contact-retrieve-connect-address.md`,
  `status: pending`, committed `c12c3e03` (unpushed). `complexity: high`,
  `security_relevant: true`, `files_budget: 12`, no `files_scope`.
- **NOT started.** `/m1-tick start M1-620` was invoked but **halted at the
  grounding checkpoint** — no branch, no status flip. Fully clean: on `main`,
  ticket still `pending`.
- **Design already scoped** (Explore survey done — don't re-derive): it's a
  subcommand of the existing `InviteCommandHandler` (reuses its DM-only + admin
  gate ~L193/L202-205 → **no new command bean, no command-index change**).
  **The real cost:** there is **no runtime path to the SimpleX self-address** —
  the `MessagingAdapter` SPI has no self-identity method, `/show_address` was
  removed as consumer-less in **M1-518 (D51)**, and the SimpleX WS codec has no
  encode/decode for it. So SimpleX needs a new SPI method + a new simplex-chat
  **WS address-query codec** (encode the query + decode the response — use a
  **REAL captured v6.5.4.1 frame** as the fixture, `[[simplex-live-frame-capture]]`).
  **Signal is cheap:** it already holds `account`/`botAci` in-process
  (`SignalAdapter` L98/L107) — just add an accessor. Command reply bodies are
  **already not logged** (D37 satisfied on the outbound path).
- **Design questions RESOLVED (operator, 2026-07-13, in-session — ticket
  amended accordingly, nothing left to re-ask):**
  1. Subcommand `/invite bot-contact` — **confirmed as filed.**
  2. Signal returns the registered number — **confirmed as filed.**
  3. Cross-adapter `--adapter <name>` — **IN scope for v1** (operator choice;
     moved from out_of_scope to acceptance. Cheap: InviteCommandHandler already
     injects AdapterRegistry + iterates activatedAdapters(), adapters expose
     name(). Multi-adapter enumeration per reply stays out).
  4. SimpleX value: **live query each call — confirmed as filed.**
- **Then drive it in the MAIN session** (`/m1-tick start M1-620` → implement →
  `/m1-tick review` → `/redteam M1-620` (security_relevant) → commit → merge).
  **NOT via the Workflow tool** — it can't nest the gate subagents
  (`[[m1-tick-workflow-cannot-nest-gates]]`).
- **Sequencing gotcha:** implementing needs `mvn verify`, which wants the app
  stack **paused** (clean-verify rule) — but capturing the real SimpleX WS
  response frame needs the bot **UP**. So **capture the frame first (bot up),
  then pause the stack for verify.**

Only after M1-620 is merged does the release plan below resume (§2 onward). §1 is
already done.

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

### 1. Rebuild + restart from current main — ✅ DONE 2026-07-13
- **Done on `c12c3e03`** via `prod/scripts/7-apps.sh` (launched detached). Exit 0:
  both images built, SimpleX identity provisioned (idempotent no-op on the
  existing profile — address did NOT rotate), Collector healthy (Flyway applied),
  Provider started.
- **Acceptance all green:** collector *(healthy)* + provider *(ready)* + postgres
  + ollama up; provider & collector readiness **UP**; `adapter_connection_status`
  = **1.0** for both simplex + signal; and `javap` on the running provider
  bytecode confirms `CONFIDENT_SIMILARITY_CUTOFF = 0.65d`. Note: only 4 infochat
  containers exist (compose defines postgres+collector+provider, ollama alongside);
  Signal+SimpleX run **embedded in the provider**, not as sidecars — the doc's
  "5 containers" was a loose count. Provider has **no compose healthcheck** (only
  collector does); verify it via its readiness endpoint (`docker exec … curl
  127.0.0.1:8081/q/health/ready`).
- **Aside (rotation myth, resolved):** an image rebuild/restart does NOT
  disconnect existing contacts — a SimpleX contact address is bootstrap-only;
  established contacts talk over their own pairwise connection. The bind-mounted
  profile DBs are preserved across rebuilds. Current stable address is in
  `.scratch/bot-address.txt` (with a corrected note).

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
