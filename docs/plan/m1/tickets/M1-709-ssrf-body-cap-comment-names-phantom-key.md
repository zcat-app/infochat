---
id: M1-709
title: "SSRF body-cap comment names a config key that never existed"
status: done
created: 2026-07-27
last_updated: 2026-07-30
blocked_by: []
files_budget: 1
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Every enforced value and every behavior in the file. `DEFAULT_BODY_CAP`
    (5 MiB), `DEFAULT_CONNECT_TIMEOUT` (5 s), `DEFAULT_READ_TIMEOUT`
    (30 s), `DEFAULT_BODY_READ_DEADLINE` (2 min) and `DEFAULT_REDIRECT_CAP`
    (3) keep their current values, their current visibility, and their
    current enforcement. This ticket changes prose in a comment; a diff
    that changes a number or a code path has left its scope.
  - >-
    Making any of those caps operator-configurable. That was considered
    and rejected in the 2026-07-27 audit: `docs/design/04-security.md`
    §4.4 items 5-6 now record them as deliberately not operator-tunable,
    on the same rationale as the scheme and IP allowlists. Adding a
    config key here would contradict the design note this ticket exists
    to align with.
  - >-
    The SSRF guard proper — scheme allowlist, IP blocklist, DNS pinning,
    redirect cap, origin-scoped caller headers. Untouched.
  - >-
    `SsrfGuardedHttpClient.USER_AGENT` and the `RedditFetcher`
    duplicate-header defect. Those belong to M1-704, which declares this
    same file in its `files_scope`; the two tickets edit different
    regions and must not be run as parallel lines (see §Notes).
  - >-
    Extending M1-708's documented-config-key gate to `src/main` comments.
    M1-708 out-of-scopes source comments deliberately; widening it is a
    separate decision.
  - any test file, any other module
acceptance:
  - >-
    The `DEFAULT_BODY_CAP` comment no longer describes the constant as
    "the `infochat.fetch.max-body-bytes` default". It states what is
    true: the cap is a compile-time constant, deliberately not
    operator-tunable, kept in lockstep with
    `docs/design/04-security.md` §4.4 item 5.
  - >-
    Re-running the §Census enumeration after the change yields no row in
    the "fix" disposition: no `infochat.*` token in a `src/main` comment
    is left that is neither a real config key (or key-family prefix), a
    PostgreSQL GUC, a dynamic runtime-built key, nor a package/class
    name.
  - >-
    The diff contains no change outside comment text — no constant value,
    no visibility modifier, no code line.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - >-
      SsrfGuardedHttpClientTest in full, including the body-cap and
      timeout tests that pin the enforced values. A comment fix that
      required a test edit would mean the diff changed behavior.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 18
      removed: 14
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-709: SSRF body-cap comment names a config key that never existed

## Context

`SsrfGuardedHttpClient.java:109-111` documents the 5 MiB body cap as:

```java
    // 5 MiB — the canonical outbound body cap, kept in lockstep with
    // docs/design/04-security.md §"Body size cap" (the
    // infochat.fetch.max-body-bytes default). Every no-arg consumer
```

`infochat.fetch.max-body-bytes` has never existed. It is not declared in
either service's `application.properties` and no `@ConfigProperty` reads
it; the cap is the constant on the next line and every production call
site takes the no-arg constructor.

The comment was merely stale when the 2026-07-27 doc-drift audit found
it (`.scratch/doc-audit.md` §A3). It is now actively contradictory: that
audit's follow-up narrowed `docs/design/04-security.md` §4.4 items 5-6 to
record the caps as **compile-time constants, deliberately not
operator-tunable**, and removed the three phantom key names from the
design note. The comment claims lockstep with a design section that now
says the opposite of what the comment implies.

The cost of leaving it is small but specific: this comment is what a
reader consults when asking "can an operator raise the body cap in
production?" — and it answers wrongly, in the file that enforces one of
the SSRF controls.

## Census

