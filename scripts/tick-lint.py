#!/usr/bin/env python3
"""Consistency gate for /tick flow tickets.

Usage: tick-lint.py <ticket-file> [ticket-file ...]

Checks the /tick ticket schema (docs/process/tick-ticket-template.md) and
the flow rules (docs/process/tick-workflow.md §1). BLOCKERs refuse
`/tick start`; WARNs are notify-and-continue.

Checks:
  REPRODUCTION-PRESENT            BLOCKER  reproduction empty, or naming neither a
                                           test method nor a probe with output
  ACCEPTANCE-VERIFIABLE           BLOCKER  acceptance item naming no test method,
                                           no runnable command, and no probe
  FORWARD-REFERENCE-RESOLVABLE    BLOCKER  load-bearing ticket-ID reference with no file
  SPEC-REFS-RESOLVABLE            BLOCKER  a present spec_refs entry whose file or
                                           §section anchor does not resolve
  ANALYSIS-REF-RESOLVABLE         BLOCKER  analysis_ref missing, or a path that does
                                           not resolve ('self' / 'none' are legal)
  STATUS-VALUE                    BLOCKER  status outside the allowed set
  REQUIRED-SECTIONS               WARN    body missing Root cause / Pitfalls /
                                           Approach / Definition of done / Verification
  SPEC-REFS-CITED-BY-DOD          WARN    no acceptance item cites any spec_refs entry
  PITFALL-VERIFICATION            WARN    a pitfall (Pn) with no Verification entry,
                                           or a Verification entry referencing no pitfall
  NEGATIVE-TESTS                  WARN    Verification has no failure-mode entry
                                           beyond the reproduction
  OUT-OF-SCOPE-PRESENT            WARN    empty or circular out_of_scope
  CENSUS-PRESENT-IF-CLASS-SCOPED  WARN    class-scoped ticket with no §Census
  PROSE-VERB-IN-VERIFY            WARN    acceptance items using unrunnable prose verbs

Exit codes: 0 clean, 1 BLOCKER(s), 2 usage error. Prints one line per
finding: `CHECK: <SEVERITY>: <ticket-id>: <message>`.
"""

import re
import sys
from pathlib import Path

PROG = Path(sys.argv[0]).name
BLOCKER, WARN = "BLOCKER", "WARN"
ALLOWED_STATUS = {
    "pending", "in-progress", "in-review", "escalated", "done", "deferred", "abandoned",
}
LOAD_BEARING_ID_FIELDS = {
    "blocked_by", "deferred_on", "decomposed_from", "replaces", "replaced_by",
    "spec_amend_parent", "remediates",
}
PROSE_VERB_RE = re.compile(
    r"\b(by reading|by inspection|should be present|loop exits|looks (correct|right)"
    r"|appears (correct|right)|makes sense|is obviously)\b",
    re.IGNORECASE,
)
REQUIRED_SECTIONS = [
    ("Root cause", "## Root cause"),
    ("Pitfalls", "## Pitfalls"),
    ("Approach", "## Approach"),
    ("Definition of done", "## Definition of done"),
    ("Verification", "## Verification"),
]
SECTION_TITLES = {
    "## Root cause", "## Pitfalls", "## Approach", "## Definition of done",
    "## Verification", "## Out-of-scope", "## Context",
}
ACCEPTANCE_VERIFY_RE = re.compile(
    r"(\.\w+\(\)|\bmvn\b|\bgit\b|\bgrep\b|\bcurl\b|\bpython3\b|"
    r"\bprobe\b|renders?|asserts?|pinned by|passes|fails)",
    re.IGNORECASE,
)
REPRODUCTION_RE = re.compile(
    r"(\w+(Test|IT)\w*[.#]\w+|\.\w+\(\)|\bmvn\b|\bcurl\b|\bpython3\b|\bgrep\b"
    r"|\bdocker\b|\bprobe\b|\bobserved\b)",
    re.IGNORECASE,
)
NEGATIVE_TEST_HINT = re.compile(
    r"(fail(ure|ing)?[- ]?mode|hostile|adversarial|malicious|wrong[- ]?(language|input)|"
    r"rejects|refuses|does not (reach|survive|appear)|must not|never|"
    r"no (window|legal|English|hardcoded|new)|contains no|absence of|fails if)",
    re.IGNORECASE,
)


class Finding:
    def __init__(self, check, severity, ticket_id, message):
        self.check = check
        self.severity = severity
        self.ticket_id = ticket_id
        self.message = message

    def __str__(self):
        return f"{self.check}: {self.severity}: {self.ticket_id}: {self.message}"


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
            if key in fields:
                fields[key] = (fields[key] + " " + value).strip()
            else:
                fields[key] = value
        elif line.startswith(" ") and fields:
            last_key = list(fields)[-1]
            # Newline, not space: joining a YAML list's items with a space
            # destroys the item boundary, and two checks depend on it —
            # ACCEPTANCE-VERIFIABLE splits items on `^- `, and the spec_refs
            # `§section` capture runs to end-of-line. Space-joining made the
            # former vacuous (one blob passes if ANY item names a probe) and
            # the latter report a bogus "file not found" naming two paths.
            fields[last_key] = (fields[last_key] + "\n" + line.strip()).strip()
    return fields


