---
id: M1-673
title: "Stop wire-controlled model field reaching Micrometer tags"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 5
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredLlmProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  - docs/spec/llm.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The providers' response parsing (OpenAiCompatibleProvider,
    AnthropicProvider). Reading the response `model` field is legitimate;
    the defect is that the wire value reaches a meter tag (and a DEBUG log
    line) unconstrained. This ticket constrains the value at the decorator
    boundary, not the SPI payload.
  - >-
    Any other Micrometer meter in the repo. The audit grepped every module
    for wire-derived metric tags and found this the ONLY instance (provider
    tags are closed enums, operator-configured adapter names, or sealed
    scope kinds); re-auditing the metric surface is not part of this fix.
  - >-
    Changes to LlmMetrics' meter set or tag keys beyond what the
    constrained model label requires. Dashboard-facing metric names stay
    stable.
acceptance:
  - >-
    A new test in LlmObservabilityTest proves that a response carrying a
    MiB-scale distinct `model` string per call does NOT grow the meter
    registry: after N calls with N distinct hostile model values, the
    `llm.calls.total` meter count stays bounded (the exact bound the fix
    pins — e.g. one `invalid` bucket, or the single operator-configured
    model id), while a normal response model still produces its meter.
  - >-
    A new test in LlmObservabilityTest proves the constraint applied at
    the MeteredLlmProvider boundary: the tag value is either the
    operator-configured model id (preferred fix — the wire value is never
    a tag) or a length-capped, charset-restricted, else-`invalid` mapping
    of it. State which in the commit; the audit's probe showed
    "unconstrained" is the only wrong answer.
  - >-
    The DEBUG log line at MeteredLlmProvider that echoes the wire model
    string logs the same constrained value the tag carries.
  - mvn -pl infochat-llm-adapter verify is green
  - >-
    docs/spec/llm.md §Bounded concurrency and observability records that
    LLM-call metric tags are never wire-derived values.
test_plan:
  adds: []
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Bounded concurrency and observability
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-673: Stop wire-controlled model field reaching Micrometer tags

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
LLM-1) verified that the chat providers copy the response body's `model`
field — an arbitrary endpoint-chosen string bounded only by the ≤8 MiB body
cap (`OpenAiCompatibleProvider.java:298-299`,
`AnthropicProvider.java:256-257`) — into `LlmResponse.model()`, whose ONLY
consumer (repo-wide grep) is `MeteredLlmProvider.java:65-67`, which passes
it unsanitized as the `model` tag on the `llm.calls.total` /
`llm.latency.ms` / `llm.tokens.*` meters (`LlmMetrics.java:81-96`). Each
distinct tag value creates a meter the registry retains for the JVM
lifetime, tag string included — runtime-probed on micrometer-core 1.16.3
(`.scratch/MeterProbe.java`): 10,000 calls with distinct model values →
10,001 retained meters, and the registry holds the identical 4 MiB wire
`String` instance (`==`) after the caller drops its reference. A hostile or
compromised endpoint therefore gets a persistent memory-amplification
channel: a few hundred MiB-scale distinct model strings retain GB-class
heap and OOM the service. The module's own bounded body read exists for
exactly this attacker class ("a pathological multi-GB reply from a
misbehaving or hostile endpoint cannot OOM the JVM") but bounds only the
*transient* body — the meter-tag path makes wire bytes permanent.

## Acceptance

See the frontmatter. The meter registry stays bounded under hostile
distinct model values; the tag (and the DEBUG echo) carries the
constrained value; the spec records the never-wire-derived-tag rule.

## Out-of-scope

The providers' response parsing, every other meter in the repo (the audit
already grepped for wire-derived tags — this is the only instance), and
the meter/tag naming surface. See the frontmatter.

## Notes

- Preferred fix per the audit: tag with the operator-configured model id
  (the value `configFor` resolves per task) instead of the wire-reported
  model — the deployment's own config is the cardinality-bounded source.
  The sanitize-the-wire-value alternative (cap ≤64–128 chars, safe
  charset, else `invalid`) is acceptable if the wire value is considered
  load-bearing for drift detection; the constraint MUST live at the
  decorator boundary either way.
- Finding detail, falsification history, and the probe: the audit report
  (`kimi-audit.md` under `.scratch/`) §LLM-1 (module 3).
