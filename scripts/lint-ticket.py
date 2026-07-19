#!/usr/bin/env python3
"""Author-side static linter for ticket files.

Runs static checks against a ticket's frontmatter and body to catch
mechanical authoring errors at author time. This is the mechanical half of
the ticket-readiness gate: `/m1-tick start` runs it and refuses to start on
a BLOCKER (the clarity-reviewer subagent it replaced is gone — the judgment
half is now a developer self-check done in-context at start; see
`.claude/skills/m1-tick/subcommands/start.md`).

Usage:
  scripts/lint-ticket.py <path/to/ticket.md> [<path/to/ticket.md> ...]
  scripts/lint-ticket.py docs/plan/m1/tickets/M1-NNN-*.md
  scripts/lint-ticket.py --quiet ...   # only print findings, not PASS lines

Exit codes:
  0 — every file CLEAN or WARN-only
  1 — at least one file has BLOCKERS

The checks:

  SPEC-REFS-RESOLVABLE       BLOCKER / WARN
    Every spec_ref entry must point at a file that exists and contain
    a heading whose text (lowercased) includes the section-title
    (lowercased) as a substring.  Implements docs/process/workflow.md
    §"Spec-anchor resolution (canonical)" — the authoritative
    fence-aware ATX heading algorithm.
    ANCHOR-NOT-FOUND → BLOCKER; AMBIGUOUS → WARN.

  FILES-SCOPE-COVERAGE       WARN
    (a) test_plan.adds / test_plan.modifies entries not in files_scope
        warn: the reviewer's negative-space check won't cover them.
    (b) Code-file paths mentioned in §Notes (or legacy §Implementation
        notes / §Authorized test changes / §Big-picture notes for
        grandfathered tickets) that are not in files_scope warn too,
        unless the section contains an explicit "inner class of X"
        disclaimer.

  PROSE-VERB-IN-VERIFY       WARN
    Acceptance items using "by reading", "by inspection", "should be
    present", "loop exits" — not mechanically checkable. Rewrite as a
    named test or runnable command.

  OUT-OF-SCOPE-PRESENT       BLOCKER / WARN
    out_of_scope must be non-empty (BLOCKER if empty) and specific
    (WARN on circular entries like "things unrelated to this ticket").

  FORWARD-REFERENCE-RESOLVABLE   BLOCKER / WARN
    Ticket-ID references (M<N>-NNN) must resolve to a file under
    docs/plan/*/tickets/. Unresolved in a load-bearing frontmatter field
    (blocked_by, deferred_on, decomposed_from, replaces, replaced_by,
    spec_amend_parent, remediates) → BLOCKER; unresolved in prose → WARN.

  SECURITY-FLAG-INFERENCE    WARN
    files_scope touches a security surface (invite/admin/ban/intake/
    sanitizer/tool-wiring/audit) but security_relevant: false — the
    /redteam gate keys off that flag, so a mis-flag skips the gate.

  CENSUS-PRESENT-IF-CLASS-SCOPED   WARN
    Ticket reads as class-scoped (plural/parity/guard framing) but has no
    §Census section enumerating the class. Nudge only — the developer runs
    the census grep live at start and the reviewer verifies sites in the diff.
"""

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("lint-ticket.py requires pyyaml (apt install python3-yaml or pip install pyyaml)",
          file=sys.stderr)
    sys.exit(2)


RED = "\033[31m"
YELLOW = "\033[33m"
GREEN = "\033[32m"
DIM = "\033[2m"
RESET = "\033[0m"


def color(s, c):
    return f"{c}{s}{RESET}" if sys.stdout.isatty() else s


# ---------- ticket reading ----------

