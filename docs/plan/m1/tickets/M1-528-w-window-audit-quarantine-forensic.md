---
id: M1-528
title: "Implement -w window: /audit + forensic /quarantine list --all"
status: done
created: 2026-06-30
last_updated: 2026-06-30
clarity_check:
  date: 2026-06-30
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-06-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 448
      removed: 28
  - round: 2
    date: 2026-06-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 667
      removed: 28
redteam_findings:
  - date: 2026-06-30
    category: DOS
    severity: low
    promise: |
      §Threat model determinism boundary + boundary-validation posture: argument
      parsing at a system boundary (the command parser) must reject malformed input
      deterministically rather than throw; §DOS covers blocking the adapter event loop.
    gap: |
      The new -w parser validates shape but not magnitude. WINDOW_PATTERN's ([0-9]+)
      is unbounded; parseWindow guards Long.parseLong but NOT the subsequent
      Duration.ofHours/ofDays(n) / ofDays(n*7). A value that fits in a long but
      overflows Duration's seconds field (e.g. 999999999999999d) makes Duration.ofDays
      throw an uncaught ArithmeticException; downstream clock.instant().minus(window)
      can throw DateTimeException. The `w` unit's n*7 is an unchecked long multiply →
      silent overflow → negative Duration → future cutoff → silently-empty view.
    repro: |
      A (possibly compromised) bot admin sends `/audit -w 999999999999999d` or
      `/quarantine list --all -w 999999999999999d`. The value passes the regex and
      Long.parseLong, then Duration.ofDays throws ArithmeticException, never caught in
      the handler. A correct boundary validator would reject the over-range window with
      the same friendly usage error already returned for `-w abc`.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-06-30
    verdict: FINDINGS
    base: main
    head: m1/M1-528-w-window-audit-quarantine-forensic
    verdict_file: docs/plan/m1/redteam/M1-528-2026-06-30.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One low-severity input-sanitization finding on the ticket's own new -w parsers
      (unbounded magnitude → uncaught Duration overflow / silent n*7 overflow). Surfaced
      pre-commit while in-review; user chose to refine in-branch. Out-of-model item (bare
      /audit defaults to 24h) is the documented D53 trade-off, not a gap.
  - date: 2026-06-30
    verdict: CLEAN
    base: main
    head: m1/M1-528-w-window-audit-quarantine-forensic
    verdict_file: docs/plan/m1/redteam/M1-528-2026-06-30-recheck.md
    out_of_model_count: 2
    note: |
      Recheck after the magnitude-bound remediation (withinRange on both parseWindow
      methods). The round-1 low-severity finding is closed — over-range -w returns the
      usage error before any Duration overflow. CLEAN; only advisory out-of-model items.
escalations:
  - date: 2026-06-30
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      DOS / low / input-sanitization: the new -w parser validates shape but not
      magnitude. A value like `999999999999999d` passes the regex + Long.parseLong,
      then Duration.ofDays throws an uncaught ArithmeticException out of handle(); the
      `w` unit's n*7 silent overflow yields a negative Duration → future cutoff →
      silently-empty view. User chose refine: bound the magnitude in-branch.
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - docs/spec/decisions.md
  - ADMIN_GUIDE.md
revisions:
  - date: 2026-06-30
    change: >-
      files_scope += cs.properties (user-approved refine mid-implementation).
      The new bundle key error.quarantine.window_requires_all required by the
      acceptance must be mirrored in cs.properties to satisfy D43's bilateral
      keyset completeness (BundleLoaderTest.everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe);
      the original files_scope omitted the cs twin. Within files_budget (10th file).
  - date: 2026-06-30
    change: >-
      Round-1 rework (user-accepted in-branch redteam remediation, finding above):
      bound the -w magnitude in both parseWindow methods so an over-range value
      (e.g. `-w 999999999999999d`) returns the existing friendly usage error instead
      of throwing an uncaught Duration overflow / silently producing a future cutoff.
      Adds one acceptance item + one regression test per handler. No files_scope change
      — the fix lives in the two handlers and their tests already in scope.
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Any command other than `/audit` and `/quarantine list`. The `-w` idiom (D12) already works on `/summary` and `/saved`; do not touch those handlers, SummaryArgs, or SavedCommandHandler — reuse their parse pattern, don't modify them."
  - "Putting a time window on the default `/quarantine list` PENDING queue. The decision (below) is that the active review queue is reviewed WHOLE — a window (and especially a 24h default) would hide stale-but-unreviewed items, the exact 'old entries invisible' hazard the M1-081b redteam flagged for pagination. `-w` is valid ONLY with `--all`."
  - "Changing the quarantine review-status enum, the approve/reject stored procedures, or any quarantine schema. This ticket only adds a read-side WHERE filter on the forensic listing path."
  - "infochat-collector/** and any Flyway migration — this is a Provider read-path + docs change; no DB schema or collector change."
  - "Adding new audit-action verbs or changing audit_log_view. `/audit` already reads the redacted view; `-w` only adds a `created_at` range predicate to the existing query."
