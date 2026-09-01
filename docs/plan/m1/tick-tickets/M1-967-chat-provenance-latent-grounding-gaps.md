---
id: M1-967
title: "Grounding notice for saved posts and memory; defer history"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction:
  to-be-written — ChatAgentProvenanceTest.savesGroundedReplyMustNotClaimGeneralKnowledge
  (write and run RED at /tick start, workflow §0). The wrong behavior it
  states: a turn whose ONLY grounding is a model-initiated listSaves
  Success (the tool returns full feed-post entries — uid, snapshot_title,
  snapshot_url from saved_post, ListSavesTool.java:88-154) and whose
  reply enumerates the user's saved posts ships the general-knowledge
  notice "Not based on your feed posts; answered from general knowledge."
  — both clauses mislead (the reply IS based on the user's own feed-post
  copies and did NOT come from general knowledge). Latent (verified by
  code read; not observed live): the same drive shape exists today for
  recallMemory (a reply built from conversation notes also claims general
  knowledge). Child of the 2+ decomposition; analysis:
  docs/plan/m1/tick-analysis/chat-provenance-notice-contradiction-and-ladder-priority.md.
analysis_ref: docs/plan/m1/tick-analysis/chat-provenance-notice-contradiction-and-ladder-priority.md
blocked_by:
  - M1-965
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    POST_CORPUS_TOOLS membership: listSaves and recallMemory stay
    excluded from the feed-grounding count. A saved_post row can outlive
    its subscription (ListSavesTool's SQL has no subscription predicate),
    so counting saves as "posts from your subscribed feed" would trade a
    false denial for a false claim. Verified verdict: the EXCLUSION
    survives; the WORDING does not.
  - >-
    Any listSaves/recallMemory tool change (SQL, output shape, security.md
    tool-table rows) and any new tool — the fix is the NOTICE selection
    only.
  - >-
    Counting posts quoted only in KEPT history (latent gap 2, disposed in
    §Census as defer): the turn-scoped count is specced (commands.md
    §Chat mode), the fix would need URL-matching of model-authored prose
    — a new, determinism-sensitive surface with no live evidence (the
    M1-927 deferred-observation precedent).
  - >-
    The ladder and the dropped-block notice rule (siblings M1-965/M1-966);
    the clarify null-notice rule; the degrade null-notice paths.
acceptance:
  - "REPRODUCTION closed: ChatAgentProvenanceTest.savesGroundedReplyMustNotClaimGeneralKnowledge (test_plan.adds) passes — a listSaves Success with saved-post entries and an otherwise-empty admitted feed set asserts the notice is the new saved-state bundle wording, never CHAT_PROVENANCE_GENERAL_KNOWLEDGE and never a grounded count."
  - "NEW BUNDLE KEYS (P13): reply.chat.provenance.saved_posts and reply.chat.provenance.conversation_memory are declared in BundleKeys and present in ALL five bundles (en/cs/es/ru/tr, D43 bilateral keyset rule); BundleLoaderTest keyset parity passes. The wordings claim their own source only (e.g. en drafts: \"Based on your saved posts, not a feed search.\" / \"Based on your conversation notes, not a feed search.\") — count-free, no feed-derived text interpolated (D31)."
  - "MEMORY ARM (P14, failure-mode): a drive passes — a recallMemory Success grounding an otherwise-ungrounded turn carries the conversation-memory wording, never the general-knowledge claim; ChatAgentProvenanceTest.memoryToolResultsDoNotGroundTheTurn is amended per test_plan.modifies to expect the memory wording (the tool stays excluded from the grounded COUNT)."
  - "FEED GROUNDING PRECEDES (P14, failure-mode): a drive passes — when the admitted feed set is non-empty (pre-fetch kept or post-corpus loop Success), the notice is the grounded count regardless of any saves/memory Success in the same turn; saves/memory never enter that count."
  - "SELECTION PRECEDENCE: when both a saves and a memory Success folded into one ungrounded-feed turn, one deterministic choice applies (saved_posts wording) — asserted by a drive; the selection is pure Java over the folded tool outcomes (llm.md §Determinism boundary)."
  - "SPEC AMENDMENT rides the diff (engineering-rules §12 — exact wording approved by the user at implementation; rule-text draft in Approach): docs/spec/commands.md §Chat mode's provenance paragraph gains the user-scoped-state notice state — a reply grounded only in the user's own saved posts or conversation notes carries a notice naming that source, not a feed search; the grounded-count and not-grounded wordings are unchanged. Probe: grep -n 'conversation notes' docs/spec/commands.md returns the §Chat mode notice sentence."
  - "DESIGN RECORD: docs/design/05-llm-and-embeddings.md §5.4.6's D58 notice section records the state and its precedence (feed grounding > saved posts > conversation notes > general knowledge), and keeps the exclusion line truthful (user-scoped state is still not feed grounding); git diff --stat docs/ shows exactly docs/spec/commands.md and docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
      — savesGroundedReplyMustNotClaimGeneralKnowledge (the reproduction),
      the memory-arm drive, the feed-grounding-precedes drive, and the
      both-fired precedence drive (acceptance items 1, 3, 4, 5); the rig's
      bundle stub serves the two new keys.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
      — AUTHORIZED (§8): memoryToolResultsDoNotGroundTheTurn's expected
      notice moves from "general-knowledge" to the conversation-memory
      wording; its failure message keeps stating the tool is excluded
      from the post-corpus count. This ticket changes the spec'd notice
      selection for user-state-grounded turns (acceptance item 6), which
      requires updating exactly this pinned expectation.
  preserves:
    - all tests currently green on main, explicitly the grounded-count,
      empty-retrieval, breaker-open, duplicate-uid and null-notice pins
      (no feed-grounded or degrade path changes), and
      InboundRouterChatModeIT's empty-retrieval outbound pin
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D58
  - D43
  - D31
---

# M1-967: Grounding notice for saved posts and memory; defer history

## Context

The 2026-08-31 live incident (see the analysis doc and M1-965) exposed
the notice-truthfulness defect class. Triage found two more members
(not firing in the observed turn): (1) a reply grounded in the user's
SAVED posts — listSaves returns full feed-post entries (uid,
snapshot_title, snapshot_url from `saved_post`, ListSavesTool.java:88-154)
— ships "Not based on your feed posts; answered from general
knowledge.", false in both clauses; (2) the identical shape for
recallMemory (a reply built from compressed conversation notes also
claims general knowledge). The spec's own commitment ("the user can
always tell a 'found nothing' answer from a 'didn't look' answer",
commands.md §Chat mode) demands the notice name the source the reply
actually used. Analysis: `analysis_ref:`.

