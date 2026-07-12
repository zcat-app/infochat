#!/usr/bin/env python3
"""Local-vs-remote LLM eval harness for the SECURITY_JUDGE and TAGGER (M1-609).

Seeded from the M1-610 judge-reasoning-eval.py. Generalized along three axes so
the local-vs-remote comparison the M1-609 spike needs can be re-run when models,
prompts, or hardware change:

  1. BACKEND — `remote` (OpenAI-compatible DeepSeek, key from env) or `local`
     (Ollama's OpenAI-compatible endpoint at http://localhost:11434/v1, no auth).
  2. TASK — `judge` (security-judge.md, closed-set label) or `tagger`
     (tagger.md, controlled-vocabulary JSON tags).
  3. RESOURCES — a box-wide CPU/memory sampler runs for the duration of the
     batch (/proc/stat + /proc/meminfo), so the "does local inference saturate
     the VPS and starve the co-tenant Postgres/services?" question in the ticket
     is measured, not guessed.

Every quality metric is scored against the HUMAN labels in the fixtures, never
against a model's own output: DeepSeek is NOT ground truth (M1-609 found a
corpus post DeepSeek labelled INJECTION that reads as ordinary news).

The wire body and reply parsing mirror production exactly:
  - JUDGE  — system message "", user = security-judge.md with {{id}}->uuid4 and
    {{content}}->text; reply parsed as Stage2Worker.parseVerdict (trim, exact
    match of {BENIGN, INJECTION, MALWARE, UNKNOWN}); anything else is
    unparseable -> Stage 2 INFRA_FAILURE -> release-as-READY (a fail-open).
  - TAGGER — the {#tags}..{/tags} block is expanded once per vocabulary name
    (TaggerWorker.renderPrompt), then {{id}}/{{title}}/{{body}} substituted;
    reply parsed as TaggerWorker.parseTags (strict JSON {"tags":[...]} with a
    single enclosing code-fence stripped, or the `TAGS: a, b` fallback line);
    each tag normalized with the canonical TagNormalizer rule (NFC + lower-case
    + ^[a-z0-9][a-z0-9-]{0,47}$) and checked against the vocabulary; a tag that
    fails is an out-of-vocab tag the pipeline silently drops.
  - Both default to reasoning OFF on the remote (DeepSeekProvider's production
    default; M1-610 kept the judge OFF), i.e. body carries
    {"thinking":{"type":"disabled"}}; local Ollama has no such field.

Credentials (remote only) are read from the environment, never committed:
  INFOCHAT_LLM_API_KEY   (required for --backend remote)
  INFOCHAT_LLM_BASE_URL  (default https://api.deepseek.com)

Usage:
  # remote DeepSeek baseline (reasoning OFF, as in production)
  export INFOCHAT_LLM_API_KEY=...        # e.g. from prod/runtime/secrets.env
  python3 local-vs-remote-eval.py --task judge  --backend remote \
      --model deepseek-v4-flash --out .scratch/m1-609/judge-remote.json
  python3 local-vs-remote-eval.py --task tagger --backend remote \
      --model deepseek-v4-flash --out .scratch/m1-609/tagger-remote.json

  # local Ollama models
  python3 local-vs-remote-eval.py --task judge  --backend local \
      --model llama3.2:3b --out .scratch/m1-609/judge-llama32-3b.json
  python3 local-vs-remote-eval.py --task tagger --backend local \
      --model qwen2.5:7b  --out .scratch/m1-609/tagger-qwen25-7b.json
"""
import argparse
import json
import os
import re
import sys
import threading
import time
import unicodedata
import urllib.request
import urllib.error
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[3]  # docs/plan/m1/spikes -> repo root
PROMPTS = REPO_ROOT / "infochat-llm-adapter/src/main/resources/prompts"
JUDGE_PROMPT_PATH = PROMPTS / "security-judge.md"
TAGGER_PROMPT_PATH = PROMPTS / "tagger.md"
JUDGE_SAMPLES = HERE / "judge-eval-samples.jsonl"
TAGGER_SAMPLES = HERE / "tagger-eval-samples.jsonl"
# Union of `tags` across the bootstrap sources = the seed of the controlled
# vocabulary (CLAUDE.md; the DB `tag` table is loaded from it, then normalized).
BOOTSTRAP_SOURCES = [
    REPO_ROOT / "prod/runtime/bootstrap-sources.json",
    REPO_ROOT / "prod/config/bootstrap-sources.json",
]

