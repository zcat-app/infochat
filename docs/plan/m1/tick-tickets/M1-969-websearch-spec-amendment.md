---
id: M1-969
title: "Amend security.md: the bounded web-grounding lane (amendment-first)"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Probe form (pure spec amendment; no failing behavior test exists —
  the spec itself is the artifact under change, and the lane's code is
  M1-970..972 behind this ticket). Today's wrong posture,
  probe-verified on this checkout 2026-09-01: (1) grep -n 'web' over
  the tool-allowlist markers and the typed-structured-value paragraph
  docs/spec/security.md:324-356 returns NO match — the spec nowhere
  authorizes any web-grounding content class; (2) the K1 sentence at
  security.md:339-343 promises "Every output is a typed structured
  value, never a passthrough of free-form upstream text outside the
  post body / saved snapshot already vetted by the ingest pipeline" —
  read together with the lane's need (vendor snippets ARE that excluded
  class), the spec as written makes the user-approved capability
  unspec-compliant: a genuine promise change, resolved amendment-first
  (analysis §SPEC-GAP). Intended tests this ticket precedes
  (to-be-written, land with their siblings): M1-970's
  BraveSearchClientTest family — this ticket's acceptance is probe-
  based over the amended text.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: [M1-968]
spec_amend_for: docs/spec/security.md §Prompt-injection defenses
spec_amend_parent: M1-970
files_scope:
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The "Never exposed (forever)" list (security.md:352-356) — NOT
    touched, not weakened, not re-worded (BINDING user decision). The
    first increment violates nothing on it: one code-pinned vendor host fetched by the
    dispatch layer is not "a tool fetching arbitrary URLs", and nothing
    in the model's tool surface fetches at all until the separately
    decided T1.
  - >-
    llm.md §Determinism boundary — NOT amended. "The LLM is not allowed
    to … fetch URLs" (llm.md:475-477) stays true in the deterministic-arm increment: the fetch is
    deterministic Java on a KB-miss, never an LLM election. Reopening
    this sentence belongs to T1 (the model-elected arm), a separate
    later decision recorded in the analysis.
  - >-
    ANY production code, and ANY commands.md edit — the chat-behavior
    record (trigger rule, compositional notice, ladder position) rides
    M1-972's diff per the M1-932/M1-940 rides-the-diff precedent; this
    ticket carries ONLY the security.md promise change plus the design
    -05 note. Two tickets amending the same spec section fuse (the
    M1-779 lesson) — hence one security.md diff here, one commands.md
    diff there.
  - >-
    The weather lane's spec posture — getWeather's tool row is
    K1-compliant typed output and rides M1-973's diff (the M1-931
    precedent); it does not wait on this ticket and this ticket does
    not carry it.
  - >-
    Vendor-specific facts in spec prose — no endpoint paths, no
    pricing, no vendor names in rule text where a class suffices (the
    pinned-host identity lives in design notes/config per the
    "values in design notes" convention); no dates, ticket IDs, or
    report citations anywhere in the amended prose (engineering-rules
    §12).
