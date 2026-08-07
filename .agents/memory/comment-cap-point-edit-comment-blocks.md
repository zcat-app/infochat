---
name: comment-cap-point-edit-comment-blocks
description: "tick-comment-cap counts maximal runs of ADDED comment lines and removed lines do NOT break a run — so rewrapping any comment/javadoc block longer than 3 lines trips the cap even when the content barely changes. Point-edit comment blocks, leaving original lines as context."
metadata: 
  type: project
---

`scripts/tick-comment-cap.py` (the /tick diff self-check; the reviewer
FAILs what you leave) flags runs of >3 consecutive `+` lines whose content
is a comment. Removed (`-`) lines in the same hunk do not reset the
counter — only unchanged context lines and code lines do. Consequence:
rewrapping a 4+ line comment or javadoc paragraph (e.g. to widen one
clause) turns the whole block into added lines and trips the cap, however
small the semantic edit.

**How to apply:** when editing an existing comment block, change only the
lines that must change and keep every other original line byte-identical,
so they survive as context and break the added-run counter. If the new
text genuinely needs more than 3 fresh lines, split the insertion around
an untouched line. Markdown files are exempt (the cap targets code
comments). Cost of not knowing: one extra restructure cycle on M1-788's
class-javadoc edit (2026-08-07), caught pre-review only because the
self-check ran before the verify.
