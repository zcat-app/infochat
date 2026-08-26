---
id: M1-931
title: "Add deterministic getPrice chat tool for price_snapshot"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
flow: tick
reproduction: >-
  GetPriceToolIT#dispatchReturnsLatestSnapshotForFixtureAsset (to-be-written;
  converted at /tick start: written first, run RED — dispatch of "getPrice"
  returns the typed "Error: Unknown tool: getPrice" because the closed
  allowlist has no such name, while the seeded price_snapshot row the test
  asserts on exists and is served by /zcash today). Today's wrong behavior,
  probe-verified on this checkout (2026-08-26): (1)
  grep -rn 'price_snapshot' infochat-provider/src/main/java/…/provider/chat/
  returns NO match — no chat tool reads the table; the only reader is
  AssetSnapshotReader (infochat-provider/…/command/asset/AssetSnapshotReader.java:161,
  consumed by AssetHandler.java:195 for the /zcash //monero commands);
  (2) the closed tool allowlist holds exactly seven names
  (ChatToolRegistry.java:18-26), mirrored by the security.md table rows
  docs/spec/security.md:328-334 — no price-reading tool exists, so the chat
  agent cannot ground a price question and answers from stale parametric
  knowledge or declines (live observation, user, 2026-08-26: "What is the
  price of zcash/monero?" unanswerable in chat while /zcash and /monero
  answer; one of the three live failures motivating the RAG campaign).
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPriceTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    RAG for prices in ANY form — embedding, retrieval, or a semantic arm over
    price data. Binding user decision (2026-08-26): the price question class
    is not a retrieval problem; a deterministic tool is the fix.
  - >-
    Any change to AssetSnapshotReader's or AssetHandler's semantics (query,
    staleness window, caching, rendering, rate bucket) and to
    AssetReplyRenderer — the command surface is correct as shipped; this
    ticket only adds a SECOND CONSUMER of the existing reader.
  - >-
    Any ticker-symbol mapping table ("xmr"→"monero", "zec"→"zcash") — the
    brief's premise that asset_config already maps symbols is FALSE
    (verified: grep '"xmr"|"zec"|symbol' over *.java returns no asset-side
    hit; AssetRegistry.getAsset is an exact-key map lookup,
    AssetRegistry.java:125-127). Natural-language normalization is the
    MODEL's job (llm.md §Determinism boundary permits prose, never the
    row choice); the tool resolves exact configured names and its
    unknown-asset error lists them for self-correction. A symbol table
    would be the "second, drifting mapping" the brief forbids.
  - >-
    A sub-verb/source input argument on the tool (exchange selection) —
    binding brief shape: input is asset + optional vs_currency only; the
    tool resolves the per-asset default pair exactly as bare /zcash does.
  - >-
    POST_CORPUS_TOOLS membership or any provenance-notice wiring for
    getPrice (ChatAgent.java:1276-1277) — price data is not feed-post
    grounding (commands.md:722 "Data is not posts"; :1830-1843 the notice
    counts posts only); a price-grounded reply correctly carries the
    not-feed-grounded notice.
  - >-
    The eval harness and golden set (M1-928/929/930) — parallel track; the
    price-class flip from none_expected to hit is an eval-side decision
    AFTER the baseline run exists, never this diff.
  - >-
    Any DB grant change — the Provider role already holds SELECT-only on
    price_snapshot (docs/spec/security.md:2117-2119); verified, no
    migration.
  - >-
    Sanitizer, UNTRUSTED_CONTENT fold, per-turn cache, 25-call cap, input
    caps, or the prompt-budget ladder values — all ride unchanged; the only
    prompt-budget action is the design-05 ledger entry (acceptance item 9).
  - >-
    Any ChatAgent production change — the tool loop, fold-back wrapper, and
    POST_TOOL_RESULT_INSTRUCTION are generic over tool names
    (ChatAgent.java:1019-1049); the catalog drives TOOL_INSTRUCTIONS
    (ChatAgent.java:114-127). No ChatAgent.java edit is expected; if one
    seems needed, escalate before editing.
