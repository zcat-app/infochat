---
id: M1-388
title: "Provider image: make simplex-chat and signal-cli available at the configured .binary paths (the adapters spawn them as subprocesses)"
status: done
created: 2026-06-16
last_updated: 2026-06-17
clarity_check:
  date: 2026-06-16
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: pinned-versions-named-in-comment is an inspection check, not runnable; consider a grep-based command."
    - "ACCEPTANCE-RUNNABLE item 4: design-doc reconciliation is verified by inspection; reviewer should read the stale §7.7.2 line to confirm the 'no messaging-client containers' claim is removed/updated."
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/docker/Dockerfile.jvm
  - docker-compose.yml
  - docs/design/07-deployment.md
complexity: high
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-388.md
out_of_scope:
  - The adapter config-key names the wizard writes (M1-387) — this ticket assumes the .binary key is being set correctly and provides the file it points at.
  - Changing the adapters' subprocess model (SimpleXSubprocess / SignalSubprocess spawn the clients in-process) — that is the shipped, tested behavior this ticket supplies the binaries for; no Java change.
  - The SimpleX / Signal out-of-band account registration (operator-driven, step 6) — unchanged.
acceptance:
  - "The infochat-provider runtime image makes an executable simplex-chat available at the path the wizard writes to infochat.adapters.simplex.binary (default /usr/local/bin/simplex-chat): `docker compose --profile prod run --rm --no-deps --entrypoint sh infochat-provider -c 'command -v simplex-chat'` prints a path and exits 0."
  - "The infochat-provider runtime image makes an executable signal-cli available at the path written to infochat.adapters.signal.binary (default /usr/local/bin/signal-cli): the same presence probe for signal-cli exits 0."
  - "Both client versions are pinned to explicit upstream release tags (M1-004 pinned-tag precedent), not 'latest'; the pinned versions are named in a Dockerfile comment."
  - "docs/design/07-deployment.md §7.7.2 / §7.7 is reconciled with the implemented model: the operator note no longer claims v1 ships no messaging-client containers when the binaries are baked into the Provider image (or, if the host-mounted-binary path is chosen instead, that mechanism is documented and the compose mount is added)."
  - "The Provider image builds; `docker compose --profile prod config` exits 0; `mvn -B verify` from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.4 Canonical
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-06-16
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 42
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-16
    verdict: CLEAN
    base: c92e86d156474bf5c1285899be2e955ab7cc16aa
    head: "working-tree (m1/M1-388-provider-image-messaging-client-binaries, uncommitted, in-review)"
    verdict_file: docs/plan/m1/redteam/M1-388-2026-06-16.md
    out_of_model_count: 2
    note: |
      Pre-commit adversarial audit of the baked-in messaging-client binaries and the
      §7.7 design-note reconcile. CLEAN — no findings. The diff touches no
      auth/authz/input/ban/audit code surface (Dockerfile + markdown only). Two
      advisory out-of-model observations are recorded verbatim in the verdict file;
      neither blocks commit/merge. Operator may review them when extending the
      threat model.
---

# M1-388: Provider image must provide the messaging-client binaries

## Context

Re-verified at source 2026-06-16. The Provider drives both messaging clients as
**OS subprocesses inside its own container**:

- `SimpleXSubprocess` spawns `simplex-chat` via `ProcessBuilder`
  (`.../messaging/impl/simplex/SimpleXSubprocess.java:18,292`).
- `SignalSubprocess` launches the `signal-cli` JSON-RPC daemon, which
  `SignalAdapter` then connects to over TCP.
- `ProductionAdapterBeans` requires each adapter's `.binary` config, and
  `SimpleXConfig.validate()` / `SignalConfig.validate()` reject a `.binary`
  that does not point at an existing, executable file
  (`SimpleXConfig.java:152`, `SignalConfig.java:135`).

But the Provider runtime image is `eclipse-temurin:25-jre` plus `curl` only
(`infochat-provider/src/main/docker/Dockerfile.jvm`) — it contains **neither
`simplex-chat` nor `signal-cli`**. No compose mount provides them either (only
the identity-dir and application.properties are mounted). With simplex/signal
the only production adapters (D46), every adapter fails `validate()` at startup,
so the Provider never reaches a connected state — the wizard finishes "green"
but the bot is non-functional.

This contradicts a stale design statement: `07-deployment.md:603` says *"v1 does
not ship containers for the messaging clients ... Operators run them on the host
or in their own dedicated containers."* That external-sidecar model does not
match the implemented containerized subprocess model. Part of this ticket is
reconciling that note.

> **DECISION REQUIRED (flag for the maintainer).** Default approach taken by
> this ticket: bake both binaries into the Provider runtime image (`simplex-chat`
> static Haskell binary; `signal-cli`, which runs on the image's JRE), pinned to
> release tags. Alternative: keep the image thin and bind-mount a
> host-provided binary at the `.binary` path. The acceptance is written to the
> default; if the maintainer prefers the host-mount path, the acceptance's
> presence-probe becomes a mount check and the design note documents that
> instead. The deeper alternative — changing the adapters to connect to an
> externally-managed daemon and not require `.binary` — is a Java change well
> beyond this ticket and is NOT assumed.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Image-size and supply-chain: `simplex-chat` is a sizeable static binary;
  pin by release tag and fetch over TLS. Prefer a verified checksum on the
  fetched binary (same spirit as M1-394's GGUF integrity check).
- Pairs with M1-387 (correct `.binary` key) — neither alone makes an adapter
  start; both must land before the §7.7.2 step-8 health smoke can report the
  Provider UP with a connected adapter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-388-*.md
```
