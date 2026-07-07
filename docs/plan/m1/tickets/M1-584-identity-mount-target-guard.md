---
id: M1-584
title: "Identity mount targets: correct the over-claiming allowlist comment; refuse system-prefix and colon data-dirs"
status: done
created: 2026-07-06
last_updated: 2026-07-07
clarity_check:
  date: 2026-07-07
  verdict: WARN
  warnings:
    - >-
      Acceptance item 3's "pack.sh applies the same two refusals" has no test
      in test_plan (no PackWiringTest exists); a regression in pack.sh's
      mirrored validation would pass mvn verify undetected. pack.sh's mirror
      is verified by code review only.
    - >-
      Acceptance item 5 ("§7.10.1 notes the two constraints") does not pin
      expected wording/placement; implement as a checkable one-line note in
      the pack.sh/restore.sh data-dir contract bullet.
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/restore.sh
  - prod/scripts/pack.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Bundle signing or promoting the tampered bundle into the v1 threat model.
    security.md keeps the bundle/config trusted (supply-chain excluded); this
    ticket is comment truthfulness plus a cheap boundary gate, the
    M1-568/M1-565 defense-in-depth precedent.
  - >-
    Relocating identity dirs / relaxing the same-absolute-path clone contract.
    Unchanged.
  - >-
    An allowlist gate (e.g. require data-dirs under prod/runtime/). The
    compose bind sources are operator-configurable, so a denylist of
    clearly-system prefixes is the widest gate that breaks no legitimate
    layout.
acceptance:
  - >-
    restore.sh's extraction comment block no longer claims a tampered bundle's
    members are "never written onto this host — doubly so, since only the
    allowlisted dirs are even mounted writable". It states precisely what
    holds: the named-member allowlist plus mount scoping bound EXTRA members
    of an honest-DATA_DIR bundle; a COHERENTLY tampered secrets.env chooses
    the mount target itself (the writable `-v /$rel:/$rel` is derived from the
    same attacker-controlled value the allowlist is built from), and the
    M1-568-era incidental backstop — the non-root untar hitting EACCES on
    root-owned system dirs — no longer exists under the M1-569 root untar.
    Out-of-model either way; the comment must not over-claim.
  - >-
    The existing DATA_DIR validation loop in restore.sh refuses, BEFORE any
    mount is built, values that resolve under clearly-system prefixes (at
    minimum: /etc /root /boot /bin /sbin /lib /lib64 /dev /proc /sys
    /var/lib/docker), with an actionable FAIL naming the offending key. This
    restores an explicit equivalent of the EACCES property M1-569 removed.
    It is boundary validation (operator config parsing), not internal
    defensive code.
  - >-
    The same loop refuses DATA_DIR values containing ':' — the docker -v
    mount-spec separator, currently yielding a mis-parsed mount and an obscure
    docker error — with a message naming the constraint. pack.sh applies the
    same two refusals (its identity-tar mount construction is the twin).
  - >-
    RestoreWiringTest: a fixture with INFOCHAT_SIGNAL_DATA_DIR=/etc/cron.d is
    refused pre-mutation; a colon-containing path is refused with the named
    message.
  - "§7.10.1 notes the two constraints in the data-dir contract."
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "RestoreWiringTest — system-prefix data-dir refused; colon data-dir refused."
  modifies:
    - "restore.sh + pack.sh — data-dir boundary refusals + corrected comment; 07-deployment.md — contract note."
  preserves:
    - "The M1-568 named-member allowlist, the M1-569 root untar and mount scoping, the empty-allowlist guard, and all existing extraction tests."
spec_refs:
  - "docs/design/07-deployment.md §7.10.1"
  - "docs/spec/security.md §Threat model"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 157
      removed: 18
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-07
    verdict: CLEAN
    base: main (96aac25e)
    head: m1/M1-584-identity-mount-target-guard (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-584-2026-07-07.md
    out_of_model_count: 3
    note: >-
      Pre-commit audit (security_relevant: true) of the branch working tree.
      CLEAN. Three out-of-model advisories, all matching deliberate documented
      trade-offs: (1) literal-prefix match, no realpath — a `..`/symlink
      coherent tamper could evade the denylist, but that is supply-chain /
      trusted-config, out of security.md scope, and the comment acknowledges
      the no-realpath limitation; (2) the denylist omits /usr and /usr/local
      (a merged-usr system's /bin,/sbin,/lib symlink into /usr) — a possible
      belt-and-suspenders addition, but out-of-model and risks false-positives
      on legit /usr/local state, so not filed; (3) the whole pack/restore
      clone-tooling surface is outside the documented threat model — the diff
      strictly ADDS defense-in-depth and CORRECTS an over-claim, removing no
      protection. None warrants a follow-up ticket.
---

# M1-584: identity mount-target guard + honest comment

## Context

MEDIUM finding of the 2026-07-06 audit of M1-567..576. M1-569's root
in-container untar builds its writable bind-mounts from the bundle's own
staged secrets.env: set `INFOCHAT_SIGNAL_DATA_DIR=/etc/cron.d` with a matching
`etc/cron.d/…` member and the consistency gate passes, the loop builds
`-v /etc/cron.d:/etc/cron.d` writable, and root tar writes a root-owned cron
file onto the host. Under M1-568's non-root untar the identical write died on
EACCES — incidental protection M1-569 removed while its comment claims the
opposite ("ONLY the mounted data-dirs are writable on the host … doubly so").

Falsified before filing: the scenario IS out-of-model (a coherently tampered
bundle/secrets.env is supply-chain, excluded by security.md, and game-over via
DB creds regardless) — so this is not an in-model vulnerability. What survives
falsification: (a) a load-bearing comment that over-claims is a correctness
defect in its own right; (b) the repo's own precedent (M1-568 allowlist,
M1-565 shape gate) is to take cheap out-of-model hardening when it is a
few lines at an existing boundary; (c) the colon refusal is a pure
error-message improvement for trusted-but-fallible operator config.

Note the exact-match gate fix (M1-581) does NOT close this: a coherent tamper
controls both sides of the consistency check.

## Notes

- Coordinate with M1-580/M1-581 (same file) — run serially.
- Audit provenance: finding M6 of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`).