acceptance:
  - "REPRODUCTION closed: GetPriceToolIT.dispatchReturnsLatestSnapshotForFixtureAsset passes — seeds asset_config (zcash/coingecko, is_default, attribution_url) plus two price_snapshot rows (distinct captured_at) via JDBC, dispatches {\"asset\":\"zcash\"} through a REAL ChatToolDispatcher, and asserts the Success JSON carries the NEWER row's price (toPlainString), \"source\":\"coingecko\", \"vs_currency\":\"usd\", \"captured_at\" equal to the newer row's seeded instant, \"stale\":false, and the asset_config attribution URL verbatim. Non-vacuity: a mutation returning the older row, or dropping/mis-naming any asserted field, fails (analysis P1/P9)."
  - "FRESHNESS CONTRACT (stale shape, analysis P4; commands.md:755-767): GetPriceToolIT.staleSnapshotIsServedWithAgeDisclosed passes — the app Clock is pinned via QuarkusMock.installMockForType(Clock.fixed(...)) (engineering-rules §9) so the seeded row's age exceeds infochat.assets.freshness-window (application.properties:159); the result STILL carries the price (stale data is served, never suppressed) AND \"stale\":true AND \"age_seconds\" equal to pinnedNow − captured_at. A mutation hiding the stale row (returning a no-data error instead) or dropping stale/age fails — presenting week-old data as current, or refusing to serve it at all, both fail."
  - "NO-DATA SHAPE (analysis P4): GetPriceToolIT.pairWithNoRowReturnsTypedNoDataError passes — an enabled default pair with ZERO price_snapshot rows dispatches to a ToolResult.ValidationError naming the (asset, sub-verb) pair (the dispatcher's IllegalArgumentException path, ChatToolDispatcher.java:192-195), never a Success with invented numbers; mirrors the /asset no-data friendly error and the M1-836 failed-pair honesty (the failed pair's history: stale-with-age first, no-data only when nothing exists)."
  - "RESOLUTION FAILURE MODES (analysis P3): GetPriceToolTest (plain JUnit; populated AssetRegistry via the existing test seam, reader stubbed at readLatest) — unknownAssetErrorListsEnabledAssets, unsupportedQuoteCurrencyNamesTheOnlyAvailableCurrency (M1-671: one pair, one currency), and defaultPairAbsentOrDisabledListsEnabledSubVerbs each return the typed self-correctable error enumerating the enabled set (the helpLookup {command:null} kin); assert each is an IllegalArgumentException whose message the dispatcher surfaces, so the model can self-correct within the turn. The enabled-asset/sub-verb set is already user-visible via /help and the /asset friendly errors — no new disclosure."
  - "FOLD BOUNDARY SITING (assertion-adequacy §8; the M1-648 lesson): ChatAgentTest.getPriceResultRidesBackWrappedWithPostToolInstruction passes — a scripted text-transport turn (TOOL_CALL: getPrice {\"asset\": \"monero\"}) with the tool stubbed asserts llmProvider.lastUserPrompt carries 'Tool result for getPrice', the UNTRUSTED_CONTENT wrapper, and POST_TOOL_RESULT_INSTRUCTION verbatim, AND the delivered reply carries no protocol fragment. Asserting only the tool's return value proves the producer, not the fold — this pin sits at the last surface our code controls before the model."
  - "ALLOWLIST PARITY lands both sides in one diff (analysis P1; security.md:313-316 'closed at spec level'): ChatToolAllowlistSpecParityTest.registryMatchesMarkedSpecTable passes WITH the getPrice row added inside the tool-allowlist markers (docs/spec/security.md:324-336) and the name in ChatToolRegistry — probe: mvn -pl infochat-provider -am test -Dtest='ChatToolAllowlistSpecParityTest' is green; grep -n '^   | `getPrice`' docs/spec/security.md returns exactly the new row."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; this ticket authorizes exactly these, in plain language): (a) ChatToolRegistryTest.registryContainsExactlySpecTools — expected set gains \"getPrice\" (the seven existing names unchanged); (b) ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — gains assertArgs(\"getPrice\", List.of(\"asset:string\", \"vs_currency:string\")); (c) ChatAgentTest.renderedInstructionTableIsByteIdentical — APPENDS the getPrice line quoted verbatim in the Approach; the seven existing lines stay byte-identical; (d) ChatAgentTest.structuredToolCallDispatchesThroughTheBoundary — the wire-declaration count assertion (:1029-1030) becomes 8 ('the wire declarations render from the catalog's eight tools'). Probe: git diff over src/test names exactly these four hunks plus the added test files/tests — any other pre-existing test edit is unauthorized."
  - "SPEC AMENDMENT rides the diff (analysis P1/P9; engineering-rules §12 — the exact wording goes to the user for approval at implementation; rule-text drafts in the Approach): (i) the security.md §Prompt-injection defenses tool table gains the getPrice row between the markers; (ii) the §DB roles price_snapshot sentence (docs/spec/security.md:2117-2119) records the chat tool as a second reader of the same SELECT-only grant. Probe: spec prose is rule-text only — no dates, ticket IDs, or report citations (git diff on docs/spec/security.md)."
  - "DESIGN LEDGER (analysis P7; the M1-916/M1-918 posture): docs/design/05-llm-and-embeddings.md §5.4.6 records the getPrice catalog line and a prompt-byte ledger entry — the description is ≤ ~60 words riding the NEVER-DROPPABLE TOOL_INSTRUCTIONS scaffolding (M1-918's compactionNeverDropsScaffolding pins the complete rendered table), absorbed by the 6,144-budget headroom. Probe: grep -n 'getPrice' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mentions."
  - "D19/§9 DISCIPLINE (analysis P5): the tool introduces NO LLM call and NO inline now — the row is chosen by the reader's SQL (ORDER BY captured_at DESC LIMIT 1, AssetSnapshotReader.java:158-167), and age/stale read the injected Clock bean the reader already uses (AssetSnapshotReader.java:70-71); a String.valueOf(age) computed from Instant.now() in NEW code is a §9 violation. Probe: grep -n 'Instant.now()' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPriceTool.java returns no match."
  - "OWNER-RUN live probe (verification ceiling, the M1-916 item-6 posture — no unit test can prove the model elects the tool; an owner-executed probe with a recorded outcome, not a CI gate): after landing, the owner asks 'What is the price of zcash and monero?' on the test stack and captures the provider-log slice — probe: the slice shows a getPrice tool-loop dispatch for EACH asset and the reply presents prices with source attribution and age (a reply from memory, or a decline while /zcash answers, FAILS); the outcome is recorded in the commit message. The eval price-class flip (M1-928's none_expected rationale names this exact gap) is NOT this ticket's gate — it is the eval side's follow-up once M1-930's baseline exists."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceToolIT.java — dispatchReturnsLatestSnapshotForFixtureAsset (reproduction), staleSnapshotIsServedWithAgeDisclosed, pairWithNoRowReturnsTypedNoDataError
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceToolTest.java — resolution failure modes (plain JUnit; no container)
    - ChatAgentTest.getPriceResultRidesBackWrappedWithPostToolInstruction (fold boundary pin)
  modifies:
    - ChatToolRegistryTest.java — expected set gains getPrice (§8-authorized, item 7a)
    - ChatToolCatalogTest.java — assertArgs getPrice line (§8-authorized, item 7b)
    - ChatAgentTest.java — byte pin appends the getPrice line; wire-declaration count 7→8 (§8-authorized, items 7c/7d)
  preserves:
    - all tests currently green on main — explicitly ChatToolAllowlistSpecParityTest (auto-covers the new row), ChatToolDispatcherTest (loops registry names; the package-private test constructor is signature-unchanged), ChatToolCatalogTest's remaining pins, ChatPromptBudgetTest (scaffolding pin now includes the eighth line automatically), ChatAgentProvenanceTest (getPrice never joins POST_CORPUS_TOOLS), the asset suites (AssetHandlerIT, AssetCommandsRoundtripIT, AssetSnapshotReaderCacheIT, AssetSnapshotReaderClockTest — the reader and command path are untouched)
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §DB roles
  - docs/spec/commands.md §Asset commands
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D21
  - D30
  - D34
  - D39
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-931: Add deterministic getPrice chat tool for price_snapshot

