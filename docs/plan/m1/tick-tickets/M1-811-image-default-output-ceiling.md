---
id: M1-811
title: "Align the image pixel ceiling with the default output"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  to-be-written: PngMetadataStripTest.shippedCeilingAcceptsTheMeasuredDefaultOutput —
  reads infochat.image.max-output-pixels from the MAIN
  infochat-provider/src/main/resources/application.properties (filesystem
  path, the HelpTopicCorpusTest.readProbationDurationFromMainProperties
  precedent — test-resources shadow the classpath), builds a PNG whose IHDR
  is the measured default output 1792x1344 (2,408,448 px,
  bench/livetest-10-08-26.md E8/E12), runs PngMetadataStrip.strip at the
  shipped value, and asserts the output survives with its IHDR intact. RED
  on main: the shipped value is 2000000, so the strip throws
  InvalidPngException on the deployment's own default output.
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/PngMetadataStripTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
  - docs/design/future/image-generation.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The strip's mechanics — bound arithmetic, IHDR-only read, chunk-level
    drop, verbatim copy (M1-801; this ticket changes the bound VALUE
    alone).
  - The wizard-authored templates and MODEL_FIT_MP values (4b-image.sh) —
    the 2.296875 MP Krea fit is the user-approved Final decision 3/5 shape
    (design doc :831-858); the ceiling moves to the pipeline, never the
    pipeline to the ceiling.
  - The -r converter graph (M1-812), the queue-depth audit arms (M1-813),
    the sweeper (M1-814), the sanitizer (M1-815), the e2e gate (M1-816).
  - Any spec edit — commands.md:634-638 delegates the pixel-bound value to
    design notes; the design-table row is the value's home of record.
acceptance:
  - "PngMetadataStripTest.shippedCeilingAcceptsTheMeasuredDefaultOutput passes — REPRODUCTION (written and run RED at start). The shipped ceiling accepts the measured default output 1792x1344 (commands.md §Content: the pixel bound is delegated to design notes — the configured pipeline's own default output must pass it; bench E12 proved the delivery path works once it does)."
  - "The value lands in BOTH sites: `grep -n '^infochat.image.max-output-pixels=' infochat-provider/src/main/resources/application.properties` prints 5000000, and `grep -n 'max-output-pixels' infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java` shows the matching @ConfigProperty defaultValue (a missing key must not fall back to a value that rejects the default output)."
  - "The design-table row moves with the value (P1): docs/design/future/image-generation.md's shipped-gate-values row reads 5000000 and its Bounds column names BOTH surfaces the key bounds — the --resolution parser check and the strip's IHDR check on every output (analysis D-1: the old wording named only --resolution) — Verify: `grep -n 'max-output-pixels' docs/design/future/image-generation.md` plus DocumentedConfigKeyParityTest green via mvn verify."
  - "ImageCommandHandlerTest.overCeilingOutputFailsLoudlyAndContentFree passes — FAILURE-MODE (analysis P2): the stub client returns a PNG whose IHDR exceeds the handler's ceiling; asserts the generic IMAGE_ERROR_GENERATION_FAILED terminal, exactly one content-free IMAGE_GENERATE row {\"outcome\":\"failed\"}, no spool file created, AND the rejection is observable — Verify: review grep of the diff shows the InvalidPngException catch emits one WARN whose arguments are the exception's structural message (dimensions + bound; PngMetadataStrip.java:45-69 builds messages from parsed integers and fixed strings only) and nothing else — never the prompt, graph, or response body (D75/D37)."
  - "The bound still bounds (P1): PngMetadataStripTest.refusesOversizedDimensions passes UNEDITED at the raised value — a hostile IHDR over the ceiling is still refused before any strip output (redteam finding 6's intent preserved)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - PngMetadataStripTest.shippedCeilingAcceptsTheMeasuredDefaultOutput (plus the dimension-parameterized minimal-PNG builder it and M1-816's wiring test share)
    - ImageCommandHandlerTest.overCeilingOutputFailsLoudlyAndContentFree
  preserves:
    - all tests currently green on main (PngMetadataStripTest's three refusal tests, ImageSpoolTest, ImageCommandHandlerTest's existing suite — its :133 `2_000_000L` is a test-local stub value exercising the rejection path, not a config pin, and stays)
spec_refs:
  - docs/spec/commands.md §Content
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-811: Align the image pixel ceiling with the default output

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E8): the default `/image
a red cat` fails AFTER ComfyUI completes — the generated 1792x1344 PNG
(2,408,448 px) is valid, but `PngMetadataStrip` rejects it at the shipped
`infochat.image.max-output-pixels=2000000`, and the handler's
`InvalidPngException` catch returns the generic failure with NO log line, so
the operator sees nothing. Reproduced in DM and group; E12 isolates it —
raising the ceiling to 3,000,000 delivered the identical request end-to-end.
Shared analysis: `analysis_ref:` (pitfalls P1/P2 below match it).

## Root cause

