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

- [Quickstart (the default path)](#quickstart-the-default-path)
- [Before you start (prerequisites)](#before-you-start)
- [The easy path: run the wizard](#the-easy-path-run-the-wizard)
- [What the wizard asks you](#what-the-wizard-asks-you)
- [Choosing your settings](#choosing-your-settings)
- [After setup](#after-setup)
- [Troubleshooting](#troubleshooting)
- [Advanced (technical details)](#advanced-technical-details)
- [Where to go next](#where-to-go-next)

---

## Quickstart (the default path)

The most common setup — **SimpleX** (no phone number) + the free local **Ollama**
AI, on one Linux machine — is essentially one command and two chat messages.
Everything after this section is detail, options, and the Signal / remote-AI /
advanced paths; read on only if you need them.

1. **Clone** infochat and open a terminal in its folder.
2. **Run the wizard**, pressing **Enter** at each prompt to take the defaults:
   ```bash
   ./prod/setup.sh
   ```
   At **step 6** it asks for a secret **claim-token** — type one and keep it safe.
3. When **step 8** prints a green **healthy** summary, infochat is live.
4. **Connect** to the bot (the wizard prints its contact link) and **DM it your
   claim-token** — that first message makes you the admin.
5. **Unset the token** afterward so it can't be reused (one line in
   `secrets.env` + restart — see
   [Connecting to the bot](#connecting-to-the-bot-for-the-first-time)).

The only prerequisite is **Docker**. That's the whole happy path.

---

## Before you start

### What kind of computer you need

infochat runs on **Linux** (a regular Linux PC, a home server, a Raspberry Pi,
or a rented cloud server all work). You'll need:

- **About 15 GB of free disk space** (for the program, the database, and a few
  small AI models).
- A reasonably modern processor and a few GB of free memory. A laptop from the
  last few years is fine.

### Software you must install first

The wizard checks for these at the start and, in a single pass, lists every one
that is missing or misconfigured — each with the command to fix it — so you can
resolve them all at once rather than one re-run at a time. Install them before
you begin:

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
  the default data directory (the wizard creates it under `prod/runtime`, so
  there is nothing to set up by hand). SimpleX is the most private option — no phone
  number or personal detail. When provisioning finishes, the wizard prints the
  bot's contact link so you can connect to it.

  > **Why auto-accept is safe:** it only opens the chat connection — it does
  > **not** bypass invite gating; an un-invited contact still gets the "you need
  > an invite" reply.
- **Signal:** `signal-cli` ships **baked into infochat's container image** too,
  exactly like `simplex-chat` — you do **not** install it on your host. The one
  thing the wizard can't automate is **registering the bot's phone number**. This
  must be a **dedicated** number — a spare SIM or a VoIP number that can receive an
  SMS/voice code — and **not a number already used for your own personal Signal**,
  because Signal allows only **one account per number** (pointing the bot at your
  personal number would take it over). The wizard can't do this for you because
  Signal makes you solve a captcha that can't be scripted. You register it once, out-of-band, by
  running `signal-cli` against the bot's data directory. A **fresh** number needs
  the `--captcha` step (a plain `register` errors, telling you to fetch a captcha
  token first), then a `verify` with the code Signal SMSes you:

  ```bash
  # uses the signal-cli baked into the Provider image (build it first if needed:
  # `docker compose --profile prod build infochat-provider`)
  docker compose --profile prod --env-file prod/runtime/secrets.env \
    run --rm --no-deps --entrypoint /usr/local/bin/signal-cli infochat-provider \
    -a +<bot-number> register --captcha <token-from-the-captcha-link>
  # then, with the SMS code:
  #   ... --entrypoint /usr/local/bin/signal-cli infochat-provider -a +<bot-number> verify <code>
  ```

  (A host-installed `signal-cli` works the same way — same `register --captcha`
  / `verify` commands against the same data directory. The full procedure is in
  the deployment notes, docs/design/07-deployment.md §7.7, and the signal-cli
  Quickstart: https://github.com/AsamK/signal-cli/wiki/Quickstart)

  **Two different values — don't mix them up.** The bot's **account** is its
  **phone number** (the one you just registered). **Your** admin identity is your
  Signal **contact id (ACI)** — a UUID, *not* a phone number — which you hand the
  wizard at the step-6 admin prompt.

You can use either one, or both. For SimpleX you need nothing in advance. For
Signal, the wizard's step 6 captures the bot's data directory and number (the
`signal-cli` binary path is already the in-image default — just press Enter); you
complete the captcha registration out-of-band, either with a host `signal-cli` or
with the bundled one once the image is built. The Provider reads the registered
account from that data directory at startup, so it just needs to exist before the
Signal adapter comes up.

> **Honest note:** for SimpleX, everything is automated by the wizard. For Signal,
> both client binaries are baked into the image, so the only remaining manual step
> is registering the bot's phone number — that account belongs to Signal (and its
> captcha), not to us.
>
> For the deeper picture — why "verified in the Signal app" is **not** the same
> as "verified in `signal-cli`," where the account's keys actually live, and how
> to move the bot onto a different number or identity later — see [Bot chat
> identity](#bot-chat-identity-where-it-lives-and-how-to-change-it) in the
> Advanced section.

> **Advanced alternative (possible, but NOT recommended or supported): link
> instead of register.** `signal-cli` can also run as a *linked secondary device*
> of a number you registered in the normal Signal **phone app** — the same
> mechanism as Signal Desktop: register the dedicated number on a phone, then
> `signal-cli link` the bot to it (instead of `register`/`verify` above). This
> works at the `signal-cli` level, but infochat does **not** test or support it —
> the adapter assumes `signal-cli` is the account's **primary** device with the
> identity keys in the data-dir. If you go this route anyway, know the trade-offs:
> the **phone stays the primary device** and must remain registered (reset or
> unregister it and the linked bot stops working); the account is **shared** —
> every message lands on **both** the phone and the bot, so use it **only** with a
> dedicated number, never your personal account; and a linked device only receives
> messages sent **after** it was linked. Prefer the register-as-primary flow above
> unless you specifically need the number usable on a phone as well; the wizard,
> the runbook, and the data-dir contract all assume the primary-registration path.

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
| **2. Secrets** | Creates strong random passwords for the database | No |
| **3. Database** | Starts the database and waits until it's ready | No |
| **4. AI model** | Sets up the AI brain | Yes — pick `ollama`, `llamacpp`, or `remote` (default `ollama`) |
| **5. Sources** | Installs a starter list of news/social sources | Optional — a custom sources file, and whether to enable the price commands (default Yes) |
| **6. Messaging** | Connects your SimpleX and/or Signal account | Yes — which app(s), where the program is, and the bot's account |
| **7. Start apps** | Builds and launches infochat (this is the slow one) | No |
| **8. Verify** | Confirms infochat is up and healthy | No |

The three steps that actually need a decision from you are **1 (Profile)**,
**4 (AI model)**, and **6 (Messaging)** — covered next.

### The one question you must not skip: who's the admin?

In step 6, the wizard asks how you become the bot's administrator. The answer
differs by app, because the two apps prove identity differently:

- **SimpleX** has no phone number or fixed address to point at, so you choose a
  **secret claim-token**. After the bot starts, you DM it that token from your
  own SimpleX app, and that first message makes *you* the admin. Then you **unset
  the token** so a leaked one can never re-claim admin later (exact one-line step
  under [Connecting to the bot](#connecting-to-the-bot-for-the-first-time)).
- **Signal** has a stable account, so you give the wizard your Signal **contact
  id** (ACI) directly and you are the admin from the first start — nothing to
  claim.

**You must provide at least one** (a SimpleX token or a Signal contact id) — if
you don't, infochat refuses to start (this is a safety guard, so a deployment
never launches with nobody in charge). Getting your own contact id and what
admin powers it grants are covered in the [Admin Guide](ADMIN_GUIDE.md).

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
| **ollama** *(default, easiest)* | Most people | Nothing — it downloads the models for you (~5 GB) | Free, fully private (runs on your machine) |
| **llamacpp** | Advanced users who want a specific model | Nothing for the defaults — it uses pinned, checksum-verified models (~4.5 GB); advanced users can paste their own model ("GGUF") URLs | Free, fully private |
| **remote** | Best quality / weak hardware | A cloud AI account and key (any OpenAI-compatible API) | Costs money; your prompts go to that provider |

Not sure? Choose **ollama** — it just works and keeps everything on your
machine. (Note: if you picked the **remote-llm** profile in step 1, you must
choose **remote** here.)

If you pick **llamacpp**, the wizard first offers a pinned, checksum-verified
default chat model — a gemma "GGUF". Press Enter to accept it, or paste your own
GGUF URL to override (a custom chat model is unrestricted). One thing to know:
thinking/reasoning is switched off on the llama.cpp server — a model tuned for
step-by-step "thinking" will still run, it just won't think, and the wizard's
timeout and token-limit recommendations later in this step assume that. It then asks how to
run **embeddings** (the part that lets the bot match posts by meaning): a second
llama.cpp model (`llamacpp`, the default) or Ollama running alongside (`ollama`).
If you keep `llamacpp`, it offers a pinned nomic embeddings model the same way (a
custom embeddings model must produce 768-dimensional vectors, so the wizard asks
you to confirm); if you pick `ollama`, there is no model prompt. Either way,
embeddings always run separately from the chat model.

If you pick **remote**, the wizard asks for your provider's base URL and API key
(the key is stored in `secrets.env`, never in plain config). Only the generative
tasks — chat, summaries, tagging — use the remote provider; **embeddings always
run locally**. The wizard starts a small Ollama alongside and downloads the nomic
embedder for you, so your remote provider does **not** need to offer an
embeddings model, and the post content used to match posts by meaning never
leaves your machine.

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

> **Tags are filter tokens, not free text.** The `name` field is free-form —
> "Z.ai (Twitter)", spaces and capitals and all. But each entry in `tags` must
> be lowercase letters, digits, and hyphens only — no spaces (pattern
> `^[a-z0-9][a-z0-9-]{0,47}$`). A capitalized single word like `"AI"` is fine
> (it's auto-lowercased to `ai`), but a tag with a space like `"GLM AI"` is
> rejected and the Collector **refuses to start**. Write multi-word tags with a
> hyphen instead: `"glm-ai"`.
(You can also add feeds one at a time later with `/add-source` — see the
[User Guide](USER_GUIDE.md).)

**Price commands (on by default).** Step 5 asks `Enable crypto asset commands
(zcash, monero)? [Yes/no]`, defaulting to **Yes**: press Enter and the price
commands (`/zcash`, `/monero`, …) ship enabled, seeded from the bundled
`prod/config/bootstrap-assets.json` (zcash + monero — a ready-made file that's
already there). To turn them off, answer **no** — then they stay disabled and
`/help` won't list them. To offer different assets or price sources, supply your
own JSON file at the custom-path prompt that follows a **Yes**: copy the worked
example from
[docs/design/10-asset-commands.md §10.6](docs/design/10-asset-commands.md), save
it (e.g. as `prod/config/bootstrap-assets.json`), and give the wizard that path.

### Step 6 — Which messaging app?

Choose **simplex**, **signal**, or both (type them comma-separated, e.g.
`simplex,signal`). The wizard then asks, for each:

- where the program (`simplex-chat` / `signal-cli`) is installed,
- where the bot's account data lives,
- for SimpleX, a **display name** for the bot (the wizard then provisions the
  SimpleX profile, address, and auto-accept for you in step 7),
- for Signal, the bot's **phone number**,
- and how you become the administrator: for SimpleX a **secret claim-token**
  you'll DM the bot after it starts (then unset); for Signal your **contact id**
  (ACI) directly. See [the admin question](#the-one-question-you-must-not-skip-whos-the-admin).

Prefer **SimpleX** if you value privacy and don't want to use a phone number.

### A complete example (the most common setup)

Trying infochat on your own Linux laptop, using the free local AI and SimpleX:

```text
Hardware profile [laptop]:            ⏎  (just press Enter)
LLM backend [ollama]:                 ⏎  (Enter — downloads a local model)
Custom bootstrap-sources path [blank]: ⏎  (Enter — use the bundled sources)
Enable crypto asset commands (zcash, monero)? [Yes/no]: ⏎  (Enter — Yes, the default)
Custom bootstrap-assets path [blank]:  ⏎  (Enter — bundled zcash+monero)
Enable which adapters [simplex]:      ⏎  (Enter — SimpleX)
simplex-chat binary path [...]:       ⏎  (Enter — the image bakes it)
SimpleX data-dir [prod/runtime/simplex]: ⏎
SimpleX WebSocket port [5225]:        ⏎
SimpleX bot display name [infochat-bot]: ⏎  (or type a name for the bot)
Bootstrap admin claim-token for simplex: <type a secret token — keep it safe>
```

(The token is hidden as you type. After the bot is up, DM it this exact token
from your own SimpleX app to become admin — then unset it, as described under
[Connecting to the bot](#connecting-to-the-bot-for-the-first-time).)

Everything else runs automatically. When step 8 prints a green "healthy"
summary, you're done.

---

## After setup

- **It's running!** infochat is now live in the background. To try it, first
  connect to the bot (see
  [below](#connecting-to-the-bot-for-the-first-time)), then start with the
  [User Guide](USER_GUIDE.md).
- **To stop, start, or restart the bot** day-to-day, use the lifecycle helper.
  It controls just the two app services and leaves your database and the AI
  model running, so a restart is quick:

  ```bash
  ./prod/scripts/apps.sh stop      # stop the bot (keeps all data)
  ./prod/scripts/apps.sh start     # start it again
  ./prod/scripts/apps.sh restart   # restart it
  ./prod/scripts/apps.sh status    # is it running?
  ```

  Run `restart` after you edit a setting in `prod/runtime/application.properties`
  or one of the bootstrap files — a config change is only picked up when the
  container restarts, so editing the file alone does nothing until you do.
- **To upgrade the bot to the latest code**, run:

  ```bash
  ./prod/scripts/upgrade.sh          # backup, pull main, rebuild, restart
  ./prod/scripts/upgrade.sh -y       # same, unattended (no confirmations)
  ```

  It backs up first, pulls the latest `main`, rebuilds the two app images, and
  restarts the bot — all your data and settings (database, LLM config, secrets,
  messaging identities) are preserved. On a build or health failure it rolls the
  code back automatically. Only run it on a deployment you have already set up.
  Full procedure: `docs/design/07-deployment.md` §7.11.
- **To tear everything down and set up again from scratch**, run:

  ```bash
  ./prod/setup.sh --reset
  ```

  This stops and removes any running infochat containers — **keeping your
  database** (your stored posts) — and then runs setup again from the start. If
  there's nothing to tear down, it just goes straight into setup; it won't print
  removal messages or ask you anything about a deployment that isn't there.

  To **also** wipe the database for a truly clean slate, add `--hard`:

  ```bash
  ./prod/setup.sh --reset --hard   # ALSO deletes all stored posts
  ```

  Either way, the **downloaded AI model is kept** — a reset never re-downloads
  the multi-GB model file. (If you ever do need to free that space, add
  `--wipe-models` to a reset — `./prod/setup.sh --reset --wipe-models` — which
  drops the downloaded-model volumes for you and forces a fresh download on the
  next setup. It combines with `--hard`.)
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

You are the **bootstrap admin**, so you do **not** need an invite code — but how
you claim admin differs by app:

- **Signal:** you're already the admin (the wizard configured your contact id), so
  once you message the bot, just go — try `/help`.
- **SimpleX:** your **first DM to the bot must be the exact claim-token** you set
  in step 6. That message makes you admin (you'll get a welcome reply); after
  that, use the bot normally. Then **unset the token**: blank
  `INFOCHAT_SIMPLEX_ADMIN_TOKEN` in `prod/runtime/secrets.env` and restart, so a
  leaked token can never re-claim admin.

Everyone else needs an invite you issue with `/invite` (see the
[Admin Guide](ADMIN_GUIDE.md)).

### Back up your data

infochat keeps real state on your machine — back it up regularly, and keep the
copies **encrypted at rest**. Three things matter:

- **The database** — your posts, users, saved items, settings, and the audit
  log. It lives in a Docker volume on your machine, and the backup script below
  captures it for you.
- **The bot's messaging identity** — the SimpleX / Signal data directories you
  chose in step 6. **If you lose these you lose the bot's account for good**: a
  SimpleX queue keypair cannot be regenerated for the same address, and Signal
  re-registration is an external, out-of-band process.
- **Your configuration and secrets** — `prod/runtime/application.properties` and
  `prod/runtime/secrets.env` (database passwords, any LLM API key, admin
  credentials — the SimpleX claim-token / Signal contact id).

infochat ships a backup script that captures all three for you —
`prod/scripts/backup.sh`. It writes a database dump plus a tar of the bot's
messaging-identity directories into a backup folder (default
`prod/runtime/backups`; override with a positional argument or
`$INFOCHAT_BACKUP_DIR`). Schedule it from cron — pass the backup directory
**explicitly** so the rotation lines that delete backups older than two weeks
target the **same** folder the dumps are written to:

```
0 3 * * * /srv/infochat/prod/scripts/backup.sh /srv/infochat/backups
0 4 * * * find /srv/infochat/backups -name 'infochat-*.pgc' -mtime +14 -delete
0 4 * * * find /srv/infochat/backups -name 'adapters-*.tgz' -mtime +14 -delete
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

Before writing anything it **backs up** your config and prints a **rollback**
command, then a **privacy disclosure** naming exactly which tasks now call the
remote provider and what each one exposes — loudest for **chat** (it sends your
private messages), versus the ingest tasks (`security`/`tagger`/`entity`), which
only ever see the **public** posts infochat fetches (your topic interests and
source list, not private data). Finally it prints the one command to apply the
change, recreating the containers so the new key takes effect. The switcher is
fully interactive and walks you through each task, so there is nothing to
memorize; the per-task config it writes is documented in
[docs/design/07-deployment.md](docs/design/07-deployment.md).

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
| **Wizard refuses to finish: "no bootstrap admin credential"** | You must give at least one admin credential in step 6 — a SimpleX claim-token or a Signal contact id (see [the admin question](#the-one-question-you-must-not-skip-whos-the-admin)). Re-run and provide it. |
| **You provided your own model file (llamacpp) and it's rejected** | The pinned default models are checksum-verified automatically. For a custom URL: if you entered a checksum the file must match it (re-check the URL and checksum), and a custom *embeddings* model must be 768-dimensional. |
| **Step 8 says a service is "DEGRADED"** | Often harmless — usually one messaging adapter hasn't finished connecting yet. Give it a minute; if it stays down, check that the bot's messaging account (step 6 paths) is correct. |
| **A service exits right away saying another instance is "already running"** | You started a second Collector or Provider against the same database. infochat allows only one of each — stop the extra copy. See [Run only one copy of each service](#run-only-one-copy-of-each-service). |

---

## Advanced (technical details)

*Everything below is optional. Skip it unless you want to customise or
understand the internals.*

### Non-interactive and reset modes

```bash
./prod/setup.sh --defaults             # take every default; still prompts for the mandatory admin credential — SimpleX claim-token / Signal contact id (CI / scripted installs)
./prod/setup.sh --reset                # tear down (keeping data) + clear wizard state, then run setup; no output if nothing to remove
./prod/setup.sh --reset --hard         # same, but ALSO drop the database volume (deletes the database); AI model caches are kept
./prod/setup.sh --reset --wipe-models  # same, but ALSO drop the LLM model-cache volumes (forces a multi-GB re-download next setup)
./prod/setup.sh --help                 # list all steps and options
```

`--hard` and `--wipe-models` are independent reset modifiers — each is valid
only alongside `--reset`, and the two can be combined
(`--reset --hard --wipe-models`) for a total wipe of data **and** models.

`--defaults` cannot pick a custom model file or a remote API endpoint (those
require interactive input), so it only works with the **ollama** backend. The
wizard records progress in a git-ignored `prod/runtime/.setup-state` file and
resumes from the first incomplete step.

### Running a single step or helper script directly

`setup.sh` is only an **orchestrator**: it runs the numbered scripts under
`prod/scripts/` in order. You can also run any one of them on its own — useful
when you want to redo just one step without re-running the whole wizard (re-seed
your sources after editing the JSON, re-show the SimpleX contact link, run the
health check again, and so on). Each accepts `-h`/`--help`.

Three things to know before you do:

- **They build on each other.** A step reads the files earlier steps wrote
  (`prod/runtime/application.properties`, `prod/runtime/secrets.env`), so run a
  step on its own only after the steps before it have already completed.
- **They're idempotent.** Re-running a completed step is safe — it skips values
  already set and never rotates secrets or the SimpleX identity.
- **Direct runs don't update `.setup-state`.** The resume marker is written only
  by `setup.sh`'s loop, so a step you run by hand may be run once more on the
  next `setup.sh` — harmless, because the steps are idempotent.

| Script | What it does | Flags |
|---|---|---|
| `prod/scripts/0-doctor.sh` | Preflight host checks (Docker, free ports, disk) | `--defaults` (no-op — no prompts) |
| `prod/scripts/1-profile.sh` | Choose the hardware profile; writes `quarkus.profile` | `--defaults` (takes `laptop`) |
| `prod/scripts/2-secrets.sh` | Generate the DB-role passwords | `--defaults` (no-op — no prompts) |
| `prod/scripts/3-postgres.sh` | Start Postgres and wait until healthy | `--defaults` (no-op — no prompts) |
| `prod/scripts/4-llm.sh` | Provision the LLM backend; write the LLM + embeddings config | `--defaults` (takes the profile's default backend) |
| `prod/scripts/5-bootstrap.sh` | Seed `bootstrap-sources.json` and wire the asset (price) commands | `--defaults` (uses the bundled defaults) |
| `prod/scripts/6-adapter.sh` | Configure the messaging adapter(s); capture the bootstrap-admin credential (SimpleX claim-token / Signal contact id) | `--defaults` (takes `simplex` and the default dirs; still prompts for the values a human must supply) |
| `prod/scripts/6b-simplex-provision.sh` | Provision the SimpleX bot identity (profile + address + auto-accept) and **re-print the bot's contact link**. A no-op when SimpleX isn't enabled. Run it to recover the link, which the wizard prints only during step 7 and never saves. | _(no `--defaults`)_ |
| `prod/scripts/7-apps.sh` | Build both images, provision SimpleX, start Collector then Provider | `--defaults` (no-op — no prompts) |
| `prod/scripts/8-verify.sh` | Health-check the Collector and Provider; exits non-zero on timeout | `--defaults` (no-op — no prompts) |

The post-setup **helper** scripts are documented in the main sections above:

- `prod/scripts/apps.sh {start|stop|restart|status}` — day-to-day lifecycle
  control for the two app services (see [After setup](#after-setup)).
- `prod/switch-llm.sh` — re-route a generative LLM task to a different backend
  after setup; fully interactive, no flags (see
  [Switching your AI backend later](#switching-your-ai-backend-later)).
- `prod/scripts/backup.sh [BACKUP_DIR]` — back up the database and the bot's
  messaging-identity directories (see [Back up your data](#back-up-your-data)).
- `prod/scripts/upgrade.sh [-y]` — upgrade to the latest `main`: backup, pull,
  rebuild the app images, restart Collector then Provider, all data and config
  preserved (see [After setup](#after-setup) and `docs/design/07-deployment.md`
  §7.11).

### What gets written where

The wizard writes only to `prod/runtime/` (git-ignored):

- `prod/runtime/application.properties` — the generated configuration (profile,
  LLM endpoints, adapter blocks).
- `prod/runtime/secrets.env` — generated DB passwords, the optional LLM API
  key, adapter admin credentials (SimpleX claim-token / Signal contact id), and
  adapter data-dir paths. Created with
  `0600` permissions and fed to Docker Compose via `--env-file` (never sourced
  into a shell), so pasted values containing `#`, `$`, or `&` can't break or
  execute. The committed template is `prod/config/secrets.env.example`.

The bot's **messaging identity** does *not* live in either file above — it lives
in the adapter data directories (`prod/runtime/signal-cli/`,
`prod/runtime/simplex/`). The next section explains what's in them and how to
change them.

### Bot chat identity: where it lives and how to change it

A deep-dive on the question that trips up almost everyone the first time:
**an account that works in the Signal phone app is *not* automatically usable by
the bot.** Read this before trying to move the bot onto a different number or a
fresh identity.

**Two kinds of "verified" (Signal) — not the same thing:**

- **Verified in the Signal phone app** — you installed Signal on a phone, got the
  SMS code, and can text friends. The account's keys live **on that phone**.
- **Verified in `signal-cli`** — the account's identity keys live in the bot's
  **data directory** (`prod/runtime/signal-cli/`), which the adapter reads at
  startup.

The bot only ever uses the second kind. Signal allows **one primary device per
number**, so a number that's live in the phone app is *not* ready for the bot
just by editing config — its keys aren't in the data-dir. To make `signal-cli`
control that number you must either `register` it (SMS re-verify — this **evicts
the phone app**; that's the "sacrifice" of turning a personal number into a bot
number) or `link` it as a secondary device (phone stays primary, messages
shared — the unsupported path described in [Setting up the bot's chat
account](#setting-up-the-bots-chat-account)).

**Where the number is actually registered — no shady third party.** Registering
with `signal-cli` claims the number on **Signal's own servers** — a completely
ordinary Signal account, gated by **Signal's own captcha** (the captcha exists
because Signal blocks scripted sign-ups; it has nothing to do with infochat).
But the account's **private keys are generated and stored locally**, in the
`signal-cli` data directory on your machine. infochat never uploads them
anywhere, and the messaging binaries run as local subprocesses with no network
port of their own (see [Ports and the loopback rule](#ports-and-the-loopback-rule)).
So: the *number* is a normal Signal account on Signal's servers; the
*credentials* sit in a local folder you own.

**Think of the data-dir as a keyring.** One `signal-cli` data directory can hold
several registered accounts; the adapter picks one by
`infochat.adapters.signal.account`. That gives the rule for changing the bot's
number:

- **Switching to a number already registered in the keyring** → change
  `infochat.adapters.signal.account` and `./prod/scripts/apps.sh restart`. That
  is the *only* case where it's config-and-restart.
- **Switching to a number that's only live on a phone** → first `register` (or
  `link`) it into the data-dir as above, *then* change config + restart.

**SimpleX has none of this** — no phone number, no external registration, no
captcha. The bot's SimpleX identity is a profile + contact address the wizard
mints into `prod/runtime/simplex/`, stored entirely in that local folder. To
change it:

- **Keep the same identity** (the frictionless default): do nothing — a
  `--reset --hard` does **not** touch `prod/runtime/simplex/`, so the bot's
  contact link and every existing connection survive a database wipe. Nobody
  reconnects.
- **Fresh identity**: `rm -rf prod/runtime/simplex/*`, then re-run
  `prod/scripts/6b-simplex-provision.sh` (or the wizard). You get a **new contact
  link** to reshare, and because SimpleX connections are pairwise and bound to
  the identity, **everyone reconnects from scratch** and the admin re-claims via
  the token. There is no way to mint a new identity while keeping the old
  contacts.

| | "Just config + restart"? | A *fresh* identity means… |
|---|---|---|
| **Signal** | only if the number's keys are already in `signal-cli/` | `register`/`link` the number into the data-dir first (SMS; evicts the phone if primary) |
| **SimpleX** | n/a (no number to select) | wipe `prod/runtime/simplex/*` → new contact link → everyone reconnects |

Either identity is preserved by `--reset --hard` (both live under
`prod/runtime/`, which the reset never deletes) — you only lose them if you clear
the folder yourself.

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