## Context

Live observation (user, 2026-08-26): "What is the price of zcash/monero?" is
unanswerable in chat mode although the data exists — `price_snapshot` is read
only by the deterministic asset commands (`AssetSnapshotReader` →
`AssetHandler`, infochat-provider …/command/asset/), and the chat agent's
closed seven-tool allowlist contains no price-reading tool, so the model
answers from stale parametric knowledge or declines. One of the three live
failures motivating the RAG improvement campaign. Binding user decisions
(2026-08-26): NOT a RAG problem — a deterministic, read-only tool is the fix;
eval-gated acceptance is desired but the eval (M1-928/929/930) is a parallel
track that does not block this work. Single-ticket analysis:
`analysis_ref: self` — this body is the analysis.

## Root cause

Verified end to end on this checkout:

1. **No price path exists in the chat surface.** `grep -rn price_snapshot`
   over `infochat-provider/src/main/java/…/provider/chat/` returns no match.
   The allowlist is exactly seven names (`ChatToolRegistry.java:18-26`),
   mirrored byte-for-byte by the security.md table
   (`docs/spec/security.md:328-334`, enforced bidirectionally by
   `ChatToolAllowlistSpecParityTest`, M1-654). None reads `price_snapshot`.
2. **The read path to reuse already exists and is deterministic.**
   `AssetSnapshotReader.readLatest(asset, subVerb, vsCurrency)` reads the
   latest row per triple (`ORDER BY captured_at DESC LIMIT 1`,
   AssetSnapshotReader.java:158-167), computes staleness against the
   Provider-owned `infochat.assets.freshness-window`
   (application.properties:159; 120s laptop / 600s pi / 180s elsewhere) via
   the injected `Clock` (§9-compliant, AssetSnapshotReader.java:64-71), and
   fronts a 5s TTL cache (application.properties:171). `AssetRegistry`
   resolves the per-asset default sub-verb, enabled sub-verbs, the pair's
   single configured quote currency, display name, and attribution URL from
   `asset_config` (SELECT-only; security.md:2120).