Two config facts authored at different times never met in any test
(analysis §Root cause): the wizard bakes the Krea fit target at 2.296875 MP
(4b-image.sh:184-188 — the spacepxl 2× decode, user-approved Final
decisions 3/5, design doc :842-848 "ceiling 2.4 MP", measured 1792×1344 at
:820-822), while the Provider's pixel ceiling shipped at 2,000,000
(application.properties:230; ImageCommandHandler.java:110-111). The handler
passes the SAME value to the strip for every output
(ImageCommandHandler.java:314) — flag-free jobs included — so the default
job always fails after the GPU runs. Secondary, proven cause of the
observability gap: the catch at ImageCommandHandler.java:313-321 writes the
audit row and replies but emits no log line; the outer `SafeLog.error` arm
(:367-372) never sees the handled exception.

## Pitfalls

Numbered consistently with the analysis document.

- P1: the ceiling is a DOS bound, not a tunable — redteam finding 6 put it
  in the spec (commands.md:634-638); security.md:1896-1900 names widening
  such a limit "the dangerous direction". The value follows the headroom
  rule (user decision 2026-08-10): the deployment's own default output must
  sit WELL BELOW the ceiling — ceiling = 2x the measured default
  2,408,448 px rounded to 5,000,000 (~2.1x headroom) — and stops at the
  user-declared upper bound of ambition: 4096x4096-class requests are OUT
  of v1 scope (no upscaler in v1; "we are not a graphic studio, just a
  simple chat bot on a home box"), so 5 MP is the enforcement point. Sanity
  at 5,000,000: a worst-case PNG is ~6-8 MB, still under the 16 MiB fetch
  cap (infochat.image.max-response-bytes) and far under the 1 GiB spool and
  the adapter ceilings (Signal 150 MiB / SimpleX 1 GiB,
  06-messaging.md:362-365); refusesOversizedDimensions tests the bound
  MECHANISM, not the value, and stays green unedited. The design-table row
  moves WITH the value, including its Bounds wording (D-1). Strip mechanics
  untouched (M1-801 round-1 fixes: overflow-free arithmetic, IHDR-only
  read).
- P2: the new log line stays content-free — D75/D37. The exception message
  is structural-only (dimensions, offsets, bound — verified at
  PngMetadataStrip.java:45-69), so logging it is safe; the prompt, graph,
  and response body never reach a log call.

## Approach

Derived from `spec_refs:` — commands.md:634-638 delegates the pixel-bound
value to design notes; the fix makes the shipped value one the configured
pipeline's own default output satisfies.

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test RED, including the dimension-parameterized
     minimal-PNG builder (signature + IHDR(w,h) + tiny IDAT + IEND, CRC32
     per chunk — PngMetadataStripTest's existing builder is the precedent;
     the strip never inflates IDAT, so a tiny body with a large IHDR is an
     honest fixture).
  2. Raise the value: application.properties:230 → 5000000 and the
     @ConfigProperty defaultValue at ImageCommandHandler.java:110 → the
     same (a missing key must not fall back to rejecting the default
     output). 5,000,000 = 5 MP is the headroom rule (user decision
     2026-08-10): 2x the measured default 2,408,448 px rounded to
     5,000,000, so the default sits well below the ceiling (~2.1x
     headroom); it is also the enforcement point of the user-declared
     upper bound of ambition — 4096x4096-class requests are out of v1
     scope (no upscaler in v1; "we are not a graphic studio, just a
     simple chat bot on a home box").
  3. Make the catch speak: one `log.warn` in the InvalidPngException arm
     carrying the exception (structural message) — no other behavior of the
     arm changes (row, reply, no-refund all stay).
  4. Update the design-table row (value + Bounds wording naming both
     surfaces).
  5. The failure-mode handler test; full verify.
- **Controls to preserve (§10):** the strip's chunk-level mechanics and
  overflow-free bound arithmetic (untouched); the parser's overflow-safe
  `-r` check (ImageCommandParser.java:112-116 — it reads the same raised
  value, widening the -r range consistently with the unified converter's
  lanczos-fit contract); the audit row on the strip arm; the generic
  failure wording; every pre-existing refusal test.
- **Pitfall→mitigation:** P1→steps 2/4 + item 3 + the preserved
  refusesOversizedDimensions; P2→step 3 + item 4's review grep.

## Definition of done

Every acceptance item green by its named verification: the reproduction
passes at the shipped value; the value lands in both config sites; the
design row is truthful; the rejection path fails loudly and content-free;
the bound still bounds a hostile IHDR; full verify green.

## Verification

- P1 → PngMetadataStripTest.shippedCeilingAcceptsTheMeasuredDefaultOutput
  (reads the shipped value — a value regression reds it) +
  refusesOversizedDimensions unedited (failure-mode: hostile IHDR still
  refused) + the design-table grep.
- P2 → ImageCommandHandlerTest.overCeilingOutputFailsLoudlyAndContentFree
  (over-ceiling PNG through the handler; asserts terminal + row + no spool
  file) + acceptance item 4's review grep (no prompt/graph/body in the new
  log call).
- Non-vacuity: reverting the value to 2000000 reds the reproduction;
  deleting the new WARN leaves the failure-mode's observability clause
  unmet; a strip that skips the IHDR check reds refusesOversizedDimensions.

## Out-of-scope

Named in `out_of_scope`: the strip's mechanics, the wizard templates and
MODEL_FIT_MP values (the pipeline does not move to the ceiling), the other
livetest tickets' surfaces, and any spec edit (the value is design-layer).
No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-811-image-default-output-ceiling.md
```
