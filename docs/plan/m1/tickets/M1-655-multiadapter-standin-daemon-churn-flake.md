---
id: M1-655
title: "Fix MultiAdapterProductionIT flake: stand-in daemon must not die and trigger reconnect churn"
status: done
created: 2026-07-18
last_updated: 2026-07-18
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 70
      removed: 33
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any src/main change. SignalAdapter.reconnect()'s disconnect-then-connect
    (SignalAdapter.java:558), SignalSubprocess's restart policy, setTyping's
    best-effort absorb (SignalJsonRpcClient.java:552-556), and
    MessagingStartup's absence of adapter teardown are all correct production
    behavior; the defect is that the TEST feeds the supervisor a child that
    dies instantly, so production crash recovery runs during the test body.
  - >-
    The M1-540 generation-2 barrier (MultiAdapterProductionIT.java:396) and
    the M1-541 awaitClient barrier (:464). They guard a real, separate
    fake-side accept-bookkeeping race and are kept exactly as they are. This
    ticket removes the OTHER nondeterminism source those barriers never
    covered.
  - >-
    The two remaining /bin/sleep stand-in usages elsewhere:
    SimpleXSubprocessTest uses it DELIBERATELY (with valid numeric args, or
    to exercise crash-restart itself) and must not change;
    SignalAdapterIdentityDerivationTest shares the dies-instantly pattern but
    has no observed flake — if it flakes later, file a sibling ticket citing
    this one.
  - >-
    FakeSignalCli's accept loop overwriting clientWriter on every accepted
    connection (probes included, FakeSignalCli.java:181-184). With churn
    eliminated no probe lands mid-test, so the quirk is unreachable here;
    changing shared test infra is not justified by this ticket.
acceptance:
  - >-
    The stand-in daemon binary used by MultiAdapterProductionIT — the two
    Profile getConfigOverrides() entries (currently /bin/sleep at :665 and
    :675) and the two per-test factories newSimpleXAdapter (:514) and
    newSignalAdapter (:535) — no longer exits at launch. The replacement
    ignores the adapter-supplied argument list and stays alive for the whole
    test class (bounded: it self-terminates within ~1 hour so a child leaked
    by the CDI path, which MessagingStartup never stops, cannot outlive the
    CI host's patience), e.g. a generated sh script that execs a bounded
    sleep. Mechanism is implementer's choice; the property that matters is
    "healthy child, no supervised restart during the class".
  - >-
    Consequence, checkable in the round's full-suite log: zero
    "signal-cli subprocess exited" lines and zero
    "adapter reconnected after subprocess restart" lines (either adapter)
    originate from the infochat-provider module's test run. The production
    Signal/SimpleX adapters activate ONLY under this class's profile within
    that module (default test profile runs inmemory), so the module's log is
    attributable: any such line means the stand-in still dies.
  - >-
    No assertion, timeout, barrier, or awaited condition in any test method
    is weakened, removed, or loosened. The diff touches stand-in wiring
    (binary path constants, the static-init block, factories, Profile map)
    and comments only.
  - >-
    The class javadoc "Profile wiring" paragraph (:76-81) and both factory
    comments (:505-513, :528-533) no longer describe the exits-immediately
    pattern as benign; they state the stand-in stays alive and WHY — a
    dying child triggers supervised restart -> reconnect() ->
    client.disconnect() during the test body, severing the connection the
    liveness probes write to, which is the root cause of the recurring
    "received no outbound JSON-RPC within 2000 ms" flake (M1-540 recurrence,
    2026-07-18).
  - mvn verify is green (full suite).
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
      — stand-in daemon wiring and comments only. Authorized explicitly:
      no test method's assertions or timing change.
  preserves:
    - All other tests, byte-identical.
spec_refs:
  - docs/design/06-messaging.md §6.4.6 Reconnection
  - docs/spec/verification.md §Test layers
decision_refs: []
---

# M1-655: Fix MultiAdapterProductionIT flake — stand-in daemon churn

## Context

`MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal` failed a full-suite
run on 2026-07-18 (`FakeSignalCli received no outbound JSON-RPC within
2000 ms`, MultiAdapterProductionIT.java:424) and passed an immediate
module-scoped re-run — the same symptom M1-540 fixed once (its ticket names
the identical error string) and M1-541 hardened the SimpleX side of. The
prior fixes added readiness barriers; the actual nondeterminism source was
never removed. Every claim below was verified firsthand on 2026-07-18
against the failing log (`.scratch/m1-tick-test-M1-653-r1.log`) and source.

**Causal chain:**

1. The test's stand-in daemon binary is `/bin/sleep`, spawned by
   `SignalAdapter.start()` with `--config … -a … daemon --tcp …`
   (SignalAdapter.java:239-244). sleep cannot parse those args and exits
   code 1 within milliseconds.
2. `SignalSubprocess` supervises the child: equal-jitter backoff
   (base 250 ms × 2, SignalSubprocess.java:418-420) schedules restarts 1-4
   at roughly 125-250 / 250-500 / 500-1000 / 1000-2000 ms after each exit —
   all inside the test's ~2.25 s liveness-probe window.
3. Each successful respawn fires the restart listener
   (SignalSubprocess.java:284-288) → `SignalAdapter.reconnect()` →
   `client.disconnect()` **on the live JSON-RPC connection the test is
   probing** (SignalAdapter.java:558) → socket closed, reader joined,
   dispatch executor shut down (SignalJsonRpcClient.java:382-428) → fresh
   `connect()`.
4. A `setTyping` probe whose write lands in a severed window gets a
   TRANSIENT failure (null writer, SignalJsonRpcClient.java:650-654; or
   write IOException, :661-665) which `setTyping` **absorbs without retry**
   (:552-556, best-effort per SPI). The frame never reaches the fake;
   `nextOutbound(2000)` expires.
5. The failing log shows exactly this: the crash-test's fresh adapter
   (`daemon endpoint=/127.0.0.1:46281`) logs `scheduling restart 1/5 in
   202 ms` and `2/5 in 349 ms` immediately before the assertion failure,
   with environmental drag (fresh pgvector container + 59 Flyway
   migrations) directly preceding the class — load widens the
   disconnect-to-connect windows.

The sibling `signalCrashDoesNotAffectSimpleX` has the identical latent
structure via SimpleX's supervisor (the failing log also shows
`SimpleX adapter reconnected after subprocess restart`); its probe thread
catches-and-ignores `MessagingException`
(MultiAdapterProductionIT.java:488-491), so a frame lost to a reconnect
window times out `awaitFrame(2000)` the same way.

