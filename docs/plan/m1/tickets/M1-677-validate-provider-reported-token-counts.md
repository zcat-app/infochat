---
id: M1-677
title: "Validate provider-reported token counts before they reach the metric counters"
status: done
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 5
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredLlmProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  - docs/spec/llm.md
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The `embedding.dimension` gauge. The audit paired it with the token
    counts, but it does not carry the same exposure: the vector must
    arrive inside the 1–8 MiB response-body cap (so the value is
    bounded), its tags are config-derived, and EmbeddingWorker
    independently checks every vector's length against
    `embedding_metadata.dimension` — a mismatch alerts the operator and
    skips the row rather than storing a wrong-dimension vector. Nothing
    to fix there.
  - >-
    The providers' response parsing (OpenAiCompatibleProvider,
    AnthropicProvider). Reading the reported counts is legitimate, and
    the Anthropic cache-token fold is deliberate; the constraint belongs
    at the one boundary both providers pass through, not duplicated per
    parser — the same siting argument M1-673 settled for the `model`
    label.
  - >-
    Building the cost-weighted rate cap itself (future-features §E7).
    This ticket makes the input trustworthy; whether and how to charge a
    bucket against it stays parked.
  - >-
    LlmMetrics' meter set, metric names, and tag keys. Dashboard-facing
    names stay stable; only the values recorded change.
  - >-
    Overflow of the Anthropic three-field input sum
    (`input_tokens + cache_read_input_tokens +
    cache_creation_input_tokens`, AnthropicProvider.java:267-271). An
    endpoint can pick three values whose 64-bit sum wraps to a small
    positive, and the decorator sees only the wrapped result — so no
    check sited at the decorator can detect it. Fixing it means
    range-checking the fields before the addition, which is inside the
    parser this ticket deliberately leaves alone (see the entry above);
    the wrapped value is in-range and therefore lands in the same
    "in-range lie" residual the spec now names explicitly rather than
    being a separate exposure. Raised by the 2026-07-22 redteam gate and
    consciously left here.
