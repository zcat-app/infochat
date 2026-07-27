# User Guide

This guide is for **using** infochat — getting your news, asking the bot
questions, and tuning what you follow. No technical knowledge needed. You talk
to infochat exactly like you'd message a friend on your chat app.

> **Running the deployment** (inviting people, moderating)? See the
> [Admin Guide](ADMIN_GUIDE.md). **Installing it**? See the
> [Setup Guide](SETUP_GUIDE.md).

---

## Table of contents

- [Getting access (your invite)](#getting-access-your-invite)
- [Two ways to talk to the bot](#two-ways-to-talk-to-the-bot)
- [The essentials (command cheat sheet)](#the-essentials-command-cheat-sheet)
- [Chatting in plain English](#chatting-in-plain-english)
- [Worked examples](#worked-examples)
- [DMs vs groups](#dms-vs-groups)
- [Your privacy and your data](#your-privacy-and-your-data)
- [Advanced (reference)](#advanced-reference)
- [Where to go next](#where-to-go-next)

---

## Getting access (your invite)

<!-- topic:getting-access:begin -->
infochat is invite-only — you can't just message the bot cold.

1. An admin gives you a **one-time invite code** (a string of characters).
2. Send that code to the bot as your first direct message.
3. You're registered, and the bot welcomes you.

New accounts start in a short **probation period** with a limited set of
commands (and chat mode turned off). This is normal and temporary — it just
means a brand-new account can't do everything on day one. Full access unlocks
when probation ends, or sooner if an admin "vouches" for you. Run `/help` any
time to see exactly what you can do right now.
<!-- topic:getting-access:end -->

---

## Two ways to talk to the bot

<!-- topic:chat-vs-commands:begin -->
| Way | How | Best for |
|---|---|---|
| **Commands** | Messages starting with a slash, e.g. `/summary` | Exact, repeatable actions ("show me today's posts", "save this") |
| **Chat** | Plain language, e.g. *"what's new in AI safety?"* | Questions, follow-ups, exploring |

You can mix both freely. Commands always start with `/`; anything else is
treated as a chat question.

> The bot replies in plain text. Links appear as plain web addresses you can tap
> or copy.
<!-- topic:chat-vs-commands:end -->

---

## The essentials (command cheat sheet)

Arguments in `<angle brackets>` are things you fill in; `[square brackets]` are
optional. Run `/help` to see the ones available to *you* right now.

### Finding your way

| Command | What it does |
|---|---|
| `/help` | Lists the commands you're allowed to use |
| `/status` | Shows the bot's status (active profile and uptime; admins also see the pending-group count) |

### Getting the news

| Command | What it does |
|---|---|
| `/summary [topic]` | Summarises the latest posts, grouped into topic categories (optionally just one topic/tag). `--short` / `--full` / `--flat` change the shape — see [Worked examples](#worked-examples) |
| `/zcash` · `/monero` | Cryptocurrency price/market data — a cached snapshot stamped with its capture time and cache age (if your admin enabled them) |
| *(plain question)* | Ask the bot anything about your news — see [Chatting](#chatting-in-plain-english) |

### Saving posts

| Command | What it does |
|---|---|
| `/save <id>` | Bookmark a post to your personal library |
| `/saved [topic]` | List your saved posts |
| `/unsave <id>` | Remove a bookmark |

> Each post has an **id** — a long hexadecimal string. That's what you pass to
> `/save` and `/unsave` (your messaging app lets you tap-and-hold to copy it).
> The reliable way to get one is **`/summary --flat`**, whose per-story blocks
> carry a `covered by:` line listing each post's source and id. The other forms
> normally show prose without ids — though they fall back to a plain
> `title — link (uid …)` listing when the window holds too many posts to write
> up, or when the write-up step is unavailable, and those lines do carry ids.
> Your library is **yours alone** and follows you everywhere (a post saved in a
> group shows up when you list saves in a DM).

### News sources

| Command | What it does |
|---|---|
| `/add-source <url> --tags <tag1,tag2>` | Add a feed (RSS, Bluesky, Reddit, YouTube, etc.). **Tags are required.** |
| `/get-sources` | List the sources you're following |
| `/unfollow-source <id>` | Stop following a source (just for you) |

### Tuning your topics (tags)

| Command | What it does |
|---|---|
| `/get-tags` | Show the available topics and which you follow |
| `/follow-tag <tag>` | Narrow your digests to specific topics |
| `/unfollow-tag <tag>` | Drop a topic |
| `/unfollow-tag --all` | Reset to the default ("everything from my sources") (asks to confirm) |

By default you get **everything** from your subscribed sources. The moment you
`/follow-tag` something, you switch to "only the topics I picked."

### Language and control

| Command | What it does |
|---|---|
| `/lang <code>` | Set the language the bot answers you in — its summaries and chat replies (English and Czech in v1; original posts aren't translated) |
| `/stop` | Cancel whatever the bot is currently working on for you |
| `/retry` | Re-generate the last summary's wording (same posts) |
| `/clear` | Clear the current conversation's short-term context (asks to confirm) |
| `/forget` | Erase your stored data (see [Privacy](#your-privacy-and-your-data)) |
| `/export` | Get a copy of your own data, sent back to you in chat |

---

## Chatting in plain English

Anything that doesn't start with `/` is a question for the bot's chat assistant.
Ask things like:

- *"What happened in privacy tech this week?"*
- *"Summarise the top three AI stories."*
- *"Tell me more about that Hugging Face post."*

<!-- topic:chat-assistant-boundary:begin -->
Good to know:

- The assistant can **only read** — it searches and explains your news. It can
  never change settings, post anything, or act as an admin.
- It only sees **your** stuff — your subscriptions, your scope. It can't reach
  another user's data or another conversation.
- If a reply is taking too long, `/stop` cancels it.
<!-- topic:chat-assistant-boundary:end -->

---

## Worked examples

### 1. Get the latest news

```text
/summary
```

The bot groups recent posts into **topic categories** and sends **one message
per category**, each a short prose write-up per story:

```text
AI NEWS

Anthropic announced Fable 5, its new Mythos-class model, claiming...

A new architecture from DeepSeek aims to replace the transformer for...

+3 more stories — narrow with a tag or -w to see them
```

```text
OTHER NEWS

A privacy-tech roundup covering...
```

Want just one topic?

```text
/summary ai-safety
```

**Four ways to render it.** Same posts, different shape:

| Form | What you get |
|---|---|
| `/summary` *(default)* | Category sections, prose per story, up to 12 stories per section |
| `/summary --short` | One roll-up paragraph per category — no per-story prose |
| `/summary --full` | Like the default, but **every** story — no per-section cap |
| `/summary --flat` | One flat block per story, **including post ids** — the form to use when you want an id to `/save` |

`--flat` is the form that always prints ids, one block per story:

```text
[topic_id=t-3f8b2c1d]
Anthropic releases Fable 5, a Mythos-class model
covered by: TechCrunch (uid 9d4e1ac0f23b7e8516a0c4d9f7b2e3018a5c6d7e0f1928374655a4b3c2d1e0f9), @anthropic on Bluesky (uid 3f8b2c1d0e9a7654b1c2d3e4f5061728394a5b6c7d8e9f001122334455667788)
score: 2 sources
summary: Anthropic announced Fable 5, its new Mythos-class model, claiming...
classification: ai, ml
tags: ai, ml

[topic_id=t-c0ffee11]
DeepSeek proposes a transformer-replacement architecture
covered by: VentureBeat (uid c0ffee11223344556677889900aabbccddeeff00112233445566778899aabbcc)
score: 1 source
summary: A new architecture aims to replace the transformer for...
classification: ai
tags: ai
```

The **`covered by:`** line is the useful part there: it lists the **exact posts**
behind each topic, each with its source and its **id** — the long hex string in
parentheses after `uid`. Those ids are what you use to save a post
(next example).

### 2. Dig into one story from the summary

There's no separate "detail" command — just **ask** about it in plain language.
The most reliable way is to **name the story or topic** you're curious about:

```text
Tell me more about the new Anthropic model Fable 5.
```

The assistant searches your news for that post, reads it in full, follows any
links it cites, and explains it. If the summary is still on screen, you can also
refer to a story by its position:

```text
Tell me more about the second story.
```

Keep asking follow-ups to go deeper, and bookmark anything worth keeping using
the id from a `/summary --flat` block's `covered by:` line:

```text
/save 9d4e1ac0f23b7e8516a0c4d9f7b2e3018a5c6d7e0f1928374655a4b3c2d1e0f9
```

### 3. Add a news source

<!-- topic:add-source-requires-tags:begin -->
Adding a source always needs at least one **tag** (this guarantees your posts
can still be sorted even if automatic tagging misses):

```text
/add-source https://huggingface.co/blog/feed.xml --tags ai,ml
```

The bot figures out the source type from the address — RSS, Bluesky, Reddit,
YouTube, Odysee, or Nostr. **Nitter** is its own distinct type: add one with
`--type nitter` (e.g. `/add-source https://nitter.example/user/rss --type nitter
--tags news`). Because Nitter is self-hosted on all sorts of addresses, the bot
can only auto-recognise the Nitter instances your operator has configured; for
any other Nitter address, name the type with `--type nitter`. When you add a
brand-new source, the bot also reminds you that **source web addresses are
visible to admins** — worth knowing before you add a private feed.
<!-- topic:add-source-requires-tags:end -->

### 4. Fix the topics you see

<!-- topic:personal-vs-shared-tags:begin -->
Got the wrong topics in your digest? Tune **your own view** with tags:

```text
/follow-tag ai
/unfollow-tag crypto
```

> Note: this changes what *you* see. The tags attached to a *shared* source
> itself can only be changed by a bot admin — so it's worth getting the `--tags`
> right when you first add a source.
<!-- topic:personal-vs-shared-tags:end -->

### 5. Stop following a source

<!-- topic:unfollow-vs-delete:begin -->
```text
/unfollow-source 3f9a8b2c-1d4e-4a6f-9c0b-7e2d5a8f1b3c
```

The `<id>` is the source's UUID. This only unsubscribes *you*; the source still
exists for others. (Deleting a source entirely is an admin action — ask an admin
if a shared source should go.)
<!-- topic:unfollow-vs-delete:end -->

### 6. Read it in another language

```text
/lang cs
```

Replies now come in Czech. (The original posts themselves are never
translated — only the bot's own wording.)

---

## DMs vs groups

<!-- topic:dm-vs-group:begin -->
infochat works in both private chats and group chats, with a few differences:

| | Direct message | Group chat |
|---|---|---|
| **Getting a reply** | Just message the bot | **@mention** the bot, or wait for its scheduled digests |
| **What you can do** | The full set you're allowed | Same, but group-wide settings (language, digest times, the group's sources) are managed by **group admins** |
| **Your data** | Private to you | Your personal context and saves are still **yours** — never shared with other group members |

Approved groups also get automatic **morning and evening digests** of the latest
posts.
<!-- topic:dm-vs-group:end -->

---

## Your privacy and your data

infochat gives you direct control:

- **`/forget`** erases your stored data — your chat memory, the current
  conversation, and your **entire saved-posts library** (saves are wiped no
  matter which conversation you run it from). It tells you if you still have data
  in other conversations so you can clear those too. It asks you to confirm
  first.
- **`/export`** sends you a copy of your own data, right in the chat.
- **Heads up:** the web addresses of news sources are **global** and visible to
  admins (via the admin source list). **Don't add a feed you'd consider secret.**

What `/forget` does **not** touch: your account's ban/admin status, your group
memberships, and the system's tamper-proof audit log (which records *that*
actions happened, never your message content).

---

## Advanced (reference)

*Optional details for power users.*

- **Time windows.** Commands like `/summary` and `/saved` accept a `-w` window
  argument to limit results to a recent period. The exact accepted forms are in
  the [command reference](docs/spec/commands.md).
- **`/retry`** re-rolls only the *wording* of your last summary — the set of
  posts is frozen, so you get a fresh phrasing of the same content, not newer
  posts. It's capped to a few tries.
- **`/stop`** cancels in-flight chat replies, your `/summary` generation, and
  your `/retry` — but not already-sent messages or background jobs.
<!-- topic:clear-vs-forget:begin -->
- **`/clear` vs `/forget`.** `/clear` only drops the short-term context of the
  current conversation (your longer-term memory and saves stay). `/forget` is the
  full privacy purge.
<!-- topic:clear-vs-forget:end -->
- **`/compress`** checkpoints a long conversation's memory and trims the live
  context; the bot also does this automatically when a conversation gets long.
- **Tag your saves.** `/save <id> -t tag1,tag2` attaches your own personal tags
  to a bookmark (kept separate from the post's own tags); `/saved <tag>` then
  filters your library by them.
- **Asset price source & currency.** `/zcash` and `/monero` take an optional
  exchange sub-verb and a `--vs <currency>` — e.g. `/zcash kraken --vs usd`.
  Each source is configured with a single quote currency and prices are
  collected only in that currency, so `--vs` selects it explicitly rather than
  converting; in the shipped configuration every source quotes `usd`. Asking
  for any other currency tells you which one that source serves. Bare `/zcash`
  uses the default source and currency your admin configured. (The exchanges
  themselves quote further currencies; collecting them is not part of this
  release.)
- **Re-include all standard sources.** `/follow-all-sources` clears this
  scope's `/unfollow-source` opt-outs in one call — every standard source the
  scope had opted out of becomes visible again. Per scope: in a DM it affects
  only you; in a group it affects the whole group. Standard sources are
  visible by default, so the command only undoes explicit unfollows.
- **Pagination.** `/saved`, `/get-sources`, `/list-sources`, and `/export`
  accept `--page N` (1-indexed) to step through long lists.
- **Full command catalogue** (every command, every option, exact argument
  grammar): [docs/spec/commands.md](docs/spec/commands.md).

---

## Where to go next

- **[Admin Guide](ADMIN_GUIDE.md)** — if you also run the deployment.
- **[Setup Guide](SETUP_GUIDE.md)** — installing infochat.
- **[Project overview (README)](README.md)** — what infochat is and how it works.
