---
id: M1-379
title: "deploy: containerize Collector + Provider (Dockerfiles) and add them as prod-profile compose services"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by:
  - M1-378
files_budget: 4
files_scope:
  - infochat-collector/src/main/docker/Dockerfile.jvm
  - infochat-provider/src/main/docker/Dockerfile.jvm
  - docker-compose.yml
  - .dockerignore
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - Adding any Quarkus container-image extension or other Maven dependency — containerization uses a hand-written multi-stage Dockerfile so no pom.xml changes (avoids the dependency-approval gate).
  - Native images (07-deployment.md §7.8.2 keeps JVM mode for v1).
  - The LLM services (M1-380), bootstrap template (M1-381), and wizard scripts (M1-382+).
  - The bare-metal systemd shape (07-deployment.md §7.8.1) — this ticket is the containerized runtime only.
acceptance:
  - "infochat-collector and infochat-provider each gain a multi-stage Dockerfile.jvm (a JDK 25 build stage that runs the Maven build, then a JRE 25 runtime stage running the quarkus-app) that produces a runnable image; no Maven dependency is added (git diff shows no pom.xml change)."
  - "docker-compose.yml declares infochat-collector and infochat-provider services tagged `profiles: [prod]`, built from those Dockerfiles, with the provider's `depends_on` requiring the collector `service_healthy` so the §Topology startup ordering (only the Collector migrates) holds; both read the per-service DB role credentials from the M1-378 env vars."
  - "`docker compose --profile prod config` lists both app services AND the postgres service; `docker compose --profile dev config` lists neither app service (grep on the two config outputs)."
  - "`docker compose --profile prod build` builds both images successfully (manual procedure; commit-message evidence)."
  - "A .dockerignore at the repo root excludes target/, .git/, and .claude/ from the build context (grep -E confirms those entries)."
  - "mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7 Local and containerized stack
  - docs/spec/deployment.md §Topology
decision_refs:
  - D1
  - D41
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-379: containerize Collector + Provider as prod-profile compose services

## Context

For public testing the apps must run without a host JDK 25 or Maven — the whole
point of the containerized runtime (`07-deployment.md` §7.7.2 wizard). Today
`docker-compose.yml` runs Postgres only; the apps run via host `quarkus:dev`.
This ticket adds a multi-stage `Dockerfile.jvm` to each app module (build inside
the image, run on a JRE 25 base) and wires both as compose services under the
`prod` profile, with the provider depending on the collector being healthy so
the Collector applies the Flyway migration set before the Provider starts
(`docs/spec/deployment.md` §Topology — only the Collector migrates in
production).

Containerization uses hand-written Dockerfiles deliberately: it avoids adding a
Quarkus container-image extension (a dependency that would need explicit
approval) and keeps the pom files untouched.

Blocked on M1-378 because the app containers authenticate as
`infochat_collector` / `infochat_provider`, whose passwords M1-378 establishes.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Pin the base images (build + runtime) to explicit JDK/JRE 25 tags, not
  `latest`, to match the M1-004 pinned-tag precedent.
- The build stage may be slow (full module build in-image); that is acceptable
  for the v1 public-test runtime. Do not try to COPY host-built jars — that
  would reintroduce the host-JDK prerequisite this ticket removes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-379-*.md
```
