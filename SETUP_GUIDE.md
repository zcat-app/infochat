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

### One thing the wizard can't do for you: a chat account for the bot

infochat talks to you through a messaging app. The bot needs its **own**
account on that app, which you set up once, by hand, before running the wizard:

- **SimpleX (recommended, no phone number):** install the `simplex-chat`
  program and create a messaging address for the bot. SimpleX is the most
  private option — it doesn't need a phone number or any personal detail.
- **Signal:** install `signal-cli` and register a **phone number** for the bot
  (a spare number or one you control). Signal requires a phone number.

You can use either one, or both. The wizard will later ask you where these
programs live and what the bot's account is — so just note those down. Detailed
account-creation steps for each are in the
[deployment design notes](docs/design/07-deployment.md) (§7.7.2 / §7.9).

> **Honest note:** everything *about infochat itself* is automated by the
> wizard. The only manual part is creating that messaging account, because it
> belongs to SimpleX/Signal, not to us.

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
| **llamacpp** | Advanced users who want a specific model | A download link to a model file (a "GGUF" URL) | Free, fully private |
| **remote** | Best quality / weak hardware | A cloud AI account and key (any OpenAI-compatible API) | Costs money; your prompts go to that provider |

Not sure? Choose **ollama** — it just works and keeps everything on your
machine. (Note: if you picked the **remote-llm** profile in step 1, you must
choose **remote** here.)

### Step 6 — Which messaging app?

Choose **simplex**, **signal**, or both (type them comma-separated, e.g.
`simplex,signal`). The wizard then asks, for each:

- where the program (`simplex-chat` / `signal-cli`) is installed,
- where the bot's account data lives,
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
simplex-chat binary path [...]:       ⏎  (Enter if it's in the default place)
SimpleX data-dir [...]:               ⏎
SimpleX WebSocket port [5225]:        ⏎
Bootstrap admin contact id:           <paste your SimpleX address here>
```

Everything else runs automatically. When step 8 prints a green "healthy"
summary, you're done.

---

## After setup

- **It's running!** infochat is now live in the background. Message the bot from
  your messaging app to try it — start with the [User Guide](USER_GUIDE.md).
- **To stop or restart everything**, or to wipe it and start fresh, run:

  ```bash
  ./prod/setup.sh --reset
  ```

  It will offer to also delete the database (your stored posts). Say **no** to
  that unless you really want a clean slate.
- **To re-run setup** (e.g. to add Signal later), just run `./prod/setup.sh`
  again.

---

## Troubleshooting

> This section is a living list. The hints below come from known rough edges;
> we'll add more as real-world issues surface. If you hit something not listed,
> the [deployment notes](docs/spec/deployment.md) go deeper.

| Symptom | Likely cause & fix |
|---|---|
| **The wizard stops at step 0 saying a tool is missing** | Install whatever it names (usually Docker or the Docker Compose plugin), then re-run `./prod/setup.sh`. |
| **"port 5432 is in use"** | Another PostgreSQL (or a previous infochat) is already using the database port. Stop it, or run `./prod/setup.sh --reset` to clean up a previous attempt. |
| **"not enough disk space"** | Free up space until you have at least ~15 GB, then re-run. Old Docker images can be cleared with `docker system prune`. |
| **Step 7 seems frozen for several minutes** | This is normal on the **first** run — it's building infochat and can take 5+ minutes. Let it finish. |
| **Connections to the database/AI "time out" or "reset" for no clear reason** | If you run a **VPN**, it may be silently blocking local (localhost) traffic between the containers. Try turning the VPN off, or allow loopback traffic, then re-run. *(This one has cost people hours — check it early.)* |
| **The local AI model won't download (ollama)** | The download needs internet access to Ollama's model registry. Check your connection / proxy and re-run step 4. |
| **You chose a "remote" AI but it fails to connect** | Double-check the API address and key. Remote and `llamacpp` setups can't be done with `--defaults` — they need you to type the values in. |
| **Wizard refuses to finish: "no bootstrap admin contact id"** | You must give at least one admin contact id in step 6 (see [the admin question](#the-one-question-you-must-not-skip-whos-the-admin)). Re-run and provide it. |
| **You provided your own model file (llamacpp) and it's rejected** | If you also entered a checksum, the file must match it. Re-check the download URL and checksum. |
| **Step 8 says a service is "DEGRADED"** | Often harmless — usually one messaging adapter hasn't finished connecting yet. Give it a minute; if it stays down, check that the bot's messaging account (step 6 paths) is correct. |

---

## Advanced (technical details)

*Everything below is optional. Skip it unless you want to customise or
understand the internals.*

### Non-interactive and reset modes

```bash
./prod/setup.sh --defaults   # take every default, no prompts (CI / scripted installs)
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

### Compose profiles

The stack is gated behind Docker Compose profiles: `prod` (Collector +
Provider), `ollama` and `llamacpp` (the two local LLM backends), and `dev`
(database + Ollama for running the apps in Quarkus dev mode on the host). The
wizard activates the right ones per step.

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
