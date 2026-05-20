#!/usr/bin/env python3
"""Author-side static linter for ticket files.

Runs six static checks against a ticket's frontmatter and body to
catch the recurring clarity-check failure patterns at author time,
*before* `/m1-tick start` spawns the clarity-reviewer subagent. Each
check encodes one or more incidents from the recurring-pattern
catalogue (see [[no-heterogeneous-aggregate-test-counts]] and
[[files-scope-implementation-notes-crosscheck]] in author-memory).

Usage:
  scripts/lint-ticket.py <path/to/ticket.md> [<path/to/ticket.md> ...]
  scripts/lint-ticket.py docs/plan/m1/tickets/M1-NNN-*.md
  scripts/lint-ticket.py --quiet ...   # only print findings, not PASS lines

Exit codes:
  0 — every file CLEAN or WARN-only
  1 — at least one file has BLOCKERS

The six checks (severity, rationale):

  GREP-SHELL-PARSEABLE       BLOCKER
    Every `grep ...` backtick in an acceptance item is fed to
    `bash -n` (parse only, no execution). Catches truly unterminated
    quotes and other syntax-level shell errors.

  GREP-EMBEDDED-QUOTE        BLOCKER
    Detects the `'A''B''C'` smell where an author tries to embed
    literal single quotes inside a single-quoted bash regex by
    juxtaposing empty strings. Balances cleanly under bash -n but
    the embedded apostrophes vanish at quote-removal — the regex
    bash hands to grep does NOT contain the intended `'…'`. Correct
    embeddings are `'\''` or switching the outer delimiter to `"…"`.
    Catches M1-044a item 5.

  REGEX-COMPILABLE           BLOCKER
    The pattern argument of every `grep -E '...'` in acceptance is
    compiled with Python's `re` module. Catches malformed regex
    (unbalanced brackets, bad backreference, etc.).

  GREP-CROSS-LINE-NEWLINE    BLOCKER
    Detects `\\n` (or `\\s*\\n`) inside a `grep -E` pattern run
    without `-z` (NUL separator) or `-P` + multiline. GNU grep
    processes input line-by-line; each record passed to the regex
    engine does NOT include the terminating newline, so `\\n` in
    `-E` patterns never matches a real line boundary — the grep
    always returns 0 matches regardless of file content. Catches
    M1-044a round-2 refine items 3-6, 13-19, 21-23, 25-26.
    Recommended idiomatic single-line replacement for "the file
    contains a @Test method named X":
      grep -iE 'void\\s+\\w*X\\w*\\s*\\(' TestFile.java returns >=1 match

  FILES-SCOPE-COVERAGE       WARN
    Cross-checks files_scope membership. (a) test_plan.adds /
    test_plan.modifies entries not in files_scope warn: the
    reviewer's negative-space check won't cover them. (b) Code-file
    paths mentioned in §Implementation notes / §Authorized test
    changes / §Big-picture notes that are not in files_scope warn
    too, unless the section contains an explicit "inner class of X"
    disclaimer. Catches M1-026/30/32/33/35a/44a.

  HETEROGENEOUS-AGGREGATE-TEST-COUNT   WARN
    Acceptance items asserting `grep -E '@Test' <file> returns ≥N`
    with N >= 3 collapse structurally-different test methods into
    one count. Recommend per-method greps. Catches M1-026/27/28/33.

  PROSE-VERB-IN-VERIFY       WARN
    Verify clauses containing "by reading", "by inspection",
    "should be present", or "loop exits" are not mechanically
    checkable. Catches M1-030/32/36/39.

  IMPLEMENTATION-NOTES-ACCEPTANCE-CROSS-REF   WARN
    When body §Implementation notes claims "acceptance grep
    confirms ..." (or asserts/verifies), the nearest backticked
    identifier must appear in at least one acceptance item.
    Catches M1-044a's `invite_drop_total` self-contradiction.
"""

