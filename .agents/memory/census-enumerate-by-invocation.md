---
name: census-enumerate-by-invocation
description: "A ticket census must enumerate by INVOCATION (what drives the code path), never by output token — a label-based grep silently misses bare-content fields, so each plan pass finds a different subset."
metadata: 
  type: feedback
---

When a ticket changes a command's **output shape**, enumerate the affected
tests by what **invokes** the path, not by what the old output *looks like*.

**Why:** M1-687 (`/summary` categorized-by-default) twice outline-failed on
this. Its census grepped field LABELS —
`topic_id=\|covered by:\|classification: \|finalizedBodies` — so it could never
return the two `ClusterBlockRenderer` fields that are **bare content**: the
headline and the uid. Round 1 found 5 test files, a start-time correction added
1, round 2 found 2 more. Each pass sampled a different subset because the
*predicate* was wrong, not the list. The invocation grep
`grep -rln '"/summary\|"/retry' --include=*.java <module>/src/test/java`
returned 35 files, 28 of them outside `files_scope`.

This is the same failure class as M1-672's twice-outline-failed plan, whose
lesson was "fix the RULE, not the file-by-file list" — confirmed twice now, so
treat it as the default rather than a lesson learned once.

**How to apply:**

- Census predicate = the invocation (`"/command"`, the SPI method, the
  constructor), never the rendered tokens. Output tokens are what you are
  *changing*; they cannot identify what depends on them.
- Over-broad is correct here: every returned path needs a row, and
  `out-of-scope: <reason>` is a valid disposition. "Not listed" is not.
- Re-run the census live at `/m1-tick start` (step 1b) — it catches the ticket
  author's miss for free, and it caught this one.
- If two plan passes each surface a *different* file set, stop refining and
  decompose. The growth is the signal; a third refine will just find a third
  subset.

Related: [[relocated-controls-dont-travel]] (the behavior-census counterpart),
[[verify-subagent-quotes-before-pinning]],
[[investigation-ticket-flow-too-heavy]].
