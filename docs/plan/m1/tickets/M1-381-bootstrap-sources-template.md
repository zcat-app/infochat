---
id: M1-381
title: "deploy: commit a default bootstrap-sources.json template under prod/config/"
status: done
created: 2026-06-15
last_updated: 2026-06-15
clarity_check:
  date: 2026-06-15
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 1
files_scope:
  - prod/config/bootstrap-sources.json
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any change to the bootstrap-sources loader or its tests — this is a data file only; the loader already exists and is tested.
  - A bootstrap-assets.json template — assets are opt-in and disabled by default; out of scope here.
  - Pointing any application.properties default at this path — the wizard's 5-bootstrap.sh (M1-383) copies the template into the runtime dir.
acceptance:
  - "prod/config/bootstrap-sources.json exists and is valid JSON (`python3 -m json.tool prod/config/bootstrap-sources.json` exits 0), containing the §7.6.1 example feed set."
  - "Every entry carries the required fields kind / identifier / name / category / tags with at least one tag, per §7.6.1 (verified with a jq assertion over the array — e.g. `jq -e 'all(.kind and .identifier and .name and .category and (.tags|length>=1))' prod/config/bootstrap-sources.json` exits 0)."
  - "No entry uses a `kind` outside the §7.6.1 enum (rss, bluesky, nitter, reddit, youtube, odysee, nostr); any nostr entry includes a non-empty config.relays array (jq assertion exits 0)."
  - "mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.6.1
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  - D14
  - D38
reviews:
  - round: 1
    date: 2026-06-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 69
      removed: 8
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-381: default bootstrap-sources.json template

## Context

No `bootstrap-sources.json` is committed anywhere in the repo today, so a fresh
deployment's Collector has nothing to fetch. This ticket commits the §7.6.1
example as `prod/config/bootstrap-sources.json` — the template the wizard's
5-bootstrap.sh (M1-383) copies into the runtime directory. `prod/config/` holds
committed templates only (`07-deployment.md` §7.7 Repo-layout block).

Independent of the other deployment tickets — it is a static data file.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Copy the field set verbatim from `07-deployment.md` §7.6.1; do not invent new
  feeds or kinds beyond the documented enum.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-381-*.md
```
