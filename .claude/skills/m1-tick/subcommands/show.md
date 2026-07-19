# /m1-tick show

Read-only inspection of a ticket. No state changes.

1. Read the ticket file.
2. Print:
   - Frontmatter, formatted.
   - The ticket body, verbatim.
   - A short audit trail summary derived from `reviews:`, `escalation_reason:` (the current open escalation, if any), `overrides:`, `aborted_attempts:`, `redteam_findings:` — one line each. Refine/escalation *history* is not in frontmatter; for it, point the reader at `git log --grep "^M1-NNN: "` (the refine commits carry the reasons).
3. Stop.
