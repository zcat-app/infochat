---
name: spec-edits-need-approval-no-journal
description: Any edit under docs/spec/ is presented to the user with the exact text and plain-English reasoning and waits for explicit approval; spec text carries no dates, ticket IDs, or report citations — it states rules, not history.
metadata:
  type: feedback
---

Two non-negotiable user rules about `docs/spec/**` (stated 2026-08-07,
mid-M1-789):

1. **Approval before the edit lands.** The spec is the product of long
   deliberate shaping; no agent edits it silently. Before any spec change:
   show the exact proposed text, explain in plain English what commitment
   it adds/removes/changes and why the ticket needs it, and wait for an
   explicit yes. This holds even when the ticket's `acceptance:` already
   lists the amendment — the ticket authorizes the work, the user approves
   the wording. Same family as [[ask-before-creating-tickets]]: the
   workflow entity's owner decides.

2. **The spec is a constitution, not a journal.** New spec text states the
   rule and nothing else: no dates, no "redteam YYYY-MM-DD", no ticket
   IDs, no links to redteam/live-test/handoff reports. The motivation and
   history of a rule belong in the decision register, the ticket, or the
   analysis doc — the spec states what the system promises. Older spec
   sections carry such citations; treat them as legacy, not as license,
   and don't clean them up inside an unrelated ticket (that sweep is its
   own decision).

Practical consequence for amendments that ride a ticket diff: phrase the
amendment as a pure statement of behavior ("X is stripped when...", "the
discriminator is exact equality...") and let the ticket/analysis carry
the "observed in v1.1.0" story.
