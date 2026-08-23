> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in
> [docs/spec/commands.md](../spec/commands.md) and adjacent spec files.

---

# 03 — Slash commands

All bot commands start with `/`. Anything not starting with `/` is treated as
chat-mode input and routed to the ChatAgent. There is no "command mode" toggle.
([../spec/commands.md](../spec/commands.md) §Surface conventions.)

Commands work the same in DM and group chat unless explicitly noted. In a
group, the bot only sees a message if the user `@mentions` the bot's display
name; the `@mention` is stripped before parsing.

---

## 3.1 Conventions

### Empty / whitespace-only messages

Messages whose body is empty or whitespace-only after the
[normalization pass](../spec/security.md) (`security.md` §Authorization model
step 1.7 — bidi-strip + zero-width-strip + trim) are dropped before any further
processing. Neither the slash-prefix check, the command parser, nor the
chat-mode router runs on them. This is the same normalization pass that strips
control characters and zero-width characters, not a separate gate. A message
consisting of just a leading `/` followed by whitespace is treated as
whitespace-only and dropped — it never resolves to "unknown command" and never
costs a parse.

Leading whitespace is **trimmed** before the slash-prefix check so `  /help`
parses as `/help`. The trim runs in the same pass as the bidi/zero-width
strip — there is no separate "early trim then late check" sequence.

### Time window flag (`-w <duration>`)

A single `-w <duration>` flag is used everywhere a time window is needed
(decision D12). Accepted values:

| Form | Meaning |
|---|---|
| `1h`, `12h`, `48h` | hours (1–168) |
| `1d`, `7d`, `30d` | days (1–30) |
| `1w`, `4w` | weeks (1–4) |

The suffix `m` is intentionally not accepted (ambiguous between minutes and
months: minutes are too small to be useful, and the longest meaningful window
is `30d`, which equals the post TTL). Use `30d` if you want a 30-day window.

Default `-w 24h` for `/summary` and similar commands. Each command's documented
default lives below.

### Tag arguments

Tag arguments are exact-match against the controlled vocabulary, after the
fixed normalization pipeline ([../spec/commands.md](../spec/commands.md)
§Surface conventions — Tag arguments) applied at every read and write site:

1. Trim leading and trailing whitespace.
2. Apply Unicode **NFC** normalization (NFC, not NFKC — NFKC would silently
   merge visually-distinct tags like `①` and `1`).
3. Lower-case via `String.toLowerCase(Locale.ROOT)` (locale-independent so
   `İ` / `I` do not split between locales).
4. Reject any post-normalization value not matching `[a-z0-9][a-z0-9-]{0,47}`
   (ASCII alphanumerics + internal hyphens, leading char must be alphanumeric,
   1–48 characters).

Implemented as `infochat-core` `TagNormalizer.normalize(String)`; called from
the bootstrap loader, `/add-source --tags`, the tagger output validator, and
every command parser that reads a tag argument. Whitespace, non-ASCII letters,
and control characters are rejected at the parser with a friendly error.
Output uses the canonical (post-normalization) casing. Unknown tags produce
friendly errors with fuzzy suggestions over the controlled vocabulary.

**The `socials` tag** is part of the controlled vocabulary, seeded by
`bootstrap-sources.json`. The tagger auto-assigns `socials` to every post
coming from a source whose `category = 'social'` (Reddit, Bluesky, Nitter,
Nostr, Odysee, YouTube). It otherwise behaves like any other tag.

### Confirmation for destructive commands

Destructive commands (`/clear`, `/remove-source`, `/ban`, `/forget`,
`/unfollow-tag --all`, `/source-enable` against a soft-deleted row,
`/invite create --open`, `/invite revoke`, `/quarantine reject` for
forensic-path rows) require a follow-up `<command> confirm` within a
**fixed, profile-tunable timeout**. The same timeout applies to every
confirmable command in a given deployment (no per-command bespoke values,
[../spec/commands.md](../spec/commands.md) §Surface conventions). Per-profile
defaults — concrete values live in [07-deployment.md](07-deployment.md):

| Profile | Confirmation timeout |
|---|---|
| `laptop` | 60s |
| `vps` | 60s |
| `pi` | 90s |
| `remote-llm` | 60s |

▎ /clear
▎ This will wipe your active chat context. Type `/clear confirm` within 60s.

▎ /clear confirm
▎ Context cleared.

The confirmation message MUST start with the same slash command as the
original (so a bare `confirm` does not trigger anything, and a `confirm` typed
in the wrong scope cannot accidentally fire a destructive action).
Confirmation tokens are scoped to (user, scope) and **held in process memory
only** — a Provider restart cancels every pending confirmation; a `confirm`
issued after restart receives the same "no pending action" reply as a late
confirm. Persisting tokens across restarts would require a cleanup sweep and a
TTL gate identical to the in-memory timeout, with no UX gain.

A late `confirm` past the timeout is rejected with the same wording as a
missing pending state.

**Any other input cancels a pending confirmation**, including `/stop`. The
bot replies with a one-line acknowledgement naming the original command:

▎ /clear
▎ This will wipe your active chat context. Type `/clear confirm` within 60s.

▎ what's the weather?
▎ Pending /clear cancelled.
▎ (and the bot answers `what's the weather?` normally)

`/stop` cancels a pending confirmation as a side effect of its
"any other input" treatment and replies with the standard cancellation
acknowledgement, even when no LLM work is in flight
([../spec/commands.md](../spec/commands.md) §Surface conventions).

### One in-flight interruptible request per (user, scope)

A second request from the same caller while one is in flight returns a
localized "request already in progress; use `/stop` to cancel" reply. This
prevents a single user from multiplying LLM-trigger cost on shared workers;
once the first request completes (or is cancelled by `/stop`), the next is
accepted normally. The rule applies per `(user, scope)`; two users in the
same group can have one in-flight chat-mode reply each.

`/retry --digest` is **per-group serialized** — a second admin issuing
`/retry --digest` while another's is still running receives the localized
"a digest retry is already in progress for this group" friendly error
([../spec/commands.md](../spec/commands.md) §Conversation control — `/retry`).
The serialization key is the group id (a digest retry mutates shared
`summary_cache` state), not the issuing user.

### Friendly errors

Unknown command, unknown tag, unknown source ID, malformed flag, unknown
sub-verb on an asset command → response includes:

1. What was wrong (specific token).
2. Up to 3 fuzzy suggestions, ranked by Levenshtein distance, capped at
   `min(2, ceil(len(input) / 2))`. The adaptive bound prevents pathological
   suggestions for short tokens.
3. The exact help line for the command (or `/help` pointer).

▎ /summary securty -w 24h
▎ Unknown tag securty. Did you mean: security, sectors?
▎ Available tags: ai, bitcoin, blog, devops, java, news, science, security, socials.
▎ Usage: /summary [tag] [-w 24h]

`/group-timezone` reuses the same error shape over IANA tzdb names.

### Output formatting

Plain text (decision D30). Inline code wrapped in single backticks; multi-line
code in triple backticks; URLs bare (no markdown link syntax). Adapters
expose a `supportsCodeFormatting` capability flag for richer rendering where
available; v1 adapters additionally assert `supportsMarkdownLinks=false` so
the rendering surface cannot silently widen.

### Input length limits

Two cap categories committed at the spec level
([../spec/commands.md](../spec/commands.md) §Surface conventions); both apply
**before** any LLM or DB work. An oversized message is rejected with a friendly
error and never increments anything beyond the rejection counter.

| Cap | Profile values |
|---|---|
| **Command body cap** (whole slash-command line) | `laptop` 8192, `vps` 4096, `pi` 2048, `remote-llm` 16384 chars |
| **Chat-mode body cap** (inbound chat-mode message) | `profile.context_window / 8` chars (`laptop` 2048, `vps` 1024, `pi` 512, `remote-llm` 4096) |

Per-field caps applied after parsing (these are design-tier additions, not
spec-level commitments — they are guardrails against overlong arguments
inside an otherwise legal command line):

| Field | Cap |
|---|---|
| `--name` | 200 chars |
| `--reason` / `--note` | 500 chars |
| Personal tags (sum of all `-t` values per `/save`) | 200 chars |
| Single tag value | 48 chars (matches the tag-normalization regex) |

Limits are constants in `infochat-core`; not user-tunable. The two top-level
cap values live in [07-deployment.md](07-deployment.md).

---

## 3.2 Permission matrix

The closed set of every command in
[../spec/commands.md](../spec/commands.md) §Command catalogue, with explicit
DM / group-member / group-admin / bot-admin / probation cells. Symbols:

- ✅ — allowed.
- ✅ self — allowed only against the caller's own row / scope.
- ✅ for group — allowed only against the group the command was issued in.
- ❌ — friendly permission error.
- n/a — command does not apply in this scope (e.g. `/group-timezone` in DM).

The **Probation** column is the slow-start gate (D45,
[../spec/security.md](../spec/security.md) §Slow-start tier): ✅ means a
probation-tier caller may issue this command, ❌ means it returns the
"account is in the probation period" reply. Bot-admin and bootstrap-seeded
users are not subject to probation; probation cells for bot-admin-only
commands are ❌ as a matter of completeness (no probation user can be a bot
admin in v1).

