#!/usr/bin/env python3
"""Regenerate the milestone STATUS.md from ticket YAML frontmatter.

Usage: regen-status.py <tickets-glob> <status-file-path>

The script reads every ticket file matching the glob, parses the top-level
frontmatter fields we need (ignoring fields whose values may contain
backticks or other characters that confuse strict YAML parsing), classifies
each ticket, renders the canonical template, writes the destination file,
and prints a four-line summary on stdout matching the contract the m1-tick
skill consumes:

    STATUS REGENERATED: <path>
    Counts: pending=N, in-progress=N, in-review=N, escalated=N, done=N, deferred=N
    Runnable: M tickets — M1-AAA, M1-BBB
    In flight: <ids-or-none>

Exits 0 on success, 2 on usage error, 1 on internal error.
"""

import datetime
import glob
import sys
from pathlib import Path

import yaml

# Top-level frontmatter fields the renderer consults. Other fields
# (acceptance, files_scope, out_of_scope, test_plan, …) are deliberately
# stripped before yaml.safe_load — those bodies may contain backticks
# and other characters that break strict YAML parsing, and we don't need
# them for STATUS.md.
KEEP_FIELDS = {
    "id", "title", "status",
    "blocked_by", "deferred_on", "deferred_reason",
    "complexity", "risk", "last_updated",
    "reviews", "escalations",
}


def parse_frontmatter(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 4)
    if end < 0:
        return {}
    body = text[4:end]
    filtered_lines, keeping = [], False
    for line in body.split("\n"):
        if line and not line[0].isspace() and ":" in line and not line.startswith("#"):
            key = line.split(":", 1)[0].strip()
            keeping = key in KEEP_FIELDS
        if keeping:
            filtered_lines.append(line)
    filtered = "\n".join(filtered_lines)
    try:
        return yaml.safe_load(filtered) or {}
    except yaml.YAMLError as e:
        print(f"WARN: yaml parse error in {path}: {e}", file=sys.stderr)
        return {}


def last_review(t: dict) -> dict:
    reviews = t.get("reviews") or []
    return reviews[-1] if reviews else {}


def is_runnable(t: dict, tickets_by_id: dict) -> bool:
    if t.get("status") != "pending":
        return False
    for b in t.get("blocked_by") or []:
        if tickets_by_id.get(b, {}).get("status") != "done":
            return False
    return True