import argparse
import pathlib
import re
import subprocess
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
        # Frontmatter has strict-YAML defects (backticks-in-title, embedded
        # quotes, etc.) — some committed tickets that PASSED clarity carry
        # these (the downstream tools use targeted extraction). Try the
        # targeted fallback so the linter still covers what it can.
        fm = _targeted_extract(fm_text)
        err_msg = str(e).splitlines()[0] if str(e) else "unknown"
        return fm, body, f"frontmatter not strict YAML ({err_msg}); using targeted fallback"


def _targeted_extract(fm_text):
    """Best-effort extraction of the fields the linter needs from
    frontmatter that pyyaml can't parse strictly. Returns a dict with the
    same shape as yaml.safe_load would, populated for the fields we
    successfully recovered."""
    out = {}
    files_scope = _extract_list_field(fm_text, "files_scope")
    if files_scope is not None:
        out["files_scope"] = files_scope
    acceptance = _extract_list_field(fm_text, "acceptance")
    if acceptance is not None:
        out["acceptance"] = acceptance
    test_plan = _extract_test_plan(fm_text)
    if test_plan is not None:
        out["test_plan"] = test_plan
    return out


_YAML_DQ_ESCAPES = {"\\": "\\", '"': '"', "n": "\n", "t": "\t",
                     "r": "\r", "'": "'", "/": "/", "0": "\0"}


def _yaml_unescape_dq(s):
    """Process YAML double-quoted-scalar escapes (\\\\ -> \\, \\\" -> \", etc.)."""
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
    """Extract a top-level YAML block list as a list of strings, joining
    multi-line block scalars into one string with newlines preserved.
    Returns None if the field is absent."""
    m = re.search(rf"^{re.escape(field)}:\s*$", fm_text, re.MULTILINE)
    if not m:
        return None
    lines = fm_text[m.end():].split("\n")
    items = []
    current = None
    current_was_dq = False
    for line in lines[1:]:  # skip leading blank after header
        # Top-level key boundary (no leading whitespace, looks like 'key:')
        if line and not line[0].isspace() and re.match(r"^[a-z_]+:", line):
            break
        # New list item at indent 2: "  - <content>"
        m2 = re.match(r"^  -\s+(.*)$", line)
        if m2:
            if current is not None:
                joined = "\n".join(current).strip()
                items.append(_yaml_unescape_dq(joined) if current_was_dq else joined)
            content = m2.group(1)
            was_dq = False
            # Strip surrounding quotes if present
            if content.startswith('"') and content.endswith('"') and len(content) >= 2:
                content = content[1:-1]
                was_dq = True
            elif content.startswith("'") and content.endswith("'") and len(content) >= 2:
                content = content[1:-1]
            elif content.startswith('"'):
                # Multi-line double-quoted scalar; track the open quote
                content = content[1:]
                was_dq = True
            # Skip block-scalar indicators ('|', '>-')
            if content in ("|", ">-", "|-", ">"):
                current = []
                current_was_dq = False
            else:
                current = [content]
                current_was_dq = was_dq
            continue
        # Continuation lines (indent ≥ 4) belong to the current item.
        if current is not None and (not line or line.startswith("    ")):
            current.append(line[4:] if line.startswith("    ") else line)
            continue
        # Anything else: break out
        break
    if current is not None:
        # Trim a trailing close-quote on the last line for multi-line dq scalars.
        if current_was_dq and current and current[-1].rstrip().endswith('"'):
            current[-1] = current[-1].rstrip()[:-1]
        joined = "\n".join(current).strip()
        items.append(_yaml_unescape_dq(joined) if current_was_dq else joined)
    return items


def _extract_test_plan(fm_text):
    """Extract test_plan: {adds: [...], modifies: [...], preserves: [...]}
    in best-effort form."""
    m = re.search(r"^test_plan:\s*$", fm_text, re.MULTILINE)
    if not m:
        return None
    result = {}
    section = fm_text[m.end():]
    # Walk for nested keys: "  adds:", "  modifies:", "  preserves:"
    for sub in ("adds", "modifies", "preserves"):
        sm = re.search(rf"^  {re.escape(sub)}:\s*$", section, re.MULTILINE)
        if not sm:
            continue
        # Collect lines after, indented 4+, stopping at next sub-key or top-level
        sub_lines = section[sm.end():].split("\n")
        items = []
        for line in sub_lines[1:]:
            if not line:
                continue
            if line.startswith("    - "):
                items.append(line[6:].strip())
            elif not line[0].isspace():
                break  # top-level key
            elif re.match(r"^  [a-z_]+:", line):
                break  # next sub-key
        result[sub] = items
    return result if result else None


