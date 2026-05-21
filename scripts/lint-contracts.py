#!/usr/bin/env python3
"""Lint Java files for JSpecify @NonNull/@Nullable annotations on public-method
reference-type parameters.

Enforces the §"Method parameter contracts" rule from CLAUDE.md: every
reference-type parameter on a public/protected method (or any method in an
interface, since interface methods are implicitly public) must declare
nullability via @NonNull or @Nullable from org.jspecify.annotations. Internal
methods (private, package-private inside a class) are NOT checked — they
inherit the default per the engineering rule.

Usage:
  scripts/lint-contracts.py [--baseline <file>] <java-file> [<java-file> ...]
  scripts/lint-contracts.py --help

Exit codes:
  0 — every reference-type parameter on every public method is annotated,
      or every finding is suppressed by the baseline.
  1 — at least one public-method reference-type parameter is missing
      @NonNull/@Nullable and is not in the baseline.

The --baseline flag reads a file of `path:method` entries (one per line,
# starts a comment). Findings whose `<path>:<method-name>` matches a non-
comment entry are suppressed. This is the grandfathering surface that lets
future tickets widen the retroactive annotation pass incrementally.

The parser is regex-based (no full Java AST library). It is correct for the
typical method-declaration shapes in this codebase: single top-level type
per file, methods at depth 1 inside that type, generics with balanced
angle brackets, throws clauses spanning lines. Method bodies, nested
anonymous classes, lambda parameters, and method invocations inside bodies
are NOT scanned for method-shape (the regex requires `\\s+name\\s*(` so
`target.send(` does not match — the `.` separator is not whitespace).
"""

import argparse
import re
import sys
from pathlib import Path

PRIMITIVES = {
    "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
}

CONTROL_FLOW_KEYWORDS = {
    "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throw", "try"
}

# Match a method declaration. Anchored to start-of-line (re.MULTILINE) so
# method-level annotations on a previous line cannot bleed into the return
# type and shadow the visibility modifier. The non-greedy `ret` swallows
# the return type (may include generics like Map<String, List<String>>) up
# to the last whitespace before the method name. Constructors are NOT
# matched because the class-name token is immediately followed by `(`
# (no whitespace separator before the name group). Field declarations are
# NOT matched because they terminate in `;` without a `(...)` parameter
# list. Method calls in bodies are NOT matched because they typically
# follow `.` rather than whitespace (and aren't at line start).
METHOD_RE = re.compile(
    r'^[ \t]*'
    r'(?P<head>(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\s+)*)'
    r'(?P<ret>[A-Za-z_<][\w<>?\[\] \t,.&]*?)\s+'
    r'(?P<name>\w+)\s*'
    r'\((?P<params>[^()]*)\)'
    r'\s*(?:throws\s+[\w,\s.]+?)?'
    r'\s*[{;]',
    re.MULTILINE
)


def strip_comments(content: str) -> str:
    """Remove // line comments and /* */ block comments so they cannot
    spuriously match METHOD_RE or contaminate is_in_interface's lookback."""
    content = re.sub(r'//[^\n]*', '', content)
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    return content


def strip_method_level_annotations(content: str) -> str:
    """Strip Java annotations (@Foo, @Foo(args)) at parenthesis depth 0 so
    they cannot bleed into METHOD_RE's return-type group and shadow the
    visibility modifier. Annotations INSIDE a parameter list (depth > 0)
    are preserved verbatim — those are the @NonNull/@Nullable we lint for.

    The replacement is a single space so consecutive method-level
    annotations followed by `public ...` collapse into ` public ...`
    instead of `@Foo@Bar public ...` (which the regex couldn't parse).
    """
    out: list[str] = []
    i = 0
    depth = 0
    n = len(content)
    while i < n:
        ch = content[i]
        if ch == '(':
            depth += 1
            out.append(ch)
            i += 1
            continue
        if ch == ')':
            depth -= 1
            out.append(ch)
            i += 1
            continue
        if depth == 0 and ch == '@':
            j = i + 1
            while j < n and (content[j].isalnum() or content[j] == '_' or content[j] == '.'):
                j += 1
            if j == i + 1:
                out.append(ch)
                i += 1
                continue
            if j < n and content[j] == '(':
                pdepth = 0
                k = j
                while k < n:
                    if content[k] == '(':
                        pdepth += 1
                    elif content[k] == ')':
                        pdepth -= 1
                        if pdepth == 0:
                            k += 1
                            break
                    k += 1
                out.append(' ')
                i = k
                continue
            out.append(' ')
            i = j
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


_TYPE_KEYWORD_RE = re.compile(r'\b(class|interface|record|enum)\s+\w+')


