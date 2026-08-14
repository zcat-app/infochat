---
id: M1-823
title: "Hard-fail malformed GGUF URLs in the download preflight"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probe (RED on main): `sed -n '254,269p' prod/scripts/4-llm.sh` — the
  non-zero-exit classification at :261 enumerates only 6/7/28 (network class),
  so curl exit 3 (URL malformed — NO network I/O was ever attempted) falls
  into the WARN branch at :267, which prints "answered but refused the HEAD
  probe ... reachability confirmed; continuing" for a probe that reached
  nothing. Live-observed 2026-08-11 (.scratch/setup-hurdles.md items 4-5): a
  pasted full local path produced WARN "reachability confirmed", then the
  download died "URL rejected: No host part in the URL". Test:
  LlamacppWiringTest.preflightFailsHardOnMalformedUrl (to-be-written —
  `start` writes it and runs it RED on main before any fix code, workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/llm-wizard-robustness.md
blocked_by: []
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The local-path staging flow itself (sibling M1-824 owns it). This
    ticket's exit-3 message must NOT advertise a staging flow that does not
    exist yet (analysis P2); M1-824 updates the message under its own
    pre-authorized test modification.
  - The existing exit classes: 6/7/28 abort with the network-path/proxy
    guidance and 22 warns + continues (M1-809 P10) — re-tuning either is a
    sibling's or a new ticket's job, not this one's.
  - 4b-image.sh's head_check (strict abort on any failure for curated URLs —
    no WARN branch exists there to misclassify) and restore.sh (no preflight,
    M1-809's deliberate decision).
  - fetch_gguf and its verbatim restore.sh twin — untouched.
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.preflightFailsHardOnMalformedUrl — drive the llamacpp branch (pinned defaults) with FAKE_CURL_EXIT=3: rc is NON-ZERO, no docker-argv.log exists (the download never starts), curl-argv.log exists (the probe ran), the output contains 'malformed' and does NOT contain 'reachability confirmed'. RED on main: exit 3 hits the WARN branch and the drive proceeds to rc 0."
  - "Exit-class keying, not string matching (analysis P1, FAILURE-MODE): LlamacppWiringTest.hostlessSchemeValidUrlAbortsLikeALocalPath (to-be-written, test_plan.adds) drives a custom-URL answer that is scheme-valid but hostless (e.g. https://) with FAKE_CURL_EXIT=3 and asserts the identical abort — rc non-zero, no docker argv recorded. The script decides on rc == 3, never by pattern-matching the operator's input."
  - "Existing classes preserved (M1-809): LlamacppWiringTest.preflightDistinguishesNetworkFailureFromHeadRefusal (exit 22 warns + continues with the download issued; exits 6/7/28 abort before any download) and llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload stay green UNMODIFIED."
  - "Message honesty, intermediate-state (analysis P2; wizard contract docs/design/07-deployment.md §7.7.2 — actionable remedy, never a cryptic failure): the exit-3 text states the URL is malformed, names the looks-like-a-path-not-a-URL cause class, and gives the remedy available TODAY (paste the full https:// download URL, or press Enter for the pinned default) — and makes NO claim about a local-path staging flow (that is M1-824's end state). Probe: capture the reproduction drive's output and grep for those elements."
  - "mvn verify from the repo root is green; bash -n prod/scripts/4-llm.sh passes."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — preflightFailsHardOnMalformedUrl (reproduction) and
      hostlessSchemeValidUrlAbortsLikeALocalPath (P1 exit-class failure-mode
      drive).
  preserves:
    - all tests currently green on main
    - >-
      Every M1-809 preflight pin (:352-434) unmodified — this ticket adds a
      class; it does not re-tune the existing ones. NOTE for the sibling:
      M1-824 is pre-authorized to ADD one assertion to
      preflightFailsHardOnMalformedUrl (the exit-3 text gains a pointer to
      the staging flow once it exists); its M1-823 assertions are
      additive-tolerant by construction (substring presence/absence, never
      full-output equality).
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
---

# M1-823: Hard-fail malformed GGUF URLs in the download preflight

## Context

Live on the prod host (2026-08-11, .scratch/setup-hurdles.md items 4-5): an
operator pasted a full local path at the generative-GGUF prompt. The
preflight's curl exited 3 (URL malformed) and the wizard printed WARN
"...reachability confirmed; continuing" — both claims false, no network was
attempted — then the download died with "URL rejected: No host part in the
URL". The operator learns nothing actionable at any step. Shared analysis:
`analysis_ref:`.

## Root cause

`preflight_gguf_url` (prod/scripts/4-llm.sh:254-269) classifies non-zero curl
exits at :261: only `6 || 7 || 28` abort (the M1-809 network class). Every
other exit — exit 3 included — inherits the WARN at :267, whose text was
written for exit 22 (an HTTP-level refusal, which DOES prove reachability).
Exit 3 is a third class the M1-809 design never enumerated: the URL never
went on the wire, so neither "abort with VPN/proxy guidance" nor "warn +
continue" is honest.

## Pitfalls

Numbered consistently with the analysis document.

- P1: exit-class honesty — the fix keys on `rc == 3`, never on string-matching
  the operator's input; a scheme-valid-but-hostless URL must fail identically
  (acceptance item 2 is the discriminating drive).
- P2: intermediate-state message honesty — this ticket lands BEFORE M1-824's
  staging flow; the exit-3 text must describe only remedies that exist today.
  M1-824's message update is pre-authorized here and named there
  (test_plan.preserves).
- P3: hermeticity — the drives use the existing fake-curl seam
  (FAKE_CURL_EXIT); no real egress from mvn verify.
- P10: no new stdin prompts (M1-809's hard constraint, carried from the
  analysis) — the reclassification adds no `read`; every existing positional
  drive must line up unchanged.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write `preflightFailsHardOnMalformedUrl` + the hostless-URL drive against
     the existing fake-curl seam — run RED on main (workflow §0).
  2. In `preflight_gguf_url` (:254-269), add the exit-3 branch BEFORE the WARN
     fallthrough: hard FAIL (exit 1) whose message states the URL is
     malformed, names the path-not-a-URL cause class, and gives today's
     remedy (full https:// URL or Enter for the pinned default). Leave the
     6/7/28 abort and the 22/WARN semantics byte-identical (P1 classes
     untouched; the WARN text may keep serving the remaining non-3 exits —
     do not reword it, M1-809 owns that string).
  3. `bash -n`, then `mvn verify` from the repo root.
- **Controls to preserve (§10):** the M1-809 classification (6/7/28 abort
  text, 22 warn+continue), the preflight's call sites and ordering
  (:437-440), the no-new-prompts constraint (this change adds no read), and
  every existing LlamacppWiringTest pin (test_plan.preserves).
- **Pitfall→mitigation:** P1→step 2's rc-keyed branch + acceptance item 2;
  P2→step 2's message scope + item 4; P3→step 1's existing seam; P10→step 2
  adds no `read` (item 3's unmodified drives are the regression net).

## Definition of done

curl exit 3 in the GGUF preflight is a hard fail with an honest
malformed-URL/path-not-a-URL message and a today-valid remedy; the exit-22
warn and the 6/7/28 abort behave exactly as M1-809 pinned them; the new tests
are green and hermetic; mvn verify green.

## Verification

- P1 → LlamacppWiringTest.hostlessSchemeValidUrlAbortsLikeALocalPath
  (failure-mode): feeds the classifier a hostile edge input — a hostless
  scheme-valid URL — and asserts the same abort; a mutation that
  string-matches the operator's input instead of keying on rc fails this
  drive.
- P2 → acceptance item 4's output greps: the exit-3 text must not advertise
  M1-824's staging flow before it exists, and must not drop the
  malformed/path cause class — either mutation fails the greps.
- P3 → the reproduction drive's curl-argv-log existence assertion: a
  preflight that stops calling curl contains no recorded probe and fails
  loudly.
- P10 → every pre-existing positional drive stays green unmodified; the
  wizard must not gain a prompt in this change.
- Reproduction → acceptance item 1. Class preservation → item 3 (the M1-809
  pins stay green unmodified).

## Out-of-scope

Named in `out_of_scope`: the staging flow (M1-824), the existing exit classes,
4b-image.sh and restore.sh, fetch_gguf. No pre-existing test is modified; the
sibling-facing note in test_plan.preserves pre-authorizes M1-824's additive
assertion on this ticket's new test (engineering-rules §8 authorization,
recorded at draft time in both tickets).

## Census

Class: wizard download preflights classifying curl exits (re-runnable:
`grep -n 'fsSLI\|head_check' prod/scripts/*.sh`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh preflight_gguf_url (:254-269) | FIXED (this ticket) |
| prod/scripts/4b-image.sh head_check (:519-526, per M1-809) | out-of-scope: strict abort on ANY failure for curated URLs — no WARN branch exists to misclassify exit 3 |
| prod/scripts/restore.sh | out-of-scope: no preflight by design (M1-809 — non-interactive mid-restore; guidance + SHA + skip-if-present cover) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-823-llm-wizard-robustness-1.md
```
