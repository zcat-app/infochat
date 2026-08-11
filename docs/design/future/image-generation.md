# Future — `/image` generation

> **Status: v2 design notes. NOT part of v1. Do NOT implement against this
> without first promoting it to spec.** This file captures decisions made
> during a design conversation so the reasoning isn't lost; it is not a
> commitment, not part of `spec/decisions.md`, and not part of any MVP.
>
> **Created:** 2026-08-05, from the design conversation that followed the
> ComfyUI spike recorded in `docs/plan/future-features.md` §B2. That section
> remains the record of the measured backend; this file is the design layer
> above it.

## Goal

`/image [--ratio|-r <WxH>] [--prompt|-p] "{description}"` → generate an image on
a local backend and return it, or return text explaining why it wasn't possible.
Graceful degradation is the contract, not a fallback.

The framing that governs every decision below: **this is a chatbot with a
picture feature, not an image studio.** Where a constraint would be
uncomfortable for a generation tool and harmless for a chatbot, take the
constraint.

## Decisions settled (v2 candidate)

| # | Decision |
|---|---|
| 1 | Available in **DM and groups**. |
| 2 | **No prompt pre-filter** beyond the free controls (probation block, server-controlled negative prompt, credits, cooldown). |
| 3 | Liability rests on **attribution and operator model choice** — never on model guardrails. |
| 4 | **Auto-translate to English, and echo the prompt actually used.** Provisional, pending a multilingual measurement (§Open). |
| 5 | **Charge a credit on attempt; refund iff the GPU never ran.** |
| 6 | **Per-user AND per-group hourly credits**, in-memory buckets. Restart refills — accepted and stated, not hidden. |
| 7 | **Cooldown ~15 s everywhere**, DM and group alike. |
| 8 | **ComfyUI queue-depth gate** is the technical protection; show queue position, refuse past a threshold. |
| 9 | **No prompt and no hash in the audit trail** — `IMAGE_GENERATE` records actor, scope and outcome only. |
| 10 | **Suppressing ComfyUI's own prompt retention is a ship blocker.** |
| 11 | **No retry path.** |
| 12 | **Optional by configuration** — no `infochat.image.base-url`, no command. |
| 13 | **One class in the Provider**, no SPI and no new module, until a second backend exists. |

## The flow

```
 1  InboundRouter       normalizes (NFKC, bidi/zero-width strip), caps body      [exists]
 2  probation gate      absent from CommandPermissions.ALLOWED → blocked free    [exists]
 3  config gate         no infochat.image.base-url → command does not exist
 4  cooldown            per user, DM and group alike (~15 s)
 5  credit gate         AND of per-user and per-group hourly buckets; charge now
 6  queue-depth gate    ComfyUI /queue over budget → refuse immediately, refund
 7  dispatch off-thread InterruptibleDispatcher, not the router thread; /stop works [exists]
 8  parse flags         --ratio|-r as OUTPUT size (keeps the upscale stage free)
 9  translate → English  TranslationPipeline / QueryTranslationCache             [exists]
10  audit               IMAGE_GENERATE: actor, scope, outcome — no prompt
11  ProgressNotifier    STARTED → TRANSLATING → GENERATING, + queue position     [exists]
12  ComfyUI call        server-built graph; user text → exactly one JSON string field
13  poll /history       timeout CANCELS the job; /stop cancels it too
14  GET /view → bytes   write to a tmpfs spool dir
15  strip metadata      ComfyUI embeds the whole workflow in the PNG — must not ship
16  send attachment     *** the bulk of the work: no outbound-media path exists ***
17  delete              on adapter-reported completion; guaranteed by an age sweeper
18  echo prompt         reply carries the English prompt actually used
```

Steps 1, 2, 7, 9 and 11 already exist. Step 16 is most of the cost. Steps 6, 13,
15 and 17 are the ones that stay invisible until they bite.

## The adapter SPI is the real work

Verified against the tree, 2026-08-05:

- `MessagingAdapter.send(OutboundMessage)` and
  `OutboundMessage(scope, text, requestedAt, correlationId)` — no bytes, no
  MIME type, no path.
- `CapabilityFlags` (nine fields) has no attachment flag.
- `SimpleXMessageCodec.encodeSendCommand` emits only `{"type":"text",...}`;
  `SignalMessageCodec.encodeSend` emits only `{recipient, message}`.
- `OutboundDelivery`, `StageProgressNotifier` and `SimpleXOutboundChunker` all
  assume `String` bodies end to end.

So the feature needs a new SPI method, a new payload record, a new capability
flag, changes to both codecs, an `OutboundDelivery` path, and a spec amendment —
`docs/spec/messaging.md` and `docs/design/06-messaging.md` currently scope
attachments out of v1. Both underlying protocols support files; the gap is
entirely ours.

`/image` builds only the **outbound** half of §B's media foundation, which is
the cheaper half, and leaves B3 (`/read`, TTS) nearly free. B1 (voice-in) still
needs the inbound half separately.

## Image lifecycle: a swept spool, not a delete step

"Delete the file after delivery" cannot be a step in the flow, for three
reasons:

- **Two files exist, not one.** ComfyUI writes into its own output directory —
  that is what `GET /view` reads. The Provider then needs its own copy for the
  adapter. ComfyUI has no delete API, so one side is a process we do not own.
- **signal-cli takes attachments by file path.** The file must exist, and be
  visible to the adapter subprocess, for the whole duration of the send —
  including any `OutboundDelivery` retry.
- **SimpleX file transfer is asynchronous** (XFTP upload, then receiver pull).
  `send()` returning is not the same as the bytes being safe to drop.

The shape that works: a **spool directory on tmpfs** (never touches persistent
storage, which matches the privacy posture), delete-on-completion as the happy
path driven by an adapter-reported completion signal, and an **age-based sweeper
as the guarantee** — a scheduled job, sibling of `ChatMemoryPruner`. The sweeper
is what holds the invariant when the Provider dies between generating and
sending; delete-on-completion alone leaks a file on every crash.

## The prompt is message content

The deployment is invite-only, over privacy messengers, among people the
operator knows. Content collection is off the table — nothing beyond
content-free action records.

A hash does not serve that goal. Prompts are short, low-entropy human text, so
the space of plausible prompts is trivially small against a 256-bit digest and
anyone holding the audit table can dictionary-attack it. A hash buys weak
privacy *and* weak utility. If the goal is no content, store no content.

The durable record still exists, in a better place: decision 4 echoes the
English prompt actually used, so what was asked for lives in the user's own chat
history inside the end-to-end-encrypted channel, rather than in an
operator-held copy.

Accepted consequence: an after-the-fact question about a generated image can be
answered with *who* and *when*, never *what*. Under this trust model the
recourse is social — talk to them, revoke the invite — not forensic. The image
is deleted anyway, so a stored prompt would have nothing to correlate against.

**No-content is a chain, and one gap defeats all of it:**

1. The prompt exists in memory and in the ComfyUI request body — nowhere else.
2. Never logged: not in timeout messages, not in error paths, not in HTTP
   request logging. `SafeLog` and `Redactor` are the existing tools.
3. Never audited beyond the bare fact of the call. `AuditAction` already carries
   ordinary user verbs (`SET_LANG`, `CHAT_MODE`, `EXPORT`, `FORGET`), and V5
   deliberately left `audit_log.action` without a DB CHECK because the verb
   catalogue is open-ended — so `IMAGE_GENERATE` is one new enum constant with
   no migration.
