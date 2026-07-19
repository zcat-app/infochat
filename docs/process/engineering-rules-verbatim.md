# Engineering rules — canonical verbatim text

This file is the **single editing source** for the engineering rules and the test-integrity rules. The text below is what `CLAUDE.md` summarises; the code-reviewer subagent reads this file directly at runtime (input #4 of `docs/process/reviewer-prompt.md`). If `CLAUDE.md` and this file disagree on a rule's wording, this file wins — sync `CLAUDE.md`.

> **Why the CLAUDE.md duplication exists.** The `CLAUDE.md` summary is always-loaded context for the developer session; this file carries the full verbatim text the reviewer applies. Only the summary is duplicated — the reviewer reads this file itself, so there is no embedded copy of the rules in the reviewer prompt.

---

## §1 Surgical changes

- Every changed line must trace to either the ticket's acceptance criteria, the user's stated request, or an orphan that your own changes created. If neither, don't change it.
- Don't "improve" adjacent code, comments, or formatting.
- Match existing style even if you'd write it differently.
- If you notice unrelated dead code or a bug, file a follow-up ticket — don't delete or fix it inline.
- Clean up imports/variables that YOUR changes made unused; don't touch pre-existing dead code.

## §2 No workarounds, no shortcuts

- When tests fail, fix the code or escalate — never weaken, disable, or bypass the test. The forbidden test-integrity patterns are enumerated in §8 and enforced by the reviewer.
- When a constraint blocks progress, escalate via the workflow — never use destructive shortcuts (`--no-verify`, `-DskipTests`, `--skip-tests`, force-push) to make obstacles disappear.
- Never trade away a security property the spec states, or a performance budget the spec or ticket states, to reach a goal. Where performance and simplicity conflict and no budget is stated, prefer simplicity.

## §3 Better alternatives surface as proposals, not scope expansion

If you spot a better approach mid-implementation, complete the ticket as written. Record the alternative in the commit message under an `Alternatives considered:` trailer or file a new ticket. Never silently expand scope to chase the better idea.

## §4 Push back when simpler exists

If the requested approach has a materially simpler equivalent that meets the same goal, surface it before implementing. This is the one explicit "ask" channel outside the structured escalation; use it sparingly and only for design-level simplifications, not for scope nits.

## §5 Run the full test suite before declaring done

A ticket is not done when its own new tests pass. Run the full pre-existing suite (`mvn verify` from the repo root) and report regressions, not just the new green checks.

If a pre-existing test unrelated to the diff fails, first rule out environment causes: another `mvn verify` running in a parallel worktree (Quarkus ITs bind the shared test port 8081 — check `pgrep -af "clean verify"`), or stale sibling-module SNAPSHOTs pulled from the shared `~/.m2` (always add `-am` to module-targeted runs). With the environment clean, re-run once: if green, report the flake (test name, failure mode) alongside the result — do not "fix" the flaky test inline (§1 applies). A second clean-environment failure of the same test is a regression: escalate.

## §6 Never trade rules against each other

If a ticket's acceptance criteria cannot be satisfied without violating another rule, escalate. Do not pick which rule to violate.

## §7 No defensive code for impossible scenarios

- Don't add error handling, fallbacks, or validation for scenarios that cannot happen given the trust boundary the code lives in.
- **Trust boundary, defined for this repo:** validation belongs at *system boundaries* — adapter inbound (messages from SimpleX/Signal/in-memory), HTTP endpoints, JSON/YAML config parsing, SQL deserialization, LLM tool-call arguments, file I/O. Inside those boundaries, internal code calling internal code is trusted: no null-checks for parameters that callers cannot legally pass null for, no try/catch around operations that cannot throw, no "just in case" branches.
- Feature flags and backwards-compatibility shims are forbidden when the change can simply be made. M1 is a greenfield build; there is no prior version to be compatible with.
- The reviewer applies this rule narrowly: a defensive check at a system boundary is fine; a defensive check between two internal classes is scope drift.

## §7a Method parameter contracts

- Method parameter contracts MUST be explicit and machine-checked. Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means "never null." Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); `@NonNull` is no longer written by hand. Validation at system boundaries still uses explicit null-checks per the existing No-defensive-code rule (§7).
- The positive complement to §7: that rule prohibits paranoid null-checks; this rule requires the contract that makes paranoia unnecessary. A caller reading the signature can see immediately whether passing null is a legal call or a bug.
- JSpecify (not JetBrains) is the v1 annotation source — the type-use semantics let `List<@Nullable String>` express "list of possibly-null strings," which JetBrains declaration-only annotations cannot.
- Enforcement is the Maven build, not a reviewer hand-check (decision D48): NullAway, built on Error Prone, runs as a compile-time annotation processor with `NullAway:ERROR` active across every module, so a missing or incorrect nullability contract fails `mvn verify`. The reviewer no longer checks annotation presence — the green build is the proof.

