# infochat

**Your own news desk, inside the private messenger you already use.**

infochat is a self-hosted chatbot that watches the news and social feeds you
care about, reads and curates every post with an LLM, and serves it to you on
demand — summaries, filtered lists, follow-up questions — through a private
messaging app like SimpleX or Signal. No website to visit, no algorithmic
timeline, no big-tech intermediary holding your reading habits. You run it on
your own hardware; your data stays there.

---

## The problem it solves

Staying informed today means juggling a dozen feeds — RSS, Bluesky, Nostr,
Reddit, YouTube — each with its own app, its own engagement-maximizing
timeline, and its own pile of noise. You either drown in tabs or hand a single
platform control over what you see.

infochat collapses that into one private conversation. It pulls from all your
sources, evaluates and tags each post, and lets you ask for exactly what you
want — *"what's new on privacy tech today?"*, *"summarize the top posts"*,
*"tell me more about that third item"* — in plain language, in a chat you
already trust. It runs entirely on infrastructure you control.

## Project status

infochat is **pre-1.0 and in active development**, entering user testing. The
architecture, security model, and feature set are implemented and tested, but
the project has not yet been hardened by real-world production use at scale.
Self-host it for testing and personal use; review the [security
model](docs/spec/security.md) before exposing it to untrusted users.

---

## What it can do

### Sources it fetches

| Kind | Examples |
|---|---|
| **RSS / Atom** | Any news site, blog, or podcast feed |
| **Bluesky** | Author and feed timelines |
| **Nostr** | Relay subscriptions (stream source) |
| **Reddit** | Subreddit and listing feeds |
| **YouTube** | Channel uploads |
| **Odysee** | Channel content |
| **Nitter** | X / Twitter timelines via a Nitter instance |

Every post runs through an LLM evaluation pipeline — a security/safety check,
topic **tagging**, **entity extraction**, and vector **embedding** — before it
is stored, so retrieval is fast, filterable, and reproducible.

### Asset commands

Separate from the news pipeline, infochat answers live price/market lookups
such as `/zcash` and `/monero`, sourced from public market data
(CoinGecko, Kraken, Bitfinex). Every reply names its data source and links it.

### Messaging apps it supports

- **SimpleX**
- **Signal**

One deployment can run either, or **both at once**. (An in-memory adapter
exists for testing and is never used in production.)

### How you interact with it

| Axis | Options |
|---|---|
| **Where** | **Direct message** (private, full feature set) or **group chat** (the bot replies only when `@mentioned`, plus scheduled morning/evening digests) |
| **How** | **Slash commands** (e.g. `/news`, `/summary`, `/add-source`) for precise, reproducible queries, or **chat mode** for natural-language questions and follow-ups |

State, memory, and saved items are **isolated per user and per scope** — your
DM history never leaks into a group, and one user's data never leaks to
another.

---

## Documentation

Start with the abstract above, then jump to the guide for your role:

| If you are… | Read | Covers |
|---|---|---|
| **Setting it up** | [SETUP_GUIDE.md](SETUP_GUIDE.md) | Install, configure, and run both services; recommended settings per hardware scenario, with examples |
| **Running / moderating it** | [ADMIN_GUIDE.md](ADMIN_GUIDE.md) | Bootstrap the admin account, invite and moderate users, admin commands, and worked moderation scenarios |
| **Using the bot** | [USER_GUIDE.md](USER_GUIDE.md) | Getting invited, talking to the bot, managing news sources and tags, and example conversations |
| **Running it from source** | [DEVELOPER.md](DEVELOPER.md) | Build from source and run both services in dev mode; module layout and ports |
| **Contributing a change** | [CONTRIBUTING.md](CONTRIBUTING.md) | The ticket-driven workflow, engineering conventions, and a worked example of adding a command |
| **Understanding the architecture** | [OVERVIEW.md](OVERVIEW.md) | A high-level 5-minute map — the two services, the modules, and how data flows; links into the spec for depth |
| **The technical reference** | [docs/SPEC.md](docs/SPEC.md) | The technical map — architecture, data model, command catalogue, security model, and design notes |

> The four guides above are written for a general audience and stay
> deliberately practical. The deep technical reference lives under
> [`docs/`](docs/) — [`docs/spec/`](docs/spec/) (what & why) and
> [`docs/design/`](docs/design/) (how). The guides link into those docs rather
> than restating them, so there is a single source of truth.

---

## How it works (in brief)

infochat is two services that share a PostgreSQL database:

- **Collector** — fetches every source, runs the LLM evaluation pipeline, and
  stores posts. It is **headless**: no user can talk to it directly.
- **Provider** — the only user-facing component. It talks to your messaging
  app(s), handles slash commands and chat-mode conversations, and sends
  periodic group digests.

The full architecture, data model, and design rationale are in
[docs/SPEC.md](docs/SPEC.md).

**Stack:** Quarkus 3.33 LTS · Java 25 · PostgreSQL + `pgvector` ·
quarkus-langchain4j. The LLM is pluggable: a local model via **Ollama** (the
default) or **llama.cpp**, or a remote **OpenAI-compatible** or **Anthropic** API
endpoint.

## Security & privacy posture

- **Self-hosted.** You run both services and the database on your own
  infrastructure. There is no infochat cloud.
- **Invite-gated.** Direct-message access requires an invite code issued by an
  admin; new users start in a slow-start probation period.
- **Private by construction.** Per-user, per-scope isolation; the collector has
  no user-facing API; source post bodies are never sent to a translator.
- **You choose where the AI runs.** With the default local model, no post or
  message content leaves your machine. Opting into a remote LLM (the
  `remote-llm` profile, or routing any task to a cloud API) is an explicit choice
  that sends the content being processed to that third-party provider — public
  post bodies for the ingest tasks, and your private chat messages if you route
  chat — and the setup wizard spells out exactly what each task exposes before
  you enable it.
- **Hardened egress.** Outbound fetches run behind an SSRF guard
  (IP-range blocklist, DNS-rebind protection, redirect and header scrubbing).
- **Deterministic authorization.** Admin operations run in plain Java and are
  **never** exposed as LLM tools.

The full threat model and trust boundaries are in
[docs/spec/security.md](docs/spec/security.md).

Found a security problem? Please report it privately — see
[SECURITY.md](SECURITY.md). Don't open a public issue for vulnerabilities.

---

## Quick start

Pick the path that matches what you want to do:

- **Run a deployment** → **[SETUP_GUIDE.md](SETUP_GUIDE.md)**. An interactive
  wizard builds and configures the containers for you; no Maven required.
- **Work on the code** → **[DEVELOPER.md](DEVELOPER.md)**. Build from source and
  run both services in Quarkus dev mode against a local PostgreSQL + Ollama.

## License

infochat is released under the **[MIT License](LICENSE)** — you may use, copy,
modify, distribute, and sell it freely; the only condition is that the
copyright and license notice travel with copies of the source.

The MIT license covers infochat's own code. Bundled dependencies
(Quarkus, langchain4j, and others) are distributed under their own licenses.