4. Stripped from the delivered PNG. ComfyUI embeds the full workflow JSON in PNG
   text chunks by default, which would ship the raw prompt, our model paths and
   the server-side negative prompt inside the image. There is no binary/EXIF
   sanitizer anywhere in the codebase today — this surface is new.
5. **ComfyUI's own retention is a ship blocker, not a footnote.** `GET /history`
   returns the full submitted graph including the prompt text and holds it for
   the session, which is what makes the `127.0.0.1` bind load-bearing rather
   than a nicety. Suppression is a hard requirement before the feature ships;
   otherwise the sidecar retains exactly the content links 1–4 exist to avoid,
   and the privacy claim is false in practice.
6. Pixels on tmpfs, deleted on completion, swept by age.

**The release gate.** Mocked graph-shape tests alone do not approve image
delivery: a stub accepts any graph shape and a unit fixture never meets the
shipped config values, so backend-schema and config-mismatch defects escape a
green suite by construction. Before an `/image`-enabled release, the
configured pipeline is proven against the deployment's own backend by
`prod/live-probe-image-e2e.sh` (baked graph accepted under the configured
pixel ceiling, `-r` converter graphs accepted at exact dimensions, the
crop-less shape refused, canary hygiene) and by the shipped-ceiling wiring
test `ImageCommandHandlerTest.defaultOutputAtTheShippedCeilingDeliversEndToEnd`
(default output through strip → spool → delivery at the ceiling read from the
shipped properties).

Credit and rate-limit state is content-free by construction — counters keyed by
contact id, no payload.

## Credits, cooldown and queue depth

Three separate problems, three separate controls:

| Control | Bounds | Scope |
|---|---|---|
| Credit quota per hour | total consumption (anti-hog) | per user, and per group |
| Cooldown between images | burst (anti-flood, social) | per user |
| ComfyUI queue-depth gate | GPU contention (technical) | global |

**Charge on attempt; refund iff the GPU never ran.** A credit is a GPU-time
token, not a satisfaction token — that one rule settles the whole
charge-on-success question. Refund on: backend unreachable, breaker open, queue
over budget, timeout before the job started, Provider crash. No refund on: image
generated and delivered, generated but over the platform attachment limit (the
user chose `--ratio`), generated but the adapter send failed, prompt rejected.
Charging only on success would let a user burn the GPU indefinitely with failing
prompts, and "success" is ambiguous regardless — an ugly image is a mechanical
success. The refund primitive already exists: `RateCapBucket.refundCheapCommand`
and `LlmRateCap.refund(UUID)`.

**Dual accounting fits the existing class.** `RateCapBucket` already holds both
per-`(adapter, contactId)` and per-group-`UUID` bucket maps — seven maps today,
sharing one `@Scheduled` eviction sweep. Group and user credit are two more maps
in that shape. The gate is an AND: both must yield a token, and a refund returns
both. Deliberate consequence — a heavy group user then has no DM credit left,
which is correct if the credit is a GPU token.

**These are buckets, not a ledger.** `RateCapBucket` is a `ConcurrentHashMap`
with a scheduled idle-eviction sweep, entirely in memory, so credits reset on
Provider restart and a restart is a free refill for everyone. On a self-hosted
invite-only instance the only actor who can exploit that is the operator, so it
is acceptable — but the word "credit" implies a ledger and this is not one. A
DB-backed ledger would mean a table, a migration, and decision-time reads
falling under the injected-`Clock` rule; not worth it at this scale.

**Concurrency is not a capacity question.** ComfyUI serializes on one GPU, so
concurrency is 1 regardless of user count; N simultaneous users produce queue
*depth*, which is latency — the fifth person in line waits roughly 22 s. The
real question is what person five sees. Show queue position in the progress
message (ComfyUI `/queue` reports it) and refuse past a depth threshold rather
than silently queueing.

**Cooldown applies in DMs too, not just groups.** Same mechanism either way, and
"why is there a cooldown in groups but not DMs" is a support question worth
avoiding. 10–20 s is 2–4× generation time, a sane ratio.

### Shipped gate values (M1-803)

The values `commands.md §Content` commits live here. All are
profile-driven in `application.properties`:

| Key | Default | pi | Bounds |
|---|---|---|---|
| `infochat.ratelimit.image-user-credits-per-hour` | 10 | 5 | per-user GPU-time tokens per hour |
| `infochat.ratelimit.image-group-credits-per-hour` | 30 | 15 | per-group GPU-time tokens per hour |
| `infochat.image.cooldown` | `PT15S` | `PT30S` | per-user gap between attempts (DM and group) |
| `infochat.image.max-queue-depth` | 3 | 3 | global backend depth at/above which the command refuses immediately |
| `infochat.image.prompt-max-chars` | 500 | 300 | prompt length cap — rejected before any gate runs |
| `infochat.image.max-output-pixels` | 5000000 | 5000000 | output pixel ceiling — bounds the parser's `--resolution` check and the strip's IHDR check on every output |
| `infochat.image.min-output-pixels` | 16384 | 16384 | output pixel floor — bounds the parser's `--resolution` check |
| `infochat.image.steady-state-seconds` | unset | unset | per-model steady-state seconds the setup wizard seeds from the container re-measurement; unset → position shown without an ETA |

The cooldown is a 1-token bucket whose window IS the cooldown, reusing
the refill/sweep/fixed-clock mechanics; the two credit buckets and the
cooldown bucket all enroll in the eviction sweep.

**Refund boundary (charge on attempt; refund iff the GPU never ran):**

| Terminal | Refund |
|---|---|
| backend unreachable / breaker open (at the queue read or submit) | yes |
| backend rejected the graph before any job ran | yes |
| queue over budget | yes |
| timeout / `/stop` cancel, job KNOWN never started (`/queue` peek) | yes |
| timeout / `/stop` cancel, job started or unreadable (conservative) | no |
| transport failure after submit with no interrupt signal | no |
| generated but the adapter send failed | no |
| generated but over the platform attachment limit | no |

**Reply wording.** Every reply is a `BundleKeys` constant, present in
all five shipped bundles (en, cs, tr, es, ru), enforced by
`BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle`.
The eight failure-contract modes map to `IMAGE_ERROR_BACKEND_UNREACHABLE`,
`IMAGE_ERROR_BREAKER_OPEN`, `IMAGE_ERROR_QUEUE_BUSY` (+ the `_NO_ETA`
variant when the steady-state constant is unset),
`IMAGE_ERROR_CREDITS_EXHAUSTED`, `IMAGE_ERROR_COOLDOWN`,
`IMAGE_ERROR_TIMEOUT`, `IMAGE_ERROR_NO_ATTACHMENT_SUPPORT`,
`IMAGE_ERROR_ATTACHMENT_OVER_LIMIT`. Beyond the eight:
`IMAGE_ERROR_GENERATION_FAILED` (post-start transport failure, invalid
PNG, spool failure), `IMAGE_ERROR_SEND_FAILED` (post-GPU delivery
failure — the spec's eight do not name it, the no-refund arm needs a
voice), the parser's `IMAGE_ERROR_PROMPT_TOO_LONG` /
`IMAGE_ERROR_BAD_RESOLUTION` / `IMAGE_ERROR_RESOLUTION_TOO_SMALL` /
`IMAGE_ERROR_RESOLUTION_TOO_LARGE` / `IMAGE_ERROR_MISSING_PROMPT`, the progress pair
`IMAGE_PROGRESS_GENERATING_ETA` / `IMAGE_PROGRESS_GENERATING_NO_ETA`,
and the echo `IMAGE_REPLY_ECHO`.

