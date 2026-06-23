# Setup Guide

This guide gets infochat running on your own machine or server. **You do not
need to be a programmer.** If you can install a game that needs a launcher, you
can do this. A guided wizard does the hard parts — it asks you a handful of
plain questions and sets everything up for you.

Plan for about **30 minutes** the first time (most of it is the computer
downloading and building things while you wait).

> **New to all this?** Read the [main sections](#before-you-start) top to
> bottom and ignore the [Advanced](#advanced-technical-details) section at the
> end — that's only for people who want to customise things.

---

## Table of contents

- [Before you start (prerequisites)](#before-you-start)
- [The easy path: run the wizard](#the-easy-path-run-the-wizard)
- [What the wizard asks you](#what-the-wizard-asks-you)
- [Choosing your settings](#choosing-your-settings)
- [After setup](#after-setup)
- [Troubleshooting](#troubleshooting)
- [Advanced (technical details)](#advanced-technical-details)
- [Where to go next](#where-to-go-next)

---

## Before you start

### What kind of computer you need

infochat runs on **Linux** (a regular Linux PC, a home server, a Raspberry Pi,
or a rented cloud server all work). You'll need:

- **About 15 GB of free disk space** (for the program, the database, and one
  small AI model).
- A reasonably modern processor and a few GB of free memory. A laptop from the
  last few years is fine.

### Software you must install first

The wizard checks for these at the start and tells you exactly which one is
missing if any are. Install them before you begin:

| You need | What it is | How to check it's installed |
|---|---|---|
| **Docker** | Runs infochat in tidy, self-contained boxes ("containers") so it doesn't clutter your system | `docker info` prints without an error |
| **Docker Compose** (v2) | Starts all the pieces together | `docker compose version` prints a version |
| A few small tools: **openssl, curl, ss, df** | Standard Linux utilities (usually already present) | the wizard checks them for you |

> Most Linux systems already have everything except Docker. Installing **Docker
> Desktop** (or Docker Engine + the Compose plugin) covers the first two rows.
> See https://docs.docker.com/get-docker/

### Setting up the bot's chat account

infochat talks to you through a messaging app, and the bot needs its **own**
account on that app. How much you do by hand depends on which app you choose:

- **SimpleX (recommended, no phone number):** nothing to do by hand. The
  `simplex-chat` program ships baked into infochat's container image, and the
  wizard provisions the bot's profile, contact address, and auto-accept of
  incoming connections **for you** (step 7). All you do is pick a **display
  name** for the bot when the wizard asks in step 6, and press Enter to accept
  the default data directory. SimpleX is the most private option — no phone
  number or personal detail. When provisioning finishes, the wizard prints the
  bot's contact link so you can connect to it.

  > **Why auto-accept is safe:** it only opens the chat connection — it does
  > **not** bypass invite gating; an un-invited contact still gets the "you need
  > an invite" reply.
- **Signal:** install `signal-cli` and register a **phone number** for the bot
  (a spare number or one you control — Signal requires a phone number). This part
  the wizard can't automate, because Signal may ask you to solve a captcha:

  ```bash
  signal-cli -a +<bot-number> register       # follow the captcha link it prints
  signal-cli -a +<bot-number> verify <code>  # <code> arrives by SMS
  ```

  The full procedure, including the captcha step, is in the signal-cli
  Quickstart: https://github.com/AsamK/signal-cli/wiki/Quickstart

You can use either one, or both. For SimpleX you need nothing in advance; for
Signal, register the number first and jot down where `signal-cli` lives, its data
directory, and the bot's number, since the wizard asks for those in step 6.

> **Honest note:** for SimpleX, everything is automated by the wizard. The only
> remaining manual step is registering a Signal phone number, because that
> account belongs to Signal (and its captcha), not to us.

---

## The easy path: run the wizard

1. Download infochat (clone the repository or unzip a copy) and open a terminal
   in its folder.
2. Run:

   ```bash
   ./prod/setup.sh
   ```

3. Answer the questions it asks (the next section explains each one). For most
   of them you can just press **Enter** to accept the sensible default.

That's it. The wizard builds infochat, starts the database, downloads an AI
model (or connects to one), wires up your messaging app, and finally checks
that everything is healthy.

**If it stops partway** (you closed the terminal, the internet dropped, a step
failed) — just run `./prod/setup.sh` again. It remembers what it already
finished and **picks up where it left off**. It won't redo completed steps or
overwrite your answers.

---

## What the wizard asks you

The wizard runs nine short steps in order. Most need no input — they just do
their job and move on. Here's the whole journey in plain language:

| Step | What happens | Does it ask you anything? |
|---|---|---|
| **0. Health check** | Confirms your computer has Docker, the disk space, and a free database port | No |
| **1. Profile** | Picks settings to match your hardware | Yes — pick `laptop`, `vps`, `pi`, or `remote-llm` (default `laptop`) |
| **2. Secrets** | Creates strong random passwords for the database | Optional — a remote AI key, only if you'll use one |
| **3. Database** | Starts the database and waits until it's ready | No |
| **4. AI model** | Sets up the AI brain | Yes — pick `ollama`, `llamacpp`, or `remote` (default `ollama`) |
| **5. Sources** | Installs a starter list of news/social sources | Optional — a path for price-command data, otherwise skipped |
| **6. Messaging** | Connects your SimpleX and/or Signal account | Yes — which app(s), where the program is, and the bot's account |
| **7. Start apps** | Builds and launches infochat (this is the slow one) | No |
| **8. Verify** | Confirms infochat is up and healthy | No |

The three steps that actually need a decision from you are **1 (Profile)**,
**4 (AI model)**, and **6 (Messaging)** — covered next.

### The one question you must not skip: who's the admin?

In step 6, the wizard asks for a **bootstrap admin contact id** for your
messaging app. This makes *you* the bot's administrator. **You must provide at
least one** — if you don't, infochat refuses to start (this is a safety guard,
so a deployment never launches with nobody in charge). Getting your own contact
id and what admin powers it grants are covered in the
[Admin Guide](ADMIN_GUIDE.md).

---

## Choosing your settings

### Step 1 — Which profile?

The profile tunes infochat for your hardware. Pick the one that matches where
you're running it:

| Pick this | If you're… | What it does |
|---|---|---|
| **laptop** *(default)* | Trying it out on your own computer | Balanced small AI models for a personal machine |
| **vps** | Running it 24/7 on a rented server | Tuned for a dedicated always-on box |
| **pi** | Running on a Raspberry Pi or low-power device | The smallest, lightest AI models |
| **remote-llm** | Using your machine, but letting a cloud service do the AI | No local AI model; you'll provide an API in step 4 |

Not sure? Choose **laptop**. You can change it later.

### Step 4 — Which AI model?

infochat needs an AI model to read and summarise posts. Three ways to provide
one:

| Pick this | Best for | What you need | Cost & privacy |
|---|---|---|---|
| **ollama** *(default, easiest)* | Most people | Nothing — it downloads a model for you (~5 GB) | Free, fully private (runs on your machine) |
| **llamacpp** | Advanced users who want a specific model | Nothing for the defaults — it uses pinned, checksum-verified models (~4.5 GB); advanced users can paste their own model ("GGUF") URLs | Free, fully private |
| **remote** | Best quality / weak hardware | A cloud AI account and key (any OpenAI-compatible API) | Costs money; your prompts go to that provider |

Not sure? Choose **ollama** — it just works and keeps everything on your
machine. (Note: if you picked the **remote-llm** profile in step 1, you must
choose **remote** here.)

If you pick **llamacpp**, the wizard first offers a pinned, checksum-verified
default chat model — a gemma "GGUF". Press Enter to accept it, or paste your own
GGUF URL to override (a custom chat model is unrestricted). It then asks how to
run **embeddings** (the part that lets the bot match posts by meaning): a second
llama.cpp model (`llamacpp`, the default) or Ollama running alongside (`ollama`).
If you keep `llamacpp`, it offers a pinned nomic embeddings model the same way (a
custom embeddings model must produce 768-dimensional vectors, so the wizard asks
you to confirm); if you pick `ollama`, there is no model prompt. Either way,
embeddings always run separately from the chat model.

### Step 5 — Your sources (optional customization)

infochat comes with a starter list of news and social feeds, and the wizard
installs it for you — by default there's nothing to do here. Two things you
*can* customize:

**Your own feeds.** To start with your feeds instead of (or alongside) the
defaults, edit `prod/config/bootstrap-sources.json` **before** you run the
wizard. Each entry is one feed — its kind (RSS, Bluesky, Reddit, YouTube, and
more), its URL or handle, a name, a category, and one or more tags. The full
format, with a worked example for each kind, is in
[docs/design/07-deployment.md §7.6.1](docs/design/07-deployment.md). The wizard
copies this file into place once and never overwrites it, so your edits stick.
(You can also add feeds one at a time later with `/add-source` — see the
[User Guide](USER_GUIDE.md).)

**Price commands (off by default).** Step 5 also asks for an optional
*bootstrap-assets* file path. Leave it blank to skip the price commands
(`/zcash`, `/monero`, …) — they stay disabled and `/help` won't list them. To
turn them on, you write a small JSON file listing the assets and price sources
you want; there's no ready-made one. Copy the worked example from
[docs/design/10-asset-commands.md §10.6](docs/design/10-asset-commands.md), save
it (e.g. as `prod/config/bootstrap-assets.json`), and give the wizard that path.
You can add the file and re-run the wizard later if you skip it now.

### Step 6 — Which messaging app?

Choose **simplex**, **signal**, or both (type them comma-separated, e.g.
`simplex,signal`). The wizard then asks, for each:

- where the program (`simplex-chat` / `signal-cli`) is installed,
- where the bot's account data lives,
- for SimpleX, a **display name** for the bot (the wizard then provisions the
  SimpleX profile, address, and auto-accept for you in step 7),
- for Signal, the bot's **phone number**,
- and the **admin contact id** that makes you the administrator.

Prefer **SimpleX** if you value privacy and don't want to use a phone number.

### A complete example (the most common setup)

Trying infochat on your own Linux laptop, using the free local AI and SimpleX:

```text
Hardware profile [laptop]:            ⏎  (just press Enter)
Remote LLM API key [blank]:           ⏎  (Enter — we're using local AI)
LLM backend [ollama]:                 ⏎  (Enter — downloads a local model)
Optional bootstrap-assets path:       ⏎  (Enter — skip price commands for now)
Enable which adapters [simplex]:      ⏎  (Enter — SimpleX)
simplex-chat binary path [...]:       ⏎  (Enter — the image bakes it)
SimpleX data-dir [...]:               ⏎
SimpleX WebSocket port [5225]:        ⏎
SimpleX bot display name [infochat-bot]: ⏎  (or type a name for the bot)
Bootstrap admin contact id:           <paste your SimpleX address here>
```

Everything else runs automatically. When step 8 prints a green "healthy"
summary, you're done.

---

## After setup

- **It's running!** infochat is now live in the background. To try it, first
  connect to the bot (see
  [below](#connecting-to-the-bot-for-the-first-time)), then start with the
  [User Guide](USER_GUIDE.md).
- **To stop or restart everything**, or to wipe it and start fresh, run:

  ```bash
  ./prod/setup.sh --reset
  ```

  It will offer to also delete the database (your stored posts). Say **no** to
  that unless you really want a clean slate.
- **To re-run setup** (e.g. to add Signal later), just run `./prod/setup.sh`
  again.

### Connecting to the bot for the first time

How you reach the bot depends on your app:

- **Signal:** send a direct message to the bot's phone number from your own
  Signal app. No connection step is needed.
- **SimpleX:** connect to the bot's address once — the contact link the wizard
  printed during setup (step 7, SimpleX provisioning). From your **personal**
  SimpleX app or CLI, make a contact request to it: in the CLI that's
  `/c <bot-address>`; in the mobile/desktop app, tap "Connect" and paste the
  link. The bot auto-accepts and you're connected.

You are the **bootstrap admin**, so you do **not** need an invite code — once
connected, just message the bot (try `/help`). Everyone else needs an invite you
issue with `/invite` (see the [Admin Guide](ADMIN_GUIDE.md)).

### Back up your data

infochat keeps real state on your machine — back it up regularly, and keep the
copies **encrypted at rest**. Three things matter:

- **The database** — your posts, users, saved items, settings, and the audit
  log. It lives in the Docker volume `infochat-pgdata`.
- **The bot's messaging identity** — the SimpleX / Signal data directories you
  chose in step 6. **If you lose these you lose the bot's account for good**: a
  SimpleX queue keypair cannot be regenerated for the same address, and Signal
  re-registration is an external, out-of-band process.
- **Your configuration and secrets** — `prod/runtime/application.properties` and
  `prod/runtime/secrets.env` (database passwords, any LLM API key, admin contact
  ids).

infochat ships a backup script that captures all three for you —
`prod/scripts/backup.sh`. It writes a database dump plus a tar of the bot's
messaging-identity directories into a backup folder (default `/backups`).
Schedule it from cron, with two independent lines that delete backups older than
two weeks:

```
0 3 * * * /srv/infochat/prod/scripts/backup.sh
0 4 * * * find /backups -name 'infochat-*.pgc' -mtime +14 -delete
0 4 * * * find /backups -name 'adapters-*.tgz' -mtime +14 -delete
```

Keep the backup folder **encrypted at rest** — it holds the audit log and the
irreplaceable identity keys. The full operator runbook — the restore procedure,
recommended frequency, and recovery scenarios — is in
[docs/design/07-deployment.md §7.10 Backups](docs/design/07-deployment.md) and
§7.15 Disaster scenarios.

### Switching your AI backend later

Already set up, but want to change where the AI runs — for example, move to a
cloud (remote) API because your machine is too small for a good local model?
Use the switcher (you don't re-run the whole wizard):

```bash
./prod/switch-llm.sh
```

It asks, for each AI task, which backend to use — `remote` (a cloud API),
`ollama`, or `llamacpp` — defaulting to whatever that task uses now. Press Enter
to keep a task as-is; pressing Enter for everything changes nothing. It never
touches **embeddings** (the "match posts by meaning" model stays local and fixed,
because changing it would break your stored posts).

A sample session moving just the chat task to a cloud API:

```text
Backend for security (remote|ollama|llamacpp) [ollama]:    ⏎  (keep local)
Backend for tagger (remote|ollama|llamacpp) [ollama]:      ⏎
Backend for entity (remote|ollama|llamacpp) [ollama]:      ⏎
Backend for summarizer (remote|ollama|llamacpp) [ollama]:  ⏎
Backend for chat (remote|ollama|llamacpp) [ollama]:        remote
  chat remote base-url (e.g. https://nano-gpt.com/api/v1): https://nano-gpt.com/api/v1
  chat model [llama3.1:8b]:                                gpt-4o-mini
Backend for translator (remote|ollama|llamacpp) [ollama]:  ⏎
Remote LLM API key:                                        (paste, hidden)
```

Before writing anything it backs up your config and prints a **rollback**
command, then prints a **privacy disclosure** naming exactly which tasks now go
to the remote provider and what each one exposes — for the run above:

```text
Backed up before writing:
  .../runtime/application.properties.bak.20260621-120000
Rollback (undo this run):
  cp '.../application.properties.bak.20260621-120000' '.../application.properties'

PRIVACY DISCLOSURE — these tasks now call a REMOTE provider:
  !! chat — YOUR PRIVATE MESSAGES to the bot are sent to the remote provider.
           This is the most sensitive exposure: your direct conversations.
```

The disclosure is honest about the difference: **chat** sends your private
messages to the provider (the loudest warning), while the ingest tasks
(`security`/`tagger`/`entity`) only ever see the **public** posts infochat
fetches — they expose your topic interests and source list, not private data.
Finally it prints the command to apply the change (recreating the containers, so
the new key takes effect):

```bash
docker compose -f docker-compose.yml --env-file prod/runtime/secrets.env --profile prod up -d collector provider
```

**Two worked examples:**

- **All generative tasks remote, embeddings stay local.** Choose `remote` for
  every prompt (`security` through `translator`), giving the same base-url, model,
  and API key each time. Embeddings keep running on your machine — the switcher
  doesn't ask about them. Best when you want top-quality summaries and chat but
  are happy to keep the lightweight embedding model local.
- **Raspberry Pi: everything remote except embeddings.** A Pi is too weak for a
  good chat model, so route all six generative tasks to a cloud API as above. The
  small 768-dimensional embedder still runs locally on the Pi (it's cheap), so
  your stored posts and "match by meaning" search never leave the device, while
  the heavy generation happens in the cloud.

---

## Troubleshooting

> This section is a living list. The hints below come from known rough edges;
> we'll add more as real-world issues surface. If you hit something not listed,
> the [deployment notes](docs/spec/deployment.md) go deeper.

| Symptom | Likely cause & fix |
|---|---|
| **The wizard stops at step 0 saying a tool is missing** | Install whatever it names (usually Docker or the Docker Compose plugin), then re-run `./prod/setup.sh`. |
| **Docker is installed but the wizard says the daemon is "not reachable" (or "permission denied")** | Your user isn't in the `docker` group yet. Run `sudo usermod -aG docker $USER`, then **log out and back in** (or `newgrp docker` for just the current shell), and re-run `./prod/setup.sh`. Confirm with `docker info` — it should print without a permission error. |
| **"Docker Compose v2 not available" and your package manager has no `docker-compose-plugin`** | On distro-packaged Docker (not Docker's official apt repo) the Compose v2 plugin often isn't installable with `apt`. Install the plugin binary by hand: download the `docker-compose` binary from the [docker/compose releases](https://github.com/docker/compose/releases) into `/usr/local/lib/docker/cli-plugins/docker-compose`, `chmod +x` it, then check `docker compose version`. Paste the download URL as a **single line** — a line-wrapped URL produces a "Malformed input to a URL" error (set `URL=...` first if your terminal wraps it). |
| **"port 5432 is in use"** | Another PostgreSQL (or a previous infochat) is already using the database port. Stop it, or run `./prod/setup.sh --reset` to clean up a previous attempt. |
| **"not enough disk space"** | Free up space until you have at least ~15 GB, then re-run. Old Docker images can be cleared with `docker system prune`. |
| **Step 7 seems frozen for several minutes** | This is normal on the **first** run — it's building infochat and can take 5+ minutes. Let it finish. |
| **Connections to the database/AI "time out" or "reset" for no clear reason** | If you run a **VPN**, it may be silently blocking local (localhost) traffic between the containers. Try turning the VPN off, or allow loopback traffic, then re-run. *(This one has cost people hours — check it early.)* |
| **The local AI model won't download (ollama)** | The download needs internet access to Ollama's model registry. Check your connection / proxy and re-run step 4. |
| **You chose a "remote" AI but it fails to connect** | Double-check the API address and key. Remote and `llamacpp` setups can't be done with `--defaults` — they need you to type the values in. |
| **Wizard refuses to finish: "no bootstrap admin contact id"** | You must give at least one admin contact id in step 6 (see [the admin question](#the-one-question-you-must-not-skip-whos-the-admin)). Re-run and provide it. |
| **You provided your own model file (llamacpp) and it's rejected** | The pinned default models are checksum-verified automatically. For a custom URL: if you entered a checksum the file must match it (re-check the URL and checksum), and a custom *embeddings* model must be 768-dimensional. |
| **Step 8 says a service is "DEGRADED"** | Often harmless — usually one messaging adapter hasn't finished connecting yet. Give it a minute; if it stays down, check that the bot's messaging account (step 6 paths) is correct. |
| **A service exits right away saying another instance is "already running"** | You started a second Collector or Provider against the same database. infochat allows only one of each — stop the extra copy. See [Run only one copy of each service](#run-only-one-copy-of-each-service). |

---

## Advanced (technical details)

*Everything below is optional. Skip it unless you want to customise or
understand the internals.*

### Non-interactive and reset modes

```bash
./prod/setup.sh --defaults   # take every default; still prompts for the mandatory admin contact id (CI / scripted installs)
./prod/setup.sh --reset      # docker compose down + clear wizard state
./prod/setup.sh --help       # list all steps and options
```

`--defaults` cannot pick a custom model file or a remote API endpoint (those
require interactive input), so it only works with the **ollama** backend. The
wizard records progress in a git-ignored `prod/runtime/.setup-state` file and
resumes from the first incomplete step.

### What gets written where

The wizard writes only to `prod/runtime/` (git-ignored):

- `prod/runtime/application.properties` — the generated configuration (profile,
  LLM endpoints, adapter blocks).
- `prod/runtime/secrets.env` — generated DB passwords, the optional LLM API
  key, adapter admin contact ids, and adapter data-dir paths. Created with
  `0600` permissions and fed to Docker Compose via `--env-file` (never sourced
  into a shell), so pasted values containing `#`, `$`, or `&` can't break or
  execute. The committed template is `prod/config/secrets.env.example`.

### Ports and the loopback rule

Nothing is exposed to your network by default — this is deliberate.

| Service | Port | Binding |
|---|---|---|
| PostgreSQL | 5432 | `127.0.0.1` (loopback only) — **must stay loopback**; publishing it to `0.0.0.0` would expose the database to your LAN |
| Collector health | 8080 | in-container loopback only (not published) |
| Provider health | 8081 | in-container loopback only (not published) |
| Ollama (dev) | 11434 | `127.0.0.1` (loopback only) |
| SimpleX WebSocket | 5225 (default) | loopback only — the Provider talks to `simplex-chat` as a local subprocess |

The messaging programs (`simplex-chat`, `signal-cli`) run as local subprocesses
with no network port of their own — the trust boundary is the local machine.

### Run only one copy of each service

infochat v1 is designed for **exactly one Collector and exactly one Provider**
against a single database. Running a second copy of either — for example, trying
to scale out for more throughput — would cause duplicate fetches, duplicate
digests, and database contention, so the services don't allow it: each takes a
PostgreSQL advisory lock at startup, and a second instance **exits immediately
with a fatal "another instance is already running" message** that names the live
one. To handle more load, pick a heavier hardware `profile` rather than adding
replicas. The rationale and the rolling-upgrade caveat are in
[docs/spec/architecture.md](docs/spec/architecture.md) §Deployment topology and
[docs/design/07-deployment.md §7.11](docs/design/07-deployment.md).

### Compose profiles

The stack is gated behind Docker Compose profiles: `prod` (Collector +
Provider); `ollama`, `llamacpp`, and `llamacpp-embeddings` (the local LLM
backends — `llamacpp-embeddings` is the second llama.cpp instance used when you
run embeddings on llama.cpp rather than Ollama); and `dev` (database + Ollama for
running the apps in Quarkus dev mode on the host). The wizard activates the right
ones per step.

### Checking the local AI model is serving (llama.cpp)

The automated tests pin the generated wiring, but they do not start a real model
server. After a **llamacpp** setup finishes, you can confirm the model is
actually answering from inside the compose network:

```bash
docker compose --env-file prod/runtime/secrets.env --profile prod --profile llamacpp \
  exec llamacpp curl -fsS http://127.0.0.1:8080/v1/models
```

A JSON response naming the loaded model means llama.cpp is serving. If you chose
llama.cpp for embeddings, repeat with `--profile llamacpp-embeddings` against the
`llamacpp-embeddings` service.

### Developer mode (run from source, no containers)

If you are working on the code rather than running a deployment, skip the wizard
and build from source — the `dev` Compose profile provides PostgreSQL + Ollama
and you run each service with `quarkus:dev`. The full walkthrough (prerequisites,
DB passwords, build, run order, tests, module layout) is in
**[DEVELOPER.md](DEVELOPER.md)**.

---

## Where to go next

- **[Admin Guide](ADMIN_GUIDE.md)** — become the admin, invite users, moderate.
- **[User Guide](USER_GUIDE.md)** — talk to the bot, manage sources, run commands.
- **[Technical map (docs/SPEC.md)](docs/SPEC.md)** — architecture and design.
- **[End-to-end test plan (docs/testing/USER_TEST_PLAN.md)](docs/testing/USER_TEST_PLAN.md)**
  — how to verify a deployment from setup through usage, including copy-paste
  `psql` health-check queries (the observability runbook).
