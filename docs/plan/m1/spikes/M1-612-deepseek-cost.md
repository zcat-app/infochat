# M1-612 spike — DeepSeek per-post cost of the split ingest metadata pipeline

Status: spike findings, not spec. Measures only; batching, prompt reordering, or
any routing/prompt/pipeline change is out of scope (M1-612 §out_of_scope) and a
separate follow-up gated on this measurement.

Date: 2026-07-12 · Model: `deepseek-v4-flash` (reasoning OFF, production default)
· Corpus: the live `post` table (5272 posts, fetched 2026-07-06…07-11) ·
Harness: `M1-612-cost-eval.py` over `M1-612-cost-samples.jsonl` (50 real posts).

## Question

The collector runs three **separate** metadata LLM calls per post — TAGGER,
ENTITY, CLASSIFIER (`TaggerWorker` / `EntityExtractorWorker` / `ClassifierWorker`,
three independent `provider.generate()` calls) — and each re-sends the full post
body inside its own prompt. Batching the three into one call would cut the
redundant body tokens but trade away the split design's independent per-task
degradation (`bootstrap_tags` / no-entities / `unknown`). Before that trade can be
judged: **how much does the 3× body re-send actually cost on DeepSeek v4-flash at
production volume, and does DeepSeek's automatic prefix caching already discount
it?**

## TL;DR recommendation

**Do not pursue batching or prefix-cache restructuring for cost reasons — keep the
split design.** The redundant 2× body re-send that batching would remove is worth
**≈ $1.1 / month** at the current ~800 posts/day (24k/month); the whole
three-call metadata pipeline costs **≈ $5 / month**. Both are immaterial next to
the split design's independent per-task degradation, which batching would forfeit
(one schema failure would fail all three of tagging, entities, and
classification instead of one). Two findings sharpen this:

1. **The body is not the dominant cost — the fixed scaffolds are.** Per post the
   three prompts carry **≈ 1079 scaffold tokens vs ≈ 350 body tokens** (input is
   ~75% fixed instructions/vocabulary, ~25% post content). Even eliminating *two
   of the three* body copies removes only ~16% of input tokens.
2. **DeepSeek prefix caching discounts nothing today: 0 cache-hit tokens across
   all 153 measured calls.** So 100% of every prompt — scaffold *and* body — is
   billed at the full cache-miss rate. The body is a per-post-unique **suffix**
   (untrusted content is placed last, by design), so it can *never* be a prefix
   cache hit under any timing — the "3× body" is structurally always paid at full
   price, and "body-as-prefix" restructuring is a worse lever than batching (it
   needs caching that isn't firing *and* a security-regressing reorder of
   untrusted content, for the same ~$1/month).

Revisit only if volume grows ~100× or v4-flash pricing changes materially — re-run
this harness to get the new figure.

## Pricing

DeepSeek "Models & Pricing", `deepseek-v4-flash`, USD per 1M tokens
(https://api-docs.deepseek.com/quick_start/pricing, verified 2026-07-12;
cross-checked against third-party trackers reporting the same figures for
July 2026):

| | cache-hit input | cache-miss input | output |
|---|---|---|---|
| USD / 1M tokens | $0.0028 | $0.14 | $0.28 |

Cache-hit input is 50× cheaper than cache-miss. `deepseek-chat` is the deprecated
alias for v4-flash non-thinking mode (sunset 2026-07-24). Override in the harness
with `--price-hit/--price-miss/--price-out` when the sheet changes.

## Method

Production-faithful, mirroring `OpenAiCompatibleProvider.doCall` +
`DeepSeekProvider`:

- **Prompts.** `tagger.md` and `classifier.md` are read from the production
  resources (so the harness tracks prompt edits); the entity prompt is copied
  byte-for-intent from `EntityExtractorWorker.PROMPT_TEMPLATE`. `{{id}}` rotates
  to a fresh uuid per call; the tagger `{#tags}` block is expanded once per
  vocabulary tag exactly as `TaggerWorker.renderPrompt` does.
- **Vocabulary.** The deployed **23-tag** controlled vocabulary (the corpus `tag`
  table == the runtime `bootstrap-sources.json` union), not the 7-tag committed
  seed. The tagger scaffold scales with this count.
- **Wire body.** `{model, max_tokens: 1024, messages:[{system:""},{user}],
  thinking:{type:disabled}}`. No `temperature` (production omits it). `max_tokens`
  is the `.orElse(1024)` default — the three metadata tasks set no per-task
  override, and their completions (≈ 10–40 tokens) run far below the cap, so the
  cap does not affect cost.
