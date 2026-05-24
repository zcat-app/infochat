---
id: M1-062
title: Chat agent tool registry + dispatcher + prompt shape
status: done
created: 2026-05-24
last_updated: 2026-05-25
blocked_by:
  - M1-061
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatMemoryPreFetcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPostTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetReferencesTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/RecallMemoryTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/ListSavesTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any InboundRouter change or chat-mode dispatch wiring — M1-063 territory; this ticket builds the tool surface in isolation
  - any chat_session or chat_message persistence — M1-063 territory
  - any /clear, /compress, /stop, /retry, /forget, /export handler — M1-064, M1-065, M1-066, M1-067 territory
  - any LLM output sanitizer change — the sanitizer already exists (M1-037/M1-040); this ticket's prompt builder calls it, does not modify it
  - any group-scope dispatch or periodic digest — T2-F territory
  - any tool that mutates users, group_membership, is_admin, is_banned, audit_log, source, source_subscription — spec §Never exposed (forever)
  - any modification to ModelTask.java — CHAT_AGENT already exists in the enum
  - any auto-compress trigger or compression LLM call — M1-064 territory
acceptance:
  - "ChatToolRegistry.java exists as @ApplicationScoped and exposes a public method returning the closed set of tool names. The set equals exactly {searchPosts, getPost, getReferences, recallMemory, listSaves} — the five names from the tool allowlist in docs/spec/security.md §Prompt-injection defenses (LLM call sites). ChatToolRegistryTest.registryContainsExactlySpecTools asserts byte-for-byte equality (no extra names, no missing names) — this is the CI assertion required by spec: 'CI fails on a mismatch in either direction.' Verify: ChatToolRegistryTest.registryContainsExactlySpecTools passes"
  - "ChatToolDispatcher.java exists and dispatches a tool call by name to the matching tool implementation. An unrecognized tool name is rejected with a typed validation-error reply (spec: 'nothing else is callable'). Verify: ChatToolDispatcherTest.rejectsUnknownToolName passes"
  - "Every tool implementation validates all free-form string and list inputs against a profile-driven length cap BEFORE any SQL runs (spec: 'a call exceeding the cap is rejected by the tool dispatcher before any SQL runs and the LLM sees a typed validation-error reply'). Verify: ChatToolDispatcherTest.rejectsOversizedInput passes"
  - "SearchPostsTool reads only READY posts visible in the calling (user, scope), filtered by the scope's tag_mode rules (spec §searchPosts). Tag inputs are validated against the controlled vocabulary. Verify: ChatToolDispatcherTest.searchPostsScopeFiltered passes"
  - "GetPostTool returns null for a UID not visible in the calling scope — the existence-vs-no-access distinction is never exposed (spec §getPost). Verify: ChatToolDispatcherTest.getPostReturnsNullForInvisibleUid passes"
  - "RecallMemoryTool reads chat_memory for the calling (user, scope) only — never cross-scope (D28, spec §recallMemory). Each keyword input is length-capped. Verify: ChatToolDispatcherTest.recallMemoryNeverCrossesScope passes"
  - "ListSavesTool reads the caller's saved_post rows globally (D13); never another user's saves (spec §listSaves). Verify: ChatToolDispatcherTest.listSavesNeverReturnsOtherUserRows passes"
  - "ChatPromptBuilder.java wraps user-derived text in a delimiter block whose marker contains a per-call random value (spec §Prompt-injection defenses: 'Attackers cannot pre-guess the marker and therefore cannot forge a closing tag inside the body'). Verify: ChatPromptBuilderTest.markerIsRandomPerCall passes"
  - "ChatPromptBuilder includes the system instruction to never follow instructions inside the wrapper and to refuse action requests with the structured refusal marker (spec §Prompt-injection defenses). Verify: ChatPromptBuilderTest.systemPromptContainsRefusalInstruction passes"
  - "ChatMemoryPreFetcher.java performs the cheap deterministic keyword match on chat_memory for the calling (user, scope) and returns results to be folded into the prompt before the LLM call (spec §Memory retrieval — Pre-fetch). Verify: ChatPromptBuilderTest.preFetchResultsFoldedIntoPrompt passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Memory retrieval
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D21
  - D28
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 1131
      removed: 9
