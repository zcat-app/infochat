#!/usr/bin/env bash
# run-gate.sh — run one workflow gate agent headlessly (harness-mapping §3).
#
# Single source for the kimi headless gate recipe. The caller renders the
# prompt first (scripts/m1-render-prompt.py, primitive #2); this script spawns
# `kimi -p` with project skills suppressed (§6.3), enforces the §6.1(d)
# absolute-path rule, checks the verdict artifact landed (primitive #3 — the
# on-disk file governs, never the chat reply), and runs the §6 contamination
# check (git status --porcelain before/after). The codex/opencode recipes
# remain documented in harness-mapping §3 — extend --tool when they migrate.
#
# usage: run-gate.sh --prompt <abs-path> --reply <path> [options]
#   --tool kimi          only supported value for now (default: kimi)
#   --prompt <path>      rendered prompt file; MUST be absolute (§6.1(d))
#   --reply <path>       where the gate agent's stdout chat reply is captured
#   --verdict <path>     expected artifact path; MUST be absolute; checked
#                        after the run (missing/empty artifact = failed gate)
#   --agent <name>       kimi agent profile from .agents/agents/ (optional)
#   --timeout <seconds>  default 900 (mirrors scripts/redteam-multi.sh)
#
# exit codes: 0 clean | 1 usage error | 2 gate run failed (kimi non-zero or
#             timeout) | 3 verdict artifact missing/empty | 4 contamination
#             (gate wrote outside the expected artifact/reply paths)
#
# NOTE for the calling session: a gate run takes minutes (~4 min measured for
# a 12 KB diff on kimi 0.29.0). Invoke this script as a BACKGROUND task — it
# exceeds typical foreground shell caps.

set -uo pipefail

tool=kimi
prompt=""
reply=""
verdict=""
agent=""
timeout_s=900

usage() { sed -n '2,28p' "$0" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --tool)    tool="$2"; shift 2 ;;
        --prompt)  prompt="$2"; shift 2 ;;
        --reply)   reply="$2"; shift 2 ;;
        --verdict) verdict="$2"; shift 2 ;;
        --agent)   agent="$2"; shift 2 ;;
        --timeout) timeout_s="$2"; shift 2 ;;
        *) usage ;;
    esac
done

[ "$tool" = "kimi" ] || { printf 'run-gate: --tool %s not supported (kimi only; see harness-mapping §3 for codex/opencode)\n' "$tool" >&2; exit 1; }
[ -n "$prompt" ] && [ -n "$reply" ] || usage

# §6.1(d): a gate agent resolving a relative path against the wrong cwd reads
# or writes the wrong tree, and the §6 porcelain check cannot see a verdict
# landing in a gitignored target/ elsewhere. Refuse relative paths outright.
case "$prompt" in
    /*) ;;
    *) printf 'run-gate: --prompt must be absolute (§6.1(d)): %s\n' "$prompt" >&2; exit 1 ;;
esac
[ -r "$prompt" ] || { printf 'run-gate: prompt file not readable: %s\n' "$prompt" >&2; exit 1; }
if [ -n "$verdict" ]; then
    case "$verdict" in
        /*) ;;
        *) printf 'run-gate: --verdict must be absolute (§6.1(d)): %s\n' "$verdict" >&2; exit 1 ;;
    esac
fi

repo_root="$(git -C "$(dirname "$prompt")" rev-parse --show-toplevel 2>/dev/null)" \
    || { printf 'run-gate: prompt is not inside a git worktree: %s\n' "$prompt" >&2; exit 1; }
porcelain_before="$(git -C "$repo_root" status --porcelain)"

# --skills-dir aimed at an empty directory suppresses project/user skill
# discovery (§6.3): a gate agent needs no skill, so the safest resolution is
# none at all.
skills_void="$(mktemp -d)"
trap 'rm -rf "$skills_void"' EXIT

stub="Read $prompt and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies."

agent_args=()
[ -n "$agent" ] && agent_args=(--agent "$agent")

# `-p` is the headless form: prints the reply on stdout (no output-file flag)
# and auto-approves tool calls (`--auto` is REJECTED alongside `-p` — §6.3).
# kimi's exit status is meaningful: non-zero on a failed run.
rc=0
timeout "$timeout_s" kimi -p "$stub" --skills-dir "$skills_void" \
    "${agent_args[@]}" > "$reply" 2>&1 || rc=$?

if [ "$rc" -eq 124 ]; then
    printf 'run-gate: gate TIMED OUT after %ss (reply capture: %s)\n' "$timeout_s" "$reply" >&2
    exit 2
fi
if [ "$rc" -ne 0 ]; then
    printf 'run-gate: kimi exited %d (reply capture: %s)\n' "$rc" "$reply" >&2
    exit 2
fi

if [ -n "$verdict" ] && [ ! -s "$verdict" ]; then
    printf 'run-gate: verdict artifact missing or empty: %s\n' "$verdict" >&2
    exit 3
fi

# §6 contamination check (mandatory on non-Claude harnesses): any new or
# changed path beyond the expected artifact/reply is a contaminated gate —
# discard the artifact, revert, re-run.
porcelain_after="$(git -C "$repo_root" status --porcelain)"
delta="$(comm -13 <(printf '%s\n' "$porcelain_before" | sort) <(printf '%s\n' "$porcelain_after" | sort))"
contaminated=""
if [ -n "$delta" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        path="${line:3}"
        rel_verdict=""; rel_reply=""
        [ -n "$verdict" ] && rel_verdict="$(realpath --relative-to="$repo_root" "$verdict" 2>/dev/null)"
        rel_reply="$(realpath --relative-to="$repo_root" "$reply" 2>/dev/null)"
        if [ "$path" = "$rel_verdict" ] || [ "$path" = "$rel_reply" ]; then
            continue
        fi
        contaminated="$contaminated$line\n"
    done <<< "$delta"
fi
if [ -n "$contaminated" ]; then
    printf 'run-gate: CONTAMINATED gate — unexpected tree changes:\n%b' "$contaminated" >&2
    printf 'run-gate: discard the artifact, revert the contamination, re-run the gate (§6)\n' >&2
    exit 4
fi

printf 'run-gate: OK (tool=%s%s, verdict=%s, reply=%s, contamination=none)\n' \
    "$tool" "${agent:+ agent=$agent}" "${verdict:-unchecked}" "$reply"
exit 0
