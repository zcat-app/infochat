---
id: M1-809
title: "Wizard download preflight parity and fail-fast guidance"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  Probe (RED on main): `grep -n curl prod/scripts/4-llm.sh` matches ONLY the
  CURL_IMAGE constant (:49) and comments — the llamacpp branch starts a
  multi-GB GGUF download (4-llm.sh:410,424) with NO reachability preflight at
  all, and any network failure surfaces as a bare curl error mid-download
  (observed shape in the 4b twin: `curl: (6) Could not resolve host:
  huggingface.co`, brief 2026-08-10). Intended test (to-be-written at start):
  LlamacppWiringTest.llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload.
analysis_ref: docs/plan/m1/tick-analysis/wizard-download-container-network.md
blocked_by: [M1-808]
files_scope:
  - prod/scripts/4-llm.sh
  - prod/scripts/4b-image.sh
  - prod/scripts/restore.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The network context of the download containers — M1-808 owns it and this
    ticket CONSUMES it: the new preflight runs with host curl BECAUSE M1-808
    makes the host path the download path (parity claim). Do not edit the
    download invocations here.
  - A preflight for the `ollama pull` leg — the pull runs inside the service
    container on the compose network, which is verified WORKING on the
    divergent host class (embedded DNS forwards to the host resolver stub,
    analysis §Ground truth); a host-curl HEAD of the registry would test a
    path the pull does not use — the P1 false-pass shape. That leg is
    censused, not preflighted.
  - The image-build legs (sibling M1-810) and any claim about them: this
    ticket lands BEFORE M1-810, so its guidance text states ONLY the download
    legs (analysis P6 intermediate-state discipline). Runtime egress needs no
    text: it is verified unaffected.
  - The fetch mechanics (-u 0:0, argv-only, SHA enforcement, skip-if-present,
    retry flags) — unchanged; 4b's head_check GATE semantics for curated URLs
    (strict abort on any failure — only the printed message changes).
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload — the drive layer gains a fake curl on PATH (records argv to a curl-argv log, exits with an env-controlled code; same pattern as the fake docker). With fake-curl exit 6 (resolve failure), driving the llamacpp branch exits NON-ZERO with guidance text on stderr/stdout and records NO docker invocation (the download never starts). RED on main: 4-llm.sh never consults curl, so the drive proceeds to download and exits 0."
  - "Preflight coverage (analysis P1): with fake-curl exit 0, the llamacpp branch HEAD-checks the generative GGUF URL ALWAYS and the embeddings GGUF URL when the embeddings backend is llamacpp, before the first fetch_gguf — probe: the curl-argv log shows `-I`/HEAD invocations for both pinned URLs and the docker-argv log's first download line comes after them in script order; a drive with embeddings backend = ollama HEADs ONLY the generative URL (FAILURE-MODE: preflighting the ollama-pull leg — a path the download does not use — fails this item)."
  - "Custom-URL semantics (analysis P10): a custom generative URL whose HEAD gets an HTTP-level refusal (fake-curl exit 22 — the server ANSWERED) prints a warning and CONTINUES to download; network-class failures (exit 6, 7, 28) abort with guidance — probe: three drives (exit 22 → rc 0 + download argv present; exits 6/28 → rc non-zero + no docker argv)."
  - "Guidance text honesty, end-state-calibrated (analysis P6; wizard contract docs/design/07-deployment.md §7.7.2 First-run setup wizard — every unmet check reported with an actionable remedy, never a silent or cryptic failure): every preflight and download failure message states that the checked path IS the host's own network path, names at least one actionable cause class (connectivity / VPN / proxy / firewall), and names the proxy-env forwarding the download uses (export the proxy vars and re-run is the stated remedy). The text makes NO claim about build legs (M1-810's lane, not yet landed), does NOT blame 'container DNS', and does NOT present daemon DNS configuration as a requirement — probe: capture the failure output in the exit-6 drive and grep it for those elements; `grep -n 'network path' prod/scripts/4-llm.sh` hits the guidance (FAILURE-MODE: a message containing 'container DNS' or a daemon-config requirement fails this item — after M1-808 that class does not apply to downloads, and after M1-810 no repo leg needs daemon DNS at all)."
  - "4b-image.sh message parity: head_check's failure message (4b-image.sh:523) and fetch_asset's download failure carry the same guidance elements — probes: `grep -n 'network path' prod/scripts/4b-image.sh` hits both sites, `bash -n prod/scripts/4b-image.sh` passes, and a live dead-host probe (point the step at one unresolvable asset URL) aborts with the guidance BEFORE any download begins (the M1-798 item-6 shape, extended with the message)."
  - "restore.sh lock-step guidance (analysis P3): restore.sh's fetch_gguf failure prints the same guidance class, AND the manual-fetch recipe restore.sh PRINTS for pre-M1-571 custom GGUFs (:273-274) carries the same `--network host` + name-only proxy flags the script's own download uses (the advised manual path must work on the same host class) — probes: `grep -n 'network path' prod/scripts/restore.sh` hits, `grep -n -- '--network host' prod/scripts/restore.sh` hits BOTH the download line and the printed recipe, `bash -n prod/scripts/restore.sh` passes. (No preflight added to restore: its fetch is non-interactive mid-restore; guidance + SHA + skip-if-present cover — census rationale.)"
  - "Hermeticity (analysis P9): every llamacpp-branch drive intercepts curl — probe: the success drives assert a NON-EMPTY curl-argv log (no real query to huggingface.co escapes the fake); mvn verify from the repo root is green with M1-808's pins (--network host argv assertion, mechanics assertions) still green."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload (reproduction),
      llamacppPreflightHeadChecksEveryGgufUrlBeforeDownload (coverage),
      preflightDistinguishesNetworkFailureFromHeadRefusal (P10 semantics),
      plus the fake-curl seam in the drive layer.
  preserves:
    - all tests currently green on main
    - >-
      Every M1-808 addition (the --network-host argv pin and the shim
      extension, consumed here) and every pre-existing LlamacppWiringTest
      assertion. AUTHORIZED modification of the shared drive layer: runWizard
      places a fake curl on PATH alongside the fake docker, recording argv
      and exiting with an env-controlled code defaulting to 0 — existing
      drives see identical behavior (their preflight passes silently).
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-809: Wizard download preflight parity and fail-fast guidance

