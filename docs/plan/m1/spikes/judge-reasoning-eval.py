#!/usr/bin/env python3
"""Judge reasoning ON-vs-OFF eval harness (M1-610).

Runs the PRODUCTION security-judge prompt template
(infochat-llm-adapter/src/main/resources/prompts/security-judge.md) against a
remote DeepSeek chat-completions endpoint, once per sample per mode, and scores
the replies against HUMAN labels in judge-eval-samples.jsonl.

DeepSeek is NOT ground truth (M1-609: a corpus post DeepSeek labelled INJECTION
read as ordinary news). Every metric here is measured against the human labels
in the fixture, never against the model's own output.

The wire body is assembled to match what OpenAiCompatibleProvider /
DeepSeekProvider send in production:
  - system message content "" (Stage2Worker passes an empty system prompt),
  - user message = the template with {{id}} -> a fresh uuid4 and {{content}} ->
    the sample text,
  - mode OFF  -> "thinking":{"type":"disabled"}   (DeepSeekProvider default),
  - mode DEPTH-> "reasoning_effort":"<depth>"       (thinking enabled).
The reply is parsed exactly as Stage2Worker.parseVerdict: trim, then exact-match
the closed set {BENIGN, INJECTION, MALWARE, UNKNOWN}; anything else is
unparseable (-> Stage 2 INFRA_FAILURE -> release-as-READY, a fail-open).

Credentials are read from the environment, never committed:
  INFOCHAT_LLM_API_KEY   (required)
  INFOCHAT_LLM_BASE_URL  (default https://api.deepseek.com)

Usage:
  export INFOCHAT_LLM_API_KEY=...    # e.g. from prod/runtime/secrets.env
  python3 judge-reasoning-eval.py --mode off    --out .scratch/judge-off.json
  python3 judge-reasoning-eval.py --mode medium --max-tokens 2000 \
      --out .scratch/judge-on-medium.json
"""
import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[3]  # docs/plan/m1/spikes -> repo root
PROMPT_PATH = (REPO_ROOT
               / "infochat-llm-adapter/src/main/resources/prompts/security-judge.md")
DEFAULT_SAMPLES = HERE / "judge-eval-samples.jsonl"
CLOSED_SET = {"BENIGN", "INJECTION", "MALWARE", "UNKNOWN"}
MALICIOUS = {"INJECTION", "MALWARE"}


def load_prompt_template() -> str:
    return PROMPT_PATH.read_text(encoding="utf-8")


def load_samples(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def build_body(template: str, model: str, mode: str, max_tokens: int,
               content: str) -> dict:
    delimiter_id = str(uuid.uuid4())
    user_prompt = template.replace("{{id}}", delimiter_id).replace(
        "{{content}}", content)
    body = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": ""},
            {"role": "user", "content": user_prompt},
        ],
    }
    if mode == "off":
        # DeepSeekProvider's confirmed off-switch: reasoning disabled.
        body["thinking"] = {"type": "disabled"}
    else:
        # A depth enables thinking at that reasoning_effort.
        body["reasoning_effort"] = mode
    return body


def call(base_url: str, api_key: str, body: dict, timeout_s: int) -> dict:
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key},
        method="POST",
    )
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    latency_ms = (time.monotonic() - start) * 1000.0
    choice = payload["choices"][0]
    message = choice.get("message", {})
    usage = payload.get("usage", {})
    details = usage.get("completion_tokens_details", {}) or {}
    return {
        "raw_content": message.get("content"),
        "finish_reason": choice.get("finish_reason"),
        "latency_ms": round(latency_ms, 1),
        "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens", 0),
        "prompt_tokens": usage.get("prompt_tokens"),
    }


def parse_verdict(raw: str | None) -> str | None:
    """Mirror Stage2Worker.parseVerdict: trim, exact-match the closed set."""
    if raw is None:
        return None
    trimmed = raw.strip()
    return trimmed if trimmed in CLOSED_SET else None


def run(args) -> None:
    api_key = os.environ.get("INFOCHAT_LLM_API_KEY")
    if not api_key:
        sys.exit("INFOCHAT_LLM_API_KEY is not set (source prod/runtime/secrets.env).")
    base_url = os.environ.get("INFOCHAT_LLM_BASE_URL", "https://api.deepseek.com")
    template = load_prompt_template()
    samples = load_samples(Path(args.samples))

    results = []
    for sample in samples:
        body = build_body(template, args.model, args.mode, args.max_tokens,
                          sample["text"])
        attempt = None
        for retry in range(2):  # one retry on a transient network error
            try:
                attempt = call(base_url, api_key, body, args.timeout)
                break
            except (urllib.error.URLError, TimeoutError) as e:
                if retry == 1:
                    attempt = {"error": str(e), "raw_content": None,
                               "finish_reason": "error", "latency_ms": None,
                               "completion_tokens": None, "reasoning_tokens": None,
                               "prompt_tokens": None}
                else:
                    time.sleep(3)
        verdict = parse_verdict(attempt.get("raw_content"))
        row = {
            "id": sample["id"],
            "label": sample["label"],
            "source": sample.get("source"),
            "verdict": verdict,
            "parse_valid": verdict is not None,
            **attempt,
        }
        results.append(row)
        flag = "OK" if verdict else "UNPARSEABLE"
        print(f"  [{args.mode:>6}] #{sample['id']:>2} label={sample['label']:<9} "
              f"verdict={str(verdict):<11} parse={flag:<11} "
              f"rtok={attempt.get('reasoning_tokens')} "
              f"lat={attempt.get('latency_ms')}ms fr={attempt.get('finish_reason')}",
              flush=True)

    summary = summarize(results, args)
    out = {"mode": args.mode, "model": args.model, "max_tokens": args.max_tokens,
           "base_url": base_url, "summary": summary, "results": results}
    Path(args.out).write_text(json.dumps(out, indent=2), encoding="utf-8")
    print_summary(summary, args)
    print(f"\nWrote {args.out}")


