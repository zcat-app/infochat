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
  - The SignalAdapter.awaitEndpoint pre-connect probe — unchanged; it proves the daemon is listening at probe time but does not bound the subsequent connect, which can still hang if its SYN goes unanswered (the daemon crashed in the gap between probe and connect, or the accept backlog saturated in that gap).
acceptance:
  - "SignalJsonRpcClient.connect bounds the TCP connect: instead of new Socket(endpoint.getAddress(), endpoint.getPort()) (OS default ~75-120s connect timeout), it constructs an unconnected Socket and calls connect(endpoint, <timeout-ms>) with a bounded timeout. The failure mode this closes is a SYN that never gets a SYN-ACK (the daemon crashed after the probe, the host's accept backlog is saturated, or a network partition), which under the OS default pins the calling thread for over a minute; SignalAdapter.start() calls connect() synchronously, so that hang blocks Provider startup past the grace window. The timeout value is a named constant in SignalJsonRpcClient.java (out_of_scope keeps SignalAdapter.java out of this ticket, and ENDPOINT_PROBE_INTERVAL is private to that class, so the symbol cannot be shared); it is documented with a comment as aligned to the endpoint probe's per-attempt connect timeout (SignalAdapter uses ENDPOINT_PROBE_INTERVAL*2 = 200 ms for the localhost daemon it just probed) — a named, commented constant, not an unexplained literal."
  - "No new classification code is added for the timeout: SocketTimeoutException is an IOException, so the bounded connect's timeout already flows through the existing SignalAdapter.connectClient seam (which start() routes through), where catch (IOException) maps it to MessagingException(TRANSIENT), stops the just-started subprocess, and detaches the client — driving the supervisor restart cycle rather than letting the wedge survive the startup grace. This item is satisfied by the existing seam; item 3's test confirms the timeout exception reaches it."
  - "A new test in the signal test dir pins the bound deterministically via a socket seam — no real network, no timing race (mirroring the connectClient seam's documented rationale that this connect window 'no test can produce deterministically through start()'). SignalJsonRpcClient.connect obtains its socket through a package-private seam (e.g. Socket newSocket() { return new Socket(); }) and then calls s.connect(endpoint, CONNECT_TIMEOUT_MS). The test subclasses the client in-package, overriding newSocket() to return a RecordingSocket extends Socket whose connect(SocketAddress, int) records the timeout argument and throws SocketTimeoutException. Routed through connectClient (mirroring SignalAdapterStartFailureTest's setup), the test asserts (a) the recorded timeout argument equals the named CONNECT_TIMEOUT_MS constant — proving a bound is applied, not the OS default (timeout 0) — and (b) the resulting failure surfaces as MessagingException(TRANSIENT) with the subprocess STOPPED. A revert to new Socket(addr,port) bypasses/removes the seam (compile or assertion failure); a revert to connect(endpoint, 0) records 0 != the constant (assertion failure). Existing connect/reader tests (SignalJsonRpcClientTest, SignalAdapterStartFailureTest) stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalConnectTimeoutTest.java (deterministic socket seam: injects a RecordingSocket via a newSocket() override, asserts the bounded timeout argument + TRANSIENT; RecordingSocket co-located as a package-private top-level class in the same file to keep files_budget at 2; new file so SignalAdapterStartFailureTest stays untouched)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations:
  - date: 2026-06-14
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      Acceptance item 3: the test scenario 'a server socket that accepts the
      connection but sends no bytes causes connect() to fail within the bounded
      window' is incoherent. A listening ServerSocket completes the TCP
      handshake at the kernel level, so client connect(endpoint, timeout)
      returns successfully and never times out — that wording describes the
      read-timeout (SO_TIMEOUT) path, not connect-timeout.
