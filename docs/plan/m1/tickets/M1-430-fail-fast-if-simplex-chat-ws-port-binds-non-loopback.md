---
id: M1-430
title: "Fail-fast if simplex-chat ws-port binds non-loopback"
status: done
created: 2026-06-22
last_updated: 2026-06-22
clarity_check:
  date: 2026-06-22
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-06-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 356
      removed: 24
redteam_findings: []
redteam_audits:
  - date: 2026-06-22
    verdict: CLEAN
    base: 6a842b3553e003c42abb7ee277b33531bfaa8b70
    head: working-tree (uncommitted, in-review)
    verdict_file: docs/plan/m1/redteam/M1-430-2026-06-22.md
    out_of_model_count: 2
    note: |
      Pre-commit --in-progress audit of the off-loopback bind guard. CLEAN: 0
      findings. The diff faithfully enforces security.md trust boundary #7 (the
      credential-free WebSocket must stay loopback). Two out-of-model advisory
      observations recorded in the verdict file; neither is a gap in this diff's
      promise.
blocked_by: []
remediates: M1-429
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXLoopbackProbe.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXLoopbackProbeTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - commandFor's launch arguments (the `-p <port>` / `-d <prefix>` form) — settled by M1-429 and unchanged. This ticket adds a runtime guard, it does not alter how simplex-chat is launched.
  - Pinning/validating the configured binary against the Docker SHA-256 — a DIFFERENT mitigation deliberately NOT taken here. A runtime bind probe is robust to ANY binary (host-installed, forked, upgraded) regardless of how `infochat.adapters.simplex.binary` is set, so it is the stronger defense-in-depth; do not add a binary-hash check.
  - SimpleXConfig.validate()'s existing data-dir / binary / port rules — unchanged. The probe runs after launch, not in config validation.
  - The provider and collector modules, and any non-SimpleX adapter — untouched.
  - The SimpleX provisioning automation (profile/address/auto-accept wizard) — a separate future ticket.
acceptance:
  - "A bind-interface probe (e.g. SimpleXLoopbackProbe) reports whether the configured chat-server port is reachable on a non-loopback local interface. Named test: a `ServerSocket` bound to `0.0.0.0:<port>` makes the probe report non-loopback exposure (true); a `ServerSocket` bound to `127.0.0.1:<port>` makes it report loopback-only (false); the test exercises the real probe against a real listening socket (no real simplex-chat binary needed). Test passes."
  - "On SimpleX subprocess startup, after the process reaches RUNNING, the probe runs; if it detects the chat-server port is exposed on a non-loopback interface the adapter does NOT proceed to serve — it transitions the subprocess to FAILED and fires the existing admin notifier (the same fail path as crash-cap exhaustion), so a simplex-chat whose `-p` default binds all interfaces fails fast instead of silently exposing the credential-free WebSocket. A named test asserts the FAILED transition + one admin notification when the probe sees a non-loopback bind, and no such transition when the bind is loopback-only."
  - "mvn -pl infochat-messaging-adapter -am verify is green, and mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXLoopbackProbeTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Trust boundaries
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D10
  - D37
---

# M1-430: Fail-fast if simplex-chat ws-port binds non-loopback

## Context

A pre-merge red-team of M1-429 (the SimpleX launch-flag fix) refuted its high
AUTH-BYPASS finding — the SHA-pinned simplex-chat v6.5.4 binds the `-p`
chat-server port to `127.0.0.1` only (verified by running the pinned binary) —
but left a **low, defense-in-depth residual** (see M1-429
`redteam_findings[1]` and `docs/plan/m1/redteam/M1-429-2026-06-22.md` §"Second
audit"). The loopback bind that `docs/spec/security.md` §Trust boundaries #7
frames as a code-guaranteed default is, after M1-429, delegated entirely to the
binary's `-p` default and is neither expressed nor enforced in code:
`SimpleXConfig` has no bind-host field, and the M1-429 unit test asserts the
flag *shape* only, not the resulting bind. Because the binary PATH
(`infochat.adapters.simplex.binary`) is operator config and is NOT pinned to
the Docker artifact, a Provider run outside the pinned container against a
host-installed / forked / upgraded simplex-chat whose `-p` default binds
`0.0.0.0` would silently expose the credential-free bot WebSocket — which can
drive the bot's SimpleX identity (the D10 trust anchor) — with no test failure,
startup check, or runtime signal. This ticket adds the missing code-level
guard: a startup probe that fails the adapter fast if the chat-server port is
reachable off-loopback, so the spec's "loopback by default, off-loopback only
by explicit operator action" promise is enforced rather than assumed.

## Acceptance

See frontmatter. In prose:

1. A bind-interface probe reports whether the chat-server port is reachable on
   any non-loopback local interface. It is unit-testable against a real
   `ServerSocket` (bind `0.0.0.0:<port>` → exposed; bind `127.0.0.1:<port>` →
   loopback-only) without execing simplex-chat — exactly the seam M1-429's
   bugs hid behind.
2. The probe is wired into SimpleX subprocess startup: a non-loopback bind
   drives the subprocess to FAILED and fires the existing admin notifier (the
   crash-cap fail path), so the adapter fails fast instead of serving an
   off-loopback credential-free WebSocket. A named test asserts both the
   non-loopback (FAILED + notify) and loopback (no transition) cases.
3. Full `mvn verify` green from the repo root.

## Out-of-scope

See frontmatter. This is a runtime bind guard only — NOT a change to the launch
arguments (M1-429), NOT a binary-hash pin (a different, weaker mitigation
deliberately declined in favor of the binary-agnostic runtime probe), and NOT a
change to `SimpleXConfig.validate()`.

`SimpleXSubprocessTest.java` is modified (authorized in `test_plan.modifies`):
it gains the acceptance-item-2 wiring test — a non-loopback bind drives the
subprocess to FAILED with one admin notification, a loopback-only bind does
not. No existing test method is weakened, renamed, or removed.

## Notes

- **Probe mechanism (non-binding design pointer).** The portable, testable
  approach is a connect probe: enumerate the host's non-loopback local
  addresses (`NetworkInterface`) and attempt a short-timeout TCP connect to
  each `<addr>:<port>`; a successful connect on a non-loopback address proves
  the server is listening there. A loopback connect alone CANNOT detect a
  `0.0.0.0` bind (loopback is a subset of all-interfaces), which is why the
  probe must target non-loopback addresses specifically. The implementer may
  instead parse `/proc/net/tcp{,6}` (Linux-only; the deployment is Linux
  containers) if that proves more robust — acceptance pins the behavior, not
  the mechanism.
- **Reuse the existing fail path.** `SimpleXSubprocess` already has a
  FAILED state + throttled `adminNotifier` (crash-cap exhaustion). The probe
  failure should reuse that path rather than inventing a second failure
  channel.
- **Why fail-fast, not warn.** Trust boundary #7 makes off-loopback exposure a
  state that "voids the property" — a credential-free identity takeover
  surface. Serving anyway with a log warning would leave the window open;
  fail-fast + admin notify matches the spec's "never a default" posture.
- Adjacent code: `SimpleXSubprocess.commandFor` / `start` /
  `handleCrashCap` (the FAILED + notify path); `SimpleXAdapter` (the startup
  wiring that builds the WebSocket client). `SimpleXConfig.wsPort()` is the
  port to probe.
- Related: M1-429 (the launch-flag fix this remediates) and its redteam audit
  `docs/plan/m1/redteam/M1-429-2026-06-22.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-430-fail-fast-if-simplex-chat-ws-port-binds-non-loopback.md
```
