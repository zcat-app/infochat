# Live E2E — Handoff & Progress Log

> **Lightweight session-to-session tracker** for the live end-to-end run.
> This file answers "where are we, what's next, what changed" at a glance.
> The full plan (phase checklists, transport strategy, reset/data strategy,
> differences checklist, load-bearing code facts, canonical decisions log) lives
> in [`README.md`](README.md) — that stays the source of truth; this file links
> into it. `process:` commit prefix (docs-only, no ticket, no `mvn verify`).

Last updated: 2026-07-04 late night (**LIVE-E2E PLAN COMPLETE — M1-566 merged (96f6ef09), images rebuilt, F-live-11 host validation GREEN: live /summary edits in place (1 dataMessage + 2 editMessage revisions, two-hop latest-revision chain), zero fallback_send; board 590 done / 0 pending; Phase 6 (/testcase skill) stays optional**) · Owner: ubuntu5 + Claude

---

## ▶ START HERE (fresh session — next step)

**THE LIVE-E2E PLAN IS COMPLETE (2026-07-04 late night).** M1-566 (the
F-live-11 fix, the last open finding) is merged @ 96f6ef09, both app
images are rebuilt from it, and the ticket's host validation is GREEN
(see the running-log entry): live `/summary -w 48h` from the signal
user DM delivered ONE message that edited in place — placeholder
dataMessage → `Translating...` editMessage targeting the placeholder →
final-summary editMessage targeting the FIRST EDIT's timestamp (the
latest-revision chain live-proven, two hops) — with
`adapter_outbound_update_total{outcome="ok"} 2`, `coalesced 3`, and NO
`fallback_send`/`update_fail` counters at all (the pre-fix flow read
`update_fail 1, fallback_send 2`). Typing STARTED/STOPPED correct.

**No open findings. Board: 590 done / 0 pending.** Remaining option:
Phase 6 (`/testcase` skill wrap of the ScenarioRunner) — deliberately
optional, user's call. One new low-priority observation recorded:
F-live-12 below (reEmitStaleRaw startup SRMSG00034, self-healing).

**NEXT ACTION:** none mandatory. Push is the user's call (`main` is
~22 commits ahead of origin). If a future session drives group
scenarios, remember `live-signal-group` is APPROVED with the user as
group admin (clear the groups row or build a fresh group for a re-run).

---

## ▶ previous START HERE (2026-07-04 late night — Phase 5 done, kept for context)

**PHASE 5 IS DONE (2026-07-04 late night).** The §6 differences
checklist is fully walked — every behavioural item is ticked in
README §6 with live evidence (see the running-log entry for the probe
detail):

- **16 KB inbound cap:** real wire delivers 20 KB; dropped at the
  shared UTF-8-byte check, value-free WARN, no reply. Cap is reachable
  ⇒ load-bearing.
- **Rate pacing:** 8-msg burst (11 ms spread) → replies at a sustained
  5/s (mean gap ≈200 ms). Bonus: server-queued replies arrive in order,
  no duplicates (async-receive item).
- **Reconnect:** daemon SIGKILL → documented WARN + jittered backoff →
  `Signal adapter reconnected after subprocess restart` +4.7 s →
  round-trip green.
- **Edit fallback:** CONFIRMED live — and it fires on EVERY edit:
  **F-live-11** (new finding, ticket candidate): signal-cli 0.14.5 has
  no `updateMessage` method; the fix (`send`+`editTimestamp`) is
  proven live. Typing indicator verified in the same probe.
- Chat mode is single-frame by design (ProgressNotifier belongs to
  /summary only — the survey subagent mis-attributed it; verified in
  source).

**Fixture state:** signal user DM scope now has a `source_subscription`
row → m1-537 seed source (user-approved INSERT, kept); `live-signal-group`
is approved with the user as group admin.

**NEXT ACTION:** `/m1-tick run M1-566` — the F-live-11 fix ticket is
DRAFTED (docs/plan/m1/tickets/M1-566-signal-edit-real-wire-encoding.md:
re-encode Signal edit as `send`+`editTimestamp` DM+group, refresh the
handle timestamp per edit so chains target the latest revision,
reconcile fakes without weakening the fallback suite). Full verify
needed → pause collector+provider per the co-location rule. Needs an
image rebuild after merge; host validation recipe is in the ticket
§Notes (live /summary must edit in place, fallback_send must not
increment). Then the live-e2e plan is complete — Phase 6 (/testcase
skill) stays optional.

---

## ▶ previous START HERE (2026-07-04 late night — s07 GREEN, kept for context)

**s07 GROUP FLOW OVER SIGNAL IS GREEN (2026-07-04 late night).** All five
scenario steps passed over real relays against the rebuilt images (see
the running-log entry for evidence detail):

1. Unapproved-group `@bot /help` (structured mention: signal-cli
   `--mention "0:4:<botAci>"` on the base64 group id) → D47 pending
   reply delivered to the group, `groups` row created
   `approval_status=pending`, ADMIN-NOTIFY WARN emitted with the
   approve command (log+row IS the designed delivery — no DM leg,
   ThrottledAdminNotifier javadoc says push SPI is out of scope).
2. Admin DM `/list-groups` → `Groups (1 rows…` with the gid.
3. Admin DM `/approve-group <gid>` → "approved. Periodic digests will
   begin", group notified, DB `approved` + `activated_by` set.
4. Next eligible mention → "Available commands in this group:" +
   silent auto-promote (`group_membership.is_group_admin=t` for the
   user, who is NOT a bot admin).
5. `@bot /group-timezone UTC` from the auto-promoted user →
   "Group timezone set to `UTC`." (group-admin gate satisfied).

Bonus validation: the live 32-byte group id flowed through the M1-565
shape gate's ACCEPT path on the new images — F-live-10 parse + gate
both proven on real wire. Eligibility note for future fixtures:
auto-promote gates only on `is_banned=false` + probation expiry;
`registration_state='invited'` counts as registered.

**NEXT ACTION:** remaining §6 differences items (host work, no
tickets): message-edit fallback, outbound rate pacing (5 sends/sec),
reconnect/daemon-down semantics, 16 KB inbound cap. REMEMBER the
verify co-location rule still binds any future full verify — pause
collector+provider first (`[[clean-verify-monitoring]]`).

---

## ▶ previous START HERE (2026-07-04 night — M1-565 merged + rebuilt, kept for context)

**M1-565 is DONE + MERGED (2026-07-04, commit 8495c882).** The last
Phase-5 ticket shipped: a band-bounded base64 shape gate on the Signal
group-id scope key (M1-562 redteam out-of-model item 2). New shared
`SignalMessageCodec.isAcceptableGroupId` — strict `Base64.getDecoder()`
decode + a [16,64]-byte length BAND (not an exact pin: live id is 32
bytes, fixtures decode to 20; an exact pin is F-live-10 in reverse) —
mirrors `isAcceptableAci`; `SignalGroupHandler.extractGroupId` applies
it to whichever spelling matched and drops a rejected frame with an
observable, value-free WARN (encoded LENGTH + `signal-<ts>`
adapterMessageId token only, never the id value — D37). Defense-in-depth
(the id is trusted loopback today), so the gate matters only if that
boundary is ever redrawn. Full cycle: clarity PASS (0/0) → 4 files
(exactly files_scope) → verify RED once on the documented
`MultiAdapterProductionIT` co-location flake (2000 ms outbound-DM probe,
causally disjoint from a group-inbound diff) → GREEN on the quiet-host
re-run (227 tests) → redteam CLEAN (2 advisory out-of-model, both gated
on a future trust-boundary redraw: validate-but-don't-canonicalize the
base64 spelling; per-frame unthrottled WARN — no tickets) → review
APPROVE r1 (0 rework) → squash-merge. Not pushed.

