#!/bin/bash
# prod/scripts/shred-bundle.sh — operator-invoked secure disposal of a pack.sh
# bundle or a recovery secret-material directory (§7.10.1 "Migrating to another
# device", M1-572). A pack.sh bundle is the single highest-value artifact this
# system emits — DB passwords, LLM API key, the full audit log, the
# UNRECOVERABLE per-adapter identity keys, every secret at once. Once its
# purpose is served, a plain `rm` leaves that material in freed blocks; this
# helper standardizes overwrite-then-remove: `shred -uz` every file, then drop
# the tree. It closes the pack -> transfer -> restore -> verify -> DISPOSE
# lifecycle.
#
# DELIBERATELY MANUAL: nothing auto-invokes this — not restore.sh, not cron.
# The bundle is the disaster-recovery FALLBACK; it must outlive the restore
# until the operator has verified the clone healthy (restore.sh's own cutover
# note). Auto-destruction at restore time would remove the only backup exactly
# when a subtly-broken restore reveals itself. Disposal stays an explicit,
# operator-timed act; this script only makes that act safe and one-command.
#
# BEST-EFFORT disposal: on a copy-on-write or journaled filesystem (btrfs, zfs,
# ext4 journaling) shred(1) cannot guarantee the OLD blocks are unrecoverable —
# the same class of caveat as pack.sh's "encryption is YOUR responsibility"
# (D34/§7.10). Hardlinks cut both ways: shred overwrites the shared INODE, so
# other directory entries for the same file survive pointing at zeroed content —
# and conversely a hardlinked "safety copy" IS destroyed by shredding any one of
# its names. On SSDs, wear-leveling/FTL remapping means the overwrite may never
# reach the original NAND cells. Full-disk encryption of the storage medium is
# the real guarantee; overwrite-then-remove is the best-effort step this helper
# standardizes, not a substitute for it.
#
# Runs under the operator account — no docker, no root: pack.sh writes bundles
# 0600 operator-owned under a 077 umask, and the safety-copy members
# (db-independent dump, identities tarball, raw-config/) are likewise
# operator-owned regular files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT_ABS="$(realpath "$SCRIPT_DIR/../..")"

usage() {
  echo "Usage: shred-bundle.sh [-y|--yes] <target>"
  echo "  Securely dispose of a pack.sh bundle (a *.tgz file), a *.pgc dump, or"
  echo "  a recovery secret-material directory: shred -uz every file, then remove."
  echo "  -y, --yes   skip the interactive confirmation (unattended use)"
}

ASSUME_YES=no
TARGET=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -y|--yes) ASSUME_YES=yes; shift ;;
    *)
      if [[ -n "$TARGET" ]]; then
        echo "FAIL: exactly one target expected (got '$TARGET' and '$1')." >&2
        usage >&2
        exit 1
      fi
      TARGET="$1"; shift ;;
  esac
done

if [[ -z "$TARGET" ]]; then
  usage >&2
  exit 1
fi

# ── path guard (load-bearing: this tool destroys what it is pointed at) ──
# Order matters: existence, then dangerous-path refusals on the RESOLVED
# absolute path (symlinks can dress / or $HOME up as something harmless),
# then shape eligibility. Nothing is inventoried or removed before all three
# pass.
if [[ ! -e "$TARGET" ]]; then
  echo "FAIL: target does not exist: $TARGET — nothing removed." >&2
  exit 1
fi
TARGET_ABS="$(realpath "$TARGET")"

if [[ "$TARGET_ABS" == "/" ]]; then
  echo "FAIL: refusing to act on / — nothing removed." >&2
  exit 1
fi
if [[ -n "${HOME:-}" && "$TARGET_ABS" == "$(realpath -m "$HOME")" ]]; then
  echo "FAIL: refusing to act on the invoking user's HOME ($TARGET_ABS) — nothing removed." >&2
  exit 1
fi
if [[ "$TARGET_ABS" == "$REPO_ROOT_ABS" ]]; then
  echo "FAIL: refusing to act on the repo root ($TARGET_ABS) — nothing removed." >&2
  exit 1
fi