def split_ticket(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return {}, text, "missing leading frontmatter delimiter"
    end = text.find("\n---\n", 4)
    if end < 0:
        return {}, text, "missing closing frontmatter delimiter"
    fm_text = text[4:end]
    body = text[end + 5:]
    try:
        fm = yaml.safe_load(fm_text) or {}
        return fm, body, None
    except yaml.YAMLError as e:
        fm = _targeted_extract(fm_text)
        err_msg = str(e).splitlines()[0] if str(e) else "unknown"
        return fm, body, f"frontmatter not strict YAML ({err_msg}); using targeted fallback"


def _targeted_extract(fm_text):
    """Best-effort extraction of the fields the linter needs from frontmatter
    that pyyaml can't parse strictly."""
    out = {}
    for field in ("files_scope", "acceptance", "spec_refs", "out_of_scope"):
        val = _extract_list_field(fm_text, field)
        if val is not None:
            out[field] = val
    test_plan = _extract_test_plan(fm_text)
    if test_plan is not None:
        out["test_plan"] = test_plan
    # Scalars the new checks read; simple `key: value` lines the strict
    # parser would have handled — the fallback only fires on list-shaped
    # YAML errors, so a plain regex is enough here.
    for field in ("id", "security_relevant"):
        sm = re.search(rf"^{re.escape(field)}:\s*(\S.*?)\s*$", fm_text, re.MULTILINE)
        if sm:
            raw = sm.group(1).strip().strip('"\'')
            if field == "security_relevant":
                out[field] = raw.lower() == "true"
            else:
                out[field] = raw
    return out


_YAML_DQ_ESCAPES = {"\\": "\\", '"': '"', "n": "\n", "t": "\t",
                    "r": "\r", "'": "'", "/": "/", "0": "\0"}


def _yaml_unescape_dq(s):
    out = []
    i, n = 0, len(s)
    while i < n:
        if s[i] == "\\" and i + 1 < n:
            mapped = _YAML_DQ_ESCAPES.get(s[i + 1])
            if mapped is not None:
                out.append(mapped)
                i += 2
                continue
        out.append(s[i])
        i += 1
    return "".join(out)


def _extract_list_field(fm_text, field):
    m = re.search(rf"^{re.escape(field)}:\s*$", fm_text, re.MULTILINE)
    if not m:
        return None
    lines = fm_text[m.end():].split("\n")
    items = []
    current = None
    current_was_dq = False
    for line in lines[1:]:
        if line and not line[0].isspace() and re.match(r"^[a-z_]+:", line):
            break
        m2 = re.match(r"^  -\s+(.*)$", line)
        if m2:
            if current is not None:
                joined = "\n".join(current).strip()
                items.append(_yaml_unescape_dq(joined) if current_was_dq else joined)
            content = m2.group(1)
            was_dq = False
            if content.startswith('"') and content.endswith('"') and len(content) >= 2:
                content = content[1:-1]
                was_dq = True
            elif content.startswith("'") and content.endswith("'") and len(content) >= 2:
                content = content[1:-1]
            elif content.startswith('"'):
                content = content[1:]
                was_dq = True
            if content in ("|", ">-", "|-", ">"):
                current = []
                current_was_dq = False
            else:
                current = [content]
                current_was_dq = was_dq
            continue
        if current is not None and (not line or line.startswith("    ")):
            current.append(line[4:] if line.startswith("    ") else line)
            continue
        break
    if current is not None:
        if current_was_dq and current and current[-1].rstrip().endswith('"'):
            current[-1] = current[-1].rstrip()[:-1]
        joined = "\n".join(current).strip()
        items.append(_yaml_unescape_dq(joined) if current_was_dq else joined)
    return items


def _extract_test_plan(fm_text):
    m = re.search(r"^test_plan:\s*$", fm_text, re.MULTILINE)
    if not m:
        return None
    result = {}
    section = fm_text[m.end():]
    for sub in ("adds", "modifies", "preserves"):
        sm = re.search(rf"^  {re.escape(sub)}:\s*$", section, re.MULTILINE)
        if not sm:
            continue
        sub_lines = section[sm.end():].split("\n")
        items = []
        for line in sub_lines[1:]:
            if not line:
                continue
            if line.startswith("    - "):
                items.append(line[6:].strip())
            elif not line[0].isspace():
                break
            elif re.match(r"^  [a-z_]+:", line):
                break
        result[sub] = items
    return result if result else None


def extract_section(body, section_name):
    start_re = re.compile(rf"^##\s+{re.escape(section_name)}\s*$", re.MULTILINE)
    m = start_re.search(body)
    if not m:
        return ""
    start = m.end()
    next_m = re.search(r"^##\s", body[start:], re.MULTILINE)
    end = start + next_m.start() if next_m else len(body)
    return body[start:end]


# ---------- check: spec_refs resolvable ----------

_FENCE_RE = re.compile(r"^[ ]{0,3}(?:`{3,}|~{3,})")
_ATX_RE = re.compile(r"^[ ]{0,3}(#{1,6})[ \t]+\S")
_TRAILING_HASHES_RE = re.compile(r"\s+#+\s*$")


def _extract_headings(text):
    """Extract ATX headings from markdown, respecting fenced code blocks.

    Implements docs/process/workflow.md §"Spec-anchor resolution": toggle
    fence_open on fence delimiters, skip fenced lines, record ATX headings
    with their line number and stripped heading text.
    """
    headings = []
    fence_open = False
    for lineno, line in enumerate(text.split("\n"), start=1):
        if _FENCE_RE.match(line):
            fence_open = not fence_open
            continue
        if fence_open:
            continue
        if not _ATX_RE.match(line):
            continue
        heading_text = line.lstrip()
        heading_text = heading_text.lstrip("#")
        heading_text = heading_text.lstrip(" \t")
        heading_text = _TRAILING_HASHES_RE.sub("", heading_text).rstrip()
        headings.append((lineno, heading_text))
    return headings


_headings_cache: dict[str, list[tuple[int, str]]] = {}


def check_spec_refs_resolvable(fm):
    findings = []
    spec_refs = fm.get("spec_refs") or []
    if not isinstance(spec_refs, list):
        return findings

    for ref in spec_refs:
        if not isinstance(ref, str):
            continue
        parts = ref.split(" §", 1)
        if len(parts) != 2:
            findings.append(_finding(
                "SPEC-REFS-RESOLVABLE", "BLOCKER", None,
                detail=f"spec_ref '{ref}' does not match '<path> §<title>' format",
            ))
            continue

        file_path_str, section_title = parts
        file_path = pathlib.Path(file_path_str)

        if not file_path.is_file():
            findings.append(_finding(
                "SPEC-REFS-RESOLVABLE", "BLOCKER", None,
                detail=f"spec_ref file '{file_path_str}' does not exist",
            ))
            continue

        if file_path_str not in _headings_cache:
            try:
                _headings_cache[file_path_str] = _extract_headings(
                    file_path.read_text(encoding="utf-8"))
            except OSError as e:
                findings.append(_finding(
                    "SPEC-REFS-RESOLVABLE", "BLOCKER", None,
                    detail=f"spec_ref file '{file_path_str}' unreadable: {e}",
                ))
                continue

        headings = _headings_cache[file_path_str]
        search = section_title.lower()
        matches = [(ln, h) for ln, h in headings if search in h.lower()]

        if len(matches) == 0:
            findings.append(_finding(
                "SPEC-REFS-RESOLVABLE", "BLOCKER", None,
                detail=f"spec_ref '{ref}' → ANCHOR-NOT-FOUND "
                       f"(no heading in {file_path_str} contains '{section_title}')",
            ))
        elif len(matches) > 1:
            lines = ", ".join(str(ln) for ln, _ in matches)
            findings.append(_finding(
                "SPEC-REFS-RESOLVABLE", "WARN", None,
                detail=f"spec_ref '{ref}' → AMBIGUOUS (lines: {lines}); "
                       f"anchor resolution will pick one by the depth heuristic",
            ))

    return findings


# ---------- check: files_scope coverage ----------

FILE_PATH_RE = re.compile(
    r"`?\b([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+\."
    r"(?:java|sql|properties|json|yml|yaml|xml|md))`?"
)
INNER_CLASS_DISCLAIMER_RE = re.compile(
    r"inner class(?:es)?(?:\s+of\s+\w+)?|nested class(?:es)?|"
    r"as\s+(?:a\s+)?static\s+inner",
    re.IGNORECASE,
)

# Sections that may mention code-file paths. Includes the new §Notes
# section plus legacy section names so grandfathered tickets are still
# scanned correctly.
SECTIONS_TO_SCAN = [
    "Notes",                    # new template
    "Implementation notes",     # legacy
    "Authorized test changes",  # legacy
    "Big-picture notes",        # legacy
]


def _extract_path_token(entry):
    return entry.strip().split()[0] if entry and entry.strip() else entry


def check_files_scope_coverage(fm, body):
    findings = []
    files_scope_raw = fm.get("files_scope") or []
    if not isinstance(files_scope_raw, list):
        files_scope_raw = []
    scope = set(files_scope_raw)

    test_plan = fm.get("test_plan") or {}
    if isinstance(test_plan, dict):
        for key in ("adds", "modifies"):
            for entry in (test_plan.get(key) or []):
                if not isinstance(entry, str):
                    continue
                path = _extract_path_token(entry)
                if not path or path in scope:
                    continue
                if "*" in path:
                    continue
                if any(s.endswith("/" + path.rsplit("/", 1)[-1]) for s in scope):
                    continue
                findings.append(_finding(
                    "FILES-SCOPE-COVERAGE", "WARN", None,
                    detail=f"test_plan.{key} path '{path}' is not in files_scope "
                           f"(reviewer's negative-space check won't cover it)",
                ))

    for sec in SECTIONS_TO_SCAN:
        text = extract_section(body, sec)
        if not text:
            continue
        has_inner_disclaimer = bool(INNER_CLASS_DISCLAIMER_RE.search(text))
        seen_in_section = set()
        for m in FILE_PATH_RE.finditer(text):
            path = m.group(1)
            if path.startswith(("docs/", "scripts/", ".claude/", "target/", ".github/")):
                continue
            if path in scope or path in seen_in_section:
                continue
            base = path.rsplit("/", 1)[-1]
            if any(s.endswith(base) or s.endswith("/" + base) for s in scope):
                continue
            seen_in_section.add(path)
            if has_inner_disclaimer:
                continue
            findings.append(_finding(
                "FILES-SCOPE-COVERAGE", "WARN", None,
                detail=f"§{sec} mentions '{path}' but it is not in files_scope "
                       f"(add it, or note 'inner class of X' in the section)",
            ))
    return findings


# ---------- check: prose verbs in verify clauses ----------

PROSE_VERB_PHRASES = [
    r"by\s+reading\s+the\s+(?:file|method|class)",
    r"by\s+inspection",
    r"by\s+manual\s+(?:review|inspection)",
    r"should\s+be\s+present",
    r"loop\s+exits\s+when",
    r"a\s+loop\s+(?:that|which)\s+",
    r"verify\s+by\s+checking",
]
PROSE_VERB_RE = re.compile(
    r"Verify\s*:?[^.]{0,300}?\b(?:" + "|".join(PROSE_VERB_PHRASES) + r")\b",
    re.IGNORECASE,
)


def check_prose_verb(acceptance):
    findings = []
    if not isinstance(acceptance, list):
        return findings
    for idx, item in enumerate(acceptance, start=1):
        if not isinstance(item, str):
            continue
        for m in PROSE_VERB_RE.finditer(item):
            snippet = m.group(0)
            if len(snippet) > 120:
                snippet = snippet[:120] + "…"
            findings.append(_finding(
                "PROSE-VERB-IN-VERIFY", "WARN", idx, snippet,
                "Verify clause uses prose verb instead of a runnable command",
            ))
    return findings


# ---------- check: out_of_scope present and specific ----------

# Circular / non-committal out_of_scope entries that say nothing a reviewer
# can enforce. Kept deliberately short — a false WARN here is cheap, but the
# BLOCKER (empty list) is the load-bearing catch.
_VAGUE_OOS_RE = re.compile(
    r"^\s*(things?\s+)?(un|not\s+)related(\s+to\s+this\s+ticket)?\s*\.?$|"
    r"^\s*anything\s+not\s+(listed|mentioned)\b|"
    r"^\s*everything\s+else\b|^\s*n/?a\s*$|^\s*none\s*$",
    re.IGNORECASE,
)


def check_out_of_scope_present(fm, parsed_ok):
    findings = []
    if not parsed_ok:
        return findings  # fallback parse may miss the field; don't false-BLOCK
    oos = fm.get("out_of_scope")
    if not oos or (isinstance(oos, list) and len([e for e in oos if str(e).strip()]) == 0):
        findings.append(_finding(
            "OUT-OF-SCOPE-PRESENT", "BLOCKER", None,
            detail="out_of_scope is empty — declare the paths/features this "
                   "ticket must NOT touch (the reviewer's OUT-OF-SCOPE-CHECK "
                   "and the developer's boundary contract both read it)",
        ))
        return findings
    if isinstance(oos, list):
        for entry in oos:
            if isinstance(entry, str) and _VAGUE_OOS_RE.match(entry.strip()):
                findings.append(_finding(
                    "OUT-OF-SCOPE-PRESENT", "WARN", None, entry.strip()[:80],
                    "out_of_scope entry is circular/non-specific; name concrete "
                    "paths, files, or features instead",
                ))
    return findings


# ---------- check: forward ticket-ID references resolve ----------

_TICKET_ID_RE = re.compile(r"\bM[0-9]+-[0-9]+[a-z]*\b")
# Frontmatter fields where an unresolved ID is load-bearing (the workflow
# reads them to gate behavior), so an unresolved reference is a BLOCKER, not
# a WARN. (Absorbs the old clarity FORWARD-REFERENCE-CHECK.)
_LOAD_BEARING_ID_FIELDS = (
    "blocked_by", "deferred_on", "decomposed_from", "replaces",
    "replaced_by", "spec_amend_parent", "remediates",
)


def _ticket_file_exists(ticket_id):
    return bool(list(pathlib.Path("docs/plan").glob(f"*/tickets/{ticket_id}-*.md")))


def check_forward_references(fm, body, fm_text):
    findings = []
    own_id = str(fm.get("id") or "").strip()
    seen = set()

    # Load-bearing frontmatter fields first (BLOCKER on unresolved).
    for field in _LOAD_BEARING_ID_FIELDS:
        val = fm.get(field)
        vals = val if isinstance(val, list) else ([val] if val else [])
        for v in vals:
            for mid in _TICKET_ID_RE.findall(str(v)):
                if mid == own_id or mid in seen:
                    continue
                if not _ticket_file_exists(mid):
                    seen.add(mid)
                    findings.append(_finding(
                        "FORWARD-REFERENCE-RESOLVABLE", "BLOCKER", None,
                        detail=f"{field}: references {mid}, which has no ticket "
                               f"file under docs/plan/*/tickets/ — file the "
                               f"skeleton before depending on it",
                    ))

    # Body prose references (WARN on unresolved).
    for mid in _TICKET_ID_RE.findall(body):
        if mid == own_id or mid in seen:
            continue
        seen.add(mid)
        if not _ticket_file_exists(mid):
            findings.append(_finding(
                "FORWARD-REFERENCE-RESOLVABLE", "WARN", None, mid,
                "prose references a ticket ID with no file under "
                "docs/plan/*/tickets/ (typo, or a not-yet-filed follow-up)",
            ))
    return findings


# ---------- check: security-flag path inference ----------

# Path fragments that mark a security surface the /redteam gate keys off.
# When files_scope touches one of these but security_relevant is false, the
# ticket will silently skip the redteam gate — the M1-648 failure class.
_SECURITY_PATH_RE = re.compile(
    r"invite|grant-admin|revoke-admin|/admin|promote|demote|ban|unban|"
    r"InboundRouter|intake|[Ss]anitiz|LlmOutput|ChatTool|ToolRegistry|"
    r"ToolDispatcher|CommandPermissions|probation|/audit|AuditLog",
)


def check_security_flag_inference(fm, parsed_ok):
    findings = []
    if not parsed_ok:
        return findings
    if fm.get("security_relevant") is True:
        return findings
    scope = fm.get("files_scope") or []
    if not isinstance(scope, list):
        return findings
    for path in scope:
        if isinstance(path, str) and _SECURITY_PATH_RE.search(path):
            findings.append(_finding(
                "SECURITY-FLAG-INFERENCE", "WARN", None, path.strip(),
                "files_scope touches a security surface but "
                "security_relevant: false — the /redteam gate keys off this "
                "flag; set it true unless you are certain the surface is inert",
            ))
    return findings


# ---------- check: class-scoped ticket carries a §Census ----------

# Framing that signals the ticket fixes/guards a CLASS of code SITES rather
# than one instance. Deliberately HIGH-PRECISION: a domain plural ("each
# category", "every topic") is not a code-site class and must not trigger, or
# the check becomes noise authors learn to route around. Two trigger families:
# (1) explicit reconciliation/parity/duplication language about code; (2) a
# plural quantifier bound to a code-STRUCTURE noun (surface/handler/site/…).
_CLASS_SCOPE_RE = re.compile(
    r"\bparity\b|\breconcil|\bcensus\b|\bclosed list\b|"
    r"the same (pattern|shape|invariant|logic)\b|"
    r"(parity|invariant|completeness)\s+(guard|test|lint)\b|"
    r"duplicated\s+(between|across)\b|"
    r"\b(every|each|all|both|the\s+\d+)\s+"
    r"(surface|handler|site|cop(y|ies)|caller|adapter|module|"
    r"entry\s*point|call\s*site|declaration|place)s?\b",
    re.IGNORECASE,
)


def check_census_present(fm, body):
    findings = []
    haystack = " ".join([
        str(fm.get("title") or ""),
        " ".join(str(a) for a in (fm.get("acceptance") or []) if isinstance(a, str)),
    ])
    context = extract_section(body, "Context")
    haystack += " " + context
    if not _CLASS_SCOPE_RE.search(haystack):
        return findings
    if extract_section(body, "Census").strip():
        return findings
    m = _CLASS_SCOPE_RE.search(haystack)
    findings.append(_finding(
        "CENSUS-PRESENT-IF-CLASS-SCOPED", "WARN", None, m.group(0)[:60],
        "ticket reads as class-scoped but has no §Census section — enumerate "
        "the class mechanically (a re-runnable grep) so the developer and "
        "reviewer can verify every site was disposed",
    ))
    return findings


# ---------- finding object + reporting ----------

def _finding(check, severity, item_idx, command=None, detail=""):
    return {
        "check": check,
        "severity": severity,
        "item": item_idx,
        "command": command,
        "detail": detail,
    }


def report_file(path, findings, fm_error, quiet):
    blockers = [f for f in findings if f["severity"] == "BLOCKER"]
    warnings = [f for f in findings if f["severity"] == "WARN"]
    if blockers:
        verdict, vcolor = "FAIL", RED
    elif warnings or fm_error:
        verdict, vcolor = "WARN", YELLOW
    else:
        verdict, vcolor = "PASS", GREEN
    if quiet and verdict == "PASS":
        return verdict
    print(f"{color(verdict, vcolor)}  {path}  "
          f"({len(blockers)} blockers, {len(warnings) + (1 if fm_error else 0)} warnings)")
    if fm_error:
        print(f"  {color('WARN', YELLOW)}  FRONTMATTER-PARSEABLE")
        print(f"    {color('→', DIM)} {fm_error}")
    for f in findings:
        sev_c = RED if f["severity"] == "BLOCKER" else YELLOW
        loc = f"item {f['item']}" if f["item"] is not None else "frontmatter/body"
        print(f"  {color(f['severity'], sev_c)}  {f['check']}  ({loc})")
        if f["command"]:
            print(f"    {color('cmd:', DIM)} {f['command']}")
        print(f"    {color('→', DIM)} {f['detail']}")
    return verdict


# ---------- driver ----------

def lint_one(path, quiet):
    fm, body, fm_error = split_ticket(path)
    fm_text = _frontmatter_text(path)
    parsed_ok = fm_error is None
    findings = []
    acceptance = fm.get("acceptance") or []
    findings.extend(check_spec_refs_resolvable(fm))
    findings.extend(check_files_scope_coverage(fm, body))
    findings.extend(check_prose_verb(acceptance))
    findings.extend(check_out_of_scope_present(fm, parsed_ok))
    findings.extend(check_forward_references(fm, body, fm_text))
    findings.extend(check_security_flag_inference(fm, parsed_ok))
    findings.extend(check_census_present(fm, body))
    return report_file(path, findings, fm_error, quiet)


def _frontmatter_text(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return ""
    end = text.find("\n---\n", 4)
    return text[4:end] if end >= 0 else ""


def main(argv):
    ap = argparse.ArgumentParser(description="Author-side ticket linter")
    ap.add_argument("paths", nargs="+", help="Ticket file paths (.md)")
    ap.add_argument("-q", "--quiet", action="store_true",
                    help="suppress PASS lines; only print files with findings")
    args = ap.parse_args(argv[1:])

    any_fail = False
    for p in args.paths:
        path = pathlib.Path(p)
        if not path.is_file():
            print(f"{color('SKIP', YELLOW)}  {p} (not a file)")
            continue
        v = lint_one(path, args.quiet)
        if v == "FAIL":
            any_fail = True
    return 1 if any_fail else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
