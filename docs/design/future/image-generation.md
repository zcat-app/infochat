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
`/image` does not exist: absent from `HelpCommandHandler.CATALOGUE`, and a
"image generation is not enabled on this instance" reply if invoked. This is
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
explicit configured-internal exemption.

Generation is **not** an LLM tool and not MCP. ComfyUI exposes plain HTTP, so
deterministic Java calls it directly; exposing generation as an LLM-callable
tool would collide with the deterministic-retrieval principle and widen the
injection surface for nothing. The workflow graph is **built server-side** — the
API accepts a whole graph whose nodes execute Python and touch the filesystem,
so user text must reach exactly one field (`CLIPTextEncode.text`) as a JSON
string value. Anything looser is remote code execution.

## Open items

- ~~Multilingual prompt adherence~~ — **settled 2026-08-07**, see the table in
  §Translation: translate-to-English (decision 4) is required for Mage-Flow and
  Z-Image, optional for Krea 2; the pipeline stays uniform.
- **Attachment size ceilings on SimpleX and Signal** — still unverified
  (`docs/plan/future-features.md`), and they bound the maximum `--ratio`
  however the pixels are produced.
- **ComfyUI stdout** — whether it prints the prompt text, which would land in
  the container log; and whether `/history` is bounded or clearable. Both feed
  the decision-10 ship blocker.
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
The feature is **local-or-absent**: no `infochat.image.base-url`, no command,
and the reply says "image generation is not enabled on this instance". No
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
| Krea 2 Turbo | 25 GB | 8.3 GB (qwen3vl_4b) | 0.24 GB | **~33.5 GB** |

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
