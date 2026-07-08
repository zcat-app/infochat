export const meta = {
  name: 'm1-live-test-fixes',
  description: 'Implement the 2026-07-08 live-test fix tickets (M1-588/590-596) in 4 file-disjoint lines, full m1-tick cycle per ticket. Excludes M1-589 (human-driven). Start in a FRESH session.',
  whenToUse: 'Drain the 8 pending live-test fix tickets unattended in parallel, file-disjoint topic lines; halt only on hard gates. Bot is DOWN for the run.',
  phases: [
    { title: 'Preflight', detail: 'clean main, board sanity, stop live apps' },
    { title: 'LineA-bundles', detail: 'M1-592 → 590 → 591 → 593 → 594, sequential (shared en/cs bundles, SummaryCommandHandler, BundleKeys, commands.md)' },
    { title: 'LineB-chat', detail: 'M1-595 (ChatAgent audit actor)' },
    { title: 'LineC-scheduler', detail: 'M1-596 (FetchScheduler stall investigate)' },
    { title: 'LineD-collector', detail: 'M1-588 (NitterFetcher degraded-feed)' },
    { title: 'Wrap-up', detail: 'restart stack, prune leaks, batch report' },
  ],
}

// Lines are pairwise files_scope-disjoint (verified 2026-07-08 from each ticket's frontmatter):
//   LineA collectively owns en/cs.properties + BundleKeys + SummaryCommandHandler(+Test) +
//     ClusterBlockRenderer + AssetReplyRenderer + CommandPermissions + InboundRouter +
//     EligiblePostQuery + commands.md  (so its 5 tickets MUST run sequentially — they share files).
//   LineB owns ChatAgent.java; LineC owns FetchScheduler; LineD owns NitterFetcher — all disjoint.
// M1-589 (chat RAG, complexity:high) is DELIBERATELY EXCLUDED — human-driven separately.
// Override with { repo, lines: {...} } if needed (line order within each array = execution order).
const REPO = (args && args.repo) || '/home/infochat/infochat'
const LINES = (args && args.lines) || {
  'LineA-bundles': ['M1-592', 'M1-590', 'M1-591', 'M1-593', 'M1-594'],
  'LineB-chat': ['M1-595'],
  'LineC-scheduler': ['M1-596'],
  'LineD-collector': ['M1-588'],
}

const TICKET_SCHEMA = {
  type: 'object',
  required: ['verdict', 'summary'],
  properties: {
    verdict: { enum: ['MERGED', 'HALTED'] },
    summary: { type: 'string', description: '3-5 sentences: what was built, review rounds, redteam outcome, verify evidence' },
    mergeSha: { type: 'string', description: 'squash-merge commit sha on main (MERGED only)' },
    haltReason: { type: 'string', description: 'which hard stop fired and why (HALTED only)' },
    haltEvidence: { type: 'string', description: 'paths to logs/verdict files the user needs for the escalation menu' },
  },
}

const PREFLIGHT_SCHEMA = {
  type: 'object', required: ['ok', 'notes'],
  properties: {
    ok: { type: 'boolean' },
    notes: { type: 'string' },
  },
}

// ── The per-ticket developer-orchestrator prompt ─────────────────────────
const ticketPrompt = (id, line) => `
You are the orchestrator-developer for EXACTLY ONE M1 ticket, ${id}, in the repo ${REPO}
(cwd there). You drive it through the complete m1-tick lifecycle unattended, as one line of a
multi-line parallel batch (your line: ${line}; other lines may each have their own ticket in
flight in separate worktrees — their files_scope are provably disjoint from yours).

PROCEDURE — read fresh, follow verbatim, never from memory:
1. Read ${REPO}/.claude/skills/m1-tick/SKILL.md ("M1 workflow rules" section) in full.
2. Read .claude/skills/m1-tick/subcommands/run.md and follow its sequence, reading each
   subcommand file (start.md, review.md, commit.md, merge.md) fresh at the step that invokes it.
   Spawn the gate subagents (clarity-reviewer, plan-writer if complexity:high, code-reviewer,
   threat-actor via the /redteam skill when security_relevant: true) exactly as those files
   prescribe, using their rendered prompt templates from docs/process/.

UNATTENDED-BATCH POLICIES (resolve the user-gated menus to their safe arms; they change no other rule):
- START: invoke the start procedure WITH --parallel semantics (worktree branch), even if no
  other ticket appears in-flight — another line can start one at any moment. Re-verify the
  --parallel preconditions yourself (files_scope disjointness vs any in-flight ticket).
- GROUNDING: the ticket id was explicitly assigned (run.md decision A) — skip the blocking
  confirm, but still perform the on-disk scope-resolution falsification check; halt if it fails.
- VERIFY: always 'scripts/verify-serialized.sh' from the worktree root (NEVER bare mvn — the
  flock is what serializes the lines), captured to the .scratch → target log path per SKILL.md.
  NEVER reuse a prior green log (the Skip menu's safe arm is Run). The fully-inert diff arm
  applies as written if genuinely no *.java/pom.xml/resources file changed.
- MAIN MUTEX: every git command that writes the PRIMARY checkout (${REPO}) — status-flip or
  refine commits on main, everything merge.md drives against <main-host> — must run under
  flock -w 1800 "$(git -C ${REPO} rev-parse --git-common-dir)/m1-batch-main.lock" <cmd>
  so the lines never interleave main mutations. Commits on your own ticket branch in your own
  worktree need no lock.
- HARD STOPS: on any documented halt — clarity FAIL after the one self-refine, a structural
  blocker, an immediate-escalation trigger (files_budget breach, out-of-scope path, premise-fail
  test, two same-root-cause failures), review round-cap / must-shrink / MANUAL, redteam
  FINDINGS, substantive merge conflict, or a red verify you cannot fix in-band — STOP. Leave
  ticket state exactly as the subcommand left it (escalated etc.), do NOT improvise fixes, do
  NOT abandon the engineering rules, and return verdict=HALTED with the reason and evidence
  paths. Never weaken a test, never skip a gate, never push.
- CLEANUP (after a successful merge only): perform merge.md's worktree teardown; then take the
  verify lock (read scripts/verify-serialized.sh to find its lockfile) and, while holding it,
  remove leaked Dev Services containers: docker ps -aq --filter label=org.testcontainers=true
  | xargs -r docker rm -f  (safe: holding the lock guarantees no verify is running anywhere).
- NEVER: push, restart/stop the production compose stack (already stopped for the batch),
  touch prod/runtime, or start work on any ticket other than ${id}.

Return the structured verdict. Your final message is data for the batch orchestrator, not prose
for a human — put everything needed in the schema fields.`

