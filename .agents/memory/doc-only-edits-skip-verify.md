---
name: doc-only-edits-skip-verify
description: Doc-only diffs skip the mvn verify step; only code/script/db/config changes require a verify run
metadata: 
  type: feedback
---

When a ticket or change touches ONLY documentation (`.md` guides, spec, design,
process docs — nothing the test suite can catch), do NOT run `mvn verify`. Skip
the verify step entirely. If the diff includes ANY script, DB/migration, code,
config, or other testable file (`*.java`, `pom.xml`, `src/**/resources/**`,
shell scripts, etc.), then yes — run verify as normal.

**Why:** A full `mvn verify` on a markdown-only change proves nothing — no
testable file changed, so the suite result is unaffected. Running it wastes
minutes and annoys the user (they pushed back hard when I launched it on M1-469's
docs-only diff). This matches the M1-379 inert-diff gate and the CLAUDE.md
commit-prefix rule that pure-doc edits bypass `mvn verify`.

**How to apply:** Classify the diff first. Docs-only → skip verify, reuse a
recent still-valid green full-suite log as evidence if a gate needs one (no
testable file changed since it ran, no merge/rebase intervened). Any testable
file present → run verify. Don't fire a near-duplicate Skip/Run menu when the
user has already authorized skipping for the same docs-only change. Relates to
[[persist-design-decisions]].
