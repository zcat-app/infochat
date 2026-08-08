---
id: M1-805
title: "Confine ImageSpool writes to the tmpfs spool dir"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  to-be-written: ImageSpoolTest.refusesNonBareSpoolName — the intended test
  spools a payload with a `..`-containing name (`write("../escape.png",
  bytes)`) into a temp-dir spool and asserts the write is refused and no
  file lands outside the spool dir; it cannot compile today because
  `ImageSpool.write` resolves any caller-supplied name against the spool
  dir with no confinement (round-1 review of M1-801, finding 3). `start`
  writes the test and runs it RED before any fix code (workflow §0).
analysis_ref: self
blocked_by: [M1-801]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/ImageSpool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImageSpoolTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Any change to ImageSpoolSweeper or the capacity bound arithmetic
    (M1-801). `delete` gains only the containment check — its reclaim
    semantics (idempotent, failure is not a delivery failure) are
    unchanged.
  - A caller-side fix (e.g. M1-803 minting UUID spool names): enforcement
    belongs in ImageSpool itself, so every future caller inherits the
    confinement and the "never persistent storage" posture does not depend
    on one caller's discipline.
  - Resolving a colliding non-bare name by sanitizing it into a bare one
    (rewriting the name hides the caller bug; refuse instead).
acceptance:
  - "ImageSpoolTest.refusesNonBareSpoolName passes — REPRODUCTION (written and run RED at start): `write("../escape.png", bytes)` and an absolute-path name are refused before anything is created (IllegalArgumentException), no file lands outside the spool directory (D75's 'never persistent storage' posture, docs/spec/commands.md §Content:633-636; the round-1 M1-801 reviewer finding)."
  - "ImageSpoolTest.deleteOutsideSpoolIsANoOp passes: `delete` on an out-of-spool path (a `..`-sibling or an unrelated absolute path) removes nothing and throws nothing — the reclaim is idempotent and runs in OutboundDelivery's `finally`, so a containment breach must not become a delivery failure."
  - "ImageSpoolTest.refusesWritesPastTheCapacityBound and ImageSpoolTest.concurrentWritesNeverExceedTheCapacityBound stay green — FAILURE-MODE: the name guard is orthogonal to the capacity and atomicity behavior (M1-801), so a bare name still writes, refuses past capacity, and respects the synchronized write."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImageSpoolTest.java (new methods: refusesNonBareSpoolName, deleteOutsideSpoolIsANoOp)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D75
---

# M1-805: Confine ImageSpool writes to the tmpfs spool dir

## Context

`ImageSpool.write` (M1-801) resolves the caller-supplied `fileName` with
`dir.resolve(fileName)`. Java's resolve semantics mean a name containing
`..` navigates up, and an absolute name replaces the spool dir entirely —
so `write("../escape.png", bytes)` lands the file on whatever filesystem
the parent lives on, breaking the "never persistent storage" posture that
justifies the spool's tmpfs residency (D75; commands.md §Content). The
round-1 review of M1-801 flagged this; there is no production caller yet,
but `write` is the public spool entry M1-803 will invoke on the D35
dispatch pool, so the confinement must live in `ImageSpool` itself rather
than in one caller's naming discipline. `delete` resolves its argument the
same unconstrained way (ImageSpool.java:62) — an out-of-spool path would
remove a file outside the tmpfs — so it gets the same containment; its
callers pass the full path `write` returned (OutboundDelivery.java:206),
so its guard is containment-after-normalize, not bare-name refusal.

## Root cause

ImageSpool.java:48 (`dir.resolve(fileName)`) trusts the caller-supplied
name; `write` (ImageSpool.java:35) creates the temp file with
`Files.createTempFile(dir, ...)` but moves it to the unresolved target
(ImageSpool.java:49). Nothing validates the name before the move.
`delete` (ImageSpool.java:62) has the same unconstrained resolve on its
path argument.

## Pitfalls

- P1: resolve semantics — an absolute `fileName` makes `dir.resolve`
  return that absolute path, escaping the spool. Guard: refuse any name
  containing a path separator or that is absolute, before creating
  anything.
- P2: `..` segments — `a/../b` style names normalize outside the spool
  even without a leading separator. Guard: normalize and verify the
  resolved path stays inside the spool dir's normalized path.
- P3: the refusal must be a caller bug (unchecked), not a capacity
  outcome — a hostile/absent name must not be conflated with an
  over-capacity write. Guard: `IllegalArgumentException`, distinct from
  `SpoolFullException`.
- P4: `delete`'s containment breach must not throw — the reclaim is
  idempotent, runs in OutboundDelivery's `finally`, and a failed reclaim
  is explicitly not a delivery failure. Guard: an out-of-spool path
  deletes nothing and returns, pinned by `deleteOutsideSpoolIsANoOp`.

## Approach

- **Files to touch:** `files_scope` — ImageSpool.write + ImageSpool.delete
  + ImageSpoolTest.
- **Steps, in order:**
  1. Write `ImageSpoolTest.refusesNonBareSpoolName` and run it RED
     (reproduction first, workflow §0), plus
     `ImageSpoolTest.deleteOutsideSpoolIsANoOp`.
  2. In `ImageSpool.write`, after the capacity check and inside the
     existing `synchronized` method, reject a non-bare name with
     `IllegalArgumentException` before any file is created: null/empty,
     any `/` or `\`, an absolute path, or a resolved-and-normalized path
     not starting with the spool dir's normalized path (covers `..`).
  3. In `ImageSpool.delete`, resolve the argument against the spool dir,
     normalize, and verify containment; an out-of-spool path deletes
     nothing and returns (P4 — never throw).
  4. Full verify.
- **Controls to preserve (§10):** the capacity refusal and the
  `synchronized` atomicity (M1-801 round-1 fix) are untouched; a bare
  name still writes via the temp-file-then-move path; `delete` of an
  in-spool path still reclaims exactly as before.
- **Pitfall→mitigation:** P1/P2 → step 2's checks; P3 → step 2's
  exception type, pinned by the test's assertThrows; P4 → step 3's
  no-op refusal.

## Definition of done

`write` refuses non-bare and escaping names with `IllegalArgumentException`
before creating anything; `delete` removes nothing for an out-of-spool path
and never throws on it; bare names keep the M1-801 capacity and
atomicity behavior; the reproduction and both failure-mode tests pass;
full verify green.

## Verification

- Reproduction → `ImageSpoolTest.refusesNonBareSpoolName` — feeds `..`
  and absolute names, asserts the refusal and no out-of-dir file.
- P1 → `ImageSpoolTest.refusesNonBareSpoolName` — the absolute-name
  assertion feeds the separator/absolute escape shape directly.
- P2 → `ImageSpoolTest.refusesNonBareSpoolName` — the `..`-name
  assertion feeds the normalization escape shape directly.
- P3 → the same test's assertThrows(IllegalArgumentException.class) — an
  over-capacity write still throws SpoolFullException (pinned by
  `refusesWritesPastTheCapacityBound`).
- P4 → `ImageSpoolTest.deleteOutsideSpoolIsANoOp` — a `..`-sibling and an
  unrelated absolute path leave their files intact and throw nothing.
- Non-vacuity: a guard that only checks one of the two escape shapes
  (separator vs `..`) fails one of the assertions; a guard that rewrites
  the name instead of refusing fails the assertThrows; a `delete` guard
  that throws on containment breach fails the no-op assertion.

## Out-of-scope

Named in `out_of_scope`: sweeper and capacity behavior (M1-801), the
caller-side UUID-naming alternative, and sanitizing instead of refusing.
No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-805-*.md
```
