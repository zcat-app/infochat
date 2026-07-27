---
id: M1-708
title: "Gate docs against nonexistent infochat.* config keys"
status: pending
created: 2026-07-27
last_updated: 2026-07-27
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config/DocumentedConfigKeyParityTest.java
  - infochat-provider/src/test/resources/documented-config-key-exemptions.txt
  - docs/process/engineering-rules-verbatim.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Fixing any doc the new gate flags. The 2026-07-27 audit already
    corrected the known offenders (commit "spec: Reconcile guides and
    design notes with the shipped tree"); if the gate finds more, fix
    them in this ticket only when the fix is a key NAME. A doc stating a
    requirement the code does not deliver must be exempted with its
    reason, never edited to match the code — that is the audit's
    branch-1/branch-2 rule and deleting a documented control to green a
    test would be a test-integrity violation.
  - >-
    `scripts/lint-config-keys.py`. It checks the opposite direction
    (main-source `@ConfigProperty` sites vs `application.properties`),
    it is not wired into any build (no CI workflow, no exec-maven-plugin
    in any pom), and implementing this rule twice in two languages
    invites the two copies to drift. The gate belongs in `mvn verify`.
  - >-
    Widening the scan to `infochat.*` strings in main-source comments or
    javadoc. One such stale reference is known and still open:
    `SsrfGuardedHttpClient.java:111` describes `DEFAULT_BODY_CAP` as "the
    `infochat.fetch.max-body-bytes` default", a key that has never
    existed — and `docs/design/04-security.md` §4.4 has since been
    narrowed to say the caps are deliberately compile-time constants, so
    the comment now contradicts the design too. Fixing it is a one-line
    edit in a `src/main` file, which this test-only ticket must not
    make; M1-709 owns it.
  - >-
    `INFOCHAT_*` environment variables. The env-var surface has a
    different ground truth (compose files, wizard scripts, systemd
    units) and mixing the two ground truths into one gate makes both
    weaker.
  - >-
    `docs/plan/**` and `.scratch/**`. Tickets and audits deliberately
    discuss keys that do not exist yet — a gate over them would fight
    the ticket flow.
  - infochat-collector/**
  - any file under src/main
acceptance:
  - >-
    A test running under `mvn verify` compares every `infochat.*` key
    named in `docs/spec/**`, `docs/design/**` and the root guides
    against the real key set — the `@ConfigProperty` / config-expression
    sites in both services' main sources plus both
    `application.properties` — and fails when a doc names a key that
    does not exist.
  - >-
    The test passes on the tree as it stands, with every legitimate
    survivor carried in a committed exemption list whose entries each
    state WHY they are exempt (dynamic keys built from a prefix such as
    `infochat.adapters.<name>.*` and `infochat.llm.<task>.*`, the
    PostgreSQL GUCs `infochat.actor_id` / `infochat.request_id`, bare
    prefixes, the two deliberate "this key does not exist" statements,
    and the GAP-marked keys the audit left standing).
  - >-
    A test method proves the check actually detects drift: fed a
    synthetic doc line naming a key that is not in the real key set and
    not exempt, the checker reports it. Without this the gate could pass
    vacuously — an extraction regex that matches nothing also finds no
    drift.
  - >-
    A test method proves an exemption cannot be a blanket wildcard: an
    entry must match a bounded pattern, so a future `infochat.*` catch-all
    cannot silently disable the gate.
  - >-
    docs/process/engineering-rules-verbatim.md records the gate in one
    line so a doc author knows the build enforces key names, mirroring
    how the command-catalogue gate is documented.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/config/DocumentedConfigKeyParityTest.java
      — the parity assertion, the drift-detection self-test, and the
      no-wildcard-exemption assertion.
    - >-
      infochat-provider/src/test/resources/documented-config-key-exemptions.txt
      — the committed exemption list, one entry per line with its reason.
  preserves:
    - >-
      CommandCatalogueParityTest — the existing doc-vs-code gate. Its
      file-location and working-directory handling is the pattern to
      copy; do not refactor it into a shared base class as part of this
      ticket.
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Configuration surface (spec level)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-708: Gate docs against nonexistent `infochat.*` config keys

## Context

The doc-drift audit of 2026-07-27 found ~15 documented `infochat.*`
config keys that do not exist — `infochat.embeddings.provider`,
`infochat.llm.chat-agent.*`, `infochat.collector.fetch-interval`,
`infochat.collector.linking-interval`, `infochat.collector.ttl-prune-cron`,
`infochat.provider.digest-tick-cron`, `infochat.rate.user-*`,
`infochat.ratelimit.llm-ops-per-minute`,
`infochat.ratelimit.tool-calls-per-turn`, and more — spread across
`docs/design/07-deployment.md` §7.4, `docs/design/04-security.md`,
`docs/design/05-llm-and-embeddings.md` and three root guides. All were
fixed by hand in that pass. Nothing stops the next one.

Every other doc-vs-code contract in this repo that matters is
build-gated: `CommandCatalogueParityTest` pins the command index in
`docs/spec/commands.md` against the CDI bean graph, `/help` and the
privileged-tier list, so name and tier drift cannot reach `main`. The
configuration surface has no equivalent, which is why the drift
accumulated silently across ~15 keys before an audit went looking.

The audit's own recommendation (`.scratch/doc-audit.md` §E4) was to
extend `scripts/lint-config-keys.py`. That script exists and is useful,
but it is not wired into any build — there is no CI workflow and no
exec-maven-plugin in any pom — so extending it would produce a check
nobody runs. `mvn verify` is where this repo's gates live.

## Census

The gate's own ground truth is the enumeration; the audit records it and
it reproduces:

    # real keys: @ConfigProperty + config-expression sites, plus both properties files
    { grep -rhoE '"infochat\.[a-zA-Z0-9._-]+"' --include=*.java infochat-*/src/main/java | tr -d '"'
      grep -rhoE '\{infochat\.[a-zA-Z0-9._-]+' --include=*.java infochat-*/src/main/java | tr -d '{'
      cat infochat-*/src/main/resources/application.properties \
        | grep -oE '^%?[a-zA-Z-]*\.?infochat\.[a-zA-Z0-9._-]+' | sed -E 's/^%[a-zA-Z-]+\.//'
    } | sed 's/[.:]$//' | sort -u

    # doc-referenced keys
    grep -rhoE '\binfochat\.[a-z][a-zA-Z0-9._<>*-]*[a-zA-Z0-9>]' docs/spec docs/design *.md | sort -u

