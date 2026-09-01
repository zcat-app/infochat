#!/usr/bin/env python3
"""Static linter for partitioned-table test INSERTs (rule D72, M1-740).

The five monthly-partitioned tables (post, post_embedding, post_entity,
post_reference, price_snapshot) have no DEFAULT partition (Invariant 6) and
the migrations provision only a fixed bootstrap horizon of months. A test
fixture that inserts a wall-clock-stamped row into one of them breaks the
suite on the 1st of the first month the migrations do not cover. This linter
rejects that shape at author time, so the next ``Instant.now()`` in a seed
helper fails fast instead of next month.

Invocation posture: enforced by the Maven build (D72) — the ROOT pom binds
this script to the validate phase via exec-maven-plugin
(<inherited>false</inherited>, so it runs once with the repo root as cwd and
the scan roots resolve), failing the build on any violation; python3 is a
build-time dependency. A manual run from the repo root, and the ticket
reviewer's re-run against the diff's tree, remain free.

Usage:
  scripts/lint-partitioned-test-inserts.py
      # scans every module's test tree (the six DEFAULT_ROOTS below)
  scripts/lint-partitioned-test-inserts.py <dir-or-file> [<dir-or-file> ...]
      # scans the given roots instead
  scripts/lint-partitioned-test-inserts.py --self-test
      # runs the embedded violating/clean fixtures through the checker and
      # reports whether the linter catches what it claims to catch

Exit codes:
  0 — no violations (or, with --self-test, the self-test passed)
  1 — at least one violation (or, with --self-test, the self-test failed)

What it checks, per INSERT into one of the five tables found in a test
source (Java string-concatenated SQL — ``"INSERT INTO post (uid, " +
"..."`` — is reconstructed before checking; ``.sql`` fixture files under
the test trees are checked directly):

  PARTITION-KEY-OMITTED
    The INSERT column list does not name the table's partition-key column
    (post.fetched_at, post_embedding.fetched_at, post_entity.fetched_at,
    post_reference.created_at, price_snapshot.captured_at), so the column
    falls through to ``DEFAULT now()`` — wall clock.

  PARTITION-KEY-AMBIENT
    The partition-key column IS named, but its value is bound from an
    ambient-time source: ``now()`` / ``CURRENT_TIMESTAMP`` in the SQL text,
    or a ``setTimestamp``/``setObject``/``setString``/``setDate`` parameter
    binding (matched by ``?`` ordinal) whose argument expression calls
    ``Instant.now()`` / ``OffsetDateTime.now()`` / ``LocalDateTime.now()``.

  PARTITION-KEY-AMBIENT-INDIRECT
    The partition-key binding is a bare identifier that the SAME FILE
    resolves to ambient time (M1-964): a parameter of the seeding method,
    where a same-file call site passes an ambient-time expression (directly
    or via one local-variable hop) at that parameter's ordinal, or a field
    whose initializer contains an ambient-time call.

Known limits (by design — it is a heuristic static guard, not a Java
parser): the trace is SAME-FILE. Local-variable indirection is traced ONE
hop; beyond that, only the two INDIRECT shapes above are resolved, and the
parameter shape resolves by NAME — when several methods declare the same
parameter name, the flagged call site may belong to a sibling method.
Cross-file helpers, fields assigned in another file, ambient flowing
through two or more parameters, and deeper call chains are not traced;
identifiers the trace cannot resolve stay unflagged (no deny-by-default).
The ``?``-ordinal mapping assumes the VALUES list's placeholders are
bound in order by the setters that follow the prepareStatement in the
same file.
"""

from __future__ import annotations

import os
import re
import sys
import tempfile
from pathlib import Path

# table -> partition-key column
PARTITION_KEYS = {
    "post": "fetched_at",
    "post_embedding": "fetched_at",
    "post_entity": "fetched_at",
    "post_reference": "created_at",
    "price_snapshot": "captured_at",
}

DEFAULT_ROOTS = [
    "infochat-core/src/test",
    "infochat-ssrf/src/test",
    "infochat-llm-adapter/src/test",
    "infochat-messaging-adapter/src/test",
    "infochat-collector/src/test",
    "infochat-provider/src/test",
]