acceptance:
  - "REPRODUCTION closed (the authorization half): the typed-structured-value paragraph at security.md:339-343 is extended by ONE enumerated exception clause and ONE following authorization paragraph under §Prompt-injection defenses, together stating ALL of: (a) the exception is a single web-grounding content class — vendor-returned snippets and their result titles/URLs/page-age metadata only, never fetched page bodies; (b) it is emitted by the deterministic dispatch layer — never by a model-elected tool — on the deterministic fire condition stated BOTH ways: a corpus-miss, OR a typed-structured tool's degraded/no-data outcome (the fallback ladder; a typed tool never parses snippets into typed values, so the class stays enumeration-bounded); (c) it reaches the model context ONLY inside the same per-call-random-marker untrusted-content wrapper every other untrusted block rides; (d) it is bounded per entry and in aggregate by fixed byte caps with whole-entry drop; (e) it is budget-gated (a deployment-wide monthly query budget with an operator kill-switch and a per-scope opt-out); (f) its egress is enumerated (query text derived from the current chat message only, plus the scope's declared language — no identifiers, no history, no other context); (g) the user-facing provenance notice discloses it count-only; (h) the fallback ladder's terminal rules — fallback calls share the monthly budget under a documented sub-cap (a hard-down primary source must not drain the credit), and when the lane itself fails or its budget is exhausted the fired turn ends at an honest, bundle-localized refusal: no runtime vendor fallback exists, and a vendor swap is an operator configuration change only. Probe: grep -n 'snippet' docs/spec/security.md returns hits inside §Prompt-injection defenses; grep -c 'sub-cap' docs/spec/security.md returns at least 1 (the (h) bounds land in the amended prose); the paragraph's non-negotiable frame ('typed structured value') survives verbatim."
  - "§SSRF inventory: the Provider's outbound enumeration (security.md:198-209) gains the lane — one operator-configured, code-pinned external search-API host reached by read-only GET through the same fail-closed gate, its request limited to the query text and locale parameters and one operator-held credential; result-URL fetching is explicitly denied. Probe: the amended §SSRF paragraph names the pinned-host class and the no-result-fetching rule."
  - "§Rate limiting entry: a web-grounding bullet joins the bucket list (security.md:1895+) stating: one web query per chat turn at most, fired only on the deterministic fire condition — a corpus-miss OR a typed-structured tool's degraded/no-data outcome, both stated (analysis P20) — and only when the chat endpoint's breaker is CLOSED; a deployment-wide monthly query budget (profile-driven value in design notes) with a documented fallback sub-cap shared by fallback-triggered calls (a hard-down primary source must not drain the credit), whose exhaustion — like the lane's own failure — ends the fired turn at the honest-refusal rung rather than silently pretending no lane exists, while a disabled or opted-out lane is the feature OFF and such turns keep today's behavior; the dual-query English arm for non-English scopes is one ModelTask.TRANSLATOR call under the same query-anchoring conditions as the corpus legs (extending the existing query-anchoring bullet, security.md:1921-1943, with the new call site). Probe: grep -n 'query-anchoring\\|web-grounding' docs/spec/security.md returns both the extended bullet and the new entry."
  - "§Secrets handling: the egress-inventory enumeration (security.md:2197+, the authority the operator disclosure texts sync against, :2318-2324 'a new call site is a new leg') gains the web-lane entry: for any scope, a corpus-miss turn sends the user's chat-message-derived query text (truncated, not redacted) and the scope's declared language to the pinned external search host under one shared operator credential — users are indistinguishable behind it; the search API key is read from an environment variable; the entry names the per-scope opt-out and the operator kill-switch as the privacy-conservative valve, and acknowledges the personal-context residual (a corpus-miss on a personal-context turn egresses that turn's question text; the query never carries identifiers, history, or memory). Probe: the new entry carries all of shared-key, env-var, opt-out/kill-switch, and the acknowledged residual."
  - "§Failure handling: the chat-mode bullet (security.md:1707-1742) records that a doomed turn may already have spent one budgeted web query before the failure surfaced (the M1-589 precedent applied to the new lane) and that the lane is skipped entirely once the breaker is OPEN. Probe: the amended bullet names the bounded pre-call cost class."
  - "RULE-TEXT ONLY (engineering-rules §12): the amended prose contains no dates, ticket IDs, decision-report citations, or vendor product names where a class suffices — probe: git diff over docs/spec/security.md shows added lines matching none of 'M1-\\|20[0-9][0-9]-\\|redteam'; the exact wording goes to the user for approval at implementation time and the ticket records that gate."
  - "Design-05 record: docs/design/05-llm-and-embeddings.md §5.4.6 records the lane's posture (deterministic corpus-miss trigger, snippet-only class, wrapper discipline, budget guard, the k=60 / 2:1 fusion constants' no-config-knob rationale) — probe: grep -n 'web-grounding\\|web grounding' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "mvn verify from the repo root is green (engineering-rules §5) — docs-only diff; every parity test that parses security.md (ChatToolAllowlistSpecParityTest reads the marker-delimited table, which this ticket does NOT enter) stays green UNMODIFIED: probe: git diff shows no hunk inside the tool-allowlist markers."
