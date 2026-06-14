---
id: M1-330
title: "LLM providers: redact base-url credentials on parse-failure paths + reject userinfo at config boundary"
status: done
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is a mild under-statement for a security_relevant ticket closing a credential-leak at a config validation boundary; consider risk: medium. Does not block implementation."
  blockers: []
files_budget: 5
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The non-2xx host-only path (LlmHttpSupport.sendForBody, U-13) — already correct; this ticket aligns the sibling parse-failure paths to it, it does not re-touch sendForBody's host extraction.
  - The LlmHttpSupport.preview(responseBody) body-preview helper — unchanged; preview is a bounded body excerpt and is orthogonal to the URI/credential leak this ticket closes.
  - The api-key header routing in the providers — unchanged; this ticket steers operators to it but does not alter it.
acceptance:
  - "Every 2xx-but-malformed parse-failure message in the three providers prints uri.getHost() instead of the full uri, matching the deliberate U-13 host-only posture of the non-2xx path. Concretely: OpenAiCompatibleProvider (failed-to-parse, missing choices[], missing content), AnthropicProvider (failed-to-parse, missing content[], no text block), and OpenAiCompatibleEmbeddingProvider (failed-to-parse, missing data[], non-numeric element, shape mismatch) no longer concatenate the bare uri into the thrown LlmCallFailedException/EmbeddingCallFailedException message. providerLabel already disambiguates which provider/endpoint failed, so triage loses nothing."
  - "LlmHttpSupport.requireHttpBaseUrl rejects a base-url that embeds userinfo: after the scheme and host checks, if uri.getUserInfo() != null it throws IllegalArgumentException naming the property and pointing at the supported api-key property, so a credential-bearing base-url (https://user:pass@host) cannot enter the system at all. (M1 is greenfield — no compatibility shim per CLAUDE.md §No defensive code.)"
  - "A test pins the redaction: a provider whose configured base-url carries userinfo is rejected at requireHttpBaseUrl with a message that does NOT contain the userinfo; and a parse-failure on a 2xx-but-malformed body produces an exception message containing the host but not the full path/userinfo. The existing HttpProviderSharedPipelineTest.non2xxOmitsResponseBodyButKeepsHostAndStatus and AnthropicProviderTest.generateThrowsOnNon2xx stay green."
  - "AnthropicProvider's class Javadoc §Failure surface (lines 59-66) is corrected: it no longer claims a non-2xx reply 'carries a bounded body preview, which includes the inner Anthropic error.message' (U-13 dropped the body); it states the body is intentionally NOT in the exception (provider label + status + host only), citing U-13 as a stable decision anchor with the privacy rationale."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm (userinfo-rejection + parse-failure host-only cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 149
      removed: 24
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: 18feceed^
    head: 18feceed
    verdict_file: docs/plan/m1/redteam/M1-330-2026-06-14.md
    out_of_model_count: 3
    note: |
      CLEAN. Adversary found no gap between security.md §Secrets handling and
      the diff: userinfo rejected at requireHttpBaseUrl (message does not echo
      the credential) and parse-failure messages narrowed to host-only keep
      base-url credentials out of exception/log surfaces. 3 advisory
      out-of-model observations recorded in the verdict file; none block merge.
---

# M1-330: LLM providers — redact base-url credentials on parse-failure paths

## Context

Deep-review v5.5 (opus-48, `04-module-infochat-llm-adapter.md` F1) found that the
non-2xx path (`LlmHttpSupport.sendForBody`) was deliberately narrowed under U-13
to log/throw only the host — yet the 2xx-but-malformed parse paths in all three
providers throw the **full URI**. **Verified at source 2026-06-14:** every
parse-failure message concatenates `+ uri` (OpenAiCompatibleProvider.java:208,213,
220; AnthropicProvider.java:182,187,208; OpenAiCompatibleEmbeddingProvider.java:
179,184,193,207,222), while `requireHttpBaseUrl` (LlmHttpSupport.java:212-230)
validates scheme + host only — there is no `getUserInfo()` check.

The OpenAI-compatible wire shape supports inline credentials in the URL
(`https://apikey@host/v1`, `https://user:pass@host`), which `requireHttpBaseUrl`
accepts. When a 2xx reply then fails to parse (routine with small local models
emitting non-JSON/truncated JSON), these paths embed the full `uri` — including
`getUserInfo()` — into an exception the caller's retry/fallback harness logs.
U-13 is bypassed through sibling paths that were never narrowed; the leaked token
is an authentication credential, so this is an info-leak (SECURITY), not a style
nit.

The companion stale-doc item (opus-47 `04-module-infochat-llm-adapter.md` F1 —
AnthropicProvider §Failure surface still promising the dropped body preview) is
folded here because it describes the same U-13 surface in the same file.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Two-part fix (report Option A): close the config boundary (durable — makes the
  leak structurally impossible and steers operators to the `api-key` property)
  AND switch the parse messages to host-only (consistency with U-13). Option B
  (messages only, no userinfo rejection) patches one site and lets the next
  message-emitting path re-open it — rejected.
