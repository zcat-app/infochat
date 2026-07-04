---
id: M1-552
title: Chat system prompt gains a max-tokens-derived brevity hint
status: done
created: 2026-07-03
last_updated: 2026-07-04
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 119
      removed: 24
revisions:
  - date: 2026-07-03
    reason: budget-breach refine, applied by the developer at the user's
      explicit direction ("1 — apply the refine yourself as recommended",
      2026-07-03) — the ticket missed ChatAgentTest.java:412's direct
      construction of ChatPromptBuilder, which cannot compile once the
      constructor gains the mandated @ConfigProperty max-tokens parameter
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget: 3
      acceptance_item_3: "ChatPromptBuilderTest pins the rendering —
        max-tokens 600 → the system prompt contains 'under about 270 words';
        the default (1024) → 'under about 461 words' — and all existing
        prompt-shape tests pass unchanged."
      test_plan_preserves_entry: "ChatAgentTest and the chat suite untouched
        and green"
escalations:
  - date: 2026-07-03
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation budget-breach: acceptance mandates a new
      @ConfigProperty constructor parameter on ChatPromptBuilder (render-once
      in constructor), but ChatAgentTest.java:412 constructs ChatPromptBuilder
      directly (anonymous subclass, 3-arg call) and fails to compile without a
      4th argument. Touching ChatAgentTest exceeds files_budget: 3 and
      contradicts test_plan.preserves "ChatAgentTest ... untouched and green".
clarity_check:
  date: 2026-07-03
  verdict: PASS
  warnings:
    - "Procedural note, not a check finding: the clarity-prompt stub named
      the ticket path as
      docs/plan/m1/tickets/M1-552-chat-system-prompt-gains-a-max-tokens-derived-brevity-hint.md,
      which does not exist. The actual file is
      docs/plan/m1/tickets/M1-552-chat-prompt-brevity-hint.md (frontmatter
      id: M1-552, confirmed unique via Glob for M1-552*.md). This evaluation
      proceeded against the actual file since the frontmatter id matches the
      prompt's stated ticket id; worth tightening the prompt-generation
      step's slug so the stub path and the on-disk filename agree."
  blockers: []
blocked_by: []
files_budget: 4
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the summarizer prompt (it already demands one short paragraph per
    cluster — M1-548 design; no evidence it runs to its cap)
  - localization bundles — the system prompt is model-facing English, not
    a user-facing bundle string, so D43's bilateral-keyset rule does not
    apply and no cs twin exists
  - any change to the untrusted-content delimiter scheme, random per-call
    markers, or the [REFUSAL] contract (the prompt-injection defense
    surface is byte-identical)
  - sampling parameters (stop, temperature, top_p) — M1-548 already
    excluded them; unchanged here
  - wizard/config value collection (M1-550) — this ticket only consumes
    whatever infochat.llm.chat.max-tokens resolves to
acceptance:
  - ChatPromptBuilder.CHAT_SYSTEM_PROMPT becomes a template with a single
    integer placeholder, rendered ONCE in the constructor; the added
    sentence is "Keep replies under about <N> words unless the user
    explicitly asks for more detail." placed after the tools/history
    sentence. All other prompt text is byte-identical to today's.
  - The word target derives from
    @ConfigProperty infochat.llm.chat.max-tokens (defaultValue "1024" — a
    comment records that this mirrors the provider-side orElse(1024) from
    M1-548 and the two defaults must not drift) as
    wordTarget = max(50, round(maxTokens × 0.45)) — i.e. 600 → 270,
    1024 → 461. The 0.45 factor (≈0.75 words/token × ~60% headroom) exists
    so typical replies finish with finish_reason=stop below the hard cap
    instead of truncating mid-sentence at it (F-live-6 follow-up, live s12
    evidence: decode ran to exactly the 600-token cap).
  - ChatPromptBuilderTest pins the rendering — max-tokens 600 → the system
    prompt contains "under about 270 words"; the default (1024) → "under
    about 461 words" — and all existing prompt-shape assertions pass
    unchanged. Direct-construction call sites of ChatPromptBuilder
    (ChatPromptBuilderTest helpers and ChatAgentTest.buildAgent) gain only
    the new constructor argument, passing 1024 (the default) so their
    observed behavior is identical.
  - docs/design/05-llm-and-embeddings.md's max-tokens paragraph gains a
    sentence noting the chat system prompt derives its brevity hint
    (~45% of the cap, in words) from this key, so operators sizing the cap
    know the prompt follows automatically.
  - mvn verify is green.
test_plan:
  adds:
    - "ChatPromptBuilderTest: max-tokens 600 renders 'under about 270
      words' into the system prompt"
    - "ChatPromptBuilderTest: default max-tokens (1024) renders 'under
      about 461 words'"
  preserves:
    - all existing ChatPromptBuilderTest tests (untrusted-content wrapper
      shape, memory/history blocks, marker randomness)
    - ChatAgentTest assertions unchanged and green — its buildAgent factory
      gains only the new constructor argument (1024); the rest of the chat
      suite untouched and green
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs: []
---

## Context

Live s12 evidence (2026-07-03): with the M1-548 cap at 600 tokens, the chat
reply decoded to EXACTLY the cap (`finish_reason=length`) — the model was
cut off mid-generation after ~143 s of decode. The system prompt
(`ChatPromptBuilder.CHAT_SYSTEM_PROMPT`) contains no length instruction of
any kind, so on a slow host every substantive chat reply runs to the hard
cap: maximum latency AND a truncated tail, every time.

A brevity instruction makes typical replies finish naturally well under the
cap (faster and un-truncated), demoting `max-tokens` to what M1-548 designed
it to be — a safety net against runaway generation, not the everyday reply
length.

## Design (settled with user 2026-07-03)

- **Derive, don't add a knob.** The right target is always "comfortably
  under the hard cap"; it is a function of `infochat.llm.chat.max-tokens`,
  not an independent operator preference. Deriving keeps the sizing
  invariant in one place and means the M1-550 wizard value automatically
  sizes the prompt — no extra wizard question, no way to configure the hint
  above the cap.
- **The placeholder is an app-derived integer, never operator-authored
  text.** No config prose reaches the system prompt; the prompt stays
  deterministic per deployment and the injection-defense surface is
  untouched.
- **Rendered once at construction** (the builder already injects
  `infochat.context-window` in its constructor) — zero per-call cost; the
  per-call random untrusted-content markers stay exactly where they are.
- An operator who wants longer/shorter prose tunes `max-tokens` — the
  honest lever, since it also governs the latency and timeout geometry.

## Implementation anchors (surveyed 2026-07-03)

- `infochat-provider/.../chat/ChatPromptBuilder.java`: `CHAT_SYSTEM_PROMPT`
  constant (l.28–43 — becomes the template), constructor with the existing
  `@ConfigProperty` injection to extend (l.49–53), `BuiltPrompt` return
  (l.113 — switches from the constant to the pre-rendered field).
- `infochat-provider/src/test/.../chat/ChatPromptBuilderTest.java` — the
  builder is directly constructible in tests; new cases construct with 600
  and with the default.
- `docs/design/05-llm-and-embeddings.md` ~l.113–119 — the M1-548
  max-tokens paragraph the new sentence joins.

## Not security_relevant — justification

The prompt-injection defense surface (untrusted-content delimiters, random
per-call markers, refusal contract, spec §Prompt-injection-aware prompt
shape) is byte-identical. The diff adds one static English sentence plus an
integer computed from an already-validated positive config value; no user,
operator, or post content gains a new path into the prompt.