INSERT_RE = re.compile(
    r"INSERT\s+INTO\s+(post_embedding|post_entity|post_reference"
    r"|price_snapshot|post)\s*\(",
    re.IGNORECASE,
)

AMBIENT_SQL_RE = re.compile(r"\bnow\s*\(\s*\)|\bCURRENT_TIMESTAMP\b", re.IGNORECASE)

AMBIENT_JAVA_RE = re.compile(
    r"\bInstant\.now\s*\(|\bOffsetDateTime\.now\s*\(|\bLocalDateTime\.now\s*\("
)

SETTER_RE = re.compile(
    r"\.\s*set(?:Timestamp|Object|String|Date|Time)\s*\(\s*(\d+)\s*,",
)


class Violation:
    def __init__(self, path, line, kind, detail):
        self.path = path
        self.line = line
        self.kind = kind
        self.detail = detail

    def __str__(self):
        return f"{self.path}:{self.line}: {self.kind}: {self.detail}"


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


def iter_string_literals(text):
    """Yield (start, end, content) for each Java string literal.

    Handles backslash escapes; text blocks and char literals are not
    treated as strings (the seed SQL in these tests is plain literals).
    """
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            start = i
            i += 1
            buf = []
            while i < n:
                ch = text[i]
                if ch == "\\":
                    # keep escapes verbatim except the ones that matter for SQL
                    if i + 1 < n:
                        nxt = text[i + 1]
                        buf.append({"n": "\n", "t": "\t", '"': '"', "'": "'",
                                    "\\": "\\"}.get(nxt, "\\" + nxt))
                        i += 2
                        continue
                if ch == '"':
                    break
                buf.append(ch)
                i += 1
            yield start, i + 1, "".join(buf)
            i += 1
        elif c == "'":
            # skip char literal
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j + 1
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
        else:
            i += 1


def reconstruct_sql_segments(text):
    """Join string literals separated only by whitespace and '+'.

    Yields (start_offset, sql_text) for each maximal concatenated segment
    that contains an INSERT INTO.
    """
    lits = list(iter_string_literals(text))
    segments = []
    cur_start = None
    cur_end = None
    cur_parts = []
    for start, end, content in lits:
        if cur_start is not None:
            gap = text[cur_end:start]
            if re.fullmatch(r"[\s+]*", gap) and "+" in gap:
                cur_parts.append(content)
                cur_end = end
                continue
        if cur_start is not None:
            segments.append((cur_start, "".join(cur_parts)))
        cur_start, cur_end, cur_parts = start, end, [content]
    if cur_start is not None:
        segments.append((cur_start, "".join(cur_parts)))
    return segments


def split_top_level(s):
    """Split a parenthesised-body string on top-level commas."""
    parts = []
    depth = 0
    cur = []
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == "'":
            cur.append(ch)
            i += 1
            while i < len(s) and s[i] != "'":
                if s[i] == "\\":
                    cur.append(s[i])
                    i += 1
                if i < len(s):
                    cur.append(s[i])
                    i += 1
            if i < len(s):
                cur.append(s[i])
                i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def extract_paren_body(s, open_idx):
    """Return (body, close_idx) of the balanced-paren group opened at open_idx."""
    depth = 0
    i = open_idx
    while i < len(s):
        ch = s[i]
        if ch == "'":
            i += 1
            while i < len(s) and s[i] != "'":
                i += 2 if s[i] == "\\" else 1
            i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return s[open_idx + 1:i], i
        i += 1
    return None, None


def strip_sql_string_literals(expr):
    """Blank out '...' contents so '?' inside literals is not miscounted."""
    return re.sub(r"'(?:[^']|'')*'", "''", expr)


def local_assignment_rhs(file_text, ident):
    """RHS of a same-file local temporal assignment of ident, if any."""
    assign = re.search(
        r"\b(?:final\s+)?(?:Instant|OffsetDateTime|LocalDateTime)\s+"
        + re.escape(ident) + r"\s*=\s*([^;]+);",
        file_text)
    return assign.group(1) if assign else None


