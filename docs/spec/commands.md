# Commands and chat

This file describes the command surface the bot exposes, the rules every
command obeys, and the permission model that gates them. Concrete usage
strings, exact wording of replies, output formats, and pagination sizes
live in `docs/design/03-commands.md`.

## Surface conventions

- **Slash-prefix only.** Anything starting with `/` is a command. Anything
  else is chat-mode input routed to the chat agent. There is no "command
  mode" toggle.
- **Group rule.** In a group the bot only sees messages that `@mention` it.
  The mention is stripped before parsing.
- **Single time flag.** Every command that takes a time window uses the same
  `-w <duration>` flag with the same accepted forms (decision D12). Concrete
  forms are documented in design notes.
- **Tag arguments are exact-match** against the controlled vocabulary,
  case-insensitive. Unknown tags produce friendly errors with fuzzy
  suggestions.
- **Confirmation for destructive commands.** Destructive actions
  (e.g. `/clear`, `/remove-source`, `/ban`) require a follow-up
  `<command> confirm` within a short window. The confirmation is scoped to
  (user, scope) and any other input cancels it with an explicit
  acknowledgement.
- **Output formatting** follows decision D30 (plain text, backticks for
  code, bare URLs). Adapters with richer rendering get richer output via the
  capability flag.
- **Input length caps** are applied at the parser before any LLM or DB work.
  Specific caps live in design notes.

## Command catalogue

The catalogue below is the *spec-level commitment* — these commands exist in
v1, with the listed permissions. Argument shapes, defaults, exact reply
text, and output structure are in `docs/design/03-commands.md`.

### Discovery

- `/help` — context-aware list of commands available to the caller.
- `/status` — runtime status (active profile, uptime, scope-specific
  counts; admin sees more).
- `/get-tags` — controlled vocabulary, marking the scope's followed tags.
- `/get-sources` — alias of `/list-sources` without `--all`.

### Content

- `/summary [tag] [-w …]` — on-the-fly summary of READY posts in the
  window. Cluster grouping by `post_reference`. LLM writes prose per
  cluster.
- `/save <uid> [-t personal-tags]` — bookmark a post into the calling
  user's library (per-user, even in groups). Personal tags are free-form
  and never join the controlled vocabulary.
- `/saved [tag] [-w …] [--page N]` — list saved posts with optional
  filters and pagination.
- `/unsave <uid>` — remove from library (no confirmation).

### Source management

- `/add-source --type … --url … --tags …` — DM: any non-banned user adds to
  their own scope. Group: group admin only. Tags are mandatory (decision
  D14).
- `/list-sources [--all] [--page N]` — sources subscribed by the calling
  scope; `--all` is bot-admin only.
- `/unfollow-source <id>` — per-scope unsubscribe. Different from
  `/remove-source`: does not touch the global source row.
- `/remove-source <id>` — bot-admin only, requires confirm. Soft-delete
  only.

### Per-scope tag preferences

- `/follow-tag <tag>` / `/unfollow-tag <tag>` — controls which tags appear
  in the scope's periodic digest. Default for a fresh scope is "all tags
  from subscribed sources" (decision D15).

### Conversation control

- `/clear` — wipes the calling (user, scope) active context window only.
  Chat memory is untouched (decision D25). Requires confirm.
- `/compress` — forces an immediate `chat_memory` checkpoint for the
  calling (user, scope). Auto-triggered near the context-window ceiling
  (decision D24).
- `/lang <code>` — sets per-scope output language. v1 ships English and
  Czech. DM: own scope. Group: group admin only.

### Admin (bot admin)

- `/promote <contact>` / `/demote <contact>` — group admin
  promote/demote, used inside a group.
- `/grant-admin <contact>` / `/revoke-admin <contact>` — bot-wide. Last-
  admin protection applies.
- `/ban <contact> [--reason …]` / `/unban <contact>` — bot-wide ban. Cannot
  ban self or last admin.
- `/quarantine list [-w …] [--page N]` — pending review queue.
- `/quarantine approve <id>` / `/quarantine reject <id>` — review action.
  Approve restores the redacted span; reject leaves the placeholder.
- `/audit [-w …] [--actor …] [--action …] [--page N]` — read `audit_log`
  with filters.

## Permission model

The full per-command matrix (DM / group member / group admin / bot admin)
lives in `docs/design/03-commands.md`. The spec-level commitment:

- Permission is evaluated by deterministic Java *before* any LLM call.
- Banned users get one fixed reply and never reach the parser, the chat
  agent, or any DB query past the ban check (decision D11).
- Group destructive operations require group admin (or bot admin acting
  inside the group). Bot-wide destructive operations require bot admin.
- Any new command added to the system goes through the same permission
  matrix and the same audit-before-effect rule.

## Chat mode

Anything not starting with `/` is routed to the chat agent. The agent has a
strict, fixed tool surface (read-only, scope-filtered) — see `security.md`
and decision D21. The agent is never allowed to mutate authorization state
or perform admin actions; admin commands are dispatched by the
deterministic command path only.

## Onboarding

A user's first interaction triggers auto-registration. The welcome message
branches on three modes (DM-fresh, DM-returning, group-first-mention) so the
user is steered toward an action that will not be empty (decision D23).
Exact wording in design notes.

## Periodic group summaries

Groups receive a morning and evening digest at per-group local times
(decision D16). Generation is staggered, results are cached briefly, and a
degraded fallback (headlines + sources, no LLM prose) kicks in when the
worker pool can't keep up (decision D17).

## What lives in design notes

- Exact argument grammar and accepted `-w` forms
- Per-command exact reply wording
- Pagination page size
- Confirmation timeout duration
- Cluster cap and per-profile cluster-cap values
- Welcome message text
- Permission matrix rows
- Friendly-error suggestion ranking and cap
