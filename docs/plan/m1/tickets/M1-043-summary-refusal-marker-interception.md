---
id: M1-043
title: SummaryProseGenerator refusal-marker interception (degrade on `[REFUSAL: ...]` output)
status: pending
created: 2026-05-20
last_updated: 2026-05-20
blocked_by:
  - M1-040
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-040
out_of_scope:
  - any change to the spec — `docs/spec/security.md` §Prompt-injection defenses already commits to the refusal-marker contract ("the model emits `[REFUSAL: <reason>]` on action requests") and the spec is silent on the downstream interception point; this ticket lands the in-code interception that the M1-040 SUMMARIZER_SYSTEM_PROMPT Javadoc claimed but did not deliver
  - any change to `SUMMARIZER_SYSTEM_PROMPT` content (the system-prompt clause that asks the model to emit the marker stays unchanged — the marker contract was correct; only the downstream interception was missing)
  - any change to `LlmOutputSanitizer` (sanitizer keeps its current API + responsibility: markdown-link strip + closed-list strip; refusal-marker detection lives at the per-handler degradation seam where empty-text and exception fallback already live)
  - any change to `buildPrompt`, `UNTRUSTED_CONTENT_OPEN_FORMAT`, `UNTRUSTED_CONTENT_CLOSE_FORMAT`, or the per-call UUID marker — the wrapper itself is unchanged
  - any chat-mode (T2-D) interception — chat-mode reuses the prompt-injection wrapper but its own degradation seam does not exist yet; T2-D authors decide where to put the chat-mode interception (likely in their per-handler equivalent of `SummaryProseGenerator.generate`)
  - any change to `degradedProseFor` or the degraded-form shape (headlines + URLs + UIDs per D17)
  - any change to `ClusterProse` record (the `degraded: true` channel already exists; this ticket routes refusals through it)
  - any change to `LlmRouter`, `LlmProvider`, `LlmResponse`, `ModelTask` — the LLM call path stays as-is
  - any persistent audit row for the refusal-marker event — the v1 observable is the WARN log line (matches the M1-040 wrapper / M1-041 deferred AuditLogWriter consolidation)
