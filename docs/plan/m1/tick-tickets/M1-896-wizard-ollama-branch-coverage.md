---
id: M1-896
title: "Wizard wiring: drive the ollama branch and --defaults"
status: pending
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  Mutation probe (run 2026-08-20 by the driver on main @ 4b275e76, evidence
  not prediction; tree verified clean afterwards): change the --defaults arm
  of choose_reply_mode (prod/scripts/4-llm.sh:141) from
  `reply_mode="$rec"` to `reply_mode="native"`, then
  `./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest,RemoteLlmWiringTest'`
  — observed: Tests run: 45, Failures: 0, BUILD SUCCESS. The suite cannot
  see the wrong non-interactive write because no drive selects the ollama
  branch, the only branch accepting --defaults (llamacpp refuses it at
  4-llm.sh:507-510, remote at :708-711; grep-verified 2026-08-20: no test
  passes --defaults to any script, no drive answers backend=ollama).
  Closing tests (to-be-written; GREEN on unmodified main — they pin merged
  M1-895 behavior — RED under the named mutation, analysis P7):
  LlamacppWiringTest.ollamaDefaultsTakesAndEchoesTheReplyModeRecommendation
  and LlamacppWiringTest.ollamaBackendPullsProfileModelsAndWiresSharedDefaults.