def extract_entries(frontmatter_value):
    """Return the concatenated text of a YAML list value (inline or block)."""
    return frontmatter_value


def find_section(body, title):
    lines = body.splitlines()
    starts = [i for i, ln in enumerate(lines) if ln.strip() == title]
    if not starts:
        return ""
    start = starts[0]
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if lines[j].strip() in SECTION_TITLES:
            end = j
            break
    return "\n".join(lines[start + 1 : end])


def resolve_section_anchor(spec_entry):
    """Best-effort anchor check: does <file>§<section> resolve?

    Matches the spirit of workflow.md's anchor resolution: case-insensitive
    substring match of the section title against `#`-prefixed headings.
    Returns (resolvable: bool, note: str).
    """
    if "§" not in spec_entry:
        return None, "no §section anchor — whole-file ref, accepted"
    path, section = spec_entry.rsplit("§", 1)
    path = path.strip().strip("`")
    if not (path.startswith("docs/") and path.endswith(".md")):
        return False, f"not a docs md path: {path!r}"
    p = Path(path)
    if not p.exists():
        return False, f"file not found: {path}"
    title = section.strip().strip("`")
    pat = re.compile(r"^#{1,6}\s+" + re.escape(title), re.IGNORECASE)
    matched = False
    for ln in p.read_text(errors="replace").splitlines():
        if pat.match(ln):
            matched = True
            break
    if not matched:
        return False, f"anchor not found: {path} §{title}"
    return True, f"{path} §{title}"


