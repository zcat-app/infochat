---
id: M1-432
title: "LlmRouterStartupGuard remote-disclosure WARN must cover the language-capability route"
status: done
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 3
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The local-only FATAL branch (LlmRouterStartupGuard.java:241-249) is NOT changed — it already covers the language route correctly; this ticket only brings the non-local-only disclosure WARN up to the same coverage."
  - "The existing per-route WARN loop in warnRemoteLlmTaskRoutes is unchanged for the override / default axes; the only addition is the languages-key axis."
  - "No per-task granularity is added to the language-route WARN: the language-capability route has no per-task override (it is provider-scoped), so a provider-scoped WARN line matches the route. A provider reachable via more than one axis (e.g. default AND languages key) may emit more than one WARN line; that is acceptable — each line names a distinct off-host route."
  - "OpenAiCompatibleProvider is not in REMOTE_PROVIDER_NAMES and remains untouched."
  - "design §5.10 is a design note, not amended here; the spec promise being honored is docs/spec/llm.md §Per-task routing rules."
acceptance:
  - "warnRemoteLlmTaskRoutes additionally emits a WARN for every provider in REMOTE_PROVIDER_NAMES that is made reachable by a non-English `languages` declaration, reusing the same primitive the fatal branch uses — nonEnglishLanguages(snapshot.get(languagesKeyFor(provider))) — so the non-local-only disclosure posture decides 'off-host' on the same three axes the local-only fatal branch checks (base-url, effective provider, languages key). The emitted line names the provider and the reachable non-English languages and states that post-derived output will leave the host."
  - "A test under infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing asserts that a snapshot with a remote provider reachable ONLY via its non-English languages key (no per-task override, local default provider, not local-only posture) produces a disclosure WARN naming that provider and language — using the existing CapturingHandler the routing tests already use (see LlmRouterStartupGuardRedactionTest / LlmRouterStartupGuardLocalOnlyTest)."
  - "A test asserts the negative case: an all-English (or absent) languages key for the remote provider produces no extra language-route WARN."
  - "All tests currently green on main remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing (language-route disclosure-WARN test, mirroring the existing CapturingHandler guard tests)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 150
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-23
    verdict: CLEAN
    base: main
    head: working-tree (m1/M1-432 branch, uncommitted, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-432-2026-06-23.md
    out_of_model_count: 0
    note: |
      Pre-commit redteam (between review APPROVE and commit) on this
      security_relevant ticket. CLEAN — the diff adds a startup disclosure
      WARN for the language-capability privacy route, reusing the
      nonEnglishLanguages primitive the local-only fatal branch already
      computes. No data-flow change; no auth/authz/ban/audit/input surface
      touched. No remediation needed.
clarity_check:
  date: 2026-06-23
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-432: LlmRouterStartupGuard remote-disclosure WARN must cover the language-capability route

## Context

The 2026-06-23 `/deep-code-review full` run
(`.reviews/deep-review/full-2026-06-23-0957/`) surfaced one **medium** finding —
the only non-low across the whole run
(`04-module-infochat-llm-adapter.md#F1`).

`LlmRouter.forTask` has three ways a remote provider becomes the *effective*
provider for a task: a per-task override (priority 1), the default provider
(priority 3), and — for `SUMMARIZER`/`TRANSLATOR` with a non-English scope — the
priority-2 language-capability branch. The two startup-guard postures disagree
on whether that third axis counts as an off-host route:

- The **local-only FATAL** branch (`LlmRouterStartupGuard.java:241-249`) *does*
  inspect the languages key: a deployment that declares
  `infochat.llm.anthropic.languages=cs` is a fatal conflict under local-only,
  because that key alone routes non-`en` summarizer/translator calls off-host.
- The **non-local-only disclosure WARN** (`warnRemoteLlmTaskRoutes`,
  `LlmRouterStartupGuard.java:291-315`) does *not*: it walks only per-task
  overrides and the default provider. So a deployment with
  `default.provider=openai-compatible` (local Ollama) plus
  `anthropic.languages=cs` routes `cs`-scope post-derived prose to Anthropic and
  emits **zero** disclosure — the exact route the guard calls fatal under the
  other posture.

This contradicts the guard's own documented invariant that the two postures
"cannot drift on what counts as an off-host route," and it weakens the
privacy-disclosure intent of `docs/spec/llm.md` §Per-task routing rules
(the operator's "did I accidentally enable remote?" audit line).

Re-verified at source on 2026-06-23 before filing:
`warnRemoteLlmTaskRoutes` at line 291; the languages-aware fatal logic at
lines 241-243 (`languagesKeyFor`, `nonEnglishLanguages`); `REMOTE_PROVIDER_NAMES`
at line 154.

## Acceptance

See frontmatter. One behavior change: the disclosure WARN gains the
languages-key axis, reusing the `nonEnglishLanguages(...)` primitive the fatal
branch already computes, so both postures decide "off-host" on the identical
three axes. Two tests (positive: language-only route warns; negative: English
languages key does not), built on the `CapturingHandler` the routing tests
already use.

## Out-of-scope

See frontmatter. The fatal branch is untouched, the existing override/default
WARN loop is untouched, and no per-task granularity is added to the
(provider-scoped) language route.

## Notes

- **Source map** (verified 2026-06-23): finding
  `04-module-infochat-llm-adapter.md#F1`. The fix reuses `languagesKeyFor` and
  `nonEnglishLanguages` (already present, lines 241-243) inside
  `warnRemoteLlmTaskRoutes`.
- **security_relevant: true** — this is a privacy-disclosure (off-host data
  routing) audit-line gap; it invites a `/redteam` pass even though it adds a
  log line rather than changing data flow.
- Full reports: `.reviews/deep-review/full-2026-06-23-0957/` (`00-summary.md`
  first).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-432-router-guard-language-route-disclosure.md
```
