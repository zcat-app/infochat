---
id: M1-824
title: "Stage operator-local GGUF files into the model volume"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probe (RED on main): `grep -n 'entrypoint cp\|/stage' prod/scripts/4-llm.sh`
  prints nothing — the generative (:385) and embeddings (:412) prompts treat
  every non-empty answer as a URL, and the preflight (:437-440) runs before
  the only presence probe (:221). Live-observed 2026-08-11
  (.scratch/setup-hurdles.md items 4+6): a full local path → WARN
  "reachability confirmed" then "URL rejected: No host part in the URL"; a
  bare filename → curl exit 6 → hard FAIL with misleading VPN/proxy
  guidance; skip-if-present is unreachable for a local file behind the
  preflight, so the session workaround was a root-container pre-stage +
  re-run. Test:
  LlamacppWiringTest.localGgufPathIsStagedIntoTheVolumeWithoutDownload
  (to-be-written — `start` writes it and runs it RED on main before any fix
  code, workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/llm-wizard-robustness.md
blocked_by: [M1-823]
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - fetch_gguf's body and its verbatim restore.sh twin (analysis P7): the new
    staging function duplicates the probe/SHA block inline rather than
    refactoring the twin restore.sh may not move with (batch A). The M1-808
    lock-step dedupe probe must still print 1.
  - restore.sh itself (batch A) — including its fail-loud message's
    "bundle predates M1-571" parenthetical, which gains a second cause once
    this family lands (recorded in the analysis §P6 residual).
  - The setup-time disclosure that a staged model is not re-fetchable on
    restore, and the §7.10.1 wording (sibling M1-825). This ticket lands the
    contract-safe persistence (empty URL) only.
  - Relative-path / bare-filename acceptance: the staged flow requires an
    ABSOLUTE path to an existing readable regular file; a relative path, a
    bare filename, a missing or unreadable file, and a directory each get a
    prompt-time hard fail instead. Widening the accepted forms is a separate
    decision.
  - 4b-image.sh's asset prompts (batch C).
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.localGgufPathIsStagedIntoTheVolumeWithoutDownload — the test creates a temp .gguf file and answers the generative prompt with its ABSOLUTE path (rest of the drive: pinned/embeddings defaults); asserts rc 0, the curl-argv log contains NO entry carrying the path (the URL preflight is bypassed for a staged source), the docker-argv log records an argv-only cp invocation (no --entrypoint sh, no bash -c) carrying -u 0:0, a read-only -v <dir>:/stage mount, the pinned CURL_IMAGE, and target /models/<basename> in infochat-llamacpp-models, and application.properties carries the basename as the model on every task. RED on main: the path flows to preflight + download instead."
  - "Skip-if-present reachable for local files (evidence item 6, analysis P5): LlamacppWiringTest.stagedGgufSkipsWhenAlreadyInTheVolume (to-be-written, test_plan.adds) — a staged-path drive with the volume probe reporting PRESENT (the shim default) records NO cp and NO download invocation and prints the skip line — the item-6 workaround's effect is now the first-class flow."
  - "SHA parity (analysis P5, FAILURE-MODE): LlamacppWiringTest.stagedGgufShaMismatchFailsAndRemoves (to-be-written, test_plan.adds) — a staged file whose SHA prompt answer is non-empty is verified by the same sha256sum probe container as downloads, and a mismatch removes the file and fails the wizard (drive: path + a wrong SHA → rc non-zero, rm argv recorded, output names the mismatch); a blank SHA answer skips verification (the M1-417/M1-571 custom-override posture, preserved)."
  - "Prompt-time rejection (evidence item 4, FAILURE-MODE): LlamacppWiringTest.nonUrlNonFileAnswerFailsAtThePrompt (to-be-written, test_plan.adds) — a bare filename or any non-URL answer that is not an existing readable regular file aborts AT THE PROMPT: rc non-zero, curl-argv AND docker-argv logs absent or empty of fetch activity, and the message states the answer is neither a download URL nor an existing file and that paths must be absolute. No download attempt is ever made (the operator no longer learns after the fact)."
  - "Embeddings prompt parity + gate preserved (analysis P5): LlamacppWiringTest.stagedEmbeddingsGgufKeepsTheDimensionGate (to-be-written, test_plan.adds) asserts the embeddings GGUF prompt (:412) accepts the same local-path flow and the 768-dim confirmation (:414-422) still fires BEFORE staging — two drives: embeddings path + confirm 'yes' → staged; confirm 'no' → rc non-zero with no cp argv recorded."
  - "Contract-safe persistence from day one (analysis P6): the staged flow writes INFOCHAT_LLAMACPP_GGUF=<basename> and an EMPTY INFOCHAT_LLAMACPP_GGUF_URL via set_secret — NEVER the host path (asserted in the reproduction drive's secrets.env read; a drive that finds the path string anywhere in secrets.env fails). URL-entered flow byte-identical: LlamacppWiringTest.customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery stays green unmodified."
  - "M1-823 message end-state (analysis P2; AUTHORIZED modification, pre-authorized in M1-823's test_plan.preserves): the exit-3 preflight message gains a pointer to the local-path staging flow; LlamacppWiringTest.preflightFailsHardOnMalformedUrl gains ONE additive assertion for that pointer and keeps every M1-823 assertion unchanged — probe: the modified test passes with both assertion sets green."
  - "Twin lock-step (analysis P7): `grep -h 'docker run.*-fL -o' prod/scripts/4-llm.sh prod/scripts/restore.sh | sed 's/^[[:space:]]*//' | sort -u | wc -l` prints 1 (fetch_gguf's download line untouched); RestoreWiringTest stays green."
  - "Design record: docs/design/07-deployment.md §7.7.2's step-4 row states the GGUF override may be a download URL or an absolute local path staged into the model volume — probe: `grep -n 'local path' docs/design/07-deployment.md` hits the row."
  - "mvn verify from the repo root is green; bash -n prod/scripts/4-llm.sh passes."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — localGgufPathIsStagedIntoTheVolumeWithoutDownload (reproduction),
      stagedGgufSkipsWhenAlreadyInTheVolume (item 6), stagedGgufShaMismatchFailsAndRemoves
      (P5 failure-mode), nonUrlNonFileAnswerFailsAtThePrompt (item 4
      failure-mode), stagedEmbeddingsGgufKeepsTheDimensionGate (P5).
  modifies:
    - >-
      LlamacppWiringTest.preflightFailsHardOnMalformedUrl — AUTHORIZED
      (pre-authorized in M1-823's test_plan.preserves): adds ONE assertion
      that the exit-3 message points at the local-path staging flow; every
      M1-823 assertion is unchanged.
  preserves:
    - all tests currently green on main
    - >-
      fetch_gguf and every pin over it (oneShotDownloadContainersUseTheHostNetworkPath,
      fetchGgufWritesToTheVolumeComposeMounts, the M1-809 preflight suite),
      RestoreWiringTest (restore.sh untouched), and the fake-docker/fake-curl
      drive seams (extended only if the cp argv needs capture the current
      record-everything shim already provides).
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/deployment.md §Operator inputs
decision_refs: []
---

# M1-824: Stage operator-local GGUF files into the model volume

## Context

The wizard's GGUF prompts accept URLs only (prod/scripts/4-llm.sh:385, :412 —
every non-empty answer is treated as a URL), so an operator with a GGUF
already on disk gets a false WARN then a curl failure for a full path, or a
misleading VPN/proxy FAIL for a bare filename — and learns only after the
download attempt (evidence items 4/6, live 2026-08-11). The session
workaround (pre-stage into the volume via a root container, re-run so the
preflight WARNs through and `fetch_gguf` skips) is exactly the flow the
wizard should own. Shared analysis: `analysis_ref:`.

## Root cause

Two gaps, both verified: (1) no source-type classification at the prompt —
`:386-388` assign the raw answer to `$gen_url` unconditionally (`grep -n
'entrypoint cp\|/stage' prod/scripts/4-llm.sh` → nothing); (2) ordering — the
URL preflight (:437-440) precedes the only presence probe (:221 inside
`fetch_gguf`), so a local file can never reach skip-if-present without
passing a network check that cannot apply to it.

## Pitfalls

Numbered consistently with the analysis document.

- P2: this ticket owns the message end-state — the exit-3 text (M1-823) gains
  its staging pointer HERE, via the pre-authorized additive test modification
  (acceptance item 7).
- P3: hermeticity — drives create real temp files (the path must exist) but
  intercept every container and curl call; the record-everything fake-docker
  shim already captures the cp argv.
- P4: the staging copy is a system-boundary invocation (engineering-rules §7;
  M1-394 posture): argv-only, no shell, `-u 0:0`, pinned CURL_IMAGE, the
  operator's directory mounted READ-ONLY at /stage, everything quoted.
- P5: control parity — skip-if-present, optional-SHA semantics, SHA
  enforcement + mismatch-rm, and the embeddings 768-dim gate apply to staged
  files exactly as to downloads (§10: the staged path inherits the download
  path's incidental obligations).
- P6: the staged flow persists an EMPTY `INFOCHAT_LLAMACPP_GGUF_URL` from day
  one — a host path in that key breaks fresh-host restore (restore.sh:268-272
  would re-`fetch_gguf` it). Empty routes restore.sh to its pre-existing
  actionable fail-loud + manual recipe (:273-291). The URL-entered flow is
  byte-identical.
- P7: do NOT refactor `fetch_gguf` to share helpers — its restore.sh twin
  (restore.sh:226-252, sync obligation :51-58/:222-225) is batch A's lane.
  `stage_gguf` duplicates the probe/SHA block inline.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test + the P5/failure-mode drives — run RED on
     main (workflow §0).
  2. Add `stage_gguf()` to 4-llm.sh next to `fetch_gguf`: presence probe
     identical to :221 (skip-if-present), then an argv-only
     `docker run --rm -u 0:0 -v infochat-llamacpp-models:/models -v "<dir>":/stage:ro --entrypoint cp "$CURL_IMAGE" "/stage/<base>" "/models/<file>"`
     (P4), then the SHA block duplicated from :239-248 (P5/P7). ASSUMPTION
     for the implementor to verify live: the pinned `curlimages/curl:8.11.1`
     image ships `cp` (busybox — it already serves the ls/sha256sum/rm
     entrypoints at :221/:240/:244); if not, pick the minimal argv-only
     equivalent and record it in the commit message.
  3. Classify at the two prompts: empty → pinned default (unchanged);
     `https?://*` → the existing URL flow (unchanged); otherwise require an
     absolute path to an existing readable regular file → stage flow
     (`gen_file`/`emb_file` via the existing `gguf_basename`, the existing
     optional-SHA prompt, `gen_url`/`emb_url` set EMPTY); anything failing
     that check → hard FAIL at the prompt naming both accepted forms (the
     message, not a curl error, carries the cause). Update the prompt
     strings to name the local-path form.
  4. Skip `preflight_gguf_url` for staged sources at :437-440 (a local file
     has no network path to probe — the M1-809 same-path false-pass shape in
     reverse); call `stage_gguf` instead of `fetch_gguf` at the :445/:459
     sites for staged sources.
  5. M1-823's exit-3 message gains the staging pointer + the pre-authorized
     additive assertion (acceptance item 7).
  6. §7.7.2 step-4 row wording; `bash -n`; the lock-step probe (item 8);
     `mvn verify` from the repo root.
- **Controls to preserve (§10):** enumerated in the analysis §Controls to
  preserve — `fetch_gguf` untouched (P7), the preflight classes and call
  ordering for URL sources, the 768-dim gate (P5), `set_secret` escaping
  (M1-389/397), no new prompts (the staged flow reuses the existing answer +
  the existing SHA prompt — positional drives are unaffected), the M1-571
  persistence keys (P6).
- **Pitfall→mitigation:** P2→step 5 + item 7; P3→step 1; P4→step 2's argv
  shape + item 1's assertions; P5→steps 2/4 + items 2/3/5; P6→step 3's empty
  URL + item 6; P7→step 2's duplication + item 8.

## Definition of done

An absolute local GGUF path at either prompt is staged into
`infochat-llamacpp-models` with the same skip-if-present, SHA, and
embeddings-gate semantics as a download, bypassing the URL preflight;
non-URL/non-file answers fail at the prompt with an actionable message;
secrets.env never carries a host path in the re-fetch URL keys; the URL flow,
fetch mechanics, and restore twin are byte-identical; the §7.7.2 row records
the new form; mvn verify green.

## Verification

- P2 → item 7's additive assertion (mutation: dropping the staging pointer
  from the exit-3 text fails it).
- P3 → item 1's curl-argv emptiness assertion + the fake-docker argv capture
  (a real egress or unrecorded invocation fails loudly).
- P4 → item 1: the recorded cp argv carries `-u 0:0`, the read-only `/stage`
  mount, the pinned image, and no shell tokens (mutation: `--entrypoint sh`
  or a writable mount fails the assertions).
- P5 → items 2 (skip), 3 (mismatch → rm + fail; blank SHA → no verify), 5
  (gate before staging).
- P6 → item 6's secrets.env read (mutation: persisting the path fails it) +
  `customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery` green (URL
  flow intact).
- P7 → item 8's dedupe probe (mutation: editing fetch_gguf's download line
  prints 2) + RestoreWiringTest green.