| Command | DM | Group (member) | Group (group admin) | Bot admin (anywhere) | Probation |
|---|---|---|---|---|---|
| `/help` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/status` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/get-tags` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/get-sources` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/list-sources` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/list-sources --all` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/list-sources --include-deleted` | ❌ | ❌ | ❌ | ✅ DM only (requires `--all`) | ❌ |
| `/summary [tag]` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/save <uid>` | ✅ self | ✅ self | ✅ self | ✅ self | ❌ |
| `/saved [tag]` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/unsave <uid>` | ✅ self | ✅ self | ✅ self | ✅ self | ❌ |
| `/zcash` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/monero` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/add-source --tags …` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/unfollow-source <id>` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/follow-all-sources` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/remove-source <id>` | ❌ | ❌ | ❌ | ✅ | ❌ |
| `/source-enable <id>` | ❌ | ❌ | ❌ | ✅ | ❌ |
| `/source-disable <id>` | ❌ | ❌ | ❌ | ✅ | ❌ |
| `/follow-tag <tag>` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/unfollow-tag <tag>` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/unfollow-tag --all` | ✅ self | ❌ | ✅ for group | ✅ | ❌ |
| `/clear` | ✅ self | ✅ self | ✅ self | ✅ self | ❌ |
| `/compress` | ✅ self | ✅ self | ✅ self | ✅ self | ❌ |
| `/lang <code>` | ✅ self | ❌ | ✅ for group | ✅ | ✅ |
| `/group-timezone <tz>` | n/a | ❌ | ✅ for group | ✅ in group | ❌ |
| `/forget` | ✅ self | ✅ self | ✅ self | ✅ self | ✅ |
| `/export` | ✅ self | ✅ self | ✅ self | ✅ self | ✅ |
| `/stop` | ✅ self | ✅ self | ✅ self | ✅ self | ✅ |
| `/retry` | ✅ self | ✅ self | ✅ self | ✅ self | ❌ |
| `/retry --digest` | ❌ (DM has no shared digest) | ❌ | ✅ for group | ✅ in group | ❌ |
| `/promote <contact>` | ❌ | ❌ | ❌ | ✅ in group | ❌ |
| `/demote <contact>` | ❌ | ❌ | ❌ | ✅ in group | ❌ |
| `/grant-admin <contact>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/revoke-admin <contact>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/ban <contact> [--reason …]` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/unban <contact>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/invite create --adapter <name> --contact <id>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/invite create --adapter <name> --open` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/invite list` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/invite revoke <code>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/vouch <contact>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/quarantine list` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/quarantine list --all` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/quarantine approve <id>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/quarantine reject <id>` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/audit [-w …]` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |
| `/pending [--page N]` | ❌ | ❌ | ❌ | ✅ DM only | ❌ |

**"✅ DM only" for bot-admin commands.** The bot-global admin commands
(`/grant-admin`, `/revoke-admin`, `/ban`, `/unban`, `/vouch`, the `/invite`
subcommands, the `/quarantine` subcommands, `/audit`, `/pending`, and the
`/list-sources --all` / `--include-deleted` privileged listing) run **only
in DM scope**, even for a bot admin. A reply to a group scope is delivered to
the whole group (there is no private-reply-to-admin path), and these
commands' replies disclose material no group audience should see: verbatim
single-use invite codes (`/invite create`, `/invite list`), the audit trail
(`/audit`), the quarantine queue, deployment-wide source URLs
(`/list-sources --all`), and cross-group admin roles (`/unban`). A bot admin
invoking one of these in a group receives `error.command_dm_only` (the
accurate scope error, not `error.admin_only`). This is a **scope**
restriction within the bot-admin tier — these commands remain bot-admin-only
in the spec's closed privileged-set (`../spec/commands.md` §Permission
model); the tier is unchanged. Group-*contextual* admin commands
(`/approve-group`, `/reject-group`, `/promote`, `/demote`) are unaffected and
keep working in group scope.

**`/pending` actionable set** (mirrors `../spec/commands.md` §Command
catalogue): the listed set is exactly the un-banned users of the inbound
adapter still inside the slow-start probation window (`probation_until IS
NOT NULL AND probation_until > ?`, cutoff from the injected `Clock`;
D45/D55). Deliberately no `registration_state = 'invited'` arm: post-D47
`'invited'` is terminal and `/vouch`'s only effect is the single-column
`probation_until` clear, so "awaiting a vouch" is a subset of the probation
window and an `'invited'` arm would make the list a permanent full roster.

**Admin-only flags are part of command identity.** A non-admin caller passing
`--all` or `--include-deleted` to `/list-sources`, or `--all` to
`/quarantine list`, receives a friendly permission error. The parser does
**not** silently strip the flag and run the un-flagged variant.

**Closed list of privileged-tier commands** (spec-level, this is the set
[04-security.md](04-security.md) §LLM output sanitizer derives its match-set
from at compile time; CI fails on a mismatch):

- **Bot-admin only:** `/grant-admin`, `/revoke-admin`, `/ban`, `/unban`,
  `/promote`, `/demote`, `/vouch`, `/invite create`, `/invite list`,
  `/invite revoke`, `/quarantine list`, `/quarantine approve`,
  `/quarantine reject`, `/audit`, `/remove-source`, `/source-enable`,
  `/source-disable`, `/list-sources --all`, `/list-sources --include-deleted`.
- **Group-admin (or bot admin acting in the group):** `/add-source` in
  groups, `/unfollow-source` in groups, `/lang` in groups, `/group-timezone`,
  `/follow-tag` in groups, `/unfollow-tag` in groups.

Banned users get one fixed reply for any command or chat input:
`Your access has been revoked.` They never reach the parser, the chat
agent, or any DB query past the ban check (decision D11).

---

## 3.3 Slow-start probation tier

Newly-registered users (whether via invite code or group `@mention`) enter a
probation window before getting full access (decision D45,
[../spec/security.md](../spec/security.md) §Slow-start tier). Per-profile
durations (the canonical operator-facing values live in
[07-deployment.md](07-deployment.md)):

| Profile | Probation duration |
|---|---|
| `laptop` | 24h |
| `vps` | 12h |
| `pi` | 24h |
| `remote-llm` | 12h |

**Allowed during probation** (the read-only subset plus the user's own
privacy/locale levers):

- `/help`, `/status`, `/get-tags`, `/get-sources`, `/list-sources`,
  `/summary`, `/saved`
- All operator-configured asset commands (every top-level command registered
  via `bootstrap-assets.json` per D39 — v1 ships `/zcash` and `/monero`)
- `/export` (the user's data is theirs to read)
- `/forget` (the user's privacy lever — blocking it would undermine D37)
- `/lang` (a single-row UPDATE with no LLM cost — non-English new users get
  their own language during the window when they most need it)
- `/stop` — not blocked, but returns the standard idempotent no-op reply
  during probation regardless of in-flight state, since chat mode and
  `/retry` are blocked and there is nothing to cancel

**Blocked during probation:**

- Chat mode (any non-slash input)
- `/add-source`, `/save`, `/unsave`, `/follow-tag`, `/unfollow-tag`,
  `/clear`, `/compress`, `/group-timezone`, `/retry`
- All admin commands (a probation user is by definition not a bot admin or
  group admin in v1)

Blocked operations return a friendly localized reply
([../spec/llm.md](../spec/llm.md) §Translation flow — the bundle string is in
`en` and `cs` per D43) stating when full access unlocks. The reply never
reaches the LLM or any write path.

`/help` for a probation user is filtered to the allowed set above with a
one-line footer stating fuller access — and free-form chat-mode replies —
unlocks when probation ends. Showing a wider list with "blocked during
probation" annotations would contradict the probation reply text the user
receives if they try to invoke a blocked command.

A bot admin can issue `/vouch <contact>` at any time to immediately graduate
the user from probation. See §3.10 `/vouch`.

The probation gate is implemented as a single permission step on
`users.probation_until` (`probation_until IS NULL OR probation_until < NOW()`,
[02-schema.md](02-schema.md) §2.1.1 `users`); the auto-promotion is **lazy**
— the user is promoted at the instant `NOW() > probation_until`, regardless
of whether the column has been nulled. A passive sweep clears the column on
the next request from a promoted user; no background job is required.

---

## 3.4 Discovery commands

### `/help`

Lists commands available **to the calling user in the current scope**, after
the slow-start filter (§3.3) and the actor-tier filter (probation < non-admin
< group admin < bot admin). The list never includes a command the caller
cannot currently invoke; "blocked during probation" annotations on a wider
list would contradict the probation reply text and are not used.

**Bundle composition.** `/help` output is composed from per-command bundle
entries (one localization-bundle key per command, holding that command's
short-help line — D43), not a single monolithic bundle string per actor
tier. The header, the probation-tier footer, and any inter-section dividers
are separate bundle keys; `/help` concatenates the header, then the
per-command lines for the caller's permitted set in a fixed order, then the
footer. CI's bundle-completeness check
([../spec/llm.md](../spec/llm.md) §Translation flow) asserts that every
command in the catalogue has a help-line key in every shipped language
bundle (`en` and `cs` in v1) and that the header / footer keys exist.

Bundle-key naming: `help.cmd.<command>.short`, `help.header.<actor-tier>`,
`help.footer.probation`, `help.divider.<section>`.

### `/status`

Reports:
- Active hardware profile.
- Bot uptime.
- Number of sources subscribed by the calling scope.
- Number of posts in the last 24h matching this scope.
- For bot admins only: total users, banned users, pending quarantine,
  pending invites (`PENDING` rows whose `expires_at > NOW()`),
  eval-failure counts (last 1h).

### `/get-tags`

Lists the controlled vocabulary, sorted alphabetically. Marks tags the
calling scope follows with a leading `*`. Read-only, scope-filtered.

### `/get-sources`

Alias for `/list-sources` accepting the same flags **except `--all`** (and
therefore not `--include-deleted` either, since that requires `--all`). Lists
sources subscribed by the calling scope.

---

## 3.5 Content commands

### `/summary [tag] [-w 24h] [--short|--full|--flat]`

Generates an on-the-fly summary of `READY` posts matching the tag (or all
followed tags if no arg) within the time window (decision D18 — on-the-fly
for user `/summary`, distinct from the pre-generated cached path used for
periodic group digests, §3.12). Cluster grouping is by deterministic SQL
traversal of the `post_reference` graph **before any LLM call**; the LLM
writes prose per pre-computed cluster, so the cluster set is reproducible
(determinism boundary D19).

**Render form (four modes).** `SummaryArgs.form` selects
between four mutually-exclusive renderers; it changes nothing about post
selection, so all four forms are equally reproducible.

- **bare (default)** — `DigestRenderer.renderSummarySections(List<ClusterProse>,
  String)`: the D62 categorized form (category headers, one prose paragraph
  per cluster, `infochat.digest.category-item-cap`, overflow line from
  `reply.summary.category.more`). No closing affordance — that key is
  group-worded. The method takes prose the handler ALREADY generated, so it
  issues no LLM call of its own; that is what makes it reusable on the
  over-cap branch, and what keeps the summarizer/translator call counts
  identical to the flat form. Its name differs from `renderSections`
  because a `List<ClusterProse>` overload would collide with
  `renderSections(List<Post>, String)`'s erasure.
- **`--short`** — `DigestRenderer.renderShortBody(List<Cluster>, String)`:
  one `CategoryRollupGenerator` roll-up synthesis per category header, NO
  per-cluster prose, NO flat blocks. Calls
  `CategoryRollupGenerator.generateRollup`. One LLM call per category; zero
  `SummaryProseGenerator` calls.
- **`--full`** — `DigestRenderer.renderSummarySections(..., Integer.MAX_VALUE)`:
  the categorized form with NO per-category cap and NO overflow line (all
  clusters render). Per-cluster prose IS generated. `--full` was reclaimed
  for categorized-uncapped (the legacy flat meaning moved to
  `--flat`).
- **`--flat`** — `ClusterBlockRenderer.appendClusterBlock`: the flat
  seven-field block per cluster (`[topic_id=…]`, headline, `covered by:`,
  `score:`, `summary:`, `classification:`, `tags:`). This is the renamed
  legacy `--full`; output is byte-identical to the pre-rename `--full`.
  This is also the form `/retry` replays for a `flat` anchor.

`DigestRenderer.forSummaryRendering(...)` is the cross-package construction
seam the plain-JUnit handler tests use; the renderer's `@Inject` fields are
package-private, and the tests must wire a REAL renderer holding their own
sanitizer so the sanitization assertions stay meaningful. The 6-arg
overload additionally wires a `CategoryRollupGenerator` for the `--short`
tests.

**Window column.** The `-w` predicate is `post.ready_at >= cutoff`, not
`published_at`. `published_at` is source-supplied, nullable
(`V7__joins_post.sql`), and lags arbitrarily behind the instant the post
actually reached readers, so keying on it dropped slow-fetched posts
entirely and made NULL-dated posts permanently unreachable. `ready_at` is
stamped by every writer that sets `status='READY'` — `ReadyPromoter` and
the quarantine-approve procedures — so it is always present on the rows
the window can see, and no `COALESCE` is needed. Determinism (D19) is
unaffected and arguably strengthened: `ready_at` records our own pipeline
and cannot be rewritten by a source re-publishing an item.

The same column change applies to the top-3-active-tags query (it must be
computed over the post set it restricts), the two `DigestPostCollector`
queries, and the chat `searchPosts` tool.

**Sort key.** Presentation order stays publication-ordered, but the key is
`COALESCE(published_at, fetched_at) DESC, id DESC`, not a bare
`published_at DESC`. Moving membership to `ready_at` admits NULL-dated
posts for the first time, and Postgres sorts NULLs FIRST under `DESC` — so
a bare key would hand the head of every result set to any feed that simply
omits its `<pubDate>`, which is the exact position `schema.md`'s ingest
clamp exists to defend and strictly easier than the future-dating that
clamp denies. Flagged as a high finding by the 2026-07-25 red-team; see
the corresponding audit. Dated rows are unaffected —
their `COALESCE` resolves to `published_at`, so their relative order is
byte-identical. `NULLS LAST` was rejected: it makes date-less posts the
first evicted under the cluster cap, which defeats the reachability this
ticket was filed to deliver.

The fallback is `fetched_at`, **not** `ready_at` — round 2 of the audit
rejected `ready_at`. `ready_at` is stamped at the READY promotion, so it
is always later than the same row's `fetched_at`; an undated post keyed
on it outranks every dated post the clamp bounded to that fetch. Worse,
`approve_quarantine` and re-evaluation re-stamp `ready_at`, so a released
undated post jumps to a head position no dated post can reach (the clamp
forbids dating forward). `fetched_at` is the partition key — `NOT NULL`,
part of the PK, never re-stamped — and is the exact ceiling the clamp
imposes on dated rows. The residual, stated rather than glossed: an
undated post still sorts at the top of *its own fetch batch*. It cannot
outrank a later fetch and cannot move on release.

**Index and sort cost.** `V64__post_window_index_on_ready_at.sql` drops
`idx_post_published`; the partial index `idx_post_ready_at ON post(ready_at,
id) WHERE status = 'READY'` serves the new window exactly. No index serves
the `COALESCE` sort key (a plain btree on `published_at` cannot), so the
window set is sorted per call — the drop costs nothing that the sort-key
change had not already cost. The window bounds that set, the per-turn
tool-call cap bounds how often it runs, and the armed `statement_timeout`
bounds the worst case; the widest exposure is a `searchPosts` with
`window: P30D`, which sorts the last 30 days of READY posts. No spec-level
query-cost budget exists, so this is recorded rather than gated.

**No-arg behaviour with many followed tags.** When `/summary` is called with
no positional tag and the calling scope follows more than 5 tags, retrieval
is restricted to the **3 most-active tags in the window** (most posts in
`-w`). Reply prefix:

▎ Showing top 3 of N followed tags. Use `/summary <tag>` for a specific topic.

Scopes with ≤5 followed tags continue to summarize across all of them.

**Empty window.** When no eligible posts exist in the window — including the
case where the scope has zero active subscriptions — `/summary` returns a
friendly "no posts yet" reply (deterministic localization-bundle string, no
LLM invocation, no empty summary block).

**Posts with Stage 1 redactions retained** (`stage2_failed=true`, released
`READY` per [../spec/security.md](../spec/security.md) §Failure handling)
are included in the eligible set; the summarizer LLM sees the redacted body
as-is. `[REDACTED:<id>]` placeholders are **not** stripped before the prompt
— the placeholder serves the same defensive purpose at summarize time as it
does at delivery time.

**Summarizer LLM unreachable** (provider down, timeout, or schema-violating
reply after retry per [../spec/llm.md](../spec/llm.md) §Failure handling) →
`/summary` falls back to the **same degraded form** as a saturated periodic
digest (decision D17): headlines + source URLs + post UIDs, no prose. The
friendly degraded notice (localization-bundle string per D43) replaces the
prose block; the deterministic post selection is unaffected. `/retry`
against this degraded run regenerates the prose if the LLM has recovered
(D19, D36).

Output structure (plain text):

News (last 24h)

[topic_id=t-7f3a]
CVE-2026-1234 — OpenSSL heap overflow
covered by: Bleeping Computer (uid p-a91), TheHackerNews (uid p-b04), /r/netsec (uid p-c12)
score: high (3 sources, news+social)
summary: A heap overflow in OpenSSL 3.5 lets a remote attacker execute code...
classification: technical, urgent
tags: security, ai

[topic_id=t-9e02]
...

`-w` defaults to 24h.

The `classification:` line is the union of the clustered posts' per-post ingest
classification labels (the closed set `{factual, opinion, technical, urgent,
ongoing, personal, unknown}`, assigned at ingest — see
[05-llm-and-embeddings.md §5.4.4](05-llm-and-embeddings.md)); it is independent
of `tags:`, and `unknown` is shown only when no substantive label applies.

**Cluster cap is profile-driven** via `infochat.summary.cluster-cap`:

| Profile | Cap |
|---|---|
| `laptop` | 200 |
| `vps` | 100 |
| `pi` | 50 |
| `remote-llm` | 500 |

When deterministic SQL retrieval returns more posts than the cap, the
**oldest** posts are dropped. The response notes both the cap and the
excluded count, e.g. `Showing 100 of 137 posts (cap: vps=100; 37 oldest excluded)`.
See [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) for cluster
sizing and prompt budget interactions.

### `/save <uid>` / `/save <uid> -t tag1,tag2`

Saves a post into the calling user's library. **Saves are per-user-globally**
(decision D13): a save made in DM is visible from any group, and vice versa.
Personal tags are free-form and never become Tier-1 vocabulary.

Per-user cap = **1000** (profile-tunable; the cap exists at spec level, the
exact value is design-tier). Enforcement is atomic: a `SELECT … FOR UPDATE`
on the user's `users.save_count` row ensures two concurrent `/save` calls at
cap-1 admit exactly one. A `/save` over the cap returns a friendly error
pointing at `/unsave`. ([02-schema.md](02-schema.md) §2.6.1 — `save_count`
denormalization on `users`, populated by triggers on `saved_post`.)

**Visibility-of-target rules.**

- `/save` on a `READY` post snapshots the visible body — including any Stage 1
  redactions still in place. (A Stage 2 `BENIGN` verdict transitions the post
  to `READY` while leaving redactions in the body until `/quarantine approve`
  lifts them.)
- `/save` on a `QUARANTINED` post (Stage 2 `INJECTION`/`MALWARE`/`UNKNOWN`,
  hidden) is treated as an **unknown UID**.
- `/save` on a `NEEDS_REVIEW` post is treated as an **unknown UID**;
  `NEEDS_REVIEW` posts are never user-visible (reachable only by bot admins
  via `/quarantine list --all`).

The flow never lets a user bookmark content they cannot see.

### `/saved [tag] [-w 7d] [--page N]`

Lists saved posts. Optional positional tag filters by personal tag. Optional
`-w` filters by saved-within window. Optional `--page N` selects the
1-indexed page; default `1`. **Page size fixed at 20**, not user-tunable.

The reply header **discloses that saves are per-user-globally** (decision
D13) so users running the command in a group are not surprised to see DM
saves listed.

Saved posts (5 of 47 total — saves are global across DM and groups, page 1/3, filter: ai)
- [p-a91] OpenSSL heap overflow — saved 2d ago — tags: security, read-later
- [p-b04] LangChain4j 1.0 release — saved 5h ago — tags: java, read-later
...
Tip: use `/saved ai --page 2` for the next page.

### `/unsave <uid>`

Removes from library. No confirmation (cheap to redo).

---

## 3.6 Asset commands

`/zcash [sub-verb] [--vs <currency>]` and `/monero [sub-verb] [--vs <currency>]`
are operator-configured per-asset commands that expose price and market data
(decision D39, [../spec/commands.md](../spec/commands.md) §Asset commands).

The full design — `bootstrap-assets.json` schema, per-source allowlists, reply
layout, per-host refresh intervals, freshness contract, retention, attribution
strings — lives in
[10-asset-commands.md](10-asset-commands.md). Only a permission-matrix row
and the slow-start membership are recorded here; the asset-command family is
the spec-level abstraction, so adding a future asset to `bootstrap-assets.json`
does not require an update to the matrix.

---

## 3.7 Source management

### `/add-source <url> --tags … [--type <kind>] [--name "..."] [--category <cat>]`

Adds a source for the calling scope. Source rows are global; subscriptions
are per-scope (decision D7).

- DM: any non-banned, non-probation user adds to their own scope.
- Group: group admin only.

Required:

- `<url>` — valid URL (positional).
- `--tags` — comma-separated, ≥1 tag from controlled vocab. New tag values
  are accepted and added to the controlled vocabulary on the spot
  (decision D5). **No tags = command fails.** This guarantees deterministic
  tagger fallback (decision D14).

Optional:

- `--type <kind>` — explicit source kind, matched case-insensitively against
  the closed `source.kind` enum
  ([../SPEC.md](../SPEC.md) §Glossary — "Source kind"). The value
  never reaches a SQL query as free-form text — the enum check is the
  validation boundary; unknown values produce the friendly-error path with
  fuzzy suggestions over the enum.
- `--name "..."` — display name (auto-detected from feed if omitted).
- `--category <cat>` — `news`, `blog`, or `social` (default `news` for
  user-added sources). v1 stores `category` as informational metadata only;
  it is **not** used for retrieval, filtering, or permission decisions.

**Kind resolution.** The source `kind` is determined deterministically:

1. An explicit `--type <kind>` wins (case-insensitive enum match);
   `nitter` is a valid explicit `--type`. **One exception**: a
   URL whose host is a configured Nitter instance (the `nitter-hosts`
   row below) may only be added as `--type nitter`. A non-nitter
   `--type` on such a host is rejected with a friendly error naming the
   host (`error.add_source.nitter_host_type_conflict`) — forcing the
   wrong kind would file the same feed under a second `(kind, identifier)`
   row and duplicate-fetch it. Surfaced via a third `KindResolver.Resolution`
   variant (`nitterHostTypeConflict`) the handler checks before the
   ambiguous-URL path.
2. Without `--type`, the kind is inferred from the URL by the closed table
   below, applied in order. Host comparisons are case-insensitive against
   the URL's authority component:

   | URL pattern | Resolved kind |
   |---|---|
   | scheme `wss://` or `ws://` | `nostr` |
   | host `bsky.app`, `bsky.social`, or any subdomain | `bluesky` |
   | host `reddit.com`, `redd.it`, or any subdomain | `reddit` |
   | host `youtube.com`, `youtu.be`, or any subdomain | `youtube` |
   | host `odysee.com` or any subdomain | `odysee` |
   | host (or any subdomain) in `infochat.sources.nitter-hosts` | `nitter` |

   The `nitter-hosts` row is **config-driven**, not a fixed host literal —
   Nitter is self-hosted on arbitrary, churning domains with no canonical
   host, so the operator declares their instance(s) in the comma-separated
   `infochat.sources.nitter-hosts` allowlist (default empty; same trust
   model as `bootstrap-sources.json`). It applies **before** RSS
   auto-detection (step 3) so a Nitter RSS URL
   (`https://<instance>/<user>/rss`) on a configured host resolves
   `nitter`, not `rss`. With the allowlist empty, the row never fires.
