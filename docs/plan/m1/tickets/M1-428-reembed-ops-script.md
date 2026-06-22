---
id: M1-428
title: Reconcile embedding-dimension docs to v1 768-fixed reality (drop phantom reembed.sh)
status: pending
created: 2026-06-22
last_updated: 2026-06-22
blocked_by: []
files_budget: 5
files_scope:
  - docs/design/07-deployment.md
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/01-architecture.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # This ticket DOCUMENTS an already-shipped deferral; it does NOT implement the
  # per-profile embedding dimensions or build the migration script.
  - prod/scripts/reembed.sh
  - infochat-core/src/main/resources/db/migration/**
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  # SETUP_GUIDE.md is already correct (it tells operators embeddings are fixed at
  # 768-d and never change). Do not "fix" it — it is the source of truth this
  # ticket reconciles the design files UP to.
  - SETUP_GUIDE.md
  # V11's header comment already frames pi-384/remote-1536 as operator-selected /
  # deferred; it is correct and a code file (touching it = migration_touch). Leave
  # it.
  - prod/switch-llm.sh
acceptance:
  # Pure-docs reconciliation. The shipped reality (V11 hardcodes vector(768) +
  # seeds embedding_metadata(nomic-embed-text,768); application.properties sets
  # dimension=768 / model=nomic-embed-text at BASE with no %profile override;
  # embeddings.allow-model-change=false + the embedding_metadata startup guard
  # fatal-fails any mismatch) is that EVERY v1 profile runs 768-d nomic-embed-text.
  # The per-profile dimensions in the design tables are unimplemented aspiration.
  - docs/design/05-llm-and-embeddings.md §5.5 "Model and dimension by profile" and
    the §5.7 config table no longer present pi=384 (all-minilm) and remote-llm=1536
    as the v1 shipped reality. State that v1 ships 768-d `nomic-embed-text` across
    ALL profiles, and that per-profile embedding models/dimensions are a deferred,
    not-shipped-in-v1 capability that the schema (V11), the `embedding_metadata`
    guard, and §2.8 anticipate but v1 does not enable.
  - The stale cross-reference at docs/design/05-llm-and-embeddings.md:350 ("see
    02-schema.md §2.7") is corrected to §2.8 (the actual "Embedding model
    migration" section).
  - docs/design/02-schema.md §2.4.2 (the `vector(384)` pi note) and §2.8 are
    reconciled — §2.8 stops asserting "A migration script `scripts/reembed.sh`
    automates steps 1–3" as if it exists, and reframes the dimension-change
    migration as the intended POST-v1 procedure (the script is not shipped in v1).
  - docs/design/07-deployment.md is corrected so it stops telling operators to run
    a script that does not exist — §7.2.1 "Switching profiles" step 3 and the §7.15
    disaster-scenarios row ("Profile mistake … Run `prod/scripts/reembed.sh`. 4-day
    window self-heals.") no longer instruct running reembed.sh — they state plainly
    that the embedding dimension is fixed in v1 and a profile switch does not change
    it. The §7.7.1 ops-script file-map row and the §7.4/§7.8.1 directory-tree
    listings stop presenting reembed.sh as a shipped ops script (drop it or mark it
    deferred). The §7.1 profile table "Embedding via all-minilm:33m (384-d)" pi
    cell is reconciled the same way.
  - docs/design/01-architecture.md the per-profile "Embedding model" table row
    (pi = all-minilm:33m 384-d, remote-llm = provider default) is reconciled to the
    v1 768-d-everywhere reality with the same deferred-capability framing.
  - No design file references an implementation-plan tier ("T2", "M1-PRn") in its
    prose — use "deferred / not shipped in v1" wording (the existing
    docs-reference rules forbid plan refs in spec/design).
test_plan:
  adds:
    # Pure-docs change; no code, no migration, no test. Per CLAUDE.md "Commit
    # prefixes", a docs-only edit could even land as a `spec:` commit outside the
    # ticket flow — kept as a ticket here because it spans four design files and
    # corrects a cross-cutting contradiction worth tracking.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Hardware profiles
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/llm.md §Embedding pipeline
decision_refs:
  - D49
---

# M1-428: Reconcile embedding-dimension docs to v1 768-fixed reality

## Context

`docs/design/07-deployment.md` references `prod/scripts/reembed.sh` as "the
embedding migration" in two places — §7.2.1 (Switching profiles, step 3: "If
embedding dimension changes (e.g., laptop→pi), run the embedding migration") and
§7.15 (Disaster scenarios: "Profile mistake (e.g., switched embedding dimension) |
Run `prod/scripts/reembed.sh`. 4-day window self-heals.") — but the script **does
not exist**, and, more importantly, **the scenario it recovers from cannot occur in
shipped v1.**

**The original draft of this ticket was wrong on two counts**, both falsified
against the implementation:

1. *"Its mechanics are unspecified."* They are specified — `docs/design/02-schema.md`
   §2.8 "Embedding model migration" lays out the add-column / re-embed / swap /
   drop dance. The draft only looked at the §7.2.1/§7.15 references and missed §2.8.
2. *"Switching profiles changes the embedding dimension, so we need the script."*
   In v1 it does not. The dimension is **fixed at 768-d across every profile**:
   - `V11__post_embedding.sql` hardcodes `embedding vector(768)` and seeds
     `embedding_metadata` with `(nomic-embed-text, 768)`. Its own header comment
     says the pi `vector(384)` / remote-llm `vector(1536)` variants are
     "operator-selected via an alternative migration file or an operator-issued
     ALTER TABLE" — i.e. deliberately **not** shipped in the baseline.
   - `application.properties` sets `infochat.embeddings.model=nomic-embed-text` and
     `infochat.embeddings.dimension=768` at the **base** level, with **no**
     `%pi`/`%remote-llm`/`%vps` override (the only per-profile embedding overrides
     are `max-concurrency` and `semantic-threshold`).
   - `infochat.embeddings.allow-model-change=false` plus the `embedding_metadata`
     startup guard **fatal-fail** any model/dimension mismatch — the system
     actively prevents a dimension change from taking effect.
   - `SETUP_GUIDE.md` already tells operators (correctly) that embeddings are fixed
     at 768-d and a backend switch never touches them.

So the per-profile dimensions in the design tables (`05-llm` §5.5: pi=384,
remote-llm=1536; `02-schema` §2.4.2/§2.8; `01-architecture` and §7.1 profile rows)
are **unimplemented aspiration**, and the reembed.sh references are a documented
recovery step for a state that cannot arise. An operator who hits "profile mistake"
and follows §7.15 today is sent to a non-existent script.

**This ticket is therefore a docs-only reconciliation**, not a script build. It
makes the design files agree with the shipped 768-d-everywhere reality and with
SETUP_GUIDE, and it stops promising a recovery tool that does not exist. The §2.8
migration design is kept as the intended post-v1 procedure, just no longer written
as if its script ships.

## Acceptance

See the YAML `acceptance:`. In short: reconcile the per-profile embedding-dimension
tables in `05-llm`, `02-schema`, `01-architecture`, and `07-deployment` to "v1
ships 768-d nomic-embed-text on every profile; per-profile dimensions + reembed.sh
are deferred beyond v1"; remove the §7.2.1/§7.15 instructions to run the
non-existent script; fix the stale `§2.7`→`§2.8` cross-reference.

## Out-of-scope

This ticket documents a deferral that has **already shipped** in code; it does NOT
implement per-profile embedding dimensions, touch any Flyway migration or
`application.properties`, or build `reembed.sh`. `SETUP_GUIDE.md` is already correct
and must not be edited. V11's header comment already frames the deferral correctly
and is a code file — leave it.

## Notes

- **Why not just build reembed.sh?** Because v1 deliberately ships one embedding
  model at one dimension everywhere (see Context). Building the script would be
  adding tooling for a capability v1 chose not to enable — the opposite of the
  "no features for their own sake" bar this work was reviewed against.
- **Is this a new design decision?** No. The deferral is already encoded in V11,
  the config, and the startup guard. This ticket only makes the prose match it.
- **If per-profile dimensions are ever wanted**, that is a separate, larger piece
  of work (a profile-parameterised baseline or operator ALTER path, the §2.8
  migration, and the script) — file it as its own ticket then; do not pre-build it
  here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-428-*.md
```
