---
id: M1-571
title: persist custom GGUF download URL + SHA so restore recovers custom models
status: done
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
remediates: M1-567
files_budget: 5
files_scope:
  - prod/scripts/4-llm.sh
  - prod/scripts/restore.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
security_relevant: true
migration_touch: false
round_cap: 2
out_of_scope:
  - >-
    prod/scripts/pack.sh. pack.sh bundles secrets.env VERBATIM, so the new
    INFOCHAT_LLAMACPP_*_GGUF_URL / _SHA keys ride into the bundle with no pack.sh
    change. Do NOT touch pack.sh.
  - >-
    The DRY duplication of the pinned GGUF constants between 4-llm.sh and
    restore.sh (restore.sh's "MUST stay in sync" note). This ticket does NOT
    factor them into a shared sourced lib — restore.sh keeps its pinned constants
    for the pinned-default recovery path. Factoring the constants out is a
    separate refactor.
  - >-
    The pinned-default GGUF recovery path in restore.sh (matching the persisted
    filename to LLAMACPP_GEN/EMB_GGUF_FILE and re-fetching from the in-script
    constant). Unchanged — this ticket only changes the CUSTOM (non-pinned)
    branch that today fails loud.
  - >-
    The optional-SHA posture for custom GGUFs. 4-llm.sh already lets a custom
    override skip the integrity check (operator-trusted TLS fetch, M1-394); this
    ticket persists whatever SHA (possibly empty) the operator gave and does NOT
    make it mandatory.
  - >-
    prod/switch-llm.sh (the post-setup per-task backend switcher). It re-routes
    tasks between backends and never fetches or persists a GGUF URL, so it has no
    analogous gap. Do not touch it.
acceptance:
  - >-
    4-llm.sh persists the generative GGUF's source URL and SHA to secrets.env as
    INFOCHAT_LLAMACPP_GGUF_URL and INFOCHAT_LLAMACPP_GGUF_SHA (via the existing
    idempotent set_secret helper, dotenv-escaped, mode 0600), alongside the
    existing INFOCHAT_LLAMACPP_GGUF filename write (4-llm.sh:386). Written for
    BOTH the pinned default and a custom override — both branches already populate
    $gen_url / $gen_sha (4-llm.sh:334-344), so the persistence is uniform. The SHA
    value persisted is whatever the branch holds (the enforced pinned SHA, or the
    operator's custom SHA, or empty when the operator skipped the custom integrity
    prompt).
  - >-
    4-llm.sh persists the llamacpp EMBEDDINGS GGUF's URL + SHA as
    INFOCHAT_LLAMACPP_EMBED_GGUF_URL / _SHA, written ONLY inside the
    emb_backend=llamacpp branch that already sets $emb_url / $emb_sha and writes
    INFOCHAT_LLAMACPP_EMBED_GGUF (4-llm.sh:391-392) — NOT in the ollama-embeddings
    branch (which serves nomic from Ollama and has no llamacpp embed GGUF).
  - >-
    restore.sh's ensure_gguf recovers a CUSTOM (non-pinned) GGUF by re-fetching
    from the persisted URL + SHA instead of failing loud, when the model volume
    does not already contain it. The caller passes the persisted
    INFOCHAT_LLAMACPP_GGUF_URL / _SHA (resp. the _EMBED_ twins) read from the
    restored secrets.env via read_dotenv_value; the custom (`*)`) branch fetches
    from that URL (reusing fetch_gguf, so the SHA is verified when non-empty). The
    PINNED-default branch (matching LLAMACPP_GEN/EMB_GGUF_FILE) is UNCHANGED.
  - >-
    Backward-safe fail-loud is preserved: when a custom GGUF filename has NO
    persisted URL (an older bundle produced before this ticket, whose secrets.env
    lacks INFOCHAT_LLAMACPP_GGUF_URL) AND the model volume does not contain it,
    restore.sh still fails loud with the existing actionable message (fetch it
    manually / re-run 4-llm.sh). The recovery path fires only when a persisted URL
    is present.
  - >-
    mvn verify is green. LlamacppWiringTest pins that 4-llm.sh writes
    INFOCHAT_LLAMACPP_GGUF_URL / _SHA (and, for the llamacpp-embeddings shape, the
    _EMBED_ twins) with the chosen URL/SHA into secrets.env. RestoreWiringTest
    pins that restore.sh's custom-GGUF branch reads INFOCHAT_LLAMACPP_GGUF_URL and
    re-fetches from it (invocation/wiring shape, the same gate-only scope the file
    already uses — the real multi-GB download stays HOST validation).
  - >-
    docs/design/07-deployment.md §7.10.1 is updated: the restore.sh bullet at
    line 992-993 currently reads "a pinned-default GGUF is re-fetched from its
    known URL, a custom one fails loud because its URL was never persisted" — this
    becomes "a custom GGUF is re-fetched from the URL persisted in secrets.env at
    setup time; only a custom GGUF from an OLDER bundle (pre-M1-571, no persisted
    URL) fails loud." The §7.7.2 step-4 row (line 683) is updated to note the
    wizard now persists the GGUF URL+SHA (not just the filename) so a clone can
    recover a custom model.
test_plan:
  adds: []
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  preserves:
    - all tests currently green on main
    - >-
      the existing 4-llm.sh llamacpp-branch assertions in LlamacppWiringTest and
      all existing restore gate cases in RestoreWiringTest (M1-568 allowlist,
      M1-569 privileged untar, M1-570 role-before-pg_restore ordering) must still
      pass — this ticket adds URL/SHA persistence + custom-GGUF recovery, it does
      not alter the other steps.
  notes:
    - >-
      The real end-to-end recovery (a multi-GB custom GGUF actually re-downloaded
      from the persisted URL on a fresh host) needs real network + Docker and
      stays HOST validation, mirroring M1-567/569/570 gate-only test scope. The
      wiring tests pin the persistence write and the read-and-fetch wiring only.
    - >-
      Retroactivity (for the implementer's awareness, NOT part of this ticket):
      a deployment configured by the OLD 4-llm.sh has no persisted URL in its
      secrets.env, so recovering ITS custom GGUF needs a one-time backfill of
      INFOCHAT_LLAMACPP_GGUF_URL — an operator action, not code. The fix is
      forward-looking for freshly-configured deployments.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/design/07-deployment.md §7.7.2
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 126
      removed: 20
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-05
    verdict: CLEAN
    verdict_file: docs/plan/m1/redteam/M1-571-2026-07-05.md
    out_of_model_count: 1
    note: |
      CLEAN. 1 out-of-model: custom-GGUF recovery fetches from a secrets.env URL with an
      optionally-empty SHA; a tampered bundle could abuse it — but operator config + the
      backup bundle are trusted, bundle-tampering/MITM are out of scope for v1, §SSRF binds
      the app outbound path not operator scripts, and the pinned path uses an enforced SHA.
      Optional future hardening (mandatory SHA / bundle-signing) only if the threat model is
      widened; M1-571 preserved 4-llm.sh's existing optional-SHA posture (out_of_scope).
clarity_check:
  date: 2026-07-05
  verdict: WARN
  warnings:
    - >-
      complexity/risk/security_relevant/migration_touch/round_cap were absent from
      the draft frontmatter (the sibling M1-569/M1-570 set them explicitly).
      Addressed pre-start: added complexity:medium, risk:medium (blast radius is
      model-recovery availability, not DB integrity/auth like M1-570; pinned path
      untouched, SHA-verified, real validation is the host round-trip),
      security_relevant:true, migration_touch:false, round_cap:2.
  blockers: []
---

# M1-571: persist custom GGUF download URL + SHA so restore recovers custom models

## Context

Surfaced during the M1-570 wrap-up (the fixed restore.sh round-trip prep). The
live deployment runs a **custom** generative GGUF
(`gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf`, 5.3 GB) that is not one of restore.sh's
pinned defaults. `4-llm.sh` (wizard step 4) accepts a full download URL for a
custom generative GGUF (4-llm.sh:333) and a custom embedder (4-llm.sh:360),
downloads it via `fetch_gguf`, but then persists **only the filename** to
secrets.env (`set_secret INFOCHAT_LLAMACPP_GGUF "$gen_file"`, 4-llm.sh:386) — the
URL (`$gen_url`) and SHA (`$gen_sha`) are used for the download and discarded.

So on a fresh host, `restore.sh`'s `ensure_gguf` hits its `*)` branch and fails
loud: the custom model cannot be re-fetched because its URL was never persisted
(restore.sh:158-168). A host clone of a custom-model deployment therefore cannot
be fully rebuilt — the operator must manually re-place the GGUF. The M1-567
round-trip worked around this by KEEPING the custom gen GGUF in the volume and
wiping only the pinned embedder.

## The fix

Persist the URL + SHA that 4-llm.sh already holds, and teach restore.sh to use
them for a custom model:

1. **4-llm.sh** — after each `set_secret INFOCHAT_LLAMACPP_GGUF "$gen_file"` /
   `INFOCHAT_LLAMACPP_EMBED_GGUF "$emb_file"`, also
   `set_secret INFOCHAT_LLAMACPP_GGUF_URL "$gen_url"` +
   `INFOCHAT_LLAMACPP_GGUF_SHA "$gen_sha"` (and the `_EMBED_` twins in the
   llamacpp-embeddings branch). Both the pinned and custom branches already
   populate `$gen_url` / `$gen_sha`, so the write is uniform and small.
2. **restore.sh** — `ensure_gguf` takes the persisted URL + SHA (read from the
   restored secrets.env by the caller) and, in the custom (`*)`) branch, re-fetches
   from that URL via the existing `fetch_gguf` (SHA verified when non-empty)
   instead of failing loud. The pinned branch is untouched; the fail-loud message
   is preserved for the no-persisted-URL case (older bundles).

`pack.sh` needs no change — it bundles secrets.env verbatim, so the new keys ride
into the bundle automatically.

## Alternatives considered

- **Factor the pinned GGUF constants into a shared sourced lib** (the DRY debt
  restore.sh:47-54 flags) and make restore.sh fully data-driven off the persisted
  URL for ALL GGUFs, deleting its pinned constants — rejected for this ticket:
  it is a larger change to the restore path (just validated by M1-570) and would
  break restoring OLD bundles (no persisted URL, no fallback constants). Keeping
  the pinned constants as the pinned-path source AND fallback is smaller and
  backward-safe. The refactor stays a separate follow-up.
- **pack.sh switches to `pg_dumpall`-style role/model bundling** — irrelevant
  here; the models live in a Docker volume, not the DB dump. Persisting the URL is
  the minimal recovery key.

## Provenance

Found during M1-570 round-trip prep (the fixed restore.sh). Filed
`remediates: M1-567` because M1-567 (the host-clone tooling) is done+merged and
immutable; this closes the custom-model recovery gap that tooling left. The
current live deployment's secrets.env predates this fix, so recovering ITS custom
GGUF still needs a one-time operator backfill of INFOCHAT_LLAMACPP_GGUF_URL — the
fix is forward-looking for freshly-configured deployments.
