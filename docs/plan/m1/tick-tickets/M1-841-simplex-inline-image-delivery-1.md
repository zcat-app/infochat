---
id: M1-841
title: "Probe SimpleX image-type composed-message surface"
status: done
created: 2026-08-11
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15: no blocking ambiguity. Citations verified (codec
  :276-286/:356-363, Dockerfile.jvm:47 pin v6.5.4, §6.2.4 at :322).
  One execution judgment recorded: the live probe ran on a standalone
  two-identity throwaway harness of the sha256-verified pinned binary
  (public SMP/XFTP servers) instead of the prod provider's WS API —
  v6.5.4's chat server (apps/simplex-chat/Server.hs:118-121) races
  every connected WS client on one shared outputQ, so a second probe
  connection against prod can steal the adapter's async frames. Same
  bundled-CLI + live-contact + live-group evidence value, no prod
  interference.
flow: tick
reproduction: >-
  Probe-style (a diff mvn verify cannot cover): the only SimpleX attachment
  wire form ever verified is the plain-file form —
  `grep -n 'msgContent' docs/design/06-messaging.md` shows
  `{"filePath":"…","msgContent":{"type":"file","text":""}}`
  (docs/design/06-messaging.md:384-388) and no image-typed form; the live
  probe of an image-typed composed message with inline preview has never
  been run against the bundled simplex-chat, so `/_send <target> json`
  acceptance of image msgContent, its preview limits, and its ack/completion
  frame sequence are unverified (analysis P1/P3/P7/P8). The user-visible
  wrong behavior: `/image` output arrives in the SimpleX client as a file
  attachment, not an inline picture (live-observed 2026-08-11,
  .scratch/simplex-image-delivery.md).
analysis_ref: docs/plan/m1/tick-analysis/simplex-inline-image-delivery.md
blocked_by: []
files_scope:
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any production or test code change — this ticket probes and records;
    the SPI field, preview generator, and codec flip are M1-842/M1-843.
  - Upgrading or pinning a different simplex-chat version (the batch-G
    upgrade owns that; this ticket targets whatever is bundled at start).
  - Recipient-client rendering verification beyond live observation (P9 —
    the bot cannot control client preferences).
  - Inbound attachment handling (D74: out of scope).
acceptance:
  - "The bundled version under test is named from the pin, not from prose: `grep -n 'SIMPLEX_CHAT_VERSION' infochat-provider/src/main/docker/Dockerfile.jvm` is run at start (v6.5.4 today; v7.0.0 if the batch-G upgrade has landed first — brief's sequencing constraint) and the 06-messaging.md record names exactly that version as the probed one — Verify: the recorded section's version string equals the Dockerfile pin at the time the record lands."
  - "Upstream source at the bundled tag is consulted for the CMContent image constructor and the preview size/encoding limits, and the findings are recorded in docs/design/06-messaging.md §6.2.4 with the source file references (analysis P3 — limits are measured, never invented) — Verify: `grep -n -i 'image' docs/design/06-messaging.md` shows the new §6.2.4 record carrying the constructor shape and the preview limits with their source citations."
  - "LIVE PROBE, DM scope: an image-typed composed message (filePath + image msgContent + inline preview within the probed limits) is sent via `/_send <target> json` to a live contact against the bundled CLI, and the outcome is one of two recorded verdicts: ACCEPTED (with the emitted msgContent form the CLI accepted) or REFUSED (with the exact error tag) — Verify: the verdict and the accepted/refused form are recorded in 06-messaging.md §6.2.4."
  - "LIVE PROBE, group scope (analysis P8 — `/image` works in groups, commands.md §Content): the same image-typed send is repeated against a live group and the verdict recorded alongside the DM one — a DM-only record does not satisfy this item."
  - "COMPLETION CONTRACT (analysis P7): for an ACCEPTED image send, the full ack/completion frame sequence is captured (send ack, progress, completion or error tags) and compared against the file-send contract (`sndFileCompleteXFTP` releases; anything else fails PERMANENT — SimpleXMessageCodec.java:356-363); any divergence is recorded frame-by-frame in 06-messaging.md §6.2.4 — Verify: the record names the completion tag observed for the image send, for DM and group."
  - "STOP/CONTINUE VERDICT: the §6.2.4 record ends with an explicit verdict line of the literal form `VERDICT: CONTINUE — <reason>` (image form accepted; the recorded form, limits, and frames are the inputs M1-842/M1-843 build on) or `VERDICT: STOP — <reason>` (the bundled CLI rejects the image form: M1-842/M1-843 are dropped and the file-delivery UX stands as documented behavior, messaging.md §Required SPI surface :131-134 — analysis Option B) — Verify: `grep -n 'VERDICT: ' docs/design/06-messaging.md` returns exactly one line, inside the §6.2.4 record, carrying CONTINUE or STOP."
  - "mvn verify from repo root is green (docs-only diff; no test changes)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D74
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "3 files, +98/-9"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-841-r1.txt
---

# M1-841: Probe SimpleX image-type composed-message surface

## Context