3. **RSS auto-detection.** A URL whose path ends in `.xml`, `.rss`, or
   contains `/feed` (or `/feed/`, `/feed.xml`, etc.) resolves to `rss`
   without `--type`. The URL-validation probe (below) inspects the response
   `Content-Type`; `application/rss+xml`, `application/atom+xml`, or
   `application/xml` confirms the inferred kind. A probe that contradicts
   the URL-pattern hint (returns `text/html`) falls through to step 4.
4. URLs matching none of the rows above are **ambiguous**: the call is
   rejected with a friendly error listing the supported kinds and
   instructing the caller to supply `--type`. There is no silent fallback
   for self-hosted Nitter instances **not** in the operator allowlist, or
   non-canonical mirrors — those require explicit `--type`.

The host-pattern table is **closed at spec level**; additions are spec
amendments. IDN/Punycode folding rules: hosts are folded via
`java.net.IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)` before the case-fold
comparison, so internationalized hosts (`блюски.рф`) compare against the
ASCII pattern correctly when applicable.

**URL validation before insert.** For HTTP-shaped kinds, the Provider
performs a lightweight `HEAD` (or, for servers that reject `HEAD`, a
small-range `GET`) reachability probe through the shared `infochat-ssrf`
library ([../spec/security.md](../spec/security.md) §SSRF), identical to
the Collector's fetcher traffic. The probe runs under the same allowlist,
redirect cap, and timeout caps as fetcher traffic; a 4xx/5xx response, an
SSRF rejection, or a timeout produces a friendly error and **no row is
written**. For StreamSource-shaped kinds (Nostr in v1) the equivalent check
is a single connection attempt against the first relay in the supplied
`config`. The probe is bypassed for the bootstrap loader (the operator is
trusted) but not for `/add-source`.

