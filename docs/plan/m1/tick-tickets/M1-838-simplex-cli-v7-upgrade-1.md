---
id: M1-838
title: "Bundle simplex-chat v7.0.0; re-verify launch surface"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  to-be-written: BundledSimplexCliPinTest.dockerfilePinsV700WithBuildTimeSha256
  (infochat-provider/src/test/java/app/zcat/infochat/provider/config/, beside
  DocumentedConfigKeyParityTest) — reads
  infochat-provider/src/main/docker/Dockerfile.jvm and asserts the
  SIMPLEX_CHAT_VERSION env is v7.0.0 and a 64-hex build-time sha256 guards the
  download; it fails today because Dockerfile.jvm:47 pins v6.5.4. Companion
  probe at start: `docker compose run --rm --no-deps --entrypoint
  /usr/local/bin/simplex-chat infochat-provider --version` prints the 6.5.4.x
  banner (HANDOFF.md:2527 records the baked binary reporting v6.5.4.1).
  `start` writes the test and runs it RED before any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/simplex-cli-v7-upgrade.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/docker/Dockerfile.jvm
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config/
  - prod/scripts/6b-simplex-provision.sh
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - Wire-form re-verification and any codec change (M1-839 text/group/edit,
    M1-840 attachment/XFTP) — this ticket lands the binary and its
    launch/provisioning premises only.
  - SimpleX inline image delivery (batch H — targets the upgraded CLI after
    this lands).
  - signal-cli's pin (0.14.5) and every Signal surface.
  - Switching the target to v7.1-beta.0 (analysis P9 — escalate if v7.0.0
    evidence demands it; never drift silently).
acceptance:
  - "BundledSimplexCliPinTest.dockerfilePinsV700WithBuildTimeSha256 passes — REPRODUCTION (written and run RED at start): Dockerfile.jvm carries SIMPLEX_CHAT_VERSION=v7.0.0 with a 64-hex sha256 on the curl-download line."
  - "The v7.0.0 release notes/changelog AND the v6.5.4→v7.0.0 tag-diff findings for the ten adapter-depended surfaces (analysis §Ground truth list) are captured as evidence in the ticket record and summarized in docs/design/06-messaging.md next to the launch/wire-form sections — Verify: `grep -n 'v7.0.0' docs/design/06-messaging.md` shows the recorded surface-review result; any surface the notes mark changed is handed to M1-839/M1-840 explicitly, and a D51/D52-shaped change escalates instead of proceeding (analysis P10)."
  - "The sha256 on the download line is the implementor's own computation from the TLS download of the v7.0.0 artifact (analysis P1), and the artifact NAME is verified against the release page (A2: simplex-chat-ubuntu-22_04-x86_64 is an assumption until checked) — Verify: build probe `docker compose build infochat-provider` exits 0 with the committed name+sha pair and the build-log tail is recorded; a wrong name or sha makes Dockerfile.jvm:53 `sha256sum -c` fail the build non-zero, so a passing build probe is impossible without the correct pair."
  - "FAILURE-MODE (supply chain): corrupting one hex char of the recorded sha256 makes the image build fail non-zero at sha256sum -c — Verify: a scratch-build probe with a mutated sha (never committed) observed failing; the committed line keeps the correct sha."
  - "Loopback bind re-verified against the v7.0.0 binary (analysis P2; docs/spec/security.md §Trust boundaries #7, security.md:71-80): run the extracted v7.0.0 binary with the production `-d <prefix> -p <port>` argv (SimpleXSubprocess.commandFor shape) and `ss -tlnp` shows a single LISTEN 127.0.0.1:<port> and no 0.0.0.0/:: listener — Verify: the probe output is recorded in the ticket record beside the M1-429 spike result; an off-loopback bind STOPS the ticket (the M1-430 runtime guard would fire in production — that is an escalation, not a merge)."
  - "Identity-DB migration (analysis P3): bot data-dir AND the host LiveAdmin/LiveUser client dirs are backed up BEFORE the first v7.0.0 start; the v6.5.4→v7.0.0 migration path is confirmed from the upstream notes (A3) and observed succeeding on the migrated DBs (`--version` plus one read command against the migrated bot DB) — Verify: the migration observation is recorded; the host probe binary at prod/runtime/simplex-clients/bin/simplex-chat is re-extracted from the rebuilt image and its `--version` output (7.0.0.x) is recorded (analysis P8)."
  - "6b provisioning premises re-verified against the real v7.0.0 binary (analysis P4): exit-code-on-bad-command, the anchored markers `^bad chat command|^simplex-chat: `, `--create-bot-display-name`/`-t`/`-e`/`-y` acceptance, `/ad` idempotency output, and the `https://smp` link grep (6b-simplex-provision.sh:119-128,163-186) — Verify: each premise's observed v7.0.0 behavior recorded; any drift updates the script AND the fake-docker emulation in SimpleXProvisioningWiringTest together, and SimpleXProvisioningWiringTest stays green (this ticket authorizes exactly that conditional modification)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/config/BundledSimplexCliPinTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java (ONLY if real-binary drift is observed — the fake docker's emulated behaviors move to the v7.0.0 observations, same assertions on our script's contract)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Trust boundaries
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  - D10
  - D37
  - D46
---

# M1-838: Bundle simplex-chat v7.0.0; re-verify launch surface

## Context

The Provider image bakes `simplex-chat` pinned at tag `v6.5.4` (binary
reports 6.5.4.1; Dockerfile.jvm:47-53 — the only pin site, grep-verified).
The user wants the bundle on upstream's v7.0.0 stable. Every adapter wire
form was live-verified against the old binary, so the upgrade is an
evidence-base rebuild, not a one-line bump (shared analysis:
`analysis_ref:`). This ticket lands the binary itself plus the
launch/provisioning premises that are per-binary empirical truth; the two
siblings re-verify the wire forms on top of it.

