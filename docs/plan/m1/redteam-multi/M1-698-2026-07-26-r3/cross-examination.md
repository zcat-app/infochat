# Cross-examination report

Run directory: `/home/infochat/infochat/docs/plan/m1/redteam-multi/M1-698-2026-07-26-r3`
Auditors: kimi, opencode

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'kimi': 2, 'opencode': 1}.

## Per-auditor verdicts

- **kimi**: FINDINGS (2 finding(s))
- **opencode**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | kimi | opencode | Severity (max) | Attribution |
|---|---|---|---|---|---|---|
| 1 | AUDIT-EVASION | `OutboundChokepointArchTest.java:225` | -- | medium | medium | opencode-only -- needs review |
| 2 | INJECTION | `no-cite:7402284905750995026` | medium | -- | medium | kimi-only -- needs review |
| 3 | INJECTION | `no-cite:8763994711215282808` | low | -- | low | kimi-only -- needs review |

## Per-cluster detail

### Cluster 1: AUDIT-EVASION @ `OutboundChokepointArchTest.java:225`

**opencode** (severity: medium, fix-class: trust-boundary-tightening)

- PROMISE: "The 'structural' reach is the provider module, which the
      module DAG makes total today: only infochat-provider depends on
      infochat-messaging-adapter (enforcer rules block the edge in every
      other module), so the universe of possible senders is the provider
      main source the test scans." And: "A direct `adapter.send` from a
      seventh call site — the bypass that would make t...
- GAP (first 400 chars): The guard's package scope is `app.zcat.infochat.provider..`
      only (OutboundChokepointArchTest.java:225 —
      `importPackages("app.zcat.infochat.provider..")`). Its totality
      rests on NO other module being able to compile a caller of
      MessagingAdapter.send/update/finalizeMessage. That property is
      enforced by maven-enforcer `bannedDependencies` rules in FOUR of
      the five ...


### Cluster 2: INJECTION @ `no-cite:7402284905750995026`

**kimi** (severity: medium, fix-class: trust-boundary-tightening)

- PROMISE: docs/spec/security.md §"Sanitizer output never contains `](`" —
      "every outbound body — chat reply, progress placeholder/finalize,
      periodic digest, group announcement — has its `](` adjacency broken
      before it reaches the transport, regardless of how it was assembled",
      and the diff's own new spec paragraph (security.md:629-649):
      "only infochat-provider depends on infoch...
- GAP (first 400 chars): The "universe of possible senders is the provider main source"
      inference is false even under today's module DAG. The guard only
      flags a call whose bytecode target owner is assignable to
      MessagingAdapter (OutboundChokepointArchTest.collectViolator,
      diff.patch lines 305-313) and only scans packages
      `app.zcat.infochat.provider..` (diff.patch lines 223-225). A
      stati...


### Cluster 3: INJECTION @ `no-cite:8763994711215282808`

**kimi** (severity: low, fix-class: trust-boundary-tightening)

- PROMISE: Same section — the OUTBOUND `](`-free property is promised
      for "every outbound body", and the diff's spec paragraph commits
      the guard to "MessagingAdapter.send, .update, or .finalizeMessage"
      as the mutating entry points whose bypass "would make the break
      miss an outbound body."
- GAP (first 400 chars): GUARDED_METHODS is a hardcoded name set {send, update,
      finalizeMessage} (diff.patch lines 218-219) with no assertion that
      the set equals the actual mutating surface of the MessagingAdapter
      SPI. If the interface later grows a fourth delivery-mutating method
      (e.g. `delete`, `react`, `edit`), direct calls to it bypass both
      the chokepoint and the guard silently — the buil...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **opencode-only**: AUDIT-EVASION @ `OutboundChokepointArchTest.java:225` (severity medium). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: INJECTION @ `no-cite:7402284905750995026` (severity medium). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.
- **kimi-only**: INJECTION @ `no-cite:8763994711215282808` (severity low). See `verdict-kimi.txt` for full PROMISE/GAP/REPRO.

