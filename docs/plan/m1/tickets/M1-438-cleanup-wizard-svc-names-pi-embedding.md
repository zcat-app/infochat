---
id: M1-438
title: "cleanup: fix switch-llm recreate service names + pi-profile embedding model in the wizard"
status: pending
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 3
files_scope:
  - prod/switch-llm.sh
  - prod/scripts/4-llm.sh
  - SETUP_GUIDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Per-profile embedding dimensions / the all-minilm:33m 384-d embedder remain deferred beyond v1 (design §5.5, D49). This ticket only removes the pi mismatch by aligning the wizard to the shipped 768-d nomic config; it does NOT introduce profile-specific dimensions."
  - "The up-command shape stays a single combined `up -d infochat-collector infochat-provider`: the provider depends_on the collector with condition service_healthy (docker-compose.yml:142-145), so compose preserves Flyway-first ordering without splitting into two commands."
  - "scope_preferences.digest_enabled is NOT touched — the previously-suspected dead column was already dropped in V50 (V50__banned_admin_actor_checks.sql:195); no migration is needed and none is added."
  - "No automated test: prod/ shell scripts are not under mvn verify and there is no bats harness (precedent M1-418). Verification is by reading the emitted command string and the written property key/value."
acceptance:
  - "prod/switch-llm.sh:304 prints the recreate hint with the real compose service names `infochat-collector infochat-provider` (replacing `collector provider`), matching docker-compose.yml:57,103 and the names used in prod/scripts/7-apps.sh:68,72."
  - "SETUP_GUIDE.md:372 (the post-switch apply command) uses `infochat-collector infochat-provider`."
  - "prod/scripts/4-llm.sh pi profile sets embedding_model=\"nomic-embed-text\" (line ~206), matching the laptop/vps rows and the shipped infochat.embeddings.model=nomic-embed-text / dimension=768 / allow-model-change=false, so the wizard no longer writes a model that trips EmbeddingMetadataStartupGuard on a pi install."
  - "No other property writes change: the profile branch still writes only infochat.embeddings.model (4-llm.sh:259), leaving dimension at the global 768 (EMBEDDINGS_DIMENSION, 4-llm.sh:63)."
  - "mvn -B clean verify from the repo root exits 0 (no Java/migration change; confirms no regression)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §Switching profiles
decision_refs:
  - D49
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date:
  verdict:
  warnings: []
  blockers: []
---

# M1-438: wizard cleanup — recreate service names + pi embedding model

## Context

Two independent, low-risk wizard defects found by the 2026-06-23
documentation-vs-code audit, bundled into one cleanup ticket (precedent:
M1-434, M1-412) to avoid two tiny tickets. They are unrelated and may be
split per-file without loss.

1. **Wrong compose service names in the switch-llm apply step.**
   `prod/switch-llm.sh:304` and the copy of it in `SETUP_GUIDE.md:372`
   tell the operator to run `… up -d collector provider`. The real
   services are `infochat-collector` / `infochat-provider`
   (`docker-compose.yml:57,103`; there are no aliases). The command fails
   with "no such service: collector." `prod/scripts/7-apps.sh:68,72`
   already uses the correct names.

2. **pi profile writes an embedding model that breaks startup.**
   `prod/scripts/4-llm.sh:206` sets the pi `embedding_model` to
   `all-minilm:33m` and writes it as `infochat.embeddings.model`
   (`:259`). But the collector ships `nomic-embed-text` / `dimension=768`
   / `allow-model-change=false`
   (`infochat-collector/.../application.properties:480-486`), and
   `EmbeddingMetadataStartupGuard` throws a fatal
   `EmbeddingModelMismatchException` on the mismatch. A pi install
   following the documented wizard aborts at Collector startup.
   `SETUP_GUIDE.md:384` already (correctly, per design) says the pi runs
   the 768-d embedder — only the wizard disagrees. Per design §5.5 and
   D49, v1 ships 768-d nomic on **every** profile; per-profile dims are
   deferred. The fix aligns the wizard's pi row to the shipped config.

A third audit item — the dead `scope_preferences.digest_enabled` column —
was investigated and found **already fixed** (dropped in
`V50__banned_admin_actor_checks.sql:195`), so it is explicitly excluded.

## Acceptance

See frontmatter. Item 1 is a string fix in two files; item 2 is a
one-token fix in the wizard's pi case. No Java, no migration, no test
adds.

## Out-of-scope

See frontmatter. No per-profile embedding dimensions, no up-command
restructure, no migration.

## Notes

- **Source map (verified 2026-06-23):**
  - `prod/switch-llm.sh:304` — printed recreate hint, `up -d collector provider`.
  - `SETUP_GUIDE.md:372` — operator-facing copy of the same command.
  - `prod/scripts/4-llm.sh:206` — pi case, `embedding_model="all-minilm:33m"`.
  - `prod/scripts/4-llm.sh:259` — profile branch writes only
    `infochat.embeddings.model`; `:63` `EMBEDDINGS_DIMENSION=768` is the
    global, written only on the custom path (`:367`), so dimension stays
    768 for the pi profile after the fix.
- **security_relevant: false** — operational/setup correctness only; no
  documented security property is touched.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-438-cleanup-wizard-svc-names-pi-embedding.md
```
