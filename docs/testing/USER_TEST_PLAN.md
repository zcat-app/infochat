# User test plan (setup → admin config → usage)

Status: testing-support notes, not spec. This file sequences the end-to-end
manual + automated testing of an infochat deployment and records which parts
are machine-verifiable vs. which require a human, a real messaging account, a
real LLM, or a real host.

It is the index for the other testing-support docs:

- [observability-runbook.md](observability-runbook.md) — inspection queries and
  log locations to diagnose a deployment while you test it by hand.
- [adversarial-input-kit.md](adversarial-input-kit.md) — crafted hostile inputs
  and the safe outcomes the security model promises, to run against a real LLM.

## The automation boundary

The whole user-facing pipeline (intake → ban check → invite gate → command
parse → permission → execute → chat/LLM → group flow → digest) can run with **no
real messaging account and no real model** through two test seams:

- **In-memory adapter** — `infochat-messaging-adapter/.../inmemory/InMemoryAdapter.java`.
  A production-classpath `MessagingAdapter` activated only by `infochat.adapters=inmemory`
  (decision D46). Tests inject inbound with `deliverDm(contactId, text)` /
  `deliverGroupMention(groupId, sender, text)` and read replies via `sentMessages()`.
  Today these entry points are **test-scope only** — there is no running-app
  bridge, which is why the *dev terminal harness* (below) is worth building.
- **`TestLlmProvider`** — `infochat-provider/.../testing/TestLlmProvider.java`.
  An `@Alternative @Priority(MAX)` bean that replaces the real LLM in every
  `@QuarkusTest`, so chat/`/summary`/eval run deterministically with no Ollama.

What this means in practice:

| Can be proven automatically (no human/account/model) | Genuinely needs you |
|---|---|
| Admin bootstrap, invite→register→probation, all slash commands, chat dispatch (mocked LLM), group lifecycle, digests, asset-command routing, ban/unban intake block | Running the real `prod/setup.sh` wizard on a host (images, model pulls, ~15 GB); real SimpleX queue / Signal phone+captcha; **real LLM output quality** and prompt-injection behavior; rendering in the actual phone app; hardware-profile performance; VPN/network quirks |

## The seven deliverables and their state

| # | Deliverable | Kind | State |
|---|---|---|---|
| 1 | Suite baseline (`mvn verify`) | run | establishes the floor; see §Phase 0 |
| 2 | Seed realistic test data | M1 ticket | proposed — §Code-ticket plan |
| 3 | Dev terminal harness | M1 ticket | proposed — §Code-ticket plan |
| 4 | Golden-path E2E test | M1 ticket | proposed — §Code-ticket plan |
| 5 | Collector ingest + NOTIFY smoke test | M1 ticket | proposed — §Code-ticket plan |
| 6 | Observability runbook | doc | delivered — [observability-runbook.md](observability-runbook.md) |
| 7 | Adversarial input kit | doc | delivered — [adversarial-input-kit.md](adversarial-input-kit.md) |

## Phase 0 — Baseline (automated, do first)

`mvn clean verify` from the repo root. A green run proves the whole pipeline's
*logic* before you touch a host — the cheapest, highest-confidence gate. ITs
bind an ephemeral test port (`quarkus.http.test-port=0`), so a concurrent run
does not collide. Known rare flakes (`OutboxRehydratorPaginationIT`,
`Stage1WatchdogIT`) are retry-once, per [DEVELOPER.md](../../DEVELOPER.md).

Baseline established green on 2026-06-20: a full `clean verify` (~10 min)
passed with zero failures or errors across all modules.

## Phase 1 — Setup / operator (your host)

Run `./prod/setup.sh` (steps 0–8: doctor → profile → secrets → Postgres → LLM →
sources → adapter → apps → verify; `--defaults` takes the laptop/ollama path
non-interactively). Step 8 (`8-verify.sh`) is the OK/DEGRADED gate. Full
walkthrough in [SETUP_GUIDE.md](../../SETUP_GUIDE.md) and design detail in
[docs/design/07-deployment.md](../design/07-deployment.md).

- **Automatable:** static review of each `N-*.sh` against the spec; config-parse
  validation.
- **Yours:** actually running it — Docker, model downloads, disk, and the real
  adapter registration (SimpleX queue / Signal phone+captcha).
- **Watch for:** the data problem (below) — a freshly-set-up bot has *no posts*
  until the collector has fetched and evaluated a feed, so usage-phase commands
  look empty until then. Seed data (#2) or a short wait makes this concrete.

## Phase 2 — Admin config

Bootstrap admin seeded → `/invite create` → `/grant-admin` / `/revoke-admin`
(last-admin protection) → group `/approve-group` + auto-promote → `/add-source
--tags` → `/ban` / `/unban`. See [ADMIN_GUIDE.md](../../ADMIN_GUIDE.md).

- **Automatable:** every one of these has a passing IT or handler test
  (`AdminBootstrapIT`, `InviteIntakeRoundtripIT`, `GroupLifecycleIT`, the
  `*CommandHandlerTest` set). The dev harness (#3) lets you walk them by hand.
- **Yours:** issuing a real invite to a real contact and confirming the code
  arrives in the actual app.
- **Diagnose with:** observability-runbook §Admin & intake.

## Phase 3 — Usage

Register via code → probation limits → DM commands (`/summary`, `/save`,
`/follow-tag`, `/lang`, `/export`, `/zcash`…) → chat mode → group @mention +
digest. See [USER_GUIDE.md](../../USER_GUIDE.md).

- **Data-dependent:** the content commands (`/summary`, `/follow-tag` results,
  `/save`→`/saved`, digests) run deterministic SQL over the `post` table and
  return **empty** with no posts. `/help`, `/status`, `/lang`, and the asset
  commands (`/zcash`/`/monero`, which read live price data, not posts) work
  regardless. So either run the collector against a live feed first, or apply
  seed data (#2).
- **Automatable:** covered with the mocked LLM.
- **Yours:** judging real summary/tag *quality* and phone rendering — inherently
  human, and the point of the adversarial kit (#7) for the security paths.

## Code-ticket plan (deliverables 2–5)

These are code/tests, so each is an M1 ticket driven through `/m1-tick`
(clarity → implement → reviewer → `mvn verify` → commit). Recommended order:

1. **Seed realistic test data** — a fixture (SQL/test resource) of pre-evaluated
   `READY` posts (+ a handful of tags/sources) so the provider has content to
   serve with no LLM or network. Foundational; unblocks #3 and the usage phase.
2. **Dev terminal harness** — a dev-only inbound bridge (REST endpoint or
   console REPL) over the in-memory adapter that prints outbound replies, so you
   drive the real pipeline from a terminal without SimpleX/Signal. Most useful
   once #2 exists. Net-new dev-only code.
3. **Golden-path E2E test** — one IT chaining bootstrap→invite→register→
   probation→command→chat→group→digest→asset→ban in a single narrative.
   Durable regression artifact; can reuse #2's fixtures.
4. **Collector ingest + NOTIFY smoke test** — proves the *other* service: fetch
   → eval pipeline → store → `LISTEN/NOTIFY` → provider reacts. Independent of
   1–3; collector-side.

#1 and #2 give hands-on testing; #3 and #4 give regression/coverage and can run
in either order. Ticket IDs are assigned at `/m1-tick` time.
