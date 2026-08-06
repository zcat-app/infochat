#!/usr/bin/env python3
"""A/B measurement for the /tick flow vs the /m1-tick flow.

Usage: tick-measure.py [--json]

Reads:
  - docs/plan/m1/tickets/M1-*.md        (m1 flow)
  - docs/plan/m1/tick-tickets/M1-*.md   (tick flow)
  - docs/plan/m1/redteam/*.md           (security-audit evidence, keyed by id)

and prints a comparison table: volume, lifecycle outcomes, review-round
distribution, rework rate, escalations (git-log-derived), security audits
per ticket, and per-ticket diff size from `reviews[].diff_stats`.

The A/B question this answers: does the analysis-first flow produce fewer
review rounds, fewer escalations, fewer deferral chains, and fewer
standalone security re-audits per done ticket than the brief-driven flow?
Run it before drawing any conclusion — the M1 board is the baseline.

Exit 0 always (measurement is advisory).
"""

import glob
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
M1_GLOB = ROOT / "docs/plan/m1/tickets" / "M1-*.md"
TICK_GLOB = ROOT / "docs/plan/m1/tick-tickets" / "M1-*.md"
REDTEAM_DIR = ROOT / "docs/plan/m1/redteam"


def parse_frontmatter(text):
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return {}
    raw = m.group(1)
    fields = {}
    for line in raw.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        lm = re.match(r"^([A-Za-z_]+):\s*(.*)$", line)
        if lm:
            key, value = lm.group(1), lm.group(2).strip()
            fields.setdefault(key, "")
            fields[key] = (fields[key] + " " + value).strip()
        elif line.startswith(" ") and fields:
            last = list(fields)[-1]
            fields[last] = (fields[last] + " " + line.strip()).strip()
    return fields


def read_tickets(glob_path):
    tickets = []
    for p in sorted(glob.glob(str(glob_path))):
        fm = parse_frontmatter(Path(p).read_text(errors="replace"))
        if not fm.get("id"):
            continue
        tickets.append((Path(p), fm))
    return tickets


def count_review_rounds(fm):
    """Latest review round from the reviews field (entries accumulate)."""
    rounds = re.findall(r"round:\s*(\d+)", fm.get("reviews", ""))
    return int(rounds[-1]) if rounds else 0


def diff_stats(fm):
    m = re.search(r"added:\s*(\d+)", fm.get("reviews", ""))
    added = int(m.group(1)) if m else 0
    m = re.search(r"removed:\s*(\d+)", fm.get("reviews", ""))
    removed = int(m.group(1)) if m else 0
    return added, removed


def escalation_commits(ticket_id):
    """Count refine/escalate/abort commits for a ticket id on main."""
    try:
        out = subprocess.run(
            ["git", "log", "--oneline", "--grep", f"^{re.escape(ticket_id)}:",
             "--regexp-ignore-case"],
            capture_output=True, text=True, cwd=ROOT, check=False,
        ).stdout
    except Exception:
        return 0
    # count only non-implementation lifecycle commits
    n = 0
    for line in out.splitlines():
        if re.search(r"refine|escalat|abort|decompose|defer", line, re.I):
            n += 1
    return n


def redteam_audits(ticket_id):
    if not REDTEAM_DIR.exists():
        return 0
    return len(glob.glob(str(REDTEAM_DIR / f"{ticket_id}-*.md")))


