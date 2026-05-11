# /m1-tick show

Read-only inspection of a ticket. No state changes.

1. Read the ticket file.
2. Print:
   - Frontmatter, formatted.
   - The ticket body, verbatim.
   - A short audit trail summary derived from `reviews:`, `escalations:`, `revisions:`, `overrides:`, `aborted_attempts:`, `redteam_findings:` — one line each.
3. Stop.
