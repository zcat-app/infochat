# /m1-tick reopen

Bring a `deferred` ticket back to `pending`. Requires that the blocker (if any) is now resolved.

Preconditions:

- The ticket exists and `status: deferred`. **Refuse `status: abandoned`** with: `M1-NNN is abandoned (decided against, terminal) — reopen does not revive it. If the decision has genuinely changed, draft a fresh ticket (optionally with a lineage pointer to M1-NNN) or re-escalate with explicit justification.` `reopen` is only for work that was paused-but-still-intended.
- If `deferred_on:` is set, that ticket is `status: done`. Refuse if not.
- For `deferred_reason: spec-amend`, additionally require that the spec amendment ticket landed AND the user re-affirms that the original ticket's `spec_refs` are still correct (the spec text changed; the ticket's references may need updating). The re-affirmation procedure runs in step 1 below.

Steps:

1. Ask the user for an optional one-line reason ("why now?"). **For `deferred_reason: spec-amend`, additionally print the operand ticket's current `spec_refs:` list and the spec-amend ticket's `spec_amend_for:` target, then prompt:**
   ```
   The spec amendment M1-AAA modified <spec-path>:§<section>.
   This ticket's current spec_refs are:
     - <ref 1>
     - <ref 2>
   Do these still resolve to the right anchors after the amendment?
     - Reply `confirm` to proceed with spec_refs unchanged.
     - Edit the ticket file's spec_refs in your editor, then reply `done`
       (same file-edit-then-confirm pattern used by /m1-tick refine —
       the skill re-reads the file from disk on `done`).
   ```
   On `confirm`: proceed. On `done`: re-read the ticket file and proceed. Any other reply: re-print the prompt and STOP.
2. Append to ticket frontmatter under a `reopens:` list:
   ```yaml
   reopens:
     - date: <YYYY-MM-DD>
       prior_deferred_reason: <copy of deferred_reason>
       prior_deferred_on: <copy of deferred_on>
       reason: <user reason>
   ```
3. Clear `deferred_on:` and `deferred_reason:`. Set `status: pending`. Update `last_updated`.
4. Regenerate `STATUS.md`.
5. Print:
   ```
   M1-NNN reopened (status: pending).
   Run `/m1-tick start M1-NNN` to begin (clarity pre-flight will run).
   ```
