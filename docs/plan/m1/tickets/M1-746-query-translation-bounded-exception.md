---
id: M1-746
title: "Query leg: translate a non-English search query into the corpus anchor language under D58's four conditions"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by:
  - M1-749
files_budget: 13
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslatorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolHybridIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/RetrievalWorldPredicateIT.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Query rewriting, expansion, HyDE and cross-encoder re-ranking. D58
    defers all four and the amendment explicitly does NOT reopen them. A
    diff that adds terms to a query, or reorders results by model output,
    has violated the decision this ticket implements.
  - >-
    LANGUAGE DETECTION over the query text. D58 condition (c) is that the
    source language comes from the scope's declared `/lang`. Inferring it
    would make the retrieved SET depend on a classifier's output, which is
    the D19 boundary this exception was written to stay inside.
  - >-
    Changing the RRF fusion, `RRF_K`, the distance threshold, or either
    arm's world predicate. Only the TEXT entering the arms changes.
  - >-
    Translating the chat agent's view of the user's message.
    `docs/design/06-messaging.md` keeps inbound auto-translate out of v1 —
    the agent still receives the original language. This ticket translates
    a query for the retrieval arms only.
  - >-
    The undeclared-language gap (a user on the default `/lang en` who types
    Czech gets no translation). That is a real consequence of condition (c)
    and needs its own decision, not a silent detector bolted on here.
