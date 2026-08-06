---
id: M1-778
title: "Generated prose is sometimes not in the scope's /lang language"
status: pending
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: [M1-777]
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/**
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyLanguageTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseLanguageTest.java
  - infochat-provider/src/test/java/**
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY BUNDLE FILE. The existing `reply.translation.unavailable` key is
    reused verbatim if a notice is needed. New keys collide with
    M1-781/M1-782 and, per the five-bundle parity rule, would need a
    twin in en/cs/es/ru/tr.
  - >-
    THE M1-437 FALLBACK LEGS. M1-777 owns the notice's firing
    condition; this ticket owns what language the prose is generated
    in. Do not re-litigate the identity check here.
  - >-
    Retrieval-side query anchoring (M1-746). That translates the QUERY
    into the corpus language and is a different leg with its own
    determinism constraints (D58).
acceptance:
  - >-
    THE FAILURE IS BIDIRECTIONAL — BOTH DIRECTIONS MUST BE ADDRESSED,
    and the diff must say whether they share one cause. Observed: (1)
    under `/lang cs` a chat reply came back entirely in English while
    the bundle-localized parts were Czech; (2) under `/lang en` one
    `/summary` cluster came back in Czech among English paragraphs.
  - >-
    THE `en` DIRECTION IS NOT AN ANCHOR-AVAILABILITY PROBLEM. The post
    that leaked Czech has `translation_done=true`, a populated
    `title_en`, and a 207-char `body_en`. A usable English anchor
    existed and was not used. Establish why the summarizer had the
    source-language body in hand at all.
  - >-
    A reply delivered to a scope is in that scope's language, or the
    user is told once, truthfully, that it could not be. Silence is not
    acceptable — the `en` direction currently emits no notice at all,
    which is worse than the `cs` direction.
  - >-
    Pinned by tests on both directions: a `cs` scope whose generator
    returns English, and an `en` scope whose cluster input is
    non-English, each produce a reply in the scope language (or the
    single reused notice).
  - >-
    BOTH FAILURES ARE INTERMITTENT — a re-run produced the correct
    language each time. Tests must therefore drive the failing input
    deterministically (a stubbed generator returning wrong-language
    text), never rely on observing the live model.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyLanguageTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseLanguageTest.java
  preserves:
    - >-
      Progress placeholders and the D58 provenance footer already
      localize correctly and must keep doing so — they were right in
      every observed case.
    - >-
      M1-759's anchor-first display block, verified working in this
      test round.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D29
  - D43
reviews: []
overrides: []
---

## Why

`/lang <code>` is specified as setting "per-scope output language"
(`docs/spec/commands.md:1052`). A reply in another language breaks the one
promise the command makes.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §F3).

## Observed

**Direction 1 — English body under `/lang cs`.** Fixed UI strings were Czech
("Pracuji na tom…", "Odpověď je založena na 8 příspěvcích…"), the model's prose
was entirely English.

**Direction 2 — Czech paragraph under `/lang en`,** silently, mid-reply:

```
Canonical introduced the Enterprise Store, available through Ubuntu Pro…
Tvorba interaktivních aplikací s GUI s využitím projektu GoGPU ukazuje, jak
otevřít okno a pomocí knihovny gg vykreslit 2D scénu…
```

## Expected

```
Canonical introduced the Enterprise Store, available through Ubuntu Pro…
An article on Root.cz demonstrates how to create interactive GUI applications
using the GoGPU project, covering how to open a window and render a 2D scene…
```

## Relationship to M1-777

`blocked_by: [M1-777]` is a serialization, not a logical dependency: both edit
`TranslationPipeline.java`, and M1-777's decision about how the pipeline learns
its input language is an input to this one. If the generator is made
deterministic about language here, M1-777's symptom becomes unreachable in
practice — but its check would still be wrong in principle, which is why it is
fixed first and separately.
