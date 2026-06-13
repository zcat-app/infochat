---
id: M1-332
title: "SignalJsonRpcClient: bounded connect timeout on the daemon socket"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The reader-thread response-future timeouts in call() and the supervisor restartHung mechanism — unchanged; this ticket adds the connect-time bound they cannot cover (a hung connect before any request is pending).
  - The SignalAdapter.awaitEndpoint pre-connect probe — unchanged; it validates the daemon is listening but does not bound the connect itself (a daemon that ACKs the SYN but never speaks JSON-RPC still pins the connect thread).
acceptance:
  - "SignalJsonRpcClient.connect bounds the TCP connect: instead of new Socket(endpoint.getAddress(), endpoint.getPort()) (OS default ~75-120s), it constructs an unconnected Socket and calls connect(endpoint, <timeout-ms>) with a bounded timeout, so a daemon that accepts the SYN but never speaks fails fast rather than pinning the calling thread (SignalAdapter.start() calls connect() synchronously, so a hung connect blocks Provider startup). The timeout value is a named constant (shared with / aligned to the endpoint-probe timeout, not an unexplained literal)."
  - "On connect timeout, connect() raises an IOException that SignalAdapter.start() classifies as TRANSIENT so the supervisor drives a restart cycle, rather than the wedge surviving the whole startup grace."
  - "A test pins the bound: a server socket that accepts the connection but sends no bytes causes connect() to fail within the bounded window (not the OS default), and the failure is observable as the TRANSIENT/restart path. Existing connect/reader tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (connect-timeout case)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-332: SignalJsonRpcClient — bounded connect timeout

## Context

Deep-review v5.5 (opus-47, `05-module-infochat-messaging-adapter.md` F4) found
that `SignalJsonRpcClient.connect` opens the daemon socket with
`new Socket(InetAddress, port)`, which uses the OS default connect timeout
(~75–120s on Linux) and no `SO_TIMEOUT`. **Verified at source 2026-06-14:**
`connect()` at SignalJsonRpcClient.java:280 is `new Socket(endpoint.getAddress(),
endpoint.getPort())` with no bound; the SimpleX sibling uses
`probe.connect(new InetSocketAddress(...), 200)`.

`SignalAdapter.awaitEndpoint` validates the daemon is listening before
`connect()` runs, but a daemon that crashes between probe and connect, or one
that accepts the SYN at the kernel without app-level accept, pins the calling
thread for over a minute. `SignalAdapter.start()` calls `connect()`
synchronously, so a hung connect blocks Provider startup past the grace window.
Engineering rule §7: a timeout is the standard form of boundary validation for a
network call — this is the basic contract for a socket connect, not defensive
code.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Use `Socket s = new Socket(); s.connect(endpoint, <timeout>);`. A read-side
  `SO_TIMEOUT` is optional; the reader loop already catches `IOException` and the
  response-future timeouts cover the in-flight-request case, so the connect bound
  is the load-bearing fix.
