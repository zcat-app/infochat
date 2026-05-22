#!/usr/bin/env python3
"""
Split a ticket file into current-state + history-context pieces.

The clarity and Plan subagent prompts consume two files:
  - <current-path>: the ticket as it stands now (body + frontmatter
    with `escalations:` and `revisions:` keys removed)
  - <history-path>: just the `escalations:` and `revisions:` YAML
    blocks, or a `# No history` sentinel when neither key is present

The split is mechanical (top-level YAML key parse, no semantic
interpretation) so the reviewer never has to decide which content is
historical audit-trail vs. which is current commitment. The failure
mode this avoids: an LLM-driven body scan pattern-matches a phrase
quoted inside a `revisions:` summary (where the summary says "stripped
X") and reports the phrase as a current commitment, even though the
refine already removed it from the body. Putting the boundary in a
script removes that judgment surface.

Usage:
  m1-split-ticket.py <ticket-path> <current-out> <history-out>

Exit codes:
  0 success
  2 bad arguments
  3 ticket has fewer than two frontmatter `---` markers
"""
import re
import sys
from pathlib import Path

HISTORY_KEYS = ("escalations", "revisions")
HISTORY_SENTINEL = "# No history (no escalations or revisions on this ticket)\n"
TOP_LEVEL_KEY_RE = re.compile(r"^([a-zA-Z_][a-zA-Z0-9_]*):")


def split_frontmatter(yaml_lines):
    """Partition a list of YAML frontmatter lines into (current, history).

    A line is "in history" when the most recent top-level key on or
    before that line is one of HISTORY_KEYS. Top-level keys are
    matched by TOP_LEVEL_KEY_RE (column-zero alphanumeric identifier
    followed by `:`). Continuation lines (indented, list items, block
    scalars) inherit the in-history flag from the most recent
    top-level key.
    """
    current = []
    history = []
    in_history = False
    for line in yaml_lines:
        m = TOP_LEVEL_KEY_RE.match(line)
        if m:
            in_history = m.group(1) in HISTORY_KEYS
        if in_history:
            history.append(line)
        else:
            current.append(line)
    return current, history


def split_ticket(text):
    """Return (current_text, history_text) for a ticket markdown file."""
    lines = text.splitlines(keepends=True)
    dashes = [i for i, line in enumerate(lines) if line.strip() == "---"]
    if len(dashes) < 2:
        raise ValueError("ticket has fewer than 2 frontmatter --- markers")
    fm_start, fm_end = dashes[0], dashes[1]
    yaml_body = lines[fm_start + 1 : fm_end]
    current_yaml, history_yaml = split_frontmatter(yaml_body)
    current_text = (
        lines[fm_start]
        + "".join(current_yaml)
        + "".join(lines[fm_end:])
    )
    history_text = "".join(history_yaml) if history_yaml else HISTORY_SENTINEL
    return current_text, history_text


def main():
    if len(sys.argv) != 4:
        print(
            "Usage: m1-split-ticket.py <ticket-path> <current-out> <history-out>",
            file=sys.stderr,
        )
        return 2
    ticket_path = Path(sys.argv[1])
    current_path = Path(sys.argv[2])
    history_path = Path(sys.argv[3])

    text = ticket_path.read_text(encoding="utf-8")
    try:
        current_text, history_text = split_ticket(text)
    except ValueError as e:
        print(f"Cannot split {ticket_path}: {e}", file=sys.stderr)
        return 3

    current_path.parent.mkdir(parents=True, exist_ok=True)
    history_path.parent.mkdir(parents=True, exist_ok=True)
    current_path.write_text(current_text, encoding="utf-8")
    history_path.write_text(history_text, encoding="utf-8")
    print(
        f"Split {ticket_path}: "
        f"current={len(current_text)}B -> {current_path}, "
        f"history={len(history_text)}B -> {history_path}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
