---
id: M1-746
title: "Query leg: translate a non-English search query into the corpus anchor language under D58's four conditions"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by:
  - M1-749
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/QueryTranslationCache.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslatorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolHybridIT.java
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
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslatorTest.java
      — `en` scope issues no call and returns the
      input unchanged; a non-English scope translates; a second identical
      query hits the cache with no provider call; temperature 0 is on the
      request; a thrown translator returns the original text; source
      language comes from the declared scope value and not from the query's
      own script.
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
