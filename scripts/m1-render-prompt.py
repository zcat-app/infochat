#!/usr/bin/env python3
"""
Render an m1-tick subagent prompt template into a file.

Extracts the fenced template body from a markdown template (the content
between the first and last triple-fence markers in the file), substitutes
`{{KEY}}` placeholders with caller-supplied values, and writes the result
to a file on disk. The m1-tick skill spawns the subagent with a small stub
prompt that instructs the subagent to Read the resulting file, so the main
session never loads the full template into its own context.

Usage:
  m1-render-prompt.py <template-path> <output-path> [KEY=VALUE ...]

Substitution-value forms:
  KEY=literal-string        Substitute the literal string.
  KEY=@/path/to/file        Read the file's contents and substitute that
                            (use for multi-line values like NEGATIVE_SPACE_LIST).

Exit codes:
  0 success
  2 bad arguments
  3 template has fewer than two fence markers (malformed)

The script prints a single status line to stdout on success and exits zero.
Unfilled `{{KEY}}` placeholders in the rendered output trigger a stderr
warning but do not fail the run — the subagent will see them and report.
"""
import re
import sys
from pathlib import Path


def read_value(raw: str) -> str:
    if raw.startswith("@"):
        return Path(raw[1:]).read_text(encoding="utf-8")
    return raw


def main() -> int:
    if len(sys.argv) < 3:
        print(
            "Usage: m1-render-prompt.py <template> <output> [KEY=VALUE ...]",
            file=sys.stderr,
        )
        return 2

    template_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])

    substitutions: dict[str, str] = {}
    for arg in sys.argv[3:]:
        if "=" not in arg:
            print(f"Bad substitution arg (expected KEY=VALUE): {arg}", file=sys.stderr)
            return 2
        key, raw = arg.split("=", 1)
        substitutions[key] = read_value(raw)

    text = template_path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    fence_indices = [i for i, line in enumerate(lines) if line.startswith("```")]
    if len(fence_indices) < 2:
        print(
            f"Template {template_path} has fewer than 2 fence markers; cannot extract body",
            file=sys.stderr,
        )
        return 3
    first = fence_indices[0]
    last = fence_indices[-1]
    body = "".join(lines[first + 1 : last])

    for key, value in substitutions.items():
        body = body.replace("{{" + key + "}}", value)

    unfilled = sorted(set(re.findall(r"\{\{([A-Z_]+)\}\}", body)))
    if unfilled:
        print(
            f"WARN: unfilled placeholders in {output_path.name}: {unfilled}",
            file=sys.stderr,
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(body, encoding="utf-8")
    print(f"Wrote {len(body)} bytes to {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
