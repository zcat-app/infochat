---
id: M1-610
title: "Eval + guard: DeepSeek judge reasoning-ON quality; code-enforce the reasoning/max_tokens coupling"
status: done
created: 2026-07-12
last_updated: 2026-07-12
clarity_check:
  date: 2026-07-12
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 6
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
provenance: >-
  M1-608 (DeepSeek per-task reasoning toggle, merged 2026-07-12 @bc822c2d)
  shipped the toggle DEFAULTED OFF and explicitly deferred TWO things to a
  separate eval (M1-608 out_of_scope): (1) whether flipping any task to
  reasoning-ON actually improves quality is an untested hypothesis; (2) the
  M1-608 redteam (docs/plan/m1/redteam/M1-608-2026-07-12.md) raised an
  OUT-OF-MODEL item: the class JavaDoc promises that enabling reasoning on the
  SECURITY_JUDGE requires raising that task's max-tokens (so reasoning tokens
  cannot crowd out the verdict and fail-open), but NOTHING in the code enforces
  that coupling — it is an honor-system comment. This ticket resolves both: it
  runs the reasoning-ON-vs-OFF quality eval for the judge, and it makes the
  coupling STRUCTURAL (code-enforced) so a future reasoning-enable cannot
  silently regress the security boundary. Distinct from M1-609 (local vs remote
  cost/throughput) which touches reasoning only as one remote comparison option.
out_of_scope:
  - >-
    Flipping reasoning ON in the runtime deployment config
    (prod/runtime/application.properties infochat.llm.security.reasoning-effort).
    That file is operator-owned / gitignored; the actual production flip is an
    operator step gated on this eval's recommendation, not a code change here.
    This ticket may land the code-enforced coupling guard and a documented
    recommendation, but it does not change any shipped default (reasoning stays
    OFF for every task unless the eval concludes ON is a net win, and even then
    the runtime switch is operator-applied).
  - >-
    The M1-608 toggle MECHANISM itself (the DeepSeekProvider customizeRequestBody
    seam, the OpenAiCompatibleProvider parent seam, REMOTE_PROVIDER_NAMES). Those
    landed in M1-608 and are correct; this ticket USES the toggle and hardens its
    max-tokens coupling, it does not re-open the seam design.
  - >-
    The local-vs-remote judge/tagger question (that is M1-609). This ticket holds
    the provider fixed at remote DeepSeek and asks only whether reasoning-ON vs
    OFF is the better remote-judge security bet.
  - >-
    Enabling reasoning on non-judge tasks in production. The eval MAY measure the
    tagger as a secondary data point, but the security-relevant deliverable is
    the SECURITY_JUDGE decision + the coupling guard; no other task's default
    changes.
  - >-
    Embeddings (nomic-embed-text, D54) — unrelated SPI, untouched.