## Do not run `/image` on the router thread

`/summary` runs inline on the router thread today. `/image` blocking that thread
for 4.4 s — or 22 s when queued — starves inbound message handling once a
handful of requests overlap. Route it through `InterruptibleDispatcher`, which
already has a per-user cap and `wouldQueue()`. That also makes `/stop` work on a
queued image for free, and `/stop` must cancel the ComfyUI job on the same path
as the timeout cancellation below.

## Timeouts must cancel, not abandon

If the Provider gives up at N seconds and merely stops polling, ComfyUI keeps
generating. Abandoned jobs compound GPU contention exactly when the queue is
already deep. Cancel explicitly (`POST /interrupt` or a queue delete — verify
the endpoint at design time), and reject early when `/queue` depth is already
over budget: "busy, try again" beats a 60-second silence.

## Translation

`/lang` already stores per-scope language in `scope_preferences`, and
`InboundContext.effectiveLanguage()` already carries it into the request.
`TranslationPipeline` and `QueryTranslationCache` already translate *user query
text* — the exact precedent, so no new SPI and no new LLM task.

Translate to English whenever the effective scope language is not `en`, and echo
the English prompt actually used in the reply. The echo makes the translation
transparent, removes any need for a `--no-translate` flag, and doubles as the
failure-mode explainer — one less flag on a chatbot command.

Stated tension: this puts an LLM in the prompt path. The blast radius is bounded
because the output lands in a JSON string value, so the worst case is "the image
is of something else", not code execution.

**Settled by measurement, 2026-08-07** (same 5-element scene in en/cs/tr/es,
fixed seed per model, production-shaped graphs — raw prompt, no enhancement,
scored on red bicycle / blue door / wicker basket on handlebar / yellow lemons
/ black cat on saddle):

| Model | en | cs | tr | es |
|---|---|---|---|---|
| Mage-Flow Turbo | 5/5 | 1/5 (no bike, white door, green fruit, gibberish text) | 1/5 (dog instead of cat, no bike) | 5/5 |
| Z-Image Turbo | 5/5 | 1.5/5 (green door, no bike, bowl of oranges) | 4/5 | 5/5 |
| Krea 2 Turbo | 5/5 | 5/5 | 4.5/5 | 5/5 |

Mage-Flow and Z-Image degrade hard outside en/es (cs worst), exactly as the
caption-distribution argument predicts — translate-to-English is **required**
for them. Krea 2 (Qwen3-VL encoder) is language-robust and would not need it.
The pipeline stays uniform: translate whenever the scope language is not `en`,
echo the English prompt — one code path, no per-model special-casing, and the
echo keeps the translation transparent.

## Content liability

Every user is known and attributable: DM access requires an invite from a bot
admin (D44), and group interaction requires prior DM registration plus an
admin-approved group (D47). There are no anonymous users. Model choice is an
operator decision, exactly as LLM choice already is, and each operator is
responsible for their own instance. Probation blocks `/image` for free —
`CommandPermissions.ALLOWED` is a closed eleven-name list and anything absent
fails closed — and the negative prompt is server-controlled.

**Do not record "the model has guardrails" as a control.** Open-weights image
models are filtered at *training* time; there is no inference-time refusal the
way a chat model refuses, and it varies per checkpoint. It is what the model
*can't* do, not what it *won't* do. The controls actually depended on are
attribution, the audit row, and operator model choice.

An output-side classifier stays rejected: it needs a vision model and would
roughly double the wall clock.

`docs/plan/future-features.md` §B2 calls for a `/redteam` pass at design time.
That still stands — these decisions are the input to it, not a substitute.

## Retry

`RetryCommandHandler` is digest-specific (`DigestRetryService`, `RetryLeg`) — it
re-runs failed digest legs, not "the last command". So `/image` inherits nothing
and there is no accidental coupling to remove.

No retry path is added. Failures return text, the user retypes, and the credit
gate governs. Re-rolling the seed for taste is precisely the image-studio
behaviour the feature is defined against.

## Optional by configuration

ComfyUI runs as an opt-in compose service (see the 2026-08-07 addendum below;
the earlier conda-only assumption is superseded), and the `pi` and `vps`
profiles cannot run it at all. So if `infochat.image.base-url` is unset,
`/image` does not exist: absent from `HelpCommandHandler.CATALOGUE`, and the
standard unknown-command reply if invoked (an absent feature behaves as
absent, not as present-but-disabled). This is
capability gating on the D46 adapter-subset precedent, not a back-compat
feature flag, so it does not collide with the no-feature-flags rule.

## Failure contract

The "return text explaining why" promise, enumerated: backend unreachable ·
breaker open · queue over budget · credit exhausted · cooldown not elapsed ·
timeout (after cancelling the job) · adapter cannot carry attachments
(capability flag false) · attachment exceeds the platform limit.

## Shape of the implementation

Resist an `ImageGenerationProvider` SPI and an `infochat-image-adapter` module
mirroring `infochat-llm-adapter`. There is one backend, it speaks plain HTTP,
and `LlmHttpSupport` already provides the bounded-response, timeout and
transport-failure helpers. A single class in the Provider, reusing the
`LlmCircuitBreakerRegistry` breaker pattern, is the honest shape until a second
backend exists.

Provider→ComfyUI is new egress: route it through `infochat-ssrf` or record an
explicit configured-internal exemption. The exemption is the chosen path —
recorded in §The backend client below.

Generation is **not** an LLM tool and not MCP. ComfyUI exposes plain HTTP, so
deterministic Java calls it directly; exposing generation as an LLM-callable
tool would collide with the deterministic-retrieval principle and widen the
injection surface for nothing. The workflow graph is **built server-side** — the
API accepts a whole graph whose nodes execute Python and touch the filesystem,
so user text must reach exactly one field (`CLIPTextEncode.text`) as a JSON
string value. Anything looser is remote code execution.

## The backend client (endpoint shapes verified 2026-08-08)

One class in the Provider (`ComfyUIClient`, decision 13), plain HTTP. The
endpoint shapes below were ASSUMPTIONS at analysis time (analysis D-4) and
are verified against the pinned ComfyUI commit (the Dockerfile's
`COMFYUI_COMMIT`) running as the M1-797 container:

- **Submit:** `POST /prompt` with `{"prompt": <API-format graph>}` →
  `{"prompt_id": ...}`; 400 with `{"error": {"type": ...}}` on rejection.
- **Queue depth:** `GET /queue` → `{"queue_running": [...],
  "queue_pending": [...]}`; depth is the sum of both array lengths. The
  read is the client's primitive; the gate DECISION is the command
  handler's.
- **Poll:** `GET /history/{prompt_id}` → `{}` until the job lands, then an
  entry with `status.status_str` (`success` on completion) and
  `outputs.<node>.images[] = {filename, subfolder, type}`.
- **Cancel:** `POST /interrupt` with `{"prompt_id": ...}` interrupts a
  RUNNING job only — a job still pending in the queue survives it — so the
  client pairs it with `POST /queue` `{"delete": [prompt_id]}`. Both return
  200 and are idempotent. Timeout and `/stop` share this path: a job is
  never merely abandoned (an abandoned job keeps burning GPU).
- **History clear (D75, Provider half):** `POST /history`
  `{"delete": [prompt_id]}` removes the submitted-graph entry — verified
  the entry is gone afterwards. The client clears after EVERY job —
  completed, timed out, or failed — and a failed clear fails the
  generation: the no-retention end state is an acceptance check, not a
  best effort.
