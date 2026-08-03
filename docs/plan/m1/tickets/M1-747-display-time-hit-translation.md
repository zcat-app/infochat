---
id: M1-747
title: "Display-time translation of a retrieved post's title and snippet into the reader's language"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by:
  - M1-749
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/LlmTranslationProvider.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-llm-adapter/src/main/resources/prompts/translator.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Translating the post BODY beyond the headline's existing body-fallback
    excerpt. D29 permits title and snippet only; the DisplayHeadline
    derivation (title, else the bounded body excerpt) IS the surface — a
    body is unbounded and is the thing the user is explicitly shown in its
    original form.
  - >-
    Writing any translated text back to the `post` row (or anywhere else).
    Display-time output is ephemeral. The `title_en`/`body_en` fields are
    M1-749's RETRIEVAL artifacts — display translation reads the ORIGINAL
    `title`/`body`, never the `*_en` fields, and persists nothing.
  - >-
    Translating a post that is already in the reader's language, an
    `en`-scope hit, or a post with unknown (`null`) source language. The
    no-op path must stay a no-op.
  - >-
    The ingest leg (M1-749, done), the source-language write path
    (M1-750), and the query leg (M1-746).
  - >-
    Changing which posts are retrieved, or their order. This ticket renders
    a result set; it never selects one.
  - >-
    PROMPT inputs. `SummaryProseGenerator.buildPrompt` keeps appending the
    full untranslated title, and `CategoryRollupGenerator` keeps feeding
    `DisplayHeadline.of` output into its prompt untranslated.
    `DisplayHeadline.of` itself stays a pure derivation with no translator
    call inside it — translation happens at the display call site only,
    because one of the helper's callers is a prompt builder.
  - >-
    The OTHER display surfaces, filed as follow-ups: `/saved`
    (M1-755 — its `saved_post` snapshot columns carry no source language,
    a design question of its own) and the digest-broadcast headline block
    plus the two degraded renderers (M1-756 — `DigestRenderer
    .appendHeadlines`, `DegradedDigestRenderer`,
    `SummaryProseGenerator.degradedProseFor`; the degraded paths compose
    bundle-sourced prose, which D43's bundle-not-translator invariant
    keeps out of the translator, so they need a design call this ticket
    does not make).
  - >-
    The D21 `UNTRUSTED_CONTENT` wrapper paths. Chat-mode retrieval hits
    reach users only through model prose that already runs the full
    sanitize -> translate pipeline; no display-hit surface exists there,
    and nothing in this ticket touches prompt assembly.