# ---------- check 1+2: grep shell-parse + regex compile ----------

# Backtick-delimited grep command, e.g. `grep -E 'pat' file.java`.
GREP_BACKTICK_RE = re.compile(r"`(grep\s+[^`]+)`")

# -E or -cE followed by a single- or double-quoted pattern argument.
GREP_PATTERN_RE = re.compile(
    r"-c?E\s+"
    r"(?:'((?:[^'\\]|\\.)*)'"
    r"|\"((?:[^\"\\]|\\.)*)\")"
)


def extract_grep_commands(acceptance):
    """Yield (item_idx, command_str) tuples for every backticked grep."""
    out = []
    if not isinstance(acceptance, list):
        return out
    for idx, item in enumerate(acceptance, start=1):
        if not isinstance(item, str):
            continue
        for m in GREP_BACKTICK_RE.finditer(item):
            out.append((idx, m.group(1).strip()))
    return out


def check_grep_shell_parseable(grep_commands):
    findings = []
    for idx, cmd in grep_commands:
        try:
            result = subprocess.run(
                ["bash", "-nc", cmd],
                capture_output=True, text=True, timeout=5,
            )
        except subprocess.TimeoutExpired:
            findings.append(_finding("GREP-SHELL-PARSEABLE", "BLOCKER", idx,
                                     cmd, "bash -n timed out (5s)"))
            continue
        if result.returncode != 0:
            detail = (result.stderr.strip().splitlines() or ["unknown bash error"])[0]
            # Strip the bash:-c:1: prefix bash emits for parse errors.
            detail = re.sub(r"^bash:\s*-c:?\s*(?:line\s+\d+:)?\s*", "", detail).strip()
            findings.append(_finding("GREP-SHELL-PARSEABLE", "BLOCKER", idx,
                                     cmd, detail))
    return findings


# `''<word>''` pattern inside a grep command: the failed-embedded-quote
# smell. Author intended to match a literal single quote around <word>,
# but bash concatenates the empty quoted strings and the apostrophes are
# absent in the regex actually handed to grep.
EMBEDDED_QUOTE_SMELL_RE = re.compile(r"''[A-Za-z0-9_]+''")


def check_grep_embedded_quote(grep_commands):
    findings = []
    for idx, cmd in grep_commands:
        for m in EMBEDDED_QUOTE_SMELL_RE.finditer(cmd):
            findings.append(_finding(
                "GREP-EMBEDDED-QUOTE", "BLOCKER", idx, cmd,
                f"`{m.group(0)}` inside a single-quoted regex: bash strips "
                f"the apostrophes via empty-string concat. Use `'\\''` to "
                f"embed a literal single quote, or switch the outer "
                f"delimiter to double quotes.",
            ))
    return findings


def check_regex_compilable(grep_commands):
    findings = []
    for idx, cmd in grep_commands:
        for m in GREP_PATTERN_RE.finditer(cmd):
            pattern = m.group(1) if m.group(1) is not None else m.group(2)
            try:
                re.compile(pattern)
            except re.error as e:
                findings.append(_finding("REGEX-COMPILABLE", "BLOCKER", idx,
                                         cmd, f"re.error: {e}"))
    return findings


# Any `grep ... -E 'pattern'` or `grep ... -E "pattern"`, with or without
# surrounding backticks. Used by checks that only need the pattern (not the
# full shell-parseable command line).
GREP_ANY_RE = re.compile(
    r"\bgrep\s+(?:-[a-zA-Z]+\s+)*"
    r"-[a-zA-Z]*E\s+"
    r"(?:'((?:[^'\\]|\\.)*)'"
    r"|\"((?:[^\"\\]|\\.)*)\")"
)