**Tag-conflict resolution** when the `(kind, identifier)` already exists:

- **Non-admin caller** (DM or group admin against an already-existing source
  row): the call is idempotent on the source row. The caller's
  `source_subscription` is upserted; `--tags` are quietly ignored. Reply:
  `"subscribed; tags unchanged on existing source."` Source rows are global
  per D7 — letting any DM user mutate `bootstrap_tags` would silently change
  ingest behaviour for every other subscriber.
- **Bot admin against an existing row with `--tags`** is the single path that
  rewrites `bootstrap_tags`. Audit-logged. Requires ≥1 tag (the same constraint
  as a fresh insert; an empty replacement would leave the source with zero
  fallback tags).
- **Fresh insert** (whether bot-admin or non-admin): `bootstrap_tags` is
  populated from `--tags` and unioned into the controlled vocabulary
  (decision D5) before the row write so the new tags are addressable by
  `/follow-tag` immediately.

Per-scope tag preferences continue to flow through `scope_tag` (`/follow-tag`
/ `/unfollow-tag`), not through `/add-source`.

**Tree-aware tag retrieval.** Following a TOP node stores one `scope_tag`
row (the top), and read-time subtree expansion resolves it to its leaf set
at every matching site — searchPosts, the EXPLICIT digest filter,
`/summary` positional and top-3 filters, and the `/unfollow-tag` seed. A
leaf added under a followed top after the follow appears in the next digest
with no re-follow (future leaves included by construction). Digest sections
key at the followed level: a top-follow renders ONE aggregated section per
top, a leaf-follow renders per-leaf sections as before. Threshold and cap
apply at the rendered level (a top section is one section).

**Reply distinguishes outcomes:**

| Outcome | Reply |
|---|---|
| Fresh insert | `"Added source <name>. First fetch in ~5 minutes. Use /list-sources to confirm."` + URL-visibility disclosure |
| Bot-admin tag replacement | `"Source already existed; tags updated."` |
| Non-admin against existing row | `"Subscribed; tags unchanged on existing source."` |
| Bot admin against soft-deleted row | `"Source <name> already exists but is removed. No action taken — bootstrap tags were not replaced. Run /source-enable <id> to revive it."` |
| Non-admin against soft-deleted row | `"Subscribed, but source <name> is removed and delivers no posts. Ask a bot admin to revive it with /source-enable <id>."` |

The two soft-deleted rows are the `UPSERT_SOURCE_SQL` `CASE WHEN ? AND
source.deleted_at IS NULL` guard made visible to the caller: the guard already
skipped the tag replacement, so neither reply may claim one and neither writes
an `ADD_SOURCE` audit row. They differ only in the remedy they can name —
`/source-enable` is bot-admin-only.

The caller's `source_subscription` is upserted in the same transaction in
every case.

**URL visibility disclosure.** On a fresh insert, the reply must surface
that source URLs are visible to bot admins
([../spec/security.md](../spec/security.md) §Source URL visibility):

> Note: source URLs are global state and are visible to bot admins via
> `/list-sources --all`.

Omitted on the already-existed paths because the URL was already in the
global set; subscribing to a known URL exposes nothing new.

▎ /add-source https://example.com/feed --tags ai,research --name "Example AI Blog"
▎ Added source Example AI Blog. First fetch in ~5 minutes. Use /list-sources to confirm.
▎ Note: source URLs are global state and are visible to bot admins via /list-sources --all.

### `/list-sources [--all] [--include-deleted] [--page N]`

Without flags, lists sources subscribed by the calling scope. With `--all`
(bot admin only), lists **every source row globally where `deleted_at IS NULL`**,
regardless of subscription, with `failed` and `disabled` rows flagged in the
output. With `--all --include-deleted` (bot admin only, valid only with
`--all`), also lists soft-deleted source rows for forensic / cleanup
workflows.

Paginated like `/saved`: `--page N` 1-indexed, page size fixed at **20**,
total count + page indicator shown in the header, footer suggests
`/list-sources --page <N+1>` when more pages remain.

**URL visibility caveat** in the `--all` reply header:

> Note: source URLs are global state and visible to bot admins. Users
> adding private feeds should treat URLs as operator-visible.

### `/unfollow-source <id>`

Removes the calling scope's `source_subscription`. The source row itself
remains globally if other scopes still subscribe. **Different from
`/remove-source`**: per-scope, available to non-admins.

- DM: caller's own subscription only.
- Group: **group admin or bot admin only.** Plain group members cannot
  unfollow a group subscription in v1. The earlier "any group member may
  unfollow a subscription they added" exception is not in v1: it would
  require `source_subscription.added_by` ownership tracking and a "last
  contributor leaves" edge case that no review report flagged as necessary.

### `/remove-source <id>` *(bot admin only, requires confirm)*

**Soft-delete only.** Hard delete is forbidden in v1
([02-schema.md](02-schema.md) §2.2.1 — `source.deleted_at`).

Behavior:

- Sets `source.deleted_at = now()` and stops the fetcher for this source on
  the next scheduler tick.
- `post` rows are kept untouched. `post.source_id` is `ON DELETE RESTRICT`,
  so post history (and any clusters / summaries derived from it) survives
  the removal.
- `saved_post` references continue to resolve normally — bookmarked posts
  remain readable for every user who saved them.
- All `source_subscription` rows referencing this source are
  cascade-deleted in the same transaction (the source is gone; scope-level
  subscriptions to it must go too). The `/source-enable` path that revives
  a soft-deleted row does **not** restore subscriptions; the user-visible
  reply discloses this explicitly.
- The "no remaining subscribers" state does **not** auto-soft-delete the
  source — sources can exist without subscribers and be re-followed later.

▎ /remove-source 7f3a-...
▎ This will soft-delete source Example AI Blog. Affected subscribers: 12. Posts and saves stay intact.
▎ Type `/remove-source 7f3a-... confirm` within 60s.

### `/source-enable <id>` *(bot admin only)*

Transitions a `failed`, `disabled`, or **soft-deleted**
(`deleted_at IS NOT NULL`) source row back to `active`.

- Emits a probe (HEAD for HTTP-shaped, single-relay connection attempt for
  StreamSource-shaped) before the transition. Probe failure leaves the
  source in its prior state with a friendly error.
- Resets the consecutive-failure counter on success.
- **Soft-deleted sources require `confirm`** (reviving a deliberately-removed
  source has broader implications than re-enabling an operationally-failed
  one). On success, `deleted_at` is cleared and the row is returned to
  `active`. **Subscriptions are not restored** — the cascade-delete from
  `/remove-source` is permanent. Reply discloses this:

  > Source re-enabled. No subscriptions were restored — affected scopes must
  > /add-source again to re-subscribe.

  The disclosure is omitted on enable from `failed` or `disabled` (those
  transitions never cascade-deleted subscriptions).

Audit-logged. There is no separate `/source-undelete` command; `/source-enable`
is the single admin recovery path for all non-`active`-non-hard-deleted states.

### `/source-disable <id>` *(bot admin only)*

Transitions an `active` source row to `disabled` (operator-paused, distinct
from `failed`). Scheduler stops scheduling the source on the next tick.
Existing posts remain visible; `/list-sources --all` continues to list the
row with `disabled` status flagged. No probe required (operator is
intentionally pausing). Audit-logged. The reverse path is `/source-enable`.

---

## 3.8 Tag preferences (per-scope)

The scope's tag-selection mode is recorded explicitly on
`scope_preferences.tag_mode ∈ {ALL, EXPLICIT}` (default `ALL`;
[02-schema.md](02-schema.md) §2.2.5 `scope_preferences`, §2.2.4
`scope_tag`), not implicitly via row presence in `scope_tag` —
implicit-mode logic breaks down on `/unfollow-tag` against an empty set
and makes the digest query depend on row presence.

### `/follow-tag <tag>` / `/unfollow-tag <tag>`

Controls which tags appear in the scope's periodic digest (or `/summary`
no-arg retrieval).

- DM: any non-banned, non-probation user adds to their own scope.
- Group: group admin only.

**Default for a fresh scope is `ALL`** — the union of tags currently
attached to subscribed sources at digest time (decision D15). This is
**dynamic**: a `/add-source` that introduces a new tag to the union takes
effect on the next digest without requiring an explicit `/follow-tag`.

**Mode transitions:**

| Current mode | Command | Result |
|---|---|---|
| `ALL` | `/follow-tag <tag>` | Flip to `EXPLICIT`, seed `scope_tag` rows for **the followed tag only** ("I asked for X, only X") |
| `ALL` | `/unfollow-tag <tag>` | Flip to `EXPLICIT`, seed rows for **all currently subscribed-source `bootstrap_tags` minus the unfollowed tag** ("I want everything except X") |
| `EXPLICIT` | `/follow-tag <tag>` | Add the row in place |
| `EXPLICIT` | `/unfollow-tag <tag>` | Remove the row in place. When the row count drops to 0, the mode flips back to `ALL` |

Digest query: `ALL` mode uses the union of subscribed-source `bootstrap_tags`;
`EXPLICIT` mode uses only the tags whose `scope_tag` rows exist for that
scope.

### `/unfollow-tag --all` *(requires confirm)*

Bulk reset. In any mode, deletes all `scope_tag` rows for the scope and
sets `tag_mode = ALL`. The single command for "I want the dynamic default
back."

---

## 3.9 Conversation control

### `/clear` *(requires confirm)*

Wipes the active context window for the calling (user, scope) (decision
D25). Does NOT touch `chat_memory` (long-term).

