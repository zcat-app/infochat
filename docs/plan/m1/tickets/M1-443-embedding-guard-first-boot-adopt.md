---
id: M1-443
title: "Embedding identity guard must adopt the configured model on first boot (no embeddings yet) instead of refusing"
status: done
created: 2026-06-24
last_updated: 2026-06-25
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingMetadataStartupGuard.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingMetadataDao.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java
  - docs/spec/llm.md
  - docs/design/02-schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Do NOT edit V11__post_embedding.sql or its seed INSERT. V11 is an already-applied migration on every existing DB (immutable; editing it breaks the Flyway checksum and aborts startup). The fix is guard-side and MUST work whatever V11 seeded — including the existing `('nomic-embed-text', 768)` seed."
  - "Do NOT add a new migration to 'correct' the seed: static SQL cannot read the configured embedding model, so it cannot seed the right identity. The guard already has both the configured value (@ConfigProperty) and a DataSource — adopt at boot, not in DDL."
  - "Do NOT change the `infochat.embeddings.allow-model-change` override path or its WARN+rotate behavior: it stays the explicit operator path for a genuine model change WITH existing embeddings."
  - "Do NOT weaken dimensionality/model protection once vectors exist: a mismatch with a NON-empty post_embedding stays fatal unless allow-model-change=true. The guard's @Priority(125) ordering between Flyway (100) and LlmRouterStartupGuard (150) is unchanged."
acceptance:
  - "On a fresh deployment (post_embedding empty) the Collector starts even when the configured embedding model identifier differs from the stored singleton row, because no vectors exist yet — there is nothing to be incompatible with. The guard ADOPTS the configured (model, dimension): it rotates the singleton via EmbeddingMetadataDao.updateSingleton(...) and logs (INFO or WARN) that it recorded the embedding model identity on first use, with no re-embed required (nothing was embedded). This makes the spec's 'stored in a singleton metadata row on first use' (docs/spec/llm.md:204) true for the llama.cpp / remote / custom-embedding backends, which today trip the guard on first boot: V11 seeds the Ollama name `nomic-embed-text` and the guard treats ANY mismatch as fatal, so e.g. a llama.cpp deployment with `infochat.embeddings.model=nomic-embed-text-v1.5.f16.gguf` cannot start a fresh DB."
  - "When post_embedding is NON-empty and the configured identity differs from the stored row, the guard STILL refuses startup (fatal EmbeddingModelMismatchException) unless `infochat.embeddings.allow-model-change=true` — the existing data-integrity protection is preserved exactly once real vectors exist. The dangerous hand-cleaned case (singleton row absent BUT post_embedding non-empty) also stays fatal (the current empty-singleton fatal branch, now gated on embeddings actually existing)."
  - "EmbeddingMetadataDao gains a read for post_embedding emptiness (e.g. `SELECT EXISTS (SELECT 1 FROM post_embedding)`) using the already-injected DataSource, at the same SQL-deserialization boundary as readSingleton/updateSingleton. The guard uses it to choose adopt-vs-enforce."
  - "The package-visible evaluate(...) gains the post_embedding-emptiness signal (a `boolean hasEmbeddings`, or equivalent) so ReadyPromoterIT can drive all four cases from a single @QuarkusTest without re-bootstrapping Quarkus: (a) empty embeddings + mismatch → adopt + rotate, starts OK; (b) non-empty + mismatch + flag false → fatal; (c) non-empty + mismatch + flag true → rotate + WARN, starts OK; (d) identity match → no-op, starts OK. onStartup() supplies the real emptiness read."
  - "docs/spec/llm.md §Embedding pipeline (and/or docs/design/02-schema.md §2.8) is reconciled so 'startup is refused if either differs from the stored row' reads together with 'stored … on first use': the guard refuses on a mismatch only once embeddings exist; with zero embeddings it adopts the configured identity. Keep the edit minimal; if the existing 'on first use' wording is judged already sufficient, state that in the commit rather than padding the spec."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java — first-boot empty-embeddings mismatch adopts the configured identity and starts; non-empty mismatch with flag=false stays fatal; non-empty mismatch with flag=true rotates+WARN and starts; identity match is a no-op."
  preserves:
    - all tests currently green on main
    - "ReadyPromoterIT existing fail-fast (mismatch → refuse) and allow-model-change (rotate) assertions — re-expressed against the non-empty-embeddings case they were always meant to cover"
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/design/02-schema.md §2.8 Embedding model migration
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 181
      removed: 65
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-25
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: existing ReadyPromoterIT call sites change when evaluate() gains a boolean hasEmbeddings parameter; recommend a test_plan.modifies: entry. Info already present in acceptance item 4 — structural-location-only, non-blocking."
  blockers: []
---

# M1-443: embedding guard adopts the configured model on first boot

## Context

Part of the 2026-06-24 VPS setup investigation (sibling of M1-442). After the
llama.cpp model servers were brought up, the Collector still refused to start:

```
FATAL EmbeddingMetadataStartupGuard: embedding model identity mismatch.
  stored=(model=nomic-embed-text dimension=768)
  configured=(model=nomic-embed-text-v1.5.f16.gguf dimension=768).
  Refusing Collector startup.
```

Root cause: `V11__post_embedding.sql` hard-seeds `embedding_metadata` with the
Ollama default `('nomic-embed-text', 768)`, but the llama.cpp embeddings backend
configures `infochat.embeddings.model=nomic-embed-text-v1.5.f16.gguf` (the GGUF
filename is its identity). On the very first boot the guard sees stored ≠
configured and refuses — even though `post` and `post_embedding` are both empty,
so there is nothing to protect. This reproduces on EVERY fresh DB for the
llama.cpp, remote-llm, and any custom-embedding-model deployment; wiping Postgres
does not help, because V11 re-seeds the same Ollama name.

The guard exists to stop a *mid-deployment* model change from mixing
incompatible vectors in the fixed-width pgvector column. With zero stored
vectors that hazard does not exist yet. The spec already says the identity is
"stored in a singleton metadata row **on first use**" (docs/spec/llm.md:204) —
the implementation just established the row eagerly (a migration guess) and then
enforced it. The fix: when `post_embedding` is empty, adopt the configured
identity (rotate the singleton, log it) and start; keep the fatal refusal only
once vectors exist (the case allow-model-change is for).

On the affected VPS this manifested as the Collector container exiting(1) right
after Flyway applied all 52 migrations — the guard is the next `@Startup` bean.

## Notes (verified 2026-06-24)

- The guard's `evaluate(...)` is already package-visible and exercised by
  `ReadyPromoterIT` for both the fail-fast and allow-model-change paths — extend
  it there; no new Quarkus bootstrap needed. Add the emptiness signal as a
  parameter so the four cases are unit-drivable.
- `EmbeddingMetadataDao` already `@Inject`s a `javax.sql.DataSource` (the
  emptiness query has a home next to `readSingleton`/`updateSingleton`); the DAO
  doc already states V11's seed INSERT is the only other write.
- The adopt-on-empty rule is independent of WHAT V11 seeded, so V11 stays
  untouched and `migration_touch=false`. On an Ollama deployment the adopt is a
  no-op rotate (configured == seed); on llama.cpp/remote it rotates to the real
  configured identity.
- This is the same first-run-friendliness family as M1-439/440/442 (setup must
  succeed on its own documented happy path), but it is guard/data semantics, not
  wizard plumbing — hence a separate ticket from M1-442.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-443-embedding-guard-first-boot-adopt.md
```
