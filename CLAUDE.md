# infochat

Two-service Quarkus application: a news and social-media aggregator chatbot.




## Stack

- Quarkus 3.33 LTS / Java 25 / Maven (multi-module)
- PostgreSQL with `pgvector` extension
- `quarkus-langchain4j` for LLM integration
- SmallRye Reactive Messaging (in-memory channels v1, Kafka optional later)
- Quarkus Scheduler for periodic fetching and group digests
- Pluggable adapter for messaging apps (SimpleX + Signal in v1; one Provider can run any non-empty subset of them simultaneously, decision D46)

## Two services

- **Collector Server** — fetches RSS and social feeds, runs LLM evaluation pipeline (security check, tagging, entity extraction, embedding), stores posts. **No user-facing API.**
- **Provider Server** — talks to messaging apps via one or more pluggable adapters (decision D46). Handles slash commands, chat-mode conversations, periodic group digests. **Only user-facing component.**

## Where things live

- Spec entry point (the map): [docs/SPEC.md](docs/SPEC.md)
- Cross-cutting decisions log: [docs/spec/decisions.md](docs/spec/decisions.md)
- Architecture (service split, pipelines, principles): [docs/spec/architecture.md](docs/spec/architecture.md)
- Security model (threat model, trust boundaries, failure handling): [docs/spec/security.md](docs/spec/security.md)
- Data model (entities, invariants — no DDL): [docs/spec/schema.md](docs/spec/schema.md)
- Commands and chat (surface, catalogue, permissions): [docs/spec/commands.md](docs/spec/commands.md)
- LLM and embeddings (SPI, routing, translation, determinism boundary): [docs/spec/llm.md](docs/spec/llm.md)
- Messaging adapters (contract, capabilities, progress): [docs/spec/messaging.md](docs/spec/messaging.md)
- Asset commands (`/zcash`, `/monero` etc., price/market data): [docs/spec/commands.md](docs/spec/commands.md) §"Asset commands" + design [docs/design/10-asset-commands.md](docs/design/10-asset-commands.md)
- Deployment and configuration (operator inputs, bootstrap, runtime): [docs/spec/deployment.md](docs/spec/deployment.md)
- Verification strategy (what the test suite must prove): [docs/spec/verification.md](docs/spec/verification.md)
- MVP slice (smallest end-to-end build, design-tier): [docs/design/00-mvp.md](docs/design/00-mvp.md)

**Implementation details** (DDL, class names, package layout, property keys,                                                                                                                                                                          
retry counts, regex strings, per-profile values) live under                                                                                                                                                                                           
[docs/design/](docs/design/) — one file per spec section. Design notes carry                                                                                                                                                                          
a "Status: design notes, not spec" banner and may change without a spec                                                                                                                                                                               
amendment.

## Key conventions