## §8 Test-integrity rules

The reviewer's `TEST-INTEGRITY-CHECK` fails if the diff introduces any of the following.

### Syntactic (also catchable by a pre-commit grep gate)

- `@Disabled`, `@Ignore`, `assumeTrue(false)`, JUnit `assumptions` that always skip
- New `@Test` body that is empty, only contains `assertTrue(true)` / `assertNotNull(null)`-style trivialities, or only logs
- `mvn ... -DskipTests`, `-Dmaven.test.skip=true`, or `--no-verify` in any committed file (scripts, CI, docs except this one) — **except** a `RUN mvn ... -DskipTests` line inside a multi-stage `Dockerfile` *build stage*, where Testcontainers-backed tests cannot run (no docker-in-docker) and the deployable image is a build artifact, not a test surface. The host `mvn verify` gate stays the sole test authority and is unaffected by the image build, so this skip hides no obstacle. The exception is narrow and grep-shaped (a `Dockerfile` build-stage `RUN` line only); a skip flag anywhere else — scripts, CI, the verify gate — remains forbidden. (M1-379)
- `git push --force` / `git reset --hard` in any committed script

### Semantic (only the reviewer can catch these)

- Existing assertions weakened (e.g. `assertEquals(specific, actual)` → `assertNotNull(actual)`, `assertTrue(actual > 0)` replacing a precise check).
- New `catch (Exception ignored) {}` or silent-swallow blocks in production code.
- New `@MockBean` / `@Mock` replacing previously-real wiring inside an integration test (mocks belong in unit tests; integration tests integrate).
- A test was modified to match a new (wrong) behavior rather than the code being fixed to match the test.
- A test was deleted or renamed without an accompanying explanation tying it to a deliberate spec change.

### Test-modification authorization

- If a pre-existing test was modified, the ticket body OR the commit message MUST explicitly authorize the change ("this ticket changes behavior X, which requires updating TestY at line Z"). Absent explicit authorization, the reviewer treats every modification to a pre-existing test as suspicious and fails `TEST-INTEGRITY-CHECK`.
- "Authorized" means the ticket says, in plain language, what the new test should assert and why. A retroactive justification ("we needed to update the test because the code changed") is not authorization; it's circular reasoning.

### Stack-specific (this repo: PostgreSQL + pgvector)

- Replacing a Testcontainers PostgreSQL test with H2, HSQLDB, or any in-memory substitute is forbidden. pgvector and several Postgres-specific behaviors do not have viable in-memory equivalents; an in-memory substitute that "passes" is silently testing different code paths.
- A new integration test that touches the database MUST use Testcontainers PostgreSQL (matching the version pinned in the parent pom). Unit tests that do not touch the database may use plain JUnit without containers.

### Assertion adequacy (applies to tests the diff ADDS)

The reviewer's `ASSERTION-ADEQUACY-CHECK` asks whether a diff's new tests actually *constrain* what the diff claims. The suite is the reviewer's oracle — §Acceptance treats the test log as confirming or denying each acceptance item — so a test that asserts honestly but in the wrong place lets a diff pass every other check while the user-visible behavior is wrong. The check asks exactly two questions and no others.

