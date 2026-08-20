---
id: M1-892
title: Signal image completion carries the prompt echo
status: pending
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  Live probe (a diff mvn verify cannot cover — the loss is on the
  signal-cli leg): on the isolated infochat-test stack, from a Signal
  admin DM, `/image -r 600x600 <safe prompt>` with the signal-cli daemon
  JSON-RPC stream captured (recipes:
  .agents/memory-local/signal-jsonrpc-capture.md; never anything named
  infochat-prod). Observed wrong output (2026-08-18, commit 4ce8aea0,
  .scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md D-3 and plan §9 SIG-IMG-07
  wire captures, 100% of Signal completions, SIG-IMG-06 corroborating):
  the Signal completion edit body is exactly `Image generated.` while
  SimpleX's completion carries `Image generated.\nPrompt used: <prompt>`
  — the adapter-neutral echo mandate (docs/spec/commands.md:642-650) is
  unmet on Signal, and a non-en scope's translated effective prompt is
  never disclosed there.
analysis_ref: docs/plan/m1/tick-analysis/small-followup-batch.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any per-adapter branch in ImageCommandHandler or the progress
    lifecycle (P3 — the echo mandate is adapter-neutral,
    commands.md:642-650; StageProgressNotifier.java:134-144's
    "callers never branch on transport", M1-607). Under the expected
    Fork A the handler diff is empty.
  - >-
    signal-cli itself, the Signal service, and any daemon-side
    workaround — if the capture indicts the daemon leg, the
    provider-side answer is the Fork A format change, not a transport
    patch.
  - >-
    The translation leg, QueryAnchorTranslator, and the
    infochat.image.translate-prompt skip flag (M1-851 territory —
    submittedPrompt computation is untouched).
  - >-
    The SimpleX attachment/preview path, the M1-566 edit-fallback work,
    and any change to the image pipeline (gates, credits, spool,
    ComfyUI client).
  - >-
    Any spec edit — commands.md:642-650 already mandates the echo
    adapter-neutrally; this ticket restores compliance, it does not
    amend.
  - >-
    ImageCommandHandler.java under Fork A (bundle-only fix). If the
    capture forces Fork B (an in-repo loss), the ticket escalates with
    the capture naming the layer rather than widening scope mid-flight
    (P1).
