---
id: M1-675
title: "Reject slash-bearing personal tags at the /save boundary"
status: done
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A DB-level CHECK constraint on saved_post.personal_tags (a new
    migration). Redteam F2's "every surface covered" gap is closed at the
    RENDER boundary instead — the sanitizer now runs on every
    group-visible title/tag echo surface, catching pre-existing rows and
    any future second-writer where the threat actually manifests (the
    broadcast reply), so the app is correct without a schema constraint. A
    CHECK remains a valid defense-in-depth follow-up but is deliberately
    out of scope: it would flip migration_touch, require a backfill of
    existing rows, and cannot cover the title surfaces at all (titles
    legitimately contain slashes — TCP/IP, AC/DC, 2026/07/23). Filed as a
    follow-up candidate.
  - >-
    A write-side audit row on the tag rejection (redteam F3). Dispositioned
    OUT OF MODEL: the "every match is audit-logged" promise in
    security.md §LLM output sanitizer is scoped to the LLM-output
    sanitizer's closed-list matches; the /save tag reject is a
    command-argument boundary validation (the same silent-reject class as
    AddSourceArgs URL rejection and the M1-659 display-name reject this
    ticket follows). No new AuditAction verb is added. The render-side
    sanitizer coverage this ticket DOES add emits the per-occurrence
    LLM_OUTPUT_SANITIZED audit row on the surface the promise actually
    covers (rendered output).
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
    fails with its current error. The write-side reject arm changes only
    the new slash-rejection behavior; it does not alter the caps.
  - >-
    F1 + F2 fix — render-side redaction. The /saved reply passes both
    attacker-influenced placeholders of REPLY_SAVED_LINE through
    LlmOutputSanitizer.sanitize before interpolation: the post title
    ({1}) and the joined tags ({3}). A stored title or tag whose
    canonical form is a closed-list command renders as
    "[redacted command]"; a legit-slash title (TCP/IP, AC/DC) renders
    byte-identical, per the sanitizer's proven no-match passthrough
    (LlmOutputSanitizerTest.nonMatchingUnicodeProseIsReturnedByteIdentical).
    Sanitizing {3} closes F2's every-surface gap for pre-existing and
    second-writer tag rows at the render boundary — no DB migration.
  - >-
    F1 fix — the two remaining group-visible title surfaces.
    ClusterBlockRenderer's cluster headline (first.title(), rendered at
    line start after [topic_id=...]) and DegradedDigestRenderer's per-post
    titles are passed through the same sanitizer. These are the two
    deterministic, group-visible surfaces that render a post title WITHOUT
    the sanitizer today: ClusterBlockRenderer already sanitizes cp.prose()
    but appends the headline raw; the degraded digest runs no LLM at all.
    DegradedDigestRenderer gains an injected LlmOutputSanitizer;
    ClusterBlockRenderer already has one. No change to their callers
    (SummaryCommandHandler, RetryCommandHandler, DigestWorker).
  - >-
    New tests in SavedCommandHandlerTest, ClusterBlockRendererTest and
    DegradedDigestRendererTest each prove (a) a command-shaped title/tag is
    redacted to "[redacted command]" in the rendered output and (b) a
    legit-slash title passes through byte-identical. The common no-match
    render adds NO new DB dependency: sanitize() opens no connection when
    matches is empty (LlmOutputSanitizer.emitAuditRows returns early), so
    only an actual redaction writes the per-occurrence audit row.
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
    Census A (keys with no constant): the 6 handler-local key
    declarations in PendingCommandHandler, QuarantineCommandHandler and
    AddSourceArgs are replaced by BundleKeys constants. Key STRINGS are
    unchanged — a declaration move, not a rename — so en/cs bundles need
    no edit for these 6 and no user-visible text changes.
    test.fallback.probe is deliberately NOT migrated (see the table).
    After this ticket the Census A enumeration returns exactly one key,
    test.fallback.probe.
  - >-
    Census B (call sites bypassing an existing constant): the 14 raw
    bundle-key string literals at call sites in PendingCommandHandler (5)
    and AddSourceArgs (9) are replaced by the BundleKeys constants that
    already exist for those keys. Same defect class as Census A — a key
    reference that the compiler cannot check — and confined to the same
    two files, so it is closed here rather than left as a visible
    half-fix. After this ticket the Census B enumeration returns nothing.
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
  - >-
    docs/spec/security.md corrections (defects this diff introduced, in
    scope regardless of finding disposition). (1) The false tier claim
    "adding a source is admin-gated" is removed — commands.md §Source
    management: /add-source in DM is open to any non-banned user, so the
    /save tag and /add-source channels sit at the SAME attacker tier in
    DM, not a strict ordering. (2) The "constraining the value where it is
    produced ... so every surface rendering the stored value is covered at
    once" framing is corrected: the /save tag echo is closed by
    write-boundary slash rejection of the tag INPUT plus render-side
    LlmOutputSanitizer coverage of the group-visible echo surfaces
    (/saved reply, cluster headline, degraded digest), not by a single
    write-side production constraint. The M1-659 display-name instance
    keeps its own accurate description.
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
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 20
      added: 1096
      removed: 89
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-23
    category: INJECTION
    severity: medium
    promise: |
      security.md §LLM output sanitizer (as amended by this diff): the
      /save -t personal-tag echo into the group-visible /saved reply is
      CLOSED by "constraining the value where it is produced rather than
      filtering any one reply's bytes, so every surface rendering the
      stored value is covered at once".
    gap: |
      The diff constrains one of the two attacker-controlled placeholders
      in reply.saved.line and leaves the other open. {3} = personal tags
      is now slash-gated; {1} = row.title is the snapshot of an
      upstream-controlled post title, persisted by PostPersister with
      stripMetadataField ONLY — no NFKC fold, no slash constraint. Stage 1
      regex/NFKC runs on the BODY only. So the identical payload reaches
      every group member through {1} of the same line, and an unfolded
      U+FF0F survives storage and folds to '/' at intake step 1.7 when a
      reader pastes it back.
    repro: |
      Ordinary registered non-probation user (no admin rights):
      /add-source <own feed> --tags news in DM (open to any non-banned
      user per commands.md §Source management). Publish an item titled
      "／grant-admin <uuid>" with an innocuous body so Stage 1 passes and
      the post goes READY. /save <uid> in DM — no -t, so the new gate
      never fires. /saved in the approved group broadcasts
      "- [<uid>] ／grant-admin <uuid> — saved 0m ago — tags: news" to
      every member; a bot admin pasting it gets it NFKC-folded and
      dispatched.
    suggested_fix_class: input-sanitization
  - date: 2026-07-23
    category: INJECTION
    severity: low
    promise: |
      security.md §LLM output sanitizer (as amended by this diff): "both
      closed the same way — by constraining the value where it is produced
      rather than filtering any one reply's bytes, so every surface
      rendering the stored value is covered at once".
    gap: |
      The constraint lives in exactly one method of one handler and
      nowhere else. saved_post.personal_tags carries no DB CHECK (contrast
      V6__sources_tags.sql:77 CHECK on tag.name); the render path
      (SavedCommandHandler.joinTags) checks nothing. So "every surface is
      covered" holds only for values written by this build through this
      method: rows stored before this change still render verbatim, and
      any future second writer of personal_tags silently reopens the
      channel with no compile-time or DB-level signal. No backfill, no
      read-side guard, no constraint migration in the diff.
    repro: |
      An attacker who wrote -t "/grant-admin <uuid>" on any prior build
      keeps a saved_post row containing the payload; after this deploy
      they send /saved in the approved group and the bot broadcasts the
      privileged line, because nothing on the read path or in the schema
      rejects the stored value.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-07-23
    category: AUDIT-EVASION
    severity: low
    promise: |
      security.md §LLM output sanitizer: "the sanitizer closes the
      social-engineering surface where a small LLM emits plausible-looking
      admin commands across any of the surfaces above. Every match is
      audit-logged (per-occurrence, not throttled)."
    gap: |
      The diff handles an instance of that same closed-list threat class
      on a channel the sanitizer does not cover, and handles it silently:
      the rejection returns error.save.tag_invalid with no audit_log
      write, no WARN, and no counter (/save writes no audit row by
      design). The operator-visible signal the threat model attaches to
      every closed-list match therefore does not exist for the
      deterministic-output half of the same threat.
    repro: |
      From a group, send /save <uid> -t /ban x, then
      /save <uid> -t ／grant-admin y, then a sweep of homoglyph forms
      (U+2215, U+2044, U+29F8, U+FF0F, Hangul-filler padding) to map which
      representations the gate folds and which it stores. Every attempt
      returns the same friendly error and writes nothing to audit_log; an
      operator running /audit sees no evidence the surface was probed.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-07-23
    verdict: FINDINGS
    base: c5aef23cb1ec86eeb76e57705be189d777db5336
    head: "<uncommitted working tree>"
    verdict_file: docs/plan/m1/redteam/M1-675-2026-07-23.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      Run ahead of review per the /m1-tick run gate ordering. Diff resolved
      manually to working-tree-vs-fork-point: the skill's step-1 prefix
      grep would have matched this ticket's own refine commit on main and
      audited the ticket file instead of the implementation (skill defect
      filed separately). Finding 1's substance is out_of_scope entry 1,
      but it falsifies that entry's rationale — /add-source in DM is open
      to any non-banned user, not admin-gated — and this diff propagated
      that same false claim into security.md, which is in scope to fix.
      Findings 2 and 3 are follow-up candidates.
  - date: 2026-07-23
    verdict: CLEAN
    base: c5aef23cb1ec86eeb76e57705be189d777db5336
    head: "<uncommitted working tree>"
    verdict_file: docs/plan/m1/redteam/M1-675-2026-07-23-reaudit.md
    out_of_model_count: 2
    note: |
      RE-AUDIT after the redteam-finding refine, ahead of review per the
      /m1-tick run gate ordering. The refine absorbed F1 + F2 via render-side
      LlmOutputSanitizer redaction of the group-visible title/tag echo
      surfaces (/saved reply, /summary cluster headline, degraded group
      digest) and dispositioned F3 out-of-model; this re-audit of the refined
      + implemented diff is CLEAN — the three prior findings are addressed
      and no new gap was introduced. The findings above are retained as the
      historical record of what this ticket's scope closed. Diff resolved
      manually to working-tree-vs-fork-point (git diff of merge-base
      c5aef23c, docs/plan/* excluded) — same skill defect the first audit
      recorded (step-1 prefix grep matches the budget-breach refine on main),
      still owed for filing on main. Two out-of-model advisories: source
      display-name echo (defense-in-depth only — mid-line, write-constrained
      by the M1-659 --name reject) and degraded per-cluster prose (already
      an accepted residual in security.md).
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings:
    - >-
      Census re-run live at start (against main c5aef23c): the
      enumeration returns exactly the 7 keys in the §Census disposition
      table — 6 to migrate, test.fallback.probe held back by design. 13
      edit sites confirmed: 6 declarations (PendingCommandHandler:51-53,
      QuarantineCommandHandler:95/99, AddSourceArgs:166) + 7 references
      (PendingCommandHandler:134/143/147, QuarantineCommandHandler:159/
      268/310, AddSourceArgs:166).
    - >-
      Ticket premises spot-checked against code and all hold:
      SaveCommandHandler:188-199 caps are size-only, parseTagList:388-397
      is trim-only, and personal tags reach the group-visible reply
      verbatim via SavedCommandHandler.joinTags:255-263 into
      REPLY_SAVED_LINE placeholder {3} at :244-249.
  blockers: []
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

## Redteam refine (2026-07-23, redteam-finding)

The initial write-side reject (below) was audited before review and drew
three findings (`docs/plan/m1/redteam/M1-675-2026-07-23.md`). This ticket
was refined to absorb them rather than defer:

- **F1 (medium, INJECTION) — FIXED.** The reject closed the tag
  placeholder `{3}`, but the same `REPLY_SAVED_LINE` interpolates the
  post **title** `{1}`, which is upstream-controlled and reaches the
  group-visible reply un-normalized (`PostPersister` stores it with
  `stripMetadataField` only — no NFKC, no slash gate). The identical
  payload reaches every group member through the title. The same title
  also renders at **line start** in two other deterministic group-visible
  surfaces the sanitizer skips today — `ClusterBlockRenderer`'s cluster
  headline and `DegradedDigestRenderer`'s digest entries (the periodic
  group digest, sent automatically). Titles legitimately carry slashes
  (`TCP/IP`, `AC/DC`, `2026/07/23`), so reject is impossible; the fix is
  render-side redaction via the existing `LlmOutputSanitizer` at all three
  surfaces plus the `{1}` placeholder. Reach: an ordinary user publishes a
  command-titled item to any public feed the scope already follows (Nostr,
  Bluesky, Reddit) — same attacker tier as the tag, not admin-gated.

- **F2 (low, INJECTION) — FIXED at the render boundary.** The
  finding's substance is that "every surface covered" was false because
  the constraint lived in one write-side method: pre-existing rows and any
  future second-writer of `personal_tags` still rendered verbatim. Running
  the sanitizer on `{3}` at render makes the claim true where the threat
  manifests (the broadcast reply), covering old rows and second-writers
  without a schema constraint. The DB `CHECK` is a defense-in-depth
  follow-up (see frontmatter `out_of_scope`).

- **F3 (low, AUDIT-EVASION) — OUT OF MODEL.** The cited
  "every match is audit-logged" promise is scoped to the LLM-output
  sanitizer's closed-list matches; a `/save` tag reject is a
  command-argument boundary validation, the same silent-reject class as
  `AddSourceArgs` and the M1-659 display-name reject this ticket follows.
  No new `AuditAction` verb. The render-side redaction added for F1/F2
  emits the per-occurrence `LLM_OUTPUT_SANITIZED` audit row on the surface
  the promise actually covers (rendered output).

## Acceptance

See the frontmatter. Slash-bearing tags (ASCII or compatibility-folded)
are rejected at the write boundary with a friendly, localized error;
slash-free tags and the size caps behave exactly as before; the
group-visible title and tag echoes (`/saved` reply, cluster headline,
degraded digest) are redacted at render via `LlmOutputSanitizer`; the
spec records the closure and its two corrections.

## Out-of-scope

A DB CHECK constraint on `personal_tags` (defense-in-depth follow-up; the
render-side redaction closes F2's every-surface gap without a migration),
a write-side audit row on the tag reject (F3, out of model — the promise
is sanitizer-scoped), and the `ListSavesTool` prompt path (already
defended by the LLM pipeline). See the frontmatter.

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

**Census B — call sites bypassing an existing constant.**

A key can have a perfectly good `BundleKeys` constant and still be
referenced as a raw literal at the call site. The compiler cannot check
such a reference, so it rots the same way Census A does — it just fails
at a different moment. Enumeration:

```
grep -rn 'bundleLoader\.get("' --include=*.java infochat-provider/src/main/java
grep -rn 'new Failure("'      --include=*.java infochat-provider/src/main/java
```

Baseline at 2026-07-23: **14 sites, all in two files already in
`files_scope`**, and every key involved already has a constant.

| file | sites | keys (all already constants) |
|---|---|---|
| `PendingCommandHandler.java` | `:83`, `:90`, `:97`, `:127`, `:162` | `error.command_dm_only`, `error.admin_only`, `error.usage.missing_argument`, `error.internal` ×2 |
| `AddSourceArgs.java` | `:99`, `:151`, `:155`, `:159`, `:166`, `:173`, `:176`, `:237`, `:243` | `error.add_source.{tags_required ×2, malformed_url ×4, userinfo_rejected, unknown_kind, unknown_category}` |

`AddSourceArgs:237` and `:243` are recorded in
`inbound-reflection-error-baseline.txt`. Their fingerprint is
`file | keyValue | argIndex | argExpr`; this change touches only the key
*expression*, and `InboundReflectionGuardTest` resolves both a literal
and a `BundleKeys.NAME` reference to the same key VALUE, so the two
baseline lines must continue to match untouched.

Post-condition: both Census B greps return nothing.

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