Every user has an independent (user, scope) session, even inside a group —
there is no "shared" group context. `/clear` only ever affects the caller's
own (user, scope) row; other users in the same group are untouched.

A pending `/clear` confirm is cancelled by any other input including
`/stop`; see §3.1 — Confirmation for destructive commands.

### `/compress`

Forces an immediate `chat_memory` checkpoint for the calling (user, scope).
On success, `chat_session` rows for `(user, scope)` are **truncated** —
the live context window is cleared after the memory entry is written.

▎ /compress
▎ Compressed 47 messages into a memory entry (8 sentences, 12 keywords, 4 referenced posts).

**Auto-compress** fires when the `chat_session` for `(user, scope)`
occupies a profile-driven percentage of the context-window ceiling:

| Profile | Auto-compress threshold |
|---|---|
| `laptop` | 75% |
| `vps` | 75% |
| `pi` | 60% |
| `remote-llm` | 80% |

The trigger is **deterministic** (a token- or byte-count threshold), never
LLM-judged. It runs **between turns** — after the current reply is
delivered and before the next message is processed — so a reply is never
interrupted mid-stream by an auto-compress. On auto-compress, a one-line
system message (localization-bundle string per D43, not a prose summary)
is sent to the user confirming the checkpoint. Probation users have chat
mode blocked, so their `chat_session` cannot grow; auto-compress never
fires during probation (correct by construction).

**Failure handling.** LLM unreachable, timeout, or schema-violating reply
after retry → the chat session is **held at the ceiling**: the user's
next chat-mode message returns a localized friendly error
("memory checkpoint pending; please `/compress` manually or try again
later"), and the session is never silently truncated. Manual `/compress`
failure surfaces the same error and leaves the session unchanged. The
escape hatch is `/clear` (the user's choice, not the system's).

### `/lang <code>`

Sets per-scope output language. ISO 639-1 codes; v1 ships `en` and `cs`
(decision D43). Source post bodies are never rewritten, and only LLM-authored
prose is routed through this presentation path; the English-anchor translation
a non-English post receives at ingest (decision D29) is a separate path writing
a derived field, and does not change what `/lang` shows the user.

- DM: own scope.
- Group: group admin only.

An unsupported code produces a friendly error listing the supported codes
— never a silent no-op and never a fall-through to the default.

▎ /lang cs
▎ Output language for this scope set to Czech (cs). Source posts remain in their original language.

### `/group-timezone <tz>`

Sets the group's timezone for periodic-digest scheduling (decision D16).
IANA zone name (e.g. `Europe/Prague`, `UTC`). Group only; group admin or
bot admin.

- An unset group's timezone defaults to `UTC` (operator-side default in
  [07-deployment.md](07-deployment.md)).
- The command mutates `groups.timezone`; audit-logged before effect.
- Unknown zone names → friendly-error path with fuzzy suggestions over the
  IANA tzdb names.

▎ /group-timezone Europe/Prague
▎ Group timezone set to Europe/Prague. Morning and evening digests will fire at the global slot hours interpreted in this timezone.

### `/forget` *(requires confirm)*

Immediate purge of everything kept on the calling user's behalf (decision
D37). The user-facing privacy lever; hard purge is the v1 contract
(soft-delete tombstones would silently violate D37).

**Purge set** (called from any scope; per-scope unless explicitly global):

- `chat_memory` rows for `(caller, calling_scope)`.
- `chat_session` rows for `(caller, calling_scope)` — without this, a user
  who `/forget`s to escape a runaway thread still sees it next time they
  message.
- `summary_anchor` rows for `(caller, calling_scope)` with
  `command_kind = 'personal'` only. The group-wide digest anchor
  (`command_kind = 'digest'`, `user_id IS NULL`) is **not** touched —
  it is computed from group subscriptions and the global post set, and
  the same digest is sent to every group member; clearing it on one
  user's `/forget` would invalidate the next digest for the entire group.
- `saved_post` rows for the caller — **globally, regardless of calling
  scope** (saves are per-user-globally per D13).

**Does NOT touch** `users.is_admin`, `users.is_banned`, `group_membership`,
or any `audit_log` row (the audit log is append-only per
[../spec/schema.md](../spec/schema.md) §Invariants — Invariant 10).

Audit-logged before effect like every privileged action against user
state. **The audit row records counts only** — `chat_memory_count`,
`chat_session_count`, `summary_anchor_count`, `saved_post_count` — never
UID lists, personal tags, or any user-authored content. This satisfies the
append-only audit invariant without leaking user-content into the audit
surface ([../spec/security.md](../spec/security.md) §Secrets handling
user-content rule).

Idempotent: a second `/forget` with nothing to remove returns a friendly
no-op reply (no audit row written for the no-op).

**Remaining-scopes disclosure.** Because `/forget` is per-scope for the
chat-tier rows, a user in multiple scopes retains data outside the
calling scope after the command runs. The reply must explicitly disclose
the count of other scopes (DM + groups) where chat-tier rows still exist
for the calling user, and instruct them to issue `/forget` from each of
those scopes:

> Cleared this conversation. You still have data in N other conversations;
> run /forget from each to clear them.

The reply does **not** name the other scopes (naming a DM would leak
existence to a co-admin running the command; enumerating groups is
unnecessary). When the count is **zero** the disclosure clause is omitted
and the reply is the bare confirmation.

### `/export`

Returns the calling user's own data. **Delivery is in-band**: the export
is sent as a reply message (or paginated reply messages) on the same
adapter channel as the command. No external URLs or out-of-band download
links are generated.

Audit-logged before effect. Rate-limited in the "parser-only + DB-read
paginated" bucket ([../spec/security.md](../spec/security.md) §Rate
limiting).

**Output format.** JSON object, UTF-8, plain-text-wrapped (triple
backticks) per the output formatting rule. Per-message size cap is the
**chat-mode body cap** value (§3.1) minus a small per-message header
budget; the reply is split into pages keyed by `page=N/T` if the total
export exceeds the cap.

**Field-level positive list, per table.** The export is defined as an
**explicit table list and a field-level positive list**, not by a vague
"the user's contributions" rule — the boundary is testable and CI-asserted:

| Table | Scope filter (DM `/export`) | Scope filter (group `/export`) | Fields included |
|---|---|---|---|
| `chat_memory` | `(user_id = caller, scope_kind = 'dm', scope_id = caller_dm)` | `(user_id = caller, scope_kind = 'group', scope_id = group)` | all columns |
| `scope_preferences` | calling DM scope | calling group | `tag_mode`, `language`, `created_at`, `updated_at` |
| `scope_tag` | calling DM scope | calling group | `tag`, `created_at` |
| `chat_session` | `(user_id = caller, scope_kind, scope_id)` for calling scope | same | `role`, `body`, `token_count`, `created_at` |
| `source_subscription` | calling DM scope | calling group | `source_id`, `created_at` |
| `summary_anchor` | calling DM scope, `user_id = caller` | calling group, `user_id = caller` (i.e. `command_kind = 'personal'` only) | `command_kind`, `tag`, `window_start`, `window_end`, `cluster_count`, `frozen_uid_set`, `created_at` |
| `saved_post` | `user_id = caller` (the **full library** regardless of calling scope) | same | `post_id`, `personal_tags`, `created_at` |
| `users` | `id = caller` | `id = caller` | full row **except** `is_admin`, `banned_by`, `ban_reason`, `banned_at`, `probation_until` (authorization-state fields about the user, not data held on the user's behalf) |
| `audit_log_view` | `actor_user_id = caller` | `actor_user_id = caller` | redacted view ([04-security.md](04-security.md)); audit rows that mention the caller as a *target* without being authored by them are **not** included |

Group `/export` is scoped to the calling `(user, group)` for per-scope
tables and to the caller for `saved_post` — never another user's rows
in any of those tables, never group-wide content (other members'
messages, the `groups` row beyond `id` and `timezone`, audit rows about
other users), never any row outside the listed tables. DM `/export`
follows the same shape with DM as the scope key.

**No row outside the listed tables.** The CI export-shape test asserts
the output JSON contains only the keys above and refuses any additional
table the implementation might leak.

### `/stop`

Cancels the calling (user, scope)'s currently in-flight interruptible
request **immediately**, freeing the worker (decision D35).

**Interruptible operations** (closed list):

| Operation | Cancellation primitive |
|---|---|
| Chat-mode agent loops | LLM stream close + `pg_cancel_backend(pid)` on any in-flight tool-call connection |
| User-issued `/summary` prose generation | LLM stream close (deterministic SQL retrieval already complete) |
| User-issued `/retry` re-rolls | LLM stream close |

**Non-interruptible operations** (closed list — `/stop` against any of
these returns a friendly no-op stating why):

- Periodic group digests
- The ingest pipeline (Stage 1, Stage 2, tagging, embedding)
- Already-completed work (the message has been delivered or is on the wire)
- `/retry --digest` — a group-admin operation that replaces the group's
  shared cached digest. `/stop` against an in-flight `/retry --digest`
  returns a friendly no-op with the reason; the digest retry continues.
- Mutating commands (source adds, ban, etc.) — their side effects may
  already be partially committed.

**Cancellation primitive.** In v1 every tool in the closed allowlist
([../spec/security.md](../spec/security.md) §Prompt-injection defenses —
see the marker-delimited `<!-- tool-allowlist:begin -->` /
`<!-- tool-allowlist:end -->` table for the single source of truth)
is a read-only DB query, so the primitive is `pg_cancel_backend(pid)` at
the released connection. `helpLookup` follows the same primitive:
a single read-only pgvector cosine probe over `doc_embedding`, armed via
`CancellationService.armToolConnection` like every other chat tool.
**Best-effort** — Postgres may complete the
query before the cancel takes effect; the worker discards the in-flight
result regardless. As an additional safety net, every interruptible
read-only query runs under a profile-driven `statement_timeout` that
bounds the worst case even when `pg_cancel_backend` fails:

The deterministic delivery trigger (D67) is not a tool call —
it runs in `ChatAgent.doHandle` before the LLM is invoked, alongside the
D28 semantic pre-fetch, and shares the same read-only `doc_embedding`
SQL the `helpLookup` tool runs (via `CommandIntentIndex.lookupCommand`).
It does not register with the chat-tool allowlist and does not arm
`pg_cancel_backend`: its SQL is a single LIMIT-1 pgvector probe under
the same `statement_timeout` backstop above, and `/stop` interrupts the
turn at the LLM-call boundary as it does for the semantic pre-fetch.
The trigger's match never reaches the LLM context — its sole consumer
is the post-sanitize usage-block composition step (D67).

The topic-delivery trigger (D69) rides the same shape: the SAME
embed round-trip's vector probes `doc_kind='topic'` FIRST via
`CommandIntentIndex.lookupTopic` — at the pinned
`CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD` (0.52; no tier filter,
topics are tier-flat per D68) — and a match short-circuits the command
probe (topic-over-command precedence, D69; at most one help block per
reply). Same LIMIT-1 + `statement_timeout` bounds, same friendly
degradation (embed or DB failure → no block, the turn completes). Its
sole consumer is the post-sanitize topic-answer composition step:
`HelpTopicCorpus.byTargetRef(slug)` resolves the pointer in memory and
the topic's `topic.<slug>.answer` bundle value at the scope's `/lang`
is appended verbatim under the fixed `reply.chat.topic_delivery.header`
line — never through the sanitizer (topics name user-tier `CLOSED_LIST`
commands) and never through `TranslationPipeline` (D43 two-path rule).
The threshold is the tool-parity 0.52 rather than the command trigger's
conservative 0.62 because there is no lower-threshold tool path to
distinguish from: this trigger is the topic corpus's only consumer, and
the tool-parity value applies directly (both were recalibrated from the
M1-748 production-space measurement, matching how the chat confidence
cutoff was handled by M1-619).

| Profile | `statement_timeout` for interruptible queries |
|---|---|
| `laptop` | 30s |
| `vps` | 15s |
| `pi` | 60s |
| `remote-llm` | 30s |

Tools added in future spec amendments MUST define their own cancellation
primitive before being added to the registry; `/stop` semantics are
spec-load-bearing and a new tool with no cancellation story would
silently weaken the guarantee.

**Once outbound delivery has begun the message is not unsent.** Audit-
before-effect still holds — any audit row written before cancellation
stays. The progress notifier (decision D31) renders a final "stopped"
state on the in-place message.

`/stop` is **idempotent** (no-op friendly reply when nothing is in
flight) and is also the cancel verb for a pending destructive-command
confirmation (§3.1 — Confirmation for destructive commands), even when
no LLM work is in flight.

**During probation:** `/stop` is allowed and returns the standard
idempotent no-op reply regardless of in-flight state — chat mode and
`/retry` are blocked, so a probation user cannot have an in-flight
LLM job to cancel.

### `/retry [--digest]`

Regenerates the prose for the last summary-producing command. Re-runs the
LLM stage only; deterministic post selection and clustering are reused
unchanged (decision D19, [02-schema.md](02-schema.md) §2.6.5
`summary_anchor`). **New posts that arrived since the original run are
not pulled in** — the frozen UID set and cluster mapping recorded in
`summary_anchor` are the complete input.

**Retry cap** (profile-driven; the cap exists at spec level, exact value
is design-tier):

| Profile | Retry cap |
|---|---|
| `laptop` | 3 |
| `vps` | 3 |
| `pi` | 2 |
| `remote-llm` | 5 |

When the retry cap is exhausted, a friendly error is returned and the
anchor is **left intact** (not cleared); the user must issue a non-`/retry`
command to move past the cap. Any non-`/retry` input from the same
(user, scope) clears the anchor; `/retry` itself never advances or resets
it.

No effect (friendly error) when:
- no eligible anchor exists,
- the anchor has been cleared,
- the prior command was cancelled by `/stop`,
- the prior command was not summary-producing.

**Status filter on the frozen UID set.** The frozen UID set is filtered
against current `post.status` at retry time: any UID whose status is no
longer `READY` (e.g., the post has since been quarantined by an admin) is
excluded. If the filtered set differs from the original by more than a
profile-driven threshold (or drops to empty), the user is told that the
retry result differs because content status changed; an empty filtered
set produces a friendly error and no LLM call.

Status-drift threshold (% of UIDs filtered out → user-visible note):

| Profile | Threshold |
|---|---|
| `laptop` | 25% |
| `vps` | 25% |
| `pi` | 25% |
| `remote-llm` | 25% |

**Routing rules in groups.** A group has both per-member personal
anchors (one per `(user, group)` from the user's last `/summary`) and a
group-wide cached digest (decision D17). The `summary_anchor.command_kind`
discriminator drives routing:

| Caller | Anchors present | Resolved target |
|---|---|---|
| Regular group member | own `personal` anchor in this group | own personal anchor |
| Group admin (no personal anchor) | group's cached `digest` anchor in window | cached digest anchor |
| Group admin (both) | both | personal anchor (most recent action wins; use `--digest` to disambiguate) |
| Non-admin in group | with `--digest` flag | friendly error (group-admin or bot-admin only for digest replacement) |
| Any user in DM | with `--digest` flag | friendly error (DM has no shared digest) |

For periodic group digests, the retry replaces the cached digest
(decision D17); the post selection is the **frozen** selection captured
when the original digest was generated (the digest's slot window, not
the wall-clock window at `/retry` time). Only the prose layer is
re-rolled, so the digest does not silently drift forward as time passes.

**Concurrent `/retry --digest` is per-group serialized.** At most one
`/retry --digest` is in flight per group at a time. A second admin
issuing `/retry --digest` while another's is still running receives the
localized "a digest retry is already in progress for this group" error;
no LLM call, no anchor read, no second `summary_cache` write. Digest
retries are non-interruptible per D35, so the second admin practically
waits for the first retry to finish.

**Cached digest message handle** is held in process memory only
([../spec/messaging.md](../spec/messaging.md) §Message handles forbids
handle persistence). After a Provider restart, a `/retry --digest`
posts a *new* message (with prose noting it replaces the prior cached
digest for subsequent reads); the original message is not edited because
the handle is gone (decision D36).

---

## 3.10 Bot-admin commands

**Unknown-contact rule** ([../spec/commands.md](../spec/commands.md)
§Admin — Unknown-contact rule). Unless explicitly stated otherwise
(`/ban`, which mints a `preban` row; `/invite create --contact <id>`,
which writes an `invite_code` row but no `users` row), an admin command
targeting an unknown `(inbound_adapter, contact_id)` returns a friendly
"contact is not registered" error and writes no row. Applies uniformly
to `/grant-admin`, `/revoke-admin`, `/promote`, `/demote`, `/vouch`,
`/unban` (against a contact with no `users` row at all — distinct from
a `preban` row, which is the path covered by the `/unban` deletion
carve-out in `security.md` §User ban).

### `/promote <contact>` / `/demote <contact>` *(group context only, bot admin)*

Promote a group member to group admin (or demote). Bot admins only; group
admins cannot promote others.

**Scoped to the inbound adapter** — both the targeted user and the
targeted group must be on the inbound adapter; `<contact>` resolves to a
`(adapter, contact_id)` row on the same adapter the command came from.

The target must have an **active `group_membership` row**
(`removed_at IS NULL`) in the group. A contact with no active membership
returns the friendly "contact is not an active member of this group" error.

**Banned-target rejection.** `/promote <X>` against a `users.is_banned = true`
target returns a friendly error directing the admin at `/unban` first;
promoting a banned user to group admin would be incoherent (the
banned-user check short-circuits every inbound message before any
group-admin permission would matter).

### `/grant-admin <contact>` / `/revoke-admin <contact>`

Bot-wide admin grant/revoke. **Scoped to the inbound adapter** (one
Provider may run multiple adapters per D46 / [../spec/deployment.md](../spec/deployment.md)
§Topology, and admin grants do not cross adapters in v1). Mutating an
admin row on a different adapter requires running the command from that
adapter; this bounds the blast radius of an adapter compromise.

**Last-admin protection** applies **globally across adapters**: at least
one `is_admin = true` row must exist anywhere on the deployment after
any revoke. Cannot revoke self or last admin.

### `/ban <contact> [--reason "..."]` / `/unban <contact>` *(`/ban` requires confirm)*

Bot-wide ban flag, but `<contact>` is **scoped to the inbound adapter**:
the targeted row is the `(inbound_adapter, contact_id)` `users` row. A
contact with the same byte value on a different adapter is a different
`users` row that this command does not touch. The ban is bot-wide in the
sense that the targeted row is blocked across every scope (DM and groups)
on its adapter; banning the same human on a second adapter requires
running `/ban` from that adapter.

Cannot ban self. Cannot ban the last admin (last-admin protection,
counted globally across adapters).

**Banned user's reply** to any subsequent input: `Your access has been revoked.`
regardless of the input.

**`/unban` reply enumerates side-effects** so the executing admin
understands the post-condition:

- If the row's `registration_state = 'preban'`, the reply states the
  pre-ban-only row was deleted and a fresh invite is required for DM.
- Otherwise, the reply lists every group whose `is_group_admin = true`
  is being reinstated for this user, with a `/demote <contact>` hint
  for cases where group-admin restoration was unintended (see
  [../spec/security.md](../spec/security.md) §User ban).
- An `/unban` of a row with neither pre-ban status nor restored
  group-admin rows produces the plain "user unbanned" reply.

The audit row carries the same details under `details_json`.

The bot does **not** proactively contact a `/unban`ed user — proactive
contact would surface the existence of the ban to a user who has not
chosen to interact again (§3.11 Onboarding — previously-banned).

### `/invite create --adapter <name> {--contact <id> | --open}`

Generates a single-use UUID invite code (decision D44). At most one of
`--contact` or `--open` may be given (decision D60):

- `--contact <id>` — strict invite, bound to a specific
  (contact_id, adapter) pair. **No confirmation required** (risk is
  bounded to one identity).
- `--open` — adapter-bound invite, not pre-bound to a contact_id; the
  first unknown contact on that adapter to present the code is registered.
  **Requires confirm** (broader blast radius than `--contact`).

Providing neither flag defaults to `--open` (decision D60) and passes through
the same confirm gate; no code is created until the admin confirms. Malformed
create input — an unrecognized token, a value-less `--contact`, or a stray
bare argument — returns `error.invite.create_malformed`; no code is created
and no confirm is armed. Providing both is an error; no code is created. The
code is displayed once in the reply and stored as `PENDING`. Audit-logged
before effect.

**Cross-adapter invite creation is permitted by design** — `/invite create`
is the one admin command that may name any adapter the deployment supports
(unlike `/grant-admin` and `/vouch`, which are inbound-adapter-scoped).
A SimpleX admin may create a Signal invite for a contact they want to
onboard; the invite code is bound to the named `(adapter, contact_id)`
pair, creates no elevated access, and only opens the registration gate.
`--adapter <name>` is validated against the set of currently-enabled
adapters at parse time; naming an unknown adapter is a friendly error.

**Pre-banned-contact rejection.** `/invite create --contact <id>` against
a `is_banned=true` row returns a friendly error pointing the admin at
`/unban`; **no invite is created**.

**PENDING caps** ([../spec/security.md](../spec/security.md) §Invite-code
registration). Per-profile values:

| Profile | Per-adapter `--open` cap | Global `--contact` cap |
|---|---|---|
| `laptop` | 3 | 50 |
| `vps` | 5 | 200 |
| `pi` | 2 | 20 |
| `remote-llm` | 5 | 200 |

An admin attempting to mint an `--open` code while the cap is met
receives a friendly error listing the current open codes and a hint
pointing at `/invite revoke`. Codes that are `USED`, `REVOKED`, or
whose `expires_at` has passed do not count toward either cap.

**TTL.** Per-profile invite-code lifespan:

| Profile | Invite-code TTL |
|---|---|
| `laptop` | 7 days |
| `vps` | 7 days |
| `pi` | 14 days |
| `remote-llm` | 7 days |

A code expires at the instant `NOW() >= expires_at` (inclusive).

### `/invite list [--page N]`

Lists `PENDING` invite codes with target contact (or `OPEN` marker),
adapter, and expiry. Paginated, page size **20**.

**Open codes are visually distinguished** with a prominent `OPEN`
marker so an admin auditing exposure can spot them at a glance. Open
codes are higher-blast-radius and should not blend into a long
contact-bound list.

### `/invite revoke <code>` *(requires confirm)*

Immediately transitions a `PENDING` code to `REVOKED`. Audit-logged.

### `/vouch <contact>`

Immediately graduates a user from the slow-start probation tier to
full access (decision D45). Since **D47** removed the `group_only`
registration state from the enum (V27 narrowed the CHECK to
`{preban, invited, vouched}` and no user is ever minted in it again),
`/vouch` has exactly one effect and touches exactly one column:

1. `probation_until = NULL` (immediate graduation).

`registration_state` is **not** advanced — the pre-D47 second effect
("lift the DM invite gate for `group_only` users") no longer has a
state to act on, and the DM invite gate is now the universal
registration path.

**Scoped to the inbound adapter** (same convention as `/grant-admin`):
the targeted row is `(inbound_adapter, contact_id)`. Vouching the same
human on a second adapter requires running `/vouch` from that adapter.

Audit-logged. `details_json` carries `probation_cleared` on the success
leg and `target_registered` on the refusal legs — one transition, not the
two the pre-D47 wording described.

**No-op case.** Already past probation → friendly no-op reply, no
`UPDATE` and no audit row. Since D47 removed `group_only`, probation is
the only thing `/vouch` acts on, so "past probation" is the whole no-op
condition.

### `/quarantine list [--all [-w …]] [--page N]`

Lists quarantine items via the `quarantine_review_view`
([04-security.md](04-security.md) — no `original_html` exposed via
chat).

**Default lists `PENDING` rows only** — the active admin queue. With
`--all` (forensic / audit view), lists every status including
`BENIGN_CLOSED`, `APPROVED`, and `REJECTED`. The review-status enum is
`{PENDING, BENIGN_CLOSED, APPROVED, REJECTED}` ([02-schema.md](02-schema.md)
§2.5.1 `quarantine`).

The `-w <duration>` time window (§3.1 Time window flag) is valid **only
with `--all`** — it filters the forensic view by `flagged_at`. On the
default `PENDING` queue `-w` is **rejected** with a friendly boundary
error (`error.quarantine.window_requires_all`): the active review queue
is actioned **whole**, and a window — especially a generic 24h default —
would hide stale-but-unreviewed items, reintroducing the "old entries
invisible" hazard pagination already guards against. This is the
never-drop-unreviewed invariant (D53). The cutoff is computed from the
injected `Clock` (engineering-rules §9).

`--all` is **not a tier-changing flag** — the whole `/quarantine list`
command is bot-admin-only, so `--all` only changes the row filter, not
the permission. Paginated, page size **20**.

Output includes `quarantine_id`, `post_id`, `flagged_by` (`stage1` or
`stage2`), `rule_id`, `placeholder_id`, `review_status`, and a short
context excerpt. Raw HTML is **not** shown via chat — admins use `psql`
with the admin role on the rare occasions it's needed (admin-client
re-injection risk).

### `/quarantine approve <id>` / `/quarantine reject <id>` *(`reject` of `BENIGN_CLOSED` requires confirm)*

Both run as **stored procedures** (`approve_quarantine(quarantine_id, actor_id)`
and `reject_quarantine(quarantine_id, actor_id)`,
[02-schema.md](02-schema.md) §2.5.2) so the Provider DB role does not need
`SELECT` on the raw-original column. Provider role has `EXECUTE` on the
procedures only.

- **Approve.** Transitions `PENDING → APPROVED` or `BENIGN_CLOSED → APPROVED`,
  restores the redacted span in `post.body`, and fires `NOTIFY new_post`
  for the post (so the Provider re-renders the now-unredacted body via
  the standard high-water-mark path —
  [../spec/architecture.md](../spec/architecture.md) §Inter-service
  communication). The procedure also fires `NOTIFY quarantine_review`
  carrying the state-machine transition (§3.13).
- **Reject.** Transitions `PENDING → REJECTED` (routine path) or
  `BENIGN_CLOSED → REJECTED` (forensic path); the forensic path requires
  `confirm`. Leaves the placeholder permanently. Fires
  `NOTIFY quarantine_review` only.

### `/audit [-w 24h] [--actor <contact>] [--action <verb>] [--page N]`

Reads `audit_log_view` (the redacted view —
[04-security.md](04-security.md)) with filters. The view is **not**
scoped to the calling scope; a bot admin sees deployment-wide audit
history.

**Argument shapes:**

- `--actor <contact>` — contact id resolved against
  `(inbound_adapter, contact_id)` (same shape as `/promote`, `/ban`,
  `/vouch`); cross-adapter actor lookup is not supported in v1.
- `--action <verb>` — one of the closed audit-action enum below; an
  unknown verb returns a friendly error listing the accepted values.
- `--page N` — 1-indexed, page size **20**.
- `-w` defaults to `24h`.

**Closed audit-action enum** (matches the `audit_log.action` column —
[02-schema.md](02-schema.md) §2.1.8 audit-action enum):

```
admin_grant         admin_revoke
group_promote       group_demote
ban                 unban
vouch               invite_create        invite_revoke      invite_consume
quarantine_approve  quarantine_reject
source_add          source_remove        source_enable      source_disable
forget              export
digest_slot_missed
admin_notification_throttled
```

**Unknown actor id** (a well-formed contact id with no matching `users`
row on the inbound adapter) returns the same "no audit rows" reply as
a known id with no rows in the window — the existence-vs-no-rows
distinction is not exposed (a `/list-users`-style enumeration command
is intentionally not in v1).

---

## 3.11 Onboarding

DM access requires a valid invite code (decision D44, D23). All newly
registered users — whether via invite or group `@mention` — start in
slow-start probation (decision D45, §3.3).

### DM first-message gate

An unknown DM contact's first message is checked against the invite table:

- For a `--contact` invite: contact_id, adapter, **and** code value must
  all match, status `PENDING`, not expired.
- For an `--open` invite: only adapter and code value must match; any
  unknown contact on that adapter may consume.

On success the user row is created (probation begins per D45), the code
transitions to `USED` via a race-safe conditional UPDATE
([02-schema.md](02-schema.md) §2.1.5), the welcome is sent, and the
invite-acceptance is audit-logged. On failure: fixed
`Access requires an invitation.` reply, no registration, no further
processing. The drop is counted in the per-`(adapter, contact_id)`
brute-force counter ([../spec/security.md](../spec/security.md) §Invite-code
registration) but not individually audit-logged.

There is no group-side registration path to fall back on: D47 removed
group auto-registration, so an unregistered contact in DM is rejected by
the fixed reply until a bot admin issues an invite they consume
(`/invite create`) — see §3.10.

### Welcome messages

The welcome message branches on three modes — each tuned so the user is
not steered toward an action that will fail or feel empty (decision D23).
Wording is from the localization bundle (D43); `en` shown:

#### Mode 1 — DM, fresh user (just registered via invite)

Probation is in effect. Chat is blocked; we steer the user to the
allowed read-only commands.

[bot] Welcome! You're registered. I aggregate news and social posts.
[bot] Your account is in the probation period for the next ~24h. While probation is on, you can:
[bot]   /help, /status, /get-tags, /get-sources, /list-sources, /summary, /saved, /export, /forget, /lang, /stop, /zcash, /monero
[bot] Free-form chat unlocks automatically when probation ends.
[bot] Content starts once you follow sources with /follow-all-sources (or add your own with /add-source); like free-form chat, these unlock when probation ends. /help shows the full surface.

#### Mode 2 — DM, returning user (probation already cleared)

[bot] Welcome back. You have <N> sources subscribed.
[bot] Try /summary for the last 24h, /saved to revisit bookmarks, or just ask me about a topic.

#### Mode 3 — Group, first @mention by a specific user

Fires once per `(user, group)` pair on the user's first non-banned
`@mention`. The bot is shared, so we point the user at `/help` rather
than dumping setup advice into the channel. The user is registered into
the slow-start probation tier; the welcome surfaces it briefly so the
user understands why some commands return the probation reply.

[bot] Hi @<contact_id_short>! I'm in this group as a news/social aggregator.
[bot] Run /help here to see what you can do. Your account is in a brief probation window; full access opens automatically.
[bot] (Group admins can /add-source and /follow-tag to curate the feed for everyone in this group.)

### Previously-banned, now-unbanned users

When a banned user is `/unban`ed, the bot does **not** proactively send
a "you were unbanned" message — proactive contact would surface the
existence of the ban to a user who has not chosen to interact again, and
would also ping a user who never knew they were banned. The next inbound
message from the unbanned user is treated as the **DM-returning** case
(or the **group-first-mention** case if it arrives in a group), reusing
the existing welcome branch. The `/unban` action itself is audit-logged
as always; surfacing it to the affected user is deferred to v2 if it
surfaces at all.

### Fresh group of unregistered users

In a freshly-added group where every member is unregistered with the
bot, **no first-mention auto-promote will fire** — every first-mention
triggers a registration into the slow-start probation tier (D45), and
probation users are ineligible for the auto-promote
([../spec/security.md](../spec/security.md) §Authorization model). The
bot admin must `/promote` an intended group admin in this case. The
auto-promote path is reserved for groups that already contain at least
one registered, non-probation user who can win the first-mention race.

Operators adding the bot to large or public groups should `/promote` the
intended admin immediately to avoid a first-mention race winner who is
not the intended owner. The first-mention rule is correct for the
common case but an attentive operator gives it no chance to fire on
the wrong user.

---

## 3.12 Periodic group digests

Groups receive a morning and evening digest at per-group local times
(decision D16). The slot hours are operator-configured **globally**
(per-group overrides are a v2 candidate); both values live in
[07-deployment.md](07-deployment.md) §Configuration surface §Groups.

| Setting | Default |
|---|---|
| `infochat.digest.morning-slot-hour` | 08 (24-h local time of the group) |
| `infochat.digest.evening-slot-hour` | 20 (24-h local time of the group) |

Each digest fires within a **profile-driven window centered on the
configured local hour** so the worker pool is not slammed by hundreds of
groups all asking at the same minute:

| Profile | Slot window width |
|---|---|
| `laptop` | 30 min |
| `vps` | 30 min |
| `pi` | 60 min |
| `remote-llm` | 30 min |

**Overload fallback** (headlines + sources, no LLM prose, decision D17)
fires when the window-end is reached without the digest having started.
Results are cached briefly in `summary_cache` so a follow-up `/summary`
during the cache TTL is served from cache (no second LLM call).

**Digest verbosity (`groups.digest_mode`, M1-732).** The V67 column
selects how each category body renders: `brief` — a header carrying the
section's TRUE cluster count plus one `CategoryRollupGenerator` roll-up;
`normal` (the default) — the same plus up to
`infochat.digest.category-headline-count` (default 10) bare headlines
(sanitized `DisplayHeadline` title + URL, no prose); `full` — per-cluster
prose bounded at `infochat.digest.category-item-cap` per section (the
prominence head; a render-local effective cap, not a re-tune of the key),
with one localized demotion line per capped section steering readers to
`/summary <tag> --full`.
`brief` and `normal` make one LLM call per surviving category (the
roll-up) and zero `SummaryProseGenerator` calls. A NULL or unrecognized
value resolves to `normal` with one WARN at the SQL-deserialization
boundary (`DigestWorker.readGroupMetadata`). The user-facing
`/digest brief|normal|full` command is M1-733; delivery batching is
M1-734.

**v1 shape (window line and closing affordance).** A `normal` digest
opens with one localized window-size line — the window's true pre-cap
story count and followed-topic section count, prepended to the FIRST
section's text (lead when one renders, first category otherwise), so no
extra message is spent. Every non-degraded digest closes with one
localized affordance naming the `/summary <tag>` drill-down for topic
depth and the `@mention` chat path for an individual story, folded into
the LAST section's text exactly once.

**Render volume bounds (M1-912).** Three bounds keep a digest's size
tied to configuration rather than window size: the `full` item cap
above; `infochat.digest.degraded-member-cap` (default 3) — the max
member posts a degraded cluster lists on the digest render path, a
`+N more` suffix closing the listing (digest broadcast only; the
`/summary` render forms stay uncapped, `/summary --full` being the
reader-pulled escape); and `infochat.digest.degraded-max-entries`
(default 50) — the max post entries of the whole-digest degraded
fallback (D17), followed by one localized accounting line naming the
rest and steering to `/summary`. The same ticket made `is_degraded`
honest and gave it its first production reader: a render that
completed with zero generated synthesis is flagged degraded, and
`/retry --digest` re-runs every degraded row (over the frozen cluster
set) instead of replaying its sections — replay is reserved for
interrupted deliveries of healthy renders.

**Roll-up prompt shape (M1-728).** The roll-up's prompt carries post
**titles only** — no bodies, no URLs — each bounded via
`DisplayHeadline`, so a several-hundred-cluster category fits a model
context the full-body prompt never could. The requested length scales
with the section's cluster count
(`infochat.digest.rollup-sentence-bands`; default 1 sentence up to 5
clusters, 2 up to 20, 3 up to 75, 5 above); a multi-sentence request
asks for 2-4 distinct threads rather than one flat synthesis, and every
request forbids filler ("various", "a number of", "several
developments") and any stated quantity — the section header already
carries the true count deterministically, and nothing verifies a
model-supplied one. If the truncated titles still exceed
`infochat.digest.rollup-prompt-char-budget`, whole clusters drop from
the END of the section order until the prompt fits, logged at INFO with
the section tag and dropped count. When NOT ONE headline line is
emitted at all — every post in the section titleless (the Bluesky/Nostr
shape: blank titles and the `untitled` sentinel resolve to no headline
via `DisplayHeadline` with the body fallback off) or every cluster
dropped over the budget — the roll-up skips the LLM call entirely and
the category ships without a prefix, logged at INFO with the section
tag and the reason (empty headline set): a fabricated synthesis over an
empty input is worse than none, and the header (+ headlines) + footer
rendering already covers the no-roll-up outcome (M1-743). Both keys live in
[07-deployment.md](07-deployment.md) §Configuration surface.

**Prominence ordering within sections (M1-724, D71).** Section
membership and section order stay D62 tag arithmetic; what changes is
the order of clusters WITHIN a section. The ordering is (1) clusters
carrying the `urgent` ingest classification first, (2) a weighted sum of
four terms, each an integer percentile 0–100 within its own population,
(3) the existing `COALESCE(published_at, fetched_at) DESC, id DESC`
recency key as the final tiebreak:

| Term | Value ranked | Population | Weight key (default) |
|---|---|---|---|
| corroboration | distinct sources in the cluster ÷ distinct sources active under its assigned tag in the window (Other-bucket clusters: the digest-wide active-source count) | the other clusters in this digest | `infochat.digest.weight.corroboration` (7) |
| reposts | max `post.reposts` in the cluster | clusters of the SAME source kind | `infochat.digest.weight.reposts` (2) |
| likes | max `post.likes` in the cluster | clusters of the SAME source kind | `infochat.digest.weight.likes` (1) |
| scarcity | inverse window post volume of the least-prolific member source (`COUNT(*) OVER (PARTITION BY source_id)`, pre-LIMIT) | the other clusters in this digest | `infochat.digest.weight.scarcity` (2) |

The denominator is the sum of the weights of the terms actually PRESENT
on the cluster — a NULL social column drops the term (an RSS cluster
scores out of 9), a 0 counts as present with a bottom percentile
(M1-723 §Absent is not zero). Percentiles are rank-based with ties
sharing a value, computed in integer arithmetic only — no float
participates in any comparison, preserving the D19 byte-identical-replay
property `/retry --digest` depends on. The ranking is pure arithmetic
over columns already on `post`: no LLM participation, no fitted or
learned weights — the four keys are hand-chosen defaults, tuned later
against the live corpus by reading the per-term components
`ClusterProminence` returns alongside the ordering. The reorder never
moves a cluster between sections and never reorders sections, so a
high-scoring cluster cannot starve a small category. `/summary` stays
publication-ordered.

**Personal clusters route to Other (M1-727).** The classification label
set gained a sixth substantive value, `personal` (§5.4.4 of
[05-llm-and-embeddings.md](05-llm-and-embeddings.md)) — KIND, not topic:
a post about the author's own life, a joke, a greeting, a social
pleasantry. A cluster whose EVERY member post carries it routes to the
D62 Other bucket regardless of its tags, is excluded from the
qualifying-tag count (a run of personal posts sharing a tag can neither
create a category nor keep one alive past `category-min-clusters`), and
sorts AFTER non-personal clusters within Other — a bottom gate in
`ClusterProminence.totalOrder()`, symmetric to the `urgent` top gate and
reading no score component, so a personal run cannot evict
genuinely-uncategorizable news from the budget Other competes for. The
all-members rule keeps a mixed cluster — one personal post clustered
with real coverage — in its topic section. `/summary` and `/retry` keep
personal posts fully visible (routing moves them to Other, never filters
them) and keep rendering their `classification:` line.

**Collection window.** The slot window above decides *when* a digest
fires; it is not the period the digest covers. The collection lower bound
is the **previous digest boundary** — the group's latest `summary_cache`
row before this slot — so a post that arrives between two slots still
appears in the next digest. The bound is compared against `ready_at`
(§`/summary` *Window column*), which is what makes that guarantee hold
for a post whose feed date predates the boundary but which only finished
evaluating after it. It is also what makes a zero-post slot lossless: the
empty slot still advances the boundary, and anything that becomes READY
afterwards satisfies the next period. A group's **first-ever** digest has no
previous boundary and falls back **one inter-slot period**, derived from
the gap between the two configured centre hours above (12h at the
defaults, complemented for the morning slot whose predecessor is the
previous day's evening slot). It does not fall back to the slot window:
a ~30-minute lower bound made a new group's opening digest report "no
posts yet" against a full corpus. A missed slot's sentinel row
counts as a boundary — its period is skipped, not folded into the next
digest.

**Zero-eligible-posts digest.** When a digest slot fires and there are
no eligible posts for the group, the digest sends a fixed "no posts yet"
reply — the same deterministic localization-bundle string as
`/summary` §Content empty window — rather than silently sending nothing.
A silent digest slot would be indistinguishable from a missed slot.

**Missed-slot behaviour.** When the Provider is down for the entire
slot window, the missed slot is **skipped, not caught up**: digests are
time-of-day signals, not strictly-once events. The skip is recorded as a
per-group `digest_slot_missed` audit row (one per missed slot, no
throttling) and increments a `digest_slots_missed_total` counter
labelled by group. The next slot fires normally on its own schedule.
There is no operator-visible chat surface for missed digests in v1 —
operators read the audit log or counter.

**Degraded-fallback exit.** A degraded slot does **not** affect any
subsequent slot: each slot's mode is decided independently when its own
window ends. A degraded slot writes to the same `summary_cache` row as
a full-prose slot would, with the same TTL. `/retry --digest` on a
degraded slot regenerates **full prose** if the worker pool is now free
(the cluster set is the frozen original); it degrades again to
headlines+sources only if the retry hits the same saturation. There is
no saturation back-off across slots.

---

## 3.13 NOTIFY channel: `quarantine_review`

`/quarantine list`, `/quarantine approve`, and `/quarantine reject` sit
on top of the `quarantine_review` Postgres `LISTEN/NOTIFY` channel
([../spec/architecture.md](../spec/architecture.md) §Inter-service
communication, [02-schema.md](02-schema.md) §2.9.1 LISTEN / NOTIFY channels).

**Tagged payload.** The channel carries a tagged shape
`(target_kind, target_id, new_status)` where
`target_kind ∈ {'quarantine', 'post'}` discriminates between the two
event families that share the channel:

| Trigger | Payload |
|---|---|
| Quarantine state-machine move (`PENDING → BENIGN_CLOSED / APPROVED / REJECTED`, or initial `PENDING` insert) | `('quarantine', quarantine_id, new_status)` |
| `post.status → NEEDS_REVIEW` (`security.md` §Re-evaluation job) | `('post', post_id, 'NEEDS_REVIEW')` |

The discriminator is required so a single Provider listener can route
both event families without ambiguity. The cursor key for high-water-mark
catch-up is `(reviewed_at, target_kind, target_id)` where `reviewed_at` is
`quarantine.updated_at` for `'quarantine'` events and
`post.status_changed_at` for `'post'` events; the
`(target_kind, target_id)` tail breaks ties so two events with identical
`reviewed_at` cannot lose one to the cursor.

**Consumer behavior.** The Provider drives the throttled admin notifier
([../spec/security.md](../spec/security.md) §Failure handling) on
`PENDING` inserts and on `→ NEEDS_REVIEW` transitions — the two
transitions that require admin attention. `BENIGN_CLOSED`, `APPROVED`,
and `REJECTED` transitions advance the Provider's cursor (so the
high-water mark stays accurate) but produce no user-visible effect in
v1; they are on the channel because the channel's contract is "all
quarantine state-machine moves visible to the Provider role," not "only
the ones the Provider acts on."

**Approve also fires `NOTIFY new_post`** so the Provider re-renders the
now-unredacted body via the standard high-water-mark path on the
`new_post` channel — the two NOTIFYs from a single approve transaction
are not redundant: `quarantine_review` advances the admin-notifier
cursor; `new_post` re-renders the body.

---

## 3.14 Examples (smoke flow)

[user] /add-source https://hnrss.org/frontpage --tags tech
[bot]  Added source Hacker News Frontpage. First fetch in ~5 minutes. Use /list-sources to confirm.
[bot]  Note: source URLs are global state and are visible to bot admins via /list-sources --all.

[user] /follow-tag tech
[bot]  Following tag tech for the periodic digest.

[user] /summary tech
[bot]  Tech (last 24h)
       [topic_id=t-...] ... (4 sources)
       summary: ...

[user] /save p-a91 -t to-read,interesting
[bot]  Saved post p-a91 with tags: to-read, interesting.

[user] /saved to-read
[bot]  Saved posts (1, filter: to-read — saves are global across DM and groups)
       - [p-a91] ...

[user] hey, can you tell me more about the OpenSSL bug from earlier today?
[bot]  (chat agent: pre-fetch memory, look up referenced posts, write prose)

[user] /stop
[bot]  Stopped. (in-flight chat reply cancelled mid-stream)

---
