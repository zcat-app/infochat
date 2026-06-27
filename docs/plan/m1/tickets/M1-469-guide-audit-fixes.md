---
id: M1-469
title: Fix verified guide-audit inaccuracies in 3 role guides
status: done
created: 2026-06-27
last_updated: 2026-06-27
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 31
      removed: 18
blocked_by: []
files_budget: 3
files_scope:
  - SETUP_GUIDE.md
  - USER_GUIDE.md
  - ADMIN_GUIDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any code, test, or migration                    # doc-only
  - README.md, OVERVIEW.md                           # audited clean; no changes needed
  - docs/design/03-commands.md privileged-set staleness + docs/design/04-security.md sanitizer regex omitting `digest`  # separate design-doc/CI gap, not guide cleanup — file its own ticket
  - reconciling the aspirational summary format in docs/design/03-commands.md §3.x with shipped output  # this ticket makes the USER_GUIDE example match SHIPPED code, not the other way around
acceptance:
  - "SETUP_GUIDE.md §Step 5 — the 'Price commands' paragraph no longer claims assets are off by default / that blank-Enter skips them / that there is no ready-made file. It states assets ship ENABLED by default (bundled zcash+monero prod/config/bootstrap-assets.json), the step-5 prompt is 'Enable crypto asset commands (zcash, monero)? [Yes/no]' defaulting to Yes, and disabling requires answering 'no'"
  - "SETUP_GUIDE.md §A complete example — the step-5 portion reflects the real prompt order (a bootstrap-sources.json path prompt, then the 'Enable crypto asset commands? [Yes/no]' prompt defaulting to Yes), not a single 'Optional bootstrap-assets path — skip price commands' line; the step-5 row of the wizard table no longer describes assets as 'otherwise skipped'"
  - "USER_GUIDE.md §Getting the news — the /zcash·/monero cheat-sheet row no longer calls the data 'Live'; it reflects that replies are cached snapshots stamped with capture time + cache age (per docs/spec/commands.md §Asset commands, stale-data honesty)"
  - "USER_GUIDE.md §Worked examples #1 — the example summary output matches the shipped ClusterBlockRenderer format: the score line reads 'score: <N> sources' (no qualitative grade, no 'news+social' composition), a 'classification:' line appears between 'summary:' and 'tags:', and the score line is present in every cluster block shown"
  - "ADMIN_GUIDE.md §Managing groups — the /approve-group table cell no longer states the first eligible member becomes group admin at approval; it matches the auto-promote-on-first-@mention behavior already stated correctly in the §playbook (ADMIN_GUIDE.md:233)"
  - "SETUP_GUIDE.md §Step 4 (and §Before you start) — the ollama description no longer implies a single model download; it reflects that the ollama backend pulls multiple small models (security + chat + embeddings, deduped per profile)"
  - "SETUP_GUIDE.md §Back up your data / §reset modes — the literal Docker volume names are corrected or reworded so a reader is not told to use bare names that don't exist: only infochat-llamacpp-models is unprefixed; infochat-pgdata and infochat-ollama are project-prefixed (<project>_infochat-pgdata, <project>_infochat-ollama)"
  - "No file other than SETUP_GUIDE.md, USER_GUIDE.md, ADMIN_GUIDE.md is modified"
  - mvn verify is green
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/commands.md §Admin (bot admin)
decision_refs:
  - D30
  - D47
---

# M1-469: Fix verified guide-audit inaccuracies in 3 role guides

## Context

A claim-by-claim audit of the five role guides (README, OVERVIEW, SETUP_GUIDE,
USER_GUIDE, ADMIN_GUIDE) against the spec, the design notes, and the actual
source code turned up seven verified inaccuracies. README and OVERVIEW audited
clean. The remaining seven span three guides and range from one high-severity
contradiction (asset commands documented as off-by-default while the wizard
ships them on) to several minor accuracy slips. Each was confirmed against
ground-truth source before inclusion; this ticket fixes all seven in one
doc-only pass. No behavior changes.

Evidence per item (already verified):

1. **HIGH — assets off-by-default is inverted.** `prod/scripts/5-bootstrap.sh:8-13,127-140`
   ships assets ENABLED by default; the prompt `Enable crypto asset commands
   (zcash, monero)? [Yes/no]` defaults to Yes, blank-Enter copies the bundled
   `prod/config/bootstrap-assets.json` (zcash+monero — a ready-made file that
   exists), and disabling needs an explicit "no". SETUP_GUIDE.md:219-226 claims
   the opposite on all three counts.
