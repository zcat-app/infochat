---
id: M1-428
title: Embedding-dimension migration script (prod/scripts/reembed.sh)
status: pending
created: 2026-06-22
last_updated: 2026-06-22
blocked_by: []
files_budget: 6
files_scope:
  - prod/scripts/reembed.sh
  - docs/design/07-deployment.md
  - docs/spec/deployment.md
  - SETUP_GUIDE.md
  # Schema/migration + test paths depend on the design decision below; add the
  # concrete Flyway migration and test paths to files_scope at start, once the
  # migration mechanics are settled.
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - prod/setup.sh
  # The chat/generative backend switch is M1-418 (prod/switch-llm.sh); this is the
  # embeddings counterpart and is a separate concern. Do not fold backend routing
  # in here.
  - prod/switch-llm.sh
acceptance:
  # DESIGN-FIRST: item 1 closes the specification gap; the rest implement it.
  - docs/design/07-deployment.md (§7.2.1 Switching profiles / §7.15 Disaster
    scenarios) gains a subsection that SPECIFIES the embedding-dimension migration —
    what triggers it (embedding model/dimension change, e.g. laptop 768-d →
    pi 384-d), whether and how the `post_embedding` pgvector column dimension and
    its index (`hnsw`/`ivfflat`) change, which posts are re-embedded (all stored
    posts vs only the active retrieval/linking window — reconcile the "4-day window
    self-heals" claim in §7.15 against post-partition retention), whether it runs
    offline (services stopped, per §7.2.1 step 3) or online, and its idempotency
    contract.
  - prod/scripts/reembed.sh implements that design — after an embedding-dimension
    switch it leaves pgvector semantic retrieval functional, re-embedding the
    in-scope posts with the now-active embedding model via the project's embedding
    path (not an ad-hoc external call).
  - The script is idempotent — safe to re-run after an interruption without
    corrupting or duplicating `post_embedding` rows.
  - docs/spec/deployment.md and SETUP_GUIDE.md (profile-switch guidance) reference
    the script accurately, and stop implying it exists where they currently do.
test_plan:
  adds:
    # - migration test + script harness TBD once the design subsection fixes the
    #   migration mechanics (mirror the M1-418 ProcessBuilder-harness precedent for
    #   the script; a Flyway migration test if the column/index changes).
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Hardware profiles
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D49
---

# M1-428: Embedding-dimension migration script (prod/scripts/reembed.sh)

## Context

`docs/design/07-deployment.md` references `prod/scripts/reembed.sh` as "the
embedding migration" in two places — §7.2.1 (Switching profiles, step 3: "If
embedding dimension changes (e.g., laptop→pi), run the embedding migration") and
§7.15 (Disaster scenarios: "Profile mistake (e.g., switched embedding dimension) |
Run `prod/scripts/reembed.sh`. 4-day window self-heals.") — but the script **does
not exist**, and, more importantly, **its mechanics are unspecified**.

Switching the active profile can change the embedding model and its vector
dimension (laptop/vps/remote-llm = 768-d nomic; pi = 384-d all-minilm per
§1.7 / 05-llm-and-embeddings). That is a hard change for pgvector: the
`post_embedding` column is dimension-typed and its index is dimension-bound, so a
re-embed is not just "recompute vectors" — it likely involves a schema migration
and an index rebuild. The design says none of this; it only names the script.

**This ticket is therefore design-first.** Acceptance item 1 produces the missing
specification; the remaining items implement it. If the design work proves large
enough to warrant its own spec amendment, escalate (`spec-amend`) rather than
bundling an under-considered migration.

## Acceptance

See the YAML `acceptance:`. The load-bearing first step is to specify the
migration (trigger, column/index dimension change, re-embed scope vs the "4-day
window" claim, offline-vs-online, idempotency); then implement an idempotent
`reembed.sh` that restores functional pgvector retrieval after an
embedding-dimension switch, with the profile-switch docs reconciled to it.

## Out-of-scope

Not a wizard step (`prod/setup.sh` untouched). This is the embeddings counterpart
to the generative-backend switcher `prod/switch-llm.sh` (M1-418) and must not fold
in backend routing. Embeddings remain 768-d-or-384-d nomic-class per D49 — the
migration changes dimension *between profiles*, it does not introduce an arbitrary
operator-chosen embedding model.

## Notes

- **Why high-complexity / migration_touch.** A dimension change touches the
  `post_embedding` pgvector column and its vector index; depending on the design
  it may need a Flyway migration and an index rebuild, which serializes against
  other migration work.
- **Reconcile the "4-day window self-heals" claim.** §7.15 implies only a recent
  window needs re-embedding for retrieval to recover; the design subsection must
  state explicitly whether reembed covers all stored posts or only the active
  window, and what happens to older posts whose vectors stay at the prior
  dimension.
- **Consider whether this is beta-blocking.** Profile-switching with a dimension
  change is an edge operation; if it is not needed for the invite-gated beta, it
  may be a candidate to defer behind the design pass rather than implement now.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-428-*.md
```
