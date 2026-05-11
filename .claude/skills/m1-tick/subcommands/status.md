# /m1-tick status

Regenerate `STATUS.md` and print a one-screen summary. The regeneration work is delegated to a fresh-context subagent so the main session never reads N ticket bodies.

## No-args regenerate path

1. Snapshot `git status --porcelain` immediately before spawning the subagent. Capture the output verbatim — this is the Write-scope guard pre-image.
2. Read the prompt template at `docs/process/status-regen-prompt.md`. Substitute `{{TICKETS_GLOB}}` with the literal `docs/plan/m1/tickets/M1-*.md` and `{{STATUS_FILE_PATH}}` with the literal `docs/plan/m1/STATUS.md`. No other substitutions.
3. **PROMPT-SIZE-ALARM** (status-regenerator, threshold 10000 bytes): compute the UTF-8 byte length of the substituted prompt string built in step 2. If that length exceeds 10000, print one chat line: `⚠ PROMPT-SIZE-ALARM status-regenerator: substituted prompt is <N> bytes (threshold 10000). This may indicate a regression — a placeholder may have been re-inlined that should reference a file by path. Proceeding anyway.` The alarm is warn-only and does not block the spawn; proceed regardless. Spawn `Agent(subagent_type: "status-regenerator", prompt: <substituted>, description: "Regenerate STATUS.md")`. Foreground. The agent Globs the tickets, Reads each, renders the template, Writes to `docs/plan/m1/STATUS.md`, and returns the four-line short reply.
4. Snapshot `git status --porcelain` immediately after the spawn returns. Diff against the pre-image. The only new working-tree change permitted is `docs/plan/m1/STATUS.md`. If any other new change appears outside that path, refuse to proceed and surface a clear error ("status-regenerator wrote to <path> outside its contract") — the Write-scope guard catches a misbehaving agent before any unintended change can be staged.
5. Parse the four-line short reply (`STATUS REGENERATED:` / `Counts:` / `Runnable:` / `In flight:`). Print the same four lines to the user.

## Optional filter flags

These flags print filtered lists to chat without writing STATUS.md. They keep their main-session implementation because their cost is bounded by N tickets per invocation and they don't write a file.

- `/m1-tick status --deferred` — read each `docs/plan/m1/tickets/M1-*.md`, select those with `status: deferred`, sort by `deferred_reason` then `id`, and print as a list.
- `/m1-tick status --escalated` — read each `docs/plan/m1/tickets/M1-*.md`, select those with `status: escalated`, and print each with its most recent reviewer-verdict excerpt from the `reviews:` array.

Neither flag spawns the status-regenerator and neither modifies STATUS.md on disk.