- **`{{body}}`** ← the raw `post.body` column (all three workers `SELECT p.body`,
  never `body_summary`), including its stored HTML.
- **Body-vs-scaffold split.** One scaffold-only call per task (empty title+body)
  gives the fixed scaffold token count; per-post body tokens = full prompt_tokens
  − scaffold_tokens (the marginal, always-cache-miss share the title+body add).
- **Cache accounting.** `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`
  captured per call; each task's calls run in sequence so DeepSeek's cache is
  populated across same-task requests.
- **Sample.** 50 real posts (`ORDER BY md5(id::text) LIMIT 50`). Sample mean
  content length 613 chars (34% have an empty body — title-only social posts);
  the **corpus** mean is 860 chars (median 397, p95 2055, max 39408 — right-
  skewed). Per-post production figures below are anchored on the corpus mean via
  the measured chars-per-token ratio (5.21), since the sample mean undershoots the
  skewed corpus mean.

## Measured per-task token usage (sample mean, n=50)

| Task | scaffold tok | mean prompt tok | body tok | completion tok | cache-hit tok | cost / post |
|---|---|---|---|---|---|---|
| TAGGER | 401 | 524.6 | 123.7 | 11.3 | **0** | $0.0000766 |
| ENTITY | 191 | 305.9 | 115.0 | 39.3 | **0** | $0.0000538 |
| CLASSIFIER | 487 | 600.1 | 113.6 | 10.0 | **0** | $0.0000868 |
| **Sum / post** | **1079** | **1430.6** | **352.3** | **60.6** | **0** | **$0.0002173** |

- Body tokens are ≈ equal across the three tasks (~115–124), confirming the same
  body text is re-charged in each — the redundancy the batch-vs-split decision
  turns on.
- Scaffolds dominate: 1079 fixed tokens/post vs 352 body tokens/post. The
  classifier scaffold (487, the full 6-label set) and tagger scaffold (401,
  instructions + 23-tag vocabulary) are the largest fixed costs.

## Cache accounting (acceptance item 3)

