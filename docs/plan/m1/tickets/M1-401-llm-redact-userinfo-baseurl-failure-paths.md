---
id: M1-401
title: "llm: redact userinfo on requireHttpBaseUrl failure paths"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 3
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The userinfo-present branch's existing no-echo behavior (already correct, established by M1-330) — preserved unchanged; it must still NOT echo baseUrl.
  - OpenAiCompatibleProvider / AnthropicProvider and any other base-url consumer — only the shared LlmHttpSupport validator changes.
  - The validation rules themselves (the http(s) scheme set, the host requirement, the userinfo rejection) — what is rejected is unchanged; only the diagnostic-message content is made leak-safe.
acceptance:
  - "Each requireHttpBaseUrl failure branch that echoes the base-url — the URI-parse-failure branch, the non-http(s)-scheme branch, and the missing-host branch — routes the echoed value through a userinfo-redacting helper, so a credential-bearing base-url cannot appear verbatim in the thrown IllegalArgumentException message (and therefore the boot log)."
  - "The userinfo-present branch keeps its existing no-echo posture (it already omits baseUrl); this ticket does not regress that."
  - "A new test in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl asserts requireHttpBaseUrl on a malformed credential-bearing base-url (e.g. a literal space in the password, which makes new URI(...) throw) produces a message that does NOT contain the credential substring."
  - "BaseUrlCredentialRedactionTest and LlmHttpSupportTest remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (malformed credential-bearing base-url redaction test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-401: redact userinfo on requireHttpBaseUrl failure paths

## Context

Deep-review full (2026-06-19) llm-adapter finding **F1** (SECURITY). Verified at
source 2026-06-19:

`LlmHttpSupport.requireHttpBaseUrl`
(`infochat-llm-adapter/.../impl/LlmHttpSupport.java:208-237`) deliberately does
NOT echo `baseUrl` in its userinfo-rejection branch (line 232-235) — M1-330
established that echoing a credential-bearing URL re-leaks the very credential the
check exists to keep out of diagnostics, and `BaseUrlCredentialRedactionTest` pins
this. But three sibling branches in the same method DO echo the raw `baseUrl`: the
`URISyntaxException` parse-failure branch (line 213-214), the bad-scheme branch
(217-219), and the missing-host branch (222-224). A base-url that both embeds a
credential AND fails `new URI(...)` (e.g. a literal space in the password) throws
at line 213 first, and the credential lands in the `IllegalArgumentException` and
hence the startup-guard boot log — re-opening exactly the leak class M1-330 closed,
on a sibling branch of the same method.

Operator-supplied secret, so this is a misconfiguration-time leak rather than an
attacker-driven one — but the config boundary is precisely where this validation is
supposed to be airtight.

## Acceptance

See frontmatter. The shape: a small `redactUserInfo`-style helper masks any
`scheme://USER:PASS@host` userinfo span before the value is echoed, applied to the
three echoing branches; the userinfo-present branch is left as-is (it already
echoes nothing).

## Out-of-scope

See frontmatter. This is a diagnostic-message hardening only — no change to which
inputs are accepted or rejected. The textual scrub is a heuristic (the structural
`URI` parser already refused the input), so it masks the whole userinfo span rather
than just the password, which is the safe direction.

## Notes

- Decision-record anchor: the invariant being restored is M1-330's
  "no requireHttpBaseUrl branch echoes userinfo."
- Adjacent code: the userinfo branch (LlmHttpSupport.java:232) is the existing
  no-echo pattern to match.
- Alternative considered (Option B): drop `baseUrl` from the parse-failure message
  entirely. Rejected as the primary because echoing the offending value is the most
  useful part of the message for the common non-credential typo, and
  `URISyntaxException.getMessage()` may itself quote an input fragment, so dropping
  the echo is not a complete guarantee on its own.
