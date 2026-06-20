---
id: M1-411
title: "ssrf+messaging: two behavior-preserving simplifications (inline isIpv4Mapped, single-source chunker fence state)"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 4
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/IpBlocklistTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOutboundChunker.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOutboundChunkerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The four sibling transition-form branches in embeddedV4 (6to4, Teredo, NAT64, IPv4-compatible) — unchanged; only the extracted isIpv4Mapped helper is inlined to match them.
  - The IpBlocklist blocking decision and its address ranges — unchanged; this is a pure code-shape change.
  - The chunker's chunk-size, reserve, and hard-split accounting — unchanged in behavior; only where the fence open/closed state is owned changes.
acceptance:
  - "IpBlocklist.isIpv4Mapped is removed and its single call site in embeddedV4 is inlined, expressed consistently with the four sibling transition-form branches and using the existing allZero helper, with identical detection behavior."
  - "SimpleXOutboundChunker no longer represents the code-fence open/closed state in two places at once (a caller-computed local and a separate field threaded back in); the fence toggle is owned in one place, with unchanged chunking behavior."
  - "IpBlocklistTest (including its IPv4-mapped coverage) and SimpleXOutboundChunkerTest remain green with no assertions weakened, deleted, or relaxed."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 40
      removed: 26
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-20
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: IpBlocklist is SSRF-guard code; security_relevant: false means the reviewer will not apply heightened inspection to the inlined detection logic. The risk is low given pinned tests, but flipping to true is the conservative choice for any edit to a security-adjacent class."
  blockers: []
---

# M1-411: two behavior-preserving simplifications (inline isIpv4Mapped, single-source chunker fence state)

## Context

Deep-review full (2026-06-20) two **low** SIMPLIFICATION findings, grouped as one
pure-cleanup ticket. Both verified at source 2026-06-20; both are behavior-preserving
and pinned by existing tests, so this ticket changes no behavior.

**(a) ssrf F2 — single-use isIpv4Mapped helper.**
`IpBlocklist.isIpv4Mapped` has exactly one caller (in `embeddedV4`) and is
stylistically inconsistent with the four sibling transition-form branches beside it,
which inline their prefix tests directly. The existing `allZero(raw, 0, 10)` helper
already expresses the leading-zeros check the helper performs, so inlining flattens
it with no behavior change.

**(b) messaging F3 — chunker fence state tracked in two places.**
`SimpleXOutboundChunker` encodes the single "is a code fence open" property twice:
the field `ChunkBuilder.fenceOpen` (state before the current line) and the
caller-computed local `fenceAfterLine = builder.fenceOpen ^ line.startsWith(FENCE)`
(state after the line), which the caller is then responsible for threading back into
the field. The toggle lives in the caller rather than being owned by the builder,
making the cut/reserve accounting harder to verify than necessary. Consolidating
ownership of the toggle removes the redundancy without changing the chunking output.

## Acceptance

See frontmatter. Inline the single-use helper to match its siblings; give the
chunker's fence state a single owner. No behavioral change in either.

## Out-of-scope

See frontmatter. The blocking decision, the address ranges, the sibling branches,
and the chunk-size/reserve accounting are all unchanged.

## Notes

- Both halves are pure preference-tier simplifications; the value is readability, not
  a fix. The existing test suites for both classes are the behavior pin — they must
  pass unchanged.
</content>