def is_in_interface(content_before: str) -> bool:
    """True iff the most recent class/interface/record/enum declaration
    before this point is `interface`. Used to decide whether a method
    without an explicit access modifier is implicitly public (interface
    methods are) vs. package-private (class methods are)."""
    last = None
    for m in _TYPE_KEYWORD_RE.finditer(content_before):
        last = m
    return last is not None and last.group(1) == "interface"


def split_params(params: str) -> list[str]:
    """Split a Java parameter list on commas at angle-bracket depth 0.
    Nested generics like Map<String, List<Integer>> are preserved as a
    single parameter."""
    if not params.strip():
        return []
    out: list[str] = []
    depth = 0
    buf: list[str] = []
    for c in params:
        if c == "<":
            depth += 1
            buf.append(c)
        elif c == ">":
            depth -= 1
            buf.append(c)
        elif c == "," and depth == 0:
            piece = "".join(buf).strip()
            if piece:
                out.append(piece)
            buf = []
        else:
            buf.append(c)
    tail = "".join(buf).strip()
    if tail:
        out.append(tail)
    return out


def parameter_type(param: str) -> str:
    """Extract the type portion of a parameter declaration. Strips
    annotations (tokens starting with @) and the `final` modifier; the
    last whitespace-separated token is treated as the parameter name."""
    tokens = param.split()
    if len(tokens) < 2:
        return ""
    type_tokens = [t for t in tokens[:-1] if not t.startswith("@") and t != "final"]
    return " ".join(type_tokens).strip()


def parameter_annotations(param: str) -> list[str]:
    """Return the list of annotation names on a parameter (without @)."""
    return re.findall(r'@(\w+)\b', param)


def is_reference_type(type_str: str) -> bool:
    """True iff the parameter type is a reference type (and thus needs
    a nullability annotation). Strips generics and array brackets to
    extract the base type, then checks against the primitive set."""
    base = type_str.split("<", 1)[0].strip()
    base = base.replace("[", "").replace("]", "").strip()
    return base != "" and base not in PRIMITIVES


def lint_file(path: str, content: str, baseline: set[str]) -> list[tuple[str, str, str]]:
    """Return a list of (path, method-name, param-name) findings."""
    content = strip_comments(content)
    content = strip_method_level_annotations(content)
    findings: list[tuple[str, str, str]] = []
    for m in METHOD_RE.finditer(content):
        name = m.group("name")
        if name in CONTROL_FLOW_KEYWORDS:
            continue
        modifiers = m.group("head").split()
        if "private" in modifiers:
            continue
        is_public = "public" in modifiers or "protected" in modifiers
        if not is_public and not is_in_interface(content[:m.start()]):
            continue
        for p in split_params(m.group("params")):
            ptype = parameter_type(p)
            if not ptype or not is_reference_type(ptype):
                continue
            anns = parameter_annotations(p)
            if "NonNull" in anns or "Nullable" in anns:
                continue
            key = f"{path}:{name}"
            if key in baseline:
                continue
            pname = p.split()[-1] if p.strip() else "?"
            findings.append((path, name, pname))
    return findings


def parse_baseline(path: str | None) -> set[str]:
    """Read a baseline file. Returns a set of `path:method` strings; blank
    lines and # comments are skipped."""
    if path is None:
        return set()
    p = Path(path)
    if not p.is_file():
        return set()
    entries: set[str] = set()
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


def main() -> int:
    ap = argparse.ArgumentParser(
        prog="scripts/lint-contracts.py",
        description=(
            "Lint Java files for JSpecify @NonNull/@Nullable annotations on "
            "public-method reference-type parameters."
        ),
    )
    ap.add_argument(
        "--baseline",
        help="path to a baseline file (one `path:method` per line; # comments) "
             "whose entries suppress matching findings.",
    )
    ap.add_argument(
        "files",
        nargs="*",
        help="Java files to lint (paths ending in .java are processed; others skipped).",
    )
    args = ap.parse_args()

    baseline = parse_baseline(args.baseline)
    all_findings: list[tuple[str, str, str]] = []
    for f in args.files:
        if not f.endswith(".java"):
            continue
        p = Path(f)
        if not p.is_file():
            print(f"lint-contracts: WARN  {f}: not a file, skipping", file=sys.stderr)
            continue
        content = p.read_text(encoding="utf-8")
        all_findings.extend(lint_file(f, content, baseline))

    if all_findings:
        for path, method, pname in all_findings:
            print(
                f"FAIL  {path}:{method}  parameter `{pname}` missing "
                f"@NonNull/@Nullable annotation from org.jspecify.annotations",
                file=sys.stderr,
            )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
