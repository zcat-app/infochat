---
id: M1-282
title: "Redactor suppressed-throwable coverage"
status: pending
created: 2026-06-10
last_updated: 2026-06-10
blocked_by: []
remediates: M1-272
files_budget: 3
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log
  - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorSuppressedChainTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Prose hygiene in suppressed throwables at SafeLog call sites — already covered; SafeLog.formatSafe walks suppressed nodes and emits class names only (pinned by SafeLogTest.formatSafeIncludesSuppressedClassNames and formatSafeWalksSuppressedOnCauseChainElements). The console filter's catalogue scan is the only gap.
  - The redaction catalogue contents (what counts as a secret) — unchanged; only WHERE the catalogue is applied widens, same boundary as M1-272.
  - The Redactor in-band sentinel exact-equality quirk and the params-array in-place mutation — verified-but-wont-fix per M1-272; untouched.
  - The audit-log redaction leg — it consumes the catalogue via string-field redact(), not the thrown-chain walk; untouched.
acceptance:
  - "Redactor.redactThrownChain scans the messages of suppressed throwables on every node it walks — including suppressed nodes' own causes and nested suppressed — through the same catalogue scan and the same fail-closed timeout arm (any timed-out scan returns null so the caller substitutes the sentinel and clears the thrown) as cause-chain messages. A named test asserts an API-key-shaped string in the message of a suppressed throwable, with the primary message and full cause chain catalogue-clean, never reaches console output (the try-with-resources shape from the 2026-06-10 redteam REPRO: clean primary, secret-bearing close() failure recorded as suppressed)."
  - "When the thrown graph rebuilds, suppressed throwables are preserved on the replacement as redacted nodes (original class name + redacted message + original stack frames), so the console formatter still renders the Suppressed: frame with redacted text instead of silently dropping it. A named test asserts both: the redacted suppressed text appears in console output, and the raw secret does not."
  - "A throwable whose causes AND suppressed entries are all catalogue-clean still passes through the unchanged arm as the same object (assertSame on record.getThrown()). A named test pins this; the existing cleanThrownChainPassesThroughUntouched method is not modified."
  - "The suppressed walk is bounded by the same fail-closed truncation discipline as the MAX_THROWN_CHAIN_DEPTH cause-chain cap: mutually-suppressing cycles or oversized suppressed graphs can neither stall the filter nor reach the formatter unscanned — past-cap nodes are dropped from the replacement, never emitted raw. A named test uses a mutually-suppressing pair (a.addSuppressed(b); b.addSuppressed(a)) carrying a catalogue match and asserts termination plus no raw secret in console output."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorSuppressedChainTest.java (new top-level test class, same StreamHandler+SimpleFormatter capture style as RedactorThrownChainTest)
  modifies: []
  preserves:
    - all tests currently green on main (incl. RedactorThrownChainTest — no existing method changes; its assertSame clean-path pin stays valid because the fix returns the original object whenever the full graph is clean)
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs: []
---

# M1-282 — Redactor suppressed-throwable coverage

## Context

The 2026-06-10 redteam audit of M1-272 (verdict file
`docs/plan/m1/redteam/M1-272-2026-06-10.md`, ticket frontmatter
`redteam_findings:` on M1-272) found one medium INFO-LEAK, verified
against the committed file before transcription:

Spec promise (security.md §Secrets handling, verbatim): "Stdout console
logs pass through the closed API-key catalogue redactor, fail-closed on
regex timeout (whole message replaced with a fixed sentinel). The
audit_log writer consumes the same Redactor utility so the two cannot
drift."

Gap: `Redactor.redactThrownChain` (Redactor.java:180-220) walks only the
`getCause()` chain and never inspects `getSuppressed()`, while console
formatters render `Suppressed:` frames via `Throwable.printStackTrace`.
Two sub-paths:

1. **Unchanged arm leaks.** When no catalogue match is found in the main
   cause chain, the method returns the ORIGINAL throwable
   (Redactor.java:202-204), so its suppressed exceptions reach the
   formatter completely unscanned. This is the exploitable path: a
   try-with-resources primary whose message is clean, with a `close()`
   failure echoing a connection string (`password=...`, `bearer ...`,
   `sk-...`, `AKIA...`) recorded as suppressed, puts the secret on
   stdout raw.
2. **Rebuilt arm drops.** `RedactedThrown` is constructed with
   `super(message, cause, false, true)` (Redactor.java:231), disabling
   suppression — its javadoc states this is deliberate fail-closed
   ("suppressed throwables on the original were not scanned and must not
   ride along"). Safe but lossy; once suppressed nodes are scanned, they
   should be preserved in redacted form like cause nodes.

## Acceptance

See frontmatter. The structural fix is widening the thrown-graph walk to
suppressed nodes on both arms; the unchanged-arm guarantee ("return the
same object when fully clean") and the fail-closed disciplines (timeout
sentinel, depth-cap truncation) carry over unchanged in meaning, now
over the full graph.

## Out-of-scope

See frontmatter. In particular the redteam's OUT-OF-MODEL note (prose in
suppressed throwables) needs no work here: the spec assigns prose
hygiene to SafeLog call sites, and SafeLog already walks suppressed
nodes (class names only, pinned by existing tests). This ticket closes
the catalogue-shape leg only.

## Notes

- Preserving suppressed nodes on the rebuilt arm requires
  `RedactedThrown` to enable suppression (the third `super` arg) — or to
  re-add redacted copies via `addSuppressed` after construction. Its
  javadoc ("suppression is disabled because suppressed throwables ...
  were not scanned") becomes false once they ARE scanned; update it in
  the same pass — that comment is the invariant record for this class.
- Suppressed graphs are trees, not chains: each suppressed node has its
  own cause chain and its own suppressed array, and mutual suppression
  (`a.addSuppressed(b); b.addSuppressed(a)`) is legal Java
  (`Throwable.addSuppressed` only rejects self-suppression). A simple
  per-branch depth counter does not terminate on cycles — bound the walk
  by TOTAL visited-node count (an identity-set or a node budget in the
  spirit of MAX_THROWN_CHAIN_DEPTH), truncating fail-closed past the
  cap.
- Timeout parity comes structurally if every node message routes through
  the same `redact(message)` / `TIMEOUT_SENTINEL` check loop the cause
  chain uses today (Redactor.java:189-201); keep the single loop shape
  rather than a parallel scan path.
- Test style: follow RedactorThrownChainTest — public Filter API,
  `StreamHandler` + `SimpleFormatter` over a `ByteArrayOutputStream`,
  assert on the formatted console bytes. SimpleFormatter renders
  `Suppressed:` frames, so the leak and its fix are both observable at
  the captured-output level without touching Redactor internals.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-282-*.md
```