3. **Staleness semantics are spec-fixed and two-level** (commands.md
   §Asset commands, Freshness contract :755-767): a stale row is SERVED
   with an explicit age disclosure — "the Provider serves the most recent
   row available with an explicit 'data is N minutes old' line, and
   degrades to a friendly error only when no row exists at all". M1-836's
   failed-pair incident is exactly this shape: a failed default pair goes
   stale (not absent), so `/zcash` answers with the ⚠ stale marker + age.
   **Brief discrepancy, resolved:** the brief asks for "the honest 'no
   data' shape" for stale pairs — per the spec and the M1-836 record, the
   honest shape for a STALE pair is data-with-disclosed-age; "no data" is
   only for a pair with zero rows. The tool inherits both levels.
4. **Brief premise falsified (prior-art rule):** no name/symbol mapping
   exists in `asset_config` or anywhere else — `grep '"xmr"|"zec"|symbol'`
   over `*.java` returns no asset-side hit; `AssetRegistry.getAsset` is an
   exact-key lookup (AssetRegistry.java:125-127). The reuse directive
   stands (reuse AssetRegistry — do not build a second resolver); the
   symbol half of the premise is false. Natural-language normalization
   ("xmr" → asset name "monero") is delegated to the model, which llm.md
   §Determinism boundary permits (the LLM writes args, never picks the
   row); the tool's unknown-asset error enumerates the configured asset
   names so the model self-corrects in-turn.
5. **The surrounding machinery needs no new code.** The tool loop folds
   any Success under UNTRUSTED_CONTENT with a per-call marker +
   POST_TOOL_RESULT_INSTRUCTION generically (ChatAgent.java:1019-1049);
   the dispatcher enforces the closed-name check, input length caps
   (500-char default), the per-turn cache, and the 25-call cap
   (ChatToolDispatcher.java:137-205); the wire declarations and the
   instruction table both render from `ChatToolCatalog` (M1-872
   single-source). The Provider role already holds SELECT-only on
   `price_snapshot` (security.md:2117-2119) — no grant change.
6. **Eval linkage (non-blocking):** M1-928's golden set labels the
   price-shaped class `none_expected: true` with a rationale naming this
   exact structural gap (no chat tool reads `price_snapshot`); M1-930's
   baseline will record the class near zero. This ticket closes the gap;
   flipping the labels is the eval side's own gated change afterwards.

What remains ASSUMPTION for the implementor to check (low risk): nothing
load-bearing — the CDI wiring of `AssetRegistry`/`AssetSnapshotReader` into
a chat-tool bean is ordinary constructor injection across packages (both are
`@ApplicationScoped`); no visibility barrier exists on the public methods
(`getAsset`, `readLatest`).

## Pitfalls

- **P1 — Closed allowlist, spec-level (D21).** The tool list is closed at
  spec level (security.md:313-316): adding `getPrice` to the registry
  without the security.md row (or vice versa) fails
  `ChatToolAllowlistSpecParityTest` in BOTH directions. The row + name +
  handler must land in one diff; the row's wording is a rides-the-diff
  amendment (§12: user approves exact wording at implementation; NOT a
  SPEC-GAP — the spec's own amendment procedure is the vehicle, the
  M1-664/M1-836 precedent).
