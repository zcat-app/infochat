---
id: M1-814
title: "Treat an absent image spool as empty in the sweeper"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  to-be-written: ImageSpoolTest.absentSpoolDirIsAnEmptySpool — constructs
  ImageSpool over a path that does not exist (a fresh @TempDir child never
  created), calls agedFiles(now, maxAge) and evictAgedFiles(now, maxAge),
  and asserts an empty candidate list with no exception. RED on main:
  agedFiles opens Files.newDirectoryStream(dir) unconditionally
  (ImageSpool.java:107), so the absent directory throws
  NoSuchFileException — the exact exception the live Provider logs as
  "Image spool sweep failed" on every 15-minute cadence before any image
  request (bench/livetest-10-08-26.md E7).
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/ImageSpool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImageSpoolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImageSpoolSweeperTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The sweeper's cadence, max-age, injected-Clock seam, and IOException
    catch (ImageSpoolSweeper.java:37-47) — the catch REMAINS for genuine IO
    failures; this ticket removes only the false-positive trigger.
  - Directory creation policy — write() keeps its lazy
    Files.createDirectories (ImageSpool.java:36); no startup mkdir, no
    second creation site (analysis option 8, P7).
  - The capacity bound, the temp-then-move write, the name confinement
    (M1-801/M1-805), delete-on-completion — all untouched.
  - The live "sweeper after a clean startup" probe (bench §Additional
    tests) — satisfied by this fix; M1-816's gate statement references it,
    no separate work.
acceptance:
  - "ImageSpoolTest.absentSpoolDirIsAnEmptySpool passes — REPRODUCTION (written and run RED at start): an absent spool directory is an empty spool — agedFiles returns an empty list and evictAgedFiles completes without throwing (the spool is tmpfs, application.properties:218 — absent before first use and after any host reboot; that is an idle state, not an IO failure; design 06-messaging.md §6.2.4's crash guarantee is unaffected)."
  - "ImageSpoolSweeperTest.sweepCompletesWhenTheSpoolIsAbsent passes — the scheduled path end-to-end: sweep() against an absent directory completes normally (no exception reaches the catch, so no recurring WARN line); a sweep against a directory holding one aged file still evicts it (the same test seeds it after creating the dir)."
  - "FAILURE-MODE: genuine eviction is preserved — ImageSpoolTest.sweeperEvictsAgedFilesAndKeepsFreshOnes passes UNEDITED (fixed-Clock eviction of aged files, fresh files kept; the §9 injected-Clock seam at ImageSpoolSweeper.java:40 untouched)."
  - "FAILURE-MODE: the capacity bound and write path are preserved — ImageSpoolTest.refusesWritesPastTheCapacityBound, refusesNonBareSpoolName, and the concurrent-writes test pass UNEDITED; `grep -c 'createDirectories' ImageSpool.java` prints 1 (still exactly one creation site, in write())."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - ImageSpoolTest.absentSpoolDirIsAnEmptySpool
    - ImageSpoolSweeperTest.java (sweepCompletesWhenTheSpoolIsAbsent)
  preserves:
    - all tests currently green on main (ImageSpoolTest's capacity, name-confinement, eviction, and concurrency tests; PngMetadataStripTest; ComfyUIClientTest)
spec_refs: []
decision_refs:
  - D74
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-814: Treat an absent image spool as empty in the sweeper

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E7): the Provider log
repeatedly carries `Image spool sweep failed:
java.nio.file.NoSuchFileException: /dev/shm/infochat-image-spool` — a
scheduled, recurring exception on every 15-minute cadence BEFORE any image
request, on a stack that is otherwise healthy. The spool directory is
created lazily by the first image write, so every fresh Provider that has
not yet served an image (and every host reboot, which clears tmpfs) logs
the failure indefinitely. Shared analysis: `analysis_ref:` (pitfalls
P6/P7 below match it).

## Root cause