def extract_grep_patterns(acceptance):
    """Yield (item_idx, pattern_str) for every -E pattern in any grep
    command, backticked or in prose."""
    out = []
    if not isinstance(acceptance, list):
        return out
    for idx, item in enumerate(acceptance, start=1):
        if not isinstance(item, str):
            continue
        for m in GREP_ANY_RE.finditer(item):
            pattern = m.group(1) if m.group(1) is not None else m.group(2)
            # Skip if the surrounding grep invocation opted into -z or -P.
            window = item[max(0, m.start() - 40):m.start()]
            if re.search(r"-[a-zA-Z]*[zP]\b", window):
                continue
            out.append((idx, pattern))
    return out


def check_grep_cross_line_newline(acceptance):
    """Detect `\\n` inside grep -E patterns without -z/-P. GNU grep is
    line-oriented; \\n in -E never matches a real line boundary, so any
    such pattern returns 0 matches regardless of file content."""
    findings = []
    for idx, pattern in extract_grep_patterns(acceptance):
        if "\\n" in pattern:
            findings.append(_finding(
                "GREP-CROSS-LINE-NEWLINE", "BLOCKER", idx, pattern[:100],
                "regex contains `\\n`; GNU grep is line-oriented, so the "
                "newline never matches a real line boundary in -E mode. "
                "Use a single-line pattern (e.g. `void\\s+\\w*<name>\\w*\\s*\\(`) "
                "or pass -z/-P with multiline flags.",
            ))
    return findings


# ---------- check 3: files_scope coverage ----------

# Code-file path: a path with at least one slash and a known source extension.
# Matches relative paths in the form `module/src/.../File.java` or
# `infochat-core/src/main/resources/db/migration/V12__name.sql`. Avoids
# matching bare filenames (without a slash) which are usually informal
# mentions, not paths.
FILE_PATH_RE = re.compile(
    r"`?\b([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+\."
    r"(?:java|sql|properties|json|yml|yaml|xml|md))`?"
)
INNER_CLASS_DISCLAIMER_RE = re.compile(
    r"inner class(?:es)?(?:\s+of\s+\w+)?|nested class(?:es)?|"
    r"as\s+(?:a\s+)?static\s+inner",
    re.IGNORECASE,
)


def extract_section(body, section_name):
    start_re = re.compile(rf"^##\s+{re.escape(section_name)}\s*$", re.MULTILINE)
    m = start_re.search(body)
    if not m:
        return ""
    start = m.end()
    next_m = re.search(r"^##\s", body[start:], re.MULTILINE)
    end = start + next_m.start() if next_m else len(body)
    return body[start:end]


def _extract_path_token(entry):
    """From a test_plan.adds/modifies entry (which may be 'path (desc)'),
    return only the leading whitespace-bounded path token."""
    return entry.strip().split()[0] if entry and entry.strip() else entry


def check_files_scope_coverage(fm, body):
    findings = []
    files_scope_raw = fm.get("files_scope") or []
    if not isinstance(files_scope_raw, list):
        files_scope_raw = []
    scope = set(files_scope_raw)

    # WARN half (a): every test_plan.adds and test_plan.modifies entry's
    # path token should appear in files_scope so the reviewer's
    # negative-space check covers it. The convention allows a parenthetical
    # description after the path; strip it before comparing.
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
                    continue  # glob hint; files_scope uses concrete paths
                if any(s.endswith("/" + path.rsplit("/", 1)[-1]) for s in scope):
                    continue
                findings.append(_finding(
                    "FILES-SCOPE-COVERAGE", "WARN", None,
                    detail=f"test_plan.{key} path '{path}' is not in files_scope "
                           f"(reviewer's negative-space check won't cover it)",
                ))

    # WARN half (b): code-file path mentioned in body section but not in files_scope.
    sections_to_scan = ["Implementation notes", "Authorized test changes", "Big-picture notes"]
    for sec in sections_to_scan:
        text = extract_section(body, sec)
        if not text:
            continue
        has_inner_disclaimer = bool(INNER_CLASS_DISCLAIMER_RE.search(text))
        seen_in_section = set()
        for m in FILE_PATH_RE.finditer(text):
            path = m.group(1)
            # Skip non-source paths: docs, scripts, .claude, target/, dotfiles.
            if path.startswith(("docs/", "scripts/", ".claude/", "target/", ".github/")):
                continue
            if path in scope or path in seen_in_section:
                continue
            # Fuzzy: if any scope entry ends with the same basename, accept.
            base = path.rsplit("/", 1)[-1]
            if any(s.endswith(base) or s.endswith("/" + base) for s in scope):
                continue
            seen_in_section.add(path)
            if has_inner_disclaimer:
                continue  # author has explicitly justified inner-class realization
            findings.append(_finding(
                "FILES-SCOPE-COVERAGE", "WARN", None,
                detail=f"§{sec} mentions '{path}' but it is not in files_scope "
                       f"(add it, or note 'inner class of X' in the section)",
            ))
    return findings