revisions:
  - date: 2026-06-14
    reason: clarity-fail rework — acceptance item 3 described a technically incoherent connect-timeout test, and items 1-2 carried the same conflation in their rationale.
    prior_values: |
      Item 1 rationale claimed "a daemon that accepts the SYN but never
      speaks fails fast" — but a peer that accepts the SYN has COMPLETED the
      TCP handshake, so client connect() returns successfully; that is the
      read-timeout (SO_TIMEOUT) path, not connect-timeout.
      Item 2: "On connect timeout, connect() raises an IOException that
      SignalAdapter.start() classifies as TRANSIENT ..." — implied new
      classification code; in fact SignalAdapter.connectClient already maps
      ANY connect IOException (SocketTimeoutException is one) to TRANSIENT.
      Item 3: "a server socket that accepts the connection but sends no bytes
      causes connect() to fail within the bounded window" — incoherent: a
      listening ServerSocket completes the handshake in-kernel, so connect()
      succeeds and never times out. Rewrote items 1-3, test_plan, and Notes
      around a saturated listen-backlog endpoint (handshake cannot complete →
      connect() actually times out at the bound) routed through the existing
      connectClient seam. The test mechanism is a deterministic socket seam
      (a package-private newSocket() factory injecting a RecordingSocket that
      records the timeout argument and throws SocketTimeoutException); the test
      asserts the recorded timeout equals the bounded constant. Saturated-backlog
      and blackhole-address real-socket approaches were considered and rejected
      (flaky kernel-backlog saturation, tcp_abort_on_overflow false-green,
      ENETUNREACH wrong-exception).
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
`connect()` runs, but that proves nothing about the connect that follows: if the
daemon crashes in the gap between probe and connect, or the host's accept backlog
saturates in that gap, the connect's SYN goes unanswered and the OS-default
connect pins the calling thread for over a minute. `SignalAdapter.start()` calls
`connect()` synchronously, so a hung connect blocks Provider startup past the
grace window.
Engineering rule §7: a timeout is the standard form of boundary validation for a
network call — this is the basic contract for a socket connect, not defensive
code.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Production change is surgical: a package-private `Socket newSocket() { return
  new Socket(); }` seam, then `Socket s = newSocket(); s.connect(endpoint,
  CONNECT_TIMEOUT_MS);` plus the named, commented `CONNECT_TIMEOUT_MS` constant —
  all in `SignalJsonRpcClient`. The seam is this module's established idiom for a
  connect window that (per `connectClient`'s own javadoc) "no test can produce
  deterministically." No read-side `SO_TIMEOUT` is added — the reader loop already
  catches `IOException` and the response-future timeouts cover the
  in-flight-request case, so the connect bound is the load-bearing fix. The
  clarity WARN is resolved: this ticket is connect-timeout only; do NOT also add
  `SO_TIMEOUT`.
- Test mechanism (deterministic socket seam): a `RecordingSocket extends Socket`
  overrides `connect(SocketAddress, int)` to record the timeout argument and throw
  `SocketTimeoutException`; the test subclasses `SignalJsonRpcClient` in-package to
  return it from `newSocket()`. Keep `RecordingSocket` a top-level package-private
  class co-located in the test file (not an inner class), per the module
  convention. No real socket, no `ServerSocket`, no timing assertion — the test
  asserts the recorded timeout argument equals `CONNECT_TIMEOUT_MS`, the exact
  contract ("a bound is applied, not the OS default of 0"), and that the failure
  surfaces as `TRANSIENT` through `connectClient`.
- Alternatives considered and rejected: (a) saturated listen backlog
  (`ServerSocket(0,1)` + un-accepted fillers, a real-socket test that times out the
  connect) — rejected: saturation depends on kernel backlog rounding (flaky
  under-fill), and the outcome depends on `net.ipv4.tcp_abort_on_overflow` — on the
  non-default `=1` an overflowing queue RSTs, so the connect fails fast on BOTH the
  fixed and the regressed code, a silent false-green that stops guarding the
  regression; (b) a non-routable/blackhole address (TEST-NET 192.0.2.1) — rejected:
  CI sandboxes may return ENETUNREACH instantly (wrong exception) and it does not
  prove the bound. The seam is deterministic, asserts the exact timeout value,
  turns a revert into a compile/assertion failure, fits `files_budget: 2`, and
  matches the module's existing seam pattern (`connectClient`, `attachClient`,
  `attachSubprocess`).
