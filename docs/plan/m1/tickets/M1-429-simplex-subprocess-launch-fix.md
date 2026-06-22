---
id: M1-429
title: "Fix SimpleX subprocess launch: -p port + in-dir db prefix"
status: done
created: 2026-06-22
last_updated: 2026-06-22
revisions:
  - date: 2026-06-22
    reason: |
      redteam-finding refine (round 1 rework). The high AUTH-BYPASS finding's
      premise (that `-p` might bind all interfaces) was empirically refuted by
      running the SHA-pinned v6.5.4 binary — `-p` binds 127.0.0.1 only. The
      refine adds one acceptance item: document the verified loopback default
      in the commandFor javadoc so the trust-boundary-#7 guarantee is no longer
      an implicit/undocumented dependency on the binary's default. Pre-refine
      acceptance had 5 items (see git history for prior frontmatter).
escalations:
  - date: 2026-06-22
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM (high, AUTH-BYPASS, trust-boundary-tightening):
      commandFor drops the explicit `ws://127.0.0.1:<port>` host and now
      passes only `-p <port>`, with no interface/host argument. The loopback
      bind promised by security.md trust boundary #7 ("the shipped default
      binds the adapter's ws-port to loopback ... exposing it beyond the host
      is an explicit operator action, never a default") now rests entirely on
      simplex-chat's undocumented `-p` default. Nothing in the diff,
      SimpleXConfig, or a compose port-mapping constrains the bind to
      loopback; the new test asserts the `-p`/`-d` flag shape but NOT that the
      server binds to 127.0.0.1 only. If `-p` defaults to all interfaces, the
      credential-free bot WebSocket is host-reachable BY DEFAULT.
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
      files: 4
      added: 66
      removed: 13
  - round: 2
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
      added: 294
      removed: 13