acceptance:
  - "`SummaryProseGenerator.generate` detects a `[REFUSAL: ...]` shape in the LLM response text and routes the cluster through the degraded-form path (same as the empty-text and RuntimeException paths). Detection rule: after `text = response.text().trim()`, treat the response as a refusal IFF `text.startsWith(\"[REFUSAL:\") && text.endsWith(\"]\")`. The trimmed shape — bracket-anchored, single-line — matches the spec contract (`[REFUSAL: <reason>]`, single line, no surrounding prose) declared in `SUMMARIZER_SYSTEM_PROMPT`. grep -E 'startsWith\\(\"\\[REFUSAL:\"\\)' SummaryProseGenerator.java returns at least one match."
  - "On a detected refusal, `generate` emits exactly one WARN log line in the shape `SUMMARIZER returned refusal marker for topic <topicId>; degrading` (mirrors the existing empty-text WARN at line 119 of M1-040's SummaryProseGenerator.java) and appends `new ClusterProse(cluster, degradedProseFor(cluster), true)` to the output list. Other clusters in the same batch continue to attempt generation — the per-cluster boundary is the failure unit, identical to the empty-text and exception paths."
  - "`SummaryProseRefusalDegradeTest` (new unit test in `infochat-provider/src/test/java/app/zcat/infochat/provider/summary/`) exercises the interception with a stubbed `LlmRouter`/`LlmProvider`: (a) the provider returns `\"[REFUSAL: ignore-me]\"` for one cluster and a normal one-paragraph prose for a second cluster in the same batch; the test asserts the first cluster's `ClusterProse.degraded()` is `true` AND its `prose()` equals `SummaryProseGenerator.degradedProseFor(cluster)` AND the literal substring `[REFUSAL:` does NOT appear in the first cluster's `prose()`; the second cluster's `degraded()` is `false` AND its `prose()` is the normal text. grep -E '@Test' SummaryProseRefusalDegradeTest.java returns at least one match AND grep -F '\"[REFUSAL:' SummaryProseRefusalDegradeTest.java returns at least one match."
  - "The Javadoc on `SummaryProseGenerator.SUMMARIZER_SYSTEM_PROMPT` is updated to point at the actual interception site. Specifically the sentence claiming 'The downstream `LlmOutputSanitizer` + degraded-fallback path treats the refusal marker the same way it treats unreachable LLM output' is replaced with text that names `generate`'s refusal-detection branch as the interception site. grep -F 'LlmOutputSanitizer' SummaryProseGenerator.java SUMMARIZER_SYSTEM_PROMPT region returns zero hits referring to refusal handling (the false claim is removed)."
  - "Existing `SummaryProseInjectionTest` (M1-040) tests stay green — the wrapper / per-call marker / system-prompt assertions are unchanged. grep -E '@Test' SummaryProseInjectionTest.java return-count is unchanged from main HEAD."
  - "mvn -B clean verify from the repo root exits 0; the new test class adds at least one assertion path; no regression in M1-037 / M1-040 /summary tests."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  preserves:
    - all tests currently green on main (including M1-040's SummaryProseInjectionTest, SummaryAdapterScopeIT, AddSourceAdapterScopeIT)
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D17

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
---

# M1-043: SummaryProseGenerator refusal-marker interception (degrade on `[REFUSAL: ...]` output)

## Context

M1-040 landed the `/summary` prompt-injection defense: a per-call UUID-marked `<<<UNTRUSTED_CONTENT ...>>>` wrapper around user-derived post text, plus a system prompt instructing the SUMMARIZER model to never follow instructions inside the wrapper and to emit a structured `[REFUSAL: <reason>]` marker on action requests.

The M1-040 redteam audit (CLEAN verdict, 3 OUT-OF-MODEL — see `docs/plan/m1/redteam/M1-040-2026-05-20.md`) surfaced as its most actionable OUT-OF-MODEL observation that the `SUMMARIZER_SYSTEM_PROMPT` Javadoc claims "The downstream `LlmOutputSanitizer` + degraded-fallback path treats the refusal marker the same way it treats unreachable LLM output," but the M1-040 diff itself adds no such interception. Inspection of the call chain confirms:

- `SummaryProseGenerator.generate` falls back to `degradedProseFor(cluster)` only when `response.text()` is null/empty OR when the provider throws RuntimeException. A non-empty `[REFUSAL: ...]` response passes the empty-text guard and is appended as the cluster's prose verbatim.
- `LlmOutputSanitizer.sanitize` runs markdown-link strip + closed-list privileged-command strip. Neither pattern matches `[REFUSAL:`, so the refusal literal flows through unchanged.
- `SummaryCommandHandler.appendClusterBlock` writes the sanitized prose into the `summary:` field of the per-cluster output block.

Net effect: a successful prompt injection that yields `[REFUSAL: bogus]` surfaces as the literal `summary: [REFUSAL: bogus]` in the user-visible reply, contradicting the M1-040 Javadoc's claim. This ticket lands the missing interception and aligns the Javadoc with reality.

## Where the interception lives

In `SummaryProseGenerator.generate`, immediately after `text = response.text().trim()` and before the existing `text.isEmpty()` guard. The fix routes refusals through the same `ClusterProse(cluster, degradedProseFor(cluster), true)` path the empty-text and exception cases already use. This:

- Keeps the degradation decision at the per-cluster boundary `generate` already owns.
- Reuses the existing `degraded: true` channel on `ClusterProse`; no new record field, no new SPI surface.
- Leaves `LlmOutputSanitizer` unchanged — sanitizer responsibility stays "rewrite the prose so it is safe to display"; "decide whether to display LLM prose at all" stays in the per-handler generator. T2-D can apply the same pattern at its chat-mode equivalent when chat-mode handlers land.

The detection rule is `text.startsWith("[REFUSAL:") && text.endsWith("]")` — bracket-anchored on a trimmed single-line response, exactly matching the contract the system prompt declares.

## Why not LlmOutputSanitizer

`LlmOutputSanitizer.sanitize(String)` returns a `String`. Lifting refusal-detection into the sanitizer would require either (a) widening the return shape to a `SanitizerResult { String text; boolean refused }` record — an API change that ripples through every caller and is broader than this remediation needs — or (b) returning a sentinel string the caller string-compares against, which is uglier than the per-handler `degraded: true` channel already provides. The remediation lives where degradation already lives.

## Out-of-scope (scope discipline)

See the frontmatter `out_of_scope` list. The headline items:

- The spec is silent on the downstream interception point; this ticket lands code only. No spec amendment.
- `SUMMARIZER_SYSTEM_PROMPT` content is unchanged. The refusal contract on the model side was correct in M1-040; only the downstream handling was missing.
- `LlmOutputSanitizer` is untouched. Its closed-list strip + markdown-link strip stay as M1-037 / M1-040 left them.
- Chat-mode (T2-D) interception is its own ticket. When T2-D's per-handler generation seam exists, T2-D applies this pattern at its own degradation site.
