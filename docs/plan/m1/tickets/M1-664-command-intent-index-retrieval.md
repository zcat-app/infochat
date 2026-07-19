---
id: M1-664
title: "Semantic command-intent index: retrieval + registration"
status: pending
created: 2026-07-19
last_updated: 2026-07-19
blocked_by: []
files_budget: 24
files_scope:
  - infochat-core/src/main/resources/db/migration/V60__doc_embedding.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndexBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/DocEmbeddingDao.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandIntentSynonyms.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/commands.md
  - docs/spec/llm.md
  - docs/spec/decisions.md
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/04-security.md
  - docs/design/03-commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    THE BOUNDARY THAT KILLED M1-648 — any delivery of composed help or
    command-usage text into the chat reply outside the model's own sanitized
    prose. No collectHelpBlock equivalent, no post-sanitize append, no reply
    mutation keyed on tool results. helpLookup's output reaches the user only
    through the model's reply, which passes the existing sanitize path
    unchanged. The delivery path is M1-665, gated on the M1-663 spec
    amendment; the design record for why is
    docs/plan/m1/redteam/M1-648-2026-07-19-r2.md (medium INJECTION:
    post-sanitize, model-elected append of privileged command usage).
  - >-
    Returning a composed usage body (the composeDetailIfVisible output) as
    the tool result. The tool returns the matched command NAME plus the
    catalogue's one-line description; full usage/example bodies never enter
    the model context. This keeps the sanitizer interaction bounded (the r2
    audit's out-of-model note: a tool that feeds privileged syntax into the
    model context structurally raises sanitizer match volume) and keeps
    delivery decisions where M1-665 can make them deterministically.
  - >-
    USER_GUIDE.md conceptual topics ("what is probation", "DM vs group",
    retention semantics) — M1-649. This ticket indexes COMMAND intents only,
    where every answer has a runtime source of truth to compose from.
  - >-
    Injecting the command catalogue into the chat system prompt. Measured and
    rejected in M1-648: ChatPromptBuilder hands the ENTIRE
    infochat.context-window to history alone; the system prompt is re-sent up
    to MAX_TOOL_ITERATIONS (10) times per turn; cache_control is set only by
    AnthropicProvider, so no local profile has prompt caching; on pi
    (context-window=4096) the full catalogue exceeds the window outright.
    Retrieval must be on-demand.
  - >-
    Reusing post_embedding, or altering it, its partitioning, its TTL-by-
    partition-drop model, or its grants. The docs corpus gets its own table.
  - >-
    Changing which posts a query returns, or any part of the post retrieval
    path (SemanticSearchTool, ChatAgent's semantic pre-fetch). D19 is
    untouched.
  - >-
    LLM-in-the-retrieval-loop techniques — query rewriting, HyDE, LLM
    re-ranking — consistent with M1-617's out_of_scope and the D19
    determinism boundary.
  - >-
    Deprecating M1-647's synonym map. It remains the probation-reachable path
    and the seed corpus; this ticket adds a second, chat-only tier.
  - >-
    docs/spec/verification.md. M1-654 removed its duplicated tool-name
    enumeration and repointed it at security.md's table as the single source
    of truth; this ticket must not re-add an enumeration there. The only spec
    list this ticket edits is security.md's table.
  - >-
    The closed-allowlist mechanism itself, and M1-654's parity guard. This
    ticket adds one ROW to the spec table and one NAME to the registry; the
    guard then proves the two agree. Do not modify, relax, or work around it.
  - >-
    docs/spec/security.md §LLM output sanitizer. That section is M1-663's
    amendment surface; this ticket's only security.md edit is the
    tool-allowlist table row.
acceptance:
  - >-
    Migration V60 creates doc_embedding (doc_id TEXT PK, doc_kind TEXT,
    target_ref TEXT, content_hash TEXT, embedding vector(768), embedding_model
    TEXT) with an HNSW cosine index, NOT partitioned, and grants the provider
    role SELECT + INSERT + DELETE. 768 is the single app-wide dimension
    already recorded in the embedding_metadata singleton (see Notes), so
    EmbeddingMetadataStartupGuard's model/dimension identity assumption holds
    for both corpora.
  - >-
    On startup the provider embeds one short intent document per catalogue
    command (seeded from CommandIntentSynonyms plus the command's own name and
    short description) and upserts it into doc_embedding. Re-embedding is
    skipped when content_hash and embedding_model both match, so a restart
    with unchanged input performs zero embedding calls.
    CommandIntentIndexTest.restartWithUnchangedCorpusPerformsNoEmbeddingCall
    passes.
  - >-
    A change to the embedding model or to any intent document's text causes
    that row to be re-embedded on next startup — a stale vector can never
    outlive its source text. CommandIntentIndexTest.changedIntentTextIsReEmbedded
    passes.
  - >-
    A new read-only HelpLookupTool resolves a free-text intent to a command
    name via one fused pgvector query, filtered to the caller's visible tier
    BEFORE the result is returned, reusing the same HelpTier predicate
    HelpCommandHandler uses.
    HelpLookupToolTest.adminOnlyCommandNeverSurfacesToNonAdmin passes.
  - >-
    THE CORE INVARIANT — the tool returns a command NAME (plus the
    catalogue's one-line description composed at call time from the runtime
    catalogue), never a body derived from indexed text. Embedded text is used
    only for MATCHING, never for ASSERTING. A stale intent document can
    therefore degrade the match but can never produce wrong syntax.
    HelpLookupToolTest.toolOutputComesFromRuntimeCatalogueNotFromIndexedText
    passes by mutating an indexed intent document's text and asserting the
    returned name and description are unchanged.
  - >-
    Below the similarity threshold the tool returns no command and the agent
    is directed to say it does not know and point at /help, rather than
    answering from general knowledge.
    HelpLookupToolTest.belowThresholdReturnsNoCommand passes.
  - >-
    Free-text phrasings resolve to the right command at the TOOL level: "how
    do I stop seeing posts from this source" resolves to unfollow-source, and
    a test covers at least three phrasings that share no prefix with their
    target command. Delivered usage/examples are deliberately NOT asserted —
    that end-to-end surface is M1-665.
  - >-
    NEGATIVE PIN of the M1-665 boundary — the chat reply delivery path is
    unchanged by this ticket: what the adapter receives is the sanitized
    (and, where applicable, translated) model output with no post-sanitize
    accretion of tool-derived text.
    ChatAgentTest.noToolDerivedTextIsAppendedAfterSanitize passes by scripting
    a model turn that calls helpLookup and asserting the delivered reply
    contains no bytes sourced from the tool result that did not pass through
    the sanitizer.
  - >-
    Decision D66 in docs/spec/decisions.md records the second embedded
    corpus, the match-not-assert invariant, and the tier-filter-before-return
    rule — and is explicitly silent on chat delivery, which is governed by
    the M1-663 amendment and implemented by M1-665. docs/spec/commands.md
    §Chat mode and docs/spec/llm.md §Embedding pipeline document the new tool
    and corpus without describing any append or delivery mechanism.
    (Re-verify D66 is still free immediately before writing the row — see
    Notes.)
  - >-
    docs/spec/security.md §Prompt-injection defenses gains a table row for
    the new tool — name `helpLookup`, its free-text input, its output shape
    (matched command name + one-line description, or empty), and a Notes
    entry recording the tier filter — placed INSIDE the
    `<!-- tool-allowlist:begin -->` / `<!-- tool-allowlist:end -->` markers,
    so ChatToolAllowlistSpecParityTest.registryMatchesMarkedSpecTable stays
    green. The registry key and the spec row's name must match byte-for-byte.
  - >-
    ChatAgent.TOOL_INSTRUCTIONS advertises `helpLookup` to the model, in the
    same one-line-per-tool shape the other entries use, naming its free-text
    input and directing the model to point users at `/help <name>` for usage
    rather than restating command syntax itself. This is not cosmetic:
    TOOL_INSTRUCTIONS is a hardcoded copy of the tool-name list and the ONLY
    thing that tells the model a tool exists (see Notes) — a tool absent from
    it is registered, dispatchable, spec'd and parity-guarded yet never
    called.
  - >-
    ChatAgentTest gains everyRegisteredToolIsAdvertised, which DERIVES its
    expectation from ChatToolRegistry.toolNames() and asserts every name
    appears in TOOL_INSTRUCTIONS. It must be derived rather than a
    hand-listed set: the four existing toolInstructions*Params tests are
    hand-written per tool and already cover only 4 of the 6 shipped tools,
    which is exactly the drift this item closes for every future tool. Those
    four tests are NOT modified or removed — this is an addition.
    ChatAgentTest.everyRegisteredToolIsAdvertised passes.
  - >-
    docs/spec/commands.md's `/stop` cancellation paragraph (commands.md:980-989)
    is corrected. It asserts "every tool in the closed allowlist ... is a
    read-only DB query" and then enumerates FIVE names — stale since M1-589,
    omitting `semanticSearch`. Replace that enumeration with a pointer to
    security.md's marked table (the single source of truth M1-654
    established; do not re-add a list that can drift), and record
    `helpLookup`'s cancellation story as the same read-only
    `pg_cancel_backend` primitive. That paragraph requires a tool added by
    spec amendment to "define their own cancellation primitive before being
    added to the registry", so this is a precondition of registering the 7th
    tool.
  - >-
    The three design-tier mirrors of the closed list stop contradicting
    security.md's table: docs/design/04-security.md:189-199 (heading "closed
    at exactly five" plus a five-row table — stale, missing `semanticSearch`
    since M1-589), docs/design/03-commands.md:1056-1058 (near-verbatim mirror
    of the `/stop` cancellation enumeration), and
    docs/design/05-llm-and-embeddings.md:473 (prose list). Each gains
    `helpLookup` and stops asserting a fixed count. Design notes are
    non-normative, so the bar is "does not contradict security.md's marked
    table" — do NOT turn them into a second source of truth.
  - >-
    AUTHORIZED PRE-EXISTING TEST CHANGE —
    ChatToolRegistryTest.registryContainsExactlySpecTools pins a six-name
    Set.of and fails once ChatToolRegistry gains a 7th entry. Its expected
    set becomes the SEVEN names (the existing six plus `helpLookup`). Nothing
    else in that file changes, and the assertion stays an exact set equality
    — never a containment check, a size check, or a removal.
  - >-
    CommandIntentSynonyms is reachable from the index builder. It is
    currently `final class` (package-private) in provider.messaging with a
    private INTENT_TO_COMMAND map, while files_scope places
    CommandIntentIndexBuilder in provider.help — so seeding requires widening
    the class and exposing a read-only accessor. Widen no further than the
    builder needs; the map stays immutable and is never mutated by the index.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
      change: >-
        registryContainsExactlySpecTools — the expected Set.of grows from six
        names to seven (adds `helpLookup`). Exact-equality assertion retained.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      change: >-
        ADDS everyRegisteredToolIsAdvertised (derived from toolNames()) and
        noToolDerivedTextIsAppendedAfterSanitize (the M1-665 boundary pin).
        The four existing toolInstructions*Params tests are left byte-for-byte
        unchanged — additions only, never a replacement, merge, or weakening.
  preserves:
    - all tests currently green on main
    - >-
      M1-654's ChatToolAllowlistSpecParityTest — it stays green only if the
      security.md row and the registry key match exactly, which is the point.
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D21
  - D54
  - D58
