---
id: M1-828
title: "Document post-setup tool boundaries (profile vs switch-llm vs wizard)"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probe (wizard usage text and guides are not mvn-covered):
  `./prod/scripts/1-profile.sh --help` prints usage (1-profile.sh:13-19)
  with no statement that a profile change does not re-route the LLM, and
  `grep -n 'ordinal' SETUP_GUIDE.md` prints nothing on main — the only
  profile-change guidance is SETUP_GUIDE.md:278 "You can change it later"
  with no verb and no consequence. Observed wrong behavior (live session
  2026-08-11, .scratch/setup-hurdles.md item 8): an operator who re-runs
  1-profile.sh to move the LLM gets no warning that routing is untouched
  because the runtime config (ordinal 260, docker-compose.yml:128-135)
  beats the baked %profile defaults (250).
analysis_ref: docs/plan/m1/tick-analysis/operator-ux.md
blocked_by: []
files_scope:
  - SETUP_GUIDE.md
  - ADMIN_GUIDE.md
  - prod/scripts/1-profile.sh
  - prod/scripts/4-llm.sh
  - prod/switch-llm.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any behavior or flow change to any script — usage()/header-comment
    text only; no new flags, no prompt changes.
  - prod/scripts/4b-image.sh and its picker output (sibling M1-829's lane).
  - 4-llm.sh GGUF download / GPU-overlay logic (batch B).
  - Any docs/spec/** edit — this ticket records behavior the spec already
    promises; no amendment needed.
  - switch-llm.sh code below the header comment (the consent gate, privacy
    disclosure, backup/rollback, Phase 5 service bring-up are untouched).
acceptance:
  - "REPRODUCTION, now passing: `grep -n 'ordinal' SETUP_GUIDE.md` hits the new boundary section, and the section states the verified mechanism (records docs/spec/deployment.md §Operator inputs item 1 — the profile is the operator's sizing input, and this is what changing it does and does not reach): the runtime prod/runtime/application.properties is read at config ordinal 260, above the image-baked %profile defaults (250), and carries every infochat.llm.*/infochat.embeddings.* routing key — so re-running 1-profile.sh never re-routes the LLM, while keys the runtime file does NOT carry still follow the baked profile (P3: both halves of the qualifier present)."
  - "Verb table (P4): SETUP_GUIDE.md gains a 'which tool for which change' table mapping at minimum: change hardware profile -> re-run prod/scripts/1-profile.sh (LLM routing untouched); re-route the generative LLM post-setup -> prod/switch-llm.sh (rewrites the docs/spec/decisions.md D56 shared-default config, does NOT pull models); pull/set local models or first-time backend provisioning -> re-run prod/scripts/4-llm.sh; enable/switch/disable /image -> re-run prod/scripts/4b-image.sh. Probe: `grep -n 'switch-llm' SETUP_GUIDE.md` hits the table row and the row (or its immediate paragraph) states the no-model-pull boundary (FAILURE-MODE: a table that claims or implies switch-llm.sh pulls models fails this item — it defers to 4-llm.sh, switch-llm.sh:381)."
  - "Embeddings lock stated accurately (P8; records docs/spec/decisions.md D54 — embeddings frozen on a local 768-dim nomic backend for the deployment's life): the boundary section states embeddings are locked to a 768-dim nomic-class embedder, names infochat.embeddings.allow-model-change=false and the Collector startup refusal, AND notes the one guarded exception (a custom embeddings model via 4-llm.sh must produce 768-dimensional vectors). Probes: `grep -n 'allow-model-change' SETUP_GUIDE.md` and `grep -n '768' SETUP_GUIDE.md` hit the section."
  - "Key-name discipline (P5): every infochat.* key the new text names is a real key; the profile key is written as quarkus.profile — probe: `grep -n 'infochat\\.profile' SETUP_GUIDE.md ADMIN_GUIDE.md` prints nothing; mvn verify from repo root is green (DocumentedConfigKeyParityTest gates documented keys)."
  - "Usage pointers at the point of need (P4): `./prod/scripts/1-profile.sh --help` output gains one line stating that the profile does not re-route the LLM and naming switch-llm.sh as the re-route verb; `./prod/scripts/4-llm.sh --help` gains one line naming switch-llm.sh as the post-setup re-route verb. Probes: run both with --help and grep the output; `bash -n prod/scripts/1-profile.sh prod/scripts/4-llm.sh` passes."
  - "switch-llm.sh header comment gains the no-pull boundary (it rewrites config; model pulls are 4-llm.sh's job) — probe: `grep -n -i 'pull' prod/switch-llm.sh` hits the header comment in addition to the existing :381 note. No other line of switch-llm.sh changes."
  - "ADMIN_GUIDE.md gains a one-line pointer (Advanced > Upgrading the bot or Where to go next) sending host-level reconfiguration questions to the SETUP_GUIDE.md boundary section — probe: `grep -n -i 'switch-llm\\|re-run.*profile\\|which tool' ADMIN_GUIDE.md` hits."
  - "FAILURE-MODE behavioral proof of the central doc claim: seed a temp INFOCHAT_RUNTIME_DIR with an application.properties containing infochat.llm.default.base-url and infochat.embeddings.base-url lines, run `prod/scripts/1-profile.sh --defaults` against it, and assert every infochat.llm.*/infochat.embeddings.* line survives byte-identical — runnable shell probe, output shown in the commit evidence."
  - "mvn verify from repo root is green (docs/shell-text-only diff; proves no drift)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/decisions.md (D27, D54, D56)
decision_refs:
  - D27
  - D54
  - D56
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-828: Document post-setup tool boundaries (profile vs switch-llm vs wizard)

## Context

Live restore session 2026-08-11 (`.scratch/setup-hurdles.md` item 8): the
boundaries between the three post-setup configuration verbs —
`prod/scripts/1-profile.sh`, `prod/switch-llm.sh`, and re-running wizard
step 4 (`prod/scripts/4-llm.sh`) — are only discoverable by reading script
headers. Three facts nothing surfaces at the point of need: a profile
change alone does NOT re-route the LLM (runtime config ordinal 260 beats
baked `%profile` defaults at 250); switch-llm.sh rewrites config but does
NOT pull models (it defers to 4-llm.sh); embeddings are locked to a
768-dim nomic embedder regardless. Shared analysis: `analysis_ref:`.

## Root cause

Documentation/usage-output gap, verified. The facts exist only as code
comments (docker-compose.yml:128-135 for the ordinals; switch-llm.sh:19-21
for the embeddings lock) and design-doc prose
(docs/design/07-deployment.md:823). SETUP_GUIDE.md:278 says "You can
change it later" about the profile with no verb and no consequence;
`grep -n ordinal SETUP_GUIDE.md` → no match; `1-profile.sh --help`
(1-profile.sh:13-19) and `4-llm.sh --help` (4-llm.sh:76-82) state no
boundary; switch-llm.sh has no usage() at all. Mechanism, verified:
1-profile.sh writes ONLY `quarkus.profile` (1-profile.sh:52-57); every
`infochat.llm.*` / `infochat.embeddings.*` routing key lives in the
runtime file (4-llm.sh:140-141, :354-358, :502-504, :661-678), which
Quarkus reads at ordinal 260 above the baked 250 profile defaults
(docker-compose.yml:128-135). switch-llm.sh contains no pull
(grep: `ollama pull`/`fetch_gguf` hit 4-llm.sh only) and defers at
:381. The embeddings lock is `allow-model-change=false`
(infochat-collector/src/main/resources/application.properties:650) +
`vector(768)` (V11__post_embedding.sql:124) + D54.

## Pitfalls

Numbered consistently with the analysis document.

- P3: docs overclaim — runtime config beats baked `%profile` defaults ONLY
  for keys the runtime file carries; a profile re-run still re-tunes baked
  per-profile defaults the wizard never wrote. "The profile no longer
  matters" would be false.
- P4: verb-table inaccuracy/drift — switch-llm.sh does NOT pull models
  (defers to 4-llm.sh); 4-llm.sh is the provisioning/pull verb,
  switch-llm.sh the re-route verb. One canonical table in SETUP_GUIDE.md;
  script usage lines carry ONE pointer sentence each, not re-explanations
  (§11 drift).
- P5: DocumentedConfigKeyParityTest (§8, M1-708) — every documented
  `infochat.*` key must be real; the profile key is `quarkus.profile`,
  never the invented `infochat.profile`.
- P6: sibling calibration — this ticket's probes must not pin 4b-image.sh
  picker text (M1-829 rewrites it); disjoint surfaces.
- P8: the embeddings line states the lock AND its one guarded exception
  (custom 768-dim embeddings model via 4-llm.sh, 4-llm.sh:411-415) — not
  "embeddings can never be touched".

## Approach

- **Files to touch:** `files_scope` (two guides; usage()/header text of
  three scripts; zero behavior).
- **Steps, in order:**
  1. SETUP_GUIDE.md: add the boundary section + verb table near the
     switcher section (:638) / script table (:788) — the canonical
     statement, both ordinal-qualifier halves (P3), the embeddings lock
     with its guarded exception (P8), real key names only (P5).
  2. `1-profile.sh` usage(): one pointer line (profile ≠ LLM re-route;
     verb is switch-llm.sh). `4-llm.sh` usage(): one pointer line
     (post-setup re-route is switch-llm.sh). Echo-only edits.
  3. switch-llm.sh header comment: add the no-pull boundary sentence.
     Nothing below the header changes.
  4. ADMIN_GUIDE.md: one pointer line to the new section.
  5. Run every probe in `acceptance`, including the failure-mode re-run
     probe.
- **Controls to preserve (§10):** no code path rerouted; switch-llm.sh's
  consent gate (:301-308), privacy disclosure (:409-432), and
  backup/rollback (:311-327) untouched — the only switch-llm.sh edit is a
  header comment. Existing `--help`/exit-2 behavior of the two wizard
  scripts unchanged.
- **Pitfall→mitigation:** P3→step 1 + item 1; P4→steps 1-3 + items 2/5/6;
  P5→item 4; P6→Out-of-scope + item scoping; P8→item 3.

## Definition of done

The boundary section and verb table exist in SETUP_GUIDE.md with the
accurate ordinal mechanism, the no-pull boundary, and the embeddings lock
with its guarded exception; both wizard `--help` outputs and the
switch-llm.sh header carry their one-line pointers; ADMIN_GUIDE.md points
at the section; the failure-mode re-run probe proves the central claim;
all key names pass the parity guard; mvn verify green.

## Verification

- P3 → acceptance item 1's grep probes (both qualifier halves present).
- P4 → items 2/5/6: `--help` drives + header grep (FAILURE-MODE: a
  pull-claim for switch-llm.sh fails item 2).
- P5 → item 4: `grep -n 'infochat\.profile' ...` prints nothing; mvn
  verify green (DocumentedConfigKeyParityTest).
- P6 → Out-of-scope: no probe in this ticket asserts 4b-image.sh picker
  text.
- P8 → item 3's grep probes.
- Central-claim failure-mode → item 8: seeded-runtime re-run of
  1-profile.sh leaves every `infochat.llm.*`/`infochat.embeddings.*` line
  byte-identical (a profile re-run that touched routing would falsify the
  doc this ticket ships).

## Out-of-scope

Named in `out_of_scope`: any behavior/flow change (text-only);
4b-image.sh (M1-829); 4-llm.sh GGUF/GPU logic (batch B); spec edits (the
spec already promises this behavior — `spec_refs:` are the basis, no
amendment); switch-llm.sh below the header. No pre-existing test is
modified.

## Census

This ticket fixes the same-shaped gap — a post-setup change verb whose
boundary facts live only in its script header — at more than one site.
Class enumeration, re-runnable:

```bash
grep -ln 'RUNTIME_DIR/application\.properties' prod/scripts/*.sh prod/*.sh
```

Every returned path, disposed:

| Site | Disposition |
|---|---|
| `prod/scripts/1-profile.sh` | FIX — usage() boundary line (item 5); the profile-vs-routing fact is this ticket's core |
| `prod/scripts/4-llm.sh` | FIX — usage() pointer to switch-llm.sh (item 5); the provisioning/pull verb row of the table (item 2) |
| `prod/switch-llm.sh` | FIX — header comment gains the no-pull boundary (item 6); the re-route verb row of the table (item 2) |
| `prod/scripts/4b-image.sh` | FIX via the verb-table row (item 2 covers "enable/switch/disable /image → re-run 4b-image.sh"); its re-run semantics are already documented (SETUP_GUIDE.md:407-411) and its picker TEXT is M1-829's lane, untouched here |
| `prod/scripts/5-bootstrap.sh`, `prod/scripts/6-adapter.sh`, `prod/scripts/6b-simplex-provision.sh` | COVERED, no gap — single-purpose wizard steps already documented in the SETUP_GUIDE.md script table (:788-800) and the "Running a single step" section (:769-786); no overlapping verb exists for their function, so there is no boundary to disambiguate |
| `prod/scripts/8-verify.sh` | OUT-OF-SCOPE — read-only health check; it changes no config, so it is not a change verb an operator chooses between |
| `prod/setup.sh`, `prod/scripts/restore.sh`, `prod/scripts/upgrade.sh`, `prod/scripts/pack.sh` | OUT-OF-SCOPE — orchestration/lifecycle verbs with their own dedicated guide sections (setup/resume :760-767, restore/migration, upgrade :811-814); they do not overlap the config-change verbs item 8 is about |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-828-operator-ux-1.md
```