acceptance:
  - >-
    When the scope's language is `en` (the default, and every scope today),
    the query text reaching both arms is BYTE-IDENTICAL to today and no
    translator call is made. Asserted, not assumed — this is the no-op
    property that makes the change safe, and a regression would put an LLM
    call in front of every search.
  - >-
    When the scope's language is non-English, the query is translated to
    English once and the translated string is what gets embedded and what
    `plainto_tsquery` receives. Both arms see the same text.
  - >-
    D58 (a) GREEDY - the translation call is issued at temperature 0.
    Asserted on the request the provider receives, not on config.
  - >-
    D58 (b) CACHED - the result is memoised keyed by (source text, source
    language). A repeated query issues NO second translator call and
    returns the identical string, so "same query -> same posts" holds by
    construction rather than by model determinism. This is the load-bearing
    determinism property (D19); a cache miss must never be able to change
    a result set.
  - >-
    D58 (c) DECLARED - the source language is read from the scope's
    `/lang` (`InboundRouter.lookupScopeLanguage`, which defaults to `en`
    for a missing row per D43), never inferred from the query text.
  - >-
    D58 (d) LANGUAGE-ONLY - the translator prompt instructs
    language conversion only, and a test pins that a query is not expanded,
    disambiguated or given added terms. A translation that returns extra
    search terms is a determinism violation dressed as a quality
    improvement.
  - >-
    A translator failure or an open circuit breaker FALLS BACK to the
    original query text rather than failing the search. A user who asks a
    question must not get an error because a translation hop was
    unavailable; degraded retrieval beats no retrieval.
  - >-
    The translated query is sanitized before it reaches SQL, on the same
    path the raw query uses today. Model output entering a query string is
    new and must not bypass a control the raw text passes through.
  - >-
    `SemanticSearchTool`'s two arms keep their inline `status='READY'` +
    D59 world predicate BEFORE the LIMIT, and results still fold into the
    D21 `UNTRUSTED_CONTENT` wrapper. Carried across explicitly (engineering
    rules §10) because the text entering the arms is being rerouted.
  - >-
    R1 (redteam DOS high, 2026-08-03): the accepted translation is
    length-capped at the tool's CONFIGURED `input-max-length` — the same
    property the tool dispatcher enforces on the raw query, one knob, so
    the anchored string can never exceed what the raw path permits at
    ANY operator config (re-audit r2 tightened this from a fixed
    500-char constant that drifted under non-default config) — and an
    over-cap translation falls back to the original query text and is
    NOT cached. The cap is RE-VALIDATED on the cache-hit path too, so a
    value cached under a higher cap is never served once the cap drops
    (re-audit r4). Retention is bounded on BOTH sides regardless of
    config: a hard value ceiling (`MAX_CACHED_TRANSLATION_LENGTH` =
    2048, a fixed belt decoupled from the functional knob; re-audit r3)
    means an over-ceiling translation is served but never cached, and
    the cache KEY stores SHA-256 of the source text, not the text
    itself (re-audit r4), so retained key memory cannot scale with a
    raised `input-max-length`. Together: worst-case retained heap
    ~20 MB at any config, and a hostile endpoint's up-to-8-MiB response
    can never become tens of gigabytes of retained heap (the transport
    cap's memory-protection purpose survives retention). Asserted by
    test: an over-input-cap response yields the original query and a
    cache miss on the next call; a lower configured cap tightens the
    bound; an over-ceiling response is served but never cached.
  - >-
    R2 (redteam INFO-LEAK medium, 2026-08-03): the query-translation cache
    is SCOPE-PARTITIONED — keyed by (scope_kind, scope_id, source text,
    source language) — so no cross-scope cache state exists: a translation
    produced from one scope's query can never be served to another scope's
    search, and cache hit/miss latency cannot be a cross-scope oracle for
    another user's query text (the presentation-cache sharing precedent in
    security.md §Prompt-injection defenses excludes user-authored content
    — query text is exactly that class). D58 (b)'s determinism contract
    is unaffected: within a scope, the same query still yields the same
    translation by construction.
  - >-
    R3 (redteam INJECTION low + DOS medium, 2026-08-03): the threat model
    is updated, not silently diverged — `docs/spec/security.md`'s
    `semanticSearch` tool row, the §Failure handling "Chat-mode replies"
    pre-fetch paragraph (re-audit r3), and the §Rate limiting section
    disclose the
    query-anchoring leg: the generative `TRANSLATOR` call (greedy
    temperature 0), the per-scope cache, the input-length cap, and the
    raw-text fallback; the rate-limiting text states that the leg draws
    no per-user bucket token (the bucket counts turns, not generative
    calls inside them) and is bounded by the per-turn tool-call cap plus
    the local backend's own limits — the accepted v1 posture, stated
    rather than hidden.
  - >-
    R4 (redteam INJECTION high, r5 2026-08-03): the query text the caller
    passed is PRESENT in the prompt the provider receives, inside a
    CONSTRUCTED `<<<UNTRUSTED_CONTENT id="<per-call uuid>">>>` /
    `<<<END id="<same uuid>">>>` block — the same open/close-format idiom
    every other prompt site uses (`ChatPromptBuilder`,
    `SummaryProseGenerator`, `CategoryRollupGenerator`), not prose
    describing a wrapper that is never built. Asserted on the request the
    provider receives, not on the template: the r5 audit found the query
    reached the `en` short-circuit, the cache key and the fallback returns
    but never the prompt, so a query-less instruction's reply became the
    search text for both arms and was cached under the real query's hash.
    The user's query is substituted LAST, after the language and delimiter
    placeholders, so query text that happens to contain a placeholder
    token is never re-substituted.
  - >-
    R5 (redteam INFO-LEAK medium, r6 2026-08-03): the disclosure this diff
    adds is TRUE of the shipped code. Two corrections in
    `docs/spec/security.md`: (i) §Rate limiting must not claim the
    translator call runs "against the local translator model" — nothing
    pins `ModelTask.TRANSLATOR` local (unlike the embedding leg under
    D54) and the deployment routes it remote, so the claim is false as
    written; (ii) the leg carries VERBATIM user chat text — the D28
    pre-fetch passes the user's raw message as the query — and retains
    its translation for 24h in a store no minimization lever reaches, so
    both the §Rate limiting bullet and §Secrets handling's per-task
    privacy disclosure must say so rather than describing the
    pre-M1-746 exposure. The operator-facing runtime text in
    `prod/switch-llm.sh` is OUT OF SCOPE here (not in files_scope) and is
    carried by M1-758.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslatorTest.java
      — `en` scope issues no call and returns the
      input unchanged; a non-English scope translates; a second identical
      query hits the cache with no provider call; a thrown translator
      returns the original text; source
      language comes from the declared scope value and not from the query's
      own script; an over-input-cap translation falls back to the original
      query and is NOT cached (a lower configured cap tightens the bound);
      two different scopes translating the same
      text each issue their own call (no cross-scope cache sharing); the
      query text appears in the prompt the provider receives, between a
      constructed open/close delimiter pair carrying the SAME per-call id
      (r5 — the prior assertion checked only that the string
      "UNTRUSTED_CONTENT" appeared somewhere in the instruction prose,
      which passed while the query was absent entirely).
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java
      and AnthropicProviderTest.java — a
      `ModelTask.TRANSLATOR` call emits `"temperature":0` on the wire
      request body (asserted on the request the provider receives, not on
      config); other tasks keep the temperature-free body of today.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolHybridIT.java
      — gains a non-English-scope case asserting
      both arms receive the SAME translated string and that a repeat of the
      query returns an identical, identically-ordered result set.
  preserves:
    - >-
      Every existing SemanticSearchToolHybridIT assertion — both arms, RRF
      fusion and total order, `RRF_K = 60`, the distance threshold, the
      READY + D59 predicates inside each arm before its LIMIT.
    - >-
      The existing presentation-path `TranslationCache` and
      `TranslationPipeline` behaviour. This ticket adds a SEPARATE cache
      for queries; it does not reuse or alter the prose one, whose key is
      (sha256 of English text, target lang) — the opposite direction.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/design/05-llm-and-embeddings.md §5.4.6
