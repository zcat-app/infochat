---
id: M1-838
title: "Bundle simplex-chat v7.0.0; re-verify launch surface"
status: done
created: 2026-08-14
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15: lint clean (after copying the gitignored tick-analysis doc into
  the worktree); all file:line citations spot-checked true (Dockerfile.jvm:47-53
  sole pin site per census grep; 6b markers/flags/link; security.md:71-80);
  analysis pitfalls P1-P4,P8-P11 all landed; no in-flight tick ticket (no
  module overlap); blocked_by empty. No blocking ambiguity.
flow: tick
reproduction: >-
  BundledSimplexCliPinTest.dockerfilePinsV700WithBuildTimeSha256
  (infochat-provider/src/test/java/app/zcat/infochat/provider/config/,
  beside DocumentedConfigKeyParityTest) — reads
  infochat-provider/src/main/docker/Dockerfile.jvm and asserts the
  SIMPLEX_CHAT_VERSION env is v7.0.0 and a 64-hex build-time sha256 guards the
  download. Run RED 2026-08-15 (worktree .opencode/worktrees/M1-838, log
  /tmp/opencode/pin-red.log): "The bundled simplex-chat is v6.5.4 but the
  adapter estate is verified against v7.0.0 … expected: <v7.0.0> but was:
  <v6.5.4>"; all 1844 other unit tests green.
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
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (comment-cap, informational — class javadoc naming the real trap), SCOPE PASS"
    diff_stats: "5 files, +266/-22"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-838-r1.txt
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

## Evidence record (2026-08-15, implementation)

All probes ran on this deployment host; URLs/links in captured output are
redacted below (D37 — frame types and field shapes only, never links).

### Step 1 — upstream evidence (P9, A1, A2)

- Release page (fetched 2026-08-15): tag `v7.0.0`, released 2026-07-28,
  commit `e11128c`, GPG-signed; binary reports `SimpleX Chat v7.0.0.11`
  (host run). Artifact name on the release page IS
  `simplex-chat-ubuntu-22_04-x86_64` (A2 confirmed, not an assumption);
  a `v7.1-beta.0` exists and was NOT taken.
- Source tag-diff v6.5.4→v7.0.0 (tarballs of both tags, `src/Simplex/Chat/**`):
  - `Options.hs`: `-d`/`-p`/`-y`/`-t`/`-e`/`--create-bot-display-name` all
    present; no `--network` option in either tag. ADDITIVE only:
    `--user-display-name` (mutually exclusive with
    `--create-bot-display-name`, unused by us), `--user-image-file`,
    relay/web-preview flags, `--headless`, `--create-bot-client-service`.
  - `Library/Commands.hs` `/_send`: grammar gained `signMessagesP` between
    TTL and ` json `; `signMessagesP = " sign=" *> onOffP <|> pure False` —
    OPTIONAL, old form parses unchanged. `/_update`, `/_join`,
    `/show_address`, `/ad`, `/auto_accept` grammar lines: zero diff.
  - `Controller.hs` event constructors (`CEvtSndFile*`,
    `NewChatItems`-class, group invitation/join): zero diff.
  - `Types.hs`: `mentions` field, `UserContactLink` response shape,
    `MsgContent`/`ComposedMessage` definitions unchanged; ADDITIVE
    `GroupMember.memberVerifiedCode` and a new `ContactNameOrLink` type
    (SimpleX Names, not part of any consumed frame).
  - Migrations (A3): `Store/SQLite/Migrations` strictly additive — all 151
    v6.5.4 `M20*` files present in v7.0.0 + 13 new (M20260516…M20260720).
- **Ten-surface dispositions**: surfaces 1,2,5,6,7,8,9 (send/composed
  grammar, filePath, live=on/off, inbound frames, mentions{},
  receivedGroupInvitation, /show_address) — unchanged per tag diff, handed
  to **M1-839** for v7.0.0 frame re-capture (tag diff is source evidence,
  not a capture). Surfaces 3,4 (XFTP completion semantics, 1 GiB ceiling) —
  constructors unchanged but v7.0.0 links simplexmq 7.0.0.6 (bumped from
  6.5.4.x), so the completion-path source check and ceiling re-measurement
  are **M1-840's** duty. Surface 10 (subprocess launch/loopback) —
  re-verified empirically below. No D51/D52-shaped change found (P10 clear,
  no escalation).

### Steps 2-3 — pin test + sha (P1)

- Reproduction RED: see `reproduction:` above (v6.5.4 vs v7.0.0, all 1844
  other unit tests green). GREEN after the bump.
- Own TLS download (host curl):
  `393279f37a57ff7a63b92cffbd583d1d8abb5ea13e28f8caea74539d7c8db91d` —
  byte-identical to upstream's published `_sha256sums` figure
  (`393279f3…db91d`); artifact size 79,531,064 bytes.
