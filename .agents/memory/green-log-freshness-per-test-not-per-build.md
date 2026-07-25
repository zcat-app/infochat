---
name: green-log-freshness-per-test-not-per-build
description: "A green mvn verify log's BUILD SUCCESS time is NOT when a given test read its input — for tests that parse repo files from disk, compare your edit's mtime against when THAT test ran in the log"
metadata:
  type: feedback
---

The `/m1-tick commit` test-freshness gate asks "did any commit candidate
change since the log?" The naive check compares file mtimes against the
log's **end** timestamp. That is wrong for any test that reads a repo file
from disk at test time.

A full verify takes ~11 min; the provider unit-test phase runs in roughly
its first third. An edit made at 13:30:31 to a file whose parity test ran
at ~13:24 is **newer than that test** while still being *older* than the
13:30:39 `BUILD SUCCESS` — so a mtime-vs-build-end comparison reports
"nothing changed since the log" and the gate silently passes on a suite
that never saw the edit.

**Why:** the log records one end time for the whole reactor, but each test
class has its own read instant. Freshness is per-test, not per-build.

**How to apply:** when the diff touches a file under `docs/spec/` (or any
repo file a test parses), grep the log for the reading test's own
`Tests run: ... -- in <TestClass>` line and locate it in the run, rather
than trusting the build-end timestamp. In this repo the disk-reading tests
are found with:

    grep -rln 'Files.read\|Path.of("docs\|Paths.get("docs\|readString' \
      infochat-provider/src/test/java | xargs grep -ln "<the-file>"

For `docs/spec/security.md` that is `ChatToolAllowlistSpecParityTest`,
`InboundReflectionGuardTest`, `LlmOutputSanitizerTest` — so a spec edit is
NOT inert with respect to the suite, even though `.md` is not a testable
extension under the inert-diff rule. State the gap to the user and let
them make the skip/re-run call; do not classify it as inert.

Surfaced during M1-689 (2026-07-25). Related: [[doc-only-edits-skip-verify]]
(which holds for docs NO test reads), [[clean-verify-monitoring]].
