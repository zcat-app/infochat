#!/usr/bin/env python3
"""Regenerate the milestone STATUS.md from ticket YAML frontmatter.

Usage: regen-status.py <tickets-glob> <status-file-path>

The script reads every ticket file matching the glob, extracts the
specific frontmatter fields STATUS.md needs (id, title, status,
blocked_by, deferred_on, deferred_reason, abandoned_reason,
complexity, risk, last_updated, reviews, escalation_reason), classifies
each ticket, renders the canonical template, writes the destination
file, and prints a four-line summary on stdout matching the contract
the m1-tick skill consumes.

The parser is targeted extraction — not a general YAML parser. It
reads only the fields above; everything else in the
frontmatter (acceptance, files_scope, out_of_scope, test_plan, etc.)
is ignored by construction. This is deliberate: those fields contain
backticks and other characters that break strict YAML in our tickets
(M1-006, M1-010), and we don't need them for STATUS.md. Targeted
extraction also eliminates the pyyaml dependency, so the script's
trust chain bottoms out at CPython alone — nothing to pip-install,
nothing to scan for CVEs.

Supported value shapes:
  - Scalar: `key: value` or `key: "value with colons"`
  - Inline list: `key: [A, B, C]` or `key: []`
  - Block list of IDs: `key:` followed by `  - M1-001` lines
  - Block list of small mappings (reviews): we read only the
    round/date/verdict sub-keys; nested structures inside an entry
    (e.g. `checks:`) are skipped.

Exits 0 on success, 2 on usage error.
"""

import datetime
import glob
import re
import sys
from pathlib import Path

SCALAR_FIELDS = {
    "id", "title", "status", "complexity", "risk",
    "last_updated", "deferred_reason", "abandoned_reason", "escalation_reason",
}
ID_LIST_FIELDS = {"blocked_by", "deferred_on"}
MAPPING_LIST_FIELDS = {"reviews"}
KEEP_FIELDS = SCALAR_FIELDS | ID_LIST_FIELDS | MAPPING_LIST_FIELDS

# Sub-keys we extract from each mapping in a MAPPING_LIST_FIELDS entry.
# Other sub-keys (e.g. `checks:` under a review) are skipped — they may
# contain nested structures we don't need. `escalations` is no longer a
# field (the open-escalation reason is the `escalation_reason` scalar; the
# history lives in git log); a stray `escalations:` in an old done ticket is
# skipped as an unknown top-level key.
MAPPING_ENTRY_KEYS = {"round", "date", "verdict"}

_TOP_KEY_RE = re.compile(r"^([a-z_]+):\s*(.*?)\s*$")
_NESTED_KEY_RE = re.compile(r"^    ([a-z_]+):\s*(.*?)\s*$")