// ── Preflight ────────────────────────────────────────────────────────────
phase('Preflight')
const pre = await agent(`
In ${REPO}: (1) confirm 'git worktree list' shows main checked out at ${REPO} and
'git status --porcelain' is empty — else ok=false; (2) confirm every ticket in
${JSON.stringify(LINES)} is status: pending in its docs/plan/m1/tickets/ file; (3) confirm
scripts/verify-serialized.sh and scripts/regen-status.py exist and docker is responsive;
(4) stop the live apps for the batch per the operator's standing clean-verify directive: use
prod/scripts/apps.sh stop (read it first; stop collector+provider only, leave postgres/llm
containers up), then confirm both app containers are down; (5) note the current main sha as the
batch baseline. Return ok + notes (include the baseline sha). If ANY check fails, ok=false with
the reason — and do NOT stop the apps in that case.`,
  { label: 'preflight', phase: 'Preflight', schema: PREFLIGHT_SCHEMA })

if (!pre || !pre.ok) {
  return { aborted: true, reason: pre ? pre.notes : 'preflight agent died' }
}
log(`preflight OK — ${pre.notes}`)

// Solver model: inherit the session model for every ticket (omit the override). Robust — avoids
// pinning a possibly-stale model id; the whole batch runs on whatever model this session uses.

// ── The lines, in parallel; tickets within a line are sequential ─────────
const runLine = (line, ids) => async () => {
  const results = []
  for (const id of ids) {
    log(`[${line}] starting ${id}`)
    const r = await agent(ticketPrompt(id, line),
      { label: `${line}:${id}`, phase: line, agentType: 'general-purpose', schema: TICKET_SCHEMA })
    results.push({ id, ...(r ?? { verdict: 'HALTED', summary: 'agent died/skipped', haltReason: 'agent returned null' }) })
    const last = results[results.length - 1]
    log(`[${line}] ${id} → ${last.verdict}${last.mergeSha ? ' @ ' + last.mergeSha : ''}`)
    if (last.verdict !== 'MERGED') { log(`[${line}] line halted at ${id}: ${last.haltReason}`); break }
  }
  return results
}

const lineNames = Object.keys(LINES)
const lineResults = await parallel(lineNames.map(n => runLine(n, LINES[n])))
const lines = {}
lineNames.forEach((n, i) => { lines[n] = lineResults[i] || [] })
const flat = Object.values(lines).flat()

// ── Wrap-up ──────────────────────────────────────────────────────────────
phase('Wrap-up')
const report = await agent(`
In ${REPO}: (1) regenerate the board (python3 scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
docs/plan/m1/STATUS.md) and if it changed vs HEAD, commit it on main under
flock "$(git rev-parse --git-common-dir)/m1-batch-main.lock" as
'process: regen STATUS after batch run'; (2) prune any remaining org.testcontainers-labeled
containers; (3) restart the live stack collector-first per prod/scripts/apps.sh (read it first),
wait for both readiness endpoints to return 200, report each adapter's connection status from the
metrics endpoint; (4) list 'git log --oneline <baseline>..main' where baseline is in the batch
notes: ${JSON.stringify(pre.notes)}; (5) summarize worktree list (any leftovers from halted
tickets — leave them in place for the user, just name them). Return a concise operator report.
NEVER push.`,
  { label: 'wrap-up', phase: 'Wrap-up' })

return {
  baseline: pre.notes,
  lines,
  merged: flat.filter(t => t.verdict === 'MERGED').map(t => t.id),
  halted: flat.filter(t => t.verdict !== 'MERGED').map(t => ({ id: t.id, reason: t.haltReason, evidence: t.haltEvidence })),
  wrapUp: report,
}
