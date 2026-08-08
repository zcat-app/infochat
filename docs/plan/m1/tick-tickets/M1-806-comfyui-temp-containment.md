---
id: M1-806
title: "ComfyUI temp/ containment: tmpfs + janitor sweep"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  Probe (no mvn coverage exists for compose/image artifacts — M1-797
  precedent): `grep -n 'type: tmpfs' -A2 docker-compose.comfyui.yml` renders
  exactly ONE tmpfs target, /opt/ComfyUI/output
  (docker-compose.comfyui.yml:44-47), and
  `grep -n temp prod/images/comfyui/janitor.sh` returns nothing — the janitor
  sweeps only /opt/ComfyUI/output (janitor.sh:9,15). Observed wrong state on
  the M1-797 tree: a file written to /opt/ComfyUI/temp — a preview pixel, if
  the graph M1-802 will build ever contains a PreviewImage-type node — is
  neither on tmpfs nor in any sweep, so it sits on the container's writable
  layer until the container is removed, past the 15-minute D75 window.
  Runtime plant probe (`start` runs it RED before the fix): overlay up,
  `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml exec
  comfyui touch /opt/ComfyUI/temp/canary` — the file survives indefinitely
  and `df -T /opt/ComfyUI/temp` reports the overlay filesystem, not tmpfs.
  Nothing writes temp/ TODAY (grep-verified: zero ComfyUI references in any
  Java source; the graph builder does not exist) — the defect is the
  containment gap in a shipped boundary, not a live leak.