**~~⚠ Image rebuild pending~~ DONE (2026-07-04 late night):** both app
images rebuilt from main @ c4e7093e (apps paused per the co-location
rule, detached setsid+marker launch, BUILD_EXIT=0) and restarted
collector-first. Live-verified: readiness UP both services,
`adapter_connection_status` 1.0 for BOTH adapters, 0 SRMSG00034 on the
collector boot, containers confirmed on the fresh image IDs. **The
M1-565 shape gate is LIVE** — s07 now exercises the merged
`extractGroupId` path including the gate (live 32-byte id is in-band,
so s07 doubles as the gate's accept-path live validation).

**Board: 0 pending — every Phase-5 ticket is drained** (M1-562/563/564/
565). Phase 5 remaining is host work, no tickets: s07 group flow over
Signal + the §6 differences checklist.

**NEXT ACTION:** s07 group flow over Signal — the 3-party
`live-signal-group` fixture is READY (admin creator + bot + user, all
full members via the invite-link path; direct-add unusable on 0.14.5).
Then the remaining §6 differences items (edit fallback, rate pacing,
reconnect/daemon-down, 16 KB cap). REMEMBER the verify co-location rule
still binds any future full verify — pause collector+provider first
(`[[clean-verify-monitoring]]`).

---

## ▶ previous START HERE (2026-07-04 night — M1-563 merged, kept for context)

**M1-563 is DONE + MERGED (2026-07-04, commit 8908e3b5).** The
leave-cleanup posture amendment shipped: on membership-event-less
adapters (BOTH v1 production adapters) per-user leave cleanup is now a
stated NON-COMMITMENT across security.md §Authorization model,
messaging.md (§Required SPI surface — Membership events + §Failure
handling — "User left group"), schema.md §Identity and access, and the
two design mirrors (06-messaging §6.3.6, 02-schema §2.1.4). A departed
group admin keeps the slot (auto-promote does not fire), silently
resumes admin on rejoin, and bot-admin `/demote` is the documented
remediation. In-cycle redteam caught the round-1 diff leaving schema.md
carrying the pre-narrowing promise (MEDIUM PERM-ESCAL — an internal
spec contradiction); escalate→refine widened scope (files_budget 3→5,
+schema.md +02-schema.md) to reconcile it, review APPROVE r2, re-audit
CLEAN. Doc-only (no code), `mvn verify` inert per M1-379.

---

## ▶ previous START HERE (2026-07-04 night — F-live-10 fix, kept for context)

**F-live-10 is FIXED AND LIVE (2026-07-04 night).** Where things stand:

- **M1-562 merged (58767406):** `SignalGroupHandler` parses the real
  signal-cli 0.14.5 wire shape (`groupInfo{groupId}` first, `groupV2{id}`
  kept for route symmetry with the DM exclusion guard);
  `supportsMembershipEvents` flipped to false per the spec mandate (no
  native per-user membership signal on the 0.14.5 wire) and the
  never-fires membership dispatch removed — delivery-failure fallback,
  the SimpleX posture. Review APPROVE r1; pre-commit redteam CLEAN
  (docs/plan/m1/redteam/M1-562-2026-07-04.md; 2 advisory out-of-model
  items → follow-up tickets below).
- **Images rebuilt from main and the stack is LIVE on them** (all 5
  containers healthy, readiness UP, `adapter_connection_status` 1.0 for
  BOTH adapters). The rebuild surfaced a new gotcha, fixed by **M1-564
  (merged 1f6299d6):** the signal-cli daemon's root-owned
  `prod/runtime/signal-cli/data` breaks the classic builder's context
  walk (`checking context: can't stat`) — `.dockerignore` now excludes
  `prod/runtime/` (secrets/identity stores never belonged in the build
  context anyway; shipped images were always clean, multi-stage).
- **Board: 2 pending drafts from the M1-562 redteam out-of-model items:**
  M1-563 (spec amendment — leave-cleanup NON-COMMITMENT on
  membership-event-less adapters + /demote remediation; both v1 adapters
  are now in that class) and M1-565 (nice-to-have defense-in-depth —
  band-bounded base64 shape gate on the group-id scope key, observable
  WARN on rejection).

**NEXT ACTION (user decision 2026-07-04, session end):** drain the two
pending tickets first — `/m1-tick run M1-563` (spec amendment, doc-only)
then `/m1-tick run M1-565` (group-id shape gate; needs full verify —
pause the live stack per the co-location rule). THEN s07 group flow over
Signal — the 3-party `live-signal-group` fixture is ready (see the
fixture entry in the running log); then the remaining §6 differences
items (edit fallback, rate pacing, reconnect/daemon-down, 16 KB cap).
Note for M1-565: it touches SignalGroupHandler/SignalMessageCodec — an
image rebuild after merge is needed before its gate is live (fold into
the next rebuild; s07 does NOT depend on it).

---

## ▶ previous START HERE (2026-07-04 evening, kept for context)

**PHASE 5 (Signal) — one blocker away from done.** Where things stand
(2026-07-04 evening; full detail in the two Phase-5 running-log entries):

- **DONE + GREEN live:** 3 Signal accounts registered & PIN-locked
  (smspva rentals — now disposable); dual `simplex,signal` mode
  deployed; bot↔admin round-trip; D50 ACI-admin bootstrap; S4
  un-invited-DM rejection (zero rows persisted); S3 invite
  mint→consume (confirm-gated, D45 probation, `/vouch` clears);
  dual-adapter `/zcash` + LLM-chat on BOTH adapters (same deployment,
  same llamacpp backend; reply content garbage on both = D49
  model-side residual, adapters fine).
- **BLOCKER — F-live-10 (HIGH, adapter bug, needs a ticket):** Signal
  group inbound is dead on real wire — signal-cli 0.14.5 emits
  `dataMessage.groupInfo{groupId,…}`, `SignalGroupHandler` parses
  `groupV2{id,…}`; every group message silently drops. Membership
  arrays (`memberJoined`/`memberLeft`) also don't exist in 0.14.5
  output → the `supportsMembershipEvents=true` surface needs a decision
  in the same ticket. DMs unaffected. See §Live findings F-live-10.
- **READY + WAITING:** 3-party `live-signal-group` fixture (admin
  creator + bot + user, all full members via the invite-link path —
  direct-add is unusable on 0.14.5). s07-over-Signal runs right after
  the F-live-10 fix + image rebuild.

**NEXT ACTION:** draft the F-live-10 fix ticket → `/m1-tick run` it →
image rebuild → s07 group flow over Signal → remaining §6 items (edit
fallback, rate pacing, reconnect/daemon-down, 16 KB cap).

**Private-numbers tooling (never put the numbers in argv/chat/logs):**
`prod/runtime/signal-private.env` (chmod 600, holds numbers + PIN) +
`prod/runtime/signal-run.sh` (masking wrapper; roles bot|admin|user →
data-dirs `prod/runtime/signal-cli`, `prod/runtime/signal-clients/
{admin,user}`). Bot data-dir is daemon-locked while the provider runs —
stop the provider for any bot-CLI operation. Captcha links + SMS codes
are non-sensitive and go via chat.

**Chat-latency yardstick (do not re-diagnose as a regression):**
70–150 s per chat turn is the EXPECTED vps profile — CPU-only llama.cpp
on 4 vCPU, ~27 tok/s prefill / ~4 tok/s decode; a history-laden scope
(1878-token prompt) costs ~70 s in prefill alone before the first
output token (s12 GREEN was 159.6 s; M1-560 validation ~150 s; F-live-5
sized the 240 s timeout for exactly this). Verified 2026-07-04: no
co-located build was running during the slow turns; post-restart llama
CPU ~300% = collector RAW-backlog evals (normal). Faster = operator
tier: smaller GGUF, GPU host, or remote-LLM profile (DeepSeek answered
in ~10 s).

---

## ▶ previous START HERE (2026-07-04 afternoon, kept for context)

**4b-4 is DONE (2026-07-04 afternoon).** All five assertion items have live
evidence (see the running log for detail):

1. **Real LLM latency** — prior s10 (217 s / 293 s) + s12 (159.6 s) evidence
   stands; today's ollama-backed chat turns added 20–40 s samples.
2. **Retrieval assertion GREEN** — but reframed honestly first: v1 has **no
   embedding/RAG retrieval in chat** (spec llm.md:19 "Retrieval is always
   SQL", D19; embeddings feed only the collector's `LinkingJob`). The README
   scenario-13 "RAG retrieval" wording was a mis-assumption. What WAS
   asserted live: chat agent → `searchPosts` tool (2 real
   `/v1/chat/completions` calls = a genuine tool loop) → deterministic SQL
   retrieved the exactly-one seeded post → reply named its title
   ("Seed: security advisory"). Real-embedding evidence separately:
   `embedding_metadata` = nomic-embed-text-v1.5/768 real identity row, 2165
   vectors, LinkingJob produced 388 semantic links (avg cosine 0.877).
3. **Readiness/liveness** — UP on both services (provider mgmt :8081,
   collector :8080).
4. **Per-adapter metrics** — **F-live-7 (gap, ticket candidate):**
   `/q/metrics` is 404 on BOTH services.
5. **LLM-down = degraded** — verified live: chat LLM container stopped →
   user received the friendly bundle reply ("The chat assistant is
   unavailable right now…"), full content-free cause chain in the log
   (`ConnectException > UnresolvedAddressException` — the M1-542 logger
   paying off), readiness stayed UP (spec deployment.md degraded-not-dead),
   no crash, clean recovery on container start.

**Ollama backend VERIFIED live (user-requested check):** compose `ollama`
service (0.30.8) + `llama3.2:3b` (the vps wizard default), chat task pointed
at `http://ollama:11434/v1` → full turn + tool loop + delivery green over
real relays. Setup notes: the native host ollama holds 127.0.0.1:11434 and
sudo is interactive-only, so the compose service ran with a port override
(host 11435; container-network reach is what matters). Reverted after; the
`infochat-ollama` volume KEEPS the pulled model for future runs.

**Remote-LLM leg DONE (2026-07-04 evening — user provided a DeepSeek key):**
chat task pointed at `https://api.deepseek.com` (OpenAI-compatible;
`deepseek-chat`; `api-key` prop fed by the existing compose env
passthrough) → tag-pinned retrieval question answered in ~10 s with a
correct grounded reply over real relays. Reverted to the llamacpp baseline
after; details + re-apply recipe in the running log.

**New findings (all ticket candidates, see §Live findings):**
- **F-live-7** — no metrics export: `quarkus-micrometer` is in the poms but
  no `quarkus-micrometer-registry-prometheus`, so AdapterMetrics/LlmMetrics
  register into a registry nothing exports; `/q/metrics` 404s (design
  07-deployment.md:1059 promises it; spec deployment.md's "per-adapter
  connection state is exposed separately via metrics" is unreachable).
  `GET /q/health/llm` (design 07-deployment.md:1058) is also unimplemented.
- **F-live-8** — the shipped host chat model
  (`gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf`) is unreliable at chat: 4/4
  turns today failed (2× llama.cpp 500 "does not match peg-gemma4 format"
  on `<|channel>`-marker/degenerate output; 1× parsed-but-EMPTY reply —
  all 600 cap tokens burned in a non-content channel, user received a blank
  DM; 1× fast-500). Contradicts the F-live-6 note "no thinking channel";
  yesterday's s12 green was a favorable sample. Provider sends no
  `temperature` (llama.cpp default ~0.8) — a possible knob. Model-choice /
  sampling follow-up, F-live-6 family.
- **F-live-9** — chat path leaks the structured refusal marker:
  `ChatPromptBuilder:44` instructs `[REFUSAL: <reason>]` but nothing in the
  chat path intercepts it — a live ollama turn delivered the raw marker
  verbatim to the user. `SummaryProseGenerator:119` intercepts + degrades
  ("never surface the marker"); chat needs the same. security_relevant.

**PHASE 5 IN PROGRESS (2026-07-04, this session):** the two core automated
objectives are GREEN — see the running-log entry for full detail:

- **3 Signal accounts registered** (bot / admin / user) on smspva-rented
  numbers via the image-baked signal-cli 0.14.5; **registration-lock PIN set
  on all three** (mandatory for rented numbers — blocks takeover when the
  number recycles; PIN + numbers live ONLY in gitignored
  `prod/runtime/signal-private.env`, driven via the masking wrapper
  `prod/runtime/signal-run.sh` so numbers never hit argv/transcripts/logs).
  Data-dirs: bot `prod/runtime/signal-cli`, clients
  `prod/runtime/signal-clients/{admin,user}`. The user client is
  harness-drivable (3rd rented number), so the "user leg is phone-only"
  constraint is LIFTED.
- **Adapter live in dual mode:** `infochat.adapters=simplex,signal` +
  signal block in `prod/runtime/application.properties`;
  `INFOCHAT_SIGNAL_{ADMIN_CONTACT_ID,DATA_DIR}` in secrets.env (compose
  passthrough + mount pre-existed). Provider recreated: SignalConfig
  validate passed, daemon endpoint 127.0.0.1:7654, readiness UP,
  `adapter_connection_status{adapter="signal"} 1.0` AND simplex 1.0.
- **ACI-admin bootstrap VERIFIED (D50 Signal leg):** `AdminBootstrap`
  logged `bootstrap admin ensured: adapter=signal`; `users` row
  (adapter=signal, contact_id=admin ACI, is_admin=t).
- **bot↔admin ROUND-TRIP GREEN:** `/help` DM over real Signal answered in
  ~7 s with the admin-tier command list (sender recognized as admin);
  reply arrived sealed-sender from the bot's ACI, delivery receipts flow.

**LATER THE SAME SESSION (see the evening running-log entry):** user leg
GREEN (S4 rejection + S3 invite mint→consume + vouch); dual-adapter
`/zcash` + LLM-chat test GREEN on both adapters; **F-live-10 found
(HIGH): Signal group inbound dead on real wire** (`groupV2` vs
`groupInfo` wire-shape mismatch; membership arrays likewise absent) —
s07-over-Signal is BLOCKED on its fix ticket. 3-party group fixture
built and ready (invite-link path; direct-add unusable on 0.14.5).

**NEXT:** (1) draft + run the F-live-10 fix ticket; (2) after the fix +
image rebuild: s07 group flow over Signal (fixture ready); (3) rest of
§6 checklist (edit fallback, rate pacing, reconnect, 16 KB cap).
Registration lessons: 403 on register = expired
captcha (they die in ~2-5 min; paste-then-fire immediately); verify 404 =
registration session expired (redo register with fresh captcha, paste SMS
code fast); smspva codes appear on the rental row (envelope icon).

**IMAGE REBUILD DONE (2026-07-04 late night, from main @ 56e0a910):**
both app images rebuilt (apps paused for the build per the co-location
rule, detached setsid+marker launch) and restarted collector-first.
Live-verified on the new images:
- **`/q/metrics` 200 on BOTH services** (F-live-7 metrics leg LIVE):
  provider mgmt :8081 serves `adapter_connection_status{adapter="simplex"} 1.0`
  (the §7.14 runbook's first diagnostic), collector :8080 serves
  Prometheus text. The 404 era is over.
- **Refusal intercept deployed** (M1-559/561): `error.chat.refused`
  present in the baked provider bundle (verified via docker cp +
  zipfile — the image has NO `unzip`, remember that for future
  in-container jar checks).
- **Collector boot clean through a 1395-row RAW backlog** — 0
  SRMSG00034 (M1-551 gate holding), readiness UP both services, all 5
  containers healthy, llamacpp still on the committed reasoning-off
  config.

**M1-560 DONE (merged d9e5c95e, 2026-07-04 late night):**
`LLAMA_ARG_REASONING: "off"` is committed in docker-compose.yml (+ WHY
comment); design notes (07-deployment §7.7.2 D49 bullet,
05-llm §max-tokens sizing invariant) document the flag; operator note on
both surfaces (SETUP_GUIDE step 4 + 4-llm.sh custom-GGUF prompt echo).
Host validation from the COMMITTED compose file: force-recreate with no
override (`config_files` label = docker-compose.yml alone,
`LLAMA_ARG_REASONING=off` in env) → live DM delivered a non-empty
625-char reply in ~150 s (prev. 4/4-failing shape; prose quality still
the abliterated-quant residual, out of scope per D49). **The override
file `prod/runtime/llamacpp-reasoning-off.override.yml` is DELETED and
the trap below is CLOSED** — single `-f docker-compose.yml` is safe
again. F-live-8's serving-config leg is fully resolved; what remains of
F-live-8 is only the operator model-choice concern.
**M1-558 DONE (merged 848559b4, 2026-07-04 night):**
`quarkus-micrometer-registry-prometheus` in both service poms +
endpoint-pinning MetricsEndpointITs (provider IT pins
`adapter_connection_status`); F-live-7's metrics leg is code-resolved —
the `/q/health/llm` leg was out of scope and still needs its own ticket.
**M1-559 DONE (merged 509f7a6c) + M1-561 DONE (merged 5c998451), both
2026-07-04:** F-live-9 is code-resolved — ChatAgent intercepts the D21
refusal marker before sanitize/persist/delivery (deterministic
`error.chat.refused` bundle reply en+cs, null commit, D37 userId-only
WARN). M1-561 remediated the M1-559 redteam out-of-model item: the
intercept now runs on the POST-stripToolCalls text with a PREFIX-ONLY
predicate (`startsWith("[REFUSAL:")`, no endsWith conjunct) — the
in-branch redteam audit showed strip's unbalanced-fragment drop-through
eats the marker's closing `]` (`[REFUSAL: TOOL_CALL: foo]` →
`[REFUSAL: `), so two-sided anchoring leaks; prefix-only also catches
an unterminated marker. Re-audit CLEAN
(docs/plan/m1/redteam/M1-561-2026-07-04{,-reaudit}.md). Two advisory
out-of-model residuals recorded there (mid-prose marker delivery —
deliberate anchored-not-substring design; Unicode-lookalike prefix
evasion) — no tickets unless the marker-suppression guarantee gets
promoted to spec level.

**~~⚠ Compose-override trap~~ CLOSED by M1-560 (2026-07-04 late night):**
the flag lives in the committed docker-compose.yml, the running llamacpp
was recreated from it (provenance-pinned), and the override file is
deleted. Compose commands are back to the single-file form:
`docker compose -f docker-compose.yml --env-file prod/runtime/secrets.env --profile prod <cmd>`
(the `--env-file` remains REQUIRED — DB secrets live in secrets.env).

**Fixture state after this session:** LiveAdmin CLAIMED (`is_admin=t`,
vouched), admin-DM → m1-537-seed-source `source_subscription` row present,
`chat_message` has 8 rows from today's drives. Group/LiveUser fixtures NOT
rebuilt (4b-4 didn't need them — rebuild per the recipes below before any
future group-scenario drive).

---

## ▶ previous START HERE (2026-07-04 morning, kept for context)

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

Phases 0–5 ALL DONE and ALL live findings CLOSED (F-live-11 fixed by
M1-566 @ 96f6ef09, host-validated 2026-07-04: live /summary edits in
place, latest-revision chain proven, zero fallback_send). Phase 6
(/testcase skill) optional. Board: 590 done / 0 pending. Only
observation left: F-live-12 (low, self-healing startup noise).

## Where we are

| Phase | What | Status |
|---|---|---|
| 0 — Framing & decisions | targeting principle, transport strategy | ✅ DONE |
| 1 — Coverage audit | 15 scenarios; 7 transport-relevant, 0 logic gaps | ✅ DONE |
| 2 — Load-bearing assumptions | admin-token re-arm, SSRF strict, FK trap, caps | ✅ DONE |
| 3 — Reset & data harness | `prod/live-reset.sh`, `live-seed.sh`, `live-inject-adversarial.sh` | ✅ DONE (M1-536/537/538) |
| **4a — scenario runner substrate** | `Scenario`, `ConversationBackend` SPI, `ScenarioRunner`, InMemory backend, `ScenarioRunnerIT` | ✅ DONE (M1-539) |
| **4b — SimpleX live drive** | real simplex-chat drive + LLM latency + embedding retrieval | ✅ DONE — all 7 scenarios GREEN 2026-07-03; **4b-4 assertions DONE 2026-07-04** (retrieval GREEN over ollama; metrics gap = F-live-7) |
| **5 — Signal delta** | round-trip + ACI bootstrap + §6 differences | ✅ DONE 2026-07-04 — round-trip, ACI bootstrap, user leg (S3/S4), dual-adapter, s07 group flow, and the FULL §6 checklist (cap/pacing/reconnect/edit) all verified live; produced F-live-11 (edit RPC bug, fix ticket pending) |
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
4. [x] **4b-4** — DONE 2026-07-04 (see §START HERE): latency evidence stands;
       retrieval assertion GREEN over ollama (reframed — SQL-via-tool per D19,
       not RAG); readiness/liveness UP; LLM-down degraded verified live;
       per-adapter metrics = **F-live-7 gap** (`/q/metrics` 404, no
       Prometheus registry dependency).
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

### F-live-7 (MEDIUM, observability) — metrics leg RESOLVED by M1-558 and LIVE-VERIFIED post-rebuild (2026-07-04 late night: /q/metrics 200 both services, adapter_connection_status exported); /q/health/llm leg still open
**RESOLVED (metrics leg):** `quarkus-micrometer-registry-prometheus` added
to both service poms (BOM-managed) + two new `MetricsEndpointIT`s pinning
`GET /q/metrics` (provider IT additionally pins `adapter_connection_status`
— the §7.14 runbook's first diagnostic is now testable). The ITs assert the
main test port (shipped defaults enable no management-interface split; the
ticket's clarity WARN resolution). NOTE: the running live images predate
the merge — live `/q/metrics` stays 404 until the next image rebuild. The
`GET /q/health/llm` leg was explicitly out of scope and still needs its own
ticket. Original finding:

Found by the 4b-4 per-adapter-metrics check (2026-07-04): `/q/metrics`
returns 404 on the provider (mgmt :8081) and collector (:8080). Root cause:
every module ships `quarkus-micrometer` (CDI `MeterRegistry`; AdapterMetrics
/ LlmMetrics register meters) but NO module declares
`quarkus-micrometer-registry-prometheus`, so there is no export endpoint —
the meters are write-only. Design 07-deployment.md:1059 promises
`GET /q/metrics` (Micrometer/Prometheus); spec deployment.md §Health says
per-adapter connection state "is exposed separately via metrics" — currently
unreachable by any operator. The design's `GET /q/health/llm` (:1058) is
likewise unimplemented (404, no code references). Green in CI because no
test asserts either endpoint exists. Fix shape: add the registry-prometheus
dependency to both service poms (+ a readiness-style IT pinning the
endpoint); decide /q/health/llm separately.

### F-live-8 — serving-config leg RESOLVED by M1-560 (merged d9e5c95e, 2026-07-04); model-choice residual stays an operator concern
**ROOT-CAUSED 2026-07-04 afternoon: llama.cpp reasoning auto-detect, NOT
model flakiness. M1-560 committed `LLAMA_ARG_REASONING=off` in compose,
host-validated from the committed file, override file deleted.** Direct probes against the running
server found the mechanism: the Gemma 4 template has an optional thinking
channel (`<|channel>thought…<channel|>`), llama.cpp's `--reasoning` default
`auto` detects it and turns thinking ON, so every task has been thinking
since deploy and the M1-548 caps (sized for visible output) get eaten by
thought tokens — 3/3 probes at default temp put ALL 200 tokens in
`reasoning_content` with EMPTY content. With `LLAMA_ARG_REASONING=off`:
3/3 probes clean at default temperature (temperature exonerated) and a
live DM round-trip delivered a 438-char reply in ~90 s (content quality
still mediocre — abliterated-quant residual, operator model-choice
concern). **HOST STATE: the running llamacpp has the flag active via a compose
override** (applied 2026-07-04 ~11:45 for the verification and KEPT — chat
is unusable without it; relocated to
`prod/runtime/llamacpp-reasoning-off.override.yml`, see §START HERE trap
note); M1-560 makes it repo-permanent in docker-compose.yml. Expected side benefit: eval tasks
stop paying the hidden thinking tax (faster RAW-backlog chew). Original
finding below, kept for the record; its "sampling flakiness" framing is
superseded.

### F-live-8 original record (HIGH for chat UX, model/product) — shipped host chat model unreliable at chat-format output
2026-07-04: 4/4 chat-mode turns against the baked
`gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf` (llama.cpp, peg-gemma4 format)
failed: two returned llama.cpp 500 "The model produced output that does not
match the expected peg-gemma4 format" (decode ran to the 600 cap emitting
`<|channel>`-marker garbage / degenerate repetition); one PARSED but with
EMPTY content — all 600 cap tokens burned in a non-content channel, and the
empty reply was DELIVERED as a blank DM (client DB item 109); one 500'd
within ~8 s on an immediate malformed channel token (also under a 1200-token
experiment cap, so "cap too small" alone doesn't explain it). This
contradicts the F-live-6 note "no thinking channel" — the live outputs show
channel-structured emissions. Yesterday's s12 GREEN (600 tokens of plain
prose truncated at the cap, ≥80 chars delivered) was a favorable sample, not
proof of reliability. Contributing knob: `OpenAiCompatibleProvider` sends no
`temperature`, so llama.cpp's default (~0.8) applies — high for a Q4
abliterated 4B-class model. Follow-up options (operator/model tier): pick a
better-behaved default GGUF, and/or a per-task `temperature` key in
TaskConfig (F-live-6 / M1-548 family). The friendly-error path behaved
correctly on every failure (bundle reply delivered, D37 cause chain logged).

### F-live-9 (MEDIUM, security-relevant) — RESOLVED by M1-559 (509f7a6c) + hardened by M1-561 (5c998451), both 2026-07-04
**RESOLVED:** ChatAgent intercepts the D21 marker before
sanitize/persist/delivery (deterministic `error.chat.refused` bundle
reply en+cs, null pendingCommit — nothing persisted, D37 userId-only
WARN). M1-561 (remediation of the M1-559 redteam out-of-model item)
moved the check to the post-stripToolCalls text and hardened the
predicate to prefix-only after an in-branch redteam FINDINGS→refine→
CLEAN cycle showed two-sided anchoring leaks when strip eats the
marker's closing `]`. Live from the next image rebuild (running
provider image predates both). Original finding:

Live ollama turn (2026-07-04): a benign question ("Tell me about the recent
security advisory from my sources") made `llama3.2:3b` emit the D21
structured refusal — and the user received the raw protocol string
`[REFUSAL: unable to assist with untrusted source information]` as the bot
reply (chat_message seq 3 + client DB). `ChatPromptBuilder:44` instructs the
marker, but the chat path has no interception; `SummaryProseGenerator:119`
intercepts the same marker and degrades, with a comment citing security.md
§Prompt-injection defenses "never surface the marker … to the user". The
chat agent needs the same intercept (friendly bundle reply + log). Also
notable: the refusal itself was spurious (3B model over-triggering on the
untrusted-content framing) — a model-quality data point for F-live-8's
default-model discussion.

### F-live-11 (MEDIUM, adapter bug) — RESOLVED by M1-566 (merged 96f6ef09, 2026-07-04) and HOST-VALIDATED same night

**RESOLVED:** DM+group edit frames re-encoded as `send`+`editTimestamp`
(encoders renamed encodeEditSend/encodeGroupEditSend); each successful
edit's response timestamp refreshes the stored handle so edit chains
target the LATEST revision; fakes/tests reconciled with edit-vs-fallback
keyed on `editTimestamp` presence, fallback suite coverage intact.
Host validation GREEN post-rebuild: live `/summary -w 48h` (signal user
DM) = 1 placeholder dataMessage + 2 editMessage revisions (second edit
targeted the first edit's timestamp — two-hop chain proven), metrics
`ok 2 / coalesced 3`, NO fallback_send or update_fail counters.
Original finding below, kept for the record:

### F-live-11 original record (MEDIUM, adapter bug, UX-degrading not correctness) — Signal edit path never edits on real wire; fallback masks it

Found by the §6 edit-fallback probe (2026-07-04 late night): the Signal
adapter encodes message edits as JSON-RPC method `updateMessage`
(`SignalMessageCodec` DM+group variants), but **signal-cli 0.14.5 has no
such command** — its command list has `send` (with `--edit-timestamp`),
`updateAccount/Contact/Device/Group/Profile`, no `updateMessage`. The
daemon returns method-not-found, categorized PERMANENT (non-`-32603`) →
the designed fresh-send fallback fires on the FIRST edit of every
handle and the handle falls back for good. Green in CI because the
fakes accept `updateMessage` — D-live-9 thesis, third strike (after
F-live-1 and F-live-10).

Live evidence (all 2026-07-04, /summary progress flow over real Signal):
- User client received `Working on it...`, `Translating...`, and the
  final summary as THREE separate dataMessages (no editMessage
  envelopes); typing STARTED/STOPPED correct.
- Metrics after one flow: `adapter_outbound_update_fail_total{reason=
  "unknown"} 1`, `outcome="fallback_send" 2`, `outcome="ok" 2`,
  `outcome="coalesced" 3` — exactly the update→fail→fallback,
  finalize→direct-fallback sequence the code prescribes.
- **Fix shape PROVEN live client-to-client:** jsonRpc `send` with
  `editTimestamp:<original ts>` → recipient renders a true edit
  (`Edit: Target message timestamp: …`, new body). The fix is to
  re-encode DM+group edit as `send`+`editTimestamp` and reconcile the
  fakes with the real method surface.

Impact: correctness is preserved (content always delivered, fallback
rate-paced per M1-359), but every progress flow degrades to N separate
messages instead of one evolving message, and each fallen-back op costs
2 wire frames. `supportsMessageEdit=true` stays truthful — Signal DOES
support edits; only the RPC encoding is wrong. Ticket candidate.

### F-live-10 (HIGH for Signal groups, adapter bug) — RESOLVED by M1-562 (merged 58767406, 2026-07-04)

**RESOLVED:** dual-shape parse (`groupInfo.groupId` first, `groupV2.id`
retained for route symmetry), membership surface removed +
`supportsMembershipEvents=false` (spec-mandated; delivery-failure
fallback per the SimpleX posture). Live from the 2026-07-04 night image
rebuild. Redteam CLEAN pre-commit; the two advisory out-of-model items
became M1-563 (leave-cleanup spec posture) and M1-565 (group-id shape
gate, nice-to-have). Original finding below, kept for the record:

Found by the Phase-5 group leg (2026-07-04): every real Signal group
message is silently dropped. `SignalGroupHandler.handleReceive` gates on
`dataMessage.get("groupV2")` and reads `id`, but signal-cli 0.14.5 emits
the group stanza as `groupInfo` with `groupId`:

```
"dataMessage":{..., "mentions":[{"name":"+…","number":"+…","uuid":"<aci>",
"start":0,"length":4}], "groupInfo":{"groupId":"<base64>",
"groupName":"live-signal-group","revision":10,"type":"DELIVER"}}
```

(live envelope, captured via bot-CLI `--output=json receive` with the
provider stopped). The first guard returns → spec-permitted silent drop
→ invisible in logs. Green in CI because the fakes emit the assumed
`groupV2` shape — D-live-9 thesis, Signal edition. The DM codec's
group-exclusion guard (`SignalMessageCodec:235`) checks BOTH `groupInfo`
and `groupV2`, so the real spelling was known on the DM side; the group
parser was never reality-reconciled. Second leg: 0.14.5's `groupInfo`
carries NO `memberJoined`/`memberLeft` arrays, so the membership-event
dispatch (and the `supportsMembershipEvents=true` capability) is
unfulfillable as coded — the fix ticket must either derive deltas
(revision-diff via listGroups) or flip the capability. `mentions[]`
shape (uuid/start/length) matches the parser's expectation — only the
group stanza is wrong. DMs unaffected. s07-over-Signal blocked until
fixed; the 3-party group fixture is already built and waiting.

### F-live-12 (LOW, operational noise, self-healing) — reEmitStaleRaw scheduled task hits SRMSG00034 at startup under RAW backlog

Observed on the 2026-07-04 22:23 collector boot (first boot of the
M1-566 image, 1090-post RAW backlog): the M1-551 rehydrator gate held
(re-enqueue clean), but the `Stage1Worker#reEmitStaleRaw` SCHEDULED
task (IntervalTrigger 300000 ms) also fired ~4 s after boot, emitted
into the buffer the rehydrator had just filled, and threw
`SRMSG00034` (2 ERROR-level stack traces via StatusEmitterInvoker).
Self-healing by design: the scheduler retries in 5 min, the outbox is
at-least-once, the collector stayed healthy, and the NEXT boot (22:31,
1089 backlog) was 0-SRMSG00034 clean — the race is lost only when the
tick lands inside the post-rehydration saturation window. Impact is
log noise (ERROR + stack) that could page an operator. Possible fix
shape if ever ticketed: route reEmitStaleRaw's emits through the same
per-emit readiness gate M1-551 gave the rehydrator, or delay the
task's first execution past startup. NOT ticketed — user's call.

## Running log

### 2026-07-04 late night, LATEST +1 (LLM backend latency comparison — user-requested; DeepSeek decision recorded)

- **Four-leg latency comparison over real Signal** (same 3 chat
  questions + `/summary -w 96h` from the user DM per leg; per-turn
  deltas from wire envelope timestamps; collector paused throughout;
  adaptive FIFO-fed jsonRpc driver in `.scratch/llmcmp-*.sh`):
  | turn | llamacpp gemma-4 | ollama llama3.2:3b | ds-v4-flash (think ON) | ds-chat alias (think OFF) |
  |---|---|---|---|---|
  | q1 short | 32.8 s | 46.2 s (tool loop) | 4.9 s | 3.6 s |
  | q2 tool loop | 29.6 s | 22.1 s (refusal) | 7.0 s | 3.2 s |
  | q3 ~100 words | 31.5 s | 22.2 s (refusal) | 3.3 s | 1.7 s |
  | summary final edit | 21.3 s | 19.9 s | 7.5 s | 4.7 s |
  Correctness: DeepSeek 4/4 correct on BOTH modes; gemma garbage (D49);
  llama3.2 spurious-refused 2/3. History caveat: chat_message not
  cleared between legs (prod-DB access needs user approval), ordering
  llamacpp→ollama→deepseek means the DeepSeek gap is understated.
- **DeepSeek naming facts (API-verified):** `deepseek-chat` alias is
  SERVED BY `deepseek-v4-flash` (response `model` field) with thinking
  OFF; explicit `deepseek-v4-flash` = same engine, thinking ON
  (default; `thinking:{"type":"disabled"}` request param exists,
  `reasoning_effort` high|max). Aliases `deepseek-chat` /
  `deepseek-reasoner` are DEPRECATED 2026-07-24 — after that the app
  can only reach v4-flash with thinking ON (OpenAiCompatibleProvider
  sends only model/messages/max_tokens; no extra-body config).
- **USER DECISION (2026-07-04): live with reasoning-on.** 3–7.5 s
  turns are acceptable and quality was 4/4 correct, so NO
  thinking-parameter ticket is drafted. If that ever changes, the
  sized change is: TaskConfig field + getOptionalValue + putObject in
  OpenAiCompatibleProvider (~10 impl lines, wire-pinning test trio,
  05-llm design note; M1-548 pattern; AnthropicProvider out of scope —
  different thinking wire shape).
- Everything reverted after: application.properties byte-identical to
  the llamacpp baseline (verified via diff), provider+collector
  restarted healthy, ollama container removed (`infochat-ollama`
  volume KEEPS llama3.2:3b), all 5 containers healthy at session end.

### 2026-07-04 late night, LATEST (M1-566 run+merge; rebuild; F-live-11 host validation GREEN — live-e2e plan complete)

- **M1-566 MERGED (96f6ef09)** via `/m1-tick run M1-566` — the
  F-live-11 fix. Full cycle: clarity PASS (0/0) → implement (exactly
  the 6 files_scope files; adapter module 315/0 green first) → full
  verify green r1 (stack paused per the co-location rule, detached
  setsid+marker, DevServices leaks cleaned, 227 provider tests) →
  review r1 APPROVE (all 6 checks PASS, 0 rework) → redteam skipped
  (security_relevant false) → verify-reuse user-approved → commit
  1ea4370e → squash-merge 96f6ef09. Board: 590 done / 0 pending.
- **Images rebuilt from main @ 96f6ef09** (apps paused, detached
  build, BUILD_EXIT=0; collector cc60783f47de, provider 2e1925dd35e1),
  restarted collector-first: readiness UP both, gauges 1.0 both
  adapters.
- **Host validation GREEN (the ticket §Notes recipe):** single
  held-open jsonRpc session (user role; recipe = docker run with
  signal-private.env + a mounted probe script piping the send request
  then `sleep 300` into `signal-cli jsonRpc` — no number touches argv)
  sent `/summary -w 48h` to the bot and captured the reply envelopes:
  profile-key null-body dataMessage, placeholder dataMessage
  (`Working on it...`, ts …932112), typing STARTED, editMessage
  targeting …932112 (`Translating...`, ts …954267), editMessage
  targeting …954267 (final summary — the LATEST-REVISION chain, second
  hop live-proven), typing STOPPED. ONE user-visible message, edited
  in place; zero extra dataMessages. Metrics after the flow:
  `adapter_outbound_update_total ok 2 / coalesced 3`, NO
  fallback_send, NO update_fail (pre-fix: `update_fail 1,
  fallback_send 2`). Collector paused for the LLM-bound turn and
  restarted after (22:31 boot clean, 0 SRMSG00034).
- **New observation F-live-12** (see §Live findings): the 22:23 boot's
  `Stage1Worker#reEmitStaleRaw` startup tick lost the buffer race under
  the 1090 RAW backlog — 2 ERROR SRMSG00034 traces, self-healed;
  rehydrator gate (M1-551) held on both boots. Not ticketed.
- Stack at session end: all 5 containers healthy on the new images.
  **Every phase and every live finding is closed; Phase 6 (/testcase)
  remains the only optional item. Not pushed (~22 commits ahead).**

### 2026-07-04 late night (§6 checklist walked — Phase 5 DONE; F-live-11 found)

- **Probe tooling that worked (reuse):** single signal-cli `jsonRpc`
  stdin session via `docker run -i --env-file signal-private.env`
  (recipient = bot ACI, so no number on argv; output sed-masked). TWO
  GOTCHAS: (1) an open jsonRpc session RECEIVES AND ACKS incoming
  envelopes — hold it open (`sleep N` after the sends, capture stdout)
  or the replies are consumed invisibly; (2) `receive --timeout N
  --output=json` sometimes returns empty where the plain `receive`
  form yields the envelopes — prefer plain + grep for assertions.
- **16 KB cap probe:** 20,431-byte DM from user accepted by the Signal
  server/relay chain and delivered to the daemon — the cap is genuinely
  reachable on real wire. Provider WARN (exact): `inbound dropped —
  exceeds 16384-byte size cap; from contact#018922f0 adapterMessageId
  signal-1783198405395`. No reply to sender; no body content logged.
- **Rate-pacing probe:** 8 `/help` sends in one jsonRpc session landed
  at the bot within 11 ms; replies: 8 frames in ~1.6 s / ~1.7 s (two
  trials) = sustained 5/s, mean gap ≈200 ms (limiter signature). Min
  observed gap 119 ms + sliding-window counts of 6–7 = daemon-side
  timestamp jitter downstream of the blocking limiter, NOT a cap
  breach. Bonus: burst-2's replies (missed by its own session) were
  server-queued and delivered on burst-3's connect IN ORDER, zero
  duplicates (16/16) — async-receive/ordering evidence.
- **Reconnect probe:** `kill -9` of the in-container daemon (PID via
  `ps | grep 'daemon --tcp'`) → WARN `signal-cli subprocess exited
  (code=137); scheduling restart 1/5 in 153 ms` → INFO `Signal adapter
  reconnected after subprocess restart` 4.7 s later (new PID), gauges
  1.0, `/help` round-trip green.
- **Edit/typing probe (the F-live-11 discovery):** chat turn = single
  frame (correct — ProgressNotifier's only consumer is
  SummaryCommandHandler; the Explore recon's "chat-mode progress"
  attribution was wrong, verified in source). /summary with data
  (user-approved `source_subscription` INSERT: signal user DM scope →
  m1-537 seed; `-w 48h` because the seed posts are ~31 h old) produced
  placeholder + typing STARTED + 2 stage bodies + typing STOPPED — all
  as separate dataMessages, ZERO editMessage envelopes. Metrics:
  `update_fail{reason="unknown"} 1`, `fallback_send 2`, `ok 2`,
  `coalesced 3` = first-edit fail → permanent fallback. Root cause:
  **no `updateMessage` in signal-cli 0.14.5's command list**; fix shape
  proven live (user→admin jsonRpc `send`+`editTimestamp` rendered as a
  true edit on the admin client). Full finding: §F-live-11.
- Collector paused for both LLM-bound turns and restarted after; stack
  healthy at session end.
- **M1-566 DRAFTED (6334e8fa)** — the F-live-11 fix ticket
  (docs/plan/m1/tickets/M1-566-signal-edit-real-wire-encoding.md):
  re-encode DM+group edit frames as `send`+`editTimestamp` (rename
  encode methods to match), refresh the handle timestamp from each
  edit response so chains target the LATEST revision (dominant under
  either chain semantic; single hop live-proven), reconcile the three
  test files' fakes to the new frame shape WITHOUT weakening the
  fallback suite (2-frame accounting, correlationId reuse, fellBack
  latching all preserved). 6 files, complexity low, round_cap 2, not
  security-relevant. Host-validation recipe in the ticket §Notes
  (post-merge rebuild: live `/summary -w 48h` from the signal user must
  edit in place with zero `fallback_send` increments).

### 2026-07-04 late night (image rebuild + s07 GREEN over Signal)

- **Image rebuild (user decision: rebuild before s07 so the run
  exercises the merged code):** both app images rebuilt detached from
  main @ c4e7093e (BUILD_EXIT=0, apps paused per the co-location rule),
  restarted collector-first — readiness UP both, gauges 1.0 both
  adapters, 0 SRMSG00034, containers verified on the fresh image IDs.
  **M1-565 shape gate now LIVE.** Doc sync committed (ca247331):
  HANDOFF rebuild-pending closed; README §6 membership-events rows
  reconciled with M1-562/563 (table said Signal `true` — stale).
- **s07 drive detail (all GREEN, first try, no code changes needed):**
  group id from admin `raw listGroups`
  (`/tNyW6lovgkfmcXVosMngNAlJmFZBH8DC/HTWU57aT4=`, 32 bytes decoded —
  in the [16,64] M1-565 band); user-leg sends via
  `signal-run.sh user raw 'send -g "<gid>" -m "@bot /help" --mention
  "0:4:<botAci>"'` — signal-cli accepts the ACI as the mention
  recipient, and `SignalMentionParser` strips the span and extracts
  the command exactly as coded. Replies read via `user|admin receive`
  (bodies matched the scenario expects verbatim). DB evidence:
  `groups` 99b4e0a5-… pending→approved with `activated_by`;
  `group_membership.is_group_admin=t` after step 4; step-5
  `/group-timezone` succeeded for the non-bot-admin user.
- **Non-finding (do NOT re-chase):** no admin DM for the pending-group
  notification is EXPECTED — `ThrottledAdminNotifier` javadoc:
  "Delivery today is the WARN log line + the persisted row"; push SPI
  out of scope. The group reply's "administrator has been notified"
  wording refers to that log+row leg.
- Stack at session end: all 5 containers healthy on the new images,
  dual `simplex,signal` mode live, `live-signal-group` now an APPROVED
  group with the user as group admin (remember this state when
  planning future group-scenario drives — a re-run of s07 needs the
  groups row cleared or a fresh group).

### 2026-07-04 night (M1-565 merged — base64 group-id shape gate; board drained)

- **M1-565 MERGED (8495c882)** via `/m1-tick run M1-565` — the last
  Phase-5 ticket (M1-562 redteam out-of-model item 2). Shared
  `SignalMessageCodec.isAcceptableGroupId`: strict `Base64.getDecoder()`
  decode + a [16,64]-byte length BAND (band-not-pin WHY comment — live
  id 32 bytes, fixtures 20; an exact pin is the F-live-10
  overstrict-assumption failure in reverse), mirroring `isAcceptableAci`.
  `SignalGroupHandler.extractGroupId` gained the envelope param so it can
  emit, on a gate-reject, an observable value-free WARN carrying only the
  encoded id LENGTH + the `signal-<ts>` adapterMessageId token (never the
  id value — D37, it names a private group); a rejected frame drops
  instead of the F-live-10-style silent drop. Exactly 4 files
  (files_scope): codec + handler + the two group tests
  (`malformedGroupIdDroppedWithObservableWarn` via the existing
  `CapturingLogHandler`; garbage/empty/oversize robustness cases).
- **Cycle notes worth keeping:** clarity PASS (0/0). **Verify RED on r1,
  GREEN on the re-run** — the r1 failure was
  `MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal` "no outbound
  JSON-RPC within 2000 ms", the documented co-location flake (M1-556
  family): it probes Signal *outbound*/DM liveness, causally disjoint
  from a group-*inbound* diff, and the messaging-adapter module (mine)
  was green — a clean-host re-run passed 227/0 untouched, confirming the
  flake diagnosis rather than a real regression. Redteam CLEAN (2
  advisory out-of-model, both gated on a FUTURE signal-cli
  trust-boundary redraw: (1) `Base64.getDecoder()` isn't RFC-strict and
  the raw string, not a canonical decode-re-encode, becomes the scope
  key — a distrusted channel could fragment one group into multiple
  scope keys; canonicalize if the boundary is ever redrawn; (2) the
  shape-gate WARN is per-frame and upstream of every rate cap — revisit
  log-rate bounding on the same redraw. No tickets filed — advisory
  only; recorded in `docs/plan/m1/redteam/M1-565-2026-07-04.md`). Review
  APPROVE r1 (0 rework, all 6 checks PASS).
- **Verify protocol held:** collector+provider paused for both verify
  runs (the stop was denied once by the auto-mode classifier reading the
  earlier "stop before redteam" as a scope limit → user-approved on
  re-ask), detached setsid+marker launches, stack restarted after
  (all 5 containers healthy). **Running provider image predates the
  merge → the shape gate is code-resolved, not yet live; fold the
  rebuild into the next one. s07 does NOT depend on it.**

### 2026-07-04 night (F-live-10 fixed — M1-562/563/564/565; rebuild; stack live on new images)

- **M1-562 MERGED (58767406)** via `/m1-tick run M1-562` — the F-live-10
  fix (see the finding entry above for the shape). Cycle notes worth
  keeping: clarity FAIL → 1 bounded self-refine (test-ledger bullet);
  TWO escalations, both user-refined: premise-fail (drafted acceptance
  item cited a SignalMessageCodec comment that doesn't exist — lesson:
  read the exact line before pinning it in acceptance, don't trust a
  survey subagent's paraphrase) and budget-breach (removing the
  constructor's membershipHandler param reached 4 out-of-scope call-site
  tests + orphaned RecordingMembership; scope grew 11 → 16 files).
  Verify green r1 (stack paused per the co-location rule), review
  APPROVE r1, redteam CLEAN (2 out-of-model advisories), squash-merged.
- **M1-563 drafted (pending):** spec amendment stating the leave-cleanup
  NON-COMMITMENT on membership-event-less adapters (out-of-model item 1
  — the per-user delivery-failure fallback can't fire: group sends are
  group-addressed; implemented fallback covers bot-removal only;
  /demote is the remediation).
- **M1-565 drafted (pending, user-accepted nice-to-have):** band-bounded
  base64 shape gate on the group-id scope key (out-of-model item 2),
  mirroring isAcceptableAci; bounds are a [16,64]-byte band, NOT an
  exact 32-byte pin (fixtures decode to 20 bytes; exact pins are the
  F-live-10 failure mode in reverse), with an observable value-free WARN.
- **Rebuild blocker found + fixed — M1-564 (merged 1f6299d6):** first
  rebuild since Phase 5 provisioning failed at context prep — the
  daemon-created root-owned 700 `prod/runtime/signal-cli/data` can't be
  stat'ed by the classic builder. `.dockerignore` now excludes
  `prod/runtime/` entirely (also keeps secrets.env/signal-private.env/
  identity stores out of the build context; the shipped images were
  always clean — multi-stage copies only quarkus-app out of the build
  stage). Host-validated: the exact previously-failing compose build
  exits 0 (collector 4330b57751d4, provider 75f9b3b6dd09).
- **Stack restarted collector-first on the new images:** all 5
  containers healthy, readiness UP, `adapter_connection_status` 1.0 for
  both adapters. The groupInfo parse is LIVE — s07-over-Signal unblocked.

### 2026-07-04 evening (PHASE 5 cont. — user leg, F-live-10 group bug, dual-adapter LLM test)

- **User leg (automated, 3rd rented number):** S4 twin GREEN — un-invited
  Signal DM → fixed `Access requires an invitation.` reply, ZERO users
  rows created (blocked at intake, never reached LLM/storage; the
  "anyone can message a number" acceptance-model checklist item held).
  S3 twin GREEN — `/invite create --adapter signal --open` is
  confirm-gated exactly like SimpleX, code minted on confirm, user
  consumed it → `Welcome! You're registered.`, users row
  (registration_state=invited, D45 probation set). Admin `/vouch <aci>`
  cleared probation ("User vouched. Probation cleared.").
- **F-live-10 (HIGH for Signal groups, adapter bug — ticket candidate):
  Signal group inbound is DEAD on real wire.** signal-cli 0.14.5 emits
  the group stanza as `dataMessage.groupInfo{groupId,groupName,revision,
  type}` but `SignalGroupHandler` gates on `dataMessage.get("groupV2")`
  and reads `id` — every real group message silently drops at the first
  guard (spec-permitted silent-drop path makes it invisible). The DM
  codec's exclusion guard (`SignalMessageCodec:235`) already knows BOTH
  spellings — the group-side parser was never reality-reconciled
  (D-live-9 thesis, Signal edition). Also: 0.14.5's `groupInfo` carries
  NO `memberJoined`/`memberLeft` arrays, so the membership-event surface
  (`supportsMembershipEvents=true`) is likely unfulfillable as coded —
  the fix ticket must decide (parse both shapes; flip the capability or
  derive deltas by revision-diff). Evidence: live envelope JSON captured
  via bot-CLI `--output=json receive` (provider stopped), in this log's
  probe section. The s07 group scenario is BLOCKED on the fix.
- **Signal group fixture built (for the post-fix run):** 3-party
  `live-signal-group` exists — admin (creator/ADMIN) + bot ACI + user
  ACI all full members. Getting there was rough (all signal-cli 0.14.5
  lessons): direct-add by number ALWAYS degraded to a pending invite in
  our fresh-account matrix (profile names set + profile keys exchanged
  did NOT unlock it); invitee-side accept (`updateGroup -g <id>` bare)
  fails `Cannot find service ID for self to accept invite` for
  number-addressed invites; the WORKING path is a **group invite link**
  (`updateGroup --link enabled` → `joinGroup --uri`) — user joined
  instantly, bot joined the same way with the provider briefly stopped
  (its CLI needs the daemon-locked data-dir). Bot has a profile name
  now ("Jarvis Bot"). Stale number-addressed pending-invite ghosts
  remain on the group (harmless). Bot profile/identity ACIs:
  bot [REDACTED-ACI],
  user [REDACTED-ACI].
- **Dual-adapter live test (user request) — BOTH adapters GREEN on the
  same deployment, same LLM backend:**
  - `/zcash`: Signal admin DM → full price card delivered (~5 s).
    SimpleX LiveAdmin DM → bot received + replied in 1 s
    (itemId 193 `sndRcvd/ok`).
  - LLM chat ("In one sentence, what is Zcash?"): Signal → user+assistant
    chat_message rows committed atomically, reply delivered (~2 min,
    collector paused per the s12 contention lesson). SimpleX → same
    (1878-token prefill 70 s + decode; reply `directSnd/sndSent`).
    Reply CONTENT was abliterated-quant garbage on BOTH — consistent
    cross-adapter, confirming the residual is model-side (D49 operator
    concern), not adapter-side.
- **False-alarm lessons (do NOT re-chase):**
  `SimpleXWebSocketClient "frame ignored: unknown-resp-type"` DEBUG
  lines at message-arrival time are BENIGN (send-status update events
  the codec deliberately ignores) — NOT evidence of dropped inbound.
  The simplex one-shot client (`-e "@Contact msg" -t N`) does NOT
  reliably render inbound replies; verify via the bot-side chat DB
  instead: `.scratch/WsProbe.java` (host-compiled java.net.http WS
  probe, docker-cp'd class into the provider container) sends corrId
  commands like `/tail @LiveAdmin 3` to the subprocess WS :5225 —
  command responses arrive on any connection (async events stay on the
  adapter's), per the `simplex-live-frame-capture` memory.
- Stack at session end: all 5 containers healthy (collector restarted
  after the chat turns), dual `simplex,signal` mode live. Phase 5
  remaining: F-live-10 fix ticket → s07 group scenario over Signal;
  rest of §6 checklist (edit fallback, rate pacing, reconnect,
  16 KB cap); optional 3-party group drive post-fix.

### 2026-07-04 (PHASE 5 — Signal registration + round-trip GREEN)

- **Privacy tooling first (user constraint: numbers must never hit argv,
  transcripts, or logs):** `prod/runtime/signal-private.env` (chmod 600,
  gitignored) holds the 3 numbers + registration-lock PIN;
  `prod/runtime/signal-run.sh` wrapper runs signal-cli from the baked
  provider image with docker `--env-file` (numbers reach the process via
  env, not argv) and masks every `+E.164` in output
  (`sed 's/\+[0-9]{6,15}/+REDACTED/'`) on both streams. Roles: `bot`
  (data-dir `prod/runtime/signal-cli` — the wizard-default path compose
  mounts), `admin` / `user` (`prod/runtime/signal-clients/{admin,user}`).
  Captcha links + SMS codes are non-sensitive (no number inside) and were
  pasted via chat for speed.
- **3 smspva-rented numbers registered** with the image-baked signal-cli
  0.14.5 (`docker run --entrypoint sh` + env-file). Lessons: register
  `[403] Authorization failed` = EXPIRED CAPTCHA (~2-5 min TTL — solve,
  paste, fire within seconds); verify `StatusCode: 404` = registration
  session expired (re-register with fresh captcha, paste the SMS code
  immediately); smspva shows codes on the rental row (envelope icon).
  **Registration-lock PIN set on all 3 right after verify** — mandatory
  for rented numbers (next renter re-registers and seizes the account
  otherwise; lock holds while the account stays active, 7-day lapse
  forfeits). Rentals are now disposable — accounts never need SMS again.
- **Config wiring:** `infochat.adapters=simplex,signal` + signal block
  (binary/data-dir/account/admin) appended to
  `prod/runtime/application.properties` via sourced env (account number
  never echoed); `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID` (admin ACI
  56212115-…, extracted from the admin data-dir accounts.json) +
  `INFOCHAT_SIGNAL_DATA_DIR` added to secrets.env. Compose already had
  the env passthrough + data-dir mount (M1-391 world) — zero compose
  edits needed.
- **Provider recreated (`up -d --wait` single service):** SignalConfig
  eager validation passed, `Signal adapter started; daemon
  endpoint=/127.0.0.1:7654`, `bootstrap admin ensured: adapter=signal`
  (users row adapter=signal, contact_id=admin ACI, is_admin=t — the D50
  Signal pre-seeded-ACI leg VERIFIED), readiness UP,
  `adapter_connection_status` 1.0 for BOTH adapters (dual-mode gauge).
- **ROUND-TRIP GREEN:** admin client `/help` DM → bot replied in ~7 s
  with the ADMIN-tier command list (grant-admin/ban present ⇒ sender
  recognized as the bootstrapped admin). Reply arrived sealed-sender
  from the bot's ACI; delivery receipts flow both ways. signal-cli
  gotchas: a fresh account WARNs "No profile name set" (cosmetic — set
  via updateProfile later if desired); receive-only passes work fine
  through the wrapper (no TTY crash — unlike the simplex one-shot).
- Remaining Phase 5 work: §6 differences checklist walk; optional user
  leg (un-invited-DM rejection → invite mint/consume → minimal 3-party
  group) — the 3rd rented number makes ALL of it automatable, no phone
  needed.

### 2026-07-04 late night, later (IMAGE REBUILD — M1-558/559/560/561 all live)

- **Both app images rebuilt from main @ 56e0a910** (collector+provider
  stopped for the build; detached setsid+marker launch, BUILD_EXIT=0)
  and restarted collector-first per 7-apps.sh order (`up -d --wait`
  collector, then provider; single-file compose + `--env-file`, the
  M1-560 world).
- **Live verification on the new images:**
  - Provider mgmt :8081 `/q/metrics` → **200**, exports
    `adapter_connection_status{adapter="simplex"} 1.0`; collector :8080
    `/q/metrics` → **200** (Prometheus text). F-live-7's metrics leg is
    now LIVE, not just code-resolved.
  - Readiness UP on both services.
  - Collector booted clean through a **1395-row RAW backlog, 0
    SRMSG00034** — second consecutive live proof of the M1-551 gate.
  - `error.chat.refused` bundle key confirmed inside the baked provider
    jar (M1-559/561 refusal intercept deployed). Gotcha: the app images
    ship NO `unzip` — inspect jars via `docker cp` + python zipfile.
- Stack at session end: all 5 containers healthy, llamacpp on the
  committed reasoning-off config. **Only Phase 5 (Signal) remains.**

### 2026-07-04 late night (M1-560 merged — reasoning-off committed, trap closed)

- **M1-560 MERGED (d9e5c95e)** via `/m1-tick run M1-560`. Full cycle:
  clarity PASS (0/0) → implement (exactly the 5 files_scope files:
  compose env key + WHY comment, two design-note mentions, SETUP_GUIDE
  step-4 sentence, 4-llm.sh decision-time echo) → **host validation
  BEFORE review r1** (saved the M1-546/549-style rework round):
  force-recreated llamacpp from the committed compose file alone
  (`config_files` label = docker-compose.yml, no override;
  `LLAMA_ARG_REASONING=off` in env; collector stopped for the turn per
  the s12 contention lesson) → live DM (LiveAdmin → bot) delivered a
  non-empty 625-char reply in ~150 s, persisted (chat_message seq 15)
  AND received by the client (chat_items id 128), llama.cpp clean
  (truncated=0, no 500s) — the previously 4/4-failing shape completes;
  evidence in the ticket body → diff fully INERT (no Java/pom/resources;
  mvn verify N/A per the M1-379 gate, round log notes baseline f471142f)
  → review r1 APPROVE (all checks PASS, 0 rework) → commit 68a3a488 →
  squash-merge d9e5c95e. Board: done=585, pending=0.
- **Host cleanup rider executed:**
  `prod/runtime/llamacpp-reasoning-off.override.yml` DELETED (compose
  change landed + service recreated from it — both rider conditions
  met). The §START HERE compose-override trap is CLOSED; single-file
  compose commands are safe again.
- **One-shot client gotcha (new):** a bare receive pass
  (`simplex-chat -t N` with no `-e`) crashes without a TTY
  (`Prelude.undefined` in System.Terminal.Platform) — silently, when
  output is redirected. Use `-e "/contacts"` (any harmless command) to
  force one-shot mode for receive-only passes.
- Stack at session end: all 5 containers healthy; llamacpp running from
  the committed compose config; collector restarted after the chat turn.
  Reply-prose quality residual (abliterated quant) remains an operator
  model-choice concern (D49) — no ticket.

### 2026-07-04 night, later (M1-559 + M1-561 merged — F-live-9 closed)

- **M1-559 MERGED (509f7a6c)** — ChatAgent refusal-marker intercept
  (F-live-9): anchored check on the tool-loop terminal text, new
  `error.chat.refused` bundle key (en+cs, D43 twin), degrade like the
  unavailable path (null commit, nothing persisted), D37 userId-only
  WARN. Its pre-commit redteam audit was CLEAN with one out-of-model
  item — the pre-strip placement — which was verified real and drafted
  as M1-561 (2908a71e).
- **M1-561 MERGED (5c998451)** — intercept relocated to post-strip via
  `/m1-tick run M1-561`. Full cycle worth recording: clarity PASS (0/0)
  → implement (relocation + 3 mixed-output tests) → verify green r1 →
  review r1 APPROVE → **pre-commit redteam FINDINGS (1 medium
  INFO-LEAK)**: the relocated two-sided anchor is itself leaky —
  strip's unbalanced-fragment drop-through eats the closing `]`
  (`[REFUSAL: TOOL_CALL: foo]` → `[REFUSAL: `), delivering the secret
  prefix; an input the pre-diff check caught (regression) → user chose
  **escalate refine**: predicate hardened to PREFIX-ONLY on post-strip
  text (dual raw+stripped two-sided check rejected — evadable by
  `[REFUSAL: TOOL_CALL: foo]\nTOOL_CALL: bar`); also closes the
  unterminated-marker case → re-implement (+2 tests, 7/7) → verify
  green r2 → review r2 APPROVE (must-shrink convergent, files held 4)
  → **redteam re-audit CLEAN** → commit dc6fd425 → squash-merge
  5c998451. Audit records:
  `docs/plan/m1/redteam/M1-561-2026-07-04{,-reaudit}.md`. Two advisory
  out-of-model residuals (mid-prose marker delivery = deliberate
  anchored-not-substring design; Unicode-lookalike prefix evasion) —
  parked unless marker-suppression becomes a spec commitment. Board:
  done=584, pending=1 (M1-560).
- **Verify protocol held both rounds:** collector+provider paused,
  detached setsid+marker launches (r1 9:51, r2 ~10 min), stack
  restarted after each; all 5 containers healthy at session end.
  Running images predate M1-558/559/561 — rebuild still pending (fold
  M1-560 in).

### 2026-07-04 night (M1-558 merged — /q/metrics real)

- **M1-558 MERGED (848559b4)** — Prometheus metrics export (F-live-7
  metrics leg) via `/m1-tick run M1-558`. Full cycle: clarity WARN
  (acceptance item 2's "on the management interface" contradicted shipped
  defaults — no `quarkus.management.enabled=true` anywhere; resolved by
  asserting the main test port like the readiness ITs, symmetric with the
  collector item) → implement (exactly the 4 files_scope files: the
  registry-prometheus dependency in both service poms, + trimming the
  now-false "No registry exporter extension in v1" comment sentence — an
  orphan the change created; two new MetricsEndpointITs pinning the
  endpoint) → full verify green r1 (12:18, stack paused per the
  co-location rule, detached setsid+marker, DevServices leaks cleaned) →
  review r1 APPROVE (all checks PASS, 0 rework) → verify-reuse
  user-approved → commit 4c4720b8 → squash-merge 848559b4. Board:
  done=582, pending=2 (M1-559/560).
- **Comment nit deferred (outside ticket scope):**
  `AdapterMetricsWiringTest` (~line 107) still says "v1 ships no exporter
  extension" — false since M1-558; the test itself is unaffected
  (delta-based observer reads). Fold into the next ticket touching that
  file (memory note: `adaptermetricswiringtest-comment-nit`).
- **Live impact pending rebuild:** the running images predate the merge,
  so live `/q/metrics` still 404s until the next image rebuild.
- Stack untouched beyond the verify pause/unpause; all 5 containers
  healthy at session end.

### 2026-07-04 evening (remote-LLM leg DONE; images rebuilt — M1-551/552 live-validated)

- **Remote-LLM leg DONE (user provided a DeepSeek key in secrets.env):**
  endpoint decision — the app's remote path is OpenAI-compatible
  (OpenAiCompatibleProvider joins base-url + `/chat/completions`), so
  `base-url=https://api.deepseek.com` + `model=deepseek-chat` +
  `infochat.llm.chat.api-key=${INFOCHAT_LLM_API_KEY}` (compose already
  passes the env through, docker-compose.yml:116/183); DeepSeek's
  `/anthropic` endpoint would target the separate AnthropicProvider — not
  the product's remote shape, unused. **Result: tag-pinned retrieval
  question answered in ~10 s, tool loop + correct grounded reply**
  ("The search found one post … 'Seed: security advisory'") — the same
  assertion the local model needed 3 attempts and a reasoning fix for.
  Reply carried `**bold**` markdown (cosmetic note: LLM prose is rendered
  raw; the plain-text convention is enforced for deterministic strings
  only). Config REVERTED to the llamacpp baseline after the test (chat-only
  -remote is not a wizard shape); re-apply = those 3 property lines.
- **Images rebuilt from main (fdd7…/M1-551+M1-552 now deployed) and
  live-validated:** trigger was F-live-3 breaking its retry-clean pattern —
  THREE consecutive collector boot-loops (SRMSG00034, ~1526 RAW backlog) on
  the pre-M1-551 image. Post-rebuild: collector booted HEALTHY first try
  through the same backlog ("re-enqueued 1526 RAW posts", 0 SRMSG00034) —
  **M1-551's readiness gate live-proven**. M1-552 validated mechanically
  (idle-server DM completed in ~120 s, finished under the cap, no error;
  reply content still hallucinated junk — the F-live-8 abliterated-model
  residual, M1-560's out-of-scope model-choice concern). One
  contention-starved chat timeout (240 s HttpTimeoutException with the
  collector chewing backlog) re-confirmed the s12 isolation lesson on the
  new image.
- **Stack at session end: all 5 containers healthy**, new images, chat on
  the llamacpp baseline, reasoning-off override still active (relocated to
  `prod/runtime/llamacpp-reasoning-off.override.yml`; M1-560 commits the
  flag and retires the file). RAW backlog draining with reasoning off.

### 2026-07-04 later (F-live-8 root-caused + fixed live; M1-558/559/560 drafted)

- **F-live-8 investigation** (user-requested): compared request shapes
  (the tool loop is a TEXTUAL protocol — `runToolLoop` regex-parses plain
  text; no `tools` field ever leaves `OpenAiCompatibleProvider`, so the
  failures were pure model-output pathology), pulled the Gemma 4 chat
  template via `/props` (thinking channel + `strip_thinking` macro +
  `enable_thinking` kwarg), probed directly: default temp 3/3 → ALL tokens
  in `reasoning_content`, content EMPTY; `enable_thinking:false` →
  finish=stop, real content. Server-level fix `LLAMA_ARG_REASONING=off`
  (llama.cpp default is `auto` = detect-from-template) verified: 3/3 clean
  probes + a live bot DM delivering 438 chars in ~90 s. Details in the
  F-live-8 root-cause section above. The override is LIVE on the running
  server (scratchpad compose override, kept); M1-560 commits it properly.
- **Tickets drafted (all pending, clarity not yet run):** M1-558
  (e7daa6d2, F-live-7 — quarkus-micrometer-registry-prometheus in both
  service poms + endpoint-pinning ITs; /q/health/llm and
  Prometheus-in-compose explicitly out of scope), M1-559 (6de7ffea,
  F-live-9 — anchored `[REFUSAL:` intercept on the chat terminal text,
  new error.chat.refused bundle key en+cs, degraded-turn handling,
  content-free WARN log), M1-560 (a28c7ab1, F-live-8 — the compose flag +
  design-note mirrors + host validation; extended 2026-07-04 evening per
  user: operator-visible reasoning-off note in SETUP_GUIDE.md step 4 AND
  at the wizard's custom-GGUF prompt — the curated default GGUF is also
  Gemma 4, so the flag affects the untouched default path too).
- **Prometheus-in-repo question (user):** answered from design
  07-deployment.md §7.13 — the observability stack is deliberately
  operator-deployed, NOT a repo compose service ("Nothing in this
  subsection adds configuration"); the repo's obligation is the /q/metrics
  endpoint (M1-558). Adding an optional `observability` compose profile
  would be a design amendment — parked unless the user wants it.
- 208 collector LLM failures observed in the hour around the earlier
  LLM-down test were 503s from MY llamacpp stop/start window (plus a few
  entity-extraction releases-without-entities — the designed degradation),
  not steady-state errors.

### 2026-07-04 afternoon (4b-4 DONE; ollama backend verified; F-live-7/8/9 found)

- **Admin re-claim + minimal fixture rebuild:** D50 token DM → `is_admin=t`,
  vouched, probation NULL (single users row). Re-inserted the admin-DM →
  m1-537-seed-source `source_subscription` row (owner-role INSERT, scope_id
  = users.id — `AddSourceCommandHandler:155` confirms the dm-scope shape).
  Group/LiveUser fixtures deliberately NOT rebuilt (4b-4 needs none of them).
  Data-plane intact: 2165 READY (all embedded), ~1.55k RAW backlog, 46
  QUARANTINED; `provider_state` survived with a live cursor (M1-549 benefit
  observed: no epoch page-through on any of today's 3 provider restarts).
- **4b-4 sweep** (evidence summarized in §START HERE): readiness/liveness UP
  both services; `/q/metrics` 404 both services → **F-live-7**;
  real-embedding evidence via `embedding_metadata` + 388 LinkingJob semantic
  links; LLM-down check GREEN (llamacpp stopped → friendly bundle reply
  received by the client, `ConnectException > UnresolvedAddressException`
  cause chain in the log, readiness stayed UP, clean recovery).
- **Local-model chat attempts all failed → F-live-8** (4/4: two peg-parse
  500s, one parsed-but-empty blank DM, one fast-500; a 600→1200 max-tokens +
  240→420 s timeout experiment did not help and was REVERTED — prod config
  is back at the M1-550-recommended 240000/600). s12's green was a favorable
  sample; collector stopped/restarted around each attempt per the s12
  contention lesson.
- **Ollama backend verified live (user-requested):** native host ollama
  occupies 127.0.0.1:11434 (loopback-only, so unusable from containers;
  sudo needs interactive auth → couldn't stop it) → started the compose
  `ollama` service (0.30.8) with a scratchpad port override
  (`ports: !override → 127.0.0.1:11435:11434`; container-network reach is
  what the apps use), `ollama pull llama3.2:3b` (the §5.7 vps default),
  pointed ONLY `infochat.llm.chat.{base-url,model}` at it, provider
  restart. Results: turn 1 delivered the raw `[REFUSAL: …]` marker →
  **F-live-9**; turn 2 ("search my posts for security topics") ran a REAL
  2-call tool loop but honestly found nothing (model chose tag `security`;
  the seed post carries only `m1-537-security` — both are in the
  vocabulary); turn 3 (tag pinned) → **retrieval assertion GREEN**: reply
  named "Seed: security advisory", ~20 s wall. Everything REVERTED after
  (chat back on llamacpp/gemma, ollama container removed; the
  `infochat-ollama` volume keeps the model).
- **Remote-LLM leg blocked:** `INFOCHAT_LLM_API_KEY` is a 9-char
  placeholder; no real remote credential on the host. Needs user input
  (endpoint + key); recipe = the ollama leg above with a remote base-url +
  `api-key` prop.
- **Stack at session end:** 5 containers up & healthy, config reverted to
  documented values, collector booted clean (0 SRMSG00034 on both restarts
  today — pre-M1-551 image, race just won). Images still predate
  M1-551/552 — rebuild still pending.

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
