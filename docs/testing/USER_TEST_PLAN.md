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
| 2 | Seed realistic test data | M1 ticket | delivered — [M1-413](../plan/m1/tickets/M1-413-test-seed-fixture-ready-posts.md) (`SeedFixture` + `seed-ready-posts.sql`) |
| 3 | Dev terminal harness | M1 ticket | delivered — [M1-414](../plan/m1/tickets/M1-414-test-dev-inmemory-terminal-harness.md) (§Phase 3 — Usage) |
| 4 | Golden-path E2E test | M1 ticket | delivered — [M1-415](../plan/m1/tickets/M1-415-test-golden-path-journey-it.md) (`GoldenPathJourneyIT`) |
| 5 | Collector ingest + NOTIFY smoke test | M1 ticket | delivered — [M1-416](../plan/m1/tickets/M1-416-test-collector-ingest-notify-smoke-it.md) (`IngestNotifySmokeIT`) |
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

### Driving by hand — dev terminal harness (M1-414)

The dev terminal harness lets you walk the real DM/group pipeline through the
in-memory adapter from a terminal, with no SimpleX/Signal account. It is a
dev-build-only tool that injects raw inbound (it bypasses the adapter's
cryptographic identity, so you may claim any contact id) — but NOT the
authorization pipeline: ban check, invite gate, probation, and per-(user,scope)
isolation all still run. It is double-gated and cannot exist in a production
build:

- **Enable both gates.** Run with `infochat.dev.harness.enabled=true` AND
  `infochat.adapters=inmemory`. The harness bean is excluded at build time from
  any build that does not set the flag (`@IfBuildProperty`), so it can never
  ship in production.
- **Files (defaults shown).** `infochat.dev.harness.input-file=data/dev-harness-in.txt`,
  `infochat.dev.harness.output-file=data/dev-harness-out.txt`,
  `infochat.dev.harness.poll-interval=1s`. A scheduler tick tails the input file
  for newly-appended lines and appends the captured replies to the output file.
- **Drive it.** Append one directive per line; tail the output:

  ```
  echo 'dm alice <invite-code-uuid>' >> data/dev-harness-in.txt   # register alice
  echo 'dm alice /summary -w 24h'    >> data/dev-harness-in.txt
  echo 'group g1 alice /help'        >> data/dev-harness-in.txt
  tail -f data/dev-harness-out.txt
  ```

  Directive grammar: `dm <contactId> <text>` and
  `group <groupId> <senderContactId> <text>`. A contact must redeem an invite
  code (issue one as admin first) before content commands work, exactly as a
  real DM would.
- **Data.** The harness does no seeding (inserting posts needs owner privilege,
  which is test-only by design). For content commands to return rows, run the
  collector against a live feed first, or load the deterministic synthetic
  corpus with `prod/live-seed.sh` (M1-537) — a first-class, idempotent,
  owner-role loader that seeds an M1-413-shaped READY corpus into the running
  deployment DB with window-relative timestamps. See §Synthetic corpus seed
  below.

### Live-iteration reset (M1-536)

Iterating the live workflow (register → command → chat → group → digest) many
times needs a **fast reset that does NOT re-fetch feeds**. Re-fetching every run
is slow, non-deterministic, and impolite to public endpoints, so the loop resets
only the *control-plane* (user / group / invite / chat / audit / provider state)
and preserves the *data-plane* (the already-fetched, already-evaluated posts +
embeddings). Run it between iterations:

```
prod/live-reset.sh
```

- **This is NOT `prod/setup.sh --reset`.** That path is the FULL teardown
  (`docker compose down`, and per M1-395 the LLM services too) — it destroys the
  containers and every fetched post. `live-reset.sh` leaves all containers and
  the whole data-plane in place; only the control-plane rows go. Use the full
  teardown to start over from nothing; use `live-reset.sh` to re-run the app
  workflow against the same fetched corpus.