decision_refs:
  - D58
  - D19
  - D29
  - D21
  - D59
reviews:
  - round: 1
    date: 2026-08-03
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 1647
      removed: 34
  - round: 2
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 1679
      removed: 35
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-03
    source: redteam-multi (opencode + codex)
    evidence: docs/plan/m1/redteam-multi/M1-746-2026-08-03/
    verdict: FINDINGS
    findings:
      - >-
        DOS high (codex): QueryTranslationCache retains translator output
        verbatim, count-bounded only (10k entries x up to the 8 MiB
        transport cap) — a hostile endpoint converts the transport cap
        into ~78 GiB of retained heap.
      - >-
        DOS medium (opencode): the translation leg issues generative
        TRANSLATOR calls (pre-fetch + model-elected) outside the per-user
        LLM bucket, which counts turns not calls.
      - >-
        INFO-LEAK medium (codex): the plaintext-keyed (query, lang) cache
        is shared across scopes — hit/miss latency is a cross-scope oracle
        for another user's search text (the presentation-cache precedent
        explicitly excludes user-authored content).
      - >-
        INJECTION low (opencode): translator output trusted verbatim and
        unbounded by the tool input cap; security.md's semanticSearch row
        does not disclose the generative leg (spec drift).
  - date: 2026-08-03
    source: redteam-multi r5 (claude + opencode + codex)
    evidence: docs/plan/m1/redteam-multi/M1-746-2026-08-03-r5/
    verdict: FINDINGS
    findings:
      - >-
        INJECTION high (claude AND opencode, same root cause reported at two
        line ranges): the user's query text is NEVER inserted into the
        translator prompt. PROMPT_TEMPLATE carries a LITERAL `...` between the
        `<<<UNTRUSTED_CONTENT id>>>` / `<<<END id>>>` markers and has no query
        placeholder; translate() substitutes only {{SOURCE_LANGUAGE}} and
        {{id}} before calling generate(TRANSLATOR, "", prompt). The `query`
        parameter reaches only the `en` short-circuit, the cache key and the
        fallback returns. Consequences: (1) the model's reply to a query-less
        instruction becomes the search text for BOTH retrieval arms and the
        D28 per-turn pre-fetch; (2) a typical reply is non-blank and under the
        cap so no fallback leg fires, and the hallucination is CACHED under the
        real query's hash for 24h, making the wrong grounding deterministic;
        (3) under trust boundary 9 the endpoint chooses the ENTIRE search text
        rather than a rendering of the user's words, so the anchoring property
        the amended spec promises is absent, and the per-call random delimiter
        (D21 anti-forgery) guards an empty block. Unpinned end to end: the unit
        test asserts instruction substrings only, and the ITs' canned provider
        replies regardless of prompt content.
      - >-
        INJECTION low (codex): the scope-declared `/lang` value is interpolated
        into the translator instruction outside any UNTRUSTED_CONTENT wrapper.
        Falsified as exploitable — `LangCommandHandler` is the only writer of
        `scope_preferences.language` and gates every write on
        `LanguageRegistry.enabledLanguages()` = {en, cs}; controlling the value
        requires direct DB write access, which is outside the threat model.
  - date: 2026-08-03
    source: redteam-multi r6 (claude + opencode + codex)
    evidence: docs/plan/m1/redteam-multi/M1-746-2026-08-03-r6/
    verdict: FINDINGS
    findings:
      - >-
        R5 root cause, INFO-LEAK medium + DOS low (claude, two clusters; same
        issue): the §Rate limiting text THIS DIFF ADDS claims "a small
        (~100-token) prompt against the LOCAL translator model", but nothing
        constrains ModelTask.TRANSLATOR to a local backend (contrast the
        embedding leg, pinned local by D54) and the deployment routes every
        task to a remote provider. Compounding it, ChatAgent's D28 pre-fetch
        (ChatAgent.java:689-690) passes the first 500 chars of the user's RAW
        message as the query, so for a non-English scope verbatim private user
        messages reach whatever backend TRANSLATOR resolves to — while
        §Secrets handling's per-task privacy disclosure still describes the
        pre-M1-746 exposure. Fixed by R5.
      - >-
        INFO-LEAK low (claude): the query-translation cache retains the
        translation of the user's chat message for 24h with no minimization
        lever (`/forget` does not reach it), while the accepted-residual bullet
        it sits beside rests explicitly on cached strings being "presentation
        prose generated by the bot, not user-authored content". Fixed by R5.
      - >-
        INJECTION low (opencode): the instruction prose still rendered the
        illustrative `<<<UNTRUSTED_CONTENT id="{{id}}">>> ... <<<END>>>` pair
        with the SAME substituted per-call id as the real constructed block,
        so the prompt carried two openers and two closers with identical ids
        and a naive "extract between the id=X markers" reading yields the
        literal ellipsis. NOT a security gap (the per-call marker stays
        unguessable, so an attacker still cannot forge a closer) but a real
        clarity defect of the same shape r5 found. Fixed under R4, whose text
        already says prose describing a wrapper is not a wrapper.
      - >-
        DOS low (opencode) — FALSIFIED, no action: the cache's shared Caffeine
        capacity lets one scope's churn evict another scope's entries. The R2
        commitment is that no translation is SERVED across scopes, which the
        key partition delivers; eviction pressure reveals at most another
        scope's query VOLUME, never content, and every bounded shared cache
        evicts. Recorded so a later round does not re-litigate it.
  - date: 2026-08-03
    source: redteam-multi r7 (claude + opencode + codex)
    evidence: docs/plan/m1/redteam-multi/M1-746-2026-08-03-r7/
    verdict: CLEAN (claude, codex); one low (opencode), falsified below
    findings:
      - >-
        INJECTION low (opencode) — FALSIFIED, no action: a coaxed translator
        output is cached and served to other members of a group scope,
        steering their retrieval for 24h. Defeated by the key: the cache is
        keyed on SHA-256 of the FULL query text, so a second member hits the
        entry only by sending the BYTE-IDENTICAL message — i.e. by typing the
        attacker's payload themselves — and on a miss that same text would
        coax the same output anyway. The cache makes an already-reachable
        outcome deterministic; it does not extend reach. The auditor concedes
        impact is confined to retrieval steering inside the member's own D59
        world with no data disclosure. The "no LlmOutputSanitizer" half is
        also falsified: that sanitizer guards the user-facing render path, and
        this output reaches only the embed call and a bind parameter — never a
        user, as the auditor's own PROMISE quote states — while retrieved
        posts still fold into the D21 wrapper.
      - >-
        Same finding's secondary claim — that passing an empty system prompt
        deviates from the other prompt sites — FALSIFIED: the pre-existing
        `LlmTranslationProvider` (the shipped presentation-translation leg)
        also calls `generate(ModelTask.TRANSLATOR, "", prompt)`. Both
        TRANSLATOR sites share this shape; the SUMMARIZER sites use a system
        prompt. Matching the sibling translator is the house style for this
        task, so changing it here would diverge from the established pattern
        and would have to change the shipped leg too — out of scope.
      - >-
        OUT-OF-MODEL (opencode, no action here): under trust boundary 9 a
        hostile translator ENDPOINT ignores temperature 0 and the language-only
        prompt and chooses the entire search text of every non-English scope,
        persisted 24h by the cache. The PRIVACY half is now disclosed
        (§Secrets handling, R5); the INTEGRITY half — that D58's anchoring
        promise is only as strong as the operator's endpoint choice — is
        stated nowhere. Extending the model with a translator-locality pin
        (the D54 embedding precedent) or output verification is a design
        decision, deliberately not taken in this ticket.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings:
    - >-
      Self-check found acceptance a3 (temperature 0 asserted on the wire
      request) unbuildable inside the original 6-file scope — no temperature
      mechanism exists in either provider. User-directed scope widening #1:
      added the two provider impls + their wire tests to files_scope;
      temperature 0 emitted hard-coded for ModelTask.TRANSLATOR on the
      shared task key, asserted in the llm-adapter wire tests.
    - >-
      User-directed scope widening #2: the SemanticSearchTool constructor
      gains the QueryAnchorTranslator, so the two direct-construction
      test files (SemanticSearchToolIT, RetrievalWorldPredicateIT) are in
      scope for their mechanical ctor-ripple updates (budget 10 -> 12).