- **Slash-prefix only** for commands. No "command mode" toggle.
- **Per-(user, scope) isolation** for state, memory, saves. Never leak across users or between DM and group.
- **Deterministic SQL retrieval; LLM only for ingest evaluation and prose summarization.** The set of posts a command returns must be reproducible.
- **Two admin tiers.** Bot admin (`user.is_admin`) is global; group admin (`group_membership.is_group_admin`) is per-group. Authorization runs in deterministic Java code; admin operations are NEVER exposed as LLM tools.
- **Plain-text formatting** for all bot output. Inline code in single backticks, multi-line in triple backticks; bare URLs (no markdown link syntax). Adapters expose a `supportsCodeFormatting` capability flag for richer rendering where available; v1 adapters additionally assert `supportsMarkdownLinks=false` so the rendering surface cannot silently widen.
- **English by default**, per-scope `/lang <code>` opts into translation via `TranslationProvider` SPI. A source post's stored body is never *rewritten*: a non-English post is translated to English once at ingest into an additional derived field, and the original is retained (D29).
- **Outbox pattern** for the evaluation queue: posts are persisted with `status='RAW'` before being enqueued; a startup rehydrator re-enqueues unfinished work.
- **PostgreSQL LISTEN/NOTIFY** for collector→provider events (no Kafka dependency in v1).
- **Hardware profile** drives sizing: `quarkus.profile` / `QUARKUS_PROFILE` set to `laptop|vps|pi|remote-llm` picks context window, default chat/embedding models and eval concurrency (there is no separate `infochat.profile` key — `InfochatProfile`'s javadoc explains why). The spec's bundle also names the pgvector index type, summary worker count and eval queue depth; **none of those three ships** — v1 creates HNSW on every profile and the other two keys do not exist (`docs/spec/architecture.md` §Hardware profiles). `remote-llm` means local DB/services + remote LLM API; `vps` means everything on a VPS. Individual settings can still be overridden per-property.
- **Asset commands are not posts.** `/zcash`, `/monero` and future per-asset commands store snapshots in a dedicated `price_snapshot` table outside the ingest pipeline — no Stage 1/2, no tagging, no embedding. Every reply names its data source   
  and includes the source URL bare (per-source ToS attribution). Public no-auth endpoints only in v1.

## Verify before recommending

Every claim, recommendation, or suggestion is a hypothesis. Before stating it:

1. **Falsify it.** Ask "what would make this false?" — list those things specifically — and check them. Do NOT check the supporting evidence you already gathered; that's confirmation, not verification. Verification means actively trying to break the claim.
2. **Search for a better option.** Ask "is there an alternative I haven't considered that meets the same goal better?" and look. The proposed approach being workable doesn't make it the right one.
3. **If either step fails, revise or drop the claim before publishing.**

When the recommendation involves an artifact (modifying an existing one OR creating a new one): Read what's already there — the target file for modifications, the surrounding package/directory for creations — to falsify "the gap exists" before stating it. If the existing content already covers what you'd add, drop the proposal and say so. If it partially covers it, narrow the proposal to the actual gap.

Hedge words ("worth considering", "optional", "~5 lines", "minor addition", "one strengthening") and small line counts do not exempt the rule. A 5-line addition is a 100% claim, just compressed.

The shape, not the phrasing, is the trigger: any sentence in which you propose a fact, course of action, or change. Catch yourself before writing it, not after.

## Bootstrap admin & sources

- **Bot admin**: configured **per enabled adapter** in `application.properties` (one bootstrap admin contact id per adapter; the property is keyed by adapter — concrete keys in design notes — and is **optional per adapter** as long as the union across enabled adapters is non-empty). Each value is parsed by its own adapter (SimpleX queue address, Signal ACI, etc.). On startup, an `@Startup` bean ensures, for every adapter that has a configured admin, that the contact exists with `is_admin=true` (creating the user if needed). Audit log records each bootstrap. `/grant-admin` and `/revoke-admin` are scoped to the inbound adapter; last-admin protection counts `is_admin=true` rows globally across adapters (cannot leave the deployment with zero admins; cannot ban self or last admin). See `docs/spec/security.md` §Per-adapter admin threat profile for the SimpleX-vs-Signal threat surface and operator-side mitigations.
- **Group admin**: in an approved group with no active group admin, the first eligible (registered, non-probation, non-banned) `@mention` sender is auto-promoted. `activated_by` records which user activated the group, for accountability only (D47) — it confers no auto-promote priority. Bot admins can override with `/promote` and `/demote`.
- **Sources**: seeded from `bootstrap-sources.json` (path configurable via `infochat.bootstrap.sources-file`). Loader is idempotent: upsert by `(kind, identifier)` — `kind` is the source type (`rss`, `bluesky`, `nostr`, etc.), `identifier` is the URL for HTTP-shaped sources or the filter spec for stream sources (decision D38). The union of `tags` across all bootstrap entries seeds the controlled vocabulary. `/add-source` requires `--tags` (≥1 tag) so every source has a deterministic fallback when LLM tagging fails.

## User registration & ban

- DM access requires an invite code issued by a bot admin (D44). Group interaction requires prior DM registration and admin-approved group status (D47). All newly registered users start in slow-start probation (D45).
- Bot admin can `/ban <contact>` / `/unban <contact>`. Banned users are blocked at message intake; they receive one fixed response and never reach the LLM or any DB query beyond the ban check.

## Build / run quick reference

See [docs/spec/deployment.md](docs/spec/deployment.md) for the spec-level overview and [docs/design/07-deployment.md](docs/design/07-deployment.md) for full operational details.

```bash
# build all modules
mvn clean install

# run collector
mvn -pl infochat-collector quarkus:dev

# run provider
mvn -pl infochat-provider quarkus:dev
```

A `docker-compose.yml` starts Postgres+pgvector, Ollama (default LLM) and both services for local development, with the llama.cpp backends as opt-in compose profiles. The in-memory test adapter is **not** a compose service — it is an in-process CDI bean in the Provider. Production deployments enable one or more of SimpleX / Signal in the same Provider; the in-memory adapter is exercised in a separate test-time deployment shape and never alongside production adapters (decision D46, `docs/spec/deployment.md` §Deployment scenarios).

## Engineering rules (universal — apply to all work in this repo)

These rules apply to every change, ticket-driven or not. The reviewer enforces them; violations are escalated, never silently accepted.

The verbatim text of every rule below — plus the full test-integrity rule list — lives in [docs/process/engineering-rules-verbatim.md](docs/process/engineering-rules-verbatim.md). That file is the single editing source; this section is the always-loaded summary. If the two disagree, the canonical file wins.

### Surgical changes
- Every changed line must trace to either the ticket's acceptance criteria, the user's stated request, or an orphan that your own changes created. If neither, don't change it.
- Don't "improve" adjacent code, comments, or formatting.
- Match existing style even if you'd write it differently.
- If you notice unrelated dead code or a bug, file a follow-up ticket — don't delete or fix it inline.
- Clean up imports/variables that YOUR changes made unused; don't touch pre-existing dead code.

### No workarounds, no shortcuts
- When tests fail, fix the code or escalate — never weaken, disable, or bypass the test. The forbidden test-integrity patterns are enumerated in [engineering-rules-verbatim.md §8](docs/process/engineering-rules-verbatim.md) and enforced by the reviewer.
- When a constraint blocks progress, escalate via the workflow — never use destructive shortcuts (`--no-verify`, `-DskipTests`, `--skip-tests`, force-push) to make obstacles disappear.
- Never trade away a security property the spec states, or a performance budget the spec or ticket states, to reach a goal. Where performance and simplicity conflict and no budget is stated, prefer simplicity.

### Better alternatives surface as proposals, not scope expansion
- If you spot a better approach mid-implementation, complete the ticket as written. Record the alternative in the commit message under an `Alternatives considered:` trailer or file a new ticket. Never silently expand scope to chase the better idea.

### Push back when simpler exists
- If the requested approach has a materially simpler equivalent that meets the same goal, surface it before implementing. This is the one explicit "ask" channel outside the structured escalation; use it sparingly and only for design-level simplifications, not for scope nits.

### Run the full test suite before declaring done
- A ticket is not done when its own new tests pass. Run the full pre-existing suite (`mvn verify` from the repo root) and report regressions, not just the new green checks.

### Never trade rules against each other
- If a ticket's acceptance criteria cannot be satisfied without violating another rule, escalate. Do not pick which rule to violate.

### No defensive code for impossible scenarios
- Don't add error handling, fallbacks, or validation for scenarios that cannot happen given the trust boundary the code lives in. Validation belongs at *system boundaries* — adapter inbound, HTTP endpoints, config parsing, SQL deserialization, LLM tool-call arguments, file I/O. Inside those boundaries, internal code calling internal code is trusted.
- No null-checks for parameters callers cannot legally pass null for; no try/catch around operations that cannot throw; no "just in case" branches.
- Feature flags and backwards-compatibility shims are forbidden — M1 is greenfield, there is no prior version to be compatible with.
- The reviewer applies this rule narrowly: a defensive check at a system boundary is fine; one between two internal classes is scope drift.

### Method parameter contracts
- Method parameter contracts MUST be explicit and machine-checked. Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means "never null." Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); `@NonNull` is no longer written by hand. Validation at system boundaries still uses explicit null-checks per the existing No-defensive-code rule.
- The positive complement to §"No defensive code": that rule prohibits paranoid null-checks; this rule requires the contract that makes paranoia unnecessary. A caller reading the signature can see immediately whether passing null is a legal call or a bug.
- JSpecify (not JetBrains) is the v1 annotation source — the type-use semantics let `List<@Nullable String>` express "list of possibly-null strings," which the JetBrains declaration-only annotations cannot.
- Enforcement is the build, not a hand-check (decision D48): NullAway, built on Error Prone, runs as a compile-time annotation processor with `NullAway:ERROR` active across every module, so a missing or incorrect nullability contract fails `mvn verify`. The reviewer no longer checks annotation presence — the green build is the proof.

