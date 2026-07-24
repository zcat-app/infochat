# Guide-accuracy audit — periodic falsification of the operator/user guides

This procedure catches the **behavior-prose drift** that no build check can:
a flag described wrongly, an order-of-operations that no longer holds, a setup
step that points at a binary the image now bundles, a default path that moved.
It is the standing complement to the build-time command-catalogue parity test
([M1-527](../plan/m1/tickets/M1-527-command-catalogue-parity-test.md)): that
test stops **name** drift (a command in code but uncatalogued, or a doc naming a
command that does not exist) at `mvn verify`; this audit stops everything that
requires *reasoning over ground truth*, which a regex or a set-equality check
cannot do.

The two mechanisms are disjoint by design. Do not try to fold behavior checks
into the parity test (it would need executable doc-tests that run `setup.sh`,
far costlier than they are worth) and do not try to lint guide prose for command
names (false-positive-ridden — file paths, URLs, and negative mentions like
"there is no `/list-users` command" all match). Run the test every build; run
this audit at the cadence below.

## When to run

Event-driven, not on a timer:

- **Before tagging a release or closing a milestone** — the primary gate.
- **After a change that moves operator-facing behavior** — a new/removed config
  key or env var, a wizard step reorder, a bundled-binary change, an adapter
  bootstrap change (the SimpleX claim-token switch M1-506/507 is the canonical
  example of a behavior change that silently invalidated guide prose).
- **On demand** when an operator reports that a documented step did not work.

It is advisory and post-hoc by nature: it catches drift that already shipped,
because the claims it checks are not machine-gateable. That is the correct tool
for non-machine-checkable prose — accept the post-hoc property rather than
pretend a lint covers it.

## Scope — the six root guides

| File | Audience | Highest drift risk |
|---|---|---|
| `README.md` | Everyone (routing entry point) | Fabricated example commands; stale doc links |
| `SETUP_GUIDE.md` | Operator (clone → running bot) | **Behavior prose** — bundled binaries, env keys, wizard step order, backup defaults, captcha/registration flow |
| `ADMIN_GUIDE.md` | Bot/group admin | Confirm mechanism, permission tiers, missing/renamed commands |
| `USER_GUIDE.md` | End user | Command flags, confirm prompts, asset sub-verbs |
| `OVERVIEW.md` | Architecture reader | Pipeline-stage and module enumerations that silently gain a stage; provider/adapter lists |
| `CONTRIBUTING.md` | Contributor | Workflow mechanics that moved — gate names and ordering, frontmatter field semantics, which harnesses and skills exist |

`SETUP_GUIDE.md` carries the most behavior risk and the most cost when wrong;
weight effort there.

`OVERVIEW.md` and `CONTRIBUTING.md` were added to this scope on 2026-07-24: the
pre-release audit found drift that had survived the previous run precisely
because those two files sat outside it (a stale ingest-stage list and a
`files_budget`-vs-`files_scope` reversal). Their ground truth is not the guides'
— `OVERVIEW.md` checks against the code and `docs/spec/`, `CONTRIBUTING.md`
against the skills under `.claude/skills/` and `.agents/skills/`, the scripts
under `scripts/`, and `docs/process/{workflow,harness-mapping,ticket-template}.md`.

## Method — one falsification agent per file, in parallel

Spawn one `general-purpose` (or `Explore`) subagent **per file**, concurrently.
Each agent does NOT trust the doc: it extracts every checkable claim and tries to
**break** it against ground truth, then classifies each finding. Hand each agent
the target file plus the ground-truth source list below.

### Ground-truth sources (the audit checks docs AGAINST these, never doc-vs-doc)

- `prod/setup.sh`, `prod/switch-llm.sh`, `prod/scripts/*.sh` (the numbered wizard
  steps `0-doctor` … `8-verify`, plus `apps.sh`, `backup.sh`, `upgrade.sh`)
- `prod/config/secrets.env.example`, `bootstrap-sources.json`,
  `bootstrap-assets.json`