- **Boundary siting.** For a value the diff introduces that reaches a user or an external surface (a rendered reply, an adapter's outbound payload, an HTTP response, a persisted row another component reads back), at least one assertion must live at the **end** of that path, not only at the point of production. Asserting on a producer's return value proves the producer; it does not prove what the consumer emits. (M1-648: `HelpLookupToolIT` asserted the tool's pre-sanitizer return value; `LlmOutputSanitizer` then redacted every command the tool resolved, and ordinary DM users got `Use [redacted command] <id>`. Clarity, review and redteam were all green, because no test traced the value past the tool boundary.)
- **Non-vacuity.** For each **new** test the diff adds, it must be possible to name a concrete mutation of the diff's *own production code* that the test would catch. A test no mutation can fail is decoration. (M1-651: the shipped guard's only completeness check was non-emptiness, so a blank line mid-bullet cut the guarded set from 19 commands to 8 and the guard still passed.)

**A FAIL must name its artifact.** `FAIL` requires the reviewer to name either the specific surviving mutation or the specific unasserted boundary, with a `file:line`. A FAIL that cannot name one is not a valid FAIL — downgrade it to `WARN` or `PASS`. This is the load-bearing constraint: it makes the check a bounded, checkable question rather than an invitation to look for problems, which is the shape the reviewer is empirically good at. Without it, a new blocking check degenerates into noise on the large majority of tickets that are fine, followed by the check being overridden into irrelevance.

`NOT-APPLICABLE` (with a one-line reason) is the verdict when the diff adds no new test, so documentation-only and process-only diffs pass through without noise.

**Scope boundaries — this rule owns only newly-added tests.** Modifications to *pre-existing* tests remain the territory of §8 Semantic and §8 Test-modification authorization above; this rule never adjudicates them. Whether a class-scoped ticket enumerated its class is the clarity gate's CLASS-COMPLETENESS check, not this one — that check asks whether the ticket *enumerated* its class, this one asks whether the tests *constrain* what the diff claims. The two must not overlap.

### Round-N must-shrink (applies to every rework round, N ≥ 2)

- On round-N review (N ≥ 2), the diff is compared to round-(N−1) along three dimensions: files-touched count, net lines added, and net lines removed. Every rework round is a *fix-only* round; if the rework grew along **all three** dimensions simultaneously vs the previous round, that is scope-creep-during-rework and `SCOPE-DRIFT-CHECK` fails automatically. Growth along all three is the **only** failure condition — growth along one or two dimensions, or holding all three equal, is permitted (the rework is still convergent overall).
- This applies to round 2 (default cap) AND to round 3 (only reachable when the ticket sets `round_cap: 3`). Round-cap-3 tickets are typically `complexity: high` or `risk: high` — under-specifying must-shrink on round 3 would weaken the rule precisely when stakes are highest.
- The reviewer's prompt receives both the current-round and previous-round diff stats so the comparison is mechanical. On round 1 the previous-round substitution is the literal sentinel `(N/A — round 1, no previous round)` and the rule does not apply.
- Exception: growth is permitted when required by a citable mandate — a round-(N−1) REWORK item whose fix necessarily grows the diff (e.g. "extract this into a helper used by three callers", "add the missing coverage for case X"), or an in-branch redteam-finding remediation the user accepted. The developer must cite the REWORK item or finding ID in the round-N commit message. Without the citation, growth → fail.

### Test-integrity violations are not developer-overridable

A `FAIL` on `TEST-INTEGRITY-CHECK` is never `REWORK`-able by the developer alone; the reviewer must escalate to `MANUAL` if the developer's stated rationale is "this is fine because ...". The user is the only one who can override test-integrity violations.

## §9 Injectable time in decision logic

- Time that drives a **decision** — any comparison or gate whose outcome depends on "now": scan/retention windows, cooldowns, TTL/expiry checks, rate-limit windows, probation/ban/invite-expiry timing — MUST be read from an injected `java.time.Clock`, never from an inline `now()` (SQL) or `Instant.now()` (Java) embedded in that logic. The injected Clock is the app-wide `@Produces @ApplicationScoped Clock` (`ThrottledAdminNotifier.systemUtcClock()`); tests pin it with `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`. The reason: ambient `now()` is a hidden input — it cannot be pinned in a test without a wall-clock-relative fixture hack, which rots into a date-boundary time-bomb (the failure M1-398, M1-400, and M1-444 each fixed one instance of). M1-444 (`ReEvaluationJob`) is the reference implementation.
- **Pure audit/record writes are exempt.** A timestamp that only *records* when something happened and is never read back to gate a decision — `created_at` / `updated_at` / `status_changed_at` column writes and Flyway `DEFAULT now()` — MAY stay on the database clock (the system-of-record convention). The rule targets time *read for a comparison*, not time *written for the record*.
- **Never split one component across two clocks.** If a component reads back its own time-write to make a decision (e.g. a cooldown gate comparing a `last_*_at` it stamped), the write and the read MUST use the same clock — moving only the read to the injected Clock while the write stays on SQL `now()` creates an app-vs-DB skew bug. Either both move to the injected Clock or both stay on the DB clock; never one of each.
- The reviewer applies this rule **narrowly and to NEW code the diff introduces** (mirroring §7's narrow application): a new inline `now()` / `Instant.now()` in decision logic is a violation; an audit-only write is not; pre-existing inline time the diff does not touch is the migration backlog (M1-447), not this diff's fault. A violation is a REWORK item citing the file:line and the injected-Clock pattern.

---

## When to update this file

- A new universal rule emerges from a retrospective.
- A new shortcut pattern is observed in practice and needs codifying.
- The stack changes (e.g. Postgres swapped for something else; the pgvector rule above must be re-evaluated).

When this file changes, no re-render is needed — the reviewer reads this file directly at runtime. Keep the `CLAUDE.md` summary, the `docs/process/workflow.md` §Round-N must-shrink paragraph, the m1-tick `SKILL.md` must-shrink bullet, and the reviewer prompt's brief must-shrink restatement (verdict-format section) in sync by hand; there is no automated check.
