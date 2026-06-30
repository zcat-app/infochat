# Admin Guide

This guide is for whoever **runs** an infochat deployment — the person who
invites users, keeps the group tidy, and decides what the bot does. You don't
need to be a programmer. Everything here is done by sending the bot ordinary
chat messages that start with a slash (`/`).

> **Just want to *use* the bot?** See the [User Guide](USER_GUIDE.md). **Setting
> it up for the first time?** Start with the [Setup Guide](SETUP_GUIDE.md).

---

## Table of contents

- [The two kinds of admin](#the-two-kinds-of-admin)
- [Becoming the first admin](#becoming-the-first-admin)
- [How you run admin commands](#how-you-run-admin-commands)
- [The admin toolkit](#the-admin-toolkit)
- [How permissions keep things safe](#how-permissions-keep-things-safe)
- [Common situations (playbooks)](#common-situations-playbooks)
- [Advanced (technical reference)](#advanced-technical-reference)
- [Where to go next](#where-to-go-next)

---

## The two kinds of admin

infochat has two separate admin roles. Don't mix them up:

| Role | Scope | What it controls |
|---|---|---|
| **Bot admin** | The whole deployment | Inviting users, banning, approving groups, reviewing flagged content, managing news sources, making other people admins |
| **Group admin** | One specific group | That group's settings — digest times, language, which sources/tags the group follows |

A **bot admin** is the owner/operator (probably you). A **group admin** just
looks after one group's preferences. A bot admin can do anything a group admin
can, *inside any group*.

---

## Becoming the first admin

When you set infochat up, the [wizard](SETUP_GUIDE.md) (step 6) captured how
*you* become the first bot admin. It works differently on the two apps, because
they prove identity differently:

- **SimpleX** uses a secret **claim-token** (there is no fixed address to point
  at). You set the token in the wizard; the **first DM to the bot whose body is
  exactly that token** registers your contact and flips it to admin. Afterwards
  you **unset the token** so it can't be reused — see
  [Connecting to the bot](SETUP_GUIDE.md#connecting-to-the-bot-for-the-first-time).
- **Signal** uses your **contact id (ACI)** — the UUID that identifies your
  Signal account, *not* your phone number. You give it to the wizard directly and
  you are admin from the first start; nothing to claim.

A few things hold for both:

- **You don't need an invite code.** The bootstrap admin skips new-user
  probation, so you can talk to the bot as soon as you've connected to it (Signal:
  message the bot's number; SimpleX: connect to its address — see the link above).
- At least one admin must always exist; the deployment refuses to start with
  nobody in charge, and last-admin protection is **global across apps**.
- You can add more admins later with `/grant-admin` (below).

> Exact steps for finding your contact id on each app are in the
> [deployment notes](docs/spec/deployment.md).

---

## How you run admin commands

- **Send commands as direct messages to the bot** (or, for group-specific ones,
  inside the group while `@mentioning` the bot).
- **Run a command from the same app the target person is on.** Most admin
  actions are tied to the messaging app they arrive on — e.g. to ban someone who
  uses Signal, run `/ban` from your Signal account. (Inviting is the one
  exception — see [`/invite`](#inviting--registering-users).)
- **Some actions ask you to confirm** before they take effect (the riskier
  ones). Confirmation is a two-step **keyword resend**, not a yes/no prompt: the
  bot replies asking you to repeat the command with `confirm` on the end (e.g.
  `/ban +15551234567 confirm`). Sending anything else cancels the pending action.
- **Everything is logged.** Every admin action is recorded in an audit trail
  *before* it happens, so there's always a record of who did what.

---

## The admin toolkit

Commands are grouped below by what you're trying to do. Arguments in
`<angle brackets>` are things you fill in; `[square brackets]` are optional.

### Inviting & registering users

Nobody can message the bot until you invite them. This is deliberate — it keeps
the deployment private.

| Command | What it does |
|---|---|
| `/invite create --adapter <app> --contact <id>` | Make a one-time invite code for **one specific person**. Send them the code; their first message registers them. |
| `/invite create --adapter <app> --open` | Make a one-time code that the **first person to use it** claims (anyone on that app). Broader, so it asks you to confirm. |
| `/invite list [--page N]` | See all unused invite codes, who they're for, and when they expire. |
| `/invite revoke <code>` | Cancel an unused code. Asks to confirm. |
| `/vouch <contact>` | Instantly graduate a user out of the new-user "probation" period (see below). |

**Example — invite one person on Signal:**

```text
/invite create --adapter signal --contact +15551234567
```

The bot replies with a code (shown only once). Give it to that person; they send
the code **on its own** as their first DM to the bot, and that registers them.

> **Inviting works across apps.** Unlike other admin commands, `/invite create`
> takes an explicit `--adapter` so a SimpleX admin can invite a Signal user. The
> code only opens the door for that one identity — it grants no special powers.

### The new-user probation period

Every newly registered user starts in a **slow-start probation** window with a
reduced set of commands. This limits what a brand-new (possibly abusive) account
can do before you trust it. When you're confident in someone, `/vouch <contact>`
ends their probation immediately. Otherwise it ends on its own after the
configured time.

### Moderating users

| Command | What it does |
|---|---|
| `/ban <contact> [--reason "..."]` | Block a user everywhere on their app — DMs and every group. They get one fixed "you're blocked" reply and reach nothing else. |
| `/unban <contact>` | Lift a ban. The reply spells out exactly what changed. |

A few important rules:
- You **can't ban yourself**, and you **can't ban the last remaining admin** —
  the system protects against locking everyone out.
- A ban applies per app. To block the same person on a second app, run `/ban`
  there too.
- **`/unban` tells you the side-effects.** If the person was only ever a blocked
  contact, their record is deleted and they'll need a fresh invite. If they used
  to be a group admin, the reply lists which group-admin roles it's restoring —
  with a `/demote` hint in case you didn't want that.
- Unbanning is quiet: the bot does **not** message the person to tell them. Their
  next message just works again.

### Managing other admins

| Command | What it does |
|---|---|
| `/grant-admin <contact>` | Make someone a bot admin (on the app you run it from). |
| `/revoke-admin <contact>` | Remove someone's bot-admin role. |

The system **never lets the last admin be removed** — there must always be at
least one admin somewhere on the deployment.

### Managing groups

A group can't be used until you approve it. When the bot is added to a group and
a registered member tries to use it, you get a notification that a group is
pending.

| Command | What it does |
|---|---|
| `/approve-group <group_id>` | Turn the bot on for a pending group. Digests start; the first eligible member to @mention the bot afterward is auto-promoted to group admin. |
| `/reject-group <group_id>` | Turn the bot off for a group. Stops digests and interaction. Asks to confirm. |
| `/list-groups [--page N]` | See every group the bot knows, with its status and member count. |
| `/promote <contact>` / `/demote <contact>` | Inside a group: make/unmake someone a group admin. The person must be an active member. |

> The `<group_id>` is the internal ID shown in the pending-group notification and
> in `/list-groups`.

### Reviewing flagged content

Occasionally the AI's safety check quarantines a post (it may redact part of it).
You review these:

| Command | What it does |
|---|---|
| `/quarantine list [--all] [--page N]` | See posts awaiting review. By default shows only the ones needing attention; `--all` shows everything for auditing. |
| `/quarantine approve <id>` | Clear the post and restore any redacted text. It then appears to users normally. |
| `/quarantine reject <id>` | Keep the post hidden/redacted permanently. |

### Managing news sources

Anyone (with permission) can *add* sources one at a time with `/add-source` —
that's in the [User Guide](USER_GUIDE.md). To seed feeds **in bulk** — at first
install, or when rolling out a new set — edit the deployment's
`bootstrap-sources.json` and restart, rather than adding them one by one; see
[Setup Guide](SETUP_GUIDE.md) step 5 for the file and its format. Admins control
removal and visibility:

| Command | What it does |
|---|---|
| `/remove-source <...>` | Delete a source. |
| `/source-disable <...>` / `/source-enable <...>` | Pause/resume fetching from a source without deleting it. |
| `/list-sources --all` | List every source (the admin-wide view). `--include-deleted` shows removed ones too. |

> Tip: prefer **disable** over **remove** when a source is just temporarily
> noisy or broken — you keep its history and can turn it back on.

### Checking the record

| Command | What it does |
|---|---|
| `/audit [--actor <contact>] [--action <verb>] [--page N]` | Browse the deployment-wide history of admin and system actions, filtered by who or what. |

---

## How permissions keep things safe

A few guarantees worth understanding — they're why the bot is safe to run:

- **Permission is checked in plain code, before the AI is ever involved.** The AI
  can never perform an admin action, grant access, or ban anyone. Admin commands
  go through a separate, deterministic path.
- **Banned users hit a wall immediately.** A blocked user gets one fixed reply
  and never reaches the AI or any of your data.
- **Admin-only options can't be smuggled in.** If a non-admin tries an
  admin-only flag, they get a clear "not allowed" error — the bot never quietly
  runs a weaker version of the command.
- **Every action is logged before it happens.** The audit trail can't be skipped.

---

## Common situations (playbooks)

### Onboarding a new user

1. `/invite create --adapter <app> --contact <their id>`.
2. Send them the code; they DM it to the bot and get registered.
3. They're now in **probation** with limited commands. When you trust them,
   `/vouch <their id>` to give full access (or just let probation expire).

### Turning the bot on in a group

1. Make sure **at least one group member is already registered** (invite them via
   DM first — the bot is invisible in a group with no registered members).
2. Add the bot to the group. When a registered member uses it, you get a
   pending-group notice.
3. `/approve-group <group_id>`. The bot posts an activation notice to the group
   and digests begin. The first eligible member is auto-promoted to group admin
   on the next group interaction (you can override with `/promote` / `/demote`).

### Dealing with a problem user

1. `/ban <their id> --reason "spam"` — they're blocked everywhere on that app at
   once.
2. Changed your mind? `/unban <their id>`. **Read the reply** — it tells you if a
   fresh invite is needed, or if any group-admin role was restored (use
   `/demote` if that wasn't intended).

### A post looks wrong or got over-redacted

1. `/quarantine list` to see what's pending.
2. `/quarantine approve <id>` to clear and restore it, or `/quarantine reject
   <id>` to keep it hidden.

### A news source is misbehaving

- Temporarily noisy or down? `/source-disable <...>` — pause it, keep its
  history, re-enable later.
- Genuinely unwanted? `/remove-source <...>`.
- Several feeds from the *same site* failing at once (e.g. all your Nitter
  feeds returning errors)? That's usually the upstream host rate-limiting a
  burst. The collector already paces requests per-host to prevent this; if it
  still happens, an operator can widen the gap via
  `infochat.fetch.host-min-interval` in `application.properties` (then restart
  the collector). Details in that property's comments.

### Adding a co-admin / handing over

- `/grant-admin <their id>` (run it on the app they use). To step back later,
  the *other* admin can `/revoke-admin` you — the system won't let the last admin
  be removed, so there's no way to accidentally orphan the deployment.

---

## Advanced (technical reference)

*Optional. The details behind the behaviour above.*

### Per-adapter scoping

A single Provider can run more than one messaging app (adapter) at once. With
the exception of `/invite create` (which carries an explicit `--adapter`), admin
commands act on the **adapter they arrive on**: `/ban`, `/grant-admin`,
`/promote`, `/vouch`, `/audit --actor`, etc. all resolve their `<contact>`
against the inbound adapter. The same byte-identical contact id on a different
adapter is a *different* user record. This bounds the blast radius if one
adapter is compromised — an attacker on one app can't elevate or mutate accounts
on another. **Last-admin protection is global**, though: at least one admin must
exist somewhere across all adapters after any `/revoke-admin`.

### Commands that require confirmation

Destructive or broad-blast-radius actions prompt before taking effect — notably
`/ban`, `/invite create --open`, `/invite revoke`, `/reject-group`,
`/remove-source`, reviving a soft-deleted source via `/source-enable`, and
rejecting a system-cleared post via `/quarantine reject` (the forensic /
`BENIGN_CLOSED` path only). Routine constructive actions that only add or
approve (`/invite create --contact`, `/approve-group`) don't — but being aimed
at a single target is not what exempts a command: `/ban` is targeted and still
prompts. A few prompts are **state-dependent**: `/source-enable` and
`/quarantine reject` confirm only on their surprising path (reviving a removed
source, overriding the system's all-clear) and run directly otherwise (the
routine `/quarantine reject` of a `PENDING` post is the expected review outcome
and is not gated).

### The closed privileged-command set

These tiers are a **spec-level commitment** (changing them is a spec amendment,
not a config tweak), because the LLM output sanitizer and the probation
classifier read from them:

- **Bot-admin only:** `/grant-admin`, `/revoke-admin`, `/ban`, `/unban`,
  `/promote`, `/demote`, `/vouch`, `/invite create`, `/invite list`,
  `/invite revoke`, `/quarantine list`, `/quarantine approve`,
  `/quarantine reject`, `/audit`, `/remove-source`, `/source-enable`,
  `/source-disable`, `/list-sources --all`, `/list-sources --include-deleted`,
  `/approve-group`, `/reject-group`, `/list-groups`.
- **Group-admin (or bot admin acting in the group):** `/add-source` in groups,
  `/unfollow-source` in groups, `/lang` in groups, `/group-timezone`,
  `/digest`, `/follow-tag` in groups, `/unfollow-tag` in groups.

### Group digests

Approved groups receive a **morning and evening digest** in their own local
time. The two slot hours are set globally by the operator; per-group overrides
aren't in v1, but each group's `/group-timezone` decides when "morning" and
"evening" land. A group admin can pause digests with `/digest off`. If the
worker pool is overloaded when a digest is due, it falls back to a plain
headlines-and-links digest; `/retry --digest` regenerates the full version once
load clears. Missed slots (bot was down) are skipped, not caught up, and
recorded in the audit log.

### Recovering the auto-join group pool

The bot accepts only a bounded number of unsolicited group auto-joins (a D47
anti-spam cap). A SimpleX group the bot was *added to* but never formally approved
can occupy a slot in that pool with no `groups` row — so it won't appear in
`/list-groups`, and if the bot is later removed from such a group there's no leave
signal to free the slot automatically. `/recover-pool` (bot-admin, **DM only**)
recovers those slots:

| Command | What it does |
|---|---|
| `/recover-pool` | List the active pool — each entry's adapter, upstream group id, who invited the bot, and when it joined. |
| `/recover-pool <adapter> <upstream-group-id>` | Free one slot by its natural key (read the adapter + id from the list above) so it stops counting against the cap. Audit-logged; a later re-join reactivates the slot. |

### Upgrading the bot

To move the bot to the latest code, run **one command** on the host — no
environment variables, no git steps:

```bash
./prod/scripts/upgrade.sh        # confirms before each step
./prod/scripts/upgrade.sh -y     # unattended (no confirmations)
```

It backs up first (database dump + messaging identities), rebuilds the two app
images from the latest code, and restarts the Collector and then the Provider.
Everything that holds state is preserved — the database, your LLM and adapter
settings, secrets, and the SimpleX/Signal identities all survive untouched. An
app is only restarted if its rebuilt image actually changed, so re-running when
nothing is new is harmless. If the build or the post-restart check fails, the
code is rolled back automatically; a database migration that had already applied
is restored from the backup the upgrade took first.

Run it only on a deployment you have already set up. The per-step detail and the
single-host rolling-restart caveat are in the [Setup Guide](SETUP_GUIDE.md) and
[docs/design/07-deployment.md](docs/design/07-deployment.md) §7.11.

### Full references

- Command catalogue (every command, every flag, exact argument grammar):
  [docs/spec/commands.md](docs/spec/commands.md)
- Per-actor-tier permission matrix:
  [docs/design/03-commands.md](docs/design/03-commands.md)
- Security model, ban semantics, invite caps, admin threat profile:
  [docs/spec/security.md](docs/spec/security.md)

---

## Where to go next

- **[User Guide](USER_GUIDE.md)** — what your users can do with the bot.
- **[Setup Guide](SETUP_GUIDE.md)** — installation and configuration.
- **[Technical map (docs/SPEC.md)](docs/SPEC.md)** — architecture and design.
