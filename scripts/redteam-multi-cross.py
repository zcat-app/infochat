#!/usr/bin/env python3
"""Cross-examination parser for redteam-multi.

Reads ``verdict-<auditor>.txt`` files from one run directory, extracts the
findings each auditor produced, clusters them across auditors, and emits a
side-by-side comparison markdown to stdout (the caller redirects to
``cross-examination.md`` in the same run directory).

v1 scope: deterministic parser, fuzzy cluster by ``(CATEGORY, primary
file:line cited in GAP)``. Single-auditor findings are surfaced for human
review; the falsification pass that re-audits each against the threat
model is v2 (a synthesizer subagent), not this script.

The verdict file format is documented in ``docs/process/redteam-prompt.md``
and enforced by the rendered prompt template; this parser trusts that
format. Anything that fails to parse degrades gracefully — an unparsable
verdict contributes zero findings, never crashes the run.

Usage:
    python3 scripts/redteam-multi-cross.py <run_dir> <auditor> [<auditor> ...]
"""
import os
import re
import sys
from collections import defaultdict

# critical > high > medium > low. Unknown severities sort below low so a
# typo in one auditor's verdict cannot inflate a cluster's max severity.
SEVERITY_RANK = {"critical": 4, "high": 3, "medium": 2, "low": 1}


def parse_verdict(text):
    """Return (verdict_label, list_of_finding_dicts).

    verdict_label is the literal token after "RED-TEAM VERDICT: " — CLEAN,
    FINDINGS, UNAVAILABLE, or UNPARSEABLE if the line is missing.
    """
    m = re.search(r"^RED-TEAM VERDICT:\s*(\S+)", text, re.MULTILINE)
    label = m.group(1) if m else "UNPARSEABLE"
    if label != "FINDINGS":
        return label, []

    findings = []
    # Each finding starts at a line "  - CATEGORY:" (2-space indent) and
    # extends until the next finding, the OUT-OF-MODEL section, or EOF.
    parts = re.split(r"\n(?=  - CATEGORY:)", text)
    for block in parts[1:]:
        block = re.split(r"\nOUT-OF-MODEL:", block)[0]

        def field(key):
            mm = re.search(
                r"^\s{4}" + key + r":\s*(.*?)(?=^\s{4}[A-Z][A-Z-]*:|\Z)",
                block,
                re.DOTALL | re.MULTILINE,
            )
            return mm.group(1).strip() if mm else ""

        m_cat = re.search(r"CATEGORY:\s*(\S+)", block)
        m_sev = re.search(r"SEVERITY:\s*(\S+)", block)
        m_fix = re.search(r"SUGGESTED-FIX-CLASS:\s*(\S+)", block)
        gap = field("GAP")
        # Primary citation: first path:line or path:line-line in GAP. The
        # prompt requires GAP to "point at specific file:line locations",
        # so every real finding has at least one.
        cite = re.search(r"([\w/.-]+:\d+(?:-\d+)?)", gap)
        findings.append(
            {
                "category": m_cat.group(1) if m_cat else "?",
                "severity": (m_sev.group(1).lower() if m_sev else "?"),
                "promise": field("PROMISE"),
                "gap": gap,
                "repro": field("REPRO"),
                "fix_class": m_fix.group(1) if m_fix else "?",
                "primary_cite": cite.group(1) if cite else "",
            }
        )
    return label, findings


def cluster_key(finding):
    """Group findings across auditors by (CATEGORY, primary citation).

    Findings with no file:line citation fall back to a hash of the GAP's
    first 80 chars so two such findings from different auditors cluster
    only if their prose opens identically (a weak signal, but better than
    fragmenting every no-cite finding into its own cluster).
    """
    cat = finding["category"]
    if finding["primary_cite"]:
        return (cat, finding["primary_cite"])
    return (cat, "no-cite:" + str(abs(hash(finding["gap"][:80]))))


def severity_max(severities):
    best = "low"
    for s in severities:
        if SEVERITY_RANK.get(s, 0) > SEVERITY_RANK.get(best, 0):
            best = s
    return best