def render_dag(tickets_by_id: dict, runnable_ids: set) -> str:
    children, parents = {tid: [] for tid in tickets_by_id}, {tid: [] for tid in tickets_by_id}
    for tid, t in tickets_by_id.items():
        for kind in ("blocked_by", "deferred_on"):
            for blocker in t.get(kind) or []:
                if blocker in tickets_by_id:
                    children[blocker].append(tid)
                    parents[tid].append(blocker)
    for k in children:
        children[k] = sorted(set(children[k]))
    roots = sorted(tid for tid in tickets_by_id if not parents[tid])
    rendered, lines = set(), []

    def emit(tid: str, prefix: str, is_last: bool, depth: int):
        status = tickets_by_id[tid].get("status", "unknown")
        marker = " ← runnable" if tid in runnable_ids else ""
        if depth == 0:
            line_prefix, next_prefix = "", "  "
        else:
            line_prefix = prefix + ("└── " if is_last else "├── ")
            next_prefix = prefix + ("      " if is_last else "│     ")
        if tid in rendered:
            lines.append(f"{line_prefix}{tid} ({status}) [see above]")
            return
        rendered.add(tid)
        lines.append(f"{line_prefix}{tid} ({status}){marker}")
        kids = children.get(tid, [])
        for i, kid in enumerate(kids):
            emit(kid, next_prefix, i == len(kids) - 1, depth + 1)

    for root in roots:
        emit(root, "", True, 0)
    for tid in sorted(tickets_by_id):
        if tid not in rendered:
            emit(tid, "", True, 0)
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"usage: {argv[0]} <tickets-glob> <status-file-path>", file=sys.stderr)
        return 2
    tickets_glob, status_path = argv[1], Path(argv[2])

    tickets_by_id = {}
    for path_str in sorted(glob.glob(tickets_glob)):
        fm = parse_frontmatter(Path(path_str))
        tid = fm.get("id")
        if tid:
            tickets_by_id[tid] = fm

    counts = {"pending": 0, "in-progress": 0, "in-review": 0,
              "escalated": 0, "done": 0, "deferred": 0}
    for t in tickets_by_id.values():
        s = t.get("status")
        if s in counts:
            counts[s] += 1
    total = sum(counts.values())

    runnable_ids = sorted(tid for tid, t in tickets_by_id.items() if is_runnable(t, tickets_by_id))
    runnable_set = set(runnable_ids)
    in_flight_ids = sorted(tid for tid, t in tickets_by_id.items()
                           if t.get("status") in ("in-progress", "in-review"))
    blocked_ids = sorted(tid for tid, t in tickets_by_id.items()
                         if t.get("status") == "pending" and tid not in runnable_set)
    escalated_ids = sorted(tid for tid, t in tickets_by_id.items() if t.get("status") == "escalated")
    done_ids = sorted(
        (tid for tid, t in tickets_by_id.items() if t.get("status") == "done"),
        key=lambda tid: (str(tickets_by_id[tid].get("last_updated", "")), tid),
        reverse=True,
    )
    deferred_ids = sorted(tid for tid, t in tickets_by_id.items() if t.get("status") == "deferred")

    last_updated_line = (
        datetime.date.today().isoformat()
        if total > 0
        else "(no tickets yet — Phase 1 scaffolding only; no tickets drafted)"
    )

    L = []
    L.append("# M1 status board")
    L.append("")
    L.append("> **Auto-generated by `/m1-tick status`.** Do not hand-edit. "
             "Source of truth is the frontmatter of the files under "
             "`docs/plan/m1/tickets/`. If this file disagrees with frontmatter, "
             "frontmatter wins; re-run `/m1-tick status` to regenerate.")
    L.append("")
    L.append(f"**Last updated:** {last_updated_line}")
    L.append("")
    L.append("---")
    L.append("")
    L.append("## Summary")
    L.append("")
    L.append("| Status | Count |")
    L.append("|---|---|")
    L.append(f"| pending | {counts['pending']} |")
    L.append(f"| in-progress | {counts['in-progress']} |")
    L.append(f"| in-review | {counts['in-review']} |")
    L.append(f"| escalated | {counts['escalated']} |")
    L.append(f"| done | {counts['done']} |")
    L.append(f"| deferred | {counts['deferred']} |")
    L.append(f"| **total** | **{total}** |")
    L.append("")
    L.append("---")
    L.append("")
    L.append("## Runnable now")
    L.append("")
    L.append("Tickets where `status: pending` AND every entry in `blocked_by` has `status: done`.")
    L.append("")
    if runnable_ids:
        for tid in runnable_ids:
            t = tickets_by_id[tid]
            L.append(f"- {tid} — {t.get('title', '')} "
                     f"(complexity: {t.get('complexity', '?')}, risk: {t.get('risk', '?')})")
    else:
        L.append("_(none — all pending tickets are blocked)_")
    L.append("")
    L.append("---")
    L.append("")
    L.append("## In flight")
    L.append("")
    L.append("| ID | Title | Status | Last review |")
    L.append("|---|---|---|---|")
    if in_flight_ids:
        for tid in in_flight_ids:
            t = tickets_by_id[tid]
            r = last_review(t)
            review_str = (f"round {r['round']} {r['verdict']} on {r['date']}"
                          if r else "(none)")
            L.append(f"| {tid} | {t.get('title', '')} | {t.get('status', '')} | {review_str} |")
    L.append("")
    if not in_flight_ids:
        L.append("_(none)_")
        L.append("")
    L.append("---")
    L.append("")
    L.append("## Blocked")
    L.append("")
    L.append("Tickets with `status: pending` AND at least one `blocked_by` entry not yet done.")
    L.append("")
    if blocked_ids:
        for tid in blocked_ids:
            t = tickets_by_id[tid]
            parts = []
            for b in t.get("blocked_by") or []:
                bs = tickets_by_id.get(b, {}).get("status", "unknown")
                parts.append(f"{b} ({bs})")
            L.append(f"- {tid} — blocked_by: {', '.join(parts)}")
    else:
        L.append("_(none)_")
    L.append("")
    L.append("---")
    L.append("")
    L.append("## Escalated (awaiting user resolution)")
    L.append("")
    L.append("| ID | Title | Trigger | Date |")
    L.append("|---|---|---|---|")
    if escalated_ids:
        for tid in escalated_ids:
            t = tickets_by_id[tid]
            esc = (t.get("escalations") or [{}])[-1]
            L.append(f"| {tid} | {t.get('title', '')} | "
                     f"{esc.get('trigger', '?')} | {esc.get('date', '?')} |")
    L.append("")
    if not escalated_ids:
        L.append("_(none)_")
        L.append("")
    L.append("---")
    L.append("")
    L.append("## Done")
    L.append("")
    L.append("Showing the 10 most recently `done` tickets (full history is "
             "git-log-derivable via `git log --grep \"^M1-\"`).")
    L.append("")
    L.append("| ID | Title | Done date | Verdict |")
    L.append("|---|---|---|---|")
    if done_ids:
        for tid in done_ids[:10]:
            t = tickets_by_id[tid]
            r = last_review(t)
            verdict = f"round {r['round']} {r['verdict']}" if r else "—"
            L.append(f"| {tid} | {t.get('title', '')} | "
                     f"{t.get('last_updated', '')} | {verdict} |")
    L.append("")
    if not done_ids:
        L.append("_(none)_")
        L.append("")
    L.append("---")
    L.append("")
    L.append("## Deferred")
    L.append("")
    if deferred_ids:
        groups = {}
        for tid in deferred_ids:
            reason = tickets_by_id[tid].get("deferred_reason", "other")
            groups.setdefault(reason, []).append(tid)
        for reason in sorted(groups):
            L.append(f"### {reason} ({len(groups[reason])})")
            for tid in groups[reason]:
                t = tickets_by_id[tid]
                on = t.get("deferred_on") or []
                L.append(f"- {tid} → {', '.join(on) if on else 'unspecified'}")
            L.append("")
    else:
        L.append("_(none)_")
        L.append("")
    L.append("---")
    L.append("")
    L.append("## Dependency graph")
    L.append("")
    L.append("ASCII DAG: nodes are ticket IDs (with status in parens), edges are "
             "`blocked_by` AND `deferred_on` relationships. Mark runnable tickets with `←`.")
    L.append("")
    L.append("```")
    if tickets_by_id:
        L.append(render_dag(tickets_by_id, runnable_set))
    else:
        L.append("_(none — will render once tickets exist)_")
    L.append("```")
    L.append("")

    status_path.parent.mkdir(parents=True, exist_ok=True)
    status_path.write_text("\n".join(L), encoding="utf-8")

    print(f"STATUS REGENERATED: {status_path}")
    print(f"Counts: pending={counts['pending']}, in-progress={counts['in-progress']}, "
          f"in-review={counts['in-review']}, escalated={counts['escalated']}, "
          f"done={counts['done']}, deferred={counts['deferred']}")
    if runnable_ids:
        print(f"Runnable: {len(runnable_ids)} tickets — {', '.join(runnable_ids)}")
    else:
        print("Runnable: 0 tickets")
    print(f"In flight: {', '.join(in_flight_ids) if in_flight_ids else 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