- **What it does.** Runs the FK-safe data-only reset (`prod/sql/reset-control-plane.sql`)
  under the database owner role — reached via `docker compose exec postgres`, the
  same way `backup.sh` reaches Postgres. It clears every control-plane table
  (`users`, `groups`, `group_membership`, `invite_code`, `chat_*`, `audit_log`,
  …) while leaving `source`, `tag`, `post` (+ partitions), embeddings,
  entities, references, price snapshots — and `provider_state`, the Provider's
  cursor over that preserved data (its sentinel rows are seeded only by
  first-boot migrations, so clearing it would boot-loop the next Provider
  start) — untouched. It captures the
  `post` row count before and after and asserts it is unchanged, asserts every
  control-plane table is empty, and exits non-zero if either check fails. It is
  idempotent — running it twice leaves identical state.
- **Adapter identities are never touched.** The SimpleX queue keypair and the
  signal-cli account dir survive, so the bot keeps its address and contacts.
- **TEST-loop only — never a production procedure.** The reset deliberately
  clears `audit_log`, which is **append-only in production** (Invariant 10 / D34).
  Wiping it is acceptable ONLY for a disposable live-test deployment where a clean
  audit slate per iteration is wanted; never run this against a real deployment.

### Synthetic corpus seed (M1-537)

A freshly-set-up (or freshly-reset) deployment has **no posts**, so the content
commands (`/summary`, `/follow-tag`, `/save`→`/saved`, digests) return empty.
`prod/live-seed.sh` loads a deterministic, already-evaluated corpus directly at
the READY terminal state — the synthetic "future" half of the data strategy,
composing with the M1-536 reset above (which preserves any once-fetched real
corpus in place). Run it after a reset (or against a fresh DB):

```
prod/live-seed.sh                      # posts within the last hour (24h window)
prod/live-seed.sh --offset-minutes 1500  # posts ~25h old → OUTSIDE a 24h window
```

- **What it seeds.** One active RSS source, a three-tag controlled vocabulary,
  and 3 `READY` posts with deterministic `m1-537-…` uids (one with an embedding,
  two with NULL embeddings to exercise the embedding-optional retrieval path),
  plus a `RAW` and a `QUARANTINED` control post that retrieval must exclude. Row
  shapes mirror the M1-413 fixture (`seed-ready-posts.sql`).
- **Timestamps, not a mock clock.** The prod `Clock` is hardcoded
  `Clock.systemUTC()`; time-window behaviour is controlled by the DATA.
  `--offset-minutes N` (default 30) sets the seeded posts' `published_at`/`ready_at`
  to `now() - N` minutes, so you place rows inside or outside a given `/summary`
  window without touching the app clock.
- **Idempotent + self-verifying.** Flat rows upsert on their natural keys and the
  partitioned posts delete-then-insert by uid, so a second run neither duplicates
  rows nor errors. After load it asserts the 3 READY posts are retrievable for the
  subscribed `(dm, seed-user)` scope, the 2 non-READY posts are excluded, and 2
  READY posts have a NULL embedding — exiting non-zero if any check fails.
- **Owner role.** `post`/`source`/`tag` are collector-owned; the seed runs under
  the database owner (`infochat`), reached via `docker compose exec postgres` the
  same way `live-reset.sh` and `backup.sh` do.
- **After a reset, restart the Provider** to re-seed. Expected post-conditions
  (manual verification for the live loop):
  - `AdminBootstrap` re-creates the configured bootstrap admin (`is_admin=true`).
  - The SimpleX admin-claim token re-arms — its single-use gate is the presence of
    a `(simplex, is_admin)` row (D50), which the reset removed.
  - A fresh invite → register cycle works from scratch.
  - A pre-existing preserved post is returned by `/summary` for a newly-registered,
    newly-subscribed user — proving the data-plane survived the reset.

### Adversarial RAW injection (M1-538)

The synthetic corpus above seeds `READY` posts and **bypasses** the eval
pipeline. This is the opposite: it injects an adversarial post at the pre-eval
`RAW` stage so the **real** Collector pipeline (Stage 1 deterministic scrub +
redaction, Stage 2 LLM judge — D20/D22) runs on it and must quarantine it. It is
the malicious-detection half of the data strategy and a `[real-LLM]` test
(`adversarial-input-kit.md` §A, case A1) — the mocked-LLM suite cannot prove
Stage 2's judge. Run it against a running deployment:

```
prod/live-inject-adversarial.sh                       # reaper trigger, waits up to 10m
prod/live-inject-adversarial.sh --timeout-seconds 120 # after restarting the collector (fast path)
```

- **What it injects.** One self-contained adversarial RSS source (`disabled`, so
  the fetch scheduler never tries to fetch its bogus identifier) and one `RAW`
  post with the deterministic uid `m1-538-adversarial-a1`, whose body is the A1
  ingest-side prompt injection carrying the verbatim `grantAdmin` token. It
  upserts its own source, so it does not depend on the M1-537 seed.
- **Trigger — reaper vs restart, not a mock clock.** A bare `RAW` insert is not
  auto-enqueued (enqueue lives in `PostPersister`). The SQL backdates the row's
  `status_changed_at` (`--backdate-minutes`, default 1440 = 24h) past the
  `infochat.eval.stale-raw.age` (default 30m) so the real
  `Stage1Worker.reEmitStaleRaw()` reaper (default every 5m) re-enqueues it
  **without a restart** — the non-disruptive default. Restarting the collector is
  the fast path: `OutboxRehydrator` (`@Startup`) re-enqueues every `RAW` row at
  once. There is no NOTIFY path for eval enqueue.
- **Expected quarantine outcome.** After a bounded polled wait the script asserts
  the post reached a **non-READY terminal state** — `QUARANTINED` (D22) or
  `NEEDS_REVIEW` if the LLM verdict is UNKNOWN — that a `quarantine` row exists,
  and that the raw `grantAdmin` token appears in **no** retrievable `READY` post
  body (Stage 1 replaced the flagged span with `[REDACTED:<id>]`). It exits
  non-zero if the post is still `RAW` after the wait or if the payload is
  retrievable. A post promoted to `READY` means the ingest defense did NOT
  contain the injection — a real gap, and a separate remediation ticket (never an
  inline fix to the pipeline).
- **Idempotent + owner role.** Re-running upserts the source and delete-then-inserts
  the `RAW` post by uid (clearing the prior run's quarantine row), resetting it
  for a fresh re-evaluation. `post`/`source` are collector-owned; the tool runs
  under the database owner (`infochat`), reached via `docker compose exec postgres`
  the same way `live-seed.sh` and `live-reset.sh` do.

## Code-ticket plan (deliverables 2–5)

These are code/tests, so each was an M1 ticket driven through `/m1-tick`
(clarity → implement → reviewer → `mvn verify` → commit). All four are now
delivered; the order they were built in:

1. **Seed realistic test data** ([M1-413](../plan/m1/tickets/M1-413-test-seed-fixture-ready-posts.md)) — a fixture (SQL/test resource) of
   pre-evaluated `READY` posts (+ a handful of tags/sources) so the provider has
   content to serve with no LLM or network. Foundational; unblocked #2 and the
   usage phase. Shipped `SeedFixture` + `seed-ready-posts.sql`.
2. **Dev terminal harness** ([M1-414](../plan/m1/tickets/M1-414-test-dev-inmemory-terminal-harness.md)) — a dev-only inbound bridge (a file-driven
   poller on the existing scheduler — no HTTP, keeping the provider deaf to
   inbound calls) over the in-memory adapter that writes outbound replies to a
   file, so you drive the real pipeline from a terminal without SimpleX/Signal.
   Net-new dev-only code (`DevTerminalHarness`); see §Phase 3 — Usage.
3. **Golden-path E2E test** ([M1-415](../plan/m1/tickets/M1-415-test-golden-path-journey-it.md)) — one IT chaining bootstrap→invite→register→
   probation→command→chat→group→digest→asset→ban in a single narrative.
   Durable regression artifact (`GoldenPathJourneyIT`); reuses #1's fixtures.
4. **Collector ingest + NOTIFY smoke test** ([M1-416](../plan/m1/tickets/M1-416-test-collector-ingest-notify-smoke-it.md)) — proves the *other*
   service: fetch → eval pipeline → store → `LISTEN/NOTIFY` → provider reacts.
   Independent of 1–3; collector-side (`IngestNotifySmokeIT`).

#1 and #2 give hands-on testing; #3 and #4 give regression/coverage.
