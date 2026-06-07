---
id: M1-217
title: "LLM-adapter lows: Entry nullability contradiction, joinPath/preview dedup, Anthropic multi-block content"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: [M1-192]
files_budget: 8
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - per-task config wiring, remote-llm model keys, Retry-After machinery, local-only guard — all M1-192's (this ticket is blocked on it; same impl files)
  - the router's unknown-default fallback posture (audit L3, deliberate documented M1-042 constraint) — recorded as a drop in the batch summary, not revisited here
  - provider-name case normalization between guard and router (audit L10, PARTIAL) — a case mismatch lands in the documented loud-fallback WARN naming both the configured value and the registered set; fixing case-folding alone without revisiting that posture is churn — dropped, recorded in the batch summary
  - LlmProvider/EmbeddingProvider SPI shapes and the routing priority order
acceptance:
  - "LlmRouter.Entry's supportedLanguages contract is internally consistent: the record component's nullability annotation, the compact constructor's null→empty normalization, and the router's language-capability read agree — the dead null check and the comment claiming both \"@Nullable\" and \"null→empty normalization\" are gone, and a named test pins that constructing an Entry with null supportedLanguages yields the empty-set behavior (today the component is annotated @Nullable at :332 while the compact ctor at :337-339 makes a null accessor result impossible, so the router's :168 null check is dead)"
  - "joinPath and preview each exist exactly once in the module's main sources: the three identical private copies (AnthropicProvider, OpenAiCompatibleProvider, OpenAiCompatibleEmbeddingProvider) collapse to one shared implementation with named tests pinning path joining (with/without trailing slash on the base) and the preview truncation cap (the cap is the log-leak bound for error-path body previews); the stale per-copy justification comments go with them"
  - "AnthropicProvider tolerates a multi-block content[] response: a response whose first content block is not a text block, or whose text spans multiple blocks, still yields the text instead of throwing — named test; the exact policy (first text-typed block vs concatenation) is the implementer's call, argued in the commit message (today parseContentText reads content.get(0).path(\"text\") only; low/theoretical for v1 call shapes per the audit's calibration)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
decision_refs:
  - D32
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-217: LLM-adapter lows

## Context

Three llm-adapter members of the audit's misc-lows bucket (unified L6,
L7, L8 — `deep-code-review/v2/UNIFIED.md` §2), re-grounded 2026-06-07
against main (M1-192 is in flight in a worktree on the same impl
files — hence blocked_by; re-ground against its diff when it lands):

1. **L6 (low).** Entry declares `@Nullable Set<String>
   supportedLanguages` while its compact constructor normalizes null
   to `Set.of()` — mutually exclusive contracts; the router carries a
   dead null check and a comment asserting both at once.
2. **L7 (low, simplification).** joinPath ×3 and preview ×3 across the
   three provider impls; LlmHttpSupport already exists in-package as
   the shared-helper home. The audit adjudicated mimo's "conscious
   trade-off, no finding" as wrong — the stale justification comments
   predate LlmHttpSupport.
3. **L8 (low, was med).** parseContentText reads content[0] only — a
   thinking-block-first or multi-text-block response throws or
   truncates. Theoretical for v1 call shapes (no thinking blocks
   requested); calibrated low, Tier-A binding.

The preview-cap test in the L7 leg doubles as the guard for the
audit's body-preview log-leak observation (gpt S2) — previews of
error bodies stay bounded.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T33 under `deep-code-review/v2/` (llm
  members; opus-48 llm F4/F9, opus-47 llm F4).
- blocked_by M1-192: its worktree (in flight at draft time) rewrites
  configFor and properties across the same three impl files; this
  ticket rebases on its landed diff, and the L7 consolidation must not
  collide with M1-192's dynamic-config edits.

## Suggested direction (unverified hypothesis)

The audit (opus-48 llm F9) suggested consolidating joinPath/preview
into LlmHttpSupport, the existing package-private static-helper class.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