| Site class | Disposition |
|---|---|
| `docs/spec/**`, `docs/design/**`, root `*.md` guides | in scope — the gate's input |
| Dynamic keys (`infochat.adapters.<name>.*`, `infochat.llm.<task>.*` built from `ModelTask.configPrefix()`) | exempt with reason — the grep cannot see them, they are real |
| PostgreSQL GUCs (`infochat.actor_id`, `infochat.request_id`) | exempt with reason — set via `current_setting`, not Quarkus config |
| Bare prefixes (`infochat.fetch.`, `infochat.llm.`) used as prose | exempt with reason |
| Deliberate "this key does not exist" statements | exempt with reason — the doc is asserting absence on purpose |
| GAP-marked keys the audit left standing (A1–A6) | exempt with reason, each pointing at the gap that owns it; the exemption is removed when the gap closes |
| `docs/plan/**`, `.scratch/**` | out-of-scope — tickets discuss keys that do not exist yet |
| `INFOCHAT_*` env vars | out-of-scope — different ground truth |

Re-run both greps at `start`: the survivor list is the exemption list,
and it must be ~18 entries. A materially larger survivor set means the
real-key extraction is wrong, not that the docs are.

## Acceptance

- A `mvn verify` test fails when `docs/spec/**`, `docs/design/**` or a
  root guide names an `infochat.*` key that is neither in the real key
  set nor exempt.
- It passes on the current tree, with every survivor carried in a
  committed exemption list that states each entry's reason.
- A test method proves the checker reports a synthetic unknown key —
  the gate must be shown to detect drift, not merely to pass.
- A test method proves an exemption entry cannot be an unbounded
  wildcard.
- `docs/process/engineering-rules-verbatim.md` gains a one-line record
  of the gate.
- `mvn verify` from the repo root is green.

## Out-of-scope

The gate does not edit docs to make itself pass: a doc that names a
*requirement* the code has not built keeps the requirement and gets an
exemption citing the gap that owns it. `scripts/lint-config-keys.py`
stays as it is — it checks the other direction and lives outside the
build; two copies of one rule drift. Main-source comments,
`INFOCHAT_*` env vars, `docs/plan/**` and `.scratch/**` are all outside
the scan for the reasons in `out_of_scope`. `CommandCatalogueParityTest`
is the pattern to copy, not to refactor.

## Notes

- **Why a test and not a script.** `CommandCatalogueParityTest.java:81`
  reads `Path.of("..", "docs", "spec", "commands.md")` and reports the
  surefire working directory in its failure message — the precedent for
  a module test reading repo-root docs already exists and handles the
  working-directory trap. Copy that shape.

- **The vacuous-pass hazard is the real risk here.** A parity test whose
  extraction regex silently matches nothing passes forever and reads as
  coverage. That is why the drift-detection self-test is an acceptance
  item rather than a nice-to-have, and why the exemption list must not
  admit a wildcard.

- **Exemptions are a ledger, not a suppression mechanism.** Each entry
  names why the key is absent from the real set. When M1-705 / M1-706 /
  M1-707 land, their GAP exemptions come out with them — an exemption
  outliving its gap is the failure mode to watch for.