test_plan:
  adds:
    - >-
      None (pure spec + design amendment; the probes above are the
      verification). The lane's behavior tests are M1-970..972's
      test_plan.adds, each of which names this ticket as its spec
      basis.
  preserves:
    - >-
      all tests currently green on main — explicitly
      ChatToolAllowlistSpecParityTest (the tool table is untouched;
      the closed allowlist stays eight rows in this ticket).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §Failure handling
decision_refs:
  - D20
  - D21
  - D28
  - D43
---

# M1-969: Amend security.md — the bounded web-grounding lane (amendment-first)

## Context

The user-approved web-grounding lane cannot ship on the current spec:
K1 (`security.md:339-343`) promises grounding content is a typed
structured value, "never a passthrough of free-form upstream text
outside the post body / saved snapshot already vetted by the ingest
pipeline" — and vendor snippets are exactly the excluded class. This is
a genuine promise change (analysis §SPEC-GAP), and the user has already
chosen the resolution: an additive, bounded, enumerated exception; the
"Never exposed (forever)" list untouched; rule-text only; exact wording
approved at implementation. This ticket IS that amendment, landing
FIRST (`spec:`-prefixed work); M1-970/971/972 are blocked on it. Its
wording is gated on M1-968's evidence (the language rule and the budget
default are pinned from measurement, not free variables). Shared
analysis: `analysis_ref:` (this ticket carries P1, P4, P5, P8).

## Root cause

Not a code defect. Verified: no spec text authorizes any
web-grounding content class (grep 'web' over
`security.md:324-356` returns nothing); the K1 sentence's exclusion
class is precisely the lane's content; the Provider's §SSRF outbound
enumeration (`:198-209`) names only the `/add-source` probes; §Rate
limiting's query-anchoring bullet enumerates call sites exhaustively
and the web lane adds one; §Secrets handling's enumeration is the
authority operator disclosure syncs against (`:2318-2324`). Five
spec surfaces must move in ONE diff (the M1-779 fusion lesson) or the
spec under-discloses a private-user-text exposure mid-lane.

## Pitfalls

Carried from the analysis: P1 (promise change, amendment-first — this
ticket is the payment), P4 (the egress inventory must enumerate what
leaves per call site and acknowledge the personal-context residual;
opt-out + kill-switch recorded as controls IN the spec so widening them
later needs an amendment), P5 (the monthly-budget entry follows the
M1-756 precedent: a bound documented only as an exposure note could be
widened without an amendment — the wrong direction for a limit), P8
(the English arm is a new translator leg; §Rate limiting + §Secrets
gain it here, not in a sibling), P20 (the fallback ladder lives at the
agent layer — the dual fire condition, the laundering ban, the budget
sub-cap, and the honest-refusal terminal rung are all spec text HERE so
no sibling diff can weaken them piecemeal). Also §12 discipline:
rule-text only; and the M1-589 lesson: §Failure handling must record
the doomed-turn's bounded pre-call cost.

## Approach

Derived from `spec_refs:` — all five sections are the surfaces the
analysis enumerated; the amendment drafts rule text for each (acceptance
items 1-5 state the required semantic content per section; the exact
sentences ride the diff and the USER APPROVES THEM at implementation —
engineering-rules §12 gate recorded in acceptance item 6).

- **Files to touch:** `files_scope` (one spec, one design doc).
- **Pre-decided shapes (implementation is execution):**
  1. Draft each section's rule text against acceptance items 1-5 —
     the K1 exception clause is ONE sentence appended to the
     typed-structured-value paragraph, plus ONE authorization paragraph
     under §Prompt-injection defenses carrying the bounds (a)-(h);
     the other four sections get one bullet/paragraph each.
  2. The design-05 §5.4.6 note carries the constants' rationale and the
     pointer to the config surface (pinned host, budget default derived
     from M1-968's formula inputs, TTL values) — values live in design
     notes, not spec prose.
  3. On M1-968's evidence: if H3 (thin ru/tr native pools) falsifies
     the native-primary rule for a locale, the authorization paragraph's
     language rule is worded per-locale-honestly BEFORE landing (the
     wording gate is why blocked_by: M1-968).
- **Steps, in implementation order:** (1) read M1-968's operator-run
  record (local store) for the measured inputs; (2) draft the five rule
  texts + design note; (3) present the exact wording to the user for
  approval (§12 — the approval IS the gate, recorded in the commit);
  (4) land; (5) `mvn verify` (docs-only; parity tests untouched).
- **Controls to preserve (§10):** the tool-allowlist table between its
  markers is byte-identical (eight rows — the lane adds NO tool in the deterministic-arm
  increment);
  K2's forever-list is byte-identical; the existing query-anchoring
  bullet's legs all survive (the web leg is ADDED to the enumeration,
  replacing none).