Verified at ImageSpool.java:104-116: `agedFiles` — the sweeper's candidate
read, reached from `ImageSpoolSweeper.sweep` (ImageSpoolSweeper.java:37-47)
via `evictAgedFiles` — opens `Files.newDirectoryStream(dir)`
unconditionally. It is the ONLY reader that touches the directory without
creating it: `write` creates it first (:36), `delete` resolves without IO
on a missing dir (:71-86), `totalBytes` runs only from `write` after
creation. The sweeper's `catch (IOException)` then reports the idle state
as a failed sweep every cadence. The design notes (06-messaging.md:410-434)
define the sweeper as the crash guarantee and name the keys, but are silent
on the absent-directory state — the fix states it: a missing spool is an
empty spool.

## Pitfalls

Numbered consistently with the analysis document.

- P6: the sweeper is the crash guarantee (the feature analysis's
  spool-lifecycle pitfall; 06-messaging.md §6.2.4) — absent=empty must not
  weaken eviction of REAL
  files, the capacity-bound refusal, or the injected-Clock seam (§9:
  eviction time is still passed in from ImageSpoolSweeper.java:40, never
  read inside ImageSpool).
- P7: §7 cuts both ways — an absent directory is a real state of the tmpfs
  filesystem boundary (pre-first-use, post-host-reboot), so handling it is
  boundary validation, not defensive paranoia; conversely, NO speculative
  directory recreation (a startup mkdir races a tmpfs cleared mid-run and
  adds a second creation site next to write()'s lazy one — the lazy
  creation suffices).

## Approach

Derived from the D74 spool lifecycle (decisions.md:93; 06-messaging.md
§6.2.4): the sweeper guarantees no leaked file past the age bound; a
directory that does not exist holds no files, so the honest candidate set
is empty.

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test RED.
  2. In `agedFiles`: when `dir` is not an existing directory, return the
     empty list (one boundary check before the DirectoryStream). Nothing
     else in ImageSpool changes.
  3. The sweeper-level test (the scheduled path end-to-end, absent + seeded
     cases).
  4. Full verify; confirm the untouched-tests list green.
- **Controls to preserve (§10):** the sweeper's catch remains for genuine
  IO failures (a real failure still logs and retries next cadence — the
  comment at ImageSpoolSweeper.java:42-44 stays true); the injected-Clock
  seam; the capacity bound + atomic write; delete-on-completion; the M1-805
  name confinement. No control is rerouted — the change narrows one read's
  failure surface.
- **Pitfall→mitigation:** P6→step 2's minimal boundary check + acceptance
  items 3-4's unedited pins; P7→step 2 is the ONLY production change (no
  mkdir anywhere — acceptance item 4's grep).

## Definition of done

Every acceptance item green by its named test: the reproduction and the
sweeper-level test pass; eviction, capacity, name-confinement, and
concurrency tests all pass unedited; exactly one creation site remains;
full verify green.

## Verification

- reproduction → ImageSpoolTest.absentSpoolDirIsAnEmptySpool (RED on main
  — NoSuchFileException; removing the boundary check reds it again).
- P6 (eviction preserved) → sweeperEvictsAgedFilesAndKeepsFreshOnes
  unedited, fixed Clock (a sweeper that skips real files fails it).
- P6 (clock seam) → the new sweeper test drives sweep() with the injected
  Clock; an Instant.now() inside ImageSpool would fail the fixed-clock
  eviction assertion.
- P7 → `grep -c 'createDirectories' ImageSpool.java` prints 1 (a startup
  mkdir fails it); sweepCompletesWhenTheSpoolIsAbsent's seeded case proves
  the catch still serves real sweeps.
- failure-mode → refusesWritesPastTheCapacityBound +
  refusesNonBareSpoolName unedited (hostile write paths unchanged).
- Non-vacuity: deleting the boundary check reds the reproduction; widening
  it to swallow all IOExceptions reds nothing visible but is barred by the
  diff's shape (the check is a precondition, not a catch).

## Out-of-scope

Named in `out_of_scope`: the sweeper's cadence/age/clock/catch, directory
creation policy, and every other spool control. No pre-existing test is
modified; `spec_refs:` is legally empty for this defect ticket — the
contract is its reproduction, and the spool lifecycle's home is design
notes (06-messaging.md §6.2.4) per messaging.md §What lives in design notes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-814-image-spool-sweeper-absent-dir.md
```
