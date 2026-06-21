---
id: M1-420
title: Correct DEVELOPER.md dev-run workflow to match the %dev profile
status: done
created: 2026-06-21
last_updated: 2026-06-21
clarity_check:
  date: 2026-06-21
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - DEVELOPER.md
  - docs/design/07-deployment.md
complexity: low
risk: low
security_relevant: false
migration_touch: false
out_of_scope:
  # RECOMMENDED SOLUTION IS DOC-ONLY. The %dev profile is deliberately minimal
  # (passwords only; no jdbc.url, no adapters — the rest is %test-only, M1-414
  # precedent). Do NOT "fix" it by baking dev credentials / adapters / a DB URL
  # into the app config: that re-opens a settled decision and risks a dev-only
  # low-trust adapter or known admin shipping in a mis-profiled image. If, while
  # verifying, you conclude a config change is genuinely the only fix, STOP and
  # escalate (flip security_relevant, run /redteam) — do not edit these inline.
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
acceptance:
  - DEVELOPER.md §1 and §3 accurately describe the actual %dev DB behavior, VERIFIED
    by running the documented commands. Concretely — bare `mvn -pl <module> quarkus:dev`
    activates `%dev`, which declares NO `quarkus.datasource.jdbc.url` (only
    laptop/vps/pi/remote-llm do — collector application.properties:52-56 says the URL
    is intentionally omitted so Quarkus DevServices triggers), so the host service
    uses a throwaway DevServices pgvector container (trust auth), NOT the Compose
    Postgres, and the `infochat-dev` / `.env` passwords are NOT consumed by the host
    JVM in that mode.
  - The guide documents at least one WORKING full two-service run path (collector +
    provider sharing ONE database so the provider sees the collector's migrated
    schema), verified to bring both services up healthy. Capture the exact command(s)
    used. (Bare per-module `quarkus:dev` gives each module its OWN throwaway DB, which
    does NOT satisfy the shared-schema requirement — call that out.)
  - The "DB passwords must match the dev defaults" / `.env` section is corrected to
    state precisely where those values ARE consumed (Compose `postgres-init.sh` at
    container init) vs where the host `quarkus:dev` DB credentials come from in each
    documented run mode.
  - docs/design/07-deployment.md §7.7 is reconciled with the same reality if it
    repeats the bare-`quarkus:dev`-connects-to-Compose-Postgres claim.
  - No change to any application.properties or the %dev profile (the recommended
    doc-only fix); `git diff --stat` touches only the two docs in files_scope.
test_plan:
  adds:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Local development
decision_refs:
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 101
      removed: 28
revisions: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-420: Correct DEVELOPER.md dev-run workflow to match the %dev profile

## Context

`DEVELOPER.md` §1 tells the developer that under `%dev` the host `quarkus:dev`
services connect to the Compose Postgres with password `infochat-dev`, and to
create a repo-root `.env` so the passwords match. This is contradicted by the
actual config:

- `%dev` declares **only** the datasource passwords
  (`application.properties:38,46`); it declares **no** `quarkus.datasource.jdbc.url`
  and no `quarkus.datasource.owner.jdbc.url`. Only the operator profiles
  (`laptop/vps/pi/remote-llm`) declare those (collector `:639-640`, etc.).
- The base config comment is explicit: *"The JDBC URL is intentionally NOT declared
  at this base level — Quarkus DevServices treats 'no URL configured' as its trigger
  to spin up a container ... Operator-facing profiles ... declare the URL per-profile
  below"* (collector `application.properties:52-56`).

So bare `mvn -pl <module> quarkus:dev` (`%dev`) spins a **throwaway DevServices
pgvector container** (trust auth); the Compose Postgres and the `infochat-dev` /
`.env` passwords are **not** used by the host JVM in that mode. Worse for the
two-service story: each module's `quarkus:dev` gets its **own** ephemeral DB, so the
provider would not see the collector's migrated schema — the documented "collector
first (runs Flyway), then provider" flow cannot work that way under `%dev`.

This matches the established design: `%dev` is deliberately minimal — adapters,
admin, and the JDBC URL are `%test`-only, and the M1-414 dev terminal harness is
gated by `@IfBuildProperty(infochat.dev.harness.enabled)` precisely because `%dev`
strips the `%test`-only owner datasource. A full live host run uses explicit
overrides, not a fattened `%dev`.

## Recommended solution (best UX, security-first)

**Doc-only.** Make `DEVELOPER.md` describe the two real modes accurately rather than
changing app config:

1. **Inner-loop code iteration (single service):** bare `quarkus:dev` uses
   DevServices — zero setup, no Compose Postgres or `.env` needed for the DB; great
   for editing one module and running its tests with live reload. Note the
   per-module-separate-DB caveat (not a full bot).
2. **Full two-service live bot run (shared DB):** point both services at the
   loopback Compose Postgres. Verify and document the exact recipe — candidates to
   test: `-Dquarkus.profile=laptop` (laptop supplies BOTH datasource URLs →
   `localhost:5432`, and LLM base-urls are baked at base → `localhost:11434`) with
   the `INFOCHAT_*_PASSWORD` env exported for the host JVM, and/or the M1-414 dev
   terminal harness `-D` override recipe. Document whichever brings both services up
   healthy, including the provider's adapter requirement (see M1-421 / the related
   provider-adapter note below).

**Why not change `%dev`:** baking a JDBC URL + adapter + admin into `%dev` would give
the smoothest "just run `quarkus:dev`" UX, but it re-opens a deliberately-settled
decision and creates a security footgun — a dev-only low-trust adapter and known
bootstrap admin baked into a profile that must never reach production. The security
posture (no baked production credentials; fail-fast on missing env) is worth more
than the convenience. Keep `%dev` minimal; fix the doc.

## Related finding (provider adapter)

The same §3 also presents `mvn -pl infochat-provider quarkus:dev` as turnkey, but
`infochat.adapters` is `%test`-only (`provider application.properties:108`), so under
`%dev` the provider hits the empty-adapter startup gate. The corrected full-run
recipe must include configuring an adapter (e.g. the in-memory adapter override, or
real SimpleX/Signal data-dir). Fold this into the §3 rewrite.

## Out-of-scope

No app-config changes (see frontmatter `out_of_scope` + the rationale above). If
verification proves a doc-only fix is impossible, escalate rather than editing
`application.properties` inline.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-420-*.md
```