## Root cause

The notice selection (ChatAgent.java:877-888) recognizes exactly two
grounding outcomes — feed grounding (the admitted post-corpus uid set)
and everything-else (general knowledge). `POST_CORPUS_TOOLS`
(ChatAgent.java:1418-1419) deliberately excludes recallMemory/listSaves
("user-scoped state, not feed grounding", design 05 §5.4.6 L802-804 —
verified verdict: the exclusion survives, because a saved_post row can
outlive its subscription and must not be claimed as "from your
subscribed feed"), but the exclusion currently drops those turns onto
the general-knowledge WORDING, which asserts a source ("answered from
general knowledge") the reply did not use. The tool outcomes needed to
distinguish this are already folded through the loop (Success results,
ChatAgent.java:1160-1191) — only the notice selection and its bundle
vocabulary lack the third state.

## Pitfalls

Carries P13-P16 and P17 of the analysis.

- P13: bundle keyset discipline — the two new keys must ship in ALL
  five bundles (en/cs/es/ru/tr, D43; the existing provenance keys are in
  all five), count-free, no feed-derived interpolation (D31), bundle
  path in the scope language, never the translator.
- P14: scope creep into tool semantics — the fix is the NOTICE; the
  tools, POST_CORPUS_TOOLS, and the security.md tool table are
  untouched; saves/memory never enter the grounded count (the feed
  claim stays feed-only).
- P15: gap-2 gold-plating — do not implement history-quoted-post
  counting (deferred, §Census); the turn-scoped count is specced.
- P16: no prod identifiers in committed artifacts.
- P17: same module as M1-965/M1-966; blocked by M1-965 because the new
  state slots into the notice decision M1-965 reshapes and both touch
  ChatAgentProvenanceTest.

## Approach

Derived from `spec_refs:` — commands.md §Chat mode's "always tell"
promise and its notice-state enumeration are extended by a third,
user-scoped state; llm.md §Determinism boundary keeps the selection
pure Java over folded tool outcomes.

- **Files to touch:** `files_scope`.
- **Pre-decided shapes (implementation is execution):**
  1. Track two booleans (or a small enum) in the tool loop: a
     listSaves Success folded, a recallMemory Success folded (the loop
     already switches on tool results at ChatAgent.java:1160-1191; no
     tool output is parsed for the notice — the outcome kind suffices).
  2. Notice selection after M1-965's admitted-set rule: admitted feed
     set non-empty → grounded(count); else saves fired →
     `reply.chat.provenance.saved_posts`; else memory fired →
     `reply.chat.provenance.conversation_memory`; else
     general-knowledge. Both fired → saved_posts (deterministic;
     breadth under-claim is acceptable, falsehood is not).
  3. Two bundle keys, five bundles each, en drafts in acceptance item
     2 (final wording via §12 user approval).
  4. Amend memoryToolResultsDoNotGroundTheTurn per `test_plan.modifies`.
  5. Spec + design records last.
- **Spec amendment rule-text draft (§12 — wording approved by the user
  at implementation; appended to the notice-state sentence in
  commands.md §Chat mode's provenance paragraph):** "A reply grounded
  only in the user's own saved posts or conversation notes carries a
  notice naming that source and stating it was not a feed search; such
  tools never count toward the feed-grounded notice."
- **Steps, in order:** write the reproduction RED at `/tick start`
  (after M1-965 lands) → shapes 1-2 → keys (shape 3) → authorized test
  amendment (shape 4) → spec + design (shape 5) → module test run →
  `mvn verify`.
- **Controls to preserve (§10):** the notice bundle path/count-free
  interpolation (D31/D43); the grounded count's feed-only semantics
  (POST_CORPUS_TOOLS unchanged); the degrade null-notice paths; the
  breaker-open general-knowledge path (no tools fired); no tool,
  SQL, allowlist, or security.md row changes.
- **Pitfall→mitigation:** P13→acceptance item 2 (five-bundle presence +
  keyset parity test); P14→acceptance items 3-4 (count stays feed-only)
  and the grep probe below; P15→§Census defer row; P16→scrubbed
  artifacts; P17→blocked_by + serial landing.

## Definition of done

Every acceptance item verified by its named test/probe: the
reproduction and the three drives pass; the two keys exist in all five
bundles and the keyset-parity test passes; the authorized
memory-arm amendment lands; the spec and design records land
probe-clean; `mvn verify` green from the repo root.

## Verification

- P13 → BundleLoaderTest keyset parity + the drives asserting the exact
  new BundleKeys constants; the grounded/general-knowledge wordings
  unchanged (existing pins green).
- P14 → savesGroundedReplyMustNotClaimGeneralKnowledge asserts the
  notice is NOT grounded(N) (the count-creep regression: saves/memory
  must never enter the feed count); the feed-grounding-precedes drive
  asserts a saves Success alongside feed grounding still yields
  grounded(count); probe: grep -n "POST_CORPUS_TOOLS"
  infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  returns the unchanged four-tool set.
- P15 → gap 2 appears only as the §Census defer row and an out_of_scope
  entry; no code path matches history prose (the deferred behavior must
  not ship in this ticket).
- P16 → grep of the landed test files for prod identifiers returns
  nothing.
- P17 → blocked_by M1-965 + serial landing: the module test run names
  this ticket's suites only; no sibling ticket is in flight in this
  module at start.
- acceptance items 1-8 → the named drives, the grep and diff-stat
  probes, `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: POST_CORPUS_TOOLS membership and any tool/SQL/
security.md change; history-quoted-post counting (deferred, §Census);
the ladder and dropped-block rule (M1-965/M1-966); the clarify rule and
degrade paths. This ticket modifies ONE pre-existing test (§8
authorization in `test_plan.modifies`):
ChatAgentProvenanceTest.memoryToolResultsDoNotGroundTheTurn's expected
wording. Any other pre-existing assertion that conflicts is a
start-hurdle escalation, not a silent edit.

## Census

The defect class is "the provenance notice asserts a source the reply
did not use, or denies one it did" — a semantic class at one decision
site (the notice selection, ChatAgent.java:877-888) plus the tool
outcome kinds feeding it; enumerated by reading that site and the tool
set (`grep -n "POST_CORPUS_TOOLS\|CHAT_PROVENANCE"
infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java`).
Every member disposed:

| Member | Disposition |
|---|---|
| Dropped pre-fetch block + loop re-grounding (live 2026-08-31) | M1-965 (fix) |
| Truncated-to-zero fold-back over-claim | already fixed by M1-923 (collect-after-fit, ChatAgent.java:1188-1190 — verified) |
| listSaves Success folded, reply enumerates saved posts | this ticket (fix) |
| recallMemory Success folded, reply from conversation notes | this ticket (fix) |
| Posts quoted only in KEPT history | defer — turn-scoped count is specced; the fix needs prose-URL matching (new determinism-sensitive surface) and has no live evidence; the M1-927 deferred-observation precedent. Revisit on live evidence of the mislabel. |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-967-chat-provenance-latent-grounding-gaps.md
```
