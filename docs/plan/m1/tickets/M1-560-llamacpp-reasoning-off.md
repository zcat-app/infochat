---
id: M1-560
title: llamacpp serves with reasoning off — token caps buy visible output (F-live-8)
status: done
created: 2026-07-04
last_updated: 2026-07-04
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 53
      removed: 9
blocked_by: []
files_budget: 5
files_scope:
  - docker-compose.yml
  - docs/design/07-deployment.md
  - docs/design/05-llm-and-embeddings.md
  - SETUP_GUIDE.md
  - prod/scripts/4-llm.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - swapping the default/curated generative GGUF — model choice stays an
    operator decision (D49); the abliterated-quant quality residual (messy
    prose even with reasoning off) is a model-selection concern, not a
    serving-config one
  - a per-task temperature key in TaskConfig — investigated 2026-07-04 and
    exonerated (default temperature produced clean replies once reasoning
    was off, 3/3); add it only if future evidence demands
  - app-side chat_template_kwargs {"enable_thinking": false} in
    OpenAiCompatibleProvider — rejected alternative; it is a
    llama.cpp-specific request key inside a generic OpenAI-compatible
    client, and it would fix chat while leaving every other task thinking
  - the ollama service (its models and template handling are the wizard's
    §5.7 table; no evidence of a thinking-mode issue there)
  - measuring Stage-2 verdict drift with reasoning off (expected side
    effect is throughput gain; the deterministic verdict validation and
    UNKNOWN fallback are unchanged — see §Why security_relevant is false)
acceptance:
  - "The llamacpp service in docker-compose.yml gains
    LLAMA_ARG_REASONING: \"off\" beside the existing LLAMA_ARG_* env keys,
    with a WHY comment naming the failure mode: llama.cpp's default
    --reasoning auto detects the Gemma 4 template's thinking channel and
    turns it ON, so per-task max-tokens caps (M1-548, sized for visible
    output) are consumed by <|channel>thought tokens — empty or
    format-broken replies (F-live-8, host-proven 2026-07-04)."
  - The design notes that document the llamacpp env surface
    (07-deployment.md §7.4/D49 wiring; 05-llm-and-embeddings.md sizing
    invariant, if it states one) mention the flag and that the cap/timeout
    sizing invariant assumes non-thinking generation.
  - "Operator-visible note, both surfaces (user request 2026-07-04): (a)
    SETUP_GUIDE.md's step-4 llamacpp paragraph (the one describing the
    pinned default + custom GGUF override, ~line 261) gains one plain-
    language sentence: thinking/reasoning is disabled on the llama.cpp
    server — a reasoning-tuned model will run but will not 'think', and
    the wizard's timeout/token recommendations assume that; (b) the
    4-llm.sh llamacpp branch prints an equivalent one-line note at the
    custom-GGUF override prompt, so the operator sees it at decision time
    even without the guide open. Guide tone matches the surrounding
    non-technical style."
  - "Host validation recorded in the ticket body: with the flag active,
    the previously 4/4-failing live chat DM completes with non-empty
    content (the 2026-07-04 investigation already proved this exact flag
    live: 3/3 direct probes returned finish=stop/content at default
    temperature, and a live DM round-trip delivered a 438-char reply —
    re-run once from the committed compose file to pin provenance)."
  - mvn verify is green (no Java in the diff — compose + docs + guide +
    a wizard echo line; the M1-549 inert-diff precedent covered a shell
    script too and applies if the gate agrees).
test_plan:
  adds: []
  preserves:
    - the full pre-existing suite (no Java change; the behavioral proof is
      the host validation, which CI cannot exercise — D-live-9)
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/llm.md §Hardware profile contract
decision_refs:
  - D49
---

## Context (root cause, investigated 2026-07-04)

F-live-8's "unreliable chat model" had a deterministic mechanism, found by
direct probes against the running server:

1. The Gemma 4 chat template has an optional thinking channel
   (`<|channel>thought … <channel|>`, plus a `strip_thinking` macro and an
   `enable_thinking` kwarg that injects `<|think|>`).
2. llama.cpp's `--reasoning` defaults to `auto` — "detect from template" —
   so the server has been running every task in thinking mode since
   deploy.
3. The model therefore spends its decode budget thinking. Chat's 600-token
   cap (M1-548, sized from visible-output timing) is consumed partly or
   entirely by thought tokens: 3/3 direct probes at default temperature
   put ALL tokens in `reasoning_content` (content empty, finish=length);
   the live failures were the same pathology (2× peg-parse 500 on
   malformed/truncated channel output, 1× parsed-but-empty reply delivered
   as a blank DM, 1× fast-500).
4. With reasoning off (probe-level `enable_thinking:false`, then the
   server-level `LLAMA_ARG_REASONING=off` this ticket commits): 3/3 probes
   returned real content (`finish=stop` reachable within 200 tokens), and
   a live DM round-trip through the bot delivered a 438-char reply in
   ~90 s. Temperature was exonerated (default sampling, clean output).

This corrects F-live-6's "no thinking channel" note — the channel exists;
it was invisible until output-format failures forced a direct look.

Not custom-model-specific: the wizard's curated default GGUF
(`gemma-4-E4B-it-qat-UD-Q4_K_XL`, 4-llm.sh:55) is also Gemma 4 with the
same thinking-capable template, so `--reasoning auto` bites the untouched
default path too — this host merely found it first via its OBLITERATED
override. Hence the operator note on both surfaces (guide + wizard
prompt): with the flag committed, a reasoning-tuned custom GGUF will run
but never think, which is exactly what the M1-550 timeout/max-tokens
recommendations assume.

Host cleanup rider (not a repo diff): the running stack carries the flag
via `prod/runtime/llamacpp-reasoning-off.override.yml` (gitignored; see
live-e2e HANDOFF §START HERE trap note). After the compose change lands
and the service is recreated from it, DELETE that override file — leaving
it costs nothing functionally but keeps a stale second source of truth.

Expected side benefit: eval tasks (tagger/entity/security/summarizer) have
been paying the same hidden thinking tax per call on a 4-vCPU host; with
reasoning off the RAW-backlog chew rate should improve. Not an acceptance
criterion — noted so the next backlog observation isn't misattributed.

## Host validation (2026-07-04, from the committed compose file)

Provenance pinned per acceptance item 4 — the service was force-recreated
from the branch's committed compose file ALONE (no override file):

- `docker compose -f docker-compose.yml --env-file prod/runtime/secrets.env
  --profile prod --profile llamacpp up -d --force-recreate --wait llamacpp`
  → Healthy. Container label
  `com.docker.compose.project.config_files=/home/infochat/infochat/docker-compose.yml`
  (single file — the gitignored override was NOT part of the config) and
  `LLAMA_ARG_REASONING=off` present in the container env.
- Live chat DM over real relays (LiveAdmin → bot, 15:02:40Z, collector
  stopped for the turn per the s12 contention lesson, restarted after):
  assistant reply persisted (`chat_message` seq 15, **625 chars, non-empty**)
  at 15:05:11Z (~150 s wall) and DELIVERED to the client
  (`chat_items` id 128, 625 chars, same timestamp). llama.cpp released the
  task cleanly (`truncated = 0`), no 500s, no peg-format errors — the
  previously 4/4-failing shape completes.
- Reply prose is degenerate junk — the abliterated-quant model-quality
  residual, explicitly out_of_scope (D49 model choice).

## Why security_relevant is false

The flag changes the Stage-2 judge's generation verbosity, not the trust
boundary: verdict parsing, validation, and the UNKNOWN/infra-failure
fallback paths are untouched, and the judge's output contract is the same
structured verdict either way. Verdict-quality drift (either direction) is
unmeasured and explicitly out of scope; the deterministic handling of
malformed verdicts is what the security property rests on.
