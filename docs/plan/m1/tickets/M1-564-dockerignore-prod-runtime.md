---
id: M1-564
title: Exclude prod/runtime from the app-image build context
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
files_budget: 1
files_scope:
  - .dockerignore
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the two Dockerfiles and docker-compose.yml (the multi-stage COPY . .
    into the build stage is correct for the in-image reactor build; the
    shipped images already copy only target/quarkus-app/ out of it)
  - permissions/ownership of prod/runtime/signal-cli/data (root-owned
    700 is the signal-cli daemon's own posture; it must NOT be made
    world-readable)
  - any other .dockerignore entries (target/, .git/, .claude/ stay as
    they are)
acceptance:
  - ".dockerignore gains a prod/runtime/ exclusion with a WHY comment:
    (a) the dir holds operator secrets and messenger identity stores
    that must never enter a build context, and (b) the signal-cli
    daemon's root-owned data dir breaks the classic builder's context
    walk outright (can't stat), failing every image build on a host
    with a provisioned Signal adapter."
  - "Host validation: docker compose -f docker-compose.yml --env-file
    prod/runtime/secrets.env --profile prod build infochat-collector
    infochat-provider completes with exit 0 from a tree containing the
    root-owned prod/runtime/signal-cli/data dir (the exact command that
    failed with 'checking context: can't stat' before the fix)."
  - "The diff is .dockerignore-only, so mvn verify is inert per the
    M1-379 gate (Dockerfile*/.dockerignore are enumerated inert paths);
    the round log records the inert-N/A note."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (no testable file changes)
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs: []
---

# M1-564: Exclude prod/runtime from the app-image build context

## Context

Found 2026-07-04 triggering the post-M1-562 image rebuild: `docker
compose --profile prod build` fails during context preparation with
`checking context: can't stat '<repo>/prod/runtime/signal-cli/data'`.
The dir is the bot's signal-cli identity store, created root-owned
`700` by the daemon during Phase 5 provisioning — this is the first
rebuild since. The classic builder (no buildx on this host) stats the
whole context tree, and `.dockerignore` currently excludes only
`target/`, `.git/`, and `.claude/`.

Independent of the stat failure, `prod/runtime/` (operator secrets.env,
signal-private.env, SimpleX/Signal identity stores) has been entering
the build context and the build-stage layers all along. The final
images are clean (multi-stage: only `target/quarkus-app/` is copied
out), so this is hygiene, not a leak — but runtime state is not a build
input and should never be shipped to the builder.

## Acceptance

Mirrors the YAML list: one `.dockerignore` entry + WHY comment; the
previously-failing compose build command exits 0 on this host;
inert-diff `mvn verify` N/A per M1-379.

## Out-of-scope

Dockerfiles, compose, other ignore entries, and the ownership of the
signal-cli data dir (making it readable would put identity keys INTO
the context — the exclusion is the correct direction). See frontmatter.

## Notes

- The classic builder skips descending into an excluded directory when
  the ignore file has no `!` exception patterns (ours has none), which
  is what makes the exclusion sufficient to avoid the stat.
- Nothing in the in-image reactor build reads `prod/runtime` — it is
  runtime state, bind-mounted by compose at run time.
