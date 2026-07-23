---
id: M1-679
title: "DevTerminalHarnessRoundtripIT fails on any repeat verify: the startup poll eats the previous run's input file"
status: pending
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/dev/DevTerminalHarnessRoundtripIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/dev/DevTerminalHarness.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The two group-IT failures seen in the same 2026-07-23 batch
    (GroupLifecycleIT step d reading a stale `lastReply()`, and
    GroupAuthorizationRoundtripIT dying on a Flyway V58 `SQL State 08006`
    connection drop). Neither cause is established, and neither shares
    this mechanism — the harness failure is fully explained by the
    stale-input-file chain below. Filing them together would let a green
    run on this ticket be read as closing them.
  - >-
    The dev harness's transport design. The file transport, the directive
    grammar, and the interruptible-capture wait are not at fault and stay
    as they are. Only the interaction between the startup firing and
    leftover on-disk state is in scope.
  - >-
    Production exposure. `DevTerminalHarness` is excluded at build time
    by `@IfBuildProperty(name = "infochat.dev.harness.enabled",
    enableIfMissing = false)`, so nothing here can reach a shipped jar.
    This is a test-isolation defect, not a runtime one.
acceptance:
  - >-
    `DevTerminalHarnessRoundtripIT` passes on a SECOND consecutive
    `mvn -pl infochat-provider -am verify` in the same working tree with
    no `clean` in between. That is the exact condition that fails today,
    and a single `clean` run proves nothing — it is the state that masks
    the bug.
  - >-
    The startup firing of the `@Scheduled` poll can no longer consume the
    directives the test subsequently writes. Whether the fix lands in the
    test (a per-run input/output path, computed before the app boots) or
    in the harness (advance `inputOffset` only for directives that were
    actually dispatched, so a throwing directive does not silently commit
    the read cursor) is the implementer's call — state the reasoning in
    the commit.
  - >-
    The false claim in the IT's class javadoc is corrected: "the poll
    cycle is invoked directly (the `@Scheduled` timer is set to 24h under
    test so it never races)". A 24h interval does NOT stop the timer —
    Quarkus fires an `IntervalTrigger` once at startup and every interval
    thereafter, which the failing run's log shows verbatim
    (`IntervalTrigger [id=1_...DevTerminalHarness#poll,
    interval=86400000]`). If `DevTerminalHarness`'s own "poll-interval is
    set arbitrarily large under test" phrasing is left standing it must
    stop implying the same thing.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/dev/DevTerminalHarnessRoundtripIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-679: DevTerminalHarnessRoundtripIT fails on any repeat verify

## Context

The IT fails with `java.nio.file.NoSuchFileException:
target/m1-414-dev-harness-out.txt` — the harness wrote no output at all.
The cause is a five-step chain, every step of which is visible in the
2026-07-23 run log (`.scratch/m1-677-prov-rerun.log`):

1. `@Scheduled(every = "{infochat.dev.harness.poll-interval:1s}")` on
   `DevTerminalHarness.poll()` becomes an `IntervalTrigger`. The test
   profile's `24h` override **does** apply — the log names the trigger
   `interval=86400000` — but a Quarkus `IntervalTrigger` still fires
   **once at startup**. The test's premise that a large interval means
   "it never races" is wrong.
2. That startup firing happens before `AdapterRegistry` activates the
   inmemory adapter. Log order: the poll's stack trace, then
   `activating adapter: inmemory`, then `started in 2.509s`.
3. The poll finds `target/m1-414-dev-harness-in.txt` **left over from the
   previous run** — `@BeforeEach` deletes it, but `@BeforeEach` runs long
   after the app booted. `readNewCompleteLines()` advances `inputOffset`
   to the file length (`DevTerminalHarness.java:127`) and only then calls
   `processDirective`, which throws
   `IllegalStateException: InMemoryAdapter.deliverDm called before
   setInboundHandler`. The cursor advance is already committed; the
   exception does not roll it back.
4. `@BeforeEach` then deletes both files and the test writes fresh input
   — **exactly the same 128 bytes**, because both ids are fixed-length
   UUIDs (`m1-414-newuser-<uuid> <uuid>` + `m1-413-seed-user /summary
   -w 24h`). The rewind guard is `all.length < inputOffset`, i.e.
   `128 < 128`, which is false, so the cursor is not rewound.
5. `readNewCompleteLines()` therefore returns nothing, no directive runs,
   `writeReplies` early-returns on the empty list, and the output file is
   never created. The test's `Files.readString` throws.

## Why it looks intermittent and is not

The failure needs a leftover input file present at app boot. A first-ever
run, or any `mvn clean verify`, has none — the startup poll no-ops
silently and the test passes. The bug appears on the **second** `verify`
in a tree that was not cleaned.

That is measurable rather than inferred: across the two 2026-07-23 runs,
`DevTerminalHarness_ScheduledInvoker_poll` appears **0 times** in the log
of the run that passed and **1 time** in the log of the run that failed.

The cost is that a repeat `verify` — the normal way to re-check a suspect
failure — injects a fresh failure in an unrelated module, which reads as
a regression in whatever ticket happens to be at its gate. It surfaced
exactly that way during M1-677's build gate.

## Notes

- Two fixes are viable and the acceptance criteria deliberately do not
  pick one:
  - **Test-side** (recommended): derive the input/output paths per run —
    `HarnessProfile.getConfigOverrides()` and the enclosing class's
    static initializer both execute before the app boots, so a unique
    name is in force for the startup firing. Leftover files then land in
    `target/` and die with `clean`. Smallest change, and it leaves the
    harness exactly as shipped.
  - **Harness-side**: commit `inputOffset` only for directives that were
    actually dispatched. This also fixes a real dev-tool wart — today any
    throwing directive is swallowed together with every other line the
    same poll read — but it is a behavioural change to main code for a
    dev-only bean, so it needs its own justification.
- Do not "fix" this by adding `clean` to the project's verify
  instructions. That hides the defect rather than closing it, and the
  acceptance criterion above is written to make a clean-only proof
  insufficient.