acceptance:
  - >-
    A new test in LlmObservabilityTest proves a response reporting
    NEGATIVE input/output token counts does not decrement or otherwise
    corrupt `llm.tokens.in` / `llm.tokens.out`. A Prometheus counter
    that moves backwards reads as a counter reset, so every `rate()`
    over the series silently mis-reports.
  - >-
    A new test in LlmObservabilityTest proves a response reporting an
    output-token count far above what the call could have produced is
    not recorded verbatim. The rule is: drop the usage record for that
    call and count it as unreported (never clamp — a clamped figure is
    indistinguishable from an honest max-length completion, so it hides
    the tampering instead of surfacing it).
  - >-
    The output bound is the cap the request actually carried, NOT "the
    per-task key if the operator happened to set one". No properties
    file in the repo sets `infochat.llm.<task>.max-tokens` and the setup
    wizard writes it for chat + summarizer only, yet
    OpenAiCompatibleProvider resolves an absent key to 1024 and sends it
    on every request (docs/design/05-llm-and-embeddings.md §"Every
    request carries max_tokens": "The default is a cap, not
    absent-means-uncapped"). A bound that goes unbounded when the key is
    absent therefore never fires for SECURITY_JUDGE / TAGGER / ENTITY /
    CLASSIFIER / TRANSLATOR in any shipped deployment. An absent,
    unparseable, or non-positive key resolves to the same 1024 default
    the providers send; a test pins that a task with no explicit key is
    still bounded. (Redteam M1-677 medium finding, 2026-07-22.)
  - >-
    The reported INPUT count is bounded above as well, not by sign
    alone. Micrometer counters are monotonic, so one reply reporting
    `prompt_tokens` near Long.MAX_VALUE permanently destroys the
    `llm.tokens.in` series for the JVM lifetime — the same
    "poisoned for the process lifetime" property M1-673 closed for the
    `model` tag. The bound is derived from the prompt the decorator
    itself holds (a UTF-8 byte ceiling over the system+user prompt,
    plus slack for provider-added template/tool overhead), so it needs
    no new config and cannot false-positive on an honest reply. A test
    pins it. (Redteam M1-677 low finding, 2026-07-22.)
  - >-
    The constraint lives at the single boundary every provider passes
    through (MeteredLlmProvider, which since M1-673 already holds the
    LlmRouter.ConfigReader needed to read the per-task bound), not in
    each provider's parser.
  - mvn -pl infochat-llm-adapter verify is green
  - >-
    docs/spec/llm.md §Bounded concurrency and observability records that
    provider-reported usage values are untrusted input, and the
    §Trust boundaries entry 9 residual sentence in docs/spec/security.md
    ("v1 records it into the token counters as reported") is updated to
    match what now ships. Both descriptions must state only what the
    code delivers and must name the residual accurately — including the
    Anthropic three-field sum below, which the boundary sees only after
    the addition. Prose promising a stronger check than ships is the
    defect the 2026-07-22 redteam gate caught the first time round.
test_plan:
  adds: []
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 914
      removed: 29
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-22
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §Trust boundaries entry 9 (as amended by this
      diff): "Provider-reported numeric usage (token counts) is checked at
      that same boundary before it reaches the counters: a negative count,
      or an output count above the generation cap the request itself
      carried, is impossible for an honest endpoint, and such a report is
      discarded whole rather than clamped." Mirrored in docs/spec/llm.md
      §Bounded concurrency and observability.
    gap: |
      The delivered upper bound is not "the generation cap the request
      itself carried" but "the per-task config key, if the operator set
      one" — and no .properties file in the repo sets it for any task, so
      the upper-bound half of the promised check never fires in a shipped
      default deployment. OpenAiCompatibleProvider.java:207 resolves an
      absent key to 1024 and :225 sends it, so the request DOES carry a
      cap; configuredOutputBound (MeteredLlmProvider.java:202-213) returns
      NO_OUTPUT_BOUND=Long.MAX_VALUE instead. The new test
      reportedCountsStillRecordForATaskThatConfiguresNoMaxTokens pins the
      gap as intended behavior.
    repro: |
      Deploy with the shipped application.properties (the wizard writes
      max-tokens for chat + summarizer only). Point a per-task base-url at
      a hostile endpoint and answer any SECURITY_JUDGE / TAGGER / ENTITY /
      CLASSIFIER / TRANSLATOR call with usage.completion_tokens =
      9_000_000_000. plausibleUsage accepts it (bound = Long.MAX_VALUE)
      and llm.tokens.out is incremented by it; the promised
      "calls.total outrunning the token counters" tamper signal never
      appears.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-07-22
    category: AUDIT-EVASION
    severity: low
    promise: |
      docs/spec/security.md §Trust boundaries entry 9 (as amended by this
      diff): "The residual is the in-range lie: a reply reporting a
      wrong-but-possible count is indistinguishable from an honest one."
    gap: |
      The input count is bounded by sign only
      (MeteredLlmProvider.java:174), with no upper bound of any kind, so
      the admitted residual is far wider than "wrong-but-possible": an
      endpoint may report prompt_tokens = Long.MAX_VALUE and it is
      recorded verbatim. Micrometer counters are monotonic, so one such
      reply permanently destroys the llm.tokens.in series for the JVM
      lifetime — the same "poisoned for the process lifetime" property
      M1-673 closed for the model tag. Secondary vector: the value the
      check sees for Anthropic is a three-field wire-controlled sum
      (AnthropicProvider.java:267-271) whose 64-bit addition can overflow
      to a small positive before the boundary ever sees it.
    repro: |
      Same hostile-endpoint setup. Return one well-formed reply with
      "usage":{"prompt_tokens":9223372036854775807,"completion_tokens":1}
      for any task — no config key required, the input side is unbounded
      in every configuration. llm.tokens.in jumps to ~9.2e18 and never
      comes back down.
    suggested_fix_class: input-sanitization

redteam_audits:
  - date: 2026-07-22
    verdict: FINDINGS
    base: addf27b59795e1873ba48a87a97e83b1bb3c71e6
    head: working-tree (uncommitted branch m1/M1-677-validate-provider-reported-token-counts)
    verdict_file: docs/plan/m1/redteam/M1-677-2026-07-22.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Gate audit at /m1-tick run step 4, ahead of review. Both findings are
      spec-vs-code mismatches this diff introduces: the spec prose it adds
      promises a stronger check than the code delivers (upper bound tied to
      the cap the request carried, vs. an operator key that is unset in
      every shipped config), and understates the input-side residual as an
      "in-range lie" when out-of-range input counts are admitted. Halted
      per run.md step 4 — not reviewed, not committed. Both load-bearing
      claims independently verified before the halt (repo-wide *.properties
      grep for max-tokens returns nothing; docs/design/05-llm-and-embeddings.md
      :172-176 confirms "The default is a cap, not absent-means-uncapped").
      Out-of-model items (absurd operator-set cap; unresolvable config
      expression) are both operator-config paths, trusted per §Threat model
      — advisory only, no follow-up ticket recommended.
  - date: 2026-07-22
    verdict: FINDINGS
    base: addf27b59795e1873ba48a87a97e83b1bb3c71e6
    head: working-tree (post-refine, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-677-2026-07-22.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Re-audit after the refine, per the rule that an in-branch fix
      invalidates the audit it answers. The medium closed. One low of the
      same class remained: the input criterion carried an undisclosed flat
      65_536-token overhead allowance that neither spec file mentioned,
      ~3 orders of magnitude above real overhead (verified independently:
      no "tools"/"functions" key exists anywhere in main, so both v1
      request bodies carry only model, max_tokens and the system+user
      strings). Treated as an unmet acceptance item rather than a new
      escalation — the refine's own criterion already required the prose to
      state only what the code delivers — and fixed in-band by tightening
      the allowance to 1024 and disclosing it in both spec files.
  - date: 2026-07-22
    verdict: CLEAN
    base: addf27b59795e1873ba48a87a97e83b1bb3c71e6
    head: working-tree (uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-677-2026-07-22.md
    out_of_model_count: 4
    note: |
      Third and final audit: CLEAN. All ten spec commitments this diff adds
      were checked and hold, including that recordLlmCall has exactly two
      call sites (both in the decorator), that decorator and providers read
      the byte-identical max-tokens key expression off the same injected
      Config so no divergence is constructible, and that the prompt-derived
      ceiling cannot false-discard an honest Anthropic cache-fold reply.
      Of the four out-of-model items, one was doc drift inside this diff (a
      test-setup comment still describing the pre-refine "unbounded degrade
      path") and was corrected in-band. The other three need no ticket now:
      an absurd operator-set cap is trusted config; the raw usage surviving
      on the returned LlmResponse has no reader outside the decorator and
      LlmResponse's javadoc already forbids one; and the absence of a
      booted-Quarkus test proving the @Decorator is applied is pre-existing
      (it equally underwrites M1-673's model-label defense) — that one is
      the only plausible follow-up ticket candidate.
clarity_check:
  date: 2026-07-22
  verdict: WARN
  warnings:
    - >-
      SPEC-REFS-RESOLVABLE: spec_ref 'docs/spec/security.md §Trust
      boundaries' is AMBIGUOUS (headings at lines 38 and 216); the
      developer resolves it to the one carrying numbered entry 9.
    - >-
      Self-check: census grep re-run at start returns exactly the six
      sites the §Census table disposes, same line numbers. Every
      ticket claim about existing code verified (provider parse sites,
      MeteredLlmProvider:93, LlmMetrics:88-95, M1-673's ConfigReader).
  blockers: []
---

# M1-677: Validate provider-reported token counts before they reach the metric counters

## Context

M1-673 expelled the wire-reported `model` string from the metric
**labels**. Two of the four auditors on that ticket's multi-auditor gate
(`docs/plan/m1/redteam-multi/M1-673-2026-07-22/`) independently observed
that the same untrusted response is still trusted for its numeric
**values**, and that observation survives checking:

- `OpenAiCompatibleProvider.java:302-306` accepts `prompt_tokens` /
  `completion_tokens` behind a `canConvertToLong()` guard and calls
  `asLong()` — no range check, no sign check. `AnthropicProvider.java:260-271`
  has the same shape and additionally folds
  `cache_read_input_tokens` + `cache_creation_input_tokens` into the
  reported input.
- `MeteredLlmProvider.java:93` passes that `response.usage()` straight to
  `LlmMetrics.recordLlmCall`, which increments `llm.tokens.in` /
  `llm.tokens.out` by the reported amounts (`LlmMetrics.java:88-95`).
- `canConvertToLong()` admits negatives, so a reply reporting
  `"prompt_tokens": -5` decrements a Prometheus counter — a monotonicity
  violation that reads downstream as a counter reset.

**Severity today is low and the ticket should not oversell it.** A
repo-wide grep finds no consumer of these counters other than the meters
themselves: no budget, no cap, no alert threshold in v1 reads them, so a
lying endpoint skews usage observability and nothing else. Unlike the
M1-673 label path there is no memory-growth component either — the meter
set is fixed, only the recorded numbers move.

What makes it worth closing now rather than parking is that the value is
already named as a future **decision input**: future-features §E7
("Cost-weighted LLM rate cap") proposes charging the rate-limit bucket by
"what a turn actually consumed (tool-loop iterations, or
provider-reported usage)". If that lands on top of unvalidated counts, a
hostile or compromised endpoint gets to choose how much of a sender's
budget each turn costs — an authorization-adjacent decision driven by
attacker-supplied data. Validating at the boundary now is a few lines;
retrofitting it under a rate cap later is not.

`docs/spec/security.md` §Trust boundaries entry 9 currently discloses
this residual in prose. This ticket closes it and updates that sentence.

## Acceptance

See the frontmatter. Negative counts cannot move the counters backwards;
an impossible output-token count is not recorded verbatim under a stated
rule; that rule's bound is the cap the request actually carried, so it
fires in shipped deployments rather than only where an operator set the
key; the input count is bounded above too; the constraint sits at the
shared decorator boundary; both spec files record the new state and name
the residual accurately.

## Out-of-scope

The `embedding.dimension` gauge (already bounded by the body cap and
independently guarded by EmbeddingWorker's per-vector dimensionality
check), the providers' own parsing, the E7 rate cap itself, and the
meter/tag naming surface. See the frontmatter.

## Census

The class is "every site where a value the wire response chose reaches
the metric surface". Enumerate it mechanically — re-run this at `start`
and confirm the six sites below are still the whole set:

```
grep -rn "metrics\.record\|metrics\.llmInflight" --include=*.java infochat-*/src/main
```

| Site | Wire-derived value? | Disposition |
|---|---|---|
| `MeteredLlmProvider.java:85` `llmInflight(task, provider)` | no — enum + delegate name | none needed |
| `MeteredLlmProvider.java:93` `recordLlmCall(..., response.usage())` | **yes — token counts** | **fix here** |
| `MeteredLlmProvider.java:99` `recordLlmCall(..., null)` (fail path) | no — usage is null, model is the `unknown` constant | none needed |
| `MeteredEmbeddingProvider.java:62` `recordEmbeddingCall(...OK)` | no — provider name + configured model | none needed |
| `MeteredEmbeddingProvider.java:64` `recordEmbeddingDimension(..., results.get(0).dimension())` | yes — vector length | out of scope: bounded by the 1–8 MiB body cap and independently guarded by EmbeddingWorker's per-vector dimensionality check (alert + skip, never stored) |
| `MeteredEmbeddingProvider.java:71` `recordEmbeddingCall(...FAIL)` | no — same as :62 | none needed |

Labels across all six are already non-wire-derived (M1-673 for the LLM
side; `infochat.embeddings.model` for the embedding side), so this
ticket is about recorded **values** only.

## Notes

- The enabling change already shipped: M1-673 gave `MeteredLlmProvider` a
  `LlmRouter.ConfigReader`, so the decorator can read
  `task.configPrefix() + "max-tokens"` the same way it now reads
  `+ "model"` — the clamp bound is available at the site without new
  wiring.
- **Decided (refine, 2026-07-22): drop the record, never clamp.**
  Clamping keeps a usable lower bound on cost observability while lying
  about the exact figure; dropping makes the gap visible as "unreported"
  rather than silently plausible, and reuses a state every consumer must
  already handle (providers that report no usage at all produce it).
  State the reasoning in the commit.
- **The absent-key default is load-bearing, not an edge case.** The
  first implementation attempt treated an absent `max-tokens` as "no
  bound" and was caught by the redteam gate: since no shipped properties
  file sets the key, that made the upper-bound check dead code in
  production. There are exactly three `LlmProvider` impls
  (`OpenAiCompatibleProvider`, `DeepSeekProvider` extending it,
  `AnthropicProvider`) and all three send `max_tokens` on every request;
  Anthropic reads the key with `config.getValue` (no default), so an
  absent key fails its startup scan and never reaches the decorator.
  Absent-key therefore implies the OpenAI-compatible 1024 default.
  `DeepSeekProvider.java:86-97` already duplicates that constant
  deliberately (`PARENT_DEFAULT_MAX_TOKENS`), so a second reader
  matching it is the established pattern here, not a new coupling.