2. **MED — complete-example step 5 mismatch** (same root cause). SETUP_GUIDE.md:250
   collapses step 5 to one "Optional bootstrap-assets path — skip price commands"
   line; the real flow (`5-bootstrap.sh:100-140`) prompts for a sources path,
   then the enable-assets Yes/no prompt, then a custom-assets path. Table row
   :135 mislabels assets as "otherwise skipped".
3. **MED — "Live" asset data.** `docs/spec/commands.md:330-332`: "The bot does not
   pretend to be live; the websocket 'live' mode is deferred to v2." Data is
   cached `price_snapshot` rows stamped with capture time + cache age.
   USER_GUIDE.md:75 labels it "Live".
4. **LOW — summary example ≠ shipped output.** `ClusterBlockRenderer.java:80-106`
   + `en.properties:102-106` emit `score: <N> sources` (no grade, no
   "news+social"), always emit a `classification:` line, and always emit the
   score line. USER_GUIDE.md:160-168 shows `score: high (2 sources, news+social)`,
   omits `classification:`, and drops the score line from the second block. (The
   guide matches the *aspirational* design doc, not shipped code; the renderer
   comment calls the current shape a "placeholder for MVP".)
5. **LOW — approve-group group-admin timing.** `ApproveGroupCommandHandler` only
   flips approval status; the group admin is auto-promoted on the first eligible
   @mention after approval (D47). ADMIN_GUIDE.md:155 implies it happens at
   approval; the playbook at :233 already states it correctly.
6. **LOW — "one small AI model".** `prod/scripts/4-llm.sh:204` pulls three models
   for the laptop profile (llama3.2:3b + llama3.1:8b + nomic-embed-text, deduped
   per profile). SETUP_GUIDE.md:38-39,177 imply a single download.
7. **LOW — bare volume names.** `docker-compose.yml:256-265`: only
   `infochat-llamacpp-models` is `name:`-pinned; `infochat-pgdata` /
   `infochat-ollama` resolve to `<project>_infochat-…` (`prod/setup.sh:174,189`).
   SETUP_GUIDE.md:305,333 give the bare names a user could not `docker volume rm`
   directly.

## Acceptance

The seven YAML `acceptance:` items, in prose: correct the asset-default paragraph
and the complete-example step-5 flow in SETUP_GUIDE; drop the "Live" label and
match the cached-snapshot reality in USER_GUIDE; make the worked-example summary
block match shipped `ClusterBlockRenderer` output (score-count line,
`classification:` line, score line in every block); fix the `/approve-group`
group-admin timing in ADMIN_GUIDE; stop implying a single ollama model download;
and correct (or reword) the Docker volume names. Only the three named guides are
touched, and `mvn verify` is green (doc-only no-op).

## Out-of-scope

Doc-only: no code, test, or migration. README.md and OVERVIEW.md audited clean —
do not touch them. Two adjacent findings are explicitly **not** in this ticket:
(a) the stale design-tier privileged-command set in `docs/design/03-commands.md`
and the LLM-sanitizer regex in `docs/design/04-security.md` omitting `digest`
(a possible CI gap between the spec commitment and the sanitizer — file its own
ticket, since it may need code, not just doc edits); and (b) reconciling the
aspirational richer summary format in the design doc with the renderer — this
ticket makes the USER_GUIDE example track *shipped* output, it does not change
the design doc or the code.

## Notes

- House style: terse cheat-sheet cells and parenthetical notes, matching the
  existing tables (see `/forget`'s "It asks you to confirm first." as the
  reference voice). Keep edits surgical — fix the wrong claim, don't rewrite
  surrounding prose.
- For item 7, the simplest correct fix is usually to reword so the guide doesn't
  hand the reader a literal `docker volume` name that won't resolve (the reset
  scripts remove the volumes themselves), rather than teaching the project-prefix
  derivation.
- For item 6, "~5 GB" total was not independently verifiable (remote artifacts);
  only the model *count* is wrong — fix the singular/"one model" framing, leave
  any size figure as an approximation.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-469-guide-audit-fixes.md
```
