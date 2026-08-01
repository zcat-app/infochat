# Gate scratch under `target/` is wiped by a concurrent verify's `mvn clean`

Observed twice on M1-705 (2026-08-01): while a full `mvn verify` runs in
the background, the parent-module clean plugin deletes `<repo>/target/`
early in the build. Any gate scratch written to `target/` shortly BEFORE
or DURING that window — `redteam-diff-*.diff`, `redteam-inv-*.txt`,
rendered prompts, even a prior `m1-tick-test-*.log` copy — silently
disappears, and follow-up redirects fail with "No such file or
directory".

Rule: when a verify is in flight (or about to be), write redteam/review
scratch to `.scratch/` (gitignored, worktree-local, mvn-proof) instead of
`target/`, with absolute paths as usual. `target/` paths are only safe
once no verify is running or about to start. The durable artifacts never
live in either place: verdicts persist to `docs/plan/<milestone>/redteam/`
and the test log is copied back to `target/` AFTER the build closes (the
`.scratch/` → `target/` capture pattern exists for the same reason).
