# Shared memory index (tier 1 — committed, portable)

Durable project knowledge that survives any single session and applies to any
checkout: build/test gotchas, process lessons, architectural facts that cost
real time to rediscover. Read this index at session start; open only the
entries relevant to your task.

**One file per fact.** Frontmatter is `name`, `description`, `metadata.type`
(`project` | `feedback` | `reference`). Cross-link with `[[name]]`.

**What belongs here:** anything true of the repo regardless of who runs it or
where. **What does NOT:** bot contact addresses, live-user data, host paths,
prod runtime state, or anything else that would leak operational detail into a
fork — those go in `.agents/memory-local/` (gitignored, absent in fresh
clones). Harness-specific quirks belong in your own tool's memory, not here.
Committed with a `process:` prefix.

Claude Code additionally auto-loads its own user-level store; it points here,
and this store is the shared one both worlds read.

## Process and workflow

- [Reviewer is a conformance gate, not a correctness gate](reviewer-is-conformance-not-correctness.md) — measured over 670 tickets: review APPROVEs 88% first-pass and 61% of its rejections are bookkeeping; redteam found real bugs in 13 already-APPROVEd diffs. An APPROVE means "matches its ticket", not "is correct".
- [Redteam remediation needs a re-audit](redteam-remediation-needs-reaudit.md) — an in-branch fix invalidates the audit it answers; re-audit the NEW diff and explicitly permit a CLEAN verdict or the auditor manufactures findings.
- [Redteam diff-range gap](redteam-diffrange-refine-uncommitted-gap.md) — the `--in-progress` range is EMPTY at every pre-commit redteam gate (branch has 0 commits); a CLEAN verdict from an empty diff is the silent failure. Audit the working-tree diff vs fork point.
- [deep-review full is a sampling audit](deep-review-full-samples.md) — "all lows" means "this sample surfaced lows"; for real coverage partition every file into small per-slice agents, and run that exhaustive form on a cheap fast model.
- [Investigation tickets shouldn't traverse the full cycle](investigation-ticket-flow-too-heavy.md) — when the deliverable is a finding, decompose into close-investigation + a fresh properly-gated implementation ticket.
- [ID claims are invisible to registries](id-claims-invisible-to-registries.md) — decision numbers AND ticket IDs get claimed in pending/untracked artifacts the registry can't see; grep the claimants, not the index.
- [The in-progress precondition is blind to worktrees](m1-tick-start-precondition-blind-to-worktrees.md) — a ticket started in a git worktree is invisible to a frontmatter grep; pair it with `git worktree list`.
- [A concurrent session can commit to your branch](concurrent-session-committed-to-my-branch.md) — in a shared checkout, re-check `git log <fork>..<branch>` right before merge and squash ONLY your own sha.
- [Ask, don't assume (ambiguous or irreversible)](ask-dont-assume-ambiguous-or-irreversible.md) — a stop/kill word arriving while background work runs is ITSELF the stop sign; ask, however obvious the reading feels.
- [Persist reached decisions](persist-reached-decisions.md) — a decision reached in a session is a deliverable; commit it or write it here before the session ends.
- [Verify subagent quotes before pinning them](verify-subagent-quotes-before-pinning.md) — survey-agent "exact quotes" can be invented paraphrases; Read the line yourself before an acceptance item asserts it.
- [Audit claims vs sequences](audit-claims-vs-sequences.md) — claim-by-claim doc checks are STATELESS and miss ordering bugs; walk every numbered path as a state machine, per variant. Cost: a wrong README step 2 survived a full pre-release audit.
- [Doc-only edits skip verify](doc-only-edits-skip-verify.md) — run `mvn verify` only when a script/db/code/config file is in the diff.
- [Green-log freshness is per-test, not per-build](green-log-freshness-per-test-not-per-build.md) — a `BUILD SUCCESS` timestamp is NOT when a given test read its input file; an edit to `docs/spec/security.md` can postdate the parity test that parses it while predating the build's end, so the freshness gate silently passes on a suite that never saw it.
- [Release state: read the truth doc, rank your sources](release-state-source-ranking.md) — live DB/config/git > committed docs > scratch handoffs > memory; always read the date.
- [v1.0.0 tag was pulled pre-announcement](v1-0-0-tag-pulled-pre-announcement.md) — tagged at `3fb97365`, deleted local+origin 2026-07-25 so M1-687..690 ship first; there is currently NO release tag, and that is expected.

## Build, test and CI mechanics

- [mvn -Dtest filtering is blocked by a tripwire](mvn-dtest-filter-blocked-by-tripwire.md) — cross-module `-Dtest`/`-Dit.test` always fails (parent POM `failIfNoTests=true`); targeted runs are module-scoped and UNFILTERED.
- [Clean-verify monitoring](clean-verify-monitoring.md) — pause the live stack for the whole batch, launch the verify detached, never gate a poll loop on a self-matching `pgrep`.
- [Full-suite timing flakes](full-suite-timing-flakes.md) — three flake classes, all fixed by construction; a red in these is now a REAL regression. The hazard pattern: a supervised subprocess stand-in that dies at launch runs crash recovery during your test.
- [A green suite can be an environmental accident](green-suite-can-be-environmental-accident.md) — an unstubbed SPI passes while a real local service happens to answer; the tell is a stub with zero calls.
- [Scan-window fixture time-bombs](scan-window-fixture-timebombs.md) — absolute fixture dates age out of pickup windows and go vacuously green; a guard test blocks new ones, SQL-literal seeds still evade it.
- [IT stranger-bucket silent drop](it-stranger-bucket-silent-drop.md) — an IT seeding users via direct SQL is a stranger at intake and gets silently dropped late in the full suite; mark the contact registered after the INSERT.
- [Bundle keys need a cs twin](bundle-key-needs-cs-twin.md) — an en-only bundle key fails the build (bilateral keyset parity); scope both files up front.

## Codebase couplings and design facts

- [Handler input is not always normalized](handler-input-not-always-normalized.md) — the router's NFKC pass exempts fenced code while routing reads only line 1, so normalize at the check; never enumerate blank-rendering codepoints.
- [The reflection guard is error-scoped by design](reflection-guard-is-error-scoped-by-design.md) — a green guard does NOT mean reflection is impossible; the reply/success blind spot is disclosed, not overlooked. Don't re-litigate widening it.
- [New admin commands trip three couplings](new-admin-command-couplings.md) — IT naming guard, sanitizer closed-list parity, and audit-before-effect for privileged PII reads.
- [D59 world predicate and bundle-copy gotchas](d59-world-predicate-and-bundle-copy-gotchas.md) — the periodic digest is `DigestPostCollector` (9 predicate sites); bootstrap-origin fixtures leak to every scope; change copy by editing a key's VALUE.
- [Quarkus fast-jar live debugging](quarkus-fastjar-live-debug.md) — transformed-bytecode.jar shadows lib/main so jar overlays silently no-op; adapter dispatch threads carry a foreign context classloader.
- [git add with a stale pathspec stages nothing](git-add-stale-pathspec-silent-nostage.md) — all-or-nothing pathspecs; `RM` in status means your rewrite is UNSTAGED.

## Open TODOs

- [Recheck mutation testing (pitest) after this batch](mutation-testing-recheck-after-batch.md) — user-requested 2026-07-25, research deliberately deferred; motivated by M1-689's vacuous-pass tests that stayed green while asserting nothing.

## Known nits awaiting a passing ticket

- [EvalQueueProducer javadoc nit](evalqueueproducer-javadoc-nit.md) — says "first emit" but the gate is per-emit; fold the fix into the next ticket touching that file.
- [AdapterMetricsWiringTest comment nit](adaptermetricswiringtest-comment-nit.md) — its "v1 ships no exporter extension" comment is false since the prometheus registry landed.
