---
id: M1-609
title: "Spike: local vs remote LLM for the security judge and tagger (quality, throughput, VPS CPU cost)"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
provenance: >-
  M1-606/M1-608 discussion + a quick directional test on 2026-07-12 (scripts in
  .scratch). The question: can the SECURITY_JUDGE and TAGGER run on a local
  small model to save paid DeepSeek API cost, or is local too weak/too heavy and
  we should standardize on remote? The quick test (llama3.2:3b, 6 tagger + 8
  judge samples) found: local FORMAT robustness is good (judge 8/8 valid tokens,
  tagger 6/6 valid JSON — not the fragility we feared), but 3b QUALITY is
  mediocre (judge over-flags benign; tagger invents out-of-vocab tags ~1/3 of
  the time, silently dropped) and CPU LATENCY is high (~7-9s/call, all 4 cores
  saturated, no GPU). RAM was a non-issue (3.3 GiB peak, 10.6 GiB free). It also
  found DeepSeek is NOT ground truth (a corpus post DeepSeek labeled INJECTION
  reads as ordinary news — likely a DeepSeek false positive). The tradeoff is
  genuinely non-obvious, hence this spike.
out_of_scope:
  - >-
    Implementing any routing switch. This ticket MEASURES and RECOMMENDS only;
    the actual move (local judge/tagger, hybrid, or stay remote) is a follow-up
    ticket gated on this spike's recommendation.
  - >-
    GPU provisioning or changing the deployment hardware. The spike evaluates
    what the current CPU-only profile can do; a GPU recommendation may be an
    output but procuring one is not this ticket.
  - >-
    Changing the LlmProvider / EmbeddingProvider SPI, the M1-606 circuit breaker,
    or the M1-608 DeepSeek adapter. This is an evaluation, not a code change to
    the routing surface.
  - >-
    Embeddings. They stay local (nomic-embed-text, D54) and are out of this
    comparison, which is about the chat-completion judge and tagger only.
acceptance:
  - >-
    A findings report (docs/plan/m1/spikes/M1-609-local-vs-remote.md or similar)
    comparing, for SECURITY_JUDGE and TAGGER, the current remote DeepSeek
    baseline against at least TWO local models — the llama3.2:3b low baseline
    (already pulled) plus at least one stronger small model in the
    qwen2.5 / gemma2 / phi / llama3.3 class — on a single shared, labelled sample
    set, using the ACTUAL production prompt templates (prompts/security-judge.md,
    prompts/tagger.md) and the controlled tag vocabulary.
  - >-
    Judge metrics are measured against HUMAN-verified labels, NOT DeepSeek
    verdicts (the 2026-07-12 test found a likely DeepSeek false positive, so
    DeepSeek is not ground truth). Report the FALSE-NEGATIVE rate (an
    injection/malware post labelled BENIGN — the unsafe direction that lets a
    payload into the corpus) SEPARATELY from the false-positive rate (a benign
    post flagged — the safe-but-costly direction), plus label-format validity.
    Use a curated set mixing real corpus samples with synthetic adversarial
    inputs, each human-labelled.
  - >-
    Tagger metrics on a labelled sample: the out-of-vocabulary rate (invented
    tags the pipeline silently drops), tag quality (precision/recall or
    human-judged) against the controlled vocabulary, and format validity.
  - >-
    Throughput AND resource profile on the target hardware: per-call latency and
    the SUSTAINED + peak CPU and memory during a realistic ingestion batch, with
    an EXPLICIT assessment of whether that CPU load fits the target VPS's CPU
    allowance and the provider's fair-use policy, INCLUDING co-tenancy impact
    (does local inference starve Postgres and the provider services sharing the
    box?). Starting evidence to extend, not repeat: the 2026-07-12 run — 3b
    saturates 4 cores at ~7-9s/call, RAM ~3.3 GiB (fine), no GPU; CPU is the
    binding constraint, and the remote-llm profile exists precisely to keep the
    VPS light, so moving inference local reverses that intent.
  - >-
    A recommendation making the THREE-WAY tradeoff explicit — paid-API token
    cost vs VPS CPU cost / fair-use risk vs quality — and landing on ONE of:
    keep remote (DeepSeek), move the tagger and/or judge local, or a hybrid
    (e.g. local tagger + remote judge, or the reverse). The recommendation notes
    how it feeds the M1-608 judge-reasoning-toggle decision (is a local judge or
    a reasoning-mode remote judge the better security bet?).
  - >-
    A reusable benchmark harness (a checked-in script, not a throwaway) so the
    comparison can be re-run when models, prompts, or hardware change — seeded
    from the 2026-07-12 scripts.
  - >-
    mvn verify is green from the repo root IF the harness or any change touches a
    Java/config/DB file; if the deliverable is a standalone script + report only
    (no Java/config/DB change), the diff is inert and mvn verify is N/A per the
    inert-diff rule.