def parameter_decls(file_text, ident):
    """Yield (method_name, param_ordinal) for each method declaration whose
    parameter list declares a temporal-typed parameter named ident.

    Structural: a ``Type ident`` pair inside a paren group containing no
    ``;``/``=`` — a call-site argument list never has that shape.
    """
    for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\(", file_text):
        body, _ = extract_paren_body(file_text, m.end() - 1)
        if body is None or ";" in body or "=" in body:
            continue
        for idx, part in enumerate(split_top_level(body)):
            words = part.split()
            if words and words[0] == "final":
                words = words[1:]
            if (len(words) >= 2 and words[-1] == ident
                    and re.search(r"(?:Instant|OffsetDateTime|LocalDateTime"
                                  r"|Timestamp)$", words[-2].split("<")[0])):
                yield m.group(1), idx


def ambient_callsite(file_text, method_name, param_ordinal):
    """First ambient-time call site of method_name whose argument at
    param_ordinal is ambient (directly, or via one local hop of its leading
    identifier): (line, source) or None. Scanning the declaration site
    itself is harmless — a parameter declaration is never an ambient
    expression."""
    for m in re.finditer(r"\b" + re.escape(method_name) + r"\s*\(", file_text):
        body, _ = extract_paren_body(file_text, m.end() - 1)
        if body is None or ";" in body:
            continue
        args = split_top_level(body)
        if param_ordinal >= len(args):
            continue
        ambient = AMBIENT_JAVA_RE.search(args[param_ordinal])
        if ambient is None:
            for arg_ident in re.findall(r"^\s*([A-Za-z_]\w*)",
                                        args[param_ordinal].strip()):
                rhs = local_assignment_rhs(file_text, arg_ident)
                if rhs:
                    ambient = AMBIENT_JAVA_RE.search(rhs)
                    if ambient:
                        break
        if ambient:
            return (line_of(file_text, m.start()),
                    ambient.group(0).rstrip("(").strip())
    return None


def field_initializer_decl(file_text, ident):
    """The same-file field-declaration Match for ident, if it has an
    initializer."""
    return re.search(
        r"\b(?:private|public|protected)\s+(?:static\s+)?(?:final\s+)?"
        r"[\w$.]+\s+" + re.escape(ident) + r"\s*=\s*([^;]+);",
        file_text)


def check_segment(path, file_text, seg_start, sql, violations):
    m = INSERT_RE.search(sql)
    if not m:
        return
    table = m.group(1).lower()
    key = PARTITION_KEYS[table]

    open_idx = m.end() - 1
    cols_body, cols_close = extract_paren_body(sql, open_idx)
    if cols_body is None:
        return
    columns = [c.strip().strip('"').lower() for c in split_top_level(cols_body)]
    line = line_of(file_text, seg_start)

    if key not in columns:
        violations.append(Violation(
            path, line, "PARTITION-KEY-OMITTED",
            f"INSERT INTO {table} does not name partition-key column "
            f"'{key}' (falls through to DEFAULT now())"))
        return

    # Locate the VALUES list aligned with the column list.
    values_m = re.compile(r"\bVALUES\s*\(", re.IGNORECASE).search(sql, cols_close)
    if not values_m:
        return
    vals_body, _ = extract_paren_body(sql, values_m.end() - 1)
    if vals_body is None:
        return
    values = split_top_level(vals_body)
    key_idx = columns.index(key)
    if key_idx >= len(values):
        return
    key_value = values[key_idx]

    if AMBIENT_SQL_RE.search(key_value):
        violations.append(Violation(
            path, line, "PARTITION-KEY-AMBIENT",
            f"INSERT INTO {table} binds '{key}' from an ambient SQL source: "
            f"{key_value.strip()!r}"))
        return

    if "?" not in key_value:
        return  # literal / expression without placeholder — nothing to trace

    # Ordinal of this placeholder among all placeholders in the VALUES list,
    # ignoring '?' inside SQL string literals.
    ordinal = 0
    for v in values[: key_idx + 1]:
        ordinal += strip_sql_string_literals(v).count("?")

    # Find the setter call binding that ordinal and check its argument.
    tail = file_text[seg_start:]
    for sm in SETTER_RE.finditer(tail):
        if int(sm.group(1)) != ordinal:
            continue
        arg_start = sm.end()
        arg_end = tail.find(";", arg_start)
        arg_text = tail[arg_start: arg_end if arg_end != -1 else arg_start + 400]
        idents = re.findall(r"(?:Timestamp\.from\(\s*|^\s*)([A-Za-z_]\w*)",
                            arg_text.strip())
        ambient = AMBIENT_JAVA_RE.search(arg_text)
        if ambient is None:
            # One hop of indirection: Timestamp.from(t) / setObject(i, t)
            # where t is a local assigned from an ambient-time call.
            for ident in idents:
                rhs = local_assignment_rhs(file_text, ident)
                if rhs:
                    ambient = AMBIENT_JAVA_RE.search(rhs)
                    if ambient:
                        break
        if ambient is None:
            # Same-file indirection beyond the local hop (M1-964): the
            # identifier is a parameter of the seeding method fed ambient
            # time at a call site, or a field with an ambient initializer.
            for ident in idents:
                hit = None
                for method_name, param_ordinal in parameter_decls(
                        file_text, ident):
                    callsite = ambient_callsite(file_text, method_name,
                                                param_ordinal)
                    if callsite:
                        callsite_line, src = callsite
                        hit = Violation(
                            path, line, "PARTITION-KEY-AMBIENT-INDIRECT",
                            f"INSERT INTO {table} binds '{key}' (parameter "
                            f"{ordinal}) via {method_name}(..) parameter "
                            f"'{ident}' from {src}() at line {callsite_line}")
                        break
                if hit is None:
                    decl = field_initializer_decl(file_text, ident)
                    if decl:
                        a = AMBIENT_JAVA_RE.search(decl.group(1))
                        if a:
                            hit = Violation(
                                path, line, "PARTITION-KEY-AMBIENT-INDIRECT",
                                f"INSERT INTO {table} binds '{key}' "
                                f"(parameter {ordinal}) from field "
                                f"'{ident}' initialized with "
                                f"{a.group(0).rstrip('(').strip()}() "
                                f"at line {line_of(file_text, decl.start())}")
                if hit is not None:
                    violations.append(hit)
                    return
        if ambient:
            src = ambient.group(0).rstrip("(").strip()
            violations.append(Violation(
                path, line, "PARTITION-KEY-AMBIENT",
                f"INSERT INTO {table} binds '{key}' (parameter {ordinal}) "
                f"from {src}()"))
        return


def strip_sql_comments(text):
    """Remove -- line comments, ignoring '--' inside single-quoted strings."""
    out = []
    for line in text.splitlines(keepends=True):
        in_str = False
        cut = len(line)
        i = 0
        while i < len(line):
            ch = line[i]
            if ch == "'":
                in_str = not in_str
            elif not in_str and line.startswith("--", i):
                cut = i
                break
            i += 1
        out.append(line[:cut] + ("\n" if line.endswith("\n") and cut < len(line) else ""))
    return "".join(out)


def check_sql_text(path, text, violations):
    """Check INSERT statements in a .sql fixture file directly."""
    cleaned = strip_sql_comments(text)
    for m in INSERT_RE.finditer(cleaned):
        table = m.group(1).lower()
        key = PARTITION_KEYS[table]
        line = line_of(cleaned, m.start())
        cols_body, cols_close = extract_paren_body(cleaned, m.end() - 1)
        if cols_body is None:
            continue
        columns = [c.strip().strip('"').lower() for c in split_top_level(cols_body)]
        if key not in columns:
            violations.append(Violation(
                path, line, "PARTITION-KEY-OMITTED",
                f"INSERT INTO {table} does not name partition-key column "
                f"'{key}' (falls through to DEFAULT now())"))
            continue
        values_m = re.compile(r"\bVALUES\s*", re.IGNORECASE).search(cleaned, cols_close)
        if not values_m:
            continue
        # Walk every top-level row group: VALUES (...), (...), ...
        pos = values_m.end()
        key_idx = columns.index(key)
        while True:
            while pos < len(cleaned) and cleaned[pos] in " \t\r\n,":
                pos += 1
            if pos >= len(cleaned) or cleaned[pos] != "(":
                break
            row_body, row_close = extract_paren_body(cleaned, pos)
            if row_body is None:
                break
            values = split_top_level(row_body)
            if key_idx < len(values) and AMBIENT_SQL_RE.search(values[key_idx]):
                violations.append(Violation(
                    path, line, "PARTITION-KEY-AMBIENT",
                    f"INSERT INTO {table} binds '{key}' from an ambient SQL "
                    f"source: {values[key_idx].strip()!r}"))
                break
            pos = row_close + 1


def check_file(path, violations):
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN {path}: unreadable ({e})", file=sys.stderr)
        return
    if str(path).endswith(".sql"):
        if INSERT_RE.search(re.sub(r"\s", " ", text)):
            check_sql_text(path, text, violations)
        return
    # Coarse gate: the table name and its '(' may sit in separate
    # concatenated literals, so the full INSERT_RE is applied per
    # reconstructed segment below, not here.
    if not re.search(r"INSERT\s+INTO", text, re.IGNORECASE):
        return
    for seg_start, sql in reconstruct_sql_segments(text):
        if INSERT_RE.search(sql):
            check_segment(path, text, seg_start, sql, violations)


def scan_roots(roots):
    violations = []
    for root in roots:
        p = Path(root)
        if p.is_file() and p.suffix in (".java", ".sql"):
            check_file(p, violations)
        elif p.is_dir():
            for src in sorted(p.rglob("*.java")) + sorted(p.rglob("*.sql")):
                check_file(src, violations)
        else:
            print(f"WARN {root}: not a .java/.sql file or directory", file=sys.stderr)
    return violations


SELF_TEST_VIOLATING_OMIT = '''
class FixtureOmit {
    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (uid, source_id, title, status, "
                        + "upstream_identifier) "
                        + "VALUES (?, ?, ?, 'READY', ?)")) {
            ps.executeUpdate();
        }
    }
}
'''

SELF_TEST_VIOLATING_AMBIENT = '''
class FixtureAmbient {
    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                        + "captured_at, source_url) "
                        + "VALUES (?, ?, ?, ?, ?, 'src')")) {
            ps.setString(1, "zcash");
            ps.setString(2, "coingecko");
            ps.setString(3, "usd");
            ps.setBigDecimal(4, java.math.BigDecimal.ONE);
            ps.setTimestamp(5, java.sql.Timestamp.from(
                    java.time.Instant.now().minusSeconds(30)));
            ps.executeUpdate();
        }
    }
}
'''

SELF_TEST_VIOLATING_INDIRECT = '''
class FixtureIndirect {
    void seed(java.sql.Connection conn) throws Exception {
        java.time.Instant capturedAt = java.time.Instant.now().minusSeconds(30);
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                        + "captured_at, source_url) "
                        + "VALUES (?, ?, ?, ?, ?, 'src')")) {
            ps.setString(1, "zcash");
            ps.setString(2, "coingecko");
            ps.setString(3, "usd");
            ps.setBigDecimal(4, java.math.BigDecimal.ONE);
            ps.setTimestamp(5, java.sql.Timestamp.from(capturedAt));
            ps.executeUpdate();
        }
    }
}
'''

SELF_TEST_SQL_VIOLATING = '''
-- fixture comment mentioning INSERT INTO post should not count
INSERT INTO post (id, uid, source_id, title, published_at, status, tags)
VALUES ('00000000-0000-4000-8000-000000000001', 'u1',
        '00000000-0000-4000-8000-000000000010', 't',
        now() - interval '1 hour', 'READY', ARRAY['x']);
'''

SELF_TEST_SQL_CLEAN = '''
INSERT INTO post (id, uid, source_id, title, published_at, fetched_at, status,
                  tags)
VALUES
    ('00000000-0000-4000-8000-000000000001', 'u1',
     '00000000-0000-4000-8000-000000000010', 't',
     now() - interval '1 hour', '2026-05-22T12:00:00Z', 'READY', ARRAY['x']),
    ('00000000-0000-4000-8000-000000000002', 'u2',
     '00000000-0000-4000-8000-000000000010', 't2',
     now() - interval '2 hours', '2026-05-22T12:00:00Z', 'RAW', ARRAY['y']);
'''

SELF_TEST_CLEAN = '''
class FixtureClean {
    private static final java.time.Instant FETCHED_AT =
            java.time.Instant.parse("2026-05-22T12:00:00Z");

    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, status, "
                        + "upstream_identifier) "
                        + "VALUES (?, ?, ?, ?, 'READY', ?)")) {
            ps.setString(1, "uid");
            ps.setObject(2, java.util.UUID.randomUUID());
            ps.setString(3, "title");
            ps.setTimestamp(4, java.sql.Timestamp.from(FETCHED_AT));
            ps.setString(5, "uid");
            ps.executeUpdate();
        }
    }
}
'''

# The observed 2026-09-01 bomb shape: the key setter binds a seeding
# method's parameter, and a same-file call site passes Instant.now().
SELF_TEST_VIOLATING_HELPER_PARAM = '''
class FixtureHelperParam {
    void drive(java.sql.Connection conn) throws Exception {
        seedNeedsReviewPost("slug", java.time.Instant.now().minusSeconds(60));
    }

    private void seedNeedsReviewPost(String slug, java.time.Instant fetchedAt)
            throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, status, "
                        + "upstream_identifier) "
                        + "VALUES (?, ?, ?, ?, 'NEEDS_REVIEW', ?)")) {
            ps.setString(1, "needs-review-" + slug);
            ps.setObject(2, java.util.UUID.randomUUID());
            ps.setString(3, "NR Post " + slug);
            ps.setTimestamp(4, java.sql.Timestamp.from(fetchedAt));
            ps.setString(5, "upstream-nr-" + slug);
            ps.executeUpdate();
        }
    }
}
'''

# The UnresolvedRepostEdgeUniqueIT shape: a static field initialized from
# ambient time, bound to the partition key.
SELF_TEST_VIOLATING_STATIC_FIELD = '''
class FixtureStaticField {
    private static final java.sql.Timestamp CREATED_AT =
            java.sql.Timestamp.from(java.time.Instant.now().truncatedTo(
                    java.time.temporal.ChronoUnit.SECONDS));

    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post_reference (from_post, to_upstream_identifier, "
                        + "created_at) "
                        + "VALUES (?, ?, ?)")) {
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setString(2, "upstream");
            ps.setTimestamp(3, CREATED_AT);
            ps.executeUpdate();
        }
    }
}
'''

# The AdminReviewTtlJobTest shape: a helper parameter whose call sites pass
# fixed-literal-derived instants — must stay unflagged.
SELF_TEST_CLEAN_HELPER_PARAM = '''
class FixtureHelperParamClean {
    void drive(java.sql.Connection conn) throws Exception {
        seedPost("slug", java.time.Instant.parse("2026-05-18T12:00:00Z"));
    }

    private void seedPost(String slug, java.time.Instant fetchedAt)
            throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, status, "
                        + "upstream_identifier) "
                        + "VALUES (?, ?, ?, ?, 'READY', ?)")) {
            ps.setString(1, "uid-" + slug);
            ps.setObject(2, java.util.UUID.randomUUID());
            ps.setString(3, "title " + slug);
            ps.setTimestamp(4, java.sql.Timestamp.from(fetchedAt));
            ps.setString(5, "upstream-" + slug);
            ps.executeUpdate();
        }
    }
}
'''

# The provider ready_at shape: ambient time bound to a NON-key column —
# D72-legal, must stay unflagged.
SELF_TEST_CLEAN_NON_KEY_AMBIENT = '''
class FixtureNonKeyAmbient {
    private static final java.time.Instant FETCHED_AT =
            java.time.Instant.parse("2026-05-22T12:00:00Z");

    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, ready_at, "
                        + "status) "
                        + "VALUES (?, ?, ?, ?, ?, 'READY')")) {
            ps.setString(1, "uid");
            ps.setObject(2, java.util.UUID.randomUUID());
            ps.setString(3, "title");
            ps.setTimestamp(4, java.sql.Timestamp.from(FETCHED_AT));
            ps.setTimestamp(5, java.sql.Timestamp.from(java.time.Instant.now()));
            ps.executeUpdate();
        }
    }
}
'''

# The second 2026-09-01 bomb leg: the INSERT's table name and column list
# sit in SEPARATE concatenated literals, so only check_file's entry gate
# (not the per-segment check alone) can admit it.
SELF_TEST_VIOLATING_SPLIT_LITERAL = '''
class FixtureSplitLiteral {
    private static final java.sql.Timestamp CREATED_AT =
            java.sql.Timestamp.from(java.time.Instant.now());

    void seed(java.sql.Connection conn) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post_reference "
                        + "(from_post, to_post, to_upstream_identifier, link_type, score, created_at) "
                        + "VALUES (?, NULL, ?, 'repost', 1.0, ?)")) {
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setString(2, "upstream");
            ps.setTimestamp(3, CREATED_AT);
            ps.executeUpdate();
        }
    }
}
'''


def self_test():
    cases = [
        ("violating (partition key omitted)", SELF_TEST_VIOLATING_OMIT, 1),
        ("violating (Instant.now() binding)", SELF_TEST_VIOLATING_AMBIENT, 1),
        ("violating (now()-valued local variable)", SELF_TEST_VIOLATING_INDIRECT, 1),
        ("violating (helper parameter fed Instant.now() at a call site)",
         SELF_TEST_VIOLATING_HELPER_PARAM, 1, "PARTITION-KEY-AMBIENT-INDIRECT"),
        ("violating (static field initialized from Instant.now())",
         SELF_TEST_VIOLATING_STATIC_FIELD, 1, "PARTITION-KEY-AMBIENT-INDIRECT"),
        ("clean (fixed-instant binding)", SELF_TEST_CLEAN, 0),
        ("clean (helper parameter fed a fixed instant)",
         SELF_TEST_CLEAN_HELPER_PARAM, 0),
        ("clean (ambient bound to a non-key column)",
         SELF_TEST_CLEAN_NON_KEY_AMBIENT, 0),
    ]
    ok = True
    for case in cases:
        name, source, expected = case[0], case[1], case[2]
        expected_kind = case[3] if len(case) > 3 else None
        violations = []
        for seg_start, sql in reconstruct_sql_segments(source):
            if INSERT_RE.search(sql):
                check_segment("<self-test>", source, seg_start, sql, violations)
        good = len(violations) == expected and (
            expected_kind is None
            or expected_kind in [v.kind for v in violations])
        status = "OK" if good else "FAIL"
        if not good:
            ok = False
        print(f"{status}  {name}: expected {expected} violation(s), "
              f"got {len(violations)}")
        for v in violations:
            print(f"       {v}")
    # End-to-end through check_file: this case fails if the per-file entry
    # gate ever narrows back below the segment check — the split-literal
    # bomb shape would re-open silently (round-1 rework, M1-964).
    with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False) as f:
        f.write(SELF_TEST_VIOLATING_SPLIT_LITERAL)
        fixture_path = f.name
    violations = []
    try:
        check_file(Path(fixture_path), violations)
    finally:
        os.unlink(fixture_path)
    good = (len(violations) == 1
            and violations[0].kind == "PARTITION-KEY-AMBIENT-INDIRECT")
    status = "OK" if good else "FAIL"
    if not good:
        ok = False
    print(f"{status}  violating (split-literal INSERT, via check_file): "
          f"expected 1 violation(s), got {len(violations)}")
    for v in violations:
        print(f"       {v}")
    sql_cases = [
        ("violating .sql (partition key omitted)", SELF_TEST_SQL_VIOLATING, 1),
        ("clean .sql (fixed literal, multi-row)", SELF_TEST_SQL_CLEAN, 0),
    ]
    for name, source, expected in sql_cases:
        violations = []
        check_sql_text("<self-test-sql>", source, violations)
        status = "OK" if len(violations) == expected else "FAIL"
        if len(violations) != expected:
            ok = False
        print(f"{status}  {name}: expected {expected} violation(s), "
              f"got {len(violations)}")
        for v in violations:
            print(f"       {v}")
    print("self-test " + ("PASSED" if ok else "FAILED"))
    return ok


def main(argv):
    args = [a for a in argv[1:]]
    if "--self-test" in args:
        return 0 if self_test() else 1
    roots = args if args else DEFAULT_ROOTS
    violations = scan_roots(roots)
    for v in violations:
        print(v)
    if violations:
        print(f"FAIL  {len(violations)} partitioned-insert violation(s): bind "
              f"every partition-key column to a FIXED instant constant, never "
              f"to Instant.now() / DEFAULT now() / CURRENT_TIMESTAMP (M1-740)")
        return 1
    print(f"PASS  no ambient-time partitioned-table INSERTs in "
          f"{', '.join(roots)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