escalation_reason:
---

# M1-746: Query leg — translate a non-English query into the corpus anchor language

## Context

M1-745 makes the corpus English. That fixes the document side and does nothing
for the query side: a Czech user still types Czech, and a Czech query embedded
against an English corpus is the incumbent embedder's weak case (0.430 recall@8
against 0.630 on English).

The alternatives were a multilingual embedder — measured as buying +0.12 on
non-English while costing 0.02–0.07 on English, plus a 768→1024 migration and a
re-embed of three corpora — or translating the query. D58 previously forbade
the latter outright, because putting a model in the retrieval loop makes the
retrieved SET a function of non-deterministic output and collides with D19.

The amendment (`21ad3517`) takes that decision explicitly and narrowly: query
translation is permitted, under four conditions, and rewriting/expansion/HyDE/
re-ranking stay deferred. **This ticket is those four conditions and nothing
else.**

## Approach

The determinism argument rests on **(b), the cache** — not on the model. A model
at temperature 0 is *usually* reproducible; a cache keyed by (source text,
source language) is reproducible *by construction*, and that is the difference
between satisfying D19 and hoping to. Greedy decoding (a) is what makes the
first population of a cache entry stable; the cache is what makes every
subsequent query stable.

Condition **(c)** is why no detector appears anywhere: the declared `/lang` is
already read on the inbound path and already defaults to `en`.

