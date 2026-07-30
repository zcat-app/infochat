---
id: M1-731
title: "Retire infochat.digest.category-summary-enabled and reclaim the generateRollup name"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-722
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-731: retire the category-summary flag

> **Skeleton from the M1-722 decompose (2026-07-30).** `acceptance`,
> `out_of_scope` and the sizing fields still need authoring. The census below
> was re-verified live during M1-722's third plan pass and can be carried
> across as-is.

## Context

`infochat.digest.category-summary-enabled` gates a roll-up PREFIX above the
digest's per-cluster prose, and ships default-`false`. The decomposition of
M1-722 makes the roll-up the category body outright (M1-732), so the gate loses
its last caller. Retiring it is behaviour-neutral at the shipped default, which
is why it is carved out as the first, independent child: it clears
`DigestRenderer` / `DigestRendererSectionsTest` churn out of M1-732's way and
unblocks M1-728, whose only stated reason for sequencing behind M1-722 is that
both edit `CategoryRollupGenerator.java`.

Once the gated wrapper is gone, `generateRollupUnconditional` is the only entry
point and reclaims the plain name — an orphan this change itself creates, so
the rename is in scope.

## Census

```bash
grep -rn "category-summary-enabled\|categorySummaryEnabled\|generateRollupUnconditional" \
  --include=*.java --include=*.properties --include=*.md . \
  | grep -v '^./.bench' | grep -v '^./docs/plan'
```

The acceptance verification is that this same grep returns ZERO hits.
`docs/plan` is excluded because closed tickets (M1-642, M1-700, M1-722) are a
historical record and are not rewritten.

| Site | Disposition |
|---|---|
| `CategoryRollupGenerator.java:103-104` | **delete** — the `@ConfigProperty` field |
| `CategoryRollupGenerator.java:126` | **delete** — the short-circuit branch |
| `CategoryRollupGenerator.java:125` `generateRollup` | **delete** — the gated wrapper |
| `CategoryRollupGenerator.java:24` | **delete** — javadoc describing the flag |
| `CategoryRollupGenerator.java:135,146` | **update** — javadoc + the declaration, renamed |
| `DigestRenderer.java:134` | **delete** — the prefix call, `generateRollup`'s only production caller |
| `DigestRenderer.java:46-47`, `:327` | **delete** — comments describing the gate |
| `DigestRenderer.java:315`, `:442` | **update** — two javadoc `@link`s, renamed |
| `DigestRenderer.java:361` | **update** — the `--short` call site, renamed |
| `CategoryRollupGeneratorTest.java:42,75,101,116,130` | **update** — drop the flag setup, keep every assertion |
| `CategoryRollupGeneratorTest.java:56-66` (incl. `:60`) | **delete** — `flagOffYieldsNoRollupAndNoLlmCall` asserts only the flag's off-state |
| `DigestRendererSectionsTest.java:140-153` | **update** — the anonymous `generateRollup` override and its comment |
| `DigestRendererSectionsTest.java:273-283` | **update** — `RecordingRollupGenerator`, the SECOND `generateRollup` override |
| `RetryCommandHandlerTest.java:778,806` | **update** — `@Override` renamed with the method |
| `SummaryCommandHandlerTest.java:1624,1656` | **update** — `@Override` renamed with the method |
| `docs/spec/commands.md:1983` | **delete** — the "Optional per-category roll-up" paragraph |
| `docs/design/03-commands.md:465-466` | **update** — the `--short` bypass aside |

**The identifier grep has a known blind spot** that cost M1-722 two gates: it
cannot see a subclass overriding a method it never names in text. The subtype
search is the complement, and it is what surfaces the four `@Override`s above:

```bash
grep -rn "class .* extends \(DigestRenderer\|CategoryRollupGenerator\)" \
  --include=*.java infochat-provider/src
```

## Acceptance

*To author.* The contract is narrow: every site above disposed as stated, the
verification grep at zero, and no assertion lost — the five
`CategoryRollupGeneratorTest` sites keep the behaviour each pins (determinism
across calls, localization, sanitization, failure handling); only the flag
setup goes. The single deletion is `flagOffYieldsNoRollupAndNoLlmCall`, whose
entire subject is the flag's off-state, so it is dead code removed rather than
coverage lost.

## Out-of-scope

*To author.* At minimum: `CategoryRollupGenerator.buildPrompt`'s CONTENT (that
is M1-728), and any digest render-shape or mode change (that is M1-732). This
ticket deletes the flag that gates whether the roll-up runs; it does not touch
what the roll-up asks for or where its output lands.

## Notes

Retired means deleted, not left inert. A config key kept "just in case" after
its last caller goes is a feature flag by another name, which `CLAUDE.md`
§"No defensive code" forbids outright in a greenfield M1.

`DocumentedConfigKeyParityTest` (M1-708) gates the key, and it is not in
`documented-config-key-exemptions.txt` — so the `@ConfigProperty` deletion and
the doc deletions MUST land in the same diff. No exemptions-file edit is needed
or permitted.

Verified during M1-722's pass 3: the key appears in `docs/` (excluding
`docs/plan`) at exactly two sites — `docs/spec/commands.md:1983` and
`docs/design/03-commands.md:466`. It does **not** appear in
`docs/design/07-deployment.md`, contrary to M1-722's acceptance item 22, and
that file has no §"Configuration surface" heading at all (its headings are
`## 7.3 Configuration sources and precedence` and
`## 7.4 Canonical application.properties`).
