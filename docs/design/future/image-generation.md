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

ComfyUI is a manual conda install (`py314`, ROCm 7.13), not a compose service,
and the `pi` and `vps` profiles cannot run it at all. So if
`infochat.image.base-url` is unset, `/image` does not exist: absent from
`HelpCommandHandler.CATALOGUE`, and an unknown-command reply if invoked. This is
capability gating on the D46 adapter-subset precedent, not a back-compat feature
flag, so it does not collide with the no-feature-flags rule.

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

- **Multilingual prompt adherence — a measurement, not a design question.**
  Mage-Flow's `qwen3vl_4b` encoder is broadly multilingual, but diffusion
  prompt-adherence tracks the *caption* distribution the model was trained on,
  which is English-dominant. Run the same scene in en / cs / tr / es on the
  existing install (a few minutes of GPU time) and it settles decision 4.
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
