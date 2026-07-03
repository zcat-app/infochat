---
id: M1-550
title: Wizard step 4 writes profile-sized LLM timeout-ms + max-tokens (F-live-5)
status: done
created: 2026-07-03
last_updated: 2026-07-03
reviews:
  - round: 1
    date: 2026-07-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 83
      removed: 8
clarity_check:
  date: 2026-07-03
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - eval-task timeout sizing (security/tagger/entity/translator) — the
    F-live-5 cancel-storm evidence is contention-bound (30 s eval calls
    dying while prose calls hog the slots); whether longer eval timeouts
    help or worsen contention is unproven. Follow-up when evidence exists.
  - changing the in-app defaults (timeout-ms orElse 30000, max-tokens
    orElse 1024 stay — the wizard writes visible overrides, the code
    default is the safety net)
  - infochat.embeddings.* timeout keys (embeddings calls are sub-second
    even on the pi; no finding implicates them)
  - retrofitting values onto existing deployments via upgrade.sh (the
    live host already carries hand-set values; a fresh run of step 4 is
    the retrofit path)
  - ChatPromptBuilder brevity hint (M1-552) and the OutboxRehydrator
    readiness gate (M1-551) — separate tickets
acceptance:
  - After the backend branch completes, 4-llm.sh prompts (read -rp, default
    shown in brackets, empty answer takes it — the existing backend-prompt
    style) for infochat.llm.chat.timeout-ms, infochat.llm.chat.max-tokens,
    infochat.llm.summarizer.timeout-ms, infochat.llm.summarizer.max-tokens,
    with recommended defaults per the §Recommended values table (keyed on
    backend local-vs-remote, then profile for local); --defaults skips the
    prompts and writes the recommendations unchanged.
  - Each answer is validated as a positive integer at the prompt boundary
    (FAIL naming the offending key otherwise) — same system-boundary
    posture as the existing backend validation.
  - All four keys are written via the existing set_prop helper, so a
    resumed or re-run step replaces rather than duplicates the lines.
  - docs/design/05-llm-and-embeddings.md documents
    infochat.llm.<task>.timeout-ms (optional, default 30000) next to the
    existing max-tokens paragraph, stating the sizing invariant
    (cap × per-token decode time + prefill < timeout-ms) once for the pair.
  - docs/design/07-deployment.md step-4 wizard table row mentions that the
    step also collects the chat/summarizer timing keys with profile-sized
    recommendations.
  - mvn verify is green.
test_plan:
  adds: []
  # prod/ wizard tooling has no automated test harness (M1-536 / M1-549
  # precedent). The prompt/validation/set_prop logic is line-reviewable
  # shell; behavior rides the existing wizard conventions.
  preserves:
    - all tests currently green on main (no src/main or src/test change)
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
  - D27
---

## Context

**F-live-5 (MEDIUM, config — live 4b-3 run 2026-07-03):** both LLM providers
default `infochat.llm.<task>.timeout-ms` to 30000 when unset; only `security`
sets it explicitly. On the 4-vCPU llama.cpp `vps` host, prose tasks need
60–300 s, so every chat/summarizer call died in `HttpTimeoutException` before
M1's first live prose completed — and the doomed calls produced a cancel
storm on the shared llama.cpp server. The host fix (kept):
`infochat.llm.chat.timeout-ms=240000` + summarizer twin, hand-edited into
`prod/runtime/application.properties`; M1-548 later added the coupled
`max-tokens` keys (chat=600, summarizer=400) the same way.

Decision (user, 2026-07-03): the wizard collects these values interactively
with recommended defaults, rather than raising the `%vps` in-app profile
defaults. Rationale: the values are host-speed-dependent (the same `vps`
profile spans very different CPUs), and a wizard-written line in
`prod/runtime/application.properties` is visible and editable where a
baked profile default is invisible; the operator prompt also teaches the
knob exists.

## Design (settled with user 2026-07-03)

- **Timeout and max-tokens are collected as a pair** — the sizing invariant
  (`cap × per-token decode time + prefill < timeout-ms`, see the M1-548
  design) couples them; configuring one without the other re-creates the
  F-live-6 geometry.
- **Recommendation keyed on backend first, profile second.** A remote
  OpenAI-compatible API answers prose in seconds regardless of profile — a
  240 s recommendation there would hide real outages for 4 minutes. Local
  backends (ollama, llamacpp) get profile-sized slow-host values.
- **Recommended values:**

  | backend | profile | chat timeout/max-tokens | summarizer timeout/max-tokens |
  |---|---|---|---|
  | local (ollama, llamacpp) | laptop | 240000 / 600 | 240000 / 400 |
  | local (ollama, llamacpp) | vps | 240000 / 600 | 240000 / 400 |
  | local (ollama, llamacpp) | pi | 480000 / 400 | 480000 / 300 |
  | remote | any | 60000 / 1024 | 60000 / 1024 |

  The vps row is host-proven (2026-07-03 live run: 600-token cap fired at
  143 s decode + 13.5 s prefill < 240 s). The pi row is provisional, derived
  from the invariant assuming ~1 tok/s decode; the laptop row assumes
  vps-class CPU. All are prompt defaults the operator can override inline.
- **Only chat + summarizer.** They are the two prose tasks with documented
  live evidence; eval tasks are out of scope (see frontmatter).

## Implementation anchors (surveyed 2026-07-03)

- `prod/scripts/4-llm.sh`: `set_prop` helper (~l.91–98), profile read
  (~l.210), backend prompt + closed-set validation (~l.228–238 — the
  read -rp style to mirror), `LLM_TASKS` list (~l.71). The new prompts slot
  after the backend `case` completes (all branches converge), before the
  model-key writes' final summary.
- `docs/design/05-llm-and-embeddings.md` ~l.114–119: the M1-548 max-tokens
  paragraph the timeout-ms doc joins (the key is undocumented today —
  M1-548 out_of_scope explicitly deferred it here).
- `docs/design/07-deployment.md` ~l.683: the step-4 wizard table row.

## Not security_relevant — justification

No trust-boundary change: the step already collects operator input
interactively and validates it at the prompt boundary; the new values are
positive integers written to the same operator-owned config file. Timeout
and output-cap sizing tightens resource exhaustion if anything.