## Root cause

The pin, sha256, and every launch-surface premise (loopback bind, `-d`
prefix semantics, exit-0-on-bad-command, provisioning flags) were verified
against one exact binary (M1-429/M1-431 spikes). A binary swap invalidates
each premise's provenance. What v7.0.0 actually changed is NOT knowable
from this checkout (analysis A1) — capturing the changelog + tag diff is
step 1, and every downstream claim is conditional on it.

## Pitfalls

Numbered consistently with the analysis document.

- P1: sha256 copied from anywhere but the implementor's own TLS download;
  artifact name assumed, not read off the release page (A2).
- P2: the 127.0.0.1-only bind is per-binary empirical (M1-429 spike);
  skipping the `ss` re-check silently voids trust boundary #7 — the M1-430
  runtime guard is the backstop, not the evidence.
- P3: one-way simplex-chat DB migration of the bot identity (D10 anchor)
  and the host live-harness client DBs; no backup → no rollback path.
- P4: 6b provisioning parses v6.5.4 stdout markers and assumes
  exit-0-on-error; SimpleXProvisioningWiringTest emulates the OLD binary
  with a fake docker, so drift is invisible to the suite.
- P8: the live harness execs a host-side EXTRACTED binary; a stale v6.5.4
  extraction makes all later "live" evidence about the wrong version.
- P9: release notes summarize; the tag diff is truth for the ten surfaces.
  Stay on v7.0.0 — v7.1-beta.0 exists but is not the target.
- P10: a v7.0.0 change to the D51/D52 frame mechanism changes what the spec
  promises — escalate, never rider.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Fetch the v7.0.0 release notes/changelog and diff the v6.5.4→v7.0.0
     tags on the ten depended surfaces (analysis §Ground truth). Record the
     result in the ticket and in docs/design/06-messaging.md (P9). If the
     evidence shows a D51/D52-shaped break or removes a surface the adapter
     needs with no adaptation path, STOP and escalate (P10).
  2. Write BundledSimplexCliPinTest RED (reproduction), then bump
     Dockerfile.jvm: version `v7.0.0`, artifact name per the release page,
     sha256 computed from the implementor's own download (P1). Update the
     adjacent comment block (:44-46) so it states v7.0.0 truth (analysis
     P11 — this file is touched, so its claims are re-read).
  3. Rebuild the image; run the failure-mode sha-mutation probe (item 4)
     before committing the correct sha.
  4. Re-extract the host binary to prod/runtime/simplex-clients/bin/ from
     the rebuilt image; record `--version` (P8).
  5. Back up the bot data-dir and host client DBs; observe the migration
     on first v7.0.0 start; record it (P3).
  6. Run the loopback `ss` probe with the production argv shape; record
     beside the M1-429 record (P2).
  7. Re-run each 6b premise against the real v7.0.0 binary; on drift, adapt
     6b-simplex-provision.sh and the SimpleXProvisioningWiringTest fake
     docker together (P4 — the pre-authorized conditional modification).
- **Controls to preserve (§10):** the `sha256sum -c` build gate is
  strengthened, never weakened; the Dockerfile `-DskipTests` build-stage
  exception stays exactly as-is; 6b's D37 discipline (anchored marker echo
  only, contact link never persisted) is untouched by any drift adaptation;
  SimpleXSubprocess and its M1-430 guard are NOT modified by this ticket —
  only their documented premises are re-verified.
- **Pitfall→mitigation:** P1→steps 2-3 + items 3-4; P2→step 6 + item 5;
  P3→step 5 + item 6; P4→step 7 + item 7; P8→step 4 + item 6; P9→step 1 +
  item 2; P10→step 1's escalation clause.

## Definition of done

Pin test green with v7.0.0 + self-computed sha256; changelog/tag-diff
evidence recorded and surface-by-surface dispositions handed to M1-839/
M1-840; sha-mutation failure probe observed; loopback bind re-verified and
recorded; data-dir backups taken and migration observed; host probe binary
re-extracted at 7.0.0.x; every 6b premise re-verified (script + fake
updated together iff drift); full verify green.

## Verification

- P1 → acceptance items 1/3/4 — the pin test pins the end state; item 4
  feeds the build a mutated sha and asserts non-zero (non-vacuity: a build
  that ignores the sha fails item 4).
- P2 → item 5's recorded `ss` probe; a v7.0.0 off-loopback bind stops the
  ticket rather than shipping.
- P3 → item 6's backup-then-migrate record; a skipped backup is a
  review-visible acceptance miss.
- P4 → item 7's premise-by-premise record; SimpleXProvisioningWiringTest
  green either way (drift ⇒ updated fake, no drift ⇒ untouched).
- P8 → item 6's `--version` record for the extracted binary.
- P9/P10 → item 2's committed evidence + escalation clauses.
- failure mode → item 4's sha-mutation probe: the scratch build fed a
  sha256 with one flipped hex char must come back non-zero at
  `sha256sum -c`; a mutated-sha build that exits 0 means the supply-chain
  gate is broken and the ticket fails if shipped anyway (the probe is
  observed failing, recorded, and never committed).
- acceptance item 8 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: all wire-form re-verification and codec work
(M1-839/M1-840), batch-H image delivery, the signal-cli pin, and any
target other than v7.0.0. One pre-existing test may be modified —
SimpleXProvisioningWiringTest's fake-docker emulation — ONLY when a
recorded real-binary observation contradicts it; the modification keeps the
script-contract assertions at equal strength (§8 authorization: this
paragraph). No `docs/spec/**` edit rides this ticket; D51/D52-shaped
findings escalate per P10.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-838-simplex-cli-v7-upgrade-1.md
```