acceptance:
  - >-
    AuditCommandHandlerTest.audit_windowFilter passes — `/audit -w 24h`
    returns only rows whose `created_at` is within the window; rows older than
    the window are excluded. The window cutoff is computed from the injected
    `java.time.Clock` (pinned in the test via `Clock.fixed(...)`), NOT an inline
    `Instant.now()` or SQL `now()` — engineering-rules §9 (the cutoff gates which
    rows the admin sees, a decision on "now").
  - >-
    AuditCommandHandlerTest.audit_windowComposesWithActionFilter passes —
    `/audit --action ban -w 30d` filters by BOTH the action and the time window;
    the WHERE clause AND-combines the existing `--actor`/`--action` predicates
    with the new `created_at >= cutoff` predicate.
  - >-
    AuditCommandHandlerTest.audit_windowDefault passes — bare `/audit` (no `-w`)
    applies the design's documented default window of `24h`
    (docs/design/03-commands.md "`-w` defaults to `24h`"); the default is read
    from a single named constant/config, not duplicated.
  - >-
    AuditCommandHandlerTest.audit_malformedWindow_usageError passes — an
    unparseable `-w` value returns the usage/error reply (matching the existing
    `--page` malformed-value convention in AuditArgs.parse), not a silent
    fallback.
  - >-
    AuditCommandHandlerTest.audit_overRangeWindow_usageError and
    QuarantineCommandHandlerTest.list_overRangeWindow_usageError pass — an out-of-range
    `-w` magnitude (e.g. `-w 999999999999999d`, which fits in a long but overflows
    `Duration`) returns the same friendly usage error as a malformed `-w`, never an
    uncaught `ArithmeticException`/`DateTimeException` and never a silent future-cutoff
    empty view. The accepted ranges match the design Time-window table (1–168h / 1–30d /
    1–4w), mirroring SummaryArgs. (Round-1 redteam remediation.)
  - >-
    QuarantineCommandHandlerTest.list_allWithWindow_filtersForensic passes —
    `/quarantine list --all -w 7d` lists every status BUT bounded to the window;
    the cutoff uses the injected Clock. Rows older than 7d are excluded from the
    `--all` forensic view.
  - >-
    QuarantineCommandHandlerTest.list_windowWithoutAll_rejected passes —
    `/quarantine list -w 7d` WITHOUT `--all` returns a friendly boundary error
    (new bundle key) stating `-w` requires `--all`; NO window is ever applied to
    the default PENDING queue. (System-boundary arg validation — permitted.)
  - >-
    QuarantineCommandHandlerTest.list_defaultPending_noWindow passes — bare
    `/quarantine list` returns ALL PENDING rows regardless of age (the
    never-drop-unreviewed invariant); adding `-w` parsing must not regress this.
  - >-
    docs/spec/commands.md updated: the `/audit` entry keeps `[-w …]`; the
    `/quarantine list` entry is corrected so `-w` is documented as valid only on
    the `--all` forensic view (NOT on the default PENDING queue).
  - >-
    docs/design/03-commands.md updated: the `### /quarantine list` section
    documents window-on-`--all`-only with the never-drop rationale; the `### /audit`
    section's `-w` default is unchanged; the permission/flag matrix row stays
    consistent.
  - >-
    docs/spec/decisions.md gains a new decision entry (next free D-id) recording:
    `/audit` takes `-w` (window over audit history, default 24h); `/quarantine
    list` PENDING queue takes NO window and `-w` is forensic-only (`--all`);
    rationale = the review queue is actioned whole, a window would hide stale
    unreviewed items. This is the decision M1-081b never recorded.
  - >-
    ADMIN_GUIDE.md: `-w` is added to the `/audit` row and to `/quarantine list`
    (documented as the forensic `--all` window) — matching the now-true code.
    This is the guide line M1-509 deferred here.
  - "mvn verify is green from the repo root; every pre-existing test still passes."
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/commands.md §Admin (bot admin)"
decision_refs:
  - D12