CLOSED_SET = {"BENIGN", "INJECTION", "MALWARE", "UNKNOWN"}
MALICIOUS = {"INJECTION", "MALWARE"}
MAX_TAGS_PER_POST = 4  # TaggerWorker.MAX_TAGS_PER_POST
TAG_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
TAGS_BLOCK = re.compile(r"\{#tags\}(?P<body>.*?)\{/tags\}", re.DOTALL)
CODE_FENCE = re.compile(r"^```[a-zA-Z0-9]*\n(?P<inner>.*)\n```$", re.DOTALL)


# ---------------------------------------------------------------------------
# Vocabulary + normalization (mirror of TagNormalizer + TagVocabulary)
# ---------------------------------------------------------------------------

def normalize_tag(raw):
    """Mirror app.zcat.infochat.core.util.TagNormalizer.normalize: NFC +
    lower-case + char-class; returns the normalized form or None."""
    if raw is None:
        return None
    lower = unicodedata.normalize("NFC", raw).lower()
    return lower if TAG_NAME_PATTERN.match(lower) else None


def load_vocabulary():
    """The normalized controlled vocabulary the tagger renders into its prompt
    — the union of bootstrap-source tags, normalized exactly as TagVocabulary
    normalizes the rows it loads from the `tag` table."""
    raw = set()
    for path in BOOTSTRAP_SOURCES:
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        entries = data if isinstance(data, list) else data.get(
            "sources", data.get("entries", []))
        for entry in entries:
            for tag in entry.get("tags", []) or []:
                raw.add(tag)
    vocab = set()
    for tag in raw:
        norm = normalize_tag(tag)
        if norm is not None:
            vocab.add(norm)
    return sorted(vocab)


# ---------------------------------------------------------------------------
# Prompt rendering
# ---------------------------------------------------------------------------

def render_judge_prompt(template, content):
    delimiter_id = str(uuid.uuid4())
    return template.replace("{{id}}", delimiter_id).replace("{{content}}", content)


def render_tagger_prompt(template, vocab, title, body):
    """Mirror TaggerWorker.renderPrompt: expand {#tags}..{/tags} once per
    vocabulary name, then substitute {{id}}/{{title}}/{{body}}."""
    delimiter_id = str(uuid.uuid4())

    def expand(match):
        sub = match.group("body")
        return "".join(sub.replace("{name}", name) for name in vocab)

    rendered = TAGS_BLOCK.sub(expand, template)
    return (rendered
            .replace("{{id}}", delimiter_id)
            .replace("{{title}}", title or "")
            .replace("{{body}}", body or ""))


# ---------------------------------------------------------------------------
# Reply parsing (mirror of Stage2Worker.parseVerdict / TaggerWorker.parseTags)
# ---------------------------------------------------------------------------

def parse_verdict(raw):
    if raw is None:
        return None
    trimmed = raw.strip()
    return trimmed if trimmed in CLOSED_SET else None


def strip_code_fence(text):
    """Mirror LlmJson.stripCodeFence: remove a single enclosing markdown fence."""
    match = CODE_FENCE.match(text.strip())
    return match.group("inner").strip() if match else text


def parse_tags(text):
    """Mirror TaggerWorker.parseTags: TAGS: fallback line, else strict JSON
    {"tags":[...]} with a single enclosing code-fence stripped. Returns the raw
    (un-normalized) tag list, or None when the reply is schema-violating."""
    if text is None:
        return None
    trimmed = text.strip()
    if not trimmed:
        return None
    if trimmed.startswith("TAGS:"):
        payload = trimmed[len("TAGS:"):].strip()
        if not payload:
            return []
        return [t.strip() for t in payload.split(",") if t.strip()]
    try:
        root = json.loads(strip_code_fence(trimmed))
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(root, dict):
        return None
    tags = root.get("tags")
    if not isinstance(tags, list):
        return None
    return [t for t in tags if isinstance(t, str)]