- **Pitfall→mitigation:** P1→this ticket precedes M1-970 (blocked_by
  wiring); P4→item 1(f)/4's enumeration + residual; P5→item 3's
  spec-level bound; P8→item 3's leg extension; §12→item 6's probe and
  the user-approval gate.

## Definition of done

All five sections carry the user-approved rule text with the required
semantic content; the forever-list, the tool table, and llm.md are
untouched; the amended prose is number-free, date-free, and
citation-free; design-05 records the posture; every parity test passes
unmodified; `mvn verify` green from the repo root.

## Verification

- P1 → acceptance item 1's probes (the exception clause exists; the
  frame survives) + the blocked_by wiring on M1-970/971/972.
- P4 → item 4's enumeration probe (shared-key, env-var, opt-out,
  kill-switch, residual — each named).
- P5 → item 3's §Rate-limiting probe (monthly budget as a spec-level
  bound; breaker gate; the extended query-anchoring bullet).
- P8 → item 3's leg-extension probe.
- P20 → item 1's (b)/(h) probes and item 3's dual-condition + sub-cap
  + refusal-rung wording (all three terminal rules are spec text here).
- §12 → item 6's grep probes over the diff.
- FAILURE-MODE (spec-side) → item 5: the amended §Failure-handling
  bullet is tested for honesty by M1-972's breaker-open drive (a
  doomed turn asserts at most one web call, per the amended promise).
- acceptance items 7-8 → the design-doc grep; mvn verify.

## Out-of-scope

Named in `out_of_scope`: the forever-list; llm.md §Determinism
boundary; any production code; any commands.md edit (rides M1-972);
the weather row (rides M1-973); vendor facts and citations in spec
prose. No pre-existing test is touched.

## Census

Class-scoped: the amendment touches every `docs/spec/security.md`
surface that carries an egress or capability disclosure for the chat
path — a class of disclosure sites, not one paragraph (the M1-940
census style). Re-runnable enumeration: the section list below over
`docs/spec/security.md`; every site disposed (states verified at draft
time, 2026-09-01):

- §Prompt-injection defenses, typed-structured-value paragraph
  (`:339-343`) → **FIX** (acceptance item 1's enumerated class +
  authorization paragraph).
- §SSRF and outbound connections, Provider outbound enumeration
  (`:198-209`) → **FIX** (item 2).
- §Rate limiting, bucket list + query-anchoring bullet (`:1895-1943`)
  → **FIX** (item 3).
- §Secrets handling, egress-inventory enumeration (`:2197-2334`) →
  **FIX** (item 4).
- §Failure handling, chat-mode bullet (`:1707-1742`) → **FIX**
  (item 5).
- Tool-allowlist markers (`:324-337`) → DISPOSED, byte-identical —
  the lane adds NO tool in this increment; eight rows stay (item 6's
  probe).
- "Never exposed (forever)" list (`:352-356`) → DISPOSED, untouched
  (BINDING user decision; out_of_scope).
- `docs/spec/llm.md` §Determinism boundary → DISPOSED, untouched —
  the deterministic-arm increment's fetch is deterministic Java; the
  LLM fetches nothing (the deferred T1 arm's concern, out_of_scope).
- `docs/spec/commands.md` §Chat mode → DISPOSED here; rides M1-972's
  diff (the M1-779 fusion lesson, out_of_scope).
- `docs/spec/security.md` §DB roles → DISPOSED here; rides M1-973's
  diff (the weather table's grant sentence, out_of_scope).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-969-websearch-spec-amendment.md
```