**Why the barriers cannot fix this:** the M1-540 generation-2 barrier
completes before the churn does; restarts keep firing after it passes.
Any barrier answers "is the connection up NOW" — the churn severs it LATER,
mid-probe. Only removing the churn is deterministic.

**Why the fix is test-side:** the production code is behaving as designed —
a crashing daemon SHOULD be respawned and its transport revived
(design 06-messaging §6.4.6). The defect is the test feeding the supervisor
a child that instantly dies, making production crash recovery run
concurrently with a test that assumes a stable transport.

## Acceptance

See `acceptance`.

## Notes

**Alternatives considered and rejected:**
- *Retry-looping the probes* — adds tolerance instead of removing the
  nondeterminism; the churn would keep racing everything else in the class
  (including the fake's clientWriter bookkeeping).
- *Injecting maxRestarts=0* — unreachable: `SignalAdapter.start()` hardcodes
  `SUBPROCESS_MAX_RESTARTS = 5` and constructs its own `SignalSubprocess`
  (SignalAdapter.java:245-249); the crash tests go through `start()` by
  design (they exercise the production constructors).
- *Stopping adapters in MessagingStartup `@PreDestroy`* — a src/main change
  to serve a test, and it would not stop the churn anyway (the churn runs
  while the test is alive, not at shutdown).

**Why a bounded lifetime (~1 h) instead of sleep-forever:** MessagingStartup
never stops the CDI-wired adapters (verified: no `@PreDestroy` in the file,
2026-07-18), so the Profile-path children are never SIGTERMed; an unbounded
child would outlive the JVM as an orphan on the CI host. ~1 h is far beyond
any class runtime and self-reaps. The per-test factory adapters ARE closed
(`finally { sx.close(); sg.close(); }` → `subprocess.stop()` → destroy), so
their children die with the test either way.

**Relationship to M1-653:** the flake was caught by M1-653's round-1 verify
(a javadoc+docs ticket with zero runtime surface — the red is provably
unrelated to its diff). M1-653 proceeds independently; its next verify
round benefits from this fix once merged.