### Preserve the controls of a path you replace
- When a change **reroutes or replaces an existing code path**, that path's *incidental* obligations do not travel with its stated purpose. Enumerate what the old path did that the new one's job description omits — sanitize/redaction calls, audit emissions, authorization checks, validation, and the tests pinning any of them — and carry each across or say why it is no longer needed. For a ticket, that enumeration belongs in `acceptance:`, authored at `start`.
- **Granularity is part of the control**: preserving a `sanitize` call but widening its input from one author's field to several concatenated ones changes what the call can reach. State the unit, not just the call.
- **Tests are controls too**: an assertion that incidentally pins a security property is load-bearing beyond its name. Retargeting it onto another code path removes coverage from the path users take.
- Verbatim text and the reviewer's (narrow) application in [`engineering-rules-verbatim.md`](docs/process/engineering-rules-verbatim.md) §10.

### Injectable time in decision logic
- Time that drives a **decision** (any comparison or gate on "now" — scan/retention windows, cooldowns, TTL/expiry, rate-limit windows, probation/ban/invite-expiry timing) must be read from an injected `java.time.Clock` — app-wide producer `ThrottledAdminNotifier.systemUtcClock()`, pinned in tests via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)` — never an inline `now()` (SQL) or `Instant.now()` (Java). Ambient `now()` is a hidden input that can't be pinned without a wall-clock-relative fixture hack — the date-boundary time-bomb M1-398 / M1-400 / M1-444 each fixed one instance of. `ReEvaluationJob` (M1-444) is the reference implementation.
- **Pure audit/record writes are exempt**: `created_at` / `updated_at` / `status_changed_at` stamps and DDL `DEFAULT now()` only *record* time, they don't gate a decision, so they may stay on the DB clock. **Never split one component across two clocks** — if a component reads back its own time-write for a decision, the write and the read use the same clock; moving only the read to the injected Clock is an app-vs-DB skew bug. The reviewer applies this narrowly, to NEW inline time the diff introduces into decision logic (verbatim text and exemptions in [`engineering-rules-verbatim.md`](docs/process/engineering-rules-verbatim.md) §9; the migration backlog of existing sites is M1-447).

## Coding style

These are project-level coding-style preferences. They are NOT reviewer-enforced (the reviewer enforces the §Engineering rules above and the canonical [`engineering-rules-verbatim.md`](docs/process/engineering-rules-verbatim.md)); they are guidelines the developer applies when writing the diff.

### Comment important, crucial, or complex code
- Comment new code that carries an invariant, a hidden constraint, a non-obvious decision, a performance-critical path, or a subtle correctness argument — anything a future reader can't see by reading the code alone. When in doubt about whether code falls into one of these categories, include the comment.
- This **overrides** the system-prompt default of "default to writing no comments" for these categories. The system-prompt rule still holds for ordinary code (well-named identifiers explain themselves there); this section widens the carve-out for important/complex code only.
- Still WHY-not-WHAT: don't narrate code that named identifiers already explain. Don't reference *rotting* context — callers or usage ("used by X", "added for the Y flow"), or a bare reference standing as a comment's only content with no inline WHY ("handles the case from issue #123") — that belongs in the commit message and rots as the codebase evolves. A *stable decision-record anchor* — a ticket ID (M1-NNN), a redteam/audit finding ID, a decision ID (D-NN), or an in-repo `docs/plan/...` path — MAY appear as a supplement to an inline WHY, never as a substitute for it, because those identifiers are immutable and resolve within the repo, so the "rots as the codebase evolves" rationale does not apply to them (unlike caller refs, which do rot).

### Descriptive names
- Prefer fully-spelled, descriptive identifiers over abbreviations. `databaseConnection` reads better than `dc`; `messageAdapter` better than `ma`; `userId` better than `uid`. The cost of a longer name is paid once when typing; the benefit is paid every time the code is read.
- Standard short names are still fine where the convention is universal: `i`/`j` for loop indices, `e` in a catch, `id` for an obvious primary key in scope. The bar is "would a new reader know what this means without context?"
- Apply the same standard to test names — `findByTagReturnsEmptyWhenTagUnknown` beats `testFind3`. Tests are documentation; their names describe behavior.

### Prefer switch expressions
- For multi-branch dispatch over an enum, sealed type, or fixed set of values, prefer a Java switch expression (`switch (x) { case A -> ...; case B -> ...; }`) over a chain of `if`/`else if` or a classic `switch` statement with `break`s.
- Switch expressions are exhaustiveness-checked by the compiler against sealed types and enums, return a value (so the result can be assigned), and have no fall-through. They make the dispatch shape visible at a glance.
- This is a preference, not a hard rule. If the branches genuinely don't fit the dispatch pattern (heterogeneous conditions, side effects per branch, mid-branch returns), fall back to `if`/`else if`.

### Early return / early exit
- Validate inputs and handle special cases at the top of a function with early returns, then write the main path at the bottom unindented. `if (input == null) return EMPTY; ... main work ...` is clearer than wrapping the main work in an `if (input != null) { ... } else { return EMPTY; }`.
- This includes guard clauses for system-boundary validation (NOT internal-code defensive checks — see §"No defensive code" above), short-circuit returns when the result is already known, and breaking out of loops as soon as the answer is found.
- The benefit is reduced indentation: the main logic lives at the function's base indent rather than nested inside a happy-path `if`. A reader can scan the guards and skip to the meat.

### Simplify aggressively
- Already covered by the §Engineering rules: §"No workarounds" ("Where performance and simplicity conflict ... prefer simplicity"), §"Push back when simpler exists" (surface a simpler design before implementing), and the system-prompt rule "Don't add features, refactor, or introduce abstractions beyond what the task requires."
- The bias holds at every level — design, structure, line-by-line. If a simpler form meets the same goal, prefer it. Three similar lines beats a premature abstraction. A flat function beats an unnecessary class. The simpler the implementation, the less commenting it needs (which is the point of pairing this with the comment policy above).

## Context budget heuristics

These are working-style heuristics for keeping the main conversation's context usable across long sessions. They complement (not replace) the system-prompt's general guidance about when to spawn agents.

### Survey via Explore, not sequential Reads
- When you are about to Read 3+ source files in a row to understand an existing API surface ("what's the shape of these classes I'll consume; what method signatures exist; how does this SPI work"), spawn an `Explore` subagent instead. Hand it the file list plus the specific questions; receive a 1–2K summary. The full file bytes stay in Explore's context, not yours.
- Excludes spec/design files under `docs/spec/` and `docs/design/` — read those directly. They are authoritative, and re-reading them later is normal.
- Excludes files you are about to modify — you need the full content in your context for the Edit calls that follow.
- The heuristic is "code-surface survey of files you will not modify". Anything else, prefer direct Read.

### Diagnose framework failures via subagent; diagnose your own-diff failures yourself
- For test failures whose stack lives entirely outside the diff you just wrote — intermittent flakes, infra failures, OOM, long framework traces (Quarkus, Spring, Hibernate internals), Maven plugin errors — route the diagnosis through an `Explore` subagent reading the raw output. Take back a 2–3 line summary plus a suggested fix location.
- Do NOT use a subagent for "the code I just wrote doesn't work" failures. The main session has the diff context the subagent lacks; a fresh subagent would propose any of several plausible fixes without knowing which one respects your recent intent.
- The dividing line: is the failure's signal mostly in the framework's bytes (use subagent) or mostly in the diff's bytes (do it yourself)?

## M1 workflow (in force for the v1 build)

M1 work is ticket-driven via the `/m1-tick` skill. The full rule set — lifecycle, round cap, must-shrink, ticket-readiness pre-flight (deterministic linter + developer self-check), escalation, reviewer, parallelism, `mvn verify` log capture — lives in [`.claude/skills/m1-tick/SKILL.md`](.claude/skills/m1-tick/SKILL.md) §M1 workflow rules and loads when you invoke the skill. The universal workflow specification is [`docs/process/workflow.md`](docs/process/workflow.md); M1-specific framing is [`docs/plan/m1/README.md`](docs/plan/m1/README.md).

Tickets live in `docs/plan/m1/tickets/M1-NNN-<slug>.md`; status board at `docs/plan/m1/STATUS.md` (regenerated, never hand-edited). Invoke `/m1-tick next` to see the next runnable ticket; `/m1-tick start <id>` to begin work. Adversarial security review is a separate skill: `/redteam`.

### Commit prefixes (not every change is a ticket)

The ticket flow exists for code, tests, migrations, and spec changes coordinated with code. Pure-doc edits (spec, design, process, skills, agents) bypass the ticket-readiness pre-flight, reviewer, `mvn verify`, and STATUS regen — commit them directly on `main` with a non-ticket prefix:

| Prefix | When | Example |
|---|---|---|
| `M<N>-NNN:` | Implementation ticket: code, tests, migrations, or spec coordinated with code | `M1-009: Advisory-lock single-instance enforcement + heartbeat` |
| `spec:` | Pure spec/design edit (`docs/spec/`, `docs/design/`), no code change | `spec: Clarify NOTIFY payload tag schema` |
| `process:` | `.claude/`, `docs/process/`, `docs/plan/`, or `CLAUDE.md` edit, no code change | `process: Replace status-regenerator subagent with script` |
| `text:` | Zero-behavior text **inside a source file**: comments, javadoc, and message-only string literals (assertion/log/exception messages). Every `+`/`-` line must sit inside a comment or a string literal — no executable line changes, and never a literal a test asserts on or a user sees | `text: Drop the phantom config key from the body-cap assertion message` |

If a change touches both code and docs, it's a ticket. `git log --grep "^M1-"` keeps cleanly enumerating ticketed work because no non-ticket prefix starts with `M`. Full rules in `docs/process/workflow.md` §Non-ticket commits.