redteam_findings:
  - date: 2026-06-22
    category: AUTH-BYPASS
    severity: high
    promise: |
      Trust boundary #7 (security.md §Trust boundaries): the SimpleX adapter
      speaks an unauthenticated WebSocket bot API to a co-located simplex-chat
      subprocess; that "no authentication" property is sound only while the
      channel stays loopback — "the shipped default binds the adapter's
      ws-port to loopback. Exposing it beyond the host ... is an explicit
      operator action, never a default."
    gap: |
      commandFor drops the explicit `ws://127.0.0.1:<port>` host and now
      passes only `-p <port>`, with no interface/host argument. The loopback
      bind now rests entirely on simplex-chat's undocumented `-p` default.
      Nothing in the diff, SimpleXConfig (binary/dataDir/wsPort only), or a
      compose port-mapping (the subprocess is host-spawned, not port-mapped)
      constrains the bind to loopback. The new test asserts the `-p`/`-d` flag
      shape but NOT that the server binds to 127.0.0.1 only; the javadoc is
      silent on the bind interface. If `-p` defaults to all interfaces, the
      credential-free bot WebSocket is host-reachable BY DEFAULT — the
      "never a default" state trust boundary #7 forbids.
    repro: |
      Operator deploys the shipped default (trusting the "binds to loopback by
      default" promise) on a multi-tenant / LAN-reachable host. simplex-chat
      launches as `simplex-chat -d <dir>/simplex_v1 -p 5225`. A co-located
      untrusted process, container neighbor, or same-L2 host connects to
      <host-ip>:5225 and, with no token/cookie/session, drives the bot's
      SimpleX identity (read DMs, send messages, the D10 anchor). The operator
      never took the explicit exposure action the spec requires.
    suggested_fix_class: trust-boundary-tightening
    resolution: |
      VERIFIED FALSE (2026-06-22). Ran the SHA-pinned simplex-chat v6.5.4
      binary (sha256 8c33b69e…ab655, identical to the Dockerfile pin) with
      `-p <port>`; `ss -ltnp` showed a single listener `LISTEN 127.0.0.1:<port>`
      and no `0.0.0.0` / `::` socket on the process (falsifier checked both
      address families). The `-p` chat server binds loopback by default, so
      trust boundary #7 holds and there is no auth-bypass exposure — the high
      premise is empirically refuted. Severity effectively downgraded to
      informational. Residual (the loopback guarantee was implicit and
      undocumented in code, where the old `--network ws://127.0.0.1` form made
      it explicit) is closed by this refine: the commandFor javadoc now records
      the verified loopback default and cites this finding.
  - date: 2026-06-22
    category: AUTH-BYPASS
    severity: low
    promise: |
      Trust boundary #7 (security.md §Trust boundaries): the unauthenticated
      SimpleX bot WebSocket is safe only while it stays loopback — "the shipped
      default binds the adapter's ws-port to loopback. Exposing it beyond the
      host ... is an explicit operator action, never a default."
    gap: |
      Defense-in-depth residual surfaced by the pre-merge re-audit (the HIGH is
      refuted: the SHA-pinned v6.5.4 `-p` binds 127.0.0.1 only). The loopback
      bind is delegated to the binary's `-p` default and is not expressed or
      enforced in code: SimpleXConfig exposes no bind-host field, the unit test
      asserts flag SHAPE only (not the resulting bind), and the only evidence is
      a one-time manual `ss` observation + a javadoc claim. Because the binary
      PATH (`infochat.adapters.simplex.binary`) is operator config — NOT forced
      to the SHA-pinned Docker artifact — a Provider run outside the pinned
      container against a host/forked/upgraded simplex-chat whose `-p` default
      binds non-loopback would silently expose the credential-free bot WebSocket
      with no code-level signal, test failure, or runtime check.
    repro: |
      Operator runs the Provider outside the pinned container and sets
      `infochat.adapters.simplex.binary` to a system/forked simplex-chat whose
      `-p` default bind is 0.0.0.0. commandFor launches `-p 5225` with no host
      argument; the server binds all interfaces. A co-located untrusted process,
      container neighbour, or same-L2 host drives the bot's SimpleX identity
      with no credential. No test fails and no startup check fires.
    suggested_fix_class: trust-boundary-tightening
    disposition: |
      OUT OF SCOPE for M1-429 (scoped to "launch-correctness only"; a bind probe
      or binary pin is new functionality). Does NOT block merge. Recommended as a
      separate low-priority defense-in-depth hardening ticket (e.g. a startup
      probe asserting the simplex-chat server bound loopback, or validating the
      configured binary). Compound non-default scenario; the pinned-container
      default is verified loopback-safe.
redteam_audits:
  - date: 2026-06-22
    verdict: FINDINGS
    base: main
    head: m1/M1-429-simplex-subprocess-launch-fix
    verdict_file: docs/plan/m1/redteam/M1-429-2026-06-22.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Pre-merge re-audit of the committed branch tip (22056960, now including
      the round-2 loopback-documenting javadoc). HIGH gone — threat-actor says
      the "exposed by default today" premise is "fairly refuted for the pinned
      artifact." One LOW defense-in-depth residual (operator-configurable binary
      path not pinned to the Docker artifact); out of scope for M1-429, does not
      block merge, recommended as a separate hardening ticket. Out-of-model item
      (db-prefix relocation = availability) advisory.
  - date: 2026-06-22
    verdict: FINDINGS
    base: 0a3a393ff9181c44898833096f8eecfe7d0ee072
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-429-2026-06-22.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Audited the uncommitted branch tip (pre-commit) of in-review M1-429.
      One high AUTH-BYPASS finding: the `-p <port>` launch drops the explicit
      127.0.0.1 host, so the trust-boundary-#7 loopback property now rests on
      simplex-chat's undocumented `-p` default, untested and undocumented in
      the diff. Per B1 the old `--network ws://127.0.0.1` form was rejected by
      v6.5.4 and never bound, so this is not a regression but the first
      working launch form, which must establish (not merely preserve) the
      loopback property; the recorded spike evidence covers flag acceptance
      and db-file location, not the bind interface. Out-of-model item
      (db-prefix relocation = availability) advisory only.
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
  - "The commandFor javadoc documents that the pinned simplex-chat v6.5.4 binds the `-p` chat server to 127.0.0.1 (loopback) only — verified by running the SHA-pinned binary (M1-429 spike: `ss` showed `LISTEN 127.0.0.1:<port>`, no `0.0.0.0`/`::` listener) — so the loopback guarantee of security.md trust boundary #7 holds via that default, and `-p`'s syntax carries no host argument to make the bind explicit. (Closes the residual of the redteam high finding, whose premise that `-p` might bind all interfaces is empirically refuted; no unit test can assert the bind because the harness cannot exec the real binary.)"
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