test_plan:
  adds:
    - >-
      A reusable local-vs-remote benchmark harness (standalone script) plus its
      labelled eval-set fixture.
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D22
  - D56
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-609: Spike — local vs remote LLM for the security judge and tagger

## Context

The bot runs the `remote-llm` profile: every LLM task goes to DeepSeek, which
keeps the VPS running only Postgres + the two services. The question raised in
the M1-606/M1-608 discussion is whether the **security judge** and **tagger**
could run on a **local** small model to cut paid-API cost — or whether local is
too weak / too CPU-heavy and we should standardize on remote (and only tune the
remote side, e.g. the M1-608 reasoning toggle).

A quick directional test on 2026-07-12 (llama3.2:3b, real prompt templates,
corpus samples) established the shape of the tradeoff:

- **Format robustness: good** — judge 8/8 valid labels, tagger 6/6 valid JSON.
  The DeepSeek-style schema fragility did NOT appear for these two tasks on 3b.
- **Quality: mediocre on 3b** — the judge over-flagged benign posts (safe but
  costly false positives) though it DID catch the clear synthetic injections;
  the tagger invented out-of-vocab tags ~1/3 of the time (silently dropped).
- **Latency/CPU: the binding constraint** — ~7-9s/call, all 4 cores saturated,
  no GPU. RAM was fine (3.3 GiB peak, 10.6 GiB free).
- **DeepSeek is not ground truth** — a corpus post it labelled INJECTION reads
  as ordinary crypto news (likely a DeepSeek false positive), so the judge eval
  MUST use human labels.

## Why a spike, not a guess

The result is genuinely non-obvious: local is not fragile on format and fits in
RAM, but 3b quality is weak and CPU latency is high on a GPU-less VPS. The
decision hinges on numbers this quick pass could not produce (labelled
false-negative rates, out-of-vocab rates at scale, throughput at real volume, a
stronger small model) AND on an ops constraint that may be decisive: sustained
4-core saturation on a shared/burstable VPS can hit provider fair-use limits and
starve the co-tenant DB/services. The spike quantifies all three axes and
recommends.

## Notes

- **CPU-on-VPS is potentially decisive.** Even if local quality were acceptable,
  sustained near-100% CPU during ingestion may be unacceptable on the target VPS
  tier — enough to recommend "stay remote" on ops grounds alone. Treat the VPS
  CPU budget + fair-use as a first-class metric, not an afterthought.
- **Hybrid is on the table.** The tagger (every post, low-consequence, tolerant
  fallback) and the judge (~5% of posts, security-critical, safe-on-UNKNOWN)
  have different profiles — the answer may be "local tagger, remote judge" or the
  reverse, not an all-or-nothing switch.
- **Evaluate "local judge only" explicitly (operator hypothesis, 2026-07-12).**
  The judge is the VPS-cheapest task to localize — on the current corpus it runs
  on only ~0.3% of posts (~18 of ~5,300 got a verdict; Stage 1 flags very few),
  so a local judge is occasional CPU bursts, not sustained load. BUT the same
  0.3% creates two counter-forces: localizing it saves almost NO paid-API cost
  (the judge is nearly free remotely at that volume), and it moves the ONE
  security-critical task (false-BENIGN = payload into the corpus) onto the
  weakest local model (3b was the noisiest performer in the quick test). The
  three lenses conflict — VPS-CPU favours a local judge; cost and security favour
  keeping the judge remote and (if anything) localizing the tagger. Quantify all
  three before choosing; do not default to "local judge" just because it is the
  lightest on CPU.
- **Explore a batch-vs-split switch for the benign-metadata tasks.** Today
  TAGGER, ENTITY, and CLASSIFIER are three separate LLM calls per post, each
  re-sending the body (~3x input tokens on a paid API). Evaluate a configurable
  switch between (a) BATCHED — one request to ONE provider returning
  {tags, entities, classification} in a single structured response — and (b) the
  current SPLIT design. Batched saves round-trips and tokens but is all-or-nothing
  on failure (one schema violation loses all three), whereas SPLIT degrades each
  independently via its own fallback (bootstrap_tags / no-entities / unknown) —
  a robustness difference already observed live (DeepSeek failed the ENTITY schema
  ~85% while tagging worked). A switch lets a deployment pick per its
  provider/cost/robustness profile. This interacts with the local-vs-remote
  choice: batching pays off most on the paid remote API and least on a local
  model where round-trips are cheap. (Note: the SECURITY_JUDGE and EMBEDDING stay
  separate regardless — the judge is a gate on pre-redaction content, embeddings
  are a different SPI.)
- **Feeds M1-608.** If a local judge is viable, it competes with the M1-608
  remote-judge-with-reasoning path; the spike should say which is the better
  security-per-cost bet.
- Starting scripts: `.scratch` local_eval / deepseek_smoke from 2026-07-12; the
  pulled `llama3.2:3b` is left in Ollama for this work.
