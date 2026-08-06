---
id: M1-779
title: "LLM replies leak prompt scaffolding and markdown to the reader"
status: pending
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE ADMIN-COMMAND CLOSED LIST. `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList()`
    pins that set against `docs/spec/commands.md`, and it is a spec-level
    closed list. This ticket adds NEW strip categories; it must not add,
    remove or reorder a single entry in that list.
  - >-
    PROMPT WORDING. `docs/design/05-llm-and-embeddings.md:450` already
    instructs "You write plain-text news summaries for a chat
    application. The reader cannot render markdown." Prompting is
    demonstrably insufficient — this is an enforcement ticket, not a
    prompt-tuning one. Do not edit the prompts.
  - >-
    THE DELIMITER FORMAT ITSELF. The per-call random marker stays
    exactly as it is; D21's guarantee rests on its unguessability, not
    on its shape being secret.
acceptance:
  - >-
    BOTH CATEGORIES ARE ONE CHANGE BECAUSE THEY SHARE ONE SITE AND ONE
    SPEC SECTION. They are filed together deliberately: two tickets each
    amending `security.md` §LLM output sanitizer would fuse.
  - >-
    Untrusted-content wrapper markers emitted by the model are stripped
    from the reply. A test feeds model output containing
    `<<<UNTRUSTED_CONTENT id="…">>>` … `<<<END id="…">>>` and asserts
    neither marker survives to the user, while the wrapped text itself
    does.
  - >-
    THIS IS NOT A D21 BREAK, AND THE TICKET MUST NOT CLAIM IT IS. The
    marker carries a per-call random id, so observing one leaked id does
    not help forge another. The defect is that internal scaffolding is
    shown to users. Severity framing matters for the redteam pass.
  - >-
    Markdown emphasis and horizontal rules are removed or downgraded to
    the D30 plain-text surface. A test asserts `**bold**` renders as
    `bold` and a `---` rule does not survive.
  - >-
    D30's ALLOWED formatting is preserved: inline single backticks,
    triple-backtick blocks, and bare URLs pass through untouched. Bare
    URLs were already correct in every observed reply and must stay so.
  - >-
    `docs/spec/security.md` §LLM output sanitizer records the two new
    strip categories.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  preserves:
    - >-
      LlmOutputSanitizerTest.matchSetEqualsSpecClosedList() — the
      admin-command closed list is untouched and its parity with
      docs/spec/commands.md still holds.
    - >-
      Backticked code and bare URLs survive sanitization unchanged.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D21
  - D30
reviews: []
overrides: []
---

## Why

Two separate LLM output artifacts reach users. Both are intermittent (the model
emits them sometimes), both are invisible to prompting, and both live at the same
enforcement point.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md`
§F4, §F5).

## Observed

**Scaffolding leak**, inside a `/summary` reply:

```
<<<UNTRUSTED_CONTENT id="e4cea7de-0f11-4a7d-9f88-d2ecd6bcae2e">>>
Série „Softwarová sklizeň" na Root.cz z 29. července 2026 se věnuje…
<<<END id="e4cea7de-0f11-4a7d-9f88-d2ecd6bcae2e">>>
```

**Markdown**, in a chat reply:

```
**Security flaws and fixes** – unfortunately, this is a large part of the news.
- **JanusCape**: a flaw allowing VM escape on Intel and AMD processors
---
```

## Expected

```
Série „Softwarová sklizeň" na Root.cz z 29. července 2026 se věnuje…
```

```
Security flaws and fixes – unfortunately, this is a large part of the news.
· JanusCape: a flaw allowing VM escape on Intel and AMD processors
```