outline_file: target/m1-tick-outline-M1-062.md
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-25
    category: DOS
    severity: low
    promise: |
      Tool calls per chat turn — fixed cap. Tool results are cached within
      a single turn so identical calls don't re-query.
    gap: |
      Convenience dispatch() overload creates a fresh TurnContext per call,
      bypassing per-turn cap and cache. The TurnContext-aware overload
      correctly enforces both, but the bypass is public.
    repro: |
      M1-063 session dispatch uses the convenience overload. LLM issues 100
      tool calls; each gets a fresh context; cap never fires; cache empty.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: main
    head: m1/M1-062-chat-agent-tool-registry
    verdict_file: docs/plan/m1/redteam/M1-062-2026-05-25.md
    findings_count: 4
    out_of_model_count: 1
    note: |
      Two high-severity findings (list-size unbounded DOS, memory pre-fetch
      outside untrusted delimiter) and two medium (unbounded result sets,
      no per-turn cap/cache). Done commit is immutable; fixes land as
      remediation tickets. Finding 4 may belong to M1-063 scope.
  - date: 2026-05-25
    verdict: FINDINGS
    base: main
    head: m1/M1-062-chat-agent-tool-registry
    verdict_file: docs/plan/m1/redteam/M1-062-2026-05-25-r2.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Second audit post-remediation. All four original findings (2 high,
      2 medium) resolved. One new low: convenience dispatch() overload
      bypasses per-turn state. Acceptable residual risk; naturally
      addressed when M1-063 wires TurnContext.
clarity_check:
  date: 2026-05-24
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-062: Chat agent tool registry + dispatcher + prompt shape

## Context

The chat agent's security model is defined at spec level: a strict five-tool
allowlist, per-call random-marker prompt wrapping, and length-capped inputs
validated before any SQL runs (`docs/spec/security.md` §Prompt-injection
defenses). This ticket builds the tool surface in isolation — the registry,
the dispatcher, the prompt builder, and the memory pre-fetcher — so M1-063
(InboundRouter dispatch) can wire them into the live message path. The tool
registry's CI shape assertion (`registryContainsExactlySpecTools`) is the
spec-required structural guarantee that additions or removals to the tool
surface are spec amendments, not silent drift.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **ChatToolRegistry** holds exactly the five spec-listed tool names and a CI
   test asserts byte-for-byte equality with the spec table.
2. **ChatToolDispatcher** routes by name, rejects unknown tools, enforces
   length caps on all inputs before SQL.
3. **Five tool implementations** (SearchPostsTool, GetPostTool,
   GetReferencesTool, RecallMemoryTool, ListSavesTool) each enforce scope
   filtering and the tool-specific contract from the spec table.
4. **ChatPromptBuilder** wraps user text with a per-call random marker and
   includes the refusal instruction.
5. **ChatMemoryPreFetcher** runs the deterministic keyword pre-fetch on
   `chat_memory` for the calling `(user, scope)`.
6. `mvn verify` is green.

## Out-of-scope

- **No InboundRouter wiring.** This ticket builds the tool surface; M1-063
  wires it into the live message path.
- **No chat_session/chat_message writes.** Session persistence is M1-063.
- **No LLM output sanitizer changes.** The existing sanitizer (M1-037/M1-040)
  is called by the prompt builder; it is not modified here.
- **No auto-compress.** M1-064.
- **No mutating tools — ever.** The spec's "Never exposed (forever)" list is
  structural; the registry test pins this.

## Notes

- The five tool implementations will need DB access to existing tables:
  `post` + `post_tag` (searchPosts, getPost), `post_reference` if it exists
  (getReferences — may return empty until post_reference lands in v2),
  `chat_memory` (recallMemory), `saved_post` (listSaves).
- `getReferences` reads `post_reference` which does not exist in any migration
  yet (v2-deferred). The tool should return an empty list gracefully when the
  table is absent or has no rows for the given UID — the spec says "Edges from
  the `post_reference` graph" and a graph with no edges returns nothing.
- The `CHAT_AGENT` ModelTask already exists in the enum (verified at
  `ModelTask.java` line 28 — not modified by this ticket, listed in
  out_of_scope).
- The structured refusal marker token lives in design notes
  (`docs/design/04-security.md`).
- Adjacent pattern: `LlmOutputSanitizer` for the outbound regex pass that
  the prompt builder chains on chat-agent replies.
- Relevant design: `docs/design/04-security.md` §4.3 (chat agent defenses).
