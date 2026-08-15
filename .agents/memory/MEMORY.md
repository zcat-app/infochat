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

- [Spec edits need approval; spec is no journal](spec-edits-need-approval-no-journal.md) — show the exact docs/spec/ text with plain-English reasoning and wait for a yes, even when the ticket's acceptance lists the amendment; new spec text states the rule only — no dates, ticket IDs, or report citations.
- [Comments on a diet](comments-on-a-diet.md) — comments aid genuinely complex logic only; code self-documents, javadoc stays short and references spec/design docs instead of retelling history; long comments are the rare exception. Now canonical: engineering-rules §11/§12.
- [The /tick flow (analysis-first) supersedes /m1-tick](tick-flow-exists.md) — the flow for NEW work (/m1-tick deprecated 2026-08-06, still invocable for its existing board): reproduction gate + mandatory analyst gate at draft time, hurdle-report discipline, one merged review gate with falsification duty, files_budget abolished; never mix flows on one ticket.
- [Relocated controls don't travel](relocated-controls-dont-travel.md) — a diff that reroutes a path drops that path's INCIDENTAL controls (sanitize, audit row, the unit they operate on, tests that pinned a security property); invisible to the suite and to review, so enumerate them in `acceptance:` at authoring time. Engineering-rules §10.
- [Enumerate a census by invocation, not output token](census-enumerate-by-invocation.md) — a label-based grep misses bare-content fields, so each plan pass finds a different subset; two passes finding different file sets means decompose, not refine again.
- [A pre-registration with a free variable isn't pinned](pre-registration-free-variable.md) — write the threshold as a formula and mark which inputs are known TODAY; a percentage of a survivor set moves its own trigger point once the data lands. Fix by shrinking the population until the rule is invariant, or by stating an absolute count.
- [Reviewer is a conformance gate, not a correctness gate](reviewer-is-conformance-not-correctness.md) — measured over 670 tickets: review APPROVEs 88% first-pass and 61% of its rejections are bookkeeping; redteam found real bugs in 13 already-APPROVEd diffs. An APPROVE means "matches its ticket", not "is correct".
- [Redteam remediation needs a re-audit](redteam-remediation-needs-reaudit.md) — an in-branch fix invalidates the audit it answers; re-audit the NEW diff and explicitly permit a CLEAN verdict or the auditor manufactures findings.
- [Redteam diff-range gap](redteam-diffrange-refine-uncommitted-gap.md) — the `--in-progress` range is EMPTY at every pre-commit redteam gate (branch has 0 commits); a CLEAN verdict from an empty diff is the silent failure. Audit the working-tree diff vs fork point.
- [deep-review full is exhaustive since 2026-07-27](deep-review-full-samples.md) — it partitions every reviewable file into verified-complete-cover slices on the cheap tier; read the run's `Coverage:` line, and treat `INCOMPLETE` as a sample. Was one-agent-per-module, which read clean because nobody looked.
- [Investigation tickets shouldn't traverse the full cycle](investigation-ticket-flow-too-heavy.md) — when the deliverable is a finding, decompose into close-investigation + a fresh properly-gated implementation ticket.
- [ID claims are invisible to registries](id-claims-invisible-to-registries.md) — decision numbers AND ticket IDs get claimed in pending/untracked artifacts the registry can't see; grep the claimants, not the index.
- [The in-progress precondition is blind to worktrees](m1-tick-start-precondition-blind-to-worktrees.md) — a ticket started in a git worktree is invisible to a frontmatter grep; pair it with `git worktree list`.
- [Merge from a ticket worktree: main is elsewhere](merge-from-ticket-worktree-main-is-elsewhere.md) — a worktree cannot checkout main (the primary holds it) and cannot -D its own branch (the worktree guard); run the squash-merge from the primary and leave the branch held until worktree removal; a sibling merge between verify and merge fires the staleness check — rebase + full re-verify.
- [Gate subagent audits the wrong tree on relative paths](gate-subagent-audits-wrong-tree-on-relative-paths.md) — a reviewer/threat-actor handed relative paths resolves them against the session cwd (the primary), not the audited worktree; the §6 porcelain check passes (target/ is gitignored), so the only tell is a line-number fingerprint in the verdict that doesn't match the diff. Use absolute paths in the stub and every rendered placeholder.
- [Gate scratch under target/ is wiped by a concurrent verify](gate-scratch-target-wiped-by-concurrent-verify.md) — `mvn clean` deletes `target/` early in the build, taking redteam/review scratch with it; when a verify is in flight, write gate inputs to `.scratch/` (mvn-proof) with absolute paths.
- [A concurrent session can commit to your branch](concurrent-session-committed-to-my-branch.md) — in a shared checkout, re-check `git log <fork>..<branch>` right before merge and squash ONLY your own sha.
- [Ask, don't assume (ambiguous or irreversible)](ask-dont-assume-ambiguous-or-irreversible.md) — a stop/kill word arriving while background work runs is ITSELF the stop sign; ask, however obvious the reading feels.
- [Ask before creating tickets](ask-before-creating-tickets.md) — creating a ticket file needs an explicit user confirmation every time; a discussion pointing at "needs a ticket" is not the confirmation, and neither is an in-flight ticket's acceptance item mandating a follow-up.
- [Persist reached decisions](persist-reached-decisions.md) — a decision reached in a session is a deliverable; commit it or write it here before the session ends.
- [Verify subagent quotes before pinning them](verify-subagent-quotes-before-pinning.md) — survey-agent "exact quotes" can be invented paraphrases; Read the line yourself before an acceptance item asserts it.
- [Audit claims vs sequences](audit-claims-vs-sequences.md) — claim-by-claim doc checks are STATELESS and miss ordering bugs; walk every numbered path as a state machine, per variant. Cost: a wrong README step 2 survived a full pre-release audit.
- [Doc-only edits skip verify](doc-only-edits-skip-verify.md) — run `mvn verify` only when a script/db/code/config file is in the diff.
- [Green-log freshness is per-test, not per-build](green-log-freshness-per-test-not-per-build.md) — a `BUILD SUCCESS` timestamp is NOT when a given test read its input file; an edit to `docs/spec/security.md` can postdate the parity test that parses it while predating the build's end, so the freshness gate silently passes on a suite that never saw it.
- [Release state: read the truth doc, rank your sources](release-state-source-ranking.md) — live DB/config/git > committed docs > scratch handoffs > memory; always read the date.
- [v1.0.0 tag was pulled pre-announcement](v1-0-0-tag-pulled-pre-announcement.md) — tagged at `3fb97365`, deleted local+origin 2026-07-25 so M1-687..690 ship first; there is currently NO release tag, and that is expected.

- [Measurements never ride prod containers](measurements-never-ride-prod-containers.md) — benchmark runs target isolated/test instances, never prod as backend or via prod config; any prod stop/pause is logged first where other sessions read.

## Build, test and CI mechanics

- [Rootless docker port-split](rootless-docker-port-split.md) — random "address already in use" IT deaths = host ephemeral range overlapping rootless docker's fixed ~40000-60999 publish band; fix is the HOST sysctl at 32768-39999 (daemon-netns sysctl is a proven no-op; trust only the `docker run -P` draw probe, never sysctl reads).

- [mvn -Dtest filtering is blocked by a tripwire](mvn-dtest-filter-blocked-by-tripwire.md) — cross-module `-Dtest` always fails (parent POM `failIfNoTests=true`); IT-only filtering IS legal (`-Dit.test` + `-Dfailsafe.failIfNoSpecifiedTests=false`, never `-Dtest`), otherwise module-scoped and UNFILTERED.
- [Comment-cap: point-edit comment blocks, never rewrap](comment-cap-point-edit-comment-blocks.md) — tick-comment-cap counts runs of ADDED comment lines and removed lines don't break a run, so rewrapping a 4+ line javadoc/comment block trips the cap; keep untouched lines byte-identical as context.
- [Clean-verify monitoring](clean-verify-monitoring.md) — pause the live stack for the whole batch, launch the verify detached, never gate a poll loop on a self-matching `pgrep`.
- [Full-suite timing flakes](full-suite-timing-flakes.md) — three flake classes, all fixed by construction; a red in these is now a REAL regression. The hazard pattern: a supervised subprocess stand-in that dies at launch runs crash recovery during your test.
- [A green suite can be an environmental accident](green-suite-can-be-environmental-accident.md) — an unstubbed SPI passes while a real local service happens to answer; the tell is a stub with zero calls.
- [Scan-window fixture time-bombs](scan-window-fixture-timebombs.md) — absolute fixture dates age out of pickup windows and go vacuously green; a guard test blocks new ones, SQL-literal seeds still evade it.
- [IT leftovers starve explicit worker ticks](it-leftovers-starve-explicit-worker-ticks.md) — a leftover RAW post pending for tagger/entity/classifier with an earlier fetched_at fills the one-tick maxConcurrency slot a later IT's fixture needed (`expected READY but was RAW`, green in isolation); seed `*_done=TRUE` for stages the fixture isn't about.
- [IT stranger-bucket silent drop](it-stranger-bucket-silent-drop.md) — an IT seeding users via direct SQL is a stranger at intake and gets silently dropped late in the full suite; mark the contact registered after the INSERT.
- [switch-llm.sh stdin is positional](switch-llm-stdin-is-positional.md) — `SwitchLlmWiringTest` feeds the real script raw newline-positional stdin, so adding an `LLM_TASKS` entry shifts every later answer and fails far from the edit; the task list also lives in two files.
- [Bundle keys need a cs twin](bundle-key-needs-cs-twin.md) — an en-only bundle key fails the build (bilateral keyset parity); scope both files up front.

## Codebase couplings and design facts

- [Title/headline doctrine](title-headline-doctrine.md) — LLM-generated titles were rejected; ingest normalizes the stored title (untitled sentinel, 200-char cap) and DisplayHeadline derives per-post headlines deterministically. New surfaces reuse it, never re-derive; LLM-prompt inputs are the one deliberate split.
- [Handler input is not always normalized](handler-input-not-always-normalized.md) — the router's NFKC pass exempts fenced code while routing reads only line 1, so normalize at the check; never enumerate blank-rendering codepoints.
- [The reflection guard is error-scoped by design](reflection-guard-is-error-scoped-by-design.md) — a green guard does NOT mean reflection is impossible; the reply/success blind spot is disclosed, not overlooked. Don't re-litigate widening it.
- [New admin commands trip three couplings](new-admin-command-couplings.md) — IT naming guard, sanitizer closed-list parity, and audit-before-effect for privileged PII reads.
- [D59 world predicate and bundle-copy gotchas](d59-world-predicate-and-bundle-copy-gotchas.md) — the periodic digest is `DigestPostCollector` (9 predicate sites); bootstrap-origin fixtures leak to every scope; change copy by editing a key's VALUE.
- [Quarkus fast-jar live debugging](quarkus-fastjar-live-debug.md) — transformed-bytecode.jar shadows lib/main so jar overlays silently no-op; adapter dispatch threads carry a foreign context classloader.
- [git add with a stale pathspec stages nothing](git-add-stale-pathspec-silent-nostage.md) — all-or-nothing pathspecs; `RM` in status means your rewrite is UNSTAGED.
- [CDI proxy field writes hit the proxy](cdi-proxy-field-writes-hit-the-proxy.md) — `bean.field = x` through an injected reference writes the proxy's slot, not the bean's (tests must `ClientProxy.unwrap`); and a test driving `onTick()` directly must neutralize other IT classes' standing pickup-ready rows or they eat the stub's FIFO responses.

## Open TODOs

- [Mutation testing (pitest) — DISCHARGED](mutation-testing-recheck-after-batch.md) — spiked 2026-07-27: opt-in `-Pmutation` over the 4 pure-Java modules (M1-713), 14 min, 82-89% test strength; found 3 real defects (M1-710/711/712), all adapter-parity gaps or refuse-legs. Read test STRENGTH not score; it would NOT have caught M1-689.

## Known nits awaiting a passing ticket

- [EvalQueueProducer javadoc nit](evalqueueproducer-javadoc-nit.md) — says "first emit" but the gate is per-emit; fold the fix into the next ticket touching that file.
- [AdapterMetricsWiringTest comment nit](adaptermetricswiringtest-comment-nit.md) — its "v1 ships no exporter extension" comment is false since the prometheus registry landed.