- **Fetch:** `GET /view?filename=...&type=output[&subfolder=...]` serves
  the output bytes.

**SSRF exemption (recorded).** The client's egress does NOT route through
`infochat-ssrf`. `infochat.image.base-url` is operator-configured internal
infrastructure — the compose-network name `comfyui:8188` in the one-box
form, a D77-firewalled private address in the two-box form — which is
outside the security.md §SSRF enumeration (feeds / redirects /
StreamSource / `/add-source` probes, all user-controlled URLs). Routing the
call through the gate would block the loopback/private address the feature
requires. The compensating control is item-9's bounded-read posture: every
response body is endpoint-chosen bytes read under the byte cap below, and
the connection is cut at the cap. The client additionally never follows
redirects, so a misconfigured or compromised backend cannot re-point the
egress at arbitrary hosts.

**Graph template.** The graph is built server-side from a template file
plus a JSON serializer — never string interpolation (the endpoint executes
submitted graphs; interpolation is an RCE vector, D77). The template is
API-format JSON; the prompt slot is the unique `CLIPTextEncode` text field
carrying the sentinel `INFOCHAT_PROMPT_PLACEHOLDER`, and the client rejects
a template without exactly one such field and exactly one `KSampler` with a
numeric seed. The seed is randomized per job — a fixed seed measures the
backend's node cache, not generation. The committed reference template is
`prod/config/comfyui-workflow.json` (Mage-Flow Turbo, the wizard default);
the setup step writes the per-model template and points the key below at
it. The negative prompt is server-controlled text inside the template
(decision 2).

**Client configuration** (all `infochat.image.*`; the command handler gates
on base-url presence per decision 12):

| Key | Default | Meaning |
|---|---|---|
| `infochat.image.base-url` | unset | backend endpoint; unset → no `/image` (decision 12) |
| `infochat.image.workflow-file` | unset | API-format graph template path; required when base-url is set |
| `infochat.image.connect-timeout` | `PT5S` | transport connect timeout |
| `infochat.image.call-timeout` | `PT30S` | per-HTTP-call timeout |
| `infochat.image.job-timeout` | `PT3M` | whole-job deadline (queue wait + generation); on expiry the job is CANCELLED, never abandoned |
| `infochat.image.poll-interval` | `PT0.5S` | `/history` poll cadence |
| `infochat.image.max-response-bytes` | `16777216` | byte cap for EVERY backend response body — 16 MiB leaves headroom over a 2048px PNG while bounding a hostile endpoint |

**Breaker.** The client carries its own consecutive-transport-failure
breaker: 3 failures → open for 30 s, then a single half-open probe — the
LLM breaker's defaults. Reusing `LlmCircuitBreakerRegistry` would require
llm-adapter API changes (it is keyed by `ModelTask`), which the D-2
discipline rules out. Any response — success or application error — is
reachability evidence; only transport failures advance the breaker. State
is in-memory; a restart resets it.

**No-content logging.** No log line on any client path (error, timeout,
breaker) carries the prompt, the graph, or a response body — backend error
messages are reduced to their `error.type` before they reach an exception
message.

## Open items

- ~~Multilingual prompt adherence~~ — **settled 2026-08-07**, see the table in
  §Translation: translate-to-English (decision 4) is required for Mage-Flow and
  Z-Image, optional for Krea 2; the pipeline stays uniform.
- **Attachment size ceilings on SimpleX and Signal** — still unverified
  (`docs/plan/future-features.md`), and they bound the maximum `--ratio`
  however the pixels are produced.
- **ComfyUI stdout** — ~~whether it prints the prompt text~~ **settled
  2026-08-08 (container measurement, M1-797):** stock ComfyUI 0.30.0 prints
  no prompt text — the log carries only the content-free `got prompt` and
  `Prompt executed in N seconds` lines. A canary prompt string hunted in
  `docker logs` after 19 generations (one canary + the full per-model timing
  set) returned zero hits. ~~Whether `/history` is bounded or clearable
  remains open for the M1-802 post-job clear~~ **settled 2026-08-08
  (verified against the pinned backend commit, M1-802):** `POST /history`
  with `{"delete": [prompt_id]}` removes the submitted-graph entry — the
  clear is per-job and verified live; see §The backend client (both halves
  feed the decision-10 ship blocker).
- **SimpleX XFTP lifetime** — whether the local file must survive until an
  upload-complete event, which decides what the delete-on-completion signal
  actually is.
- **`--disable-metadata`** — verify it still suppresses the embedded workflow;
  otherwise strip PNG text chunks Provider-side.

## Addendum 2026-08-07 — containerization, setup, and the path to spec

Settled in the planning conversation of 2026-08-07. Where these contradict
earlier sections, the addendum wins; everything else stands.

**Confirmed unchanged.** Decision 1 (DM **and** groups, with the per-group
credit bucket) stands — a DM-only scope was considered and rejected. Decision 4
(auto-translate to English and echo the English prompt used) stands and is now
**validated by measurement** (2026-08-07, see the table in §Translation).
Decision 12 (config-gated) stands and now covers the remote question too.

**Remote backends deferred, by design.** Hosted ComfyUI services
(cloud.comfy.org and similar) were considered and set aside: the prompt is
message content, and sending it to a third party breaks the no-content chain.
The feature is **local-or-absent**: no `infochat.image.base-url`, no command —
invoking it yields the standard unknown-command reply, per decision 12. No
API-key support is built. `base-url` accepting any URL does not preclude a
future hosted-backend decision, which would need its own privacy discussion.

**ComfyUI is a container, not a conda install.** Supersedes the "manual conda
install" assumption. The shape follows the `docker-compose.gpu.yml` precedent:
an opt-in GPU overlay as a second `-f` file, so the base compose file stays
startable on hosts without a GPU. There is no official ComfyUI ROCm image, so
the overlay carries a purpose-built image (ROCm base, `/dev/kfd` + `/dev/dri`
mapping). The measured launch flags are load-bearing and must survive
containerization: `--disable-mmap`, `--bf16-vae`, `--highvram`, env
`TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL=1`. The `127.0.0.1` bind stays
load-bearing (ComfyUI has no auth; `/history` holds the submitted graph).
**The 4.4 s Mage-Flow number was measured on conda — the containerized setup
must be re-measured before any number is printed to operators.**

**Hardware scope, stated plainly: the local container path is ROCm-only and
validated on Strix Halo (gfx1151) alone.** Other ROCm GPUs are expected to
work but are unverified — the launch flags were measured on gfx1151 and
`--disable-mmap` specifically works around a >64 GB unified-memory ROCm bug
that smaller cards may not need. NVIDIA is not covered by the overlay at all
(a CUDA variant is a separate, unscheduled decision; note the model repos'
`nvfp4` builds would apply there, unlike on ROCm). Every operator-facing
surface — the wizard step, SETUP_GUIDE, and eventually the README — must
carry this scope sentence rather than implying "any GPU box".

**Setup is part of the wizard, not a side script.** A new numbered step script
under `prod/scripts/` (registered in the `setup.sh` STEPS list, alongside the
existing steps — not a standalone like `switch-llm.sh`), plus a re-run/edit
path for existing installs, plus the wizard adaptation to offer it only on
profiles that can run a GPU (never on `pi`/`vps`). The step offers three
hardcoded model choices with honest measured performance next to each:

1. **Mage-Flow Turbo** (default) — 4.38 s @ 4 steps measured (conda;
   re-measure in container); 5.2 s at the 6-step setting, 2026-08-07.
2. **Z-Image Turbo** — 21 s measured.
3. **Krea 2 Turbo** — tunable: 53.59 s at the stock 8-step/1 MP template,
   **23.78 s at 6 steps/0.6 MP** (both measured 2026-08-07, bf16; fp8/int8 buys
   nothing on gfx1151 — no fp8 matmul path, dequant overhead only). Ships as
   the quality tier with the honest "roughly 5–12× slower than the default"
   note; pairs with the ESRGAN upscale stage to recover output resolution.

The step preflights before downloading: HEAD-check every model asset URL,
verify disk space and VRAM for the chosen model, then download (checkpoint +
text encoder + VAE), start the service, and healthcheck via `/system_stats`.

**Model switching is an operator operation, never a chat command.** There is
no `/image-model` or per-request model flag: the installed model is a property
of the instance, changed by re-running the setup step, which recreates the
ComfyUI container and offers to delete the previous model's files. A
chat-level switch would multiply the graph-building surface (three graph
shapes instead of one), the asset footprint, and the support matrix for zero
user-facing gain — the picker exists so the *operator* trades speed against
quality once, not so users re-roll it per prompt.

**Per-model disk footprint** (measured on this host, bf16 builds; the wizard
prints these next to the picker so the resource demand is explicit):

| Model | Checkpoint | Text encoder | VAE | Total |
|---|---|---|---|---|
| Mage-Flow Turbo | 7.7 GB | 8.3 GB (qwen3vl_4b) | 0.33 GB | **~16.5 GB** |
| Z-Image Turbo | 12 GB | 7.5 GB (qwen_3_4b) | 0.32 GB | **~20 GB** |
| Krea 2 Turbo | 25 GB | 8.3 GB (qwen3vl_4b) | 0.24 GB (stock) + 0.51 GB (krea2RealVae_v10, measured 507 591 212 B) + 0.51 GB (Wan2.1_VAE_upscale2x_imageonly_real_v1, measured 507 684 560 B) | **~34.5 GB** |

The two Krea community VAE files (Final decision 5) are measured on this
host: `krea2RealVae_v10.safetensors` 507 591 212 bytes and
`Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors` 507 684 560 bytes —
the wizard prints them in the Krea disk demand and HEAD-checks both before
any download.

**Container re-measure (2026-08-08, M1-797) — these are the numbers the
wizard prints.** Measured inside the `docker-compose.comfyui.yml` overlay's
container on the Strix Halo host (same torch stack as the conda baseline:
2.12.0a0+rocm7.13, gfx1151 nightly index; load-bearing flags verbatim), one
warm-up plus five timed 1024px generations per curated model, unique seed
per run (a fixed seed measures ComfyUI's node cache, not generation — the
first fixed-seed probe recorded 0.21 s cache hits):

| Model | Setting | conda mean | Container steady-state mean (5 runs) |
|---|---|---|---|
| Mage-Flow Turbo | 4 steps, 1024px | 4.38 s | **3.75 s** (3.66–4.06) |
| Z-Image Turbo | 8 steps, 1024px | 21 s | **21.81 s** (21.50–22.45) |
| Krea 2 Turbo | 8 steps, 1 MP | 53.59 s | **53.07 s** (52.57–53.99) |

The load-bearing launch flags survived containerization: Mage-Flow's
container steady state is 0.86x its conda number (the acceptance threshold
was 2x). No conda number may be presented to operators as a container
number.

**VAE-Utils carve re-verification (2026-08-09, M1-807).** The image now
carries spacepxl/ComfyUI-VAE-Utils at pinned commit
`d69e7afaa9edc3cd99096dc063ec37e71b2d1184` (MIT; node id
`VAEUtils_VAEDecodeTiled`, the Krea 2x VAE-decode fit stage). With the node
present the load-bearing flags were re-measured, same protocol: Mage-Flow
Turbo 4 steps 1024px container steady state **4.03 s** (5 runs, 0.92x the
conda 4.38 s — within the 2x threshold). Krea 2 Turbo 6 steps @ 0.6 MP with
VAELoader(Wan2.1_VAE_upscale2x_imageonly_real_v1) + VAEUtils_VAEDecodeTiled:
container steady state **22.73 s** (3 runs, 22.57–22.81), output 1792x1344
— exactly 2x the 896x672 latent, matching the conda VAE-lever measurement.

**The backend no-retention window lives in the image.** The ComfyUI image
carries the tmpfs output dir plus an aged-file janitor sweeping files older
than `INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES` — **default 15 minutes**
(measured 2026-08-08: output dir empty again once every file crossed the
window). ComfyUI has no delete API for output files, so this janitor is the
only backend-side bound; the Provider fetches via `/view` within seconds of
completion, so the window is a backstop, not a lifecycle. The Provider-side
spool sweeper (M1-801) MUST exceed this window — the backend copy dies
first, the Provider's spool copy is the surviving one for adapter retries.