acceptance:
  - "CAPTURE-FIRST localization probe, executed before any fix (P1 — the M1-855 shape): the provider→daemon and daemon→client legs of a real Signal /image completion are captured on the isolated infochat-test stack and the fork decision is recorded from the frames — Fork A (the provider→daemon frame carries the full two-line body; the expected outcome per analysis Ground truth): the loss is downstream, apply the single-line bundle change; Fork B (the frame lacks the second line): ESCALATE with the capture naming the in-repo layer, no guessed fix; Fork C (both legs carry the full body): evidence-only close — the boundary pin (item 3) and the design record (item 7) land, no production change. Verify: the capture logs are attached to the ticket evidence and the recorded fork matches their content."
  - "REPRODUCTION passes post-fix: a SIG-IMG-07 re-run on the isolated test stack shows the Signal /image completion body carrying the prompt disclosure (under Fork A: the single-line `Image generated. Prompt used: <prompt>`), and a SimpleX re-run shows the same disclosure — Verify: the post-fix capture logs are attached and grep of the Signal completion frame/body shows the prompt text present; an en-scope and one non-en scope (e.g. /lang es) are both run so the translated-effective-prompt disclosure is evidenced (D-3's real information loss)."
  - "Boundary pin (P4, engineering-rules §8 assertion-adequacy — boundary siting): SignalJsonRpcClientTest.multiLineBodyThroughEditArrivesByteForByte passes — a two-line body driven through update() and finalizeHandle() arrives byte-for-byte in the encoded edit frame's `message` param (the FakeSignalCli param-inspection pattern, SignalJsonRpcClientTest.java:133-160). Green on current code by design: it pins the adapter boundary so a future truncation regression reds it; it is not the reproduction."
  - "FAILURE-MODE probe (P2, engineering-rules §10): the sanitizer posture survives any format change — the live SIG-IMG-06 re-run on Signal asserts a prompt containing a privileged-command string yields a completion whose disclosure shows `[redacted command]`, never the raw token, with exactly one LLM_OUTPUT_SANITIZED audit row and the IMAGE_GENERATE row still content-free — Verify: the live capture attached to the evidence, plus the pre-existing ImageCommandHandlerTest echo/sanitizer pins green unmodified."
  - "D43 completeness (P4): under Fork A the single-line value (`Image generated. Prompt used: {0}` and its per-locale equivalents) lands in ALL FIVE shipped bundles — Verify: BundleLoaderTest green and `git diff --stat infochat-provider/src/main/resources/bundles/` shows exactly the five files."
  - "Adapter-neutrality (P3): the diff contains no adapter-name conditional anywhere — Verify: `grep -rn -i 'signal' infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java` returns nothing (as today) and under Fork A `git diff infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java` is empty."
  - "The wire truth is recorded (P1/P4): docs/design/06-messaging.md §6.5.7 region gains the capture outcome and the landed completion format — Verify: `grep -n 'Prompt used' docs/design/06-messaging.md` hits the new note."
  - "mvn verify from repo root is green (engineering-rules §5), with every pre-existing ImageCommandHandlerTest and SignalJsonRpcClientTest case unmodified."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
    - >-
      every ImageCommandHandlerTest case (M1-851's echo, skip-flag and
      sanitizer pins) and every SignalJsonRpcClientTest case — no
      pre-existing test is modified by this ticket.
  notes:
    - >-
      The one new test method lands in the existing
      SignalJsonRpcClientTest (the FakeSignalCli harness's home), so no
      new test file is added; the live-probe arms are evidence captures,
      not mvn tests.
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/messaging.md §Progress notifications
decision_refs:
  - D43
  - D75
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

# M1-892: Signal image completion carries the prompt echo

## Context

Live E2E (2026-08-18, D-3 / SIG-IMG-07, corroborated by SIG-IMG-06): a
successful Signal `/image` ends in a completion edit whose body is exactly
`Image generated.`, while SimpleX's completion carries
`Image generated.\nPrompt used: <prompt>`. The adapter-neutral mandate
(`spec_refs:` — commands.md:642-650: the echo "is the transparency
mechanism, the failure-mode explainer, and the durable record") is
therefore unmet on Signal: invisible for en scopes (the echo equals the
user's own prompt), real information loss for non-en scopes, whose
translated effective prompt is never disclosed. Shared analysis:
`analysis_ref:`.

## Root cause

Not fully proven — and the proof boundary is the ticket's first step.
PROVEN (analysis §Ground truth, all citations verified there): the
provider composes the full echo ONCE, adapter-neutrally
(ImageCommandHandler.java:393-394), the D43 bundle key exists in all five
locales (en.properties:930 etc.), and the Signal outbound chain
(StageProgressNotifier.complete → OutboundDelivery.finalizeInPlace →
SignalAdapter.finalizeMessage → SignalJsonRpcClient.finalizeHandle →
editMessage → SignalMessageCodec.encodeEditSend) transmits the body whole
— no in-repo truncation point exists. NOT PROVEN: where the second line is
lost on the live Signal leg (signal-cli 0.14.5 edit or receive-rendering
behavior vs campaign capture artifact). The campaign's wire captures are
not in the repo, so a fresh capture localizes the layer — the M1-855
capture-first precedent. The ticket is still safe to start: every fork has
a small, pre-mapped deliverable, and the expected fork (A) is a five-file
bundle change.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4.

- P1: never guess the loss layer (the M1-800 completion-signal rule; the
  M1-855 capture-first precedent). A fix aimed at the wrong layer ships a
  non-fix that a green suite cannot catch — the provider-side echo pins
  run through the in-memory adapter, so nothing today traces the value
  across the Signal boundary. Capture first; Fork B escalates, Fork C
  closes evidence-only, neither ships a guessed change.
- P2: §10 carry-over — the sanitize call with the echo field ALONE as its
  redaction unit and the LLM_OUTPUT_SANITIZED audit row
  (ImageCommandHandler.java:390-394; the reflecting-echo rule,
  commands.md:644-648 + security.md §LLM output sanitizer) must survive
  any completion-format change; the IMAGE_GENERATE row stays content-free
  (D75). SIG-IMG-06's live privacy contract pins this.
- P3: no per-adapter branch — a Signal-only completion path violates the
  adapter-neutral mandate and the progress lifecycle's
  callers-never-branch rule (StageProgressNotifier.java:134-144, M1-607).
  The single-line bundle form is adapter-neutral BY CONSTRUCTION: SimpleX
  renders the same one line, its disclosure unchanged in content.
- P4: D43 five-locale completeness on the bundle edit (BundleLoaderTest's
  reflective gate) + end-state fixture calibration (the M1-785 lesson):
  the boundary pin asserts byte-for-byte multi-line transport, which holds
  in every fork's end state; no test pins the intermediate two-line bundle
  value (none exists today — verified: no test references
  IMAGE_REPLY_ECHO).

## Approach

Derived from `spec_refs:` — commands.md:642-650 mandates the echo's
CONTENT adapter-neutrally (not its line structure), security.md §LLM
output sanitizer fixes the redaction discipline on it, and messaging.md
§Progress notifications owns the edit lifecycle the completion rides.

- **Files to touch** (plan, not allowlist): `files_scope`. Under Fork A
  only the five bundle files change in production code.
- **Steps in order** (each green before the next):
  1. Capture (P1): run a Signal `/image` on the isolated test stack with
     both daemon legs captured (the signal-jsonrpc-capture.md recipes —
     one long-lived jsonRpc stdin session so inbound frames are not eaten;
     never prod). Record which fork the frames prove, in the ticket
     evidence.
  2. Fork A (expected): `image.reply.echo` becomes single-line —
     `Image generated. Prompt used: {0}` — in en/cs/es/ru/tr (P3/P4).
     Fork B: stop and escalate with the capture. Fork C: skip to step 4
     with no production change.
  3. Live verification (the reproduction): SIG-IMG-07 re-run on Signal AND
     SimpleX, en and one non-en scope; SIG-IMG-06 sanitizer re-run on
     Signal (P2).
  4. The boundary pin (P4): SignalJsonRpcClientTest gains
     multiLineBodyThroughEditArrivesByteForByte — a two-line body through
     update()/finalizeHandle() asserted byte-for-byte in the encoded
     frame's `message` param.
  5. The design record: 06-messaging.md §6.5.7 region notes the capture
     outcome and the landed completion format (design docs carry history;
     this is the M1-855 wire-record pattern).
- **Controls to preserve (§10):** the echo sanitize call + unit + audit
  row (P2); the content-free IMAGE_GENERATE row; the placeholder finalize
  / typing-off contract (StageProgressNotifier.terminate); the chokepoint's
  retry ladder and link-adjacency neutralization
  (OutboundDelivery.finalizeInPlace); SIG-IMG-05/06/08's live-verified
  failure/restart contracts — none are touched.
- **Pitfall→mitigation:** P1→step 1 + acceptance items 1-2; P2→acceptance
  item 4; P3→step 2's bundle-only shape + acceptance item 6; P4→steps 2/4
  + acceptance items 3/5.

## Definition of done

The capture is attached and its fork recorded; the reproduction probe
passes (Signal completion carries the disclosure, en and non-en, SimpleX
unchanged in content); the boundary pin lands green; the sanitizer
failure-mode holds on Signal live; all five bundles carry the landed
value with BundleLoaderTest green; no adapter-name branch exists in the
diff; the design note records the wire truth; `mvn verify` green with
every pre-existing test unmodified. Under Fork C, "done" is the boundary
pin + design record + the evidence that exonerates the repo, with the
defect report's D-3 retest case answered.

## Verification

- P1 → acceptance item 1 (capture artifacts + recorded fork) and item 2
  (post-fix live probe). A fix committed without the capture fails
  SPEC-TRUTHNESS on its face.
- P2 → acceptance item 4 (FAILURE-MODE): privileged-command tokens in the
  prompt render as `[redacted command]` in the Signal completion, one
  LLM_OUTPUT_SANITIZED row, IMAGE_GENERATE content-free.
- P3 → acceptance item 6's greps (no `signal` in the handler; empty
  handler diff under Fork A).
- P4 → acceptance item 3 (SignalJsonRpcClientTest.multiLineBodyThroughEditArrivesByteForByte
  — a mutation that truncates at the adapter boundary reds it) and item 5
  (BundleLoaderTest + five-file diff-stat).
- acceptance item 7 → `grep -n 'Prompt used' docs/design/06-messaging.md`.
- acceptance item 8 → `mvn verify` from repo root.
- Non-vacuity: a fork decision recorded without attached frames fails
  item 1; a bundle edit in fewer than five locales reds BundleLoaderTest;
  a truncation mutation at the codec/client boundary reds item 3's pin.

## Out-of-scope

Prose mirror of the YAML list. No per-adapter branch anywhere (P3) — the
fix is adapter-neutral or it does not ship. signal-cli and the Signal
service are not patched here; if the capture indicts the daemon leg, Fork
A's format change is the whole provider-side answer. The translation leg,
skip flag, image pipeline, SimpleX attachment path, and M1-566's
edit-fallback work are untouched. No spec edit: the mandate already
exists; this ticket restores compliance with it. Under Fork B the ticket
escalates with the capture rather than widening into the indicted layer
mid-flight (P1); under Fork C no production change lands at all. No
pre-existing test is modified by this ticket.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-892`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-892-signal-image-prompt-echo.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