analysis_ref: docs/plan/m1/tick-analysis/wizard-ollama-branch-coverage.md
blocked_by: []
files_scope:
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    prod/scripts/4-llm.sh and every other production file — this is a
    coverage gap, not a behavior defect; a defect spotted while writing the
    drives is a follow-up ticket, not a rider (§1, analysis P6).
  - >-
    RemoteLlmWiringTest.java — the seeded-model detail-print drive is the
    sibling ticket M1-897 (its drive needs the remote branch's free-text
    model prompt; the two tickets are independent).
  - >-
    A new OllamaWiringTest class — it would duplicate the fake-docker/curl
    harness; LlamacppWiringTest's drive layer already seeds a local-model
    (vps) profile and answers every compose call the ollama branch makes
    (analysis O4, rejected).
  - >-
    SwitchLlmWiringTest / prod/switch-llm.sh — a different script; its
    ollama re-route drives already exist.
  - >-
    docs/spec/** and docs/design/** — no promise changes: the D79 row
    (decisions.md:98) already carries the wizard sentence and §7.7.2
    documents the ollama branch (:797) and --defaults (:810).
acceptance:
  - "REPRODUCTION closed: LlamacppWiringTest.ollamaDefaultsTakesAndEchoesTheReplyModeRecommendation (to-be-written, test_plan.adds) — drive `bash prod/scripts/4-llm.sh --defaults` with EMPTY stdin over the helper's vps-profile fixture with a pre-seeded EMPTY runtime/secrets.env (the ollama branch's standalone-run guard, 4-llm.sh:463-466): rc 0; output contains `taking reply-mode recommendation for llama3.2:3b: translate`; the written application.properties carries `infochat.chat.reply-mode=translate` (exactly one line), `infochat.llm.default.base-url=http://ollama:11434/v1`, `infochat.llm.chat.model=llama3.2:3b`, and the vps timing recommendations 240000/600 + 240000/400. Non-vacuity (§8, analysis P3): the reproduction's mutation (`reply_mode=\"native\"` at :141) turns the props assertion RED — an echo-only assertion could not, because the echo prints `$rec` either way; the written props are the end-of-path assertion."
  - "Interactive ollama drive: LlamacppWiringTest.ollamaBackendPullsProfileModelsAndWiresSharedDefaults (to-be-written, test_plan.adds) — stdin `ollama\\n` + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT (analysis P1): rc 0; docker-argv.log records exactly one `up -d ollama` and exactly two `ollama pull` execs — `llama3.2:3b` once (vps security==chat, 4-llm.sh:431, deduped at :486-489) and `nomic-embed-text`; output prints `chat reply-mode recommendation for llama3.2:3b: translate (unmeasured)`; the written props carry the shared default base-url AND `infochat.embeddings.base-url` at `http://ollama:11434/v1`, `infochat.llm.security.model=llama3.2:3b`, `llama3.2:3b` on the six chat-model tasks, `infochat.embeddings.model=nomic-embed-text`, `infochat.embeddings.dimension=768`, and `infochat.chat.reply-mode=translate`."
  - "FAILURE-MODE: LlamacppWiringTest.ollamaBackendOnRemoteLlmProfileRefuses (to-be-written, test_plan.adds) — pre-seed `quarkus.profile=remote-llm` in the runtime application.properties, answer `ollama` at the backend prompt: rc non-zero, output names the mismatch (`has no local models`), and NO `infochat.chat.reply-mode` line is written — the refusal fires at 4-llm.sh:453-456 before any pull or write (a branch that proceeded would `ollama pull \"\"` and mis-wire the deployment)."
  - "Drive-layer discipline (analysis P5): the ONLY change to pre-existing test code is (a) an ADDITIVE argv-accepting overload of runWizardCapture (the --defaults drive needs script argv; the existing ProcessBuilder at LlamacppWiringTest.java:1064 passes none — the M1-827 'authorized drive-layer addition' precedent) and (b) one class-javadoc sentence stating the ollama-branch coverage (§11 — the comment must state current truth about the class). Every pre-existing drive's stdin and assertions and the fake docker/curl script strings are byte-untouched. Verify: `git diff infochat-llm-adapter/src/test` shows no hunk inside any existing method body or fake-script string, and `git diff prod/ docs/` is empty."
  - "`./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest,RemoteLlmWiringTest'` is green AND `mvn verify` from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      LlamacppWiringTest.ollamaDefaultsTakesAndEchoesTheReplyModeRecommendation
      (reproduction closure — the --defaults arm), .ollamaBackendPullsProfileModelsAndWiresSharedDefaults
      (interactive ollama branch), .ollamaBackendOnRemoteLlmProfileRefuses
      (failure-mode), plus the additive argv-accepting runWizardCapture
      overload.
  preserves:
    - all tests currently green on main
    - >-
      Every pre-existing drive in both wiring classes — stdin strings,
      assertions, and the fake docker/curl scripts byte-untouched (the
      ollama branch needs no fake change: compose up/exec exit 0, the
      readiness until-loop passes on its first pass, the branch makes no
      curl call).
  modifies:
    - >-
      LlamacppWiringTest class javadoc only — one sentence extending the
      stated coverage to the ollama backend branch (authorized here: the
      class gains ollama drives, and §11 requires the comment to state
      current truth; no assertion or fixture changes).
  notes:
    - >-
      Coverage-ticket RED semantics (analysis P7): the new tests are
      expected GREEN on unmodified main at `start`; the RED evidence is the
      reproduction's mutation probe. The workflow §0 demonstration is:
      re-apply mutation B (reply_mode=\"native\" at 4-llm.sh:141), watch
      item 1's test go RED, revert.
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/decisions.md §Decisions log
decision_refs:
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates: M1-895
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-896: Wizard wiring: drive the ollama branch and --defaults

## Context

M1-895 (merged 2026-08-20 as 4b275e76) added the D79 chat reply-mode ask to
`prod/scripts/4-llm.sh`, including a `--defaults` arm
(`choose_reply_mode`, 4-llm.sh:140-144) that takes the per-model
recommendation non-interactively and echoes it. `--defaults` is accepted
only by the ollama branch — llamacpp refuses it (:507-510), remote refuses
it (:708-711) — and **no wiring drive selects the ollama branch at all**
(grep-verified 2026-08-20: every `ollama` hit in LlamacppWiringTest is the
llamacpp branch's embeddings-backend sub-choice; no test passes `--defaults`
to any script). The mutation probe in `reproduction:` proves the hole: the
arm can write the wrong value with the suite fully green. Coverage
extension of merged behavior, not a defect in it — M1-895's review filed
exactly this observation as a RECOMMENDED-NEW-TICKET. Shared analysis:
`analysis_ref:`.

## Root cause

The drive-the-real-script harness (M1-553's origin: fake docker/curl,
positional stdin, Linux-gated) grew with the llamacpp and remote branches;
the ollama branch — the script's DEFAULT backend (:35) — never got a drive,
so anything reachable only through it (today: the `--defaults` arm) is
verified by read alone. Verified mechanics the drives rely on: the ollama
branch requires a local-model profile (remote-llm refused, :453-456) and a
pre-existing secrets.env (:463-466); the fake docker exits 0 on every
compose call so the readiness until-loop (:476-482) passes on its first
pass; the branch makes no curl call; `clear_remote_llm_creds` (:223-232) is
pure `sed`, safe on an empty secrets.env under `set -e`. The vps profile
(:431) makes the expected values deterministic: chat/security model
`llama3.2:3b`, embedding `nomic-embed-text`, timing 240000/600 +
240000/400, reply-mode recommendation `translate (unmeasured)`.

## Pitfalls

Numbered consistently with the analysis document; this ticket carries
P1-P3, P5-P7 (P4's placement half and P8 belong to M1-897).

- P1: positional-stdin discipline (M1-553/M1-826/M1-895's P1;
  LlamacppWiringTest.java:98-105) — the interactive drive feeds exactly
  `ollama\n` + 4 timing Enters + 1 reply-mode Enter; the `--defaults` drive
  feeds EMPTY stdin (nothing reads under `defaults=1`: :123-125, :438-441,
  :140-144). A mis-fed drive dies at EOF under `set -e`.
- P2: the ollama branch's secrets.env precondition (:463-466) — the helper
  never creates secrets.env (the llamacpp branch mints it), so both ollama
  drives pre-seed an EMPTY `runtime/secrets.env` (the
  `switchingAwayFromRemoteToLlamacppClearsStaleRemoteApiKeys` precedent,
  LlamacppWiringTest.java:745-752) or they fail at the step-2 pointer guard
  instead of exercising the branch.
- P3: boundary siting (§8 Assertion adequacy) — mutation B changes only the
  assignment; the echo prints `$rec` regardless. The written-props
  assertion is the end-of-path check that turns RED under it; the echo
  assertion pins the non-interactive disclosure but cannot stand alone.
- P5: no fake-docker/curl change (the ollama branch needs none) — the only
  drive-layer edit is the ADDITIVE argv overload; a "hardened" fake that
  fails `exec` would burn WAIT_TIMEOUT=120s per drive (:46).
- P6: test-only scope (§1/§12) — no production or spec edit rides; a defect
  spotted while driving is a follow-up ticket.
- P7: coverage-ticket RED semantics — the new tests PASS on unmodified
  main; the RED evidence is the reproduction's mutation probe, re-applied
  at `start` (test_plan.notes).

## Approach

Derived from `spec_refs`: §7.7.2's step-4 row (:797) commits the wizard to
the ollama branch (start the service, pull the profile's models) and its
behavior contract (:810) commits to the `--defaults` non-interactive hatch;
the D79 row (decisions.md:98) commits the recommendation rule the arm
applies. The drives pin that existing contract.

- **Files to touch:** `files_scope` — one test class.
- **Steps, in order:**
  1. Add the additive `runWizardCapture` overload taking script argv
     (delegate the existing 3-arg form to it; P5) and the class-javadoc
     sentence (acceptance item 4).
  2. Write the three drives (acceptance items 1-3), each pre-seeding an
     empty secrets.env where the branch requires it (P2). Run them GREEN on
     unmodified main, then demonstrate RED: re-apply mutation B
     (`reply_mode="native"` at 4-llm.sh:141), watch item 1 fail, revert
     (P7 — capture both runs to .scratch and cite in the commit message).
  3. The acceptance-item-4 git-diff probes; the two mvn runs (item 5).
- **Controls to preserve (§10):** no path rerouted — additive test code.
  Every pre-existing drive's stdin, assertions, and the fake docker/curl
  script strings byte-untouched; no production/spec/doc file touched; no
  new `infochat.*` key written (DocumentedConfigKeyParityTest unaffected).
- **Pitfall→mitigation:** P1→step 2's exact stdin strings (and the empty
  stdin under `--defaults`); P2→step 2's pre-seed; P3→item 1's props
  assertion as the named end-of-path check; P5→step 1's additive-only
  overload + item 4's probes; P6→item 4's empty-`git diff prod/ docs/`
  probe; P7→step 2's RED demonstration.

Alternatives considered (rejected; analysis §Solution options): bundling the
seeded-model drive here (O2 — independent seam, sibling M1-897); a model
override env to reach the seeded model via ollama (O3 — a production seam
for a test's sake); a new OllamaWiringTest class (O4 — harness
duplication).

## Definition of done

The `--defaults` drive passes: empty stdin, rc 0, the echo names
`llama3.2:3b: translate`, and the written props carry
`infochat.chat.reply-mode=translate` plus the ollama URL and vps models/
timing — RED under the reproduction's mutation. The interactive ollama
drive passes, pinning the pull set, the shared-default + embeddings URLs,
the per-task models, dimension 768, and the printed
`translate (unmeasured)` recommendation. The remote-llm refusal drive
fails loud with nothing written. No pre-existing test code changes beyond
the additive overload and the javadoc sentence; `git diff prod/ docs/` is
empty; the targeted module run and repo-root `mvn verify` are green.

## Verification

- P1 → the drives themselves: a mis-fed drive dies at EOF with rc != 0,
  failing loudly; the `--defaults` drive's empty stdin is structural (any
  added `read` under `--defaults` hangs/fails it).
- P2 → items 1-2 pass only with the pre-seeded secrets.env; the guard's
  text would otherwise be the failure output.
- P3 → item 1's non-vacuity clause: mutation B reds the props assertion;
  the commit message cites the RED capture (P7).
- P5 → item 4's git-diff probes (no hunk in the fake-script strings, no
  production hunk).
- P6 → item 4's `git diff prod/ docs/` empty probe.
- P7 → test_plan.notes' mutation re-application at `start`.
- Failure-mode (mandatory class) → item 3: feeds the hostile edge (a local
  backend on the no-local-models profile) and asserts loud refusal + no
  reply-mode line.
- acceptance item 5 → the named mvn runs.

## Out-of-scope

Named in `out_of_scope`: any production edit (coverage gap, not a behavior
defect — §1); RemoteLlmWiringTest (sibling M1-897's file — the two tickets
touch disjoint files so neither invalidates the other's pins); a new
OllamaWiringTest class (harness duplication); switch-llm.sh and its test;
all spec/design docs (the D79 row and §7.7.2 already carry the text).
Pre-existing test modification, authorized per §8 and bounded by acceptance
item 4: ONLY the additive `runWizardCapture` overload (new method, existing
form delegates unchanged) and one class-javadoc sentence — no existing
method body, stdin string, assertion, or fake-script string changes.

## Census

Class: `4-llm.sh` backend branches and their wiring-drive coverage
(re-runnable: `grep -n '^  ollama)\|^  llamacpp)\|^  remote)' prod/scripts/4-llm.sh`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh:450 `ollama)` | FIXED (this ticket — interactive + --defaults + refusal drives) |
| prod/scripts/4-llm.sh:502 `llamacpp)` | already covered (LlamacppWiringTest's existing drives) — untouched |
| prod/scripts/4-llm.sh:707 `remote)` | already covered (RemoteLlmWiringTest) — the seeded-model detail line reachable through it is sibling M1-897 |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-896-wizard-ollama-branch-coverage.md
```