- Item 4 (failure-mode) → LlamacppWiringTest.nonUrlNonFileAnswerFailsAtThePrompt
  feeds the classifier the hostile bare-filename input from the live session
  and asserts the prompt-time abort: a mutation that defers validation to the
  preflight fails the log-absence assertions.

## Out-of-scope

Named in `out_of_scope`: fetch_gguf/twin refactor (P7), restore.sh (batch A,
including the stale "predates M1-571" attribution), the disclosure print +
§7.10.1 (M1-825), relative-path acceptance (the enumerated rejected forms get
the prompt-time hard fail), 4b-image.sh. Pre-existing test modification:
exactly one — `preflightFailsHardOnMalformedUrl` gains one additive assertion
(authorized in both tickets' test_plan, engineering-rules §8).

## Census

Class: operator free-text GGUF source entry points (re-runnable:
`grep -n 'GGUF — paste\|GGUF_URL\|fetch_gguf\|stage_gguf' prod/scripts/*.sh`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh:385 (generative prompt) | FIXED (this ticket) |
| prod/scripts/4-llm.sh:412 (embeddings prompt) | FIXED (this ticket) |
| prod/scripts/4-llm.sh:437-440 (preflight calls) | FIXED (bypassed for staged sources) |
| prod/scripts/4-llm.sh:445/:459 (fetch calls) | FIXED (stage branch added alongside) |
| prod/scripts/restore.sh:260-294 (ensure_gguf) | out-of-scope: consumes persisted values, no prompt; empty-URL semantics already correct (batch A owns the message attribution) |
| prod/scripts/4-llm.sh:591-599 (remote base-url prompt) | out-of-scope: not a GGUF source |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-824-llm-wizard-robustness-2.md
```