**M1-806 extension (2026-08-08): the same containment now covers BOTH
backend pixel directories.** ComfyUI also writes preview pixels to its temp
directory — `folder_paths.get_temp_directory()` resolves to
`/opt/ComfyUI/temp` at the pinned commit (probe-verified 2026-08-08) — and
that directory is now tmpfs-backed (same 512 MB cap, same sizing basis) and
swept by the SAME janitor on the SAME `INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES`
window, so the D75 no-retention end state is graph-shape-independent: no
submitted graph, current or future, can leave job-derived pixels on the
container's writable layer. DECIDE-BEFORE answer for M1-802: retention
containment no longer depends on graph shape, so M1-802 inherits no
temp/-specific obligation and its existing no-leftover-output-files probe is
backed by the image for both directories; an output-only graph (SaveImage,
no PreviewImage) stays the intended shape as RAM efficiency (previews charge
tmpfs RAM and the Provider never fetches them), not as a retention control.
`input/` is deliberately NOT contained: no writer exists or is planned (the
Provider's client surface has no upload verb) — a future graph adding a
LoadImage-type node must re-run the census.

**Two-box deployments: remote is a URL, not an API key.** An operator may run
ComfyUI on a second, GPU-capable box and point `infochat.image.base-url` at it
— the same mechanism as the remote-LLM profile. ComfyUI has **no
authentication and no API-key support**; its API accepts whole workflow graphs
whose nodes execute Python, so the endpoint is code execution on whatever box
hosts it. Therefore: the trust boundary is the operator's own network, full
stop. The remote box must be operator-owned infrastructure on a private LAN
(or equivalent), never a third party — the local-or-absent rule above is about
*prompts leaving the operator's infrastructure*, not about which machine the
GPU sits in. The Provider-side egress still goes through `infochat-ssrf` (or a
recorded configured-internal exemption), and the wizard documents the
requirement rather than enforcing it — we cannot verify network topology from
a setup script. Prompts cross the LAN in cleartext HTTP; acceptable inside
one operator's network, stated in the docs.

**Profile consequences, said plainly in the wizard.** Fully local means this
box needs a usable GPU and 16–34 GB of disk *on top of* the LLM stack; `pi`
and `vps` profiles are offered only the remote-URL path or "not enabled". The
wizard states the numbers (disk table above, plus measured per-image latency
from the container re-measure) before the operator commits to a download.

**Two precision tiers per model, hardcoded — never a live HuggingFace
picker.** Each model's repo contains traps a raw file list would expose to
operators: `nvfp4`/`mxfp8` builds that run only on NVIDIA, Krea `raw`
variants (~5 min/image, the opposite of the picker's promise), and nine
Mage-Flow checkpoint files of which only two are the turbo t2i builds. So the
wizard offers, per model, exactly two curated choices with plain labels:

- **Recommended (best quality)** — bf16 checkpoint + bf16 encoder.
- **Smaller footprint** — `int8_convrot` checkpoint + fp8 encoder where one
  exists: roughly half the disk (Mage ~13 GB, Z-Image ~11.5 GB, Krea ~19 GB).
  The honest label is "same speed, about half the disk, slight quality cost"
  — on gfx1151 quantization buys no speed (no int8/fp8 matmul path), only
  disk and co-residency headroom with the LLM stack.

Asset URLs stay hardcoded per tier; the preflight HEAD-check is what catches
a repo restructure. The `qwen3vl_4b` encoder is the *same file* in the
Mage-Flow and Krea repos (identical blob) — the download step dedupes it.
Quantized-tier sanity check (2026-08-07, single en scene, same seed, bf16
encoders): all three int8 builds scored 5/5 adherence, visually
indistinguishable from bf16, at identical speed (differences were
first-load noise). One scene is not a quality guarantee — the tier keeps its
"quality may differ slightly" label and bf16 stays the recommended default —
but there is no visible collapse that would justify dropping the tier.

**Coarse ETA in the progress message.** Decision 8 already shows queue
position; the ETA turns position into time, which is what the user actually
wants. The mechanism is one multiplication: `(queue position + 1) × per-model
measured steady-state constant`, where the constant is an operator config
value **seeded by the setup step's container re-measurement** (the same probe
M1-797's acceptance runs). Render it rounded ("~25 s", optionally a range),
never as a countdown: ComfyUI's HTTP API reports queue depth but not in-job
progress, and `--resolution`/the upscale stage shift real time, so precision
would be a lie. The queue-depth refusal reuses it ("busy, ~N min backlog").
Mage-Flow-class latencies make the ETA a nicety; Krea-class latencies and a
deep queue are where it earns its bundle key. The interpolated value is an
integer — inside the progress-string scalar-parameter rule.

**Ship blockers become acceptance criteria.** The five open items above that
gate shipping — `/history` suppression (decision 10), stdout prompt logging,
PNG workflow-embedding strip, SimpleX XFTP file lifetime, SimpleX/Signal
attachment size ceilings — are written into the implementation tickets as
acceptance criteria, not deferred to a separate spike.

**Sequencing.**

- **P0 — spec promotion + redteam.** Amend `docs/spec/messaging.md` and
  `docs/design/06-messaging.md` (attachments scoped into the spec), `docs/spec/commands.md`,
  `docs/spec/decisions.md`. The design-time `/redteam` pass called for in
  `docs/plan/future-features.md` §B2 still stands and runs before tickets.
- **P1 — container + setup step.** Overlay, image, wizard step, re-measure.
- **P2 — outbound media SPI.** The bulk of the work (step 16): SPI method,
  payload record, capability flag, both codecs, `OutboundDelivery` path, tmpfs
  spool + age sweeper, PNG metadata strip.
- **P3 — `/image` command.** Config gate, cooldown, per-user and per-group
  credits, queue-depth gate, server-built graph, translation + echo,
  timeout-cancels-job, content-free audit.

Tickets are carved via the `/tick` flow after P0. The Krea 2 measurement runs
between this plan and ticket carving and decides whether the picker ships two
options or three.

**Design-time redteam: done 2026-08-07** (verdict:
`docs/plan/m1/redteam/image-spec-promotion-2026-08-07.md` — 0 critical, 2
high, 3 medium, 2 low, all accepted and fixed in the spec the same day). The
fixes, now part of the spec: the translation leg is enrolled in the
`security.md` §Secrets handling enumeration and disclosed rather than
prohibited (it is the same exposure class as remote-routed chat); the two-box
form requires the backend port firewalled to the single Provider host; the
echo passes through the closed-list output sanitizer; `/image` joins the D35
interruptible-concurrency ceiling; the backend-retention requirement is a
verifiable end state with an acceptance check; backend replies, the spool,
and PNG decode are byte/pixel-bounded; and the prompt carries a
profile-driven length cap at the parser.

## Addendum 2026-08-09 — pipeline configurability: diffusion steps + optional upscaler

Found while preparing M1-798: the ETA constant is per-model, but the
pipeline it would describe is not configurable — sampler settings are baked
into the workflow template with no operator-visible knob, and the ESRGAN
upscale stage named in the Krea 2 tier decision above was dropped by the
seven-ticket analysis decomposition (none of M1-797..M1-806 carries it).
Where this addendum contradicts earlier sections, this addendum wins.

**Decisions (2026-08-09; numbers pending the measurement spike below):**

1. **Diffusion steps are operator configuration with per-model recommended
   values** — Mage-Flow 4 steps (container-measured 3.75 s), Z-Image 8 steps
   (container-measured 21.81 s), Krea 2 6 steps @ 0.6 MP (23.78 s
   conda-measured ONLY — container re-measurement is required before the
   wizard may print it, P22).
2. **The upscaler is an optional pipeline stage, available for all three
   models, on/off at setup time.** Per-model motivation: Mage-Flow — bigger
   outputs (it is already fast enough); Z-Image — trained/optimized for
   1024×1024, other sampling resolutions gamble quality, so its shape is
   sample-at-1024 + upscale-to-target; Krea 2 — the crucial case, currently
   the slowest model: the 0.6 MP sweet spot + upscale should land near
   Z-Image's ~22 s for 1 MP-class output with Krea's ratio flexibility,
   versus 53 s native 1 MP.
3. **Configuration is setup-time and baked by the wizard step (M1-798's
   shape), never runtime.** The step writes the per-model template: steps,
   sampling resolution, and — iff the operator enabled the upscaler — the
   upscale nodes plus the chosen upscaler model (a ~17–67 MB download added
   to the preflight/download path). The Provider cannot change any of it;
   changing = re-running the step, the model-switch shape ("Model switching
   is an operator operation, never a chat command"). Runtime configuration
   was explicitly rejected as ringing yet another complexity.
4. **`--resolution` remains the final output size, per job.** A converter
   maps requested output (dimensions; resolution = density + dimensions) to
   per-model sampling density + upscale, unifying all three models behind
   one flag. This target is the one per-job numeric input beyond prompt +
   seed — set through the JSON serializer like the seed, so the P15
   property (user text reaches exactly one string field) is preserved.
   DECIDE-BEFORE the follow-up analysis: how the handler learns the baked
   sampling resolution (candidate: the step writes it alongside
   base-url/workflow-file/ETA constant, keeping the handler model-agnostic),
   and whether the target lands as ImageScaleToTotalPixels megapixels or
   exact W/H after the model upscale. **Resolved 2026-08-10 (M1-803 round-1
   MANUAL → user refine):** (1) the handler learns the budget from the
   TEMPLATE ITSELF — the client's load-time validation captures the latent
   node (the KSampler's latent_image link) and its baked numeric width/height
   (budget = W×H); no wizard-written key, M1-798 unamended, no key/template
   drift, existing deployments need no re-run. (2) The target lands as EXACT
   W/H: for `-r` jobs the serializer sets per-job latent dims (requested
   ratio at the budget, rounded /16) and swaps the fit node
   ImageScaleToTotalPixels → ImageScale(width,height); no-flag jobs keep the
   baked graph untouched. (3) The converter is ONE unified model-agnostic
   rule — sample at budget at the requested ratio, lanczos exact fit. The
   pinned ComfyUI ImageScale schema requires `crop` and permits `disabled` or
   `center`; the converter uses `disabled`, so exact sizing never crops the
   image.
   Recorded deviation from Final decision 3: Mage samples at its 1 MP budget
   for all targets rather than directly at the target (Mage targets over
   1 MP are lanczos-upscaled from 1 MP — a quality nuance, user-approved to
   keep the handler free of per-model strategy tables).
5. **Stock nodes only — verified at the pinned commit (6f7cd7fc).**
   `UpscaleModelLoader` (comfy_extras/nodes_upscale_model.py) is
   spandrel-based and loads any single-image model spandrel 0.4.x supports
   (ESRGAN/RRDB family, SwinIR, HAT, DAT, OmniSR, ...) from
   `models/upscale_models/`; `ImageUpscaleWithModel` is internally tiled
   (512 px tiles, 32 px overlap — memory-bounded at large outputs);
   `ImageScaleToTotalPixels`/`ImageScale` (lanczos etc.) provide exact final
   sizing and the zero-model baseline. No custom nodes — the image stays
   pinned and minimal. The M1-802 client already accepts an upscale template
   unchanged: validation requires exactly one prompt placeholder + one
   KSampler (both survive), and the output read is graph-shape-independent
   (`firstOutputImage` iterates all output nodes).
6. **The measurement spike gates the ship** and runs BEFORE M1-798 starts
   (protocol = M1-797's: one warm-up + five timed container runs, unique
   seeds): (a) Krea 2 @ 6 steps/0.6 MP alone — validates the conda→container
   transfer (Mage-Flow came out 0.86×) and is needed for M1-798's honesty
   regardless of the upscaler outcome; (b) the upscale cost isolated: fixed
   0.6 MP input → 1 MP and 2 MP outputs, per candidate; (c) the full
   pipeline 6 steps/0.6 MP + upscale versus Krea native 1 MP (53.07 s) and
   Z-Image (21.81 s); (d) bounds sanity at the max target: PNG bytes versus
   the 16 MiB response cap (sized for "2048px PNG"), spool capacity, tmpfs
   output cap, and the SimpleX/Signal attachment ceilings (M1-800). Numbers
   land in THIS document; only container numbers reach the wizard.

**Upscaler candidates (all load via the stock UpscaleModelLoader):**

| Candidate | ~Size | License (verified 2026-08-09) | Character |
|---|---|---|---|
| 4x-UltraSharp | 67 MB | **CC-BY-NC-SA-4.0** | de-facto general-purpose favourite; sharp (§B2's named default) |
| RealESRGAN_x4plus | 64 MB | project BSD-3 (verify card at download) | the classic baseline; robust, softer |
| 4x_NMKD-Siax | 64 MB | community — verify (likely NC) | general-purpose, fewer halos |
| 4x_foolhardy_Remacri | 64 MB | community — verify (likely NC) | photo-leaning, natural texture |
| RealESRGAN_x4plus_anime_6B / 4x-AnimeSharp | 17 / 64 MB | verify | illustration-leaning; only if that content class matters |
| DAT/HAT/SwinIR family | 16–60 MB | per-model | newer architectures, better metrics; spandrel supports, unproven here |
| lanczos (no model) | 0 | n/a | baseline/fallback; interpolation, no detail synthesis |

**VAE-decode lever results 2026-08-09 (conda env, same protocol):**

| Config | s | Output |
|---|---|---|
| Krea base + krea2RealVae (1×) | 22.14 | 896×672, visibly crisper than stock, no tint |
| Krea base + spacepxl Wan2.1-VAE-upscale2x (2× decode) | 22.54 | 1792×1344, sharp, no tint |

The 2× VAE decode costs ~0.1 s over the 1× decode: Krea delivers 2.4 MP
at 22.5 s — faster than native 1 MP (39.55 s). Same-seed crops in
/tmp/opencode/img-measure/samples/vae_cmp_*.png. Licenses: the
ComfyUI-VAE-Utils node repo is MIT; krea2RealVae is licence-UNDECLARED on
HuggingFace (a community derivative hosted by a mirror), while the spacepxl
Wan2.1-VAE-upscale2x card declares Apache-2.0 (card + README frontmatter
re-verified 2026-08-09) — the wizard prints both as community assets with
those labels, same posture as the curated model tiers.

**Final decisions 2026-08-09 (v1 scope, user-approved):**

1. **No diffusion upscaler in v1.** PiD (broken on AMD upstream, #14273)
   and the ESRGAN family (+22–45 s on gfx1151) are excluded, evidence
   recorded above.
2. **The converter ships:** `--resolution WxH` is the final output
   contract; ratio is steered at sampling (per-model pixel budget at the
   requested ratio, dims rounded to /16); resolution is steered at the fit
   stage. Budgets: Krea 0.6 MP @ 6 steps, Mage 1 MP @ 4 steps, Z-Image
   1 MP @ 8 steps (sample-at-ratio; the 1024-lock-in gamble recorded as an
   open measurement).
3. **Fit stages per model (v1):** Krea — spacepxl 2× VAE decode when the
   target is ~2× the sampling budget (ceiling 2.4 MP), lanczos down for
   exact targets at or under the decoded size; Mage — sample directly at
   the target (sampling is 4 s-class), lanczos exact-fit, ceiling 2 MP;
   Z-Image — sample at 1 MP, lanczos fit, ceiling 1 MP hard (soft lanczos
   up to 2 MP labelled). Ceilings also bounded by the 16 MiB response cap
   and the adapter attachment ceilings (M1-800).
4. **krea2RealVae ships as Krea's recommended decoder** (drop-in via stock
   VAELoader, zero image change); stock `qwen_image_vae` remains the
   fallback (right choice for text-heavy renders per fblissjr's
   decoder-isolation read).
5. **spacepxl 2× ships in v1** (user decision: before first ship,
   accepting the image re-validation and the undeclared-weight risk):
   `prod/images/comfyui/` gains spacepxl/ComfyUI-VAE-Utils (MIT) and the
   M1-797 load-bearing flags are re-verified with the node present; the
   wizard downloads the two VAE files into the models dir with preflight
   like the checkpoints.
6. **Stack bump (ROCm 7.14 / flash-attn gfx1151) deferred** to a
   post-ship ticket (~18–24 % per uncompiled.tools; requires full
   re-measurement and wizard-number refresh).
7. **Ticket impact:** M1-798 is re-analyzed before start — the picker
   gains the VAE choice (krea2RealVae recommended / stock fallback), the
   per-model steps (4/8/6), the new container numbers (22.41/22.14/22.54
   Krea row family), license printing for community assets, and the VAE
   downloads; a small carve (image node add + flag re-verification) rides
   with it or lands as its own ticket per the re-analysis. M1-803 keeps
   the guardrail: `--resolution` wired as output contract, per-job latent
   dims serializer-set (the converter's only per-job numeric inputs).

All model candidates are 4× native; exact targets are reached by
model-upscale then scale-to-size (the converter's tail). REJECTED:
SUPIR-class diffusion upscalers (wall clock + VRAM) and custom-node
upscalers like UltimateSDUpscale (image change + supply-chain surface) —
the stock tiled ImageUpscaleWithModel already handles memory.

**Late addition (2026-08-09, user-raised): NVIDIA PiD — license-blocked,
exploratory.** PiD (Pixel Diffusion Decoder, arXiv:2605.23902) reformulates
the latent→pixel DECODE as a conditional pixel-space diffusion model:
decode + 4× super-resolution in one 4-step-distilled pass, up to 4K.
Comfy-Org repackages checkpoints for the flux / flux2 / qwenimage latent
spaces. Fit notes, all verified against the tree:

- **Stock support at the PINNED commit** — `comfy_extras/nodes_pid.py`
  (PiDConditioning node) is already in 6f7cd7fc; no commit bump, no custom
  nodes, no image change.
- **All three models' latent spaces are covered:** Z-Image(-Turbo) is named
  in the PiD card (flux format); Flux2's 128-channel latents (Mage-Flow's
  format) auto-detect under `flux`; Krea 2 is FLUX.2-based (VERIFY its VAE
  is the flux2 VAE at measurement time).
- **The P15 property survives:** PiDConditioning consumes the SAME positive
  conditioning — user text still reaches exactly one string field.
- Footprint ~2.6 GB bf16 (~1.3 GB int8_convrot; int8 buys no speed on
  gfx1151 per the tier measurement) — small next to the checkpoints.
- It elegantly dissolves Z-Image's 1024 training lock-in: sample native
  1024, decode to 2K/4K.

**Blockers:** (1) LICENSE — NSCLv1, "only ... non-commercial (research or
evaluation) purposes"; the wizard's curated picker is a ship path and the
project selects on license sanity (Mage-Flow's MIT is recorded in §B2).
User attestation 2026-08-09: this deployment has no commercial plans, so
PiD enters the measurement; the curated-picker question re-opens if the
deployment's nature ever changes (same rule as the NC-licensed ESRGAN
candidates above). (2) UNMEASURED on gfx1151 — 4 pixel-space steps at
2K–4K in bf16, co-resident with the LLM stack; no numbers exist.
(3) BUDGETS — it replaces VAEDecode (a pipeline-shape change, not an added
tail), and 4K PNGs exceed the 16 MiB response cap (sized for 2048px),
forcing a cap + adapter-ceiling review. Disposition: enters the measurement
spike as an EXPLORATORY candidate; if the numbers are compelling the
curated-picker license question gets its own discussion, otherwise it
closes. The ship path stays the ESRGAN family.

**PiD wiring — verified 2026-08-09 against the official Comfy-Org template
(`utility_pid_latent_upscale_dit`) and the pinned commit:** the upscale pass
is UNETLoader(pid checkpoint) + its OWN text encoder (CLIPLoader type
`pixeldit`, the Gemma 2 2B encoder — bf16 on gfx1151, fp8 buys nothing) +
CLIPTextEncode(prompt) + PiDConditioning(positive, base-graph latent,
latent_format, degrade_sigma=0) + EmptyChromaRadianceLatentImage at the
TARGET output WxH (the pixel-space noise latent — this is where the
converter's per-job target lands) + SamplerCustom(KSamplerSelect `lcm`,
BasicScheduler `simple`, 4 steps, cfg 1) + VAEDecode with the built-in
`pixel_space` no-op VAE. Two template consequences for the client: the
prompt reaches a SECOND CLIPTextEncode (the P15 "exactly one string field"
wording needs the serializer-fills-both-fields amendment, or the PiD encode
bakes a static text — a measurement question), and the per-job target size
lands as the pixel-latent dimensions (numeric, serializer-set).

**Measurement results 2026-08-09 (spike, container, M1-797 protocol; graphs
and samples under /tmp/opencode/img-measure/):**

| Config (steady-state mean, 5 runs) | s | Output |
|---|---|---|
| Krea 2, 6 st @ 0.6 MP (base) | 22.41 | 896×672 |
| Krea 2, 6 st @ 1 MP (base) | 39.55 | 1024² |
| Krea 2 base + lanczos → 1 / 2 MP | 22.50 / 22.61 | scaling is free |
| Krea 2 base + RealESRGAN_x4plus → 1 / 2 MP | 44.47 / 45.19 | +22 s |
| Krea 2 base + 4x-UltraSharp → 1 / 2 MP | 44.93 / 45.07 | +22 s |
| Mage-Flow, 4 st @ 1024 (base) | 4.07 | 1024² |
| Mage-Flow + RealESRGAN → 1 MP | 48.76 | +44 s |
| Mage-Flow + lanczos → 1 / 2 MP | 4.06 / 4.06 | free |
| Z-Image, 8 st @ 1024 (base, steady state) | 22.37 | 1024² |

Findings: (1) **the ESRGAN family is dead on gfx1151** — the RRDB CNN adds
20–45 s regardless of target (ROCm has no fast path for it), versus the
"few seconds" §B2 assumed; lanczos is free but synthesizes nothing. (2) Krea
2's 6 st @ 0.6 MP container number validates the conda 23.78 s (0.94×) and
lands at Z-Image's 1 MP time — the sweet-spot hypothesis holds. (3) The
Z-Image 28.64 s first-pass figure was a warm-up/autotune artifact; steady
state 22.37 s matches M1-797's 21.81 s. (4) **PiD's cost profile is the
opposite of ESRGAN's** (added time ≈ 2 s @ 1 MP, 5 s @ 2 MP, 15 s @ 4 MP,
~163 s @ 16.8 MP — scales with output pixels) BUT **every PiD output came
out BLACK** with graphs wired exactly per the official template (ELM Gemma,
lcm/simple/4/cfg 1, pixel_space VAE, target-size pixel latent). The PiD
timings are therefore mechanical only and meaningless until the blank
output is fixed; debugging moves to the conda env by hand, against the
official template (`utility_pid_latent_upscale_dit`, in
comfyui-workflow-templates ≥ 0.11.31) and PR #14103's attached examples.
The spike's ship-relevant verdict stands on the ESRGAN/lanczos numbers.
(5) **PiD-on-AMD is broken upstream — disposition closed 2026-08-09.**
Comfy-Org/ComfyUI#14273 (gfx1201/RX 9070 XT, ROCm): PiD outputs noise at
upscaled targets and green-tinted images at 1× targets, with BOTH
nvidia/PiD's own inference code and the official PR-#14103 workflow, while
every other workflow on the same setup works; closed as not planned, no
fix. Our gfx1151 black outputs are the same failure family on a second AMD
architecture — not a graph bug. PiD is therefore excluded on this
deployment hardware unless someone debugs the ROCm pixel-space-DiT path
themselves; the manual conda session can confirm with Merserk's
`pid_upscale_complete.json` (LoadImage → PiDUpscale) as the A/B, but the
expected result is broken. Remaining upscale-ish levers, untested and
ROCm-plausible (plain VAE decodes): krea2RealVae drop-in sharpness and
spacepxl Wan2.1-VAE-upscale2x decode-time 2× (fblissjr/krea-explorations
docs/krea2_vae.md), plus the base-stack speedup lever (ROCm 7.14 /
flash-attn gfx1151 builds, ~18–24% per uncompiled.tools and the kyuz0
toolbox).

**Ticket impact.** M1-798's brief changes (template content, the upscaler
download + option in the picker, ETA probe measuring the FINAL template) —
re-analyze before it starts; the `m1/M1-798-...` worktree has no commits
yet, so nothing is lost. M1-803 is unaffected in scope but carries the
guardrail: wire `--resolution` as an output-size contract, template-opaque
(never assume sampling resolution == output resolution). The M1-802 client
needs no change to carry the stage itself; the per-job target-size input
lands with the converter decision.