The defect class is "an `infochat.*` key named in a `src/main` comment
that is not a real config key". Enumerated mechanically rather than
assumed to be one site:

```bash
# real keys
{ grep -rhoE '"infochat\.[a-zA-Z0-9._-]+"' --include=*.java infochat-*/src/main/java | tr -d '"'
  grep -rhoE '\{infochat\.[a-zA-Z0-9._-]+' --include=*.java infochat-*/src/main/java | tr -d '{'
  cat infochat-*/src/main/resources/application.properties \
    | grep -oE '^%?[a-zA-Z-]*\.?infochat\.[a-zA-Z0-9._-]+' | sed -E 's/^%[a-zA-Z-]+\.//'
} | sed 's/[.:]$//' | sort -u > /tmp/real-keys.txt

# every infochat.* token mentioned in main sources, comments included
grep -rhoE '\binfochat\.[a-z][a-zA-Z0-9._-]*[a-zA-Z0-9]' --include=*.java \
  infochat-*/src/main/java | sort -u > /tmp/src-mentions.txt

comm -23 /tmp/src-mentions.txt /tmp/real-keys.txt
```

Run 2026-07-27 this returned ~230 survivors. Disposition of every class
of survivor:

| Site | Disposition |
|---|---|
| `SsrfGuardedHttpClient.java:111` — `infochat.fetch.max-body-bytes` | **fix** — the only real defect; the key has never existed |
| `app.zcat.infochat.*` package and class names (the large majority) | not-a-key — the regex tail-matches the package prefix |
| `infochat.actor_id`, `infochat.request_id` | not-a-key — PostgreSQL GUCs set via `current_setting` |
| `infochat.adapters.<name>.*`, `infochat.llm.<task>.*` | real — dynamic keys built at runtime, invisible to the grep |
| `infochat.partitions.retention-days`, `infochat.assets.refresh` | real — prefix mentions of key FAMILIES (`.post`, `.post-embedding`, `.coingecko`, …), each member declared |

One site to fix. Re-run at `start`: a second row appearing means the
class grew and this ticket's scope is wrong.

## Acceptance

- The `DEFAULT_BODY_CAP` comment describes the cap as a compile-time
  constant that is deliberately not operator-tunable, in lockstep with
  `docs/design/04-security.md` §4.4 item 5, and names no config key.
- Re-running the §Census enumeration leaves no site in the "fix"
  disposition.
- The diff touches comment text only — no value, no modifier, no code.
- `mvn verify` from the repo root is green.

## Out-of-scope

Nothing in the file's behavior changes: every cap keeps its value,
visibility and enforcement. Making the caps configurable is explicitly
rejected — §4.4 now records the opposite decision. The guard's
allowlists, DNS pinning, redirect cap and header scoping are untouched,
as is the `USER_AGENT` work owned by M1-704.

## Notes

- **File overlap with M1-704.** Both tickets declare
  `SsrfGuardedHttpClient.java` in `files_scope`. They edit different
  regions — M1-704 the `USER_AGENT` constant at `:131` and its javadoc,
  this one the `DEFAULT_BODY_CAP` comment at `:109-111` — and neither
  depends on the other, so no `blocked_by` is set. They must not be
  started as parallel lines: the file-disjointness rule that governs
  parallel drainage already catches this, but it is worth being explicit
  since M1-704 is also pending and runnable.

- **`security_relevant: false` on a security-surface file, deliberately.**
  `scripts/lint-ticket.py` does not WARN on it (checked), but the
  combination is worth defending anyway. The judgment: this diff changes no control, no value, no code
  path, and no untrusted-input handling — it makes a comment stop
  describing a knob that does not exist. A `/redteam` pass over a
  comment-only diff would be ceremony. If the reviewer disagrees, flip
  the flag rather than widening the diff.

- **Cheap follow-on, not committed to here.** After this lands, the
  census above returns clean, so extending M1-708's documented-key gate
  to `src/main` comments would pass on day one. M1-708 out-of-scopes
  source comments on purpose; whether to widen it is a separate call.
