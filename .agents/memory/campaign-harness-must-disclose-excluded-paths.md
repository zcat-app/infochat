---
name: campaign-harness-must-disclose-excluded-paths
description: Post-mortem rules from the 2026-08-16 query-plane discovery — campaigns replacing production paths must enumerate exclusions; unfilled verdict forms are open decisions that block
metadata:
  type: process
---

Two rules from the 2026-08-16 discovery that production's query-anchor
translation (M1-746, shipped, redteamed) was never exercised end-to-end
while two campaigns measured adjacent surfaces:

1. **A measurement campaign that replaces a production path with a
   harness stand-in must enumerate exactly which production paths were
   excluded, and a ticket must exist for validating each exclusion.**
   The M1-844 direct-chat-e2e campaign swapped pgvector cosine for a
   lexical stand-in (disclosed) but silently omitted the M1-746
   QueryAnchorTranslator — so "Russian query → 0 results" was recorded
   as production-shaped when production would have anchored the query
   to English first. The analysis never walked the production turn path
   (`ChatAgent.doHandle` → `SemanticSearchTool.execute`) end-to-end; it
   took the premise "retrieval happens" as an axiom. A ten-minute code
   walk at analysis time beats a post-merge disclosure sentence.
2. **A measurement record with an unfilled verdict form is an OPEN
   DECISION that blocks the campaign it feeds — not a closed file.**
   The embedder campaign's `languages_cleared_to_enable: [<FILL: …>]`
   was left unfilled in a gitignored .bench file no gate reads; the
   query-leg translation it gated was measured (TRANSLATOR-MEASUREMENT
   §4) and implemented (M1-746), but the "English pivot" decision it
   was bought for was never cashed into spec language. Evidence without
   a forced decision step evaporates.

Durable corollary: prior art for ANY new analysis includes the .bench
campaign index — grep .bench/*/RESULTS*.md and DECISIONS.md for the
surface before writing the brief; measurements that already exist are
cheaper than analyses that re-derive them.

Related: [[reviewer-is-conformance-not-correctness]], [[doc-edit-tiering-and-spec-layering]]