acceptance:
  - >-
    NAMED TEST — the coupling guard is code-enforced, not a comment. A
    DeepSeekProvider (or its startup-scan) test asserts that when a task's
    reasoning-effort is set to a depth AND that task's max-tokens is below a
    documented floor sufficient for reasoning + the answer, the startup config
    scan FAILS naming the offending task and both properties (mirroring the
    parent's non-positive-max-tokens guard) — so a reasoning-ON judge with an
    unraised max-tokens cannot boot into a silent fail-open. Reasoning OFF (the
    default) is unaffected: the guard only fires when reasoning is enabled.
    Red-before/green-after on the guard.
  - >-
    A findings report (docs/plan/m1/spikes/M1-610-judge-reasoning.md or similar)
    measuring, on a shared HUMAN-labelled sample using the production
    security-judge prompt template against remote DeepSeek (deepseek-v4-flash),
    the judge's injection-detection quality with reasoning OFF vs reasoning ON at
    a representative depth. Report the FALSE-NEGATIVE rate (injection/malware
    labelled BENIGN — the unsafe direction that admits a payload) SEPARATELY from
    the false-positive rate, plus label-format validity. Reuse / extend the
    2026-07-12 .scratch eval scripts and the M1-609 labelled set where they
    overlap; do not re-collect labels already curated there.
  - >-
    The report measures the COST of reasoning-ON: per-call latency and
    reasoning-token consumption, and the max-tokens value needed for reasoning +
    the verdict to fit WITHOUT truncation — this is the empirical basis for the
    guard's documented floor and for the recommendation.
  - >-
    A recommendation stating ONE of: enable reasoning for the judge (at which
    depth and which max-tokens floor) or keep it OFF, justified by the measured
    quality delta against the measured latency/token cost and the fail-open (empty
    or truncated verdict) rate. If the recommendation is ENABLE, the report names
    the exact operator config lines to add (the out-of-scope runtime flip a human
    then applies); if KEEP OFF, no shipped default changes and only the guard +
    report land.
  - >-
    mvn verify is green from the repo root (the coupling guard is a Java change,
    so the diff is testable — not inert).
test_plan:
  adds:
    - >-
      A DeepSeekProvider reasoning/max-tokens coupling-guard test (startup-scan
      fails when reasoning-effort is enabled on a task whose max-tokens is below
      the floor; reasoning-OFF unaffected).
    - >-
      A reusable judge reasoning-ON-vs-OFF eval harness (standalone script) plus
      its human-labelled sample fixture, seeded from the 2026-07-12 .scratch
      scripts.
  modifies:
    - >-
      DeepSeekProvider.java — add the max-tokens floor enforcement to the
      existing reasoning-effort resolution / assertTaskConfigResolvable override
      (the M1-608 method that already validates the reasoning-effort value);
      enumerate the exact assertion at start. Reasoning-OFF behaviour and the
      request-body assembly are unchanged.
  preserves:
    - all tests currently green on main
    - >-
      the M1-608 default-OFF behaviour: with reasoning-effort unset the assembled
      body still carries thinking:{type:disabled} and no guard fires.
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D56
reviews:
  - round: 1
    date: 2026-07-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 552
      removed: 9
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-12
    verdict: CLEAN
    base: merge-base with main (pre-commit)
    head: m1/M1-610 working tree
    verdict_file: docs/plan/m1/redteam/M1-610-2026-07-12.md
    out_of_model_count: 2
    note: |
      Pre-commit audit (run.md step 5 gate). CLEAN — no gap between the threat
      model and the diff; the change removes a fail-open path (adds a
      fail-closed startup guard) rather than adding attack surface. Two
      out-of-model observations, both requiring trusted operator config
      (reasoning is opt-in) and both describing the documented release-as-READY
      Stage-2 default: (1) the 4000 floor is a probabilistic mitigation not a
      proof — deferred to the future operator enable-reasoning decision, the
      report already caveats it; (2) the guard is startup-scan-only, mirroring
      the pre-existing parent requirePositiveMaxTokens posture the spec accepts.
      Neither warrants a follow-up ticket now.
---

# M1-610: Eval DeepSeek judge reasoning-ON + code-enforce the reasoning/max_tokens coupling

## Context

M1-608 shipped a per-task DeepSeek reasoning toggle
(`infochat.llm.<task>.reasoning-effort`) defaulted OFF for every task, and
deliberately deferred two questions to a separate eval (its `out_of_scope`):

1. **Does reasoning-ON actually help?** Turning DeepSeek "thinking" on for the
   `SECURITY_JUDGE` is *plausibly* better at spotting prompt-injection, but the
   benefit is unmeasured and it costs latency + reasoning tokens.
2. **The coupling is a comment, not a guard.** The M1-608 redteam
   (`docs/plan/m1/redteam/M1-608-2026-07-12.md`) flagged, as an OUT-OF-MODEL
   item, that the class JavaDoc *promises* enabling reasoning on the judge
   requires raising that task's `max-tokens` — because reasoning tokens share the
   completion budget, and a verdict truncated/emptied by reasoning routes to the
   Stage 2 infra-failure path whose default is release-as-READY (a silent
   fail-open of the actual security boundary). Nothing in the code enforces that
   coupling today; it is dormant only because the shipped default is OFF.

This ticket resolves both: it runs the reasoning-ON-vs-OFF quality eval for the
judge, and it makes the coupling **structural** (code-enforced at the startup
config scan) so a future reasoning-enable — whether by this ticket or an
operator later — cannot boot into a silent fail-open.

## Acceptance

The behavioral contract (mirrors the YAML `acceptance:`):

1. NAMED TEST: the reasoning/max-tokens coupling is code-enforced — the startup
   config scan fails, naming the task and both properties, when a task has
   reasoning-effort set to a depth while its `max-tokens` is below a documented
   floor sufficient for reasoning + the verdict. Reasoning OFF (the default) is
   unaffected. Red-before/green-after.
2. A findings report comparing the judge's injection detection reasoning OFF vs
   ON on a human-labelled sample with the production prompt, reporting the
   false-NEGATIVE rate separately from false-positives, plus format validity.
3. The report measures reasoning-ON latency + token cost and the `max-tokens`
   value needed to avoid truncation (the empirical basis for the guard's floor).
4. A recommendation — enable (with depth + max-tokens) or keep OFF — justified by
   the measured quality delta vs cost vs fail-open rate. ENABLE names the exact
   operator config lines; KEEP-OFF changes no shipped default.
5. `mvn verify` green from the repo root (the guard is a Java change).

## Out-of-scope

See the YAML `out_of_scope`. In short: this ticket does NOT apply the production
runtime flip (operator-owned, gitignored — gated on the recommendation), does
NOT re-open the M1-608 toggle mechanism, does NOT touch the local-vs-remote
question (M1-609), and changes no non-judge task's default. It modifies exactly
one pre-existing production file — `DeepSeekProvider.java`, extending the
`assertTaskConfigResolvable` override M1-608 already added — with the max-tokens
floor check; the reasoning-OFF path and request-body assembly stay byte-identical.

## Notes

- **The guard is the always-lands deliverable; the eval decides the flip.** Even
  if the eval concludes "keep OFF," the coupling guard is worth landing: it turns
  the JavaDoc's process-promise into a boot-time invariant, so the fail-open the
  redteam described is structurally impossible the moment anyone (this ticket or a
  future operator) sets reasoning-effort without a matching max-tokens.
- **Floor value comes from measurement, not a guess.** Acceptance item 3 measures
  how many tokens reasoning-ON actually consumes on real judge prompts; the
  guard's documented `max-tokens` floor should be set from that number (reasoning
  budget + the small fixed verdict-label budget), not an arbitrary constant.
- **DeepSeek is not ground truth** (M1-609 finding): a corpus post DeepSeek
  labelled INJECTION read as ordinary news. The judge eval MUST score against
  human labels, mixing real corpus samples with synthetic adversarial inputs.
- **Feeds / fed by M1-609.** M1-609 (local vs remote) asks whether a *local*
  judge beats a *remote reasoning* judge on security-per-cost; this ticket
  produces the "remote reasoning judge" quality number that comparison needs.
  Share the labelled sample set between the two where possible.
- **Adjacent code:** `DeepSeekProvider.assertTaskConfigResolvable` /
  `resolveReasoningDepth` (the M1-608 startup-scan override this guard extends);
  the parent's `requirePositiveMaxTokens` startup guard is the pattern to mirror.
- Starting scripts: the 2026-07-12 `.scratch` deepseek_smoke / local_eval; the
  M1-608 body-proof scripts.