decomposed_from: M1-648
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-664: Semantic command-intent index: retrieval + registration

## Context

Ticket A of the M1-648 decomposition — the safe 80%. M1-648 reached
round-4 APPROVE and then died at the redteam gate on a *delivery* defect
(`docs/plan/m1/redteam/M1-648-2026-07-19-r2.md`): composed help text was
appended to the chat reply after the sanitizer, at the model's election.
The retrieval surface, by contrast, was audited CLEAN on the pre-fix diff
(`docs/plan/m1/redteam/M1-648-2026-07-19.md` — read both audits before
implementing; they are the evidence base for this split). This ticket
rebuilds exactly the audited-sound part: the doc_embedding corpus, the
startup-built intent index, and a tier-filtered lookup tool that returns a
command NAME. How a matched command's usage reaches the user is M1-665,
under the M1-663 spec amendment.

The motivating defects are unchanged from M1-648: the chat agent invents
command syntax when asked "how do I add a source" (a trust defect, not a
missing convenience), and M1-647's hand-written synonym table cannot cover
the tail of free-text phrasings. Retrieval finds a POINTER; the runtime
composes the ANSWER. The embedded intent document is a matching surface
only — its worst failure mode is a missed match, never a wrong instruction.

## Census

This ticket adds a name to a CLOSED LIST duplicated across several sites,
so it is class-scoped. The class is "places that enumerate the chat
tool-name set". Enumerated mechanically — every file carrying the current
six tool-name literals:

    grep -rln -E "searchPosts|semanticSearch|getPost|getReferences|recallMemory|listSaves" \
      infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ \
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ \
      docs/spec/ docs/design/

