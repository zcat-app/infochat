# Cross-examination report

Run directory: `/home/infochat/infochat/docs/plan/m1/redteam-multi/M1-698-2026-07-26`
Auditors: kimi, opencode

## Summary

- 4 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 4 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'kimi': 3, 'opencode': 1}.

## Per-auditor verdicts

- **kimi**: FINDINGS (3 finding(s))
- **opencode**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | kimi | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|
| 1 | INJECTION | `no-cite:3861169013392725509` | medium | -- | medium | kimi-only -- needs review |
| 2 | INFO-LEAK | `OutboundChokepointArchTest.java:214` | -- | low | low | opencode-only -- needs review |
| 3 | INJECTION | `no-cite:1700058694134497279` | low | -- | low | kimi-only -- needs review |
| 4 | INJECTION | `no-cite:5961809367400749936` | low | -- | low | kimi-only -- needs review |

## Per-cluster detail

### Cluster 1: INJECTION @ `no-cite:3861169013392725509`

**kimi** (severity: medium, fix-class: trust-boundary-tightening)

- PROMISE: docs/spec/security.md §"Sanitizer output never contains `](`":
      "every outbound body — chat reply, progress placeholder/finalize,
      periodic digest, group announcement — has its `](` adjacency broken
      before it reaches the transport, regardless of how it was assembled";
      and the new §"The chokepoint's totality is mechanically guarded"
      paragraph added by this diff: "an Arch...
- GAP (first 400 chars): The guard identifies a guarded call by exact string equality on the
      bytecode call target's OWNER: diff.patch lines 213-216
      (`if (!MESSAGING_ADAPTER.equals(target.getOwner().getName())) continue;`
      in OutboundChokepointArchTest.java). Java bytecode records the
      compile-time declared owner of the receiver, not the interface that
      declares the method. Any call site whose re...


### Cluster 2: INFO-LEAK @ `OutboundChokepointArchTest.java:214`

**opencode** (severity: low, fix-class: other)

- PROMISE: "It is now structural, like the closed-list match-set derivation
              below: an ArchUnit test (`OutboundChokepointArchTest`, M1-698)
              fails the build if any provider main class other than
              `OutboundDelivery` and `DigestDelivery.RecordingAdapter` calls
              `MessagingAdapter.send`, `.update`, or `.finalizeMessage`. A
              direct `adapter.send` fr...
- GAP (first 400 chars): OutboundChokepointArchTest.java:214 gates every flagged call on
         `MESSAGING_ADAPTER.equals(target.getOwner().getName())`. ArchUnit
         takes a method call's target owner from the bytecode
         INVOKEINTERFACE/INVOKEVIRTUAL constant-pool entry, which carries the
         STATIC declared type at the call site — not the dispatched method's
         declaring interface. The guard ther...


### Cluster 3: INJECTION @ `no-cite:1700058694134497279`

**kimi** (severity: low, fix-class: trust-boundary-tightening)

- PROMISE: Same paragraph — the guard must not be able to pass while a
      bypass exists; the test's own comment (diff.patch lines 190-195)
      commits to "fail loudly rather than ship a false-green guard" when
      ArchUnit's bundled ASM cannot read the bytecode.
- GAP (first 400 chars): The fail-loud sanity check only requires the two ALLOWED_CALLERS
      to have been imported (diff.patch lines 196-208:
      `missing.removeAll(importedNames); assertTrue(missing.isEmpty(), ...)`).
      ArchUnit skips a class file its bundled ASM cannot parse with a
      per-class WARN and imports the rest. If a future JDK/ASM skew makes
      some class files unreadable while the two allowlist...


### Cluster 4: INJECTION @ `no-cite:5961809367400749936`

**kimi** (severity: low, fix-class: trust-boundary-tightening)

- PROMISE: Same §"The chokepoint's totality is mechanically guarded"
      paragraph: the routing invariant "is now structural ... A direct
      `adapter.send` from a seventh call site — the bypass that would make
      the break miss an outbound body — is therefore unlandable in CI,
      rather than invisible."
- GAP (first 400 chars): The test enumerates only direct bytecode method calls
      (`caller.getMethodCallsFromSelf()`, diff.patch line 212). Invocation
      through reflection (`Method.invoke`), `MethodHandle`, or a JDK
      dynamic proxy whose `InvocationHandler` dispatches to the adapter
      leaves no `JavaMethodCall` edge to `MessagingAdapter.send` in the
      calling class and is invisible to the guard. Reflect...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **kimi-only**: INJECTION @ `no-cite:3861169013392725509` (severity medium). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: INFO-LEAK @ `OutboundChokepointArchTest.java:214` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: INJECTION @ `no-cite:1700058694134497279` (severity low). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: INJECTION @ `no-cite:5961809367400749936` (severity low). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.

