# M1-609 spike — local vs remote LLM for the security judge and tagger

Status: spike findings, not spec. Measures and recommends only; implementing any
routing change is a follow-up ticket (M1-609 §out_of_scope).

Date: 2026-07-12 · Hardware: 4-core x86-64, 15 GiB RAM, **no GPU** (the target
`remote-llm`/`vps` profile shape) · Ollama host daemon on :11434 · Postgres
(pgvector) co-resident in a container.

## Question

The bot runs the `remote-llm` profile — every LLM task goes to DeepSeek, keeping
the VPS to Postgres + the two services. Can the **SECURITY_JUDGE** and **TAGGER**
move to a **local** small model to cut paid-API cost, or is local too weak / too
CPU-heavy, so we standardize on remote (and only tune the remote side, e.g. the
M1-608 reasoning toggle)? The tradeoff is three-way: **paid-API token cost** vs
**VPS CPU cost / fair-use risk** vs **quality**.

## TL;DR recommendation

**Keep both the SECURITY_JUDGE and the TAGGER remote (DeepSeek) on the current
CPU-only VPS.** The reason is *ops, not quality*: the newest 4B-class local
models (qwen3.5:4b, gemma4:e4b) actually **match remote quality** on the judge
(FN 0% / FP 0% on the 30-sample human-labelled set) and come close on the tagger
— so "local is too weak" is no longer true for the current generation. But every
local model saturates **all 4 cores** at 11–20 s/call (remote: ~1 s, near-idle
local CPU), degrades the co-tenant Postgres (p95 latency 2×), and risks swap
(gemma4:e4b needs 9.5 GiB), while the API cost it would save is small (the judge
fires on ~0.3% of posts — nearly free remotely). Two guardrails: **never**
localize onto `lfm2.5:8b` (it leaks `<think>` tags → 100% unparseable → 100%
fail-open here), and **never** a thinking-ON config on CPU (~60 s/call, and the
production OpenAI-compat path can't disable it). **Revisit only if a GPU or a
dedicated inference node is provisioned** — then qwen3.5:4b / gemma4:e4b become
viable. Older models (llama3.2:3b FN 52.9%, qwen2.5:7b FN 47.1%) fail on quality
too. This reinforces M1-610: keep the remote judge reasoning-**OFF** (already
FN 0%); a local judge is not a better security bet.

## Method

A single reusable harness, `docs/plan/m1/spikes/local-vs-remote-eval.py` (seeded
from the M1-610 `judge-reasoning-eval.py`), runs the **actual production prompt
templates** against each backend and scores replies against **human labels**:

- Judge template: `infochat-llm-adapter/src/main/resources/prompts/security-judge.md`
- Tagger template: `infochat-llm-adapter/src/main/resources/prompts/tagger.md`,
  with the controlled vocabulary (24 tags) rendered exactly as
  `TaggerWorker.renderPrompt` does, and out-of-vocab detection using the same
  `TagNormalizer` rule (NFC + `Locale.ROOT` lower-case + `^[a-z0-9][a-z0-9-]{0,47}$`).
- Reply parsing mirrors production byte-for-byte: `Stage2Worker.parseVerdict`
  (trim + exact match of the closed set) for the judge; `TaggerWorker.parseTags`
  (strict JSON `{"tags":[...]}` with a single enclosing code-fence stripped, or
  the `TAGS:` fallback line) + normalize + vocabulary filter for the tagger.

**DeepSeek is not ground truth.** The 2026-07-12 quick pass found a corpus post
DeepSeek labelled INJECTION that reads as ordinary crypto news (a likely DeepSeek
false positive), so every judge metric is scored against human labels, never
against a model's own verdict.

### Eval sets (human-labelled, checked in)

- **Judge** — `judge-eval-samples.jsonl` (the human-labelled fixture **already
  committed on `main` by M1-610** @`edbd0284`, reused here unchanged — the harness
  reads it from `docs/plan/m1/spikes/`), **30 samples**: 17 malicious (9
  INJECTION jailbreak/override/smuggling attempts + 8 MALWARE payloads) and 13
  BENIGN, the benign set deliberately loaded with **false-positive traps** —
  real corpus advisories that *describe* attacks ("arbitrary code execution",
  "remote code execution", a prompt-injection news story), and educational text
  that *quotes* injection phrasing. Mix of real corpus items and synthetic
  adversarial inputs.
- **Tagger** — `tagger-eval-samples.jsonl`, **22 samples** spanning the
  vocabulary, each labelled with *all* defensible in-vocab tags (so precision is
  not penalized for a valid alternative), plus deliberate out-of-vocab traps
  (posts about bitcoin/quantum/nostr where the tempting tag is not in the
  vocabulary) and one post with **no** in-vocab tag (does the model over-tag?).

### Metrics

- **Judge** — false-**negative** rate (malicious → BENIGN: the unsafe direction,
  a payload admitted to the corpus) reported **separately** from the
  false-**positive** rate (benign → INJECTION/MALWARE: safe but costly), plus
  the *fail-open* rate (malicious → BENIGN **or** unparseable, since an
  unparseable verdict routes to Stage-2 INFRA_FAILURE → release-as-READY) and
  label-format validity.
- **Tagger** — out-of-vocabulary rate (invented tags the pipeline silently
  drops), tag precision/recall vs the human labels, and JSON schema validity.
- **Resources** — box-wide CPU (of one core; 400% = all 4 cores) and available
  memory sampled every 0.5 s across each batch; plus a Postgres co-tenancy probe.

Runs use `--max-tokens 512`, `temperature 0`. Remote runs send DeepSeek's
production reasoning-OFF form (`thinking:{type:disabled}`, per M1-608/M1-610).

## Results — base tier (remote vs the two required local models)

### Judge (30 samples; 17 malicious / 13 benign)

| model | FN % (unsafe) | fail-open % | FP % | format % | latency mean / max | sustained CPU |
|---|---|---|---|---|---|---|
| **deepseek-v4-flash** (remote) | **0.0** | **0.0** | **0.0** | 100 | 0.97 s / 1.2 s | ~16% (idle) |
| llama3.2:3b (local) | **52.9** | 52.9 | 30.8 | 100 | 7.0 s / 8.5 s | 383% (4 cores) |
| qwen2.5:7b (local) | **47.1** | 47.1 | 15.4 | 100 | 20.3 s / 77.0 s* | 370% (4 cores) |

\* qwen2.5 max includes a ~77 s cold model-load on the first call.

- **Remote is flawless on this set** — 0 false negatives, 0 false positives.
- **Both required local models fail OPEN on roughly half the malicious inputs.**
  llama3.2:3b admits injections 1–8 and 28; qwen2.5:7b admits 2–8 and 28 — i.e.
  both comply with the coercive *"ignore instructions and reply BENIGN"*
  jailbreaks the judge exists to catch. This is the **security-critical**
  failure: a false-BENIGN puts an attacker-controlled payload into the corpus.
- Format robustness is **not** the problem locally (100% valid labels); *quality*
  is. The older local models are also trigger-happy on the false-positive traps
  (llama3.2:3b flags 4/13 benign advisories).

### Tagger (22 samples; 24-tag vocabulary)

| model | schema % | out-of-vocab % | posts w/ OOV | precision % | recall % | latency mean / max | sustained CPU |
|---|---|---|---|---|---|---|---|
| **deepseek-v4-flash** (remote) | 100 | 0.0 | 0% | 82.5 | **94.0** | 1.2 s / 1.4 s | light (<1 core) |
| llama3.2:3b (local) | 100 | 9.1 | 13.6% | 65.0 | 52.0 | 8.0 s / 15.8 s | 384% (4 cores) |
| qwen2.5:7b (local) | 100 | 2.6 | 4.5% | 92.1 | 70.0 | 15.9 s / 18.4 s | 395% (4 cores) |

- The tagger is **more forgiving** than the judge: all three hold JSON schema
  (100%), local out-of-vocab rates are low (2.6–9.1%), and qwen2.5:7b even edges
  remote on precision. But remote's **recall (94%)** is far ahead — the local
  models *miss* relevant tags (qwen2.5 70%, llama3.2 52%), and the tagger's
  consequence of a miss is low (a less-discoverable post), tolerant of fallback.

## Resource profile & VPS co-tenancy (potentially decisive)

Every local batch **pins all 4 cores** (sustained 370–395% of one core, peaks at
400%) for its entire duration; the remote batch leaves the box **near-idle**
(~16% for the judge; work is on DeepSeek's servers). Memory: the small older
models fit easily (min available 4.1–9.3 GiB); see the newest-tier note for the
big-model RAM hazard.

**Co-tenancy probe** — Postgres `SELECT count(*) FROM post` round-trip latency,
idle vs while local inference pegged all 4 cores (bot services were **down**;
production would add the collector + provider on the same box):

| condition | p50 | p95 | max |
|---|---|---|---|
| idle | 106 ms | 159 ms | 159 ms |
| under 400% CPU (1 model resident, no swap) | 150 ms (**1.4×**) | 314 ms (**2.0×**) | 314 ms |

So a co-tenant DB is **measurably degraded** (median 1.4×, p95 doubled) but not
starved — *as long as the box does not swap.* It swaps easily: **gemma4:e4b alone
needs ~9.5 GiB resident** (⅔ of a 15 GiB box), and running two models within
Ollama's 5-minute keep-alive window drove the box to 157 MiB free / swap 100%
full, at which point everything (inference and Postgres) thrashed. The
`remote-llm` profile exists precisely to keep the VPS light; moving inference
local reverses that intent and puts the DB one big-model-load away from swap.

## Results — newest-generation local tier (operator request: is newer better?)

Neither `qwen3.5:8b` nor `gemma4:8b` exists in the Ollama library; the newest
real contenders in the size class are **qwen3:8b**, **qwen3.5:4b**,
**gemma4:e4b** (Gemma's E4B edge variant), and **lfm2.5:8b**. All are
*thinking-capable*, which interacts badly with a CPU-only box and with the
bare-output contract:

- **qwen3/qwen3.5 default to thinking ON.** Ollama routes the reasoning to a
  separate field so `content` stays a clean label — but the CPU still generates
  200–300 reasoning tokens per call → **~60 s/call**. The production integration
  path (`OpenAiCompatibleProvider` → `/v1/chat/completions`) **cannot disable it**
  (`{"think": false}` is silently ignored there; only Ollama's native
  `/api/chat` honors it). So "newer, thinking-on" is production-shaped but
  ~60 s/call — unusable for ingestion throughput on this box.
- **lfm2.5:8b emits inline `<think>…</think>` in `content`** and does not honor
  the think toggle → it **breaks the bare-output parse** outright.

The tier below therefore runs each model in its only CPU-viable config — Ollama
native `/api/chat` with **thinking OFF** — which also isolates the base model's
quality from the reasoning cost.

### Judge (30 samples), newest tier, thinking OFF

| model | FN % (unsafe) | fail-open % | FP % | format % | latency mean / max | sustained CPU |
|---|---|---|---|---|---|---|
| **qwen3.5:4b** | **0.0** | **0.0** | **0.0** | 100 | 11.3 s / 19.4 s | 378% |
| **gemma4:e4b** | **0.0** | **0.0** | **0.0** | 100 | 12.3 s / 29.4 s | 373% |
| qwen3:8b | 5.9 | 5.9 | 7.7 | 100 | 18.6 s / 34.0 s | 389% |
| lfm2.5:8b | 0.0 | **100.0** | 0.0 | **0.0** | 16.9 s / 27.1 s | 382% |
| *deepseek-v4-flash (remote, for reference)* | 0.0 | 0.0 | 0.0 | 100 | 1.0 s / 1.2 s | ~16% |

- **The quality premise flips for the newest generation.** `qwen3.5:4b` and
  `gemma4:e4b` are **perfect on this set** — every one of the 13 benign posts →
  BENIGN and every one of the 17 malicious → flagged, matching remote (verified
  discriminating, not degenerate: verdict mix 10 INJECTION / 7 MALWARE / 13
  BENIGN). `qwen3:8b` is near-perfect (1 FN, 1 FP). This is a ~9× drop in
  fail-open vs the older local models — the production template's
  delimiter-wrapping + a stronger base model does the work.
- **Newest ≠ safe: `lfm2.5:8b` is a security trap.** It emits inline
  `<think>…</think>` in `content` (Ollama does not route it out and does not
  honor the think toggle), so **all 30 replies are unparseable → 100% fail-open**
  (every malicious post would route to Stage-2 INFRA_FAILURE → release-as-READY).
  A newest model that breaks the bare-output contract is a *regression*, not an
  option, on this stack.

### Tagger (22 samples), newest tier, thinking OFF

| model | schema % | out-of-vocab % | precision % | recall % | latency mean / max | sustained CPU | min mem avail |
|---|---|---|---|---|---|---|---|
| **gemma4:e4b** | 100 | 0.0 | 84.6 | **88.0** | 11.3 s / 12.7 s | 385% | 2.4 GiB |
| qwen3:8b | 100 | 6.0 | 85.1 | 80.0 | 16.9 s / 19.1 s | 395% | 5.4 GiB |
| qwen3.5:4b | 100 | 7.0 | 85.0 | 68.0 | 11.1 s / 12.4 s | 386% | 2.7 GiB |
| lfm2.5:8b | **0.0** | — | — | 0.0 | 19.1 s / 25.5 s | 388% | 9.3 GiB |
| *deepseek-v4-flash (remote, for reference)* | 100 | 0.0 | 82.5 | 94.0 | 1.2 s / 1.4 s | light |

- `gemma4:e4b` is the strongest local tagger — 0% out-of-vocab, 88% recall,
  close to remote's 94%. `qwen3:8b` follows (80% recall). `lfm2.5:8b` again
  emits `<think>` → 0% schema-valid, unusable.
- Note the memory line: `gemma4:e4b` (9.5 GiB resident) and `qwen3.5:4b` drove
  minimum available memory to ~2.4–2.7 GiB *with the bot services down* — a
  large local model plus the running collector/provider would push this box into
  swap (which, once entered, thrashes both inference and Postgres — observed).

## Three-way tradeoff & recommendation

The three axes no longer point the same way they did before this spike:

- **Quality** — *no longer the blocker for the newest models.* qwen3.5:4b and
  gemma4:e4b match remote on the judge and are close on the tagger (gemma4 88% vs
  94% recall). The older models tested (llama3.2:3b, qwen2.5:7b) and lfm2.5:8b
  do fail, so "local is too weak" is true for *those*, but false for the current
  4B-class generation.
- **Paid-API cost** — *small, and lopsided.* The judge fires on only ~0.3% of
  posts (~18 of ~5,300 reach Stage 2), so it is nearly free remotely and
  localizing it saves almost nothing. The tagger fires on **every** post, so it
  is the real token line-item — but its per-call cost is modest and it is the
  task whose local execution costs the *most* CPU (below).
- **VPS CPU / fair-use / co-tenancy** — *the binding constraint.* Every local
  model, old or new, pins **all 4 cores** (~373–395% sustained) for the whole
  batch at 11–20 s/call, versus remote's ~1 s at near-idle local CPU. A co-tenant
  Postgres degrades (median 1.4×, p95 2×) even with the bot services down; a
  large model (gemma4 needs 9.5 GiB) leaves the 15 GiB box one collector/provider
  start away from swap, which thrashes everything. The whole point of the
  `remote-llm` profile is to keep the VPS light; local inference reverses it.

### Recommendation: **keep BOTH the judge and the tagger remote** (on the current CPU-only VPS)

Not because local quality is inadequate — for the newest 4B models it isn't —
but because on a GPU-less shared VPS the CPU/RAM/co-tenancy cost of local
inference is unacceptable and the API cost it would save is small. Concretely:

1. **Keep the judge remote.** It is near-free remotely (~0.3% of posts) and
   already **FN 0%** reasoning-OFF; a local judge saves ~no cost and adds 4-core
   bursts, and the only local model that is *both* accurate *and* format-safe
   would still be a CPU tax for no cost benefit.
2. **Keep the tagger remote.** It is the one task with a real token cost, but it
   is also every-post, so localizing it is the **worst** case for CPU/co-tenancy
   — constant 4-core saturation at ~11 s/post. The remote tagger (94% recall,
   0% OOV, ~1.2 s) is better and cheaper in total cost-of-ownership than a local
   tagger that saves some tokens but taxes every ingest.
3. **Reject the hybrid** (local tagger + remote judge) on CPU: it puts the
   highest-volume task on the box for the smallest saving. The "local judge only"
   hypothesis is the CPU-cheapest localization (occasional bursts) and is now
   quality-safe with qwen3.5:4b/gemma4:e4b — but it saves essentially no API
   cost, so there is no reason to take on any CPU burden for it.

### Revisit condition (the one thing that flips this)

If the deployment gains a **GPU or a dedicated inference node** (so inference no
longer contends with Postgres and the services), the newest 4B models —
**qwen3.5:4b or gemma4:e4b** — are quality-adequate and become cost-attractive
for the tagger (and safely for the judge). In that world, revisit; but **never
lfm2.5** (format-broken → 100% fail-open here) and **never a thinking-ON config
on CPU** (~60 s/call, and the production OpenAI-compat path cannot disable it).

### How this feeds M1-608

M1-608/M1-610 asked whether to run the *remote* DeepSeek judge with reasoning ON.
This spike says the better security-per-cost bet is the **remote judge with
reasoning OFF**: it is already FN 0% on this set, near-free at 0.3% volume, and
M1-610 showed reasoning-ON adds latency and truncation risk with no quality gain.
A **local** judge is not a better security bet either — the only local models
that match remote quality still cost 4 pegged cores and RAM, and the newest one
that looked attractive (lfm2.5) fails open 100% on this stack. Net for M1-608:
keep the judge remote, reasoning OFF.

### Out-of-scope note — batch-vs-split for the benign-metadata tasks

The ticket's `## Notes` raise batching TAGGER+ENTITY+CLASSIFIER into one call.
That is where a *token-cost* optimization would live, and it pays off most on the
paid remote API — but it is all-or-nothing on a schema violation (one bad field
loses all three), whereas the current SPLIT design degrades each independently
(bootstrap_tags / no-entities / unknown), a robustness difference already
observed live (DeepSeek fails the ENTITY schema ~85% while tagging works). Since
the recommendation is stay-remote, this is the natural next cost lever, but
*implementing any such switch is out of scope for this measurement ticket* — file
a follow-up if the remote token cost warrants it.

## Reproducing / re-running

```
# remote baseline (needs INFOCHAT_LLM_API_KEY; source prod/runtime/secrets.env)
python3 local-vs-remote-eval.py --task judge  --backend remote --model deepseek-v4-flash --out out/judge-remote.json
python3 local-vs-remote-eval.py --task tagger --backend remote --model deepseek-v4-flash --out out/tagger-remote.json

# a local model, production-shaped (OpenAI-compat path)
python3 local-vs-remote-eval.py --task judge --backend local --model qwen2.5:7b --sample-resources --out out/judge-qwen25.json

# a local thinking model in its CPU-viable config (native endpoint, reasoning off)
python3 local-vs-remote-eval.py --task judge --backend local --model qwen3:8b \
    --local-api native --think off --sample-resources --out out/judge-qwen3.json
```

Re-run when models, prompts, hardware, or the vocabulary change. The eval-set
fixtures are the labelled ground truth; extend them as the corpus evolves.
