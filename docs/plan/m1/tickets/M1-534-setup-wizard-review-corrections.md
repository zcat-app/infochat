---
id: M1-534
title: "Setup-wizard dry-walkthrough review corrections (flaws.md F1/F4/F7/F8/F9/F10/F14/F16/F17/F19)"
status: done
created: 2026-06-30
last_updated: 2026-06-30
blocked_by: []
files_budget: 12
files_scope:
  - prod/setup.sh
  - prod/scripts/1-profile.sh
  - prod/scripts/2-secrets.sh
  - prod/scripts/3-postgres.sh
  - prod/scripts/4-llm.sh
  - prod/scripts/5-bootstrap.sh
  - prod/scripts/6-adapter.sh
  - prod/scripts/6b-simplex-provision.sh
  - prod/scripts/7-apps.sh
  - prod/scripts/8-verify.sh
  - SETUP_GUIDE.md
  - prod/config/secrets.env.example
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The five deferred findings, which have their own tickets: F11 remote embeddings (M1-529), F15 stale admin secrets (M1-530), F13 directory-as-path (M1-531), F12 custom-JSON syntax gate (M1-532), F18 6b error-marker (M1-533). This ticket lands ONLY the corrections already verified safe against the wizard wiring tests."
  - "F3 (profile axis conflation) — a v2 spec amendment, out of M1 scope."
  - "Any change to Java application code, the embedding/adapter contracts, or the compose file. This batch is shell scripts + operator-facing docs + the secrets.env template only."
acceptance:
  - >-
    1-profile.sh (F4): the hardware-profile prompt prints a 4-line explanation
    grouping local (laptop/vps/pi) vs cloud (remote-llm) and pointing to step 4.
  - >-
    2-secrets.sh (F8): the premature remote-LLM-API-key prompt is removed (the key
    is captured in step 4 only); the orphaned dotenv_escape helper and defaults var
    are removed; --defaults is an accepted no-op.
  - >-
    4-llm.sh (F7/F8/F10): a blank API key for the remote backend hard-fails; the
    "set at step 2" message is corrected to "already recorded"; the llamacpp branch
    rejects the remote-llm profile symmetrically with the ollama branch; the
    secrets.env standalone-run guard sits INSIDE the ollama branch (not top-level —
    the llamacpp/remote branches mint secrets.env themselves; a top-level guard
    broke LlamacppWiringTest).
  - >-
    3-postgres.sh / 7-apps.sh / 8-verify.sh / 6b-simplex-provision.sh (F9/F17):
    each fails with a "run step 2 first" pointer when secrets.env is missing,
    instead of an opaque compose error (the 6b guard sits after the simplex-enabled
    gate).
  - >-
    5-bootstrap.sh (F14): the custom sources/assets path prompts state
    absolute-vs-relative resolution and name the bundled file as a copy-and-edit
    example.
  - >-
    6-adapter.sh (F16): the adapter-selection prompt states per-adapter
    prerequisites — simplex needs nothing; signal needs a DEDICATED pre-registered
    + verified number (not a personal Signal number; one account per number), which
    the wizard cannot create — plus how to add/change an adapter later (re-run vs
    hand-edit which file + restart).
  - >-
    setup.sh (F1/F19): the stale 7-/8-scripts-not-landed comment is removed; the
    closing handoff instructs SimpleX operators to DM their claim-token first to
    become admin, then blank INFOCHAT_SIMPLEX_ADMIN_TOKEN (the env var, in
    secrets.env) and restart.
  - >-
    SETUP_GUIDE.md / secrets.env.example: kept in lock-step with the above (step-2
    key prompt removed from the asks-table + example + script-table; remote-key
    location clarified; Signal dedicated-number requirement made explicit; an
    Advanced-but-unsupported "link instead of register" alternative documented).
  - >-
    The five wizard wiring tests that drive these scripts pass unchanged:
    LlamacppWiringTest, SwitchLlmWiringTest, SimpleXProvisioningWiringTest,
    DoctorWiringTest, AdapterAdminPromptWiringTest. `bash -n` is clean on every
    edited script.
test_plan:
  adds: []
  preserves:
    - "infochat-llm-adapter/.../wiring/LlamacppWiringTest.java"
    - "infochat-llm-adapter/.../wiring/SwitchLlmWiringTest.java"
    - "infochat-messaging-adapter/.../simplex/SimpleXProvisioningWiringTest.java"
    - "infochat-provider/.../wiring/DoctorWiringTest.java"
    - "infochat-provider/.../wiring/AdapterAdminPromptWiringTest.java"
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
  - "docs/spec/commands.md §Asset commands"
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: ""
  verdict: ""
  warnings: []
  blockers: []
---

# M1-534: Setup-wizard dry-walkthrough review corrections

## Context

A guided dry-walkthrough of `prod/setup.sh` and every step subscript (an
interactive operator-perspective review, NOT the standard clarity→implement→review
cycle) surfaced 19 findings, logged in `flaws.md`. Nine were corrected in place and
verified against the wizard wiring tests; this ticket records and lands that batch.
The corrections passed an adversarial falsification pass that caught — and fixed —
two bugs in the corrections themselves (F17's secrets-guard was breaking
LlamacppWiringTest until moved into the ollama branch; F19's handoff named the
property-key instead of the env-var).

## Acceptance

See the YAML `acceptance:` list — one item per corrected finding, each tied to its
flaws.md ID.

## Out-of-scope

See the YAML `out_of_scope:` list. The five deferred findings have their own
tickets (M1-529..M1-533); F3 is a v2 spec amendment; no Java application code is
touched.

## Notes

- **Provenance / process honesty:** authored during an interactive review, not the
  standard ticket flow — hence empty `reviews:`/`clarity_check:`. Verification was
  the five wizard wiring tests (which drive the real scripts via ProcessBuilder),
  all green, plus `bash -n` on every edited script. A full-reactor `mvn verify` was
  NOT run: the diff is exclusively shell scripts (covered by the wiring tests),
  operator-facing Markdown, and the `secrets.env` template — no Java/application
  code changed, so the rest of the suite is provably unaffected.
- The detailed per-finding verdict table and log live in
  `docs/plan/m1/flaws.md` (originally at the repo root; relocated 2026-07-22).