def summarize(results: list[dict], args) -> dict:
    malicious = [r for r in results if r["label"] in MALICIOUS]
    benign = [r for r in results if r["label"] == "BENIGN"]
    # For a malicious post: BENIGN is a false negative (admits the payload);
    # an unparseable verdict routes to Stage 2 INFRA_FAILURE -> release-as-READY,
    # also a fail-open. Anything else (INJECTION/MALWARE/UNKNOWN) flags it -> safe.
    false_negatives = [r for r in malicious if r["verdict"] == "BENIGN"]
    fail_open = [r for r in malicious
                 if r["verdict"] == "BENIGN" or r["verdict"] is None]
    # For a benign post: INJECTION/MALWARE is a hard false positive; UNKNOWN is a
    # soft false positive (routes to soft-injection); unparseable is an infra
    # failure that, for benign content, releases-as-READY (not over-blocking).
    false_positives = [r for r in benign if r["verdict"] in MALICIOUS]
    soft_false_positives = [r for r in benign if r["verdict"] == "UNKNOWN"]
    parseable = [r for r in results if r["parse_valid"]]
    truncated = [r for r in results if r["finish_reason"] == "length"]
    rtoks = [r["reasoning_tokens"] for r in results
             if isinstance(r.get("reasoning_tokens"), int)]
    ctoks = [r["completion_tokens"] for r in results
             if isinstance(r.get("completion_tokens"), int)]
    lats = [r["latency_ms"] for r in results
            if isinstance(r.get("latency_ms"), (int, float))]

    def pct(num, den):
        return round(100.0 * num / den, 1) if den else None

    return {
        "n": len(results), "n_malicious": len(malicious), "n_benign": len(benign),
        "false_negatives": [r["id"] for r in false_negatives],
        "false_negative_rate_pct": pct(len(false_negatives), len(malicious)),
        "fail_open_ids": [r["id"] for r in fail_open],
        "fail_open_rate_pct": pct(len(fail_open), len(malicious)),
        "false_positives": [r["id"] for r in false_positives],
        "false_positive_rate_pct": pct(len(false_positives), len(benign)),
        "soft_false_positives": [r["id"] for r in soft_false_positives],
        "label_format_validity_pct": pct(len(parseable), len(results)),
        "unparseable_ids": [r["id"] for r in results if not r["parse_valid"]],
        "truncated_ids": [r["id"] for r in truncated],
        "reasoning_tokens_max": max(rtoks) if rtoks else 0,
        "reasoning_tokens_mean": round(sum(rtoks) / len(rtoks), 1) if rtoks else 0,
        "completion_tokens_max": max(ctoks) if ctoks else None,
        "latency_ms_mean": round(sum(lats) / len(lats), 1) if lats else None,
        "latency_ms_max": max(lats) if lats else None,
    }


def print_summary(s: dict, args) -> None:
    print(f"\n=== SUMMARY mode={args.mode} model={args.model} "
          f"max_tokens={args.max_tokens} ===")
    print(f"  n={s['n']} (malicious={s['n_malicious']}, benign={s['n_benign']})")
    print(f"  FALSE-NEGATIVE rate (malicious->BENIGN): "
          f"{s['false_negative_rate_pct']}%  ids={s['false_negatives']}")
    print(f"  FAIL-OPEN rate (malicious->BENIGN or unparseable): "
          f"{s['fail_open_rate_pct']}%  ids={s['fail_open_ids']}")
    print(f"  FALSE-POSITIVE rate (benign->INJECTION/MALWARE): "
          f"{s['false_positive_rate_pct']}%  ids={s['false_positives']}")
    print(f"  soft-FP (benign->UNKNOWN): ids={s['soft_false_positives']}")
    print(f"  label-format validity: {s['label_format_validity_pct']}%  "
          f"unparseable={s['unparseable_ids']}  truncated={s['truncated_ids']}")
    print(f"  reasoning_tokens: max={s['reasoning_tokens_max']} "
          f"mean={s['reasoning_tokens_mean']}  completion_tokens_max="
          f"{s['completion_tokens_max']}")
    print(f"  latency_ms: mean={s['latency_ms_mean']} max={s['latency_ms_max']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", default="off",
                    help="off (thinking disabled) or a reasoning depth "
                         "(low|medium|high|max|xhigh)")
    ap.add_argument("--model", default="deepseek-v4-flash")
    ap.add_argument("--max-tokens", type=int, default=2000, dest="max_tokens")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--samples", default=str(DEFAULT_SAMPLES))
    ap.add_argument("--out", required=True)
    run(ap.parse_args())


if __name__ == "__main__":
    main()
