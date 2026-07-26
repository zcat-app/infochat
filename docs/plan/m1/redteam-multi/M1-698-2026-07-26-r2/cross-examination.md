# Cross-examination report

Run directory: `/home/infochat/infochat/docs/plan/m1/redteam-multi/M1-698-2026-07-26-r2`
Auditors: kimi, opencode

## Summary

- 2 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 2 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'kimi': 1, 'opencode': 1}.

## Per-auditor verdicts

- **kimi**: FINDINGS (1 finding(s))
- **opencode**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | kimi | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|
| 1 | INJECTION | `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java:111` | medium | -- | medium | kimi-only -- needs review |
| 2 | INJECTION | `OutboundChokepointArchTest.java:76` | -- | low | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: INJECTION @ `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java:111`

**kimi** (severity: medium, fix-class: trust-boundary-tightening)

- PROMISE: docs/spec/security.md (new paragraph added by this diff,
      lines 629-639): "an ArchUnit test (`OutboundChokepointArchTest`,
      M1-698) fails the build if any provider main class other than
      `OutboundDelivery` and `DigestDelivery.RecordingAdapter` calls
      `MessagingAdapter.send`, `.update`, or `.finalizeMessage`. A direct
      `adapter.send` from a seventh call site — the bypass th...
- GAP (first 400 chars): The guard enumerates only direct bytecode call edges:
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java:111
      iterates `caller.getMethodCallsFromSelf()`. A bare method reference
      (`adapter::send`, `adapter::update`, `adapter::finalizeMessage`)
      passed to a helper as a functional interface compiles to an
      `invokedynamic` wh...


### Cluster 2: INJECTION @ `OutboundChokepointArchTest.java:76`

**opencode** (severity: low, fix-class: other)

- PROMISE: "It is now structural, like the closed-list match-set
              derivation below ... A direct `adapter.send` from a seventh
              call site — the bypass that would make the break miss an
              outbound body — is therefore unlandable in CI, rather than
              invisible." The thesis sentence frames this as a property
              of every outbound body: "The `](`-free OUT...
- GAP (first 400 chars): The structural guard is scoped to a single Maven module. The
         test imports only `app.zcat.infochat.provider..`
         (OutboundChokepointArchTest.java:76), but `MessagingAdapter`
         itself lives in a sibling module, package
         `app.zcat.infochat.messaging`
         (infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:1,51),
         and ...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **kimi-only**: INJECTION @ `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java:111` (severity medium). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: INJECTION @ `OutboundChokepointArchTest.java:76` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