| Site | Disposition |
|---|---|
| `docs/spec/security.md` (marked table) | fix — new `helpLookup` row inside the M1-654 markers |
| `.../chat/ChatToolRegistry.java` (`TOOL_NAMES`) | fix — the registry entry |
| `.../chat/ChatToolDispatcher.java` (handler map) | fix — the handler wiring |
| `.../chat/ChatAgent.java` (`TOOL_INSTRUCTIONS`) | fix — the advertising item |
| `.../chat/ChatAgentTest.java` | fix — derived `everyRegisteredToolIsAdvertised` guard + the delivery-boundary pin |
| `.../chat/ChatToolRegistryTest.java` (six-name pin) | fix — authorized pre-existing test change |
| `docs/spec/commands.md:980-989` (`/stop` cancellation) | fix — stale five-name list → pointer; `helpLookup` cancellation primitive |
| `docs/design/05-llm-and-embeddings.md:473` (prose list) | fix — add `helpLookup` |
| `docs/design/04-security.md:189-199` ("closed at exactly five" + 5-row table) | fix — stale since M1-589; add `semanticSearch` and `helpLookup`, drop the fixed count |
| `docs/design/03-commands.md:1056-1058` (`/stop` cancellation mirror) | fix — same correction as the spec copy above |
| `docs/spec/verification.md` | out-of-scope — M1-654 removed its enumeration and repointed it at security.md; this ticket must not re-add one |
| every remaining hit of the grep above — per-tool unit tests, retrieval/DataSource tests, `docs/spec/schema.md`, and prose cross-references | out-of-scope — these name tools as their own subject under test or as cross-references. The test is NOT "how many names appear" but whether the site asserts the closed SET: none does, so none can go stale as a list when a 7th tool lands. This blanket row is the disposition for every such hit. |