- **P2 — §8 exact pins.** Four pre-existing tests pin today's seven-tool
  surface verbatim: `ChatToolRegistryTest` (exact set),
  `ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing` (exact arg
  shapes), `ChatAgentTest.renderedInstructionTableIsByteIdentical` (byte
  pin, :1374-1399), and the wire-declaration count 7→8 inside
  `ChatAgentTest.structuredToolCallDispatchesThroughTheBoundary`
  (:1029-1030). All four are authorized in acceptance item 7 with their new
  expected behavior stated; any OTHER pre-existing test edit is a §8
  violation the reviewer fails.
- **P3 — Symbol-mapping phantom.** Building a ticker map to satisfy the
  brief's "monero, xmr, zcash, zec" line would duplicate the resolver the
  brief simultaneously forbids duplicating. Reuse `AssetRegistry`
  exact-key resolution; the unknown-asset ValidationError lists the enabled
  names (already public via `/help` and the /asset friendly errors — no new
  disclosure).
- **P4 — Stale vs no-data conflation.** Suppressing a stale row ("no data"
  for a failed pair) breaks commands.md:755-767 (serve-with-age) and hides
  recoverable signal (M1-836: the pair's last price + age IS the honest
  answer). Serving it WITHOUT `stale`/`age_seconds` recreates the M1-927
  defect class (undated facts the model relabels as current). Both the
  stale flag and the age must be emitted fields.