analysis_ref: self
blocked_by: []
files_scope:
  - docker-compose.comfyui.yml
  - prod/images/comfyui/
  - docs/design/future/image-generation.md
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The graph builder and its live no-retention probe (M1-802). This ticket
    creates no Java client and does NOT edit M1-802's ticket file — the
    DECIDE-BEFORE answer is recorded in this ticket and the design addendum
    for M1-802's implementor to read.
  - The setup-wizard step (M1-798 owns prod/scripts/*, SETUP_GUIDE.md, and
    every operator-facing install text).
  - Any Java or infochat.* config key — the DocumentedConfigKeyParityTest
    key set is unchanged.
  - Renaming INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES or changing its default —
    the name is shipped and documented; a compose comment states it governs
    both directories.
  - Containment of ComfyUI's input/ directory — Census below: written only
    by an upload API the Provider never calls; no writer exists or is
    planned.
  - The overlay's network shape (no host port, compose-network-only
    reachability) — unchanged from M1-797.
  - Any docs/spec/** edit — D75 already promises the end state; this ticket
    delivers a missing piece of its image half, so no spec amendment and no
    §12 approval leg.
acceptance:
  - "REPRODUCTION now passing: `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml config` renders tmpfs mounts on BOTH /opt/ComfyUI/output and /opt/ComfyUI/temp, each with an explicit size, and `grep -n '/opt/ComfyUI/temp' prod/images/comfyui/janitor.sh` shows the janitor sweeps it — the D75 image half (docs/spec/decisions.md:94) extended to the temp directory."
  - "SHIP-BLOCKER FAILURE-MODE (D75 backend no-retention end state; security.md §Trust boundaries — retention containment at the unauthenticated backend boundary must be a property of the IMAGE, independent of any submitted graph): bring the overlay up with INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES=1, plant one canary file in EACH of /opt/ComfyUI/temp and /opt/ComfyUI/output (`docker compose ... exec comfyui touch .../canary-temp.png .../canary-out.png`), wait at least 4 minutes (60 s sweep cadence + strictly-greater `-mmin` semantics ⇒ worst case TTL + two sweeps), then assert: `docker compose ... exec comfyui find /opt/ComfyUI/temp /opt/ComfyUI/output -type f` returns nothing AND `docker compose ... exec comfyui df -T /opt/ComfyUI/temp /opt/ComfyUI/output` reports tmpfs for both AND the service still answers `docker compose ... exec comfyui curl -fsS http://127.0.0.1:8188/system_stats`. A janitor sweeping only output fails it (temp canary survives); a non-tmpfs temp mount fails it (df shows overlay); a refactor that breaks the existing output sweep fails it (§10 control preserved)."
  - "ASSUMPTION-check (P2): the pinned ComfyUI commit's real temp directory resolves inside the mount — probe: `docker compose ... exec comfyui python3 -c 'import folder_paths; print(folder_paths.get_temp_directory())'` prints /opt/ComfyUI/temp, and the verified path is recorded in docs/design/future/image-generation.md either way. If the pinned commit (Dockerfile:15) resolves a DIFFERENT path, the mount and janitor target that path — rework, never guess."
  - "Design layer names both swept directories and records the DECIDE-BEFORE answer for M1-802 (containment is graph-shape-independent; an output-only graph stays the intended shape as RAM efficiency, not as a retention control): the no-retention-window addendum paragraph in docs/design/future/image-generation.md and the ComfyUI block in docs/design/07-deployment.md — verify: `grep -n 'temp/' docs/design/future/image-generation.md docs/design/07-deployment.md` hits both files and `grep -n 'M1-806' docs/design/future/image-generation.md` shows the dated extension note."
  - "mvn verify from repo root is green (no Java change expected; the run proves nothing else drifted)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md (D75)
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D75
---

# M1-806: ComfyUI temp/ containment: tmpfs + janitor sweep

## Context

M1-797 (commit fffcca36) shipped the D75 backend no-retention containment for
the /image ComfyUI backend: `/opt/ComfyUI/output` is tmpfs-backed and swept by
an in-image janitor (`INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES`, default 15). Its
round-1 review filed a RECOMMENDED-NEW-TICKET with DECIDE-BEFORE: M1-802
(.scratch/tick-review-M1-797-r1.txt:55-72): ComfyUI ALSO has a `temp/`
directory where PreviewImage-type nodes write intermediate/preview images, and
`temp/` is NEITHER tmpfs-backed NOR inside the janitor's sweep — verified: the
overlay renders one tmpfs target, `/opt/ComfyUI/output`
(docker-compose.comfyui.yml:44-47), and the janitor sweeps only that directory
(janitor.sh:9,15); `grep -rn temp prod/images/comfyui/` returns nothing. If
the server-built graph M1-802 will submit ever contains a temp-writing node,
preview pixels persist on the container's writable layer until the container
is removed, breaking the D75 end state ("backend-side output files removed",
decisions.md:94) that M1-797's containment and M1-802's acceptance probe
(asserts "no leftover output files backend-side", M1-802 acceptance item 5)
are supposed to guarantee. Discrepancy with the brief, noted: the probe is
item 5 in M1-802's filed acceptance list, not the brief's "item 4" —
verified against the ticket file. Nothing writes `temp/` today —
grep-verified: zero ComfyUI references in any Java source; the graph builder
does not exist — so this is a constraint to settle BEFORE M1-802 starts (it
is already runnable on the board), not a defect in live behavior. This
ticket IS the analysis (`analysis_ref: self`).

Prior art read and disposed: the reviewer's note (adopted as the problem
statement; its "(a) or (b) or both" options are adjudicated under Approach);
commit fffcca36's artifacts (the boundary extended here — its falsified-safe
properties are preserved, P3); the M1-802 ticket (DECIDE-BEFORE target; its
item-5 probe is unaffected by this ticket, see Out-of-scope);
tick-analysis/image-generation-feature.md P24 (the gap's origin: the
decomposition's retention enumeration named output/ only — `temp/` appears
nowhere in that analysis); redteam/image-spec-promotion-2026-08-07.md finding
5 (INFO-LEAK medium, backend retention — the finding class this constraint
serves).

## Root cause

Containment gap in a shipped boundary, not a live leak. Proven:

- The D75 image half covers exactly one directory: the tmpfs mount targets
  `/opt/ComfyUI/output` (docker-compose.comfyui.yml:44-47) and the janitor's
  only sweep is `find "$out" -type f -mmin +"$ttl" -delete` with
  `out=/opt/ComfyUI/output` (janitor.sh:9,15).
- ComfyUI's `temp/` is where PreviewImage-type nodes write previews. This is
  a prior-art claim (review note) plus upstream knowledge of the pinned
  commit (Dockerfile:15) — NOT verified against ComfyUI source in-tree
  (ComfyUI is fetched at image build time), so it is an ASSUMPTION the
  acceptance item 3 probe checks before the mount is trusted (P2).
- Files on the container's writable layer (overlayfs) survive restarts and
  are removed only with the container; nothing sweeps them. Hence a
  temp-writing graph would retain job-derived pixels indefinitely — the
  redteam finding 5 shape (retention of prompt-derived artifacts at the
  backend) re-opened through a directory the original enumeration missed.
- Why the enumeration missed it: image-generation-feature.md P24 derived the
  containment from "ComfyUI has no delete API for OUTPUT files" and never
  enumerated the backend's writable pixel directories (Census below closes
  that enumeration).

## Pitfalls

- P1: Containment must be a property of the IMAGE, not of the submitted
  graph. Option (a) — restrict the graph to output-writing nodes and pin the
  restriction — is not a containment control: nothing can be pinned before
  M1-802's builder exists, no pin survives future graph changes silently, and
  D75's end state is a property of the deployed configuration
  ("The required end state is verifiable", decisions.md:94). The design layer
  states the principle: "The backend no-retention window lives in the image"
  (image-generation.md:446). Trap: shipping a graph-shape note instead of
  image containment.
- P2: ComfyUI's real temp path is an ASSUMPTION (P2 above; prior art +
  upstream knowledge: `<ComfyUI root>/temp`, `folder_paths
  .get_temp_directory()`). Mounting a path ComfyUI does not write is
  containment theater — the folder_paths probe asserts the real path resolves
  inside the mount BEFORE the containment is trusted; if it differs, the
  mount moves. Ground-truth discipline: the assumption is checked at
  implementation, never stated as fact.
- P3: janitor.sh is the container's ENTRYPOINT (Dockerfile:57): a shell error
  there means ComfyUI never starts (exit → `restart: unless-stopped` loop).
  Preserve the shape M1-797's review falsified as safe: `set -eu`, one
  background loop, janitor the ONLY deleter over dedicated tmpfs mounts (so
  `find -delete` cannot lose a race — ComfyUI writes temp files but never
  deletes them), `exec "$@"` last. The change is minimal: add the second
  directory to the mkdir and the find, matching existing style. No second
  janitor, no cron.
- P4: §10 control preservation — this diff re-parameterizes a SHIPPED
  containment path. Enumerated obligations to carry across: the output/ sweep
  keeps working (same window, same 512 MB tmpfs, same TTL semantics); the
  compose comment carrying M1-801's dependency ("The Provider-side spool
  sweeper must exceed it", docker-compose.comfyui.yml:29-32) stays true; the
  no-host-port shape is untouched. The dual-canary probe plants in BOTH
  directories so the output arm is pinned by the same acceptance item — a
  refactor that silently drops the output sweep fails it.
- P5: §11 — five places state the containment as output-only and each becomes
  a stale comment asserting a premise the code no longer satisfies unless
  updated: Dockerfile:9-11, janitor.sh:3-5 header,
  docker-compose.comfyui.yml:41-43 comment, image-generation.md:446-454
  addendum paragraph, 07-deployment.md:1032. `INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES`
  keeps its name (renaming is churn, §1) but the compose comment states it
  governs both directories.
- P6: tmpfs is RAM — the temp mount needs an explicit size cap with a
  commented sizing basis (512 MB output precedent; compose-file convention,
  docker-compose.yml:16-41). Under the intended M1-802 graph shape temp/
  stays empty and an empty tmpfs charges no RAM, so the cap is pure
  backstop; but an UNBOUNDED temp tmpfs under a future temp-writing graph is
  host memory exhaustion — the same class commands.md:633-636 bounds for the
  Provider-side spool.
- P7: §1 scope — do not build the graph builder (M1-802), do not edit
  M1-802's ticket file, do not touch the wizard (M1-798), add no Java or
  config keys, change no network shape, mount no input/ directory (Census).
  The DECIDE-BEFORE answer lands as this ticket's record plus the design-doc
  note, nothing else.

## Approach

Chosen: option (b) of the review note — `temp/` becomes tmpfs-backed and is
swept alongside `output/` by the same janitor and the same window, so the
containment is unconditional: no graph shape, current or future, can leave
preview pixels on the writable layer. Derived from spec_refs: D75 makes the
no-retention end state verifiable in the DEPLOYED CONFIGURATION
(decisions.md:94), and §Trust boundaries puts the backend at an
unauthenticated graph-execution boundary (item 8 class, D77) whose retention
posture the deployment controls, not its clients. The design layer's own
principle decides between the review's options: the no-retention window lives
in the IMAGE (image-generation.md:446), and the no-content chain's link 6 —
"Pixels on tmpfs, deleted on completion, swept by age" (image-generation.md
:153) — covers EVERY pixel the backend writes, not only the ones the Provider
fetches.

DECIDE-BEFORE answer for M1-802 (recorded, per P7, here and in the design
addendum — not by editing M1-802's file): retention containment no longer
depends on graph shape, so M1-802 inherits no temp/-specific containment
obligation and its existing item-5 probe ("no leftover output files
backend-side") is backed by the image for both directories. An output-only
graph (SaveImage, no PreviewImage) remains the intended graph shape —
previews would charge tmpfs RAM and the Provider never fetches them — but
that is now an efficiency, not a retention control.

### Rejected alternatives (falsified, not adopted)

- Option (a) alone — restrict the graph to output-writing nodes, pinned by a
  test/probe: rejected AS CONTAINMENT. It is unverifiable before M1-802
  exists (the ticket must be verifiable standalone — DECIDE-BEFORE means it
  lands first), it cannot bind future graph changes, and client discipline
  is not a boundary control (§Trust boundaries). Its residue — output-only
  graph as the intended shape — survives only as the RAM-efficiency note
  above.
- "Both" as two containment controls: rejected — (a) adds no containment on
  top of (b); two controls for one property where one is unconditional is
  defensive redundancy (§7 spirit: the scenario "(b) holds but a temp file
  appears" is already handled by (b)'s sweep).
- Redirect ComfyUI's temp directory into the swept output tree via a CLI
  flag: rejected — it depends on a ComfyUI flag whose existence at the
  pinned commit is another assumption (P2 class), and it mixes temp files
  into the very directory M1-802's probe asserts empty, coupling two
  lifecycles. Mounting the path ComfyUI already writes needs no flag.
- Do nothing until M1-802's graph is known: rejected — D75's end state is a
  property of the deployed image and must not depend on future graph
  choices; the review explicitly filed this as DECIDE-BEFORE.

### Files to touch

The four in `files_scope`: `prod/images/comfyui/janitor.sh` (second
directory), `prod/images/comfyui/Dockerfile` (comment only),
`docker-compose.comfyui.yml` (second tmpfs mount + comments),
`docs/design/future/image-generation.md` (addendum paragraph),
`docs/design/07-deployment.md` (one sentence).

### Steps, in order

1. Verify the assumption FIRST (P2): against a container built from the
   current M1-797 image, run acceptance item 3's `folder_paths` probe and
   confirm the temp directory is `/opt/ComfyUI/temp`. Everything downstream
   keys on this path; if it differs, the mount and janitor target the
   verified path and the design doc records it.
2. `janitor.sh`: add the temp directory to the `mkdir -p` and to the single
   `find ... -delete` (one sweep over both paths — janitor stays the only
   deleter, P3); update the header comment to name both directories (P5).
3. `docker-compose.comfyui.yml`: add the tmpfs mount on `/opt/ComfyUI/temp`
   with an explicit size (536870912, same basis as output: ~2 MB per 1024px
   PNG; under the intended graph shape the mount stays empty and charges no
   RAM — the cap is a backstop, P6) and a sizing-basis comment per the
   base-file convention; extend the TTL env comment to state it governs both
   directories (P5). No other key changes.
4. `Dockerfile`: update the lines 9-11 comment to name both directories
   (comment-only edit, P5).
5. Rebuild the image, bring the overlay up, run the probes: item 1 (config
   render), item 2 (dual-canary plant at TTL=1, ≥4 min wait, both arms),
   item 3 (folder_paths, already run in step 1 — re-assert on the rebuilt
   image).
6. Update the design layer (item 4): extend the addendum's no-retention
   paragraph (image-generation.md:446-454) with a dated M1-806 note naming
   both swept directories and the DECIDE-BEFORE answer; correct the
   07-deployment.md:1032 sentence to "output and temp directories".
7. `mvn verify` from the repo root (item 5).

Order rationale: the path verification precedes every edit that keys on the
path; the image/compose change and its comment updates land together (§11 —
never let a comment lag the code it describes); docs after the probes pass,
so the design record states measured truth.

### Controls to preserve (engineering-rules §10)

The diff re-parameterizes the shipped M1-797 containment path; carried
across, each pinned by a named probe: the output/ sweep (dual-canary probe,
output arm); the output tmpfs and its 512 MB cap (item 1's config render);
the TTL env semantics — default 15, M1-801's spool sweeper must exceed it
(compose comment stays true); the janitor's only-deleter + dedicated-tmpfs
property the M1-797 review falsified-safe (P3 — no second deleter, no shared
mount); the ENTRYPOINT startup shape (`exec "$@"` last; service-health
assertion inside item 2); the no-host-port network shape (untouched).

### Pitfall→mitigation

P1→step 2-3's unconditional image containment + item 2's graph-independent
probe; P2→step 1 + item 3; P3→step 2's minimal shape; P4→step 2-3 + item 2's
output arm; P5→steps 2-4/6 + item 4's greps; P6→step 3's explicit size +
item 1; P7→`out_of_scope` + the files_scope-bound diff.

## Definition of done

The overlay renders tmpfs mounts with explicit sizes on both
`/opt/ComfyUI/output` and `/opt/ComfyUI/temp`; the janitor sweeps both on the
same window; a planted canary in EITHER directory does not outlive the window
under the TTL=1 failure-mode probe while the service stays healthy; ComfyUI's
real temp path is verified against the pinned commit and recorded; the design
addendum and the deployment doc name both directories and the DECIDE-BEFORE
answer; repo-root verify green.

## Verification

- P1 → acceptance item 2: the plant probe is graph-independent — it submits
  no graph, it plants the preview file the containment must sweep. Any
  solution resting on graph shape fails by construction, because nothing
  constrains what a future graph contains.
- P2 → acceptance item 3 (failure-mode: the pinned commit resolves a temp
  path outside the mount ⇒ the probe fails and the mount moves; re-runnable
  on every ComfyUI commit bump).
- P3 → acceptance items 1-2: `docker compose config` renders (valid YAML)
  and item 2 runs only while the service answers `system_stats` — a broken
  entrypoint never reaches the canary assertions.
- P4 → acceptance item 2's output arm (canary planted in BOTH dirs; a
  refactor dropping the output sweep fails the same probe) + item 1's
  rendered output tmpfs unchanged at 512 MB.
- P5 → acceptance item 4's greps + review-time grep over the diff: every
  comment that today says output-only (Dockerfile:9-11, janitor.sh:3-5,
  docker-compose.comfyui.yml:41-43) names both directories after the change.
- P6 → acceptance item 1: the rendered config shows an explicit tmpfs size
  on the temp mount (a size-less mount fails the item); sizing basis
  commented in the compose file per base-file convention.
- P7 → `out_of_scope` + diff review: files touched ⊆ files_scope;
  `grep -rn 'ComfyUI' infochat-*/src` still returns nothing.
- acceptance item 5 → full-suite regression (engineering-rules §5).
- Non-vacuity: dropping the temp mount fails item 1; a janitor sweeping only
  output fails item 2 (temp canary survives); mounting the wrong path fails
  item 3; breaking the output sweep fails item 2's output arm.

## Out-of-scope

Named in `out_of_scope`: the graph builder (M1-802) and any edit to its
ticket file — the DECIDE-BEFORE answer is a record here and in the design
addendum, which M1-802's implementor reads; the wizard (M1-798); any Java or
config key; renaming or re-defaulting the TTL env; `input/` containment (no
writer — Census); network shape; any spec edit (D75 already promises the end
state; design-doc edits carry date + ticket ID, which §12 permits outside
spec). No pre-existing test is modified. M1-802's item-5 probe needs no
change: its graph contains no temp-writing node, so `temp/` is empty at
probe time, and the image now guarantees the sweep of both directories
regardless.

## Census

Class = directories under `/opt/ComfyUI` the backend writes pixel/job
artifacts to. Re-runnable enumeration: `docker compose -f docker-compose.yml
-f docker-compose.comfyui.yml exec comfyui ls -1 /opt/ComfyUI` cross-read
against the pinned commit's `folder_paths` (Dockerfile:15). Disposition of
every returned path:

- `output/` — SaveImage-class nodes; GUARDED by M1-797 (tmpfs 512 MB +
  janitor sweep).
- `temp/` — PreviewImage-class nodes (P2 assumption, probe-verified at
  start); GUARDED by this ticket (tmpfs + same sweep).
- `input/` — written only via the upload API (`POST /upload/image`), which
  the Provider never calls (M1-802's client surface is submit / poll /
  cancel / fetch / history-clear — no upload verb). OUT-OF-SCOPE: no writer
  exists or is planned; a future graph adding a LoadImage-type node must
  re-run this census.
- `models/` — read-only named volume (docker-compose.comfyui.yml:40); never
  written by the backend.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-806-comfyui-temp-containment.md
```