**Every one of the 153 calls returned `prompt_cache_hit_tokens = 0`**
(`prompt_cache_miss_tokens == prompt_tokens` for all). DeepSeek's automatic prefix
cache discounted nothing. Two compounding causes, per the DeepSeek KV-cache docs
(https://api-docs.deepseek.com/guides/kv_cache): the cache "only matches the
prefix part" and "cache construction takes seconds" —

- **Timing.** The harness fires same-task calls ~1.1 s apart, faster than cache
  construction, so consecutive requests raced ahead of the still-building cache.
  Production spaces these calls out (scheduled pollers), so production *scaffolds*
  might see some cache hits this rapid measurement did not — i.e. these figures
  are a **conservative upper bound on cost**.
- **Prefix placement.** The per-call random `{{id}}` is substituted *above* the
  scaffold in `tagger.md` (line ~6, above the vocabulary) and entity (~line 8), so
  the cacheable shared prefix is only the few tokens before the first `{{id}}` —
  the vocabulary/instructions after it can never be a prefix hit. `classifier.md`
  places the id after the whole label set, so its scaffold is prefix-cacheable *in
  principle*; the timing above still yielded 0.

**Timing-independent conclusion:** the post body sits *after* the instructions
inside the delimiter wrapper (untrusted-content-last, deliberate — see security
note below), so it is a per-post-unique **suffix** and can never be a prefix cache
hit no matter how calls are spaced. The redundant body is therefore always billed
at the full cache-miss rate. Prefix caching is a lever for the *scaffold*, not the
body — and even for the scaffold it would only help within a task across posts,
not across the three tasks of one post (their scaffolds differ).

## Cost figures

Per-post, split design (sample-measured, all-cache-miss):

- **$0.0002173 / post** → **$0.217 / 1000 posts**.
- Input $0.0002003 (92%) + output $0.0000170 (8%). Input is 1430.6 tok ×
  $0.14/1M; output 60.6 tok × $0.28/1M.
- Corpus-anchored (body scaled from ~117 to ~165 tok/task at the 860-char corpus
  mean): **≈ $0.000237 / post → ≈ $0.24 / 1000 posts**.

The specific amount batching would remove — **the redundant 2× body re-send**
(acceptance item 2). The body is always cache-miss, so saving = 2 × body_tokens ×
$0.14/1M:

- Sample body (~117 tok/task): **$0.0000329 / post**.
- Corpus-anchored (165 tok/task): **$0.0000462 / post** — i.e. batching removes
  ~21% of the per-post cost. (Batching would also merge the three scaffolds into
  one combined prompt; that combined scaffold ≈ the sum of the three instruction
  sets, so it saves little beyond the duplicated wrapper/system boilerplate — the
  body is the only clean saving.)

## Extrapolation to production volume (acceptance item 4)

**Assumption: ~800 posts/day.** Source: the corpus `fetched_at` daily counts —
884 (07-07), 896 (07-08), 627 (07-10) → steady-state mean ≈ 800/day; the
2026-07-06 bootstrap backfill (2593) is excluded as one-off. → **24,000
posts/month**.

| Figure | Monthly (24k posts) |
|---|---|
| Total split metadata pipeline (corpus-anchored) | **≈ $5.7** |
| Total split metadata pipeline (sample-measured) | $5.2 |
| **Redundant 2× body (what batching removes)** | **≈ $1.1** |

Cost scales linearly with posts/day: doubling the source set to ~1600 posts/day
still lands the redundant-body saving at only ~$2.2/month.

## Recommendation

The redundant-body cost (~$1.1/month) is **not material** — it does not justify
batching, whose real cost is forfeiting the split design's independent per-task
degradation (a schema failure in one task currently degrades only that task; a
batched call fails all three together — and entity extraction is the least
reliable of the three, see M1-613). Prefix-cache restructuring is also not worth
pursuing: the body cannot be cached (unique suffix), and the security posture
forbids moving it ahead of the instructions.

If cost ever *does* matter (≫10× volume, or a large v4-flash price rise), the
larger lever is the **fixed scaffold** (75% of input tokens), not the body:
getting DeepSeek prefix caching to actually fire on the scaffold — by spacing
calls beyond cache-construction latency and moving the random `{{id}}` below the
vocabulary in `tagger.md` so the vocabulary becomes a cacheable prefix — would
discount the scaffold at the 50× cache-hit rate. That is a separate,
security-reviewed follow-up, not this ticket.

## Caveats

- **Figures are the success-path floor (1 call/task).** Each worker retries once
  on a failed or schema-violating parse — entity and classifier retry the same
  prompt shape, the tagger retries with the smaller `tagger-fallback.md` prompt
  then falls back to deterministic `bootstrap_tags` (no LLM). So a post that
  triggers a retry costs up to ~2× for that task; entity is the most retry-prone
  (schema violations, though M1-613's lenient parser cut that). The retry
  multiplier is bounded by ~2× and does not change the materiality verdict (even
  a uniform 2× lands the pipeline near ~$10/month). It also *reinforces* keeping
  the split: a batched all-or-nothing call would re-send all three tasks' input on
  every retry, whereas today only the failing task retries.
- **Cache = 0 is timing-conservative for the scaffold**, not the body. A
  production-spaced re-run might show scaffold cache hits and thus a *lower*
  scaffold cost; it would not change the body/batching conclusion (body is never
  cacheable) or the materiality verdict. Documented as a possible follow-up
  measurement, not run here.
- The sample (mean 613 chars) undershoots the corpus mean (860); per-post
  production figures are the corpus-anchored ones. The very long tail (max 39408
  chars ≈ 7.5k tokens) is rare and does not shift the mean-driven monthly total.
- Security note (carried from the ticket): the tagger/entity/classifier prompts
  deliberately place the untrusted body **after** the instructions inside a
  delimiter wrapper. Any body-first reorder is a prompt-injection-surface change —
  a security-review item, not a free optimization.

## Reproduce

```bash
export INFOCHAT_LLM_API_KEY=...        # from prod/runtime/secrets.env
# Regenerate the sample when the corpus changes:
docker exec infochat-postgres-1 psql -U postgres -d infochat -tAc \
  "SELECT row_to_json(t) FROM (SELECT id::text AS id, title, body \
     FROM post ORDER BY md5(id::text) LIMIT 50) t;" \
  > docs/plan/m1/spikes/M1-612-cost-samples.jsonl
# Run against the deployed 23-tag vocabulary:
python3 docs/plan/m1/spikes/M1-612-cost-eval.py --model deepseek-v4-flash \
  --bootstrap-sources prod/runtime/bootstrap-sources.json \
  --out .scratch/m1-612/cost.json
```

Raw run: `.scratch/m1-612/cost.json` (2026-07-12).