- `docker-compose.yml`, `infochat-provider/src/main/docker/Dockerfile.jvm`
  (what binaries are bundled)
- `infochat-*/src/main/resources/application.properties` (profiles, real keys)
- `docs/spec/**` and `docs/design/**` (authoritative; the guides render these for
  operators — when guide and spec disagree, the spec wins and the guide is the bug)
- Java under `infochat-*/src/main/java` (command handlers, behavior claims)
- For `CONTRIBUTING.md` only: the skills under `.claude/skills/**` and
  `.agents/skills/**` (which exist, their subcommands, the exact menu options),
  `scripts/*`, and `docs/process/{workflow,harness-mapping,ticket-template}.md`

### Classification scheme

Each finding is exactly one of: **CORRECT** · **WRONG** (contradicts ground
truth — give the truth) · **OUTDATED** (was true, behavior changed) · **MISSING**
(an operator/user-needed gap) · **BURIED** (present but hidden under verbose
text) · **DEADLINK** (internal xref/path that does not resolve).

### Per-file agent prompt template

```
You are a documentation-accuracy auditor for the infochat repo (two-service
Quarkus messaging-app chatbot: Collector + Provider). FALSIFY claims in ONE
file: {FILE}. Do not trust the doc — verify every checkable claim against the
real repo and report mismatches.

TARGET: {ABSOLUTE_PATH_TO_FILE}

GROUND-TRUTH SOURCES (read/grep as needed):
{paste the ground-truth source list above}

METHOD — for EVERY checkable claim (command, path, env/property key, flag, port,
service name, version, behavior, order-of-operations, success-check, xref):
  1. Quote it + line number.
  2. Find ground truth; actively try to BREAK the claim (do not just confirm).
  3. Classify: CORRECT | WRONG (give truth) | OUTDATED | MISSING | BURIED | DEADLINK.

DELIVERABLE (final message, no preamble):
  1. Findings list: line ref | claim | classification | truth/fix. Prioritise
     WRONG / OUTDATED / MISSING — those break a real install or use.
  2. Counts per classification.
  3. For SETUP_GUIDE only — the happy-path test: can a non-expert follow the file
     start-to-finish for the DEFAULT path (SimpleX-only + local LLM) without
     hitting a wrong/missing step? List every place the happy path breaks.
  4. Verdict for THIS file: REWRITE | FACELIFT | KEEP, with justification and an
     estimate of the salvageable fraction.
Be concrete and terse; quote line numbers.
```

The orchestrator does NOT read the per-file transcripts (they are large); it
takes each agent's final summary, then assesses two cross-cutting things the
per-file agents cannot: **doc architecture** (is content in the right file, or
duplicated/misplaced?) and **navigability** (does README route a non-expert to
the right guide?).

## Acting on results

1. **Triage** by classification. WRONG/OUTDATED on `SETUP_GUIDE` are
   install-breaking → fix first. MISSING next. BURIED is a de-verbosify pass.
2. **One scoped fix-ticket**, not a rewrite. The 2026-06-30 audit found the four
   guides in scope at the time ~90 %+ accurate; the standing expectation is
   FACELIFT, not REWRITE. A REWRITE verdict on any file is a signal to escalate,
   not to start typing.
3. **Fix code, never docs, when the two disagree on behavior** — the guides must
   describe what IS. If the audit shows a doc is right and the code is wrong,
   that is a code bug, filed separately.
4. **Verify each fix against ground truth** (file:line in the scripts/code), not
   against another doc — the same rule the audit itself follows.

## Reference run

The 2026-06-30 audit (the run that produced this procedure) is the worked
example: four parallel agents, six load-bearing defects found (bundled
signal-cli framing, `/backups` default dir, Signal captcha/register flow, the
SimpleX claim-token section in ADMIN_GUIDE, the `… confirm` keyword mechanism,
`/recover-pool` missing), folded into a single accuracy ticket
([M1-509](../plan/m1/tickets/M1-509-operator-onboarding-simple-advanced-guides.md))
— not a rewrite.