- **P5 — D19 + §9.** No LLM in the tool; the row choice stays SQL (the
  reader's indexed latest-row query). `age_seconds` in NEW code must read
  the injected `Clock` bean (the same one the reader uses) — never
  `Instant.now()` inline (§9; contrast AssetReplyRenderer.java:109, which
  is pre-existing command-path code outside this diff). Never split the
  staleness decision and the emitted age across two clocks.
- **P6 — Scope-filter and provenance temptation.** Price data is not post
  data (commands.md:722): no D59 world predicate applies (the data is
  deployment-global operator config), and `getPrice` must NOT join
  `POST_CORPUS_TOOLS` (ChatAgent.java:1276-1277) — a price-grounded reply
  correctly carries the not-feed-grounded provenance notice
  (commands.md:1830-1843). Wiring it in would corrupt the post-count
  provenance signal.
- **P7 — Prompt-byte growth.** The catalog line rides the never-droppable
  TOOL_INSTRUCTIONS scaffolding (M1-918 pins the complete rendered table as
  compaction-exempt). Keep the description ≤ ~60 words; record the ledger
  entry in design 05 §5.4.6 (the M1-916 +~90-token precedent). The 6,144
  budget's ~1.7x estimate headroom absorbs it; no ladder retuning.
- **P8 — Cancellation uniformity.** All seven existing chat tools arm their
  connection via `CancellationService.armToolConnection` (grep: 7 hit
  sites). GetPriceTool reuses `AssetSnapshotReader`, which opens its own
  connection internally — the tool cannot arm a connection it does not
  hold, and refactoring the reader to take a connection would fork the
  single read path. Accepted and documented in the tool javadoc: the read
  is the same single indexed `(asset, sub_verb, captured_at DESC) LIMIT 1`
  lookup (commands.md:749-751) the un-armed command path performs; the
  `/stop`-interruptible long pole in a chat turn is the LLM call, not this
  sub-ms read. Do NOT "fix" this by duplicating the SQL.
- **P9 — Attribution and result trust posture (D30/D39).** The result must
  carry the source name (sub_verb) and the OPERATOR-configured attribution
  URL from `AssetRegistry` (the command's URL source,
  AssetHandler.java:203-204) — NOT the feed-written
  `price_snapshot.source_url` column. commands.md:768-771 makes
  attribution mandatory; `POST_TOOL_RESULT_INSTRUCTION`
  (ChatAgent.java:132-135) then binds the model to cite the URL verbatim
  and bare. Emitted strings are otherwise operator-config keys; absent
  numeric fields emit `null`, never invented zeros (the AssetReplyRenderer
  rule). The result still rides the generic UNTRUSTED_CONTENT fold — feed-
  derived data keeps the identical trust boundary as every tool output.
- **P10 — Eval coupling.** Touching the golden set or harness to "make the
  price class pass" is forbidden: M1-928's set is a frozen contract and
  M1-930's baseline must measure THIS gap before it closes. The flip is
  the eval side's follow-up; this ticket's live acceptance is the
  owner-run probe.
- **P11 — Sibling calibration (fixture discipline).** The byte pin and the
  ITs pin the END state (eight tools, dated/stale/aged emission); no
  earlier sibling exists to collide with — but the pinned catalog line and
  the security.md row must quote the SAME description text as
  ChatToolCatalog (single source; the wire declarations render from it —
  M1-916's P1 drift class). Do not hand-maintain a second copy anywhere.

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses defines
the closed tool surface and the amendment vehicle (the new row); commands.md
§Asset commands fixes what "a price" means here (default sub-verb, the pair's
single quote currency, the two-level freshness/staleness contract, mandatory
attribution) and §Chat mode fixes the tool-loop/provenance posture the tool
joins; llm.md §Determinism boundary fixes the pure-SQL row choice (D19);
security.md §DB roles records the unchanged SELECT-only grant the tool rides.

- **Files to touch:** `files_scope` (one new production class + three wiring
  edits + three new/edited test files + two §8-authorized pin edits + one
  spec + one design note).
- **Pre-decided shapes (implementation is execution):**
  1. **`GetPriceTool`** (`@ApplicationScoped`, implements
     `ChatToolRegistry.ChatTool`, package `provider.chat.tool`): constructor
     injects `AssetRegistry`, `AssetSnapshotReader`, and the
     `@ApplicationScoped` `Clock` bean. `execute`:
     - `asset` = required String arg (missing → IllegalArgumentException
       "Missing required parameter: asset", the GetPostTool.java:52-55
       pattern); resolve via `assetRegistry.getAsset` — null →
       IllegalArgumentException listing enabled asset names (P3).
     - Default pair: `asset.defaultSubVerb()` — absent or disabled →
       IllegalArgumentException listing enabled sub-verbs (the
       AssetHandler.java:138-174 semantics; no implicit fallback, D39).
     - `vs_currency` optional: absent → the pair's `defaultQuoteCurrency`;
       present but different → IllegalArgumentException naming the only
       available currency (M1-671 availability-not-capability rule,
       AssetHandler.java:176-192).
     - `readLatest(asset, defaultSubVerb, vsCurrency)` — null result →
       IllegalArgumentException "No price data for <asset>/<subVerb>" (the
       no-data level; dispatcher renders it a typed ValidationError).
     - Success JSON — single object, manual JSON via the
       `SearchPostsTool.jsonStr`/`instantStr` helpers (the GetPostTool
       emission pattern), numerics via `BigDecimal.toPlainString()`,
       absent numerics literal `null`:
       `{"asset":…,"name":<displayName>,"source":<subVerb>,"vs_currency":…,
       "price":…,"volume_24h":…,"high_24h":…,"low_24h":…,
       "change_1h_pct":…,"change_24h_pct":…,"change_7d_pct":…,
       "captured_at":<ISO-8601>,"age_seconds":<long from the injected
       Clock>,"stale":<bool from SnapshotResult>,"source_url":<
       asset_config attribution_url>}` (P4/P5/P9). ~300 bytes — far under
       any budget; no truncation machinery.
     - Javadoc states the P8 cancellation posture and the reuse
       (AssetSnapshotReader is the single interpretation of the table).
  2. **Wiring:** `ChatToolRegistry.TOOL_NAMES` + `"getPrice"`;
     `ChatToolDispatcher` @Inject constructor gains `GetPriceTool` and the
     tools map entry (the startup completeness check
     `requireHandlerForEveryAdvertisedTool` then enforces both sides);
     `ChatToolCatalog.TOOLS` APPENDS (order preserved — the byte pin's
     existing lines stay untouched):
     `new Tool("getPrice", "<description below>",
     List.of(new ToolArg("asset","string",true,"\"monero\""),
             new ToolArg("vs_currency","string",false,"\"usd\"")))`.
  3. **Catalog description (exact — the byte pin and the security.md row
     quote this verbatim; ≤ ~60 words per P7):**
     `get the latest stored price of an operator-configured crypto asset
     (e.g. zcash, monero) with its source, capture time and age. Use this
     for any question about current prices, costs or market values of
     these assets, and never state a price from memory. If the result is
     marked stale, say the data is old and give its capture time instead
     of presenting it as current.`
     (Three load-bearing elements: the anti-parametric "never from memory"
     clause — the defect this tool fixes; the stale-honesty clause — P4;
     and the asset-name examples so the model passes configured names.)
  4. **Spec amendment drafts (§12 — exact wording user-approved at
     implementation; number-free):**
     - security.md tool table, appended row:
       `| `getPrice` | `asset: string` (configured asset name, length-capped), `vs_currency: string` (optional; the pair's configured quote currency) | `{asset, name, source, vs_currency, price, volume_24h, high_24h, low_24h, change_1h_pct, change_24h_pct, change_7d_pct, captured_at, age_seconds, stale, source_url}` or a typed no-data validation error | Reads the deployment's latest `price_snapshot` row for the asset's default `(asset, sub_verb)` pair via the same read path as the asset commands (D39); the row choice is SQL-decided and reproducible on unchanged DB state (D19) — no LLM runs in the tool. Price data is not post data (commands.md §Asset commands): it is operator-configured and deployment-global, so no per-(user, scope) world filter applies and the result never feeds the feed-post provenance count. Absent numeric fields are emitted `null`, never invented. A snapshot older than the freshness window is still returned, marked `stale: true` with its `captured_at` and `age_seconds` (the asset freshness contract: stale data is served with its age disclosed; only a pair with no row at all returns the no-data error). `source_url` is the operator-configured attribution URL (D30), cited bare. |`
     - security.md §DB roles (:2117-2119): "…the Provider reads the latest
       snapshot per `(asset, sub_verb)` for `/zcash` and `/monero`…"
       becomes "…for the asset commands and the `getPrice` chat tool…".
     - commands.md §Chat mode needs NO edit: its "strict, fixed tool
       surface (read-only, scope-filtered)" summary already delegates
       per-tool truth to the security.md table (the helpLookup precedent —
       a tier filter, not a (user, scope) world filter, ships today under
       the same summary).
  5. **Design note:** docs/design/05-llm-and-embeddings.md §5.4.6 records
     the eighth tool line + the ledger entry (P7).
- **Steps, in implementation order:**
  1. Write the three GetPriceToolIT arms + the reproduction conversion RED
     (`getPrice` dispatch fails "Unknown tool: getPrice").
  2. GetPriceTool + the three wiring edits; the IT arms go GREEN (registry
     refresh after JDBC seeding: reuse the AssetHandlerIT mechanism —
     `AssetRegistry.refresh()` is package-private to `command.asset`, so
     either place the seeding helper there or take the registry through
     its test seam; pick one, do not invent a second registry).
  3. GetPriceToolTest resolution arms (plain JUnit, stubbed reader).
  4. The four §8-authorized pin updates + the ChatAgentTest fold-boundary
     test, with the catalog line quoted verbatim from shape 3.
  5. Spec amendment (user-approved wording) + design-05 ledger.
  6. Hand the owner-run live probe to the user with its record obligation.
- **Controls to preserve (§10):** the dispatcher boundary controls the new
  tool inherits unchanged — closed-name check, input length caps, per-turn
  cache, 25-call cap, IllegalArgumentException→ValidationError mapping
  (ChatToolDispatcher.java:143-204); the generic fold (UNTRUSTED_CONTENT
  marker + POST_TOOL_RESULT_INSTRUCTION + whole-or-nothing budget fit,
  ChatAgent.java:1027-1049); the sanitize path downstream of the reply;
  AssetSnapshotReader's freshness-window/TTL-cache/§9-Clock behavior
  (untouched — a second consumer, not a fork); the parity guards
  (ChatToolAllowlistSpecParityTest, ChatToolCatalogTest,
  ChatToolRegistryTest, renderedInstructionTableIsByteIdentical — all pass
  WITH the authorized updates); ChatPromptBudgetTest's scaffolding pin
  (now covers eight lines automatically).
- **Alternatives considered (rejected, recorded for the commit message):**
  (B) RAG over price data — binding user rejection (not a retrieval
  problem; also D19-hostile); (C) deterministic price pre-fetch folded
  into every turn (the semanticSearch/D28 shape) — burns never-droppable
  prompt bytes on every turn for a rare intent, and price questions are
  model-detectable (the tool-loop election is the cheap path); (D) extend
  `helpLookup` or the /help delivery path — wrong surface (command intent,
  not data lookup); (E) catalog-description-only (no tool) — impossible:
  the allowlist is the only data path into the model context, and no
  existing tool reads the table; (F) tool-owned duplicate SQL with its own
  armed connection — forks the single interpretation of `price_snapshot`
  (staleness, window, cache) that the brief mandates reusing; rejected,
  see P8; (G) sub-verb input arg (exchange selection) — binding brief
  input shape is asset + optional vs_currency; the default-pair resolution
  matches how a human asks and how bare `/zcash` behaves.
- **Pitfall→mitigation:** P1→item 6 (parity probe) + item 8 (amendment);
  P2→item 7 (the four authorizations + diff probe); P3→item 4
  (resolution failure modes); P4→items 2-3 (stale/no-data arms); P5→item
  10 (grep probe) + the injected-Clock seam; P6→out_of_scope fence +
  ChatAgentProvenanceTest preserved; P7→item 9 (ledger) + the ≤60-word
  description; P8→tool javadoc + Approach F rejection; P9→item 1's
  source_url assertion (registry URL, not snapshot column); P10→
  out_of_scope fence + item 11's phrasing; P11→shape 3 single source +
  the byte pin quoting it.

## Definition of done

The reproduction IT passes (latest-row dispatch through the real
dispatcher, newest row, attribution verbatim); the stale arm passes (price
served WITH stale:true and exact age under a pinned Clock); the no-data arm
returns the typed error; the resolution failure modes return
self-correctable errors listing the enabled sets; the fold-boundary pin
asserts the wrapped result + POST_TOOL_RESULT_INSTRUCTION on the prompt the
model sees; the spec carries the user-approved getPrice row and the
DB-roles reader sentence; the design-05 ledger entry lands; the four
authorized pin updates land and every other pre-existing suite passes
unmodified; mvn verify green from repo root; the owner-run live probe is
handed over with its record obligation.

## Verification

- P1 → acceptance items 6 and 8: the parity test green with both sides in
  one diff; the grep probe for the spec row.
- P2 → item 7's diff probe (git diff over src/test names exactly the four
  authorized hunks + additions); the reviewer diffs each against its
  plain-language authorization (§8).
- P3 → GetPriceToolTest.unknownAssetErrorListsEnabledAssets: feeds "xmr"
  against a registry holding zcash/monero, asserts the error enumerates
  the configured names (a bare "unknown asset" the model cannot recover
  from, or a second symbol table in the diff, fails).
- P4 → item 2 (staleSnapshotIsServedWithAgeDisclosed: price PRESENT +
  stale true + exact age; both wrong shapes fail) and item 3
  (pairWithNoRowReturnsTypedNoDataError: no invented numbers).
- P5 → item 10's grep probe (no Instant.now() in GetPriceTool) + the
  stale arm's exact-age assertion (a second clock source drifts the age
  and fails).