# ---------- check 4: heterogeneous-aggregate @Test count ----------

# Match `grep -E '@Test' <file>.java` followed by some prose and "≥N" / ">=N" / "at least N".
AGGREGATE_TEST_RE = re.compile(
    r"`grep\s+-c?E\s+['\"]@Test['\"]\s+\S+\.java`"
    r"[^.]*?"
    r"(?:≥|>=|at\s+least)\s*(\d+)\s*match",
    re.IGNORECASE,
)


def check_heterogeneous_aggregate(acceptance):
    findings = []
    if not isinstance(acceptance, list):
        return findings
    for idx, item in enumerate(acceptance, start=1):
        if not isinstance(item, str):
            continue
        for m in AGGREGATE_TEST_RE.finditer(item):
            n = int(m.group(1))
            if n >= 3:
                findings.append(_finding(
                    "HETEROGENEOUS-AGGREGATE-TEST-COUNT", "WARN", idx,
                    m.group(0),
                    f"aggregate @Test count ≥{n} collapses structurally-different "
                    f"tests; split into per-method greps naming each test by name",
                ))
    return findings


# ---------- check 5: prose-verb in Verify ----------

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


# ---------- check 6: impl-notes ↔ acceptance cross-ref ----------

CROSS_REF_CLAIM_RE = re.compile(
    r"(?:an?\s+)?acceptance\s+(?:item|grep)\s+(?:confirms|asserts|verifies|covers)",
    re.IGNORECASE,
)
BACKTICKED_IDENT_RE = re.compile(r"`([A-Za-z_][A-Za-z0-9_.]*)`")


def check_impl_notes_cross_ref(fm, body):
    findings = []
    impl_text = extract_section(body, "Implementation notes")
    bigp_text = extract_section(body, "Big-picture notes")
    notes_text = impl_text + "\n" + bigp_text
    if not notes_text.strip():
        return findings
    acceptance_blob = "\n".join(
        s for s in (fm.get("acceptance") or []) if isinstance(s, str)
    )
    for m in CROSS_REF_CLAIM_RE.finditer(notes_text):
        # Search back ~300 chars for the nearest backticked identifier.
        start = max(0, m.start() - 300)
        window = notes_text[start:m.start()]
        idents = BACKTICKED_IDENT_RE.findall(window)
        if not idents:
            continue
        nearest = idents[-1]  # closest to the claim
        # Skip purely-syntactic backticks (annotations, common keywords).
        if nearest in {"true", "false", "null", "@Test", "@Override", "@Inject"}:
            continue
        if nearest not in acceptance_blob:
            findings.append(_finding(
                "IMPLEMENTATION-NOTES-ACCEPTANCE-CROSS-REF", "WARN", None,
                m.group(0),
                f"body claims acceptance covers '{nearest}' but '{nearest}' "
                f"is not mentioned in any acceptance item",
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
    findings = []
    acceptance = fm.get("acceptance") or []
    greps = extract_grep_commands(acceptance)
    findings.extend(check_grep_shell_parseable(greps))
    findings.extend(check_grep_embedded_quote(greps))
    findings.extend(check_regex_compilable(greps))
    findings.extend(check_grep_cross_line_newline(acceptance))
    findings.extend(check_files_scope_coverage(fm, body))
    findings.extend(check_heterogeneous_aggregate(acceptance))
    findings.extend(check_prose_verb(acceptance))
    findings.extend(check_impl_notes_cross_ref(fm, body))
    return report_file(path, findings, None, quiet)


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