def main(argv):
    if len(argv) < 3:
        sys.stderr.write(
            "usage: redteam-multi-cross.py <run_dir> <auditor> [<auditor> ...]\n"
        )
        return 2
    run_dir = argv[1]
    auditors = argv[2:]

    per_auditor = {}
    for aud in auditors:
        path = os.path.join(run_dir, "verdict-" + aud + ".txt")
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except FileNotFoundError:
            per_auditor[aud] = ("UNAVAILABLE", [])
            continue
        per_auditor[aud] = parse_verdict(text)

    # key -> {auditor: finding}
    clusters = defaultdict(dict)
    for aud, (_, findings) in per_auditor.items():
        for finding in findings:
            clusters[cluster_key(finding)][aud] = finding

    corroborated = sum(1 for c in clusters.values() if len(c) >= 2)
    singles = sum(1 for c in clusters.values() if len(c) == 1)

    def cluster_sev(c):
        return severity_max([f["severity"] for f in c.values()])

    ordered = sorted(
        clusters.items(),
        key=lambda kv: (-SEVERITY_RANK.get(cluster_sev(kv[1]), 0), kv[0]),
    )

    out = []
    out.append("# Cross-examination report\n\n")
    out.append("Run directory: `" + run_dir + "`\n")
    out.append("Auditors: " + ", ".join(auditors) + "\n\n")

    out.append("## Summary\n\n")
    out.append("- " + str(len(clusters)) + " distinct finding cluster(s) across all auditors.\n")
    out.append("- " + str(corroborated) + " corroborated (flagged by >=2 auditors).\n")
    out.append(
        "- "
        + str(singles)
        + " single-auditor -- each is either a real gap the others missed or a "
        "false positive; see the per-cluster detail and the falsification candidates section.\n"
    )
    by_aud = defaultdict(int)
    for c in clusters.values():
        for aud in c:
            by_aud[aud] += 1
    out.append("- Per-auditor raw finding counts: " + repr(dict(by_aud)) + ".\n\n")

    out.append("## Per-auditor verdicts\n\n")
    for aud in auditors:
        label, findings = per_auditor[aud]
        out.append("- **" + aud + "**: " + label + " (" + str(len(findings)) + " finding(s))\n")
    out.append("\n")

    out.append("## Finding clusters (side-by-side)\n\n")
    out.append(
        "| # | Category | Primary location | "
        + " | ".join(auditors)
        + " | Severity (max) | Attribution |\n"
    )
    out.append("|---|---|---|" + "|".join(["---"] * len(auditors)) + "|---|---|\n")
    for i, (key, aud_map) in enumerate(ordered, start=1):
        cat, cite = key
        cells = []
        for aud in auditors:
            cells.append(aud_map[aud]["severity"] if aud in aud_map else "--")
        sev = cluster_sev(aud_map)
        attribs = sorted(aud_map.keys())
        if len(attribs) == len(auditors):
            attribution = "all (corroborated)"
        elif len(attribs) == 1:
            attribution = attribs[0] + "-only -- needs review"
        else:
            attribution = ", ".join(attribs)
        out.append(
            "| "
            + str(i)
            + " | "
            + cat
            + " | `"
            + cite
            + "` | "
            + " | ".join(cells)
            + " | "
            + sev
            + " | "
            + attribution
            + " |\n"
        )
    out.append("\n")

    out.append("## Per-cluster detail\n\n")
    for i, (key, aud_map) in enumerate(ordered, start=1):
        cat, cite = key
        out.append("### Cluster " + str(i) + ": " + cat + " @ `" + cite + "`\n\n")
        for aud in sorted(aud_map.keys()):
            f = aud_map[aud]
            promise = f["promise"]
            gap = f["gap"]
            out.append(
                "**"
                + aud
                + "** (severity: "
                + f["severity"]
                + ", fix-class: "
                + f["fix_class"]
                + ")\n\n"
            )
            out.append(
                "- PROMISE: "
                + (promise[:400] + "..." if len(promise) > 400 else promise)
                + "\n"
            )
            out.append(
                "- GAP (first 400 chars): "
                + (gap[:400] + "..." if len(gap) > 400 else gap)
                + "\n\n"
            )
        out.append("\n")

    out.append("## Single-auditor findings (falsification candidates)\n\n")
    out.append(
        "Each finding below was reported by exactly one auditor. Either the others "
        "missed a real gap, or this auditor produced a false positive. A v2 "
        "synthesizer subagent would re-audit each against the threat model; this v1 "
        "surfaces them for human review.\n\n"
    )
    single_clusters = [kv for kv in ordered if len(kv[1]) == 1]
    if not single_clusters:
        out.append("(none -- every finding was either corroborated or absent.)\n\n")
    else:
        for key, aud_map in single_clusters:
            cat, cite = key
            aud = list(aud_map.keys())[0]
            f = aud_map[aud]
            out.append(
                "- **"
                + aud
                + "-only**: "
                + cat
                + " @ `"
                + cite
                + "` (severity "
                + f["severity"]
                + "). See `verdict-"
                + aud
                + ".txt` for full PROMISE/GAP/REPRO.\n"
            )
    out.append("\n")

    sys.stdout.write("".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