- P6 → ChatAgentProvenanceTest passes UNCHANGED; probe: git diff names no
  ChatAgent.java hunk (POST_CORPUS_TOOLS untouched).
- P7 → item 9's grep probe + the description-length bound stated in
  shape 3 (the byte pin makes every added word visible to review).
- P8 → the tool's javadoc names the posture; reviewer reads it against the
  7-site armToolConnection grep (any NEW un-armed DB-opening code outside
  the reused reader is a finding).
- P9 → item 1 asserts source_url equals the SEEDED asset_config
  attribution_url (a mutation emitting price_snapshot.source_url fails;
  a mutation dropping the URL fails the M1-782-kin attribution check).
- P10 → out_of_scope fence; git diff names no
  retrieval-eval/golden-set/harness path.
- P11 → renderedInstructionTableIsByteIdentical and the security.md row
  both quote shape 3's string; grep -c the description's distinctive
  clause ("never state a price from memory") across src/main + docs/spec
  returns exactly one source (ChatToolCatalog.java).
- FAILURE-MODE coverage beyond the reproduction → items 2, 3, 4 (stale,
  no-row, unknown-asset, wrong-currency, disabled-default inputs) and the
  fold pin's no-protocol-fragment assertion.
- acceptance items 11-12 → the owner-run probe with its named FAIL
  condition (memory answer or decline while /zcash answers) and mvn
  verify.

## Out-of-scope

Named in `out_of_scope`: RAG for prices (binding user decision); any
AssetSnapshotReader/AssetHandler/AssetReplyRenderer semantics change (the
command surface is correct as shipped); any ticker-symbol mapping table
(the brief's symbols premise is falsified — P3); a sub-verb input argument
(binding input shape); POST_CORPUS_TOOLS/provenance wiring (P6); the eval
harness/golden set (M1-928/929/930 parallel; the class flip is the eval
side's gated follow-up); DB grants (SELECT-only verified,
security.md:2117-2119); sanitizer/fold/cap/prompt-budget-ladder changes
(ride unchanged; only the ledger entry moves); any ChatAgent production
change (the loop is generic — escalate if one seems needed). This ticket
modifies EXACTLY four pre-existing tests, each §8-authorized in acceptance
item 7 with its new expected behavior stated in plain language;
every other pre-existing suite must pass unmodified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-931-getprice-tool.md
```