def summarize(label, tickets):
    s = {"label": label, "total": len(tickets)}
    statuses = Counter(fm.get("status", "?") for _, fm in tickets)
    s["statuses"] = dict(statuses)
    done = [fm for _, fm in tickets if fm.get("status") == "done"]

    rounds = Counter(count_review_rounds(fm) for fm in done)
    s["rounds"] = dict(sorted(rounds.items()))
    s["rounds3plus"] = sum(rounds[r] for r in rounds if r >= 3)
    s["rework_rate"] = (
        (sum(rounds[r] for r in rounds if r >= 2) / len(done)) if done else 0.0
    )
    ordered_rounds = sorted(count_review_rounds(fm) for fm in done)
    s["median_rounds"] = ordered_rounds[len(ordered_rounds) // 2] if ordered_rounds else 0

    reasons = Counter()
    for _, fm in tickets:
        reason = fm.get("deferred_reason", "") or fm.get("abandoned_reason", "")
        if reason:
            reasons[reason.split()[0] if reason.split() else reason] += 1
    s["lineage_reasons"] = dict(reasons)

    esc = defaultdict(int)
    for _, fm in tickets:
        tid = fm.get("id", "")
        if tid:
            esc[tid] = escalation_commits(tid)
    s["escalation_commits_total"] = sum(esc.values())
    s["escalation_commits_avg"] = sum(esc.values()) / len(tickets) if tickets else 0.0

    audits = defaultdict(int)
    for _, fm in tickets:
        tid = fm.get("id", "")
        if tid:
            audits[tid] = redteam_audits(tid)
    audited = [n for n in audits.values() if n > 0]
    s["audits_total"] = sum(audits.values())
    s["audits_per_done"] = (sum(redteam_audits(fm.get("id", "")) for fm in done) /
                            len(done)) if done else 0.0
    s["tickets_with_audits"] = len(audited)

    added, removed = [], []
    for fm in done:
        a, r = diff_stats(fm)
        added.append(a)
        removed.append(r)
    s["median_added"] = sorted(added)[len(added) // 2] if added else 0
    s["median_removed"] = sorted(removed)[len(removed) // 2] if removed else 0

    chain = []
    for _, fm in tickets:
        tid = fm.get("id", "")
        if not tid:
            continue
        d = fm.get("deferred_on", "")
        depth = 0
        seen = {tid}
        cur = d
        while cur and cur not in seen:
            seen.add(cur)
            depth += 1
            nxt = None
            for _, fm2 in tickets:
                if fm2.get("id") == cur:
                    nxt = fm2.get("deferred_on", "")
                    break
            cur = nxt
        chain.append(depth)
    s["max_defer_chain"] = max(chain) if chain else 0
    return s


def print_table(m1, tick):
    rows = [
        ("tickets filed", "total", ""),
        ("  done", "statuses", "done"),
        ("  pending", "statuses", "pending"),
        ("  in-progress", "statuses", "in-progress"),
        ("  in-review", "statuses", "in-review"),
        ("  escalated", "statuses", "escalated"),
        ("  deferred", "statuses", "deferred"),
        ("  abandoned", "statuses", "abandoned"),
        ("review rounds per done ticket (median)", "median_rounds", None),
        ("  tickets at round 1", "rounds", 1),
        ("  tickets at round 2", "rounds", 2),
        ("  tickets at round 3+", "rounds3plus", None),
        ("rework rate (rounds >= 2)", "rework_rate", None),
        ("escalation commits per ticket", "escalation_commits_avg", None),
        ("security audits per done ticket", "audits_per_done", None),
        ("tickets with >=1 security audit", "tickets_with_audits", None),
        ("median lines added (done)", "median_added", None),
        ("max deferral chain depth", "max_defer_chain", None),
    ]

    def cell(s, key, sub):
        if sub is None:
            v = s.get(key, 0)
        elif isinstance(s.get(key), dict):
            v = s[key].get(sub, 0)
        else:
            v = s.get(key, 0)
        if isinstance(v, float):
            return f"{v:.2f}"
        return str(v)

    width = max(len(r[0]) for r in rows)
    print(f"\n{'metric':<{width}}  {'m1 flow':>10}  {'tick flow':>10}")
    print("-" * (width + 26))
    for name, key, sub in rows:
        print(f"{name:<{width}}  {cell(m1, key, sub):>10}  {cell(tick, key, sub):>10}")

    print(f"\nDeferred/abandoned reasons (m1):  {m1['lineage_reasons']}")
    print(f"Deferred/abandoned reasons (tick): {tick['lineage_reasons']}")


def main():
    m1 = summarize("m1", read_tickets(M1_GLOB))
    tick = summarize("tick", read_tickets(TICK_GLOB))
    if "--json" in sys.argv:
        print(json.dumps({"m1": m1, "tick": tick}, indent=2))
        return 0
    print_table(m1, tick)
    print(f"\n(measurement advisory — run at milestone boundaries; the m1 "
          f"board is the baseline)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