The grep is deliberately broad (any one of the six names), so it returns
both genuine enumeration sites and incidental single-name mentions; the
last row disposes the latter as a class. Re-run the grep live at `start`
(the line numbers above are as of 2026-07-19 and may drift).

Why the ChatAgent row matters most: M1-654's parity guard covers exactly
ONE pair — security.md's table against the registry. TOOL_INSTRUCTIONS is
the only advertising path, so missing it ships a tool the model is never
told about (this exact omission survived two rounds of M1-648's authoring;
see that ticket's revision history).

## Acceptance

See `acceptance`. New table + grants, startup-built intent index with
content-hash staleness detection, a tier-filtered name-returning lookup
tool, the match-not-assert invariant pinned by a mutation test, the full
closed-list census discharge — and a negative pin
(noToolDerivedTextIsAppendedAfterSanitize) that proves this ticket did NOT
rebuild the delivery path M1-665 owns.

## Out-of-scope

See `out_of_scope`. The first two entries are the decomposition boundary:
no delivery of composed help text into the reply, and no usage bodies in
the model context. If implementation pressure pushes against either,
that is the M1-648 design defect reasserting itself — escalate, do not
accommodate.

## Notes

**Interim UX until M1-665 lands.** The model answers "use `/help
unfollow-source`" instead of showing usage inline. That survives the
sanitizer even for admin commands: LlmOutputSanitizer's CLOSED_LIST
patterns match the slash-prefixed literal (`compileClosedListPattern`
quotes the token, so `/grant-admin` matches but `help grant-admin` after
`/help ` does not). A model that disobeys TOOL_INSTRUCTIONS and restates
privileged syntax gets redacted per-occurrence — that is the sanitizer
working, not a defect of this ticket.

**Why a separate table, same database.** One Postgres, one pgvector, one
embedding model. `embedding_metadata` is a singleton pinning model
identifier and dimension app-wide — two corpora cannot use different
models without breaking `EmbeddingMetadataStartupGuard`. The dimension is
**768 on every profile in v1**: exactly one `infochat.embeddings.dimension`
key exists in the tree
(`infochat-collector/src/main/resources/application.properties:548`, value
`768`, no per-profile override) and `V11__post_embedding.sql:65` hardcodes
`vector(768)`; D54 permanently supersedes the per-profile table in
`docs/design/05-llm-and-embeddings.md` (which that file itself labels "the
*intended* design, NOT the v1 shipped reality"). What the docs corpus must
NOT share is `post_embedding`'s shape: that table is partitioned by
`fetched_at` for TTL-by-partition-drop, and a docs corpus has no TTL.

**Grants are the non-obvious blocker.** `V11` grants the provider role
only `SELECT` on `post_embedding` — writes are the collector's. The intent
index is provider-owned and provider-written, so V60 must grant the
provider INSERT/DELETE on `doc_embedding` specifically. Do not widen the
provider's grants on any existing table. The DELETE-then-INSERT upsert
shape (in one transaction, UPDATE withheld) matched the narrow grant in
the torn-down implementation and was verified in the CLEAN audit (item 10)
— keep it.

**Corpus size and cost.** One document per catalogue command, ~41 rows of
a sentence or two. Startup embedding is a single batch call through the
existing `EmbeddingProvider` SPI (already entity-agnostic, needs no
change). The content-hash skip means steady-state startup cost is one
SELECT.

**Threshold calibration.** The post-retrieval cutoff took two tickets to
settle (M1-616 set it, M1-619 moved 0.75→0.65). Do NOT reuse the post
cutoff — a 41-document intent corpus has different similarity statistics.
Pick a starting threshold, pin it as a named constant with a comment
recording how it was chosen, and treat recalibration as a follow-up.

**Tier filtering before return, not after.** The same existence-oracle
risk as M1-647, with a wider attack surface because the input is free
text. Filter to the caller's visible set INSIDE the query (bind
`target_ref = ANY(?)` in the WHERE, as the CLEAN audit's item 4 verified)
— never let an invisible command's name enter the LLM's context and rely
on the sanitizer to remove it.

**Reachability limit, stated honestly.** Chat-only. Probation users cannot
reach it; M1-647 is their path, which is why that ticket was a
prerequisite and is not superseded.

**Package boundaries.** `CommandIntentSynonyms` is package-private in
`provider.messaging` with a private map; `HelpTier`, `CommandHelp` and the
CATALOGUE are package-private members of `HelpCommandHandler`. The new
`provider.help` and `provider.chat.tool` classes sit outside that package,
so both need a visibility widening. Widen the minimum the callers need.

**D66 renumber history.** This ticket's decision row is D66, NOT D64:
M1-653 landed D64 for an unrelated decision, and D63/D65 are claimed in
M1-642/M1-652 acceptance prose — claims invisible to `decisions.md`.
Re-verify D66 is still free immediately before writing the row:
`grep -rn 'D6[0-9]' docs/plan/m1/tickets/*.md docs/spec/decisions.md`.
(M1-649 cites D66 in `decision_refs` — that is a citation of the row this
ticket creates, not a competing claim.)

**Plan sidecar.** complexity:high — `/m1-tick start` spawns the
plan-writer. The migration, the startup build path, and the tool wiring
are three separable pieces; sequence them so the table and index land
before the tool is registered.

**The closed tool allowlist is a spec gate, not a code detail.**
`ChatToolDispatcher` rejects any name absent from `toolNames()`, so a
"deterministic-only" tool is not a loophole; registering `helpLookup`
requires the security.md table row, and M1-654's parity guard turns that
requirement into a build failure. TOOL_INSTRUCTIONS is a hardcoded literal
in ChatAgent — nothing derives it from the registry, which is why the
advertising acceptance item and the derived guard test exist.

**Original design record.** The full original body — census provenance,
revision history, the advertising-gap postmortem — is preserved in
`docs/plan/m1/tickets/M1-648-semantic-command-intent-index.md`
(status: abandoned, decomposed). This ticket is self-contained; read the
original only for the "why" behind a rule you are tempted to relax.