- `docker compose --profile prod build infochat-provider` (worktree, host
  network per compose) — exit 0, image `m1-838-infochat-provider`; baked
  binary `--version` → `SimpleX Chat v7.0.0.11` (only possible through the
  correct name+sha at `sha256sum -c`).
- **Failure-mode probe** (scratch Dockerfile in /tmp, never committed):
  same layer with the sha's last hex flipped (`…c8db91d`→`…c8db91e`) →
  real TLS download in-container, then `sha256sum: WARNING: 1 computed
  checksum did NOT match` / `/usr/local/bin/simplex-chat: FAILED` → build
  exit code 1. Supply-chain gate proven non-vacuous.

### Step 4 — host probe binary (P8)

This host had no `prod/runtime/simplex-clients/` (the live-harness client
dirs LiveAdmin/LiveUser do not exist on this box — nothing to back up,
recorded). Extracted fresh from the rebuilt image to
`prod/runtime/simplex-clients/bin/simplex-chat`:
`--version` → `SimpleX Chat v7.0.0.11`; sha256 of the extracted file
equals the pinned artifact hash byte-for-byte. Old prod image
(`infochat-prod-infochat-provider`) binary banner recorded pre-upgrade:
`SimpleX Chat v6.5.4.1`.

### Step 5 — backup + migration (P3)

- Bot data-dir `/home/infochat/infochat/prod/runtime/simplex` (root-owned,
  read via container): backed up BEFORE any v7.0.0 start to
  `/home/infochat/pre-v7-upgrade-backup-20260815/simplex-bot-datadir-pre-v7.tar.gz`
  (contains `simplex_v1_chat.db` + `simplex_v1_agent.db`; tar sha256
  `6e8e78a7cfb878d00ecd3454c5ff076d55602b8c00a5adbb3b0ccc86aefcc492`).
- Migration observed on a COPY (live dir untouched: the running v6.5.4
  Provider keeps serving; the real migration happens at image-roll time
  with this backup as the rollback): v7.0.0 `-y -e "/show_address"` →
  exit 0, no `^simplex-chat: ` fatal marker, `Current user: infochat-bot`
  + address returned (URLs redacted). DB evidence: chat.db
  `migrations` table 151→164 rows (+13, exactly the new tag files),
  agent.db 44→45.

### Step 6 — loopback bind (P2)

v7.0.0 binary with production argv shape (`-d <prefix> -p 5225`,
`SimpleXSubprocess.commandFor`), `ss -tlnp`:
`LISTEN 0 1024 127.0.0.1:5225 users:(("simplex-chat",pid=…,fd=14))` and
NO `0.0.0.0:5225` / `[::]:5225` listener. Trust boundary #7 holds on
v7.0.0 (recorded beside the M1-429 spike result in
docs/design/06-messaging.md §Bundle v7.0.0 surface review).

### Step 7 — 6b provisioning premises (P4)

Real v7.0.0.11 binary, throwaway data-dir, 6b's exact argv shape
(`-d <dir>/simplex_v1 -y …`):

| premise | v7.0.0 observation | drift |
|---|---|---|
| exit code on bad command | `/definitely-not-a-command` → exit **0** | none |
| anchored failure markers | line 2 col 0: `bad chat command: Failed reading: empty` | none |
| `--create-bot-display-name`/`-y`/`-t`/`-e` | all accepted; profile created non-interactively, no prompt | none |
| `/ad` idempotency | second run: `you already have chat address, to show: /sa` | none |
| `https://smp` link grep | first `/ad` → 1 hit; `/show_address` → 1 hit | none |

**Zero drift** ⇒ per the pre-authorization, `6b-simplex-provision.sh` and
`SimpleXProvisioningWiringTest` are UNTOUCHED (fake-docker emulation stays
at equal strength, still green in verify).

## Review observations (round 1, 2026-08-15)

Recorded from the tick-reviewer's RECOMMENDED-NEW-TICKET entry
(`TOUCHED-BY-THIS-DIFF: no`, no `DECIDE-BEFORE:` — recorded only; filing a
ticket is the user's call):

- The live-harness documentation and the actual host layout disagree. The
  analysis (docs/plan/m1/tick-analysis/simplex-cli-v7-upgrade.md §Ground
  truth, citing docs/plan/live-e2e/HANDOFF.md:2526-2531) describes a
  pre-existing host extraction at `prod/runtime/simplex-clients/bin/
  simplex-chat` and LiveAdmin/LiveUser client dirs as standing facts; on
  the deployment host neither existed before this ticket (see Step 4
  record). The live-e2e handoff documentation should match the host it
  describes (or state the setup steps to recreate the harness layout), so
  the next live run does not discover the gap mid-probe.