## Context

After M1-808 the wizard's downloads run on the host's own network path — but
4-llm.sh still starts a multi-GB GGUF download with NO reachability preflight
(grep-verified: the file contains no host curl invocation), and when any
download fails, the operator sees a bare curl error with no cause class and
no remedy (the reported defect's "cryptic curl error, no hint"). The wizard's
own design contract is preflight-with-actionable-remedy (design §7.7.2:
step-0 row "reports all unmet ones at once, each with an actionable remedy";
4b row "preflight ... before any download") and the M1-390/M1-439 doctor
lineage ("a check it cannot verify is reported unverifiable, never silently
passed"). Shared analysis: `analysis_ref:`.

## Root cause

Two gaps, both verified on main: (1) 4-llm.sh's llamacpp branch has no
preflight at all — the M1-798 step got one (4b-image.sh:519-526,774-780),
the GGUF step never did (4-llm.sh:410 downloads immediately after the
prompts); (2) none of the three download scripts translates a curl failure
into a cause class — the message the operator sees is curl's own stderr.
Preflight parity ("the check tests the path the download uses") becomes
achievable only because M1-808 moved the download to the host path — hence
blocked_by.

## Pitfalls

Numbered consistently with the analysis document.

- P1: a preflight that tests a different path than the download is a false
  pass; a preflight stricter than the download path is a false abort. The new
  preflight runs host curl BECAUSE that is now the download path, and it
  aborts only on network-class evidence (see P10). The ollama-pull leg gets
  NO host preflight — it runs on the compose network (verified working on the
  divergent host class), so a host-curl HEAD would be exactly the false-pass
  shape in the other direction.
- P6: message honesty, calibrated to the family END state in both directions
  — the text must not blame "container DNS" (after M1-808 the download path
  IS the host path), must not present daemon DNS configuration as a
  requirement (after M1-810 no repo leg needs it), and — because this ticket
  lands BEFORE M1-810 — must make no claim about build legs at all.
- P9: the preflight runs curl — the mvn drives must intercept it with a fake
  on PATH (family posture: no containers, no daemon, no network); no real
  huggingface.co query from `mvn verify`.
- P10: operator-entered custom URLs (M1-417 supported flow) may refuse HEAD;
  the preflight verifies the NETWORK PATH — an HTTP-level refusal proves
  reachability (warn + continue), only exits 6/7/28 abort.

## Approach

- **Files to touch:** `files_scope` (preflight + messages in the three
  scripts; fake-curl seam + three tests in LlamacppWiringTest).
- **Steps, in order:**
  1. Add the fake-curl seam to LlamacppWiringTest's drive layer (argv log +
     env-controlled exit code, default 0) and write the reproduction test —
     run RED on main (workflow §0).
  2. 4-llm.sh llamacpp branch: after the URLs are final (generative
     override/custom prompts + embeddings backend choice), before the first
     fetch_gguf (:410), HEAD-check the generative URL always and the
     embeddings URL when llamacpp-backed — host curl, 4b's `--max-time 60`
     shape, network-class exits abort with guidance, exit 22 warns and
     continues (P10). No new prompts — the drive layers feed positional
     stdin, so the preflight must not shift any read.
  3. Failure guidance: 4-llm.sh preflight + fetch_gguf download failures,
     4b-image.sh head_check + fetch_asset failures, restore.sh fetch_gguf
     failures — one shared text shape per analysis P6 (host network path,
     cause classes, proxy-env note + export-and-re-run remedy). 4b's strict
     gate semantics unchanged; messages only. Also update the manual-fetch
     recipe restore.sh PRINTS (:273-274) to carry `--network host` + the
     name-only proxy flags (the advised manual path must work on the same
     host class).
  4. Coverage + semantics tests (acceptance items 2–3), hermeticity item 7.
  5. Live dead-host probe for 4b (acceptance item 5); `mvn verify`.
- **Controls to preserve (§10):** M1-798 item-6 gate (HEAD-before-download,
  dead-URL abort) keeps its semantics for curated URLs; fetch mechanics
  untouched (M1-808's pins stay green); restore lock-step (P3); the drive
  layer's existing behavior is preserved by the fake curl's default exit 0
  and by adding no prompts (stdin-positional drive layers).
- **Pitfall→mitigation:** P1→step 2's same-path host curl + item 2's
  ollama-leg negative; P6→step 3 + item 4's FAILURE-MODE; P9→step 1 +
  item 7; P10→step 2's exit-code split + item 3.

## Definition of done

4-llm.sh preflights every GGUF URL it will download (llamacpp legs) on the
host path before any download; network-class failures abort with actionable
guidance, HEAD refusals warn and continue; all three scripts print the honest
cause-class guidance on preflight/download failure (download legs only — no
build claims); restore.sh's printed manual recipe carries the same flags; the
failure-mode, coverage, and semantics tests are green and hermetic; mvn
verify green with M1-808's pins intact.

## Verification

- P1 → reproduction test (exit 6 → non-zero abort, zero docker argv) +
  item 2 (both URLs HEADed; ollama-backed drive HEADs only the generative
  URL — the false-pass shape is refused).
- P6 → item 4's text probes (FAILURE-MODE: "container DNS" blame or a
  daemon-config requirement fails).
- P9 → item 7: non-empty curl-argv log in every success drive proves
  interception.
- P10 → item 3: exit 22 continues (download argv present, rc 0); exits
  6/7/28 abort (no docker argv, rc non-zero).
- Reproduction → item 1. Coverage → item 2. 4b/restore parity → items 5–6.

## Out-of-scope

Named in `out_of_scope`: the download network context (M1-808, consumed);
the ollama-pull preflight (would test a path the pull does not use —
censused, verified working); the build legs (sibling M1-810 — this ticket's
text makes no build claims); fetch mechanics (unchanged); 4b's gate
semantics (messages only). Pre-existing tests: no assertion modified; the
authorized change is the drive layer's fake-curl addition
(test_plan.preserves).

## Census

Class: wizard download legs lacking a same-path preflight or actionable
failure guidance (re-runnable: `grep -rn 'fetch_gguf\|fetch_asset\|ollama pull' prod/scripts/`).

| Site | Disposition |
|---|---|
| prod/scripts/4b-image.sh head_check (:519-526, :774-780) | has preflight; gains guidance text (this ticket) |
| prod/scripts/4b-image.sh fetch_asset failure (:594) | gains guidance (this ticket) |
| prod/scripts/4-llm.sh llamacpp generative GGUF (:410) | gains preflight + guidance (this ticket) |
| prod/scripts/4-llm.sh llamacpp embeddings GGUF (:424) | gains preflight + guidance (this ticket) |
| prod/scripts/4-llm.sh ollama `ollama pull` (:319-322, :451-452, :619-620) + restore.sh:677 | out-of-scope: compose-network leg, verified WORKING on the divergent host class — host preflight would test a path the pull does not use (P1) |
| prod/scripts/restore.sh fetch_gguf (:226-244) | gains failure guidance in lock-step (this ticket); no preflight — non-interactive mid-restore, guidance + SHA + skip-if-present cover |
| prod/scripts/restore.sh printed manual recipe (:273-274) | gains the same flags (this ticket) |
| Image-build legs (7-apps.sh:62; upgrade.sh:155,272; restore.sh:695; 4b-image.sh:801 implicit) | FIXED by sibling M1-810 |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-809-wizard-download-preflight-parity.md
```