acceptance:
  - >-
    When the scope language is `en` (the default, and every scope today),
    rendering is BYTE-IDENTICAL to today and no translator call is made —
    asserted with a provider spy. A regression here adds an LLM call to
    every result set in the deployment.
  - >-
    `TranslationPipeline` gains a display-hit entry point
    (`runForDisplayHit(displayHeadline, sourceLanguage, scopeLanguage)` or
    similarly named; `sourceLanguage` is `@Nullable`). No-op legs, each
    returning the input with no provider call: `en` scope, source language
    equal to scope language (case-insensitive), `null` source language,
    empty headline (the renderer's existing empty-headline omission
    short-circuits before the pipeline). The translating leg runs
    translator call -> pre-bound -> flatten to one line -> sanitizer-2 ->
    cache write -> truncate -> marker. The CACHE-HIT path applies no
    transform: truncate -> marker only. `run()`'s prose behaviour is
    byte-unchanged.
  - >-
    CONTROLS CARRIED ACROSS (engineering rules §10) — the rendered
    headline is rerouted through a model, so the controls that govern it
    travel, enumerated: (a) the translator's output is flattened to one
    line BEFORE sanitizer-2 — the sanitizer's token separators are
    ASCII-only and its canonical form leaves U+0085/U+2028/U+2029 intact,
    so an unflattened output could smuggle a line-start privileged command
    past the closed list exactly as the 2026-07-30 DisplayHeadline
    finding; (b) sanitizer-2 is the provider `LlmOutputSanitizer` bean, so
    every match keeps its `LLM_OUTPUT_SANITIZED` audit row, and the
    sanitize unit is ONE post's headline per call (M1-697) — never a
    concatenation; (c) the translated headline is re-bounded at
    `DisplayHeadline.MAX_LENGTH` via DisplayHeadline's existing
    marker-safe/surrogate-safe truncate exposed as a public pure static
    (existing callers byte-unchanged) — sanitize-before-truncate order
    preserved so the audit sees the full output before the cut; (d) the
    marker is appended AFTER truncation so the cut can never produce a
    half-marker; (e) NO REWRITE RUNS AFTER SANITIZATION ON ANY PATH,
    including the cache-hit path. Control (a) is only a control if
    nothing re-orders it downstream: a flatten applied to an
    already-sanitized value re-creates precisely the hazard (a) exists to
    prevent, because U+2028 survives NFKC + `stripBidiAndZeroWidth`
    (verified: the strip set is U+061C/200E/200F/202A-202E/2066-2069/
    200B/200C/200D/FEFF — U+2028 is absent) while Java's `\s`, which the
    multi-word matcher compiles to, is ASCII-only. So
    `/quarantine<U+2028>approve` is ONE unmatched token before a flatten
    and a dispatchable line-start command after it, with no closed-list
    match and no `LLM_OUTPUT_SANITIZED` row in between. The disjoint
    keyspace (see the cache item) is what makes the no-transform read
    path safe. Each control is asserted by a test naming it, (e) by a
    test proving a cache hit delivers the stored bytes unmodified.
    [(e) added — redteam 2026-08-03, medium/INJECTION]
  - >-
    A translator failure or blank output falls back to the ORIGINAL
    headline plus the existing `REPLY_TRANSLATION_UNAVAILABLE` note (the
    same key the prose path uses), with NO marker — not an error and not
    an empty hit. Degraded comprehension beats a lost result.
  - >-
    The existing `TranslationCache` bean is reused (not duplicated), but
    display-hit entries occupy a keyspace DISJOINT from the prose leg's
    and PARTITIONED per `(scope_kind, scope_id)`. Both properties are
    load-bearing security controls, not tuning
    [redteam 2026-08-03, medium/INJECTION + low/INFO-LEAK]:
    (a) DISJOINT — the prose leg stores `sanitize(translated)` UNFLATTENED,
    so a shared keyspace lets the display leg read back a value carrying
    U+2028; disjointness means every value the display leg reads was
    written by the display leg, hence already flattened AND sanitized, so
    the read path applies NO transform of any kind before delivery (see
    the §10 item — the previous read-path `flattenToOneLine` was itself
    the defect). (b) PARTITIONED — `security.md` §"What's intentionally
    NOT in v1" accepts the cross-scope cache timing side-channel on the
    stated basis that cached strings are "presentation prose generated by
    the bot ... not user-authored content"; feed-authored headlines are
    not, so sharing them across scopes would widen an explicitly-bounded
    accepted risk without the amendment its rationale rests on.
    `ClusterBlockRenderer` therefore receives `(scopeKind, scopeId)` and
    passes them to the pipeline; `SummaryCommandHandler.renderFlatBody`
    and `RetryCommandHandler`'s flat arm thread them from the values both
    already resolve for `EligiblePostQuery.fetch`. The cached value stays
    the flattened, sanitized translation; truncation and the marker are
    applied OUTSIDE the cache. A hit skips both the translator call and
    sanitizer-2 and still gets truncate + marker.
  - >-
    `sourceLanguage` is VALIDATED at the pipeline boundary before any
    `Locale.of` call — a conservative ISO-639-shaped check (2-3 ASCII
    letters); anything else takes the existing unknown/never-translate
    no-op leg. Verified empirically that this is a real sink, not a
    theoretical one: `Locale.of("{{id}}").getDisplayLanguage(ENGLISH)`
    returns `{{id}}` verbatim, and `{{SOURCE_LANGUAGE}}` is substituted
    into the prompt's INSTRUCTION region (outside the
    `UNTRUSTED_CONTENT` wrapper). Additionally the substitution chain
    keeps the attacker-controlled slots LAST so a marker inside a
    substituted value can never be expanded by a later `replace` — the
    same ordering rule the surviving `{{content}}` comment states, which
    the first implementation preserved for content and re-opened for the
    new slot. Unreachable today (nothing writes `source.language` until
    M1-750) — closed at introduction rather than deferred to the ticket
    that opens the write path.
    [redteam 2026-08-03, low/INJECTION]
  - >-
    The translator's reply is PRE-BOUNDED before it reaches
    `flattenToOneLine` + `sanitize`, at `DisplayHeadline.BODY_SCAN_LIMIT`
    (the existing constant — same value, same rationale, no new key). A
    reply is bounded only by the provider's 1-8 MiB body cap, and
    `security.md` §Trust boundaries item 9 puts a hostile endpoint's
    in-cap reply in scope: without the pre-bound, megabytes reach NFKC +
    24 closed-list matchers + 10 tokenizer scans before the 200-char
    display bound is applied. This is the same pre-bound this same render
    surface already adopted for its other unbounded operand after the
    2026-07-30 low/DOS finding; it is carried onto this leg rather than
    re-argued. [redteam 2026-08-03, low/DOS]
  - >-
    A `cp.degraded()` cluster makes NO translator call for its headline —
    asserted with a spy. The degraded branch exists because the LLM path
    already failed, and `security.md` §Failure handling pins the degraded
    form as headlines + URLs + UIDs with no prose; the first
    implementation called the display-hit leg unconditionally, ahead of
    the degraded check, turning the cost-shedding path into one extra
    provider round-trip per cluster. The degraded headline renders
    untranslated and unmarked. [redteam 2026-08-03, low/DOS]
  - >-
    The prompt's slot substitution is SINGLE-PASS: every `{{SLOT}}` in
    `translator.md` is resolved in ONE scan of the template, so no
    substituted VALUE is ever re-scanned and no slot can expand a marker
    another slot's value contains. This replaces the ordered `.replace`
    chain, whose "content last" rule protects exactly one slot and left
    `{{SOURCE_LANGUAGE}}` — substituted FIRST, filled from the unvalidated
    `source.language` TEXT column — able to expand `{{id}}` into the
    per-call random delimiter and forge a close marker. The
    `ISO_639_SHAPE` gate STAYS (defense in depth); this item removes the
    dependence on it, because `LlmTranslationProvider.translate` accepts
    any `Locale` across the `app.zcat.infochat.messaging` SPI and nothing
    mechanical pins the caller-side check as the sole control. Replacement
    values are quoted (`Matcher.quoteReplacement`) so a `$` or `\` in a
    headline stays literal exactly as `String.replace` treated it, and an
    unrecognized `{{SLOT}}` is left VERBATIM — both preserve the previous
    chain's bytes. The prose path's rendered prompt stays byte-identical,
    asserted. [redteam 2026-08-03 r2, low/INJECTION]
  - >-
    `DisplayHeadline` exposes the translated-headline preparation as ONE
    public composite (bound at `BODY_SCAN_LIMIT` -> flatten -> sanitize,
    in that fixed order) and `flattenToOneLine` + `boundForScan` return to
    PRIVATE. Order-as-convention is not the standard this codebase holds
    routing invariants to (`security.md` §"The chokepoint routing is
    build-guarded"): `flattenToOneLine` is the very rewrite whose
    application AFTER `sanitize` has been a medium/INJECTION finding twice
    — 2026-07-30, and round 1 of this ticket — and making it callable from
    any provider class leaves a javadoc sentence as the only barrier
    between a stored sanitized value and a spliced `/quarantine approve`
    at a group-visible line start. A caller that cannot reach the
    primitives cannot order them wrongly. `truncate` STAYS public (the
    cache-hit path applies it outside the composite, and it can only
    remove a suffix and append an ellipsis — it cannot manufacture a
    dispatchable token). Verified: the two primitives have exactly ONE
    external call site between them, so the composite is a strict
    narrowing. [redteam 2026-08-03 r2, low/INJECTION]
  - >-
    A new bundle key marks machine-translated headlines, appended
    space-separated to every delivered translation, plain text (D30). The
    key is a `BundleKeys` constant (the D43 parity test walks BundleKeys'
    reflective field set, so a properties-only key would evade the twin
    check) with both `en` and `cs` values present.
  - >-
    `EligiblePostQuery.selectPosts` projects `s.language`; the `Post`
    record's canonical constructor gains a trailing `@Nullable String
    sourceLanguage`, and BOTH existing shapes (the 14-component previous
    canonical and the 10-component M1-724 compat) remain as compat
    overloads defaulting it `null`, so every §Census site compiles
    unchanged. `null` means "unknown — never translate".
  - >-
    /retry replay parity: `SELECT_POSTS_BY_UIDS` projects `s.language` and
    `mapPost` carries it, so a `cs`-scope flat replay translates the same
    headlines the original render did (subject to the same cache/provider
    temporal variance the `summary:` field already has).
  - >-
    `translator.md`'s "from English" becomes "from {{SOURCE_LANGUAGE}}";
    `LlmTranslationProvider` substitutes the from-locale's English display
    name, before `{{content}}` (the substitution-order invariant — content
    last — per the M1-749 round-1 finding). For the prose path
    (from=ENGLISH) the rendered prompt is BYTE-IDENTICAL to today,
    asserted.
  - >-
    The RENDERER wiring is pinned, not just the pipeline: a `cs`-scope
    `ClusterBlockRendererTest` case asserts the flat block renders the
    translated, marked headline for a differing-source-language post
    (the en-scope byte pins stay untouched). `ClusterBlockRenderer` is
    package-private in `provider.command`, unreachable from the scoped
    translation-package test, so this case is the only thing that makes
    deleting the `runForDisplayHit` call a test failure (§10 — tests are
    controls too). [refine 2026-08-03, budget-breach]
  - >-
    The display path introduces no write: neither `TranslationPipeline`
    nor `ClusterBlockRenderer` holds a `DataSource`; `post.title` /
    `post.body` are read-only inputs and the output is ephemeral (D29).
    Reviewer-checkable structurally.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
      — `en` scope renders byte-identical with zero provider calls (spy);
      `cs` scope + `en` source translates the headline and appends the
      marker; `cs` scope + `cs` source, `null` source language, and empty
      headline each make no call; a thrown/blank translator falls back to
      the original headline + `REPLY_TRANSLATION_UNAVAILABLE` note with no
      marker; a multiline translator output is flattened BEFORE
      sanitizer-2 (the U+2028 smuggle case is the fixture); an overlong
      translator output is re-truncated with the marker-safe cut and the
      marker lands after the cut; a cache hit skips translator and
      sanitizer-2 but still gets truncate + marker; the cached value
      carries no marker; the prose path's rendered prompt bytes are
      unchanged by the {{SOURCE_LANGUAGE}} substitution.
      Redteam-remediation cases [2026-08-03]: a value written by the PROSE
      leg under the same source text is NOT readable by the display leg
      (disjoint keyspace); a display-hit entry written for one scope is
      NOT readable for another (scope partition); a cache hit delivers the
      stored bytes UNMODIFIED — no flatten, no re-sanitize — proven by
      seeding a U+2028-bearing value directly into the display keyspace
      and asserting the delivered bytes still carry it rather than a
      spliced `/quarantine approve`; a non-ISO-shaped `sourceLanguage`
      (`{{id}}`, a 40-char string, `zz9`) takes the never-translate leg
      with no provider call; an over-cap translator reply is pre-bounded
      before sanitize; a `cp.degraded()` cluster makes no translator call.
      Round-2 cases [2026-08-03]: a marker token inside a SUBSTITUTED
      value is not expanded — a `from` locale whose display name contains
      `{{id}}` renders that text verbatim while the real delimiter id
      still resolves elsewhere, proving the single-pass property directly
      rather than via slot ordering; a `$` in the translated text survives
      substitution literally; an unrecognized `{{SLOT}}` is left verbatim.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
      — ONE added case (existing cases untouched): a `cs`-scope block
      whose first post carries a differing `sourceLanguage` renders the
      translated headline with the marker, pinning the renderer's
      `runForDisplayHit` call site. [refine 2026-08-03, budget-breach]
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
      — ONE fixture line (existing cases untouched): its ResultSet proxy
      stub answers the newly projected `source_language` column from the
      fixture Post's own accessor (`null` for compat-constructed fixtures
      = no-translation, correct for those en-scope tests). [refine
      2026-08-03, budget-breach #2 — the census enumerated constructor
      invocations and missed the column-name stub coupling class]
  preserves:
    - >-
      Existing `TranslationPipeline` prose behaviour (`run()`) and its
      sanitizer ordering — sanitize, translate, sanitize — plus
      `TranslationPipelineIT`'s exact call-count pins (en-scope fixtures
      see zero new calls; the new leg is not reachable from `run()`).
    - >-
      `TranslationCache` semantics: 24h TTL, 10k entries, hit skips
      translator and second sanitizer.
    - >-
      `ClusterBlockRendererTest`'s existing byte pins (all en-scope — the
      compat constructor defaults `sourceLanguage` null, which is a no-op
      leg).
    - >-
      Every `BundleLoaderTest` keyset-parity assertion.
    - >-
      `DisplayHeadline`'s existing behaviour and tests — the only changes
      are truncate's visibility and the added translated-headline
      composite; `flattenToOneLine` and `boundForScan` stay private, so
      the class's reachable surface grows by exactly two entry points.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/security.md §LLM output sanitizer
  - docs/design/05-llm-and-embeddings.md §5.6
decision_refs:
  - D29
  - D43
  - D30
  - D19
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 2214
      removed: 68
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-03
    category: INJECTION
    severity: medium
    promise: |
      security.md §LLM output sanitizer: every LLM-generated text delivered
      to a user passes the deterministic outbound closed-list pass and every
      match is audit-logged; §Canonical-form matching requires the pass to
      match the same representation the command parser consumes.
    gap: |
      TranslationPipeline.runForDisplayHit's CACHE-READ path applies
      DisplayHeadline.flattenToOneLine — a whitespace-normalising REWRITE —
      to a value that was sanitized BEFORE storage, then returns it into the
      group-visible headline slot without re-sanitizing. That is the
      post-sanitize-rewrite ordering DisplayHeadline's javadoc forbids
      (LlmOutputSanitizer's token separators are ASCII-only and its canonical
      form leaves U+2028 intact, so /quarantine<U+2028>approve is ONE token
      pre-flatten and a dispatchable command post-flatten). The FRESH path
      orders it correctly; only the cache-read path inverts it. Reachable
      because the prose leg (run()) shares the cache keyspace and stores
      sanitize(translated) UNFLATTENED.
    repro: |
      Non-banned post-probation user sets /lang cs, coaxes a chat reply
      byte-equal to a chosen S (keying the cache on S), where the translator
      returns /quarantine<U+2028>approve — sanitizer-2 sees one token, no
      match, no audit row, value cached. Attacker then publishes a feed post
      whose title is exactly S. Any cs-scope flat /summary renders that
      cluster, hits the cache, flattens U+2028 to a space, and emits
      "/quarantine approve ..." at a group-visible line start with no
      redaction and no LLM_OUTPUT_SANITIZED row.
    suggested_fix_class: input-sanitization
  - date: 2026-08-03
    category: INJECTION
    severity: low
    promise: |
      security.md §Prompt-injection defenses: user-derived text reaches the
      model only inside the per-call-random UNTRUSTED_CONTENT wrapper, never
      as instruction text; attackers cannot forge a closing marker.
    gap: |
      The new {{SOURCE_LANGUAGE}} slot sits in the INSTRUCTION sentence
      outside the wrapper and is filled from the unvalidated source.language
      TEXT column (Locale.of accepts anything; getDisplayLanguage echoes an
      unknown code verbatim). It is also chained FIRST, so {{id}} /
      {{content}} / {{TARGET_LANGUAGE}} tokens inside the substituted value
      are expanded by the three later replaces — re-opening for the new slot
      exactly the ordering hazard the surviving comment two lines above
      documents as forbidden for {{content}}.
    repro: |
      Not reachable today (V74 leaves every row 'en'; the write path is
      M1-750). Arms the moment M1-750 lands /add-source --lang: a crafted
      --lang value relocates the untrusted content outside the delimiter
      block and expands the per-call random id into an attacker-positioned
      close marker.
    suggested_fix_class: input-sanitization
  - date: 2026-08-03
    category: INFO-LEAK
    severity: low
    promise: |
      security.md §What's intentionally NOT in v1 accepts the cross-scope
      translation-cache timing side-channel explicitly on the basis that the
      cached strings are "presentation prose generated by the bot ... not
      user-authored content".
    gap: |
      The display-hit leg writes per-post, feed-authored HEADLINES into that
      same scope-shared cache (one Caffeine instance, no scope dimension in
      TranslationKey). The accepted residual's stated basis no longer holds
      and the spec was not amended, so the inference sharpens from "another
      scope received a similar digest" to "another scope rendered THIS post
      within 24h".
    repro: |
      Attacker and victim scope both /lang cs; sources are global (D7). A
      cache HIT on the attacker's FIRST render of a given post proves another
      scope rendered that exact headline inside the TTL — per-scope reading
      activity D59 world-scoping otherwise keeps separate.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-03
    category: DOS
    severity: low
    promise: |
      security.md §Trust boundaries item 9 (a hostile endpoint's in-cap reply
      must not convert into unbounded CPU); §Rate limiting (LLM-triggering
      operations share one capped bucket); §Failure handling (a degraded
      /summary falls back to headlines + URLs, "No prose").
    gap: |
      The whole translator reply (bounded only by the 1-8 MiB provider body
      cap) is handed to llmOutputSanitizer.sanitize before the 200-char
      display bound is applied — the DisplayHeadline.BODY_SCAN_LIMIT
      pre-bound adopted for the same surface after the 2026-07-30 low/DOS
      finding is not carried onto this leg. Separately ClusterBlockRenderer
      invokes the leg for EVERY cluster headline including when
      cp.degraded() is true, the branch that previously issued zero
      translator calls precisely because the LLM path had failed.
    repro: |
      (a) Attacker coaxes the summarizer into refusal so clusters degrade;
      every flat /summary or /retry then performs K translator calls where
      the pre-diff degraded render performed none, for one token of the
      per-user LLM bucket. (b) A hostile translator endpoint answers each
      with an in-cap multi-megabyte body, each fully closed-list-scanned
      before the display bound; on /retry --digest that runs inline on the
      transport thread (D61).
    suggested_fix_class: rate-limit
  - date: 2026-08-03
    round: 2
    category: INJECTION
    severity: low
    promise: |
      security.md §Prompt-injection defenses: every prompt including
      user-derived text is wrapped in a delimiter block whose marker carries
      a per-call random value, so an attacker cannot forge a closing tag.
      §Trust boundaries item 4 puts every feed-adjacent value on the
      untrusted side, and the rule the code itself states is that every slot
      ahead of {{content}} must hold trusted bytes only.
    gap: |
      LlmTranslationProvider still substitutes {{SOURCE_LANGUAGE}} FIRST —
      ahead of {{id}} and {{content}} — so any marker token inside that
      value would still be expanded by the three later .replace calls. The
      remediation closed the reachable path with a validator in a DIFFERENT
      class on the caller side of the app.zcat.infochat.messaging SPI
      (TranslationPipeline.ISO_639_SHAPE) rather than structurally, despite
      the ticket's own acceptance text asserting the chain keeps
      attacker-controlled slots LAST. LlmTranslationProvider.translate
      accepts any Locale and applies no check of its own; source.language is
      TEXT NOT NULL DEFAULT 'en' with no CHECK (V74:52); nothing mechanical
      pins the caller-side gate as the sole control. Reordering the two
      .replace calls makes it unconditional at zero cost.
    repro: |
      Not exploitable today — the one production caller gates the value and
      nothing writes source.language before M1-750. It arms on a single
      edit rather than an attacker action: any second caller of
      TranslationProvider.translate (M1-755/M1-756 are filed to reuse this
      pipeline; M1-750 opens /add-source --lang) that builds a Locale from
      the column without repeating ISO_639_SHAPE. From there a crafted
      --lang value relocates the untrusted body outside the delimiter block
      and expands the per-call random id into an attacker-positioned close
      marker.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-03
    round: 2
    category: INJECTION
    severity: low
    promise: |
      security.md §LLM output sanitizer / §Canonical-form matching: the
      closed-list pass matches the canonical form the deterministic command
      parser consumes, and every match is audit-logged. §"The chokepoint
      routing is build-guarded" states the standard this codebase holds such
      routing invariants to — a convention enforced by census is upgraded to
      a structural property.
    gap: |
      DisplayHeadline promotes boundForScan, flattenToOneLine and truncate
      from private to public statics. flattenToOneLine is precisely the
      whitespace-normalising rewrite whose application AFTER
      LlmOutputSanitizer.sanitize was a medium/INJECTION finding twice (the
      2026-07-30 instance, and round 1 of this ticket at the display-hit
      cache-read path). Before this diff the hazard was unreachable by
      construction — no class outside provider.render could call the
      primitive. After it, any provider class can splice
      /quarantine<U+2028>approve into a dispatchable /quarantine approve
      with one call, and the only thing preventing it is prose in the class
      javadoc. No ArchUnit rule, no test, no CI check asserts that no call
      site applies flattenToOneLine to an already-sanitized value — and the
      empirical record is that the first code written against the
      newly-public primitive got the order wrong. The new call site is
      itself correct, so this is resilience, not a live defect.
    repro: |
      No adversary action reaches this today; the exposure is that the
      control is now conventional. A future ticket adding display-hit
      translation to /saved (M1-755) or to the digest and degraded renderers
      (M1-756) writes flattenToOneLine(cachedOrSanitizedValue) to normalise
      a stored value before rendering. Nothing fails: the closed-list pass
      has already run, so no LLM_OUTPUT_SANITIZED row and no WARN, mvn
      verify stays green, and the reviewer sees a diff matching its ticket.
      A feed publisher then places /quarantine<U+2028>approve where that
      path reaches it and the group receives a copy-pasteable privileged
      command at a line start with no redaction and no audit row.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-03
    verdict: FINDINGS
    base: 57f47cdea30fe884c8d831b247f0b97834c0f7ef
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam/M1-747-2026-08-03.md
    findings_count: 4
    out_of_model_count: 2
    note: |
      First audit at the /m1-tick run redteam gate, ahead of review. One
      medium (cache-read path applies a post-sanitize rewrite, re-opening
      the U+2028 line-start command hazard a prior audit closed) and three
      low (unvalidated source.language substituted first into the prompt
      instruction region; feed-authored headlines widening the accepted
      cross-scope cache side-channel; no pre-bound on translator output plus
      translator calls newly added to the degraded branch). Halted per
      run.md step 4 — no review, no commit. Out-of-model: replay-byte parity
      for non-en scopes, and marker suppression on a self-identical
      translation.
  - date: 2026-08-03
    verdict: FINDINGS
    base: 137c203a11bc2467debeff5c2c91470ff9cbb698
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam/M1-747-2026-08-03-r2.md
    findings_count: 2
    out_of_model_count: 4
    note: |
      Round-2 re-audit of the remediated diff (a remediation invalidates the
      audit that prompted it). All four round-1 findings verified CLOSED
      against the current diff — the medium/INJECTION cache-read rewrite,
      the cross-scope cache partition and both halves of the low/DOS pair
      outright; the low/INJECTION source-language sink closed for its
      reachable path. Two NEW low findings, both against the remediation's
      own surface and neither exploitable against the shipped code: the
      {{SOURCE_LANGUAGE}} slot is still substituted first (the guarantee
      rests on a caller-side validator in another class rather than on the
      substitution order the acceptance text itself calls for), and
      DisplayHeadline's three newly-public statics make a
      previously-unreachable post-sanitize-rewrite hazard conventional
      rather than structural. Halted per run.md step 4 — no review, no
      commit. Out-of-model: source language absent from the cache key,
      translator.md still framing wrapped content as LLM-authored prose with
      no refusal marker, plus the two carried forward from round 1.
  - date: 2026-08-03
    verdict: CLEAN
    base: 137c203a11bc2467debeff5c2c91470ff9cbb698
    head: c17d6636
    verdict_file: docs/plan/m1/redteam-multi/M1-747-2026-08-03/cross-examination.md
    findings_count: 0
    out_of_model_count: 4
    note: |
      Round-3 re-audit of the round-2 remediation (single-pass prompt
      renderer; DisplayHeadline primitives returned to private behind the
      prepareTranslatedHeadline composite), run as a two-auditor
      redteam-multi (opencode + codex) per the must-shrink mandate with the
      re-audit framing injected into both rendered prompts. Both auditors
      returned CLEAN; opencode's verdict re-verified all six prior findings
      (round 1: 4, round 2: 2) CLOSED against the current diff, and the
      cross-examination found zero finding clusters. The four out-of-model
      notes carry forward unchanged from round 2 (source language absent
      from the cache key; translator.md prose framing with no refusal
      marker; marker suppression on a self-identical translation; non-en
      replay parity). No findings to remediate.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings:
    - >-
      Self-check performed as the surface-binding rework itself
      (2026-08-03): every ticket-vs-code claim verified live this session —
      ClusterBlockRenderer is the sole non-degraded /summary+/retry
      headline surface (renderSummarySections renders prose/headers only);
      RetryCommandHandler's SELECT_POSTS_BY_UIDS lacks s.language;
      translator.md hard-codes "from English"; DisplayHeadline.truncate is
      private; the Post census (29 invocations, no record patterns, no
      Post::new) enumerated by invocation; TranslationCache key shape and
      TranslationPipeline fallback confirmed by reading both classes.
  blockers: []
escalation_reason:
---

# M1-747: Display-time translation of a retrieved post's title and snippet

## Context

M1-749 makes a cross-language hit *findable*. It does not make it *readable*:
D29 retains the original body as what the user is shown, so a Czech reader
who matches an English (or, post-M1-750, Spanish) post gets that language's
headline back. The amendment (`21ad3517`) closed this explicitly —
"translating a retrieved post's title and snippet into the reader's language
at display time is permitted, because comprehension of a cross-language hit
is otherwise unaddressed."

**Surface binding (verified against the code, 2026-08-03).** D29's "title
and snippet" binds to the `DisplayHeadline` derivation — the post's title,
else its bounded body excerpt (which IS the snippet case; the body branch is
capped at `BODY_SCAN_LIMIT` before sanitize and `MAX_LENGTH` at display).
The one NON-degraded surface where `/summary` and `/retry` show a retrieved
post's source-authored text is `ClusterBlockRenderer`'s headline line (flat
blocks; one headline per cluster, the first post). `DigestRenderer
.renderSummarySections` — the bare/`--full` path — renders section headers
and LLM prose only, no headlines, so it needs no change. The other headline
surfaces (`/saved`, the digest broadcast's normal-mode headline block, the
two degraded renderers) are follow-ups M1-755/M1-756 — see out_of_scope.

This is deliberately the cheap half of the problem: one bounded string
(≤ `MAX_LENGTH` + ellipsis) per rendered cluster, so a result set costs at
most `cluster-cap` extra translator calls, cache-deduped — at most doubling
the per-cluster translation volume a `cs` scope already pays for the
`summary:` field, with no new config key.

## Approach

**The cache bean is reused but its keyspace is not shared** (redteam
2026-08-03). Sharing the prose leg's keyspace is what made a
post-sanitize rewrite look necessary on the read path — and that rewrite
was itself the medium finding, because it re-created the exact U+2028
line-start-command hazard the flatten-then-sanitize order exists to
prevent. Sharing across scopes separately widened an accepted risk whose
written rationale excludes user-authored content. Both are fixed by
PARTITIONING the keyspace (disjoint from prose, partitioned per scope)
rather than by transforming values on read; the bean, its TTL and its
size bound stay shared. The read path then applies no transform at all,
which is the only shape in which "a hit skips sanitizer-2" is safe.

Reuse `TranslationPipeline` and `TranslationCache` rather than growing a
parallel path — a new display-hit entry point on the same bean, sharing the
cache, the sanitizer bean, and the fallback note. The pipeline order on the
translating leg mirrors `DisplayHeadline`'s own (flatten → sanitize →
truncate) for the same reasons, applied to the translator's output.

The `en` scope path is a strict no-op, asserted with a spy. Every scope
today is `en`, so a regression there does not degrade a feature; it adds a
translator call and a failure mode to every rendered result in the
deployment.

The translation INPUT is the DisplayHeadline OUTPUT — already flattened,
sanitized, and truncated — so "the snippet is capped before translation" is
structural: a 3,000-character body can never drive the request size.

`Locale`-wise the translator call passes the post's real source locale; the
provider's `from.equals(to)` short-circuit stays a redundant second guard
behind the pipeline's own same-language no-op.

## Census

Post record construction sites (`grep -rn "new Post(\|new
EligiblePostQuery.Post(" --include=*.java infochat-provider/src`; record
patterns and `Post::new` refs verified absent, 2026-08-03): 29 invocations.