def _unquote(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and ((s[0] == '"' and s[-1] == '"') or (s[0] == "'" and s[-1] == "'")):
        return s[1:-1]
    return s


def _split_inline_list(rest: str) -> list[str]:
    # rest begins with `[`; collect everything up to the matching `]`
    end = rest.rfind("]")
    inner = rest[1:end] if end > 0 else rest[1:]
    if not inner.strip():
        return []
    return [_unquote(part.strip()) for part in inner.split(",") if part.strip()]


def parse_frontmatter(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        return {}
    closing = text.find("\n---", 4)
    if closing < 0:
        return {}
    body = text[4:closing]
    return _parse_body(body)


def _parse_body(body: str) -> dict:
    lines = body.split("\n")
    result: dict = {}
    i, n = 0, len(lines)

    while i < n:
        line = lines[i]
        # Skip blanks, indented continuations of previous fields, and comments.
        if not line or line[0].isspace() or line.lstrip().startswith("#"):
            i += 1
            continue
        m = _TOP_KEY_RE.match(line)
        if not m:
            i += 1
            continue
        key, rest = m.group(1), m.group(2)

        if key in SCALAR_FIELDS:
            result[key] = _unquote(rest)
            i += 1
            continue

        if key in ID_LIST_FIELDS:
            if rest.startswith("["):
                result[key] = _split_inline_list(rest)
                i += 1
                continue
            if rest:
                # Single bare scalar after the colon (unusual but tolerated).
                result[key] = [_unquote(rest)]
                i += 1
                continue
            # Block list: collect `^  - ID` lines until the next top-level key.
            items: list[str] = []
            i += 1
            while i < n:
                nxt = lines[i]
                if nxt.startswith("  - "):
                    items.append(_unquote(nxt[4:]))
                    i += 1
                elif not nxt or nxt[0].isspace():
                    i += 1  # blank or deeper-indented continuation
                else:
                    break  # next top-level key
            result[key] = items
            continue

        if key in MAPPING_LIST_FIELDS:
            entries: list[dict] = []
            current: dict | None = None
            i += 1
            while i < n:
                nxt = lines[i]
                if nxt.startswith("  - "):
                    if current is not None:
                        entries.append(current)
                    current = {}
                    inner = nxt[4:]
                    sm = _TOP_KEY_RE.match(inner)
                    if sm and sm.group(1) in MAPPING_ENTRY_KEYS and sm.group(2):
                        current[sm.group(1)] = _unquote(sm.group(2))
                    i += 1
                elif current is not None and nxt.startswith("    "):
                    sm = _NESTED_KEY_RE.match(nxt)
                    if sm and sm.group(1) in MAPPING_ENTRY_KEYS and sm.group(2):
                        v = sm.group(2)
                        # Skip if value is empty (means a nested block follows).
                        if v and not v.startswith("-"):
                            current[sm.group(1)] = _unquote(v)
                    i += 1
                elif not nxt:
                    i += 1
                elif nxt[0].isspace():
                    i += 1  # deeper-indented continuation we don't read
                else:
                    break
            if current is not None:
                entries.append(current)
            result[key] = entries
            continue

        # Unknown top-level key: skip the key and any indented continuation.
        i += 1
        while i < n and (not lines[i] or lines[i][0].isspace()):
            i += 1

    return result


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
    children = {tid: [] for tid in tickets_by_id}
    parents = {tid: [] for tid in tickets_by_id}
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

    tickets_by_id: dict = {}
    for path_str in sorted(glob.glob(tickets_glob)):
        fm = parse_frontmatter(Path(path_str))
        tid = fm.get("id")
        if tid:
            tickets_by_id[tid] = fm

    counts = {"pending": 0, "in-progress": 0, "in-review": 0,
              "escalated": 0, "done": 0, "deferred": 0, "abandoned": 0}
    for t in tickets_by_id.values():
        s = t.get("status")
        if s in counts:
            counts[s] += 1
    total = sum(counts.values())

    runnable_ids = sorted(tid for tid, t in tickets_by_id.items() if is_runnable(t, tickets_by_id))
    runnable_set = set(runnable_ids)

    # blocked_by validation: warn about pending tickets that can never become
    # runnable because a blocker is dangling or deferred. Emitted on stderr so
    # the four-line stdout summary contract is unchanged. /m1-tick next relays
    # these verbatim.
    blocker_warnings = []
    for tid in sorted(tickets_by_id):
        t = tickets_by_id[tid]
        if t.get("status") != "pending":
            continue
        for b in t.get("blocked_by") or []:
            blocker = tickets_by_id.get(b)
            if blocker is None:
                blocker_warnings.append(
                    f"WARNING: {tid} references unknown blocker {b} "
                    f"(no such ticket file)")
            elif blocker.get("status") == "deferred":
                blocker_warnings.append(
                    f"WARNING: {tid}'s blocker {b} is deferred "
                    f"(status: deferred); {tid} stays unrunnable until the "
                    f"blocker is reopened and completed")
            elif blocker.get("status") == "abandoned":
                blocker_warnings.append(
                    f"WARNING: {tid}'s blocker {b} is abandoned "
                    f"(status: abandoned; will not be built); {tid} can never "
                    f"become runnable as written — re-scope it or abandon it too")
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
    abandoned_ids = sorted(tid for tid, t in tickets_by_id.items() if t.get("status") == "abandoned")

    last_updated_line = (
        datetime.date.today().isoformat()
        if total > 0
        else "(no tickets yet — Phase 1 scaffolding only; no tickets drafted)"
    )

    # The script serves both flows' boards; the header must name the flow
    # that owns THIS board, or it sends readers to the wrong command and
    # ticket directory (it did, for STATUS-TICK.md).
    is_tick_flow = "tick-tickets" in tickets_glob
    flow_command = "/tick status" if is_tick_flow else "/m1-tick status"
    tickets_dir = str(Path(tickets_glob).parent).rstrip("/") + "/"

    L = []
    L.append("# M1 status board")
    L.append("")
    L.append(f"> **Auto-generated by `{flow_command}`.** Do not hand-edit. "
             "Source of truth is the frontmatter of the files under "
             f"`{tickets_dir}`. If this file disagrees with frontmatter, "
             f"frontmatter wins; re-run `{flow_command}` to regenerate.")
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
    L.append(f"| abandoned | {counts['abandoned']} |")
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
            L.append(f"| {tid} | {t.get('title', '')} | "
                     f"{t.get('escalation_reason', '?')} | {t.get('last_updated', '?')} |")
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
        groups: dict = {}
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
    L.append("## Abandoned")
    L.append("")
    L.append("Tickets decided against — not implemented as this ticket. Terminal: "
             "not reopenable via the driver's `reopen`. `abandoned_reason` records why "
             "(`decomposed` = split into shipped children; `superseded` = absorbed by "
             "another ticket; `obsoleted-by-spec-amend` = a spec change dropped the "
             "requirement; `wont-do-infeasible` = evaluated and judged not worth building). "
             "See `docs/process/workflow.md` §Status values.")
    L.append("")
    if abandoned_ids:
        abandoned_groups: dict = {}
        for tid in abandoned_ids:
            reason = tickets_by_id[tid].get("abandoned_reason", "other")
            abandoned_groups.setdefault(reason, []).append(tid)
        for reason in sorted(abandoned_groups):
            L.append(f"### {reason} ({len(abandoned_groups[reason])})")
            for tid in abandoned_groups[reason]:
                t = tickets_by_id[tid]
                L.append(f"- {tid} — {t.get('title', '')}")
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
          f"done={counts['done']}, deferred={counts['deferred']}, "
          f"abandoned={counts['abandoned']}")
    if runnable_ids:
        print(f"Runnable: {len(runnable_ids)} tickets — {', '.join(runnable_ids)}")
    else:
        print("Runnable: 0 tickets")
    print(f"In flight: {', '.join(in_flight_ids) if in_flight_ids else 'none'}")
    for warning in blocker_warnings:
        print(warning, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