def validate_tags(parsed, vocab_set):
    """Mirror TaggerWorker.validate: normalize, partition valid/invalid by
    vocabulary membership, cap at MAX_TAGS_PER_POST in emission order."""
    valid = []
    invalid = 0
    capped = 0
    seen = set()
    for raw in parsed:
        norm = normalize_tag(raw)
        if norm is None or norm not in vocab_set:
            invalid += 1
            continue
        if len(valid) < MAX_TAGS_PER_POST:
            if norm not in seen:
                valid.append(norm)
                seen.add(norm)
        elif norm not in seen:
            capped += 1
    return valid, invalid, capped


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def build_body(model, user_prompt, max_tokens, reasoning_off, extra_body=None):
    body = {
        "model": model,
        "max_tokens": max_tokens,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": ""},
            {"role": "user", "content": user_prompt},
        ],
    }
    if reasoning_off:
        # DeepSeekProvider's production default: reasoning disabled. Ignored by
        # Ollama (local backend passes reasoning_off=False so it is omitted).
        body["thinking"] = {"type": "disabled"}
    if extra_body:
        # Escape hatch for provider-specific fields not on the OpenAI schema —
        # e.g. Ollama's thinking-disable ({"think": false}) when fairly probing
        # a default-thinking local model's quality ceiling. Empty by default so
        # the standard run stays production-shaped. M1-609.
        body.update(extra_body)
    return body


def call(base_url, api_key, body, timeout_s):
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    latency_ms = (time.monotonic() - start) * 1000.0
    choice = payload["choices"][0]
    message = choice.get("message", {})
    usage = payload.get("usage", {}) or {}
    return {
        "raw_content": message.get("content"),
        "finish_reason": choice.get("finish_reason"),
        "latency_ms": round(latency_ms, 1),
        "completion_tokens": usage.get("completion_tokens"),
        "prompt_tokens": usage.get("prompt_tokens"),
    }