# Shape eligibility: only a pack.sh bundle file or a safety-copy *.pgc dump, or
# a directory shaped like bundle / recovery material — at least one *.tgz (the
# bundle or the identities tarball) or *.pgc dump at the immediate level, a
# db/*.pgc one level down or a .infochat-pack.* name (both mark an
# interrupted-pack staging remnant: SIGKILL/OOM/power loss during pg_dump
# outruns pack.sh's EXIT trap, which only covers catchable exits — M1-583), or
# a raw-config/ subdir. Anything else is refused rather than shredded: this
# helper disposes of what pack.sh and a recovery safety copy produce; it is not
# a general-purpose shredder and must not sweep sibling files it was not
# pointed at.
if [[ -f "$TARGET_ABS" ]]; then
  # *.pgc joins *.tgz (M1-583): the recovery convention's independent
  # safety-copy dump is disposal material in its own right — it was already
  # eligible as a directory member, and the header names it as handled.
  if [[ "$TARGET_ABS" != *.tgz && "$TARGET_ABS" != *.pgc ]]; then
    echo "FAIL: $TARGET_ABS is not a *.tgz bundle or a *.pgc dump — nothing removed." >&2
    exit 1
  fi
elif [[ -d "$TARGET_ABS" ]]; then
  shopt -s nullglob
  ELIGIBLE=no
  for member in "$TARGET_ABS"/*.tgz "$TARGET_ABS"/*.pgc "$TARGET_ABS"/db/*.pgc; do
    if [[ -f "$member" ]]; then
      ELIGIBLE=yes
      break
    fi
  done
  shopt -u nullglob
  if [[ -d "$TARGET_ABS/raw-config" ]]; then
    ELIGIBLE=yes
  fi
  # The mktemp name alone qualifies a remnant even before (or without) any
  # db/*.pgc landing. The operator still names the remnant ITSELF, never its
  # parent — the no-sibling-sweep posture is unchanged. Bash expansion, not
  # basename(1): the wiring test's restricted PATH ships no basename.
  if [[ "${TARGET_ABS##*/}" == .infochat-pack.* ]]; then
    ELIGIBLE=yes
  fi
  if [[ "$ELIGIBLE" != yes ]]; then
    echo "FAIL: $TARGET_ABS does not look like bundle/recovery material (no *.tgz or *.pgc at its top level, no db/*.pgc one level down, no raw-config/, not a .infochat-pack.* staging remnant) — nothing removed." >&2
    exit 1
  fi
else
  echo "FAIL: $TARGET_ABS is neither a regular file nor a directory — nothing removed." >&2
  exit 1
fi

# ── inventory + explicit consent ─────────────────────────────────────────
# The operator sees the resolved absolute target and what it holds BEFORE
# anything is irreversibly overwritten.
if [[ -f "$TARGET_ABS" ]]; then
  FILE_COUNT=1
else
  FILE_COUNT="$(find "$TARGET_ABS" -type f | wc -l)"
fi
read -r TOTAL_SIZE _ <<<"$(du -sh "$TARGET_ABS")"
echo "target: $TARGET_ABS"
echo "about to irreversibly shred: $FILE_COUNT file(s), $TOTAL_SIZE total"

if [[ "$ASSUME_YES" != yes ]]; then
  if [[ ! -t 0 ]]; then
    echo "FAIL: confirmation required — no interactive terminal; re-run with --yes for unattended use. Nothing removed." >&2
    exit 1
  fi
  read -r -p "Shred $TARGET_ABS? This cannot be undone. [y/N] " answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *)
      echo "aborted — nothing removed." >&2
      exit 1
      ;;
  esac
fi

# ── destroy: overwrite every file, then remove ───────────────────────────
# find -type f does not follow symlinks (physical -P walk), so a link inside
# the tree pointing outside it is dropped by the rm, never shredded through.
if [[ -f "$TARGET_ABS" ]]; then
  shred -uz "$TARGET_ABS"
else
  find "$TARGET_ABS" -type f -exec shred -uz {} +
  rm -rf "$TARGET_ABS"
fi
echo "shredded: $TARGET_ABS ($FILE_COUNT file(s), $TOTAL_SIZE)"
