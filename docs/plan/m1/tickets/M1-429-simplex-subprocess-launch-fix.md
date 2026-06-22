---
id: M1-429
title: "Fix SimpleX subprocess launch: -p port + in-dir db prefix"
status: pending
created: 2026-06-22
last_updated: 2026-06-22
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SimpleX provisioning automation (wizard creating the bot profile/address/auto-accept) — a separate future ticket, blocked on the deployment-item-7 policy decision. This ticket ONLY corrects the launch arguments.
  - SimpleXConfig.validate()'s requirement that data-dir be an existing, writable directory — unchanged; the launch prefix is derived from that directory.
  - SimpleXAdapter's WebSocket connection logic (SimpleXAdapter.java:312 builds ws://127.0.0.1:<wsPort>) — unchanged; it already targets the right port.
  - Backwards-compat/migration for the old sibling-DB on-disk location — NONE. M1 is greenfield and the adapter never successfully launched (blocker B1), so there is no prior on-disk SimpleX identity to preserve; do not add a shim.
  - SimpleXReconnectTest (and any test that builds its own launch command via a flag-file script rather than commandFor) — not touched.
  - Any doc edit — no spec/design file references the launch flags (verified by grep), so none needs changing.
acceptance:
  - "SimpleXSubprocess.commandFor no longer emits `--network`; it passes the WebSocket/chat-server port via `-p <port>` (a bare port number) so the launch is accepted by the pinned simplex-chat v6.5.4 (which has no `--network` option)."
  - "SimpleXSubprocess.commandFor passes `-d` a path-PREFIX located inside the configured data-dir (e.g. `<data-dir>/simplex_v1`), so simplex-chat writes its identity DB files (`*_chat.db`, `*_agent.db`) INSIDE the bind-mounted data-dir rather than as siblings outside it."
  - "SimpleXSubprocessTest gains a test that asserts commandFor's argument list contains `-p` followed by the configured port, does NOT contain `--network`, and that the `-d` value is a strict path descendant of the configured data-dir. Test passes."
  - "The commandFor javadoc/comment (currently 'WebSocket port via --network', SimpleXSubprocess.java:144) is corrected to describe the `-p` port flag and the in-directory db prefix."
  - "mvn -pl infochat-messaging-adapter -am verify is green, and mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  - D10
---

# M1-429: Fix SimpleX subprocess launch: -p port + in-dir db prefix

## Context

A VPS spike (2026-06-22) ran the *exact* simplex-chat binary the Provider image
bakes in — release `simplex-chat-ubuntu-22_04-x86_64` @ **v6.5.4**, sha256
`8c33b69e…ab655`, matching the pin in `infochat-provider/src/main/docker/Dockerfile.jvm:47-49`
— and surfaced two production blockers in how the adapter launches it. Both were
invisible because `SimpleXSubprocessTest` mocks the subprocess and **no test
asserts the launch arguments** (verified by grep: `commandFor` is referenced only
by `SimpleXAdapter.java:240` and a comment in `SimpleXReconnectTest`, which builds
its own command). The SimpleX adapter is the project's recommended messaging path,
so it must actually start and keep a stable identity.

**Blocker B1 — `--network` is not a valid v6.5.4 flag.**
`SimpleXSubprocess.commandFor` (`SimpleXSubprocess.java:147-150`) launches
`simplex-chat -d <dir> --network ws://127.0.0.1:<port>`. Against the baked binary,
`--network` is rejected (`Invalid option '--network'`, exit 1). The binary's
`--help` shows the WebSocket/chat server is started with `-p,--chat-server-port PORT`
(a bare port). As written, the adapter cannot launch in production.

**Blocker B2 — identity DB files land outside the bind-mount.**
`SimpleXConfig.validate()` requires `data-dir` to be an existing directory
(`SimpleXConfig.java:156`) and `commandFor` passes that bare directory to `-d`. But
`-d` is a path *prefix*: the spike proved `-d /x/dir` (no trailing slash) writes
`/x/dir_chat.db` / `/x/dir_agent.db` as **siblings** of the directory. The compose
file mounts the directory itself (`<dir>:<dir>`, `docker-compose.yml:140`), so the
identity DBs land *outside* the mount → the bot's SimpleX identity is ephemeral
(lost on container recreation → the bot's queue address — the D10 trust anchor —
silently rotates → existing users can no longer reach it). Pointing `-d` at a
prefix inside the dir (`<data-dir>/simplex_v1`) puts the DBs inside the mount.

## Acceptance

See frontmatter. The diff is confined to `commandFor`: swap the rejected
`--network ws://…` argument for `-p <port>`, and make the `-d` argument a fixed
prefix inside the configured data-dir so identity persists in the bind-mount; fix
the stale comment; and add an arg-assertion test (the regression guard that was
missing). `mvn verify` green from the repo root.

## Out-of-scope

See frontmatter. This is the launch-correctness fix only — NOT the provisioning
automation (that is a separate future ticket gated on the deployment-item-7
policy question), NOT a change to `SimpleXConfig.validate()`'s directory rule, and
NOT a backwards-compat shim for the old sibling-DB path (greenfield; the adapter
never launched, so there is no prior identity on disk).

## Notes

- **Spike evidence (2026-06-22, simplex-chat v6.5.4, SHA matching the Dockerfile pin).**
  `--help`: `-e/--execute` (one-shot + exit), `-d/--database` ("Path prefix to
  chat and agent database files"), `-p/--chat-server-port PORT` ("Run chat server
  on specified port"); **no `--network`**. `--network ws://…` → `Invalid option`,
  exit 1. `-d <dir>` (no trailing slash) → `<dir>_chat.db` / `<dir>_agent.db` as
  siblings *outside* `<dir>`; `-d <dir>/simplex_v1` → files inside `<dir>`.
  (The full findings file lived under `.scratch/`, which is git-ignored.)
- Recommended prefix basename: **`simplex_v1`** (matches simplex-chat's own default
  basename `~/.simplex/simplex_v1`); pin the concrete value in the new test.
- **What `mvn verify` can and cannot prove here.** The automated test only pins the
  *argument list*; it cannot exec the real binary (simplex-chat is baked into the
  Provider image, not on the Maven test classpath — exactly why both bugs hid).
  True end-to-end validation (the adapter's WS client connects over `-p <port>`,
  and identity persists across container recreation) requires the real binary and
  should be re-confirmed on the VPS/spike environment after this change. Do not add
  a real-binary IT to `mvn verify`.
- Adjacent code: `SimpleXAdapter.java:240` calls `commandFor`; `SimpleXAdapter.java:312`
  builds the `ws://127.0.0.1:<wsPort>` URI the client connects to (unchanged — the
  port is the same value, only the way it is passed to simplex-chat changes).
- `security_relevant: true` because the fix concerns adapter availability (B1) and
  the stability of the bot's cryptographic contact id / D10 trust anchor (B2).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-429-simplex-subprocess-launch-fix.md
```