def call_ollama_native(base_url, model, user_prompt, max_tokens, think, timeout_s):
    """Ollama's NATIVE /api/chat. The OpenAI-compat endpoint used by `call`
    cannot disable a thinking model's reasoning ({"think": false} is silently
    ignored there); /api/chat honors a top-level `think` boolean, which is the
    only CPU-viable way to run qwen3/qwen3.5 (thinking-on is ~60s/call). Ollama
    routes reasoning to a separate `thinking` field, so `content` stays a clean
    bare label/JSON either way. M1-609."""
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[:-3]
    body = {
        "model": model, "stream": False, "think": think,
        "options": {"temperature": 0, "num_predict": max_tokens},
        "messages": [
            {"role": "system", "content": ""},
            {"role": "user", "content": user_prompt},
        ],
    }
    req = urllib.request.Request(
        root + "/api/chat", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    latency_ms = (time.monotonic() - start) * 1000.0
    message = payload.get("message", {}) or {}
    return {
        "raw_content": message.get("content"),
        "finish_reason": payload.get("done_reason", "stop"),
        "latency_ms": round(latency_ms, 1),
        "completion_tokens": payload.get("eval_count"),
        "prompt_tokens": payload.get("prompt_eval_count"),
    }


def invoke(args, base_url, api_key, reasoning_off, user_prompt):
    """Dispatch one call to the configured endpoint (remote OpenAI-compat,
    local OpenAI-compat, or local Ollama-native), with one retry on a transient
    network error."""
    for retry in range(2):
        try:
            if args.backend == "local" and args.local_api == "native":
                return call_ollama_native(base_url, args.model, user_prompt,
                                          args.max_tokens, args.think == "on",
                                          args.timeout)
            body = build_body(args.model, user_prompt, args.max_tokens,
                              reasoning_off, args.extra_body)
            return call(base_url, api_key, body, args.timeout)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if retry == 1:
                return {"error": str(e), "raw_content": None,
                        "finish_reason": "error", "latency_ms": None,
                        "completion_tokens": None, "prompt_tokens": None}
            time.sleep(3)


# ---------------------------------------------------------------------------
# Box-wide resource sampler (/proc/stat + /proc/meminfo)
# ---------------------------------------------------------------------------

class ResourceSampler(threading.Thread):
    """Samples system-wide CPU utilization and available memory for the
    duration of the batch. CPU% is derived from successive /proc/stat deltas
    (0..100*ncpu is possible; reported as a percentage of a single core so 400
    == all 4 cores saturated on this box). Measures the whole box on purpose —
    the co-tenancy question is whether inference starves Postgres/services."""

    def __init__(self, interval_s=0.5):
        super().__init__(daemon=True)
        self.interval_s = interval_s
        # NB: not self._stop — that name shadows Thread's internal _stop()
        # method and breaks join(). M1-609.
        self._stop_event = threading.Event()
        self.cpu_samples = []      # percent-of-one-core (n_cores*100 == full box)
        self.mem_avail_mib = []    # MemAvailable, MiB
        self.ncpu = os.cpu_count() or 1

    @staticmethod
    def _read_cpu():
        with open("/proc/stat", encoding="utf-8") as f:
            parts = f.readline().split()
        vals = [int(x) for x in parts[1:]]
        idle = vals[3] + (vals[4] if len(vals) > 4 else 0)  # idle + iowait
        total = sum(vals)
        return total, idle

    @staticmethod
    def _read_mem_avail_mib():
        with open("/proc/meminfo", encoding="utf-8") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 1024.0
        return None

    def run(self):
        prev_total, prev_idle = self._read_cpu()
        while not self._stop_event.wait(self.interval_s):
            total, idle = self._read_cpu()
            dt, di = total - prev_total, idle - prev_idle
            prev_total, prev_idle = total, idle
            if dt > 0:
                busy_frac = 1.0 - (di / dt)
                self.cpu_samples.append(round(100.0 * self.ncpu * busy_frac, 1))
            avail = self._read_mem_avail_mib()
            if avail is not None:
                self.mem_avail_mib.append(round(avail, 1))

    def stop(self):
        self._stop_event.set()

    def summary(self):
        def stat(xs):
            return {"mean": round(sum(xs) / len(xs), 1), "max": max(xs),
                    "min": min(xs)} if xs else None
        return {
            "ncpu": self.ncpu,
            "cpu_pct_of_one_core": stat(self.cpu_samples),
            "cpu_full_box_pct": self.ncpu * 100,
            "mem_available_mib": stat(self.mem_avail_mib),
            "n_samples": len(self.cpu_samples),
        }


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

def resolve_backend(args):
    if args.backend == "remote":
        api_key = os.environ.get("INFOCHAT_LLM_API_KEY")
        if not api_key:
            sys.exit("INFOCHAT_LLM_API_KEY is not set (source "
                     "prod/runtime/secrets.env) for --backend remote.")
        base_url = os.environ.get("INFOCHAT_LLM_BASE_URL",
                                  "https://api.deepseek.com")
        return base_url, api_key, True  # reasoning OFF on the remote
    base_url = os.environ.get("INFOCHAT_LLM_BASE_URL",
                              "http://localhost:11434/v1")
    return base_url, None, False  # no auth, no thinking field for Ollama


def run_judge(args, base_url, api_key, reasoning_off):
    template = JUDGE_PROMPT_PATH.read_text(encoding="utf-8")
    samples = [json.loads(l) for l in
               Path(args.samples or JUDGE_SAMPLES).read_text(
                   encoding="utf-8").splitlines() if l.strip()]
    results = []
    for s in samples:
        prompt = render_judge_prompt(template, s["text"])
        attempt = invoke(args, base_url, api_key, reasoning_off, prompt)
        verdict = parse_verdict(attempt.get("raw_content"))
        results.append({"id": s["id"], "label": s["label"],
                        "source": s.get("source"), "verdict": verdict,
                        "parse_valid": verdict is not None, **attempt})
        print(f"  [judge] #{s['id']:>2} label={s['label']:<9} "
              f"verdict={str(verdict):<11} "
              f"lat={attempt.get('latency_ms')}ms "
              f"fr={attempt.get('finish_reason')}", flush=True)
    return summarize_judge(results), results


def summarize_judge(results):
    malicious = [r for r in results if r["label"] in MALICIOUS]
    benign = [r for r in results if r["label"] == "BENIGN"]
    false_neg = [r for r in malicious if r["verdict"] == "BENIGN"]
    fail_open = [r for r in malicious
                 if r["verdict"] == "BENIGN" or r["verdict"] is None]
    false_pos = [r for r in benign if r["verdict"] in MALICIOUS]
    soft_fp = [r for r in benign if r["verdict"] == "UNKNOWN"]
    parseable = [r for r in results if r["parse_valid"]]
    lats = [r["latency_ms"] for r in results
            if isinstance(r.get("latency_ms"), (int, float))]

    def pct(n, d):
        return round(100.0 * n / d, 1) if d else None
    return {
        "task": "judge", "n": len(results),
        "n_malicious": len(malicious), "n_benign": len(benign),
        "false_negatives": [r["id"] for r in false_neg],
        "false_negative_rate_pct": pct(len(false_neg), len(malicious)),
        "fail_open_ids": [r["id"] for r in fail_open],
        "fail_open_rate_pct": pct(len(fail_open), len(malicious)),
        "false_positives": [r["id"] for r in false_pos],
        "false_positive_rate_pct": pct(len(false_pos), len(benign)),
        "soft_false_positive_ids": [r["id"] for r in soft_fp],
        "soft_false_positive_rate_pct": pct(len(soft_fp), len(benign)),
        "label_format_validity_pct": pct(len(parseable), len(results)),
        "unparseable_ids": [r["id"] for r in results if not r["parse_valid"]],
        "latency_ms_mean": round(sum(lats) / len(lats), 1) if lats else None,
        "latency_ms_max": max(lats) if lats else None,
    }


def run_tagger(args, base_url, api_key, reasoning_off):
    template = TAGGER_PROMPT_PATH.read_text(encoding="utf-8")
    vocab = load_vocabulary()
    vocab_set = set(vocab)
    samples = [json.loads(l) for l in
               Path(args.samples or TAGGER_SAMPLES).read_text(
                   encoding="utf-8").splitlines() if l.strip()]
    results = []
    for s in samples:
        prompt = render_tagger_prompt(template, vocab, s.get("title", ""),
                                      s.get("body", ""))
        attempt = invoke(args, base_url, api_key, reasoning_off, prompt)
        parsed = parse_tags(attempt.get("raw_content"))
        schema_ok = parsed is not None
        valid, invalid, capped = validate_tags(parsed or [], vocab_set)
        expected = sorted({normalize_tag(t) for t in s.get("expected", [])
                           if normalize_tag(t)})
        results.append({
            "id": s["id"], "source": s.get("source"),
            "expected": expected, "raw_tags": parsed,
            "valid_tags": valid, "invalid_count": invalid,
            "capped_count": capped, "schema_valid": schema_ok,
            "latency_ms": attempt.get("latency_ms"),
            "finish_reason": attempt.get("finish_reason"),
            "completion_tokens": attempt.get("completion_tokens"),
            "error": attempt.get("error"),
        })
        print(f"  [tagger] #{s['id']:>2} "
              f"valid={valid} invalid={invalid} "
              f"expected={expected} schema={'OK' if schema_ok else 'BAD'} "
              f"lat={attempt.get('latency_ms')}ms", flush=True)
    return summarize_tagger(results, vocab), results


def summarize_tagger(results, vocab):
    n = len(results)
    schema_ok = [r for r in results if r["schema_valid"]]
    total_emitted = sum(len(r["valid_tags"]) + r["invalid_count"] for r in results)
    total_invalid = sum(r["invalid_count"] for r in results)
    posts_with_invalid = [r["id"] for r in results if r["invalid_count"] > 0]
    # Micro-averaged precision/recall of the KEPT (valid) tags vs human labels.
    tp = fp = fn = 0
    for r in results:
        pred = set(r["valid_tags"])
        exp = set(r["expected"])
        tp += len(pred & exp)
        fp += len(pred - exp)
        fn += len(exp - pred)
    lats = [r["latency_ms"] for r in results
            if isinstance(r.get("latency_ms"), (int, float))]

    def pct(num, den):
        return round(100.0 * num / den, 1) if den else None
    return {
        "task": "tagger", "n": n, "vocab_size": len(vocab),
        "schema_validity_pct": pct(len(schema_ok), n),
        "schema_violating_ids": [r["id"] for r in results if not r["schema_valid"]],
        "tags_emitted_total": total_emitted,
        "out_of_vocab_tags_total": total_invalid,
        "out_of_vocab_rate_pct": pct(total_invalid, total_emitted),
        "posts_with_out_of_vocab": posts_with_invalid,
        "posts_with_out_of_vocab_pct": pct(len(posts_with_invalid), n),
        "precision_pct": pct(tp, tp + fp),
        "recall_pct": pct(tp, tp + fn),
        "tp": tp, "fp": fp, "fn": fn,
        "latency_ms_mean": round(sum(lats) / len(lats), 1) if lats else None,
        "latency_ms_max": max(lats) if lats else None,
    }


def print_summary(summary, resources):
    print("\n=== SUMMARY ===")
    for k, v in summary.items():
        print(f"  {k}: {v}")
    if resources:
        print("  resources:")
        for k, v in resources.items():
            print(f"    {k}: {v}")


def run(args):
    base_url, api_key, reasoning_off = resolve_backend(args)
    sampler = None
    if args.sample_resources:
        sampler = ResourceSampler(interval_s=args.resource_interval)
        sampler.start()
    try:
        if args.task == "judge":
            summary, results = run_judge(args, base_url, api_key, reasoning_off)
        else:
            summary, results = run_tagger(args, base_url, api_key, reasoning_off)
    finally:
        if sampler:
            sampler.stop()
            sampler.join(timeout=2)
    resources = sampler.summary() if sampler else None
    out = {"task": args.task, "backend": args.backend, "model": args.model,
           "base_url": base_url, "max_tokens": args.max_tokens,
           "reasoning_off": reasoning_off,
           "local_api": args.local_api if args.backend == "local" else None,
           "think": args.think if (args.backend == "local"
                                   and args.local_api == "native") else None,
           "summary": summary, "resources": resources, "results": results}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, indent=2), encoding="utf-8")
    print_summary(summary, resources)
    print(f"\nWrote {args.out}")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--task", choices=["judge", "tagger"], required=True)
    ap.add_argument("--backend", choices=["remote", "local"], required=True)
    ap.add_argument("--model", required=True,
                    help="deepseek-v4-flash (remote) | llama3.2:3b / qwen2.5:7b (local)")
    ap.add_argument("--local-api", choices=["openai", "native"], default="openai",
                    dest="local_api",
                    help="local backend endpoint: 'openai' (production path, "
                         "/v1/chat/completions) or 'native' (/api/chat, the only "
                         "path that can disable a thinking model's reasoning)")
    ap.add_argument("--think", choices=["on", "off"], default="on",
                    help="native-endpoint reasoning toggle (--local-api native "
                         "only): 'off' is the CPU-viable config for qwen3/qwen3.5")
    ap.add_argument("--max-tokens", type=int, default=2000, dest="max_tokens")
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--samples", default=None,
                    help="override the fixture path (default per --task)")
    ap.add_argument("--sample-resources", action="store_true",
                    help="run the box-wide CPU/memory sampler during the batch")
    ap.add_argument("--resource-interval", type=float, default=0.5,
                    dest="resource_interval")
    ap.add_argument("--extra-body", default=None, dest="extra_body",
                    help='JSON merged into the request body for provider-specific '
                         'fields, e.g. \'{"think": false}\' to disable a local '
                         "model's default thinking mode. Omit for a "
                         "production-shaped run.")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    args.extra_body = json.loads(args.extra_body) if args.extra_body else None
    run(args)


if __name__ == "__main__":
    main()