Disposition: ALL 29 keep compiling unchanged via the two compat overloads
(the previous 14-component canonical and the 10-component M1-724 compat,
both defaulting `sourceLanguage` to `null`). Exactly 2 sites move to the new
15-component canonical: `EligiblePostQuery.selectPosts` and
`RetryCommandHandler.mapPost` — the two real SELECT-backed constructors.

| File | Sites | Disposition |
|---|---|---|
| main: EligiblePostQuery.java | 1 | → 15-arg canonical (projects `s.language`) |
| main: RetryCommandHandler.java | 1 | → 15-arg canonical (projects `s.language`) |
| main: DigestPostCollector.java | 1 | compat (digest surfaces are M1-756) |
| test: ClusterBlockRendererTest.java | 4 | compat (en-scope pins preserved) |
| test: RetryCommandHandlerTest.java | 2 | compat |
| test: SummaryCommandHandlerTest.java | 1 | compat |
| test: CategoryRollupGeneratorTest.java | 1 | compat |
| test: DegradedDigestRendererTest.java | 1 | compat |
| test: DigestCategorizerTest.java | 2 | compat |
| test: DigestRendererSectionsTest.java | 1 | compat |
| test: DigestRendererTest.java | 1 | compat |
| test: DigestWorkerClockTest.java | 1 | compat |
| test: DigestWorkerTest.java | 3 | compat |
| test: DisplayHeadlineTest.java | 1 | compat |
| test: ClusterProminenceTest.java | 1 | compat |
| test: ClusterTraversalTest.java | 1 | compat |
| test: SummaryProseGeneratorTest.java | 4 | compat |
| test: SummaryProseInjectionTest.java | 1 | compat |
| test: SummaryProseRefusalDegradeTest.java | 1 | compat |