Condition **(d)** is the one with no mechanical enforcement — a translator can
always add a word — so it is carried by prompt and pinned by test.

The `en` scope path must be a strict no-op. Every scope today is `en`, so a
regression there does not degrade a feature, it adds an LLM call and a failure
mode to every search in the deployment.

## Out-of-scope

Rewriting, expansion, HyDE, re-ranking. Language detection. Any change to
fusion, thresholds or world predicates. Inbound message translation for the
chat agent. The undeclared-language gap below.

## Notes

- **Known gap, deliberately not closed here.** Condition (c) keys on the
  DECLARED language, so a user who never runs `/lang cs` and types Czech gets no
  translation — precisely the weak case. Closing it means either detection
  (which condition (c) forbids) or prompting the user to declare a language.
  That is a product decision, not an implementation detail, and it needs its own
  ticket before non-English users arrive.
- **Two caches, opposite directions, do not merge them.** The existing
  `TranslationCache` keys (sha256 of English prose, target lang) for
  presentation output. This one keys (source text, source language) for input.
  Sharing a store would collide keys across two different meanings of "the
  language field".
- **Fallback direction matters.** On translator failure the query degrades to
  the original text — worse retrieval, still a result. The opposite choice
  (fail the search) converts a translation outage into a total chat outage.
- Whether this leg shares `ModelTask.TRANSLATOR` with the presentation and
  ingest legs, or needs its own task key, is an open design call. It shares
  today because the same model measured best on every leg; the trigger to split
  is the first measurement showing otherwise. Adding a `ModelTask` shifts
  `SwitchLlmWiringTest`'s positional stdin by one slot.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-746-query-translation-bounded-exception.md`
  is clean.

## Round 1 rework

1. Harden
   `OpenAiCompatibleProviderTest.translatorCallCarriesTemperatureZeroOnTheWireRequest`
   (`infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java:238`)
   so it fails when the field is absent: parse the captured body once and
   assert it `has("temperature")` AND that `.get("temperature").asInt() == 0` —
   mirroring `AnthropicProviderTest.java:115` and the `has()` idiom already used
   at `OpenAiCompatibleProviderTest.java:254`. Today the test passes on the
   pre-M1-746 temperature-free body, so the mutation deleting the
   `if (task == ModelTask.TRANSLATOR) { root.put("temperature", 0); }` block
   (`infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:239-240`)
   survives the suite.