---

# M1-528: Implement -w window — /audit + forensic /quarantine list --all

## Context

The spec (`docs/spec/commands.md` §Admin) and design
(`docs/design/03-commands.md`) both promise the `-w <duration>` time-window flag
on `/audit` and `/quarantine list`, anchored to **D12** ("every command that
takes a time window uses the same `-w` flag"). The implementing ticket
**M1-081b** (done 2026-05-26) silently omitted `-w` from its acceptance —
`AuditCommandHandler` and `QuarantineCommandHandler` parse only
`--actor`/`--action`/`--page` and `--all`/`--page` respectively, and `-w` was
never tested. No decision was ever recorded to drop it; it fell through the gap
between an under-specified ticket and a reviewer/clarity pass that checked the
diff against that ticket rather than against the full spec command signature.
M1-509 caught the guide-vs-spec gap but correctly deferred the fix here, because
documenting `-w` while the code ignores it would describe a no-op flag.

This ticket restores the contract — but **scoped to where a window is actually
meaningful**, with the missing decision finally written down.

## Acceptance

See the YAML `acceptance:` list. In prose: add `-w <duration>` parsing to
`/audit` (window over the audit history, default `24h`, cutoff from the injected
`Clock`, composes with `--actor`/`--action`); add `-w` to `/quarantine list`
**only on the `--all` forensic view**, returning a friendly error when `-w` is
given without `--all`, so the default PENDING queue is NEVER windowed. Record the
applicability decision in `decisions.md`, update `commands.md` + `03-commands.md`
to match, and add the `-w` line to `ADMIN_GUIDE.md`.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing exclusion: do not put a
window on the default `/quarantine list` PENDING queue. The active review queue
is reviewed whole; a window — especially the design's generic 24h default —
would hide stale-but-unreviewed items, reintroducing the "old entries invisible"
hazard the M1-081b redteam already flagged for pagination. `-w` on quarantine is
forensic-only (`--all`). Do not touch `/summary`/`/saved` (their `-w` already
works) or the quarantine schema / stored procedures (read-side filter only).

## Notes

- **Adjacent code (the `-w` pattern to match):** `SummaryArgs` and
  `SavedCommandHandler` already parse `-w <duration>` with the D12-accepted forms
  (see `docs/design/03-commands.md` §"Time window flag (`-w <duration>`)"). Reuse
  their duration-parse approach; a few duplicated lines beats a premature shared
  abstraction unless one already exists. Whatever the source, the **cutoff must
  read the injected `Clock`** (app-wide producer `ThrottledAdminNotifier.systemUtcClock()`,
  pinned in tests via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`),
  per engineering-rules §9 — the window gates which rows the admin sees.
- **`/audit` default-window caveat for the implementer/reviewer to weigh:** the
  design says `-w` defaults to `24h`. That changes bare `/audit` from "20 most
  recent across all time" to "last 24h". This matches `/summary`'s idiom and is
  the documented contract, so the acceptance pins the 24h default — but if the
  reviewer judges the behavior change surprising for a low-traffic deployment,
  that is an `escalate → spec-amend` conversation, not a silent deviation.
- **Why security_relevant:** the change touches the quarantine review queue and
  the audit-log read surface. The redteam lens here is specifically "does the new
  window ever hide a PENDING item from the default queue?" — the answer must stay
  no.
- **Lineage:** this is the code+spec restoration M1-509 (`out_of_scope`) deferred;
  it carries the `-w` guide line M1-509 intentionally left out. Root-cause ticket
  for the original omission: M1-081b.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-528-*.md
```