## Out-of-scope

See frontmatter. In one line each: no body translation, no persistence, no
no-op regression, no other legs (M1-746/749/750), no retrieval changes, no
prompt-input translation, other display surfaces → M1-755/M1-756, D21
paths untouched.

## Notes

- **Display translation reads the ORIGINAL `title`/`body`, never
  `title_en`/`body_en`.** The `*_en` fields are retrieval artifacts (one
  hop, source → en). A `cs` reader of a Spanish post gets es→cs directly,
  not es→en→cs.
- **Only `cs` is selectable today** (`LanguageRegistry` enables `{en, cs}`),
  so the translating leg fires for `cs` scopes only; and `source.language`
  is `'en'` for every row until M1-750 lands the write path. The
  `{{SOURCE_LANGUAGE}}` prompt slot is what makes the non-`en`-source case
  correct the day it becomes reachable, at zero byte-cost to the prose path
  today.
- **This is the only leg that shows model output directly to a user as if
  it were source content** — why `security_relevant: true` and why the §10
  enumeration is in acceptance rather than assumed.
- **The marker key is not decoration.** Without it a machine translation is
  indistinguishable from the publisher's own words, which matters when the
  headline is about a security advisory or a price. D30 keeps it plain
  text; applying it AFTER truncation keeps it whole.
- **Fallback direction is deliberate**: on failure the reader sees the
  original language plus the existing note, not an error. The opposite
  turns a translator outage into an empty result set.
- Bundle keys need the `cs` twin in the same diff or `BundleLoaderTest`
  fails on D43's bilateral keyset requirement.
- Pre-flight: `python3 scripts/lint-ticket.py
  docs/plan/m1/tickets/M1-747-display-time-hit-translation.md` re-run at
  start.