`/image` output arrives in the SimpleX client as a file attachment, not an
inline picture (live-observed 2026-08-11, `.scratch/simplex-image-delivery.md`).
Our encoder emits `{"type":"file","text":""}` beside `filePath`
(SimpleXMessageCodec.java:276-286) — the ONLY wire form ever live-probed
(docs/design/06-messaging.md:381-408). SimpleX apps send images as
image-typed messages with a small embedded preview plus the full-res file,
but whether the bundled CLI's composed-message parser accepts that form is
the family's enabling unknown. This ticket resolves it before any production
code moves. Shared analysis: `analysis_ref:`.

## Root cause

Proven: the file-typed msgContent is the whole cause on our side
(SimpleXMessageCodec.java:278-279). Unproven: everything about the image
form — parser acceptance, preview limits, ack/completion frames, group
scope. This ticket converts the unproven half into recorded fact.

## Pitfalls

Numbered consistently with the analysis document.

- P1: the image wire form is unverified — this ticket IS the mitigation;
  its failure mode is an under-probe (verifying DM only, skipping the
  source consult, or recording an assumed form), so every acceptance item
  names its evidence.
- P3: preview size/encoding limits come from the bundled tag's upstream
  source AND the live probe — never from memory (M1-800's discipline:
  ceilings measured against the real transport, never invented). A probe
  that records only acceptance proves nothing about the limits, so the
  refusal arm below is part of the measurement.
- P7: the completion contract (`sndFileCompleteXFTP` or PERMANENT,
  SimpleXMessageCodec.java:356-363) was verified for file sends only;
  M1-800's round-1 rework shows this frame mapping is where review
  findings live, so the image send's frames are captured in full.
- P8: group scope is probed alongside DM — `/image` is available in both
  (commands.md §Content), and the file form's completion was verified for
  both scopes (06-messaging.md:393-395).
- P9: the bot verifies what IT sends; recipient rendering is client-side.
  The record claims parser acceptance and wire content, never client
  rendering promises.

## Approach

- **Files to touch:** `files_scope` (docs/design/06-messaging.md only).
- **Steps, in order:**
  1. Read `SIMPLEX_CHAT_VERSION` from Dockerfile.jvm:47 — that is the
     probe target (batch G lands first per the brief; if it has, the pin
     reads v7.0.0 and that is correct). Note the prose/code discrepancy:
     comments say v6.5.4.1, the sha256-pinned release tag is v6.5.4 — the
     Dockerfile pin is authoritative.
  2. Upstream source at the bundled tag
     (https://github.com/simplex-chat/simplex-chat): the CMContent image
     constructor, the composed-message JSON parser's handling of
     image msgContent beside filePath, and the preview size/encoding
     constants. Record with file references.
  3. Live probe (the live SimpleX harness — the two-identity setup under
     docs/plan/live-e2e/): send an image-typed composed message with a
     within-limits preview via `/_send @<contact> json`, then the same
     via `/_send #<group> json`; capture every frame each send emits.
     Include the refusal arm: at least one send the source findings say
     the CLI rejects (an over-limit or malformed preview), capturing the
     exact error tag.
  4. Compare the captured completion sequence against the mapped contract
     (SimpleXMessageCodec.java:356-363); record agreement or divergence.
  5. Write the §6.2.4 record: version, constructor, limits (with sources),
     both verdicts, the refusal tag, frame sequences, and the
     `VERDICT: CONTINUE|STOP` line acceptance item 6 greps for.
- **Controls to preserve (§10):** none rerouted — docs-only diff. The
  record must not rewrite the existing verified file-form section; it
  ADDS the image-form findings beside it.
- **Pitfall→mitigation:** P1→items 2-5; P3→item 2 + step 3's refusal arm;
  P7→item 5; P8→item 4; P9→item 6's wording discipline.

## Definition of done

06-messaging.md §6.2.4 carries: the probed version (matching the
Dockerfile pin), the upstream-source findings with references, the DM and
group live-probe verdicts, the captured refusal error tag, the captured
completion frame sequence with its comparison to the file-send contract,
and the explicit `VERDICT: CONTINUE|STOP` line. `mvn verify` green.

## Verification

- P1 → acceptance items 2-5 (each names its grep or probe).
- P3 → item 2's source-cited limits record; failure-mode: the step-3
  refusal arm feeds the CLI a preview the source findings say it rejects
  (over-limit or malformed) and asserts the exact refusal error tag is
  captured in the §6.2.4 record — a probe recording only acceptance
  proves nothing about the limits, so a record with no refusal
  observation fails this entry.
- P7 → item 5's frame-sequence record; a divergent completion tag is a
  recorded divergence, not a silent one.
- P8 → item 4: a record covering only DM fails this item.
- P9 → item 6's verdict wording asserts parser acceptance and wire form
  only.
- Item 1 → the grep against Dockerfile.jvm.
- Item 6 → the `grep -n 'VERDICT: ' docs/design/06-messaging.md` probe —
  exactly one verdict line, so a missing or duplicated verdict fails it.
- Item 7 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: all code changes (M1-842/M1-843), version
upgrades (batch G), recipient-client rendering guarantees, inbound
attachments. No pre-existing test is modified. If the verdict is STOP,
the siblings are dropped and this record is the family's output (analysis
Option B — wontfix-with-rationale is a valid outcome).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-841-simplex-inline-image-delivery-1.md
```
