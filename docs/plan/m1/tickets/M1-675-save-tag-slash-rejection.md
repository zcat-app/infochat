---
id: M1-675
title: "Reject slash-bearing personal tags at the /save boundary"
status: pending
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The post-title echo on the same reply line ({1} in
    REPLY_SAVED_LINE). Titles are feed-authored and reach the line only
    through the scope's own subscription world — an admin-tier opt-in the
    attacker does not control directly (unlike the personal tag, which any
    registered group member writes). The audit dispositioned the title
    surface as adjacent, one tier removed; tightening title handling is a
    separate decision, not this fix.
  - >-
    A DB-level CHECK constraint on personal_tags (V15). The app-side
    write-boundary rejection closes the finding; a defense-in-depth CHECK
    may be proposed as a follow-up but is not required here (and would
    make this ticket migration_touch).
  - >-
    ListSavesTool's read of personal_tags into the chat prompt. That path
    is covered by the LLM pipeline's own defenses (per-call random
    delimiters, output sanitizer) per the audit; the deterministic /saved
    reply channel is the unfiltered one this ticket closes.
acceptance:
  - >-
    A new test in SaveCommandHandlerTest proves
    `/save <uid> -t "/grant-admin <uuid>"` is REJECTED with a friendly
    error (a new error.save.tag_invalid bundle key, en + cs), storing
    nothing — the M1-659 acceptableOverride shape applied to the tag
    write boundary: each candidate tag is NFKC-normalized first, and any
    tag containing '/' after normalization rejects the whole /save (the
    absolute, non-boundary rule — M1-659's lesson that every Unicode
    partition has blank-rendering members on both sides).
  - >-
    The same test file proves the rejection is self-sufficient (does not
    rely on the router's upstream normalization): a tag carrying U+FF0F
    FULLWIDTH SOLIDUS is rejected, since NFKC folds it to a real '/'
    before the test.
  - >-
    Existing length/count caps keep their behavior: a slash-free tag
    within the caps still saves, an over-long or over-count tag set still
    fails with its current error. SaveCommandHandlerTest and
    SavedCommandHandlerTest stay green with no behavioral edit beyond the
    new rejection arm.
  - >-
    The new error key is declared as a BundleKeys constant AND ships in
    BOTH en.properties and cs.properties. The constant is what places the
    key under BundleLoaderTest's constant-completeness gate — the en/cs
    keyset-equality check alone does not cover it, since that check still
    passes when a referenced key is dropped from both bundles. A
    handler-local key literal (the shape used by
    QuarantineCommandHandler.RATE_LIMIT_KEY and
    PendingCommandHandler.REPLY_HEADER) bypasses that gate and must NOT
    be used here.
  - >-
    Every pre-existing handler-local bundle-key literal is migrated to a
    BundleKeys constant, per the §Census disposition table: the 6 sites
    in PendingCommandHandler, QuarantineCommandHandler and AddSourceArgs
    lose their local key declarations and reference BundleKeys instead.
    Key STRINGS are unchanged — this is a declaration move, not a rename,
    so en/cs bundles need no edit for these 6 and no user-visible text
    changes. test.fallback.probe is deliberately NOT migrated (see the
    table). After this ticket the census grep returns exactly one key,
    test.fallback.probe.
  - >-
    InboundReflectionGuardTest stays green across the migration. Its
    census fingerprint is `file | keyValue | argIndex | argExpr` and it
    resolves both `BundleKeys.NAME` references and handler-local
    `String NAME = "error.…"` declarations to the same key VALUE, so
    moving a declaration must not shift any fingerprint. If a baseline
    entry in inbound-reflection-error-baseline.txt does shift, that is a
    real signal to re-record, not a line to edit away.
  - mvn -pl infochat-provider verify is green
  - >-
    docs/spec/commands.md §Content records that personal tags may not
    contain a slash (D12 makes '/' the only command sigil, so a
    slash-free tag cannot carry a command token into the group-visible
    /saved reply), and docs/spec/security.md §LLM output sanitizer names
    the /save personal-tag echo as a CLOSED instance of the
    deterministic-reply reflection class alongside M1-656/M1-659.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D12
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-675: Reject slash-bearing personal tags at the /save boundary

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
PROV-1) verified that `/save -t` personal tags are stored with only
length/count caps (`SaveCommandHandler.java:188-199`, default 64
chars/tag, 20 tags; `parseTagList` at `:388-397` is trim-only, no charset
validation, no DB CHECK on `personal_tags`) and echoed verbatim into the
group-visible `/saved` reply (`SavedCommandHandler.java:244-249` →
`REPLY_SAVED_LINE`, `bundles/en.properties:364`). A registered group
member runs `/save <uid> -t "/grant-admin <own-ACI>"` (49 chars with a
Signal ACI — under the cap; `/ban <ACI>` is 45), then `/saved` in an
approved group, and the bot broadcasts a syntactically valid privileged
command mid-line to every member — including any bot admin who
copy-pastes it (full compromise, or a targeted ban). This is precisely
the deterministic-reply social-engineering class the repo remediated
twice before: M1-656 (no reflecting inbound text) and M1-659 (slash-
absolute rejection, because "a name containing no slash cannot carry a
command token"). The personal tag is the same surface with the weakest
attacker tier yet — no admin-tier or source-add step needed — and escaped
constraint because the write-side caps were designed for size, not
content shape. The deterministic-reply channel is the one
security.md §LLM output sanitizer leaves unfiltered by design, so no
output-side filter catches it.

## Acceptance

See the frontmatter. Slash-bearing tags (ASCII or compatibility-folded)
are rejected at the write boundary with a friendly, localized error;
slash-free tags and the size caps behave exactly as before; the spec
records the closure.

## Out-of-scope

The post-title echo on the same line (admin-tier-gated; separate
decision), a DB CHECK constraint (follow-up candidate), and the
ListSavesTool prompt path (already defended by the LLM pipeline). See
the frontmatter.

## Census

**The class: shipped bundle keys with no `BundleKeys` constant.**

A shipped bundle key that is declared as a handler-local `private static
final String` (or a bare literal) is invisible to
`BundleLoaderTest.everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle`,
the gate that test's own javadoc calls "the load-bearing CI guard". Such
a key is covered only by `everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe`
(en/cs set-equality), which still passes when a still-referenced key is
deleted from *both* bundles — turning a build failure into a runtime
`MissingResourceException` / D43 startup error. This ticket closes the
class.

**Re-runnable enumeration** (run from the repo root; prints every en key
lacking a constant):

```
python3 - <<'PY'
import re, pathlib
en = pathlib.Path('infochat-provider/src/main/resources/bundles/en.properties')
bk = pathlib.Path('infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java')
keys = [l.split('=', 1)[0].strip()
        for l in en.read_text(encoding='utf-8').splitlines()
        if l.strip() and not l.strip().startswith('#') and '=' in l]
consts = set(re.findall(r'public static final String\s+\w+\s*=\s*"([^"]+)"',
                        bk.read_text(encoding='utf-8')))
print('\n'.join(k for k in keys if k not in consts))
PY
```

Baseline at 2026-07-23 (`b071ba15`): 425 en keys, 418 constants, **7
without**. Disposition:

| key | declaration site | disposition |
|---|---|---|
| `reply.pending.header` | `PendingCommandHandler.java:51` | migrate → `BundleKeys.REPLY_PENDING_HEADER` |
| `reply.pending.line` | `PendingCommandHandler.java:52` | migrate → `BundleKeys.REPLY_PENDING_LINE` |
| `reply.pending.empty` | `PendingCommandHandler.java:53` | migrate → `BundleKeys.REPLY_PENDING_EMPTY` |
| `error.quarantine.rate_limit` | `QuarantineCommandHandler.java:95` | migrate → `BundleKeys.ERROR_QUARANTINE_RATE_LIMIT` |
| `error.quarantine.window_requires_all` | `QuarantineCommandHandler.java:99` | migrate → `BundleKeys.ERROR_QUARANTINE_WINDOW_REQUIRES_ALL` |
| `error.add_source.userinfo_rejected` | `AddSourceArgs.java:166` (bare literal) | migrate → `BundleKeys.ERROR_ADD_SOURCE_USERINFO_REJECTED` |
| `test.fallback.probe` | `BundleLoaderTest.java:58` (test-only) | **KEEP AS-IS — do NOT migrate** |
| `error.save.tag_invalid` | *new, this ticket* | add as `BundleKeys.ERROR_SAVE_TAG_INVALID` |

`test.fallback.probe` is load-bearing precisely *because* it has no
constant: it ships in `en.properties` only, and `BundleLoaderTest`'s
javadoc (`:42-48`) states the constant-driven iteration must never
inspect it, which is what lets the test exercise the 2-arg accessor's
en-fallback path without breaking en/cs equality. Giving it a constant
would fail the build. Its exclusion is a design invariant, not an
oversight — the one key this class deliberately does not close.

Post-condition: the enumeration above returns exactly `test.fallback.probe`.

## Notes

- The 6 migrations are declaration moves, NOT renames: every key string
  stays byte-identical, so `en.properties` / `cs.properties` are not
  edited for them and no reply text changes. Only the new
  `error.save.tag_invalid` adds bundle entries.
- Follow the M1-659 `acceptableOverride` shape literally: NFKC-fold each
  candidate tag, then reject the whole `/save` when any tag contains `/`
  — absolute, not boundary-sensitive. Apply the
  `IngestTextNormalizer.stripMetadataField` control-strip for consistency
  with the display-name fix, per the audit's remediation note.
- Group scope is intended, tested behavior
  (`SaveCommandHandlerTest.save_succeedsInGroupScope`,
  `SavedCommandHandlerTest.saved_succeedsInGroupScope`); the orphaned
  `error.*.group_not_in_v1` bundle keys confirm it. Do not "fix" this by
  making /saved DM-only.
- Finding detail, falsification history, and the SimpleX-vs-Signal
  payload-size analysis: the audit report (`kimi-audit.md` under
  `.scratch/`) §PROV-1 (module 6).
