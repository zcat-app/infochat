#!/usr/bin/env python3
"""Comment-cap check for /tick diffs (advisory).

Usage: tick-comment-cap.py <unified-diff-file>

The tick flow caps new comment rationale at 3 consecutive lines per call
site (tick-workflow.md §Principles 8). The rule alone was violated with it
loaded in context (M1-785 session), so this makes it mechanical: for each
hunk, count maximal runs of consecutive ADDED lines whose content is a
comment; runs longer than the cap print a WARN with the file and the new
line number. Markdown files are exempt (the cap is about code comments).

Always exits 0 — the output is input to the reviewer's MAINTAINABILITY
check and the implementor's self-check, not a gate by itself.
"""

import re
import sys
from pathlib import Path

CAP = 3
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")
COMMENT_PREFIXES = ("//", "/*", "*", "*/", "#", "<!--", "--")


def is_comment_line(content: str) -> bool:
    stripped = content.strip()
    if not stripped:
        return False
    return stripped.startswith(COMMENT_PREFIXES)


def main(argv):
    if len(argv) != 1:
        print(f"usage: {Path(sys.argv[0]).name} <unified-diff-file>",
              file=sys.stderr)
        return 2
    text = Path(argv[0]).read_text(errors="replace")

    warnings = []
    current_file = None
    exempt = False
    new_lineno = 0
    run_length = 0
    run_start = 0

    def flush_run():
        nonlocal run_length, run_start
        if run_length > CAP and not exempt and current_file:
            warnings.append(
                f"COMMENT-CAP: WARN: {current_file}:{run_start} — "
                f"{run_length} consecutive added comment lines (cap {CAP})")
        run_length = 0
        run_start = 0

    for line in text.splitlines():
        if line.startswith("+++ "):
            flush_run()
            path = line[4:].strip()
            current_file = path[2:] if path.startswith("b/") else path
            exempt = current_file.endswith(".md") or current_file == "/dev/null"
            continue
        m = HUNK_RE.match(line)
        if m:
            flush_run()
            new_lineno = int(m.group(1))
            continue
        if current_file is None:
            continue
        if line.startswith("+"):
            if is_comment_line(line[1:]):
                if run_length == 0:
                    run_start = new_lineno
                run_length += 1
            else:
                flush_run()
            new_lineno += 1
        elif line.startswith("-"):
            # removed line: does not advance the new file, does not break
            # the added-comment run (a rewritten block is still one block)
            continue
        elif line.startswith(("diff ", "index ", "--- ", "new file",
                              "deleted file", "similarity", "rename ",
                              "Binary")):
            flush_run()
        else:
            flush_run()
            new_lineno += 1
    flush_run()

    for w in warnings:
        print(w)
    print(f"tick-comment-cap: {len(warnings)} run(s) over the cap of {CAP}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