def lint_file(path):
    findings = []
    text = Path(path).read_text(errors="replace")
    fm = parse_frontmatter(text)
    ticket_id = fm.get("id", Path(path).stem)

    body = text.split("---", 2)[-1] if text.startswith("---") else text

    # ---- REQUIRED-SECTIONS -------------------------------------------------
    for name, title in REQUIRED_SECTIONS:
        if title not in body:
            findings.append(Finding(
                "REQUIRED-SECTIONS", WARN, ticket_id,
                f"missing section '{title}'"))

    # ---- REPRODUCTION-PRESENT ----------------------------------------------
    reproduction = fm.get("reproduction", "").strip()
    if not reproduction or reproduction in {"[]", "{}", "''", '""'}:
        findings.append(Finding(
            "REPRODUCTION-PRESENT", BLOCKER, ticket_id,
            "reproduction is empty — a ticket states the wrong behavior "
            "executably: a failing test method, or a probe command with its "
            "observed output"))
    elif not REPRODUCTION_RE.search(reproduction):
        findings.append(Finding(
            "REPRODUCTION-PRESENT", BLOCKER, ticket_id,
            "reproduction names neither a test method nor a runnable probe: "
            f"{reproduction[:80]}…"))

    # ---- SPEC-REFS-RESOLVABLE ----------------------------------------------
    spec_refs = fm.get("spec_refs", "")
    entries = []
    for token in re.findall(r"docs/[A-Za-z0-9_./-]+\.md\s*(?:§\s*[^,;\n]*)?", spec_refs):
        entries.append(token.strip())
    if not entries:
        findings.append(Finding(
            "SPEC-REFS-RESOLVABLE", WARN, ticket_id,
            "spec_refs is empty — legal only for a defect whose contract is "
            "its reproduction; a change to what the system promises must cite"))
    else:
        for entry in entries:
            ok, note = resolve_section_anchor(entry)
            if ok is False:
                findings.append(Finding(
                    "SPEC-REFS-RESOLVABLE", BLOCKER, ticket_id, note))

    # ---- ANALYSIS-REF-RESOLVABLE -------------------------------------------
    analysis_ref = fm.get("analysis_ref", "")
    if not analysis_ref:
        findings.append(Finding(
            "ANALYSIS-REF-RESOLVABLE", BLOCKER, ticket_id,
            "analysis_ref missing — set 'none' (with the reason), 'self', or "
            "a tick-analysis/ path"))
    elif analysis_ref.strip().lower() not in {"self", "none"} and not Path(analysis_ref).exists():
        findings.append(Finding(
            "ANALYSIS-REF-RESOLVABLE", BLOCKER, ticket_id,
            f"analysis_ref does not resolve: {analysis_ref} "
            "(use 'none' when §0b does not require analysis, 'self' for a "
            "single-ticket decomposition, or a real tick-analysis/ path)"))

    # ---- OUT-OF-SCOPE-PRESENT ----------------------------------------------
    oos = fm.get("out_of_scope", "")
    if not oos or not oos.strip() or oos.strip() in {"[]", "{}"}:
        findings.append(Finding(
            "OUT-OF-SCOPE-PRESENT", WARN, ticket_id,
            "out_of_scope is empty"))
    elif re.search(r"unrelated|anything (else|not)|everything (else|not)", oos, re.I):
        findings.append(Finding(
            "OUT-OF-SCOPE-PRESENT", WARN, ticket_id,
            "out_of_scope is circular (non-specific exclusion)"))

    # ---- status / flow sanity -----------------------------------------------
    status = fm.get("status", "pending")
    if status not in ALLOWED_STATUS:
        findings.append(Finding("STATUS-VALUE", BLOCKER, ticket_id,
                                f"invalid status {status!r}"))

    # ---- SPEC-REFS-CITED-BY-DOD ---------------------------------------------
    acceptance = fm.get("acceptance", "")
    if entries:
        cited = [e.split("§")[0].strip().strip("`").strip("/")
                 for e in entries]
        acc_has_cite = any(
            any(c in acc for c in cited) or "§" in acc
            for acc in [acceptance]
        )
        if not acc_has_cite:
            findings.append(Finding(
                "SPEC-REFS-CITED-BY-DOD", WARN, ticket_id,
                "no acceptance item cites any spec_refs entry"))

    # ---- ACCEPTANCE-VERIFIABLE ----------------------------------------------
    acc_items = re.findall(r"^\s*-\s+(.*)$", acceptance, re.M)
    unverifiable = []
    for item in acc_items:
        item = item.strip()
        if not item:
            continue
        # skip comment/example lines inside the YAML block
        if item.startswith("#") or item.startswith(("(a)", "(b)", "Runnable", "test")):
            continue
        if not ACCEPTANCE_VERIFY_RE.search(item):
            unverifiable.append(item[:80])
    if unverifiable:
        for u in unverifiable[:5]:
            findings.append(Finding(
                "ACCEPTANCE-VERIFIABLE", BLOCKER, ticket_id,
                f"acceptance item names no test/command/probe: {u}…"))

    # ---- PITFALL-VERIFICATION ------------------------------------------------
    pitfalls_sec = find_section(body, "## Pitfalls")
    verif_sec = find_section(body, "## Verification")
    pitfall_ids = set(re.findall(r"\bP(\d+)\b", pitfalls_sec))
    verif_refs = set(re.findall(r"\bP(\d+)\b", verif_sec))
    for p in sorted(pitfall_ids, key=int):
        if p not in verif_refs:
            findings.append(Finding(
                "PITFALL-VERIFICATION", WARN, ticket_id,
                f"pitfall P{p} has no matching entry in Verification"))
    for p in sorted(verif_refs, key=int):
        if p not in pitfall_ids:
            findings.append(Finding(
                "PITFALL-VERIFICATION", WARN, ticket_id,
                f"Verification references P{p} which is not declared in Pitfalls"))

    # ---- NEGATIVE-TESTS ------------------------------------------------------
    if verif_sec and not NEGATIVE_TEST_HINT.search(verif_sec):
        findings.append(Finding(
            "NEGATIVE-TESTS", WARN, ticket_id,
            "Verification contains no failure-mode test — happy-path-only "
            "coverage is not acceptable"))

    # ---- PROSE-VERB-IN-VERIFY -----------------------------------------------
    for item in acc_items:
        if PROSE_VERB_RE.search(item):
            findings.append(Finding(
                "PROSE-VERB-IN-VERIFY", WARN, ticket_id,
                f"acceptance item not mechanically checkable: {item[:80]}…"))

    # ---- CENSUS-PRESENT-IF-CLASS-SCOPED --------------------------------------
    class_scoped = re.search(
        r"\b(class[- ]scoped|parity|reconcile|every (site|instance|call site)|plural|"
        r"all (sites|call sites))\b", text, re.I)
    if class_scoped and "## Census" not in body:
        findings.append(Finding(
            "CENSUS-PRESENT-IF-CLASS-SCOPED", WARN, ticket_id,
            "class-scoped ticket has no §Census section"))

    # ---- FORWARD-REFERENCE-RESOLVABLE ----------------------------------------
    for field in LOAD_BEARING_ID_FIELDS:
        value = fm.get(field, "")
        for ref in re.findall(r"M\d+-\d+[a-z]?", value):
            if ref in {ticket_id}:
                continue
            in_tickets = list(Path("docs/plan/m1/tickets").glob(f"{ref}-*.md"))
            in_tick = list(Path("docs/plan/m1/tick-tickets").glob(f"{ref}-*.md"))
            if not in_tickets and not in_tick:
                findings.append(Finding(
                    "FORWARD-REFERENCE-RESOLVABLE", BLOCKER, ticket_id,
                    f"load-bearing reference {field}: {ref} has no ticket file"))

    return findings


def main(argv):
    if not argv:
        print(f"usage: {PROG} <ticket-file> [ticket-file ...]", file=sys.stderr)
        return 2
    findings = []
    for arg in argv:
        findings.extend(lint_file(arg))
    for f in sorted(findings, key=lambda x: (x.severity, x.check)):
        print(f)
    n_blocker = sum(1 for f in findings if f.severity == BLOCKER)
    print(f"tick-lint: {len(findings)} finding(s), {n_blocker} BLOCKER(s)")
    return 1 if n_blocker else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
