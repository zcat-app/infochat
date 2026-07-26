---
id: M1-699
title: "summary_anchor render_form column + typed /retry dispatch"
status: pending
created: 2026-07-26
last_updated: 2026-07-26
blocked_by: []
files_budget: 9
files_scope:
  - infochat-core/src/main/resources/db/migration/V65__summary_anchor_render_form.sql
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/SummaryAnchorRenderFormBackfillIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/SummaryAnchorRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/design/02-schema.md
  - docs/spec/decisions.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    Any change to /summary's RENDER forms. The parser still accepts --full
    with its CURRENT meaning (flat per-cluster). No --short, no --flat, no
    --full reclaim, no cap-skip. Those are M1-700, which is blocked_by this
    ticket. This ticket only moves the dispatch AXIS from command_name
    string-matching to a typed column; it does not add or rename any flag.
  - >-
    The periodic digest path (DigestWorker, DigestDelivery, DigestRenderer's
    digest entry points). render_form applies to PERSONAL anchors only;
    digest anchors (command_kind='digest', "T2-F territory" per
    SummaryAnchorRepository.java:22) are untouched.
  - >-
    The command_name column — kept verbatim, NOT normalized. render_form is
    the typed dispatch axis; command_name remains the human-readable/audit
    string. The two are intentionally separate concerns (see Notes).
  - >-
    ClusterBlockRenderer, DigestRenderer.renderSummarySections behavior,
    CategoryRollupGenerator, bundle keys. None of the render machinery
    moves in this ticket.
  - >-
    arg_hash (SHA-256 of rawText for change detection). Unchanged; render_form
    is a read-side dispatch column, not a change-detection hash (see Notes).
acceptance:
  - >-
    Flyway migration V65__summary_anchor_render_form.sql applies cleanly on a
    fresh DB and on a DB with pre-existing summary_anchor rows: adds
    summary_anchor.render_form as TEXT (nullable during backfill, then NOT
    NULL with DEFAULT 'bare'), backfills from command_name
    (command_name LIKE '%--full%' → 'flat', else → 'bare'), and adds a CHECK
    constraint render_form IN ('bare','short','full','flat'). Naming follows
    summary_anchor_scope_kind_chk (V37).
  - >-
    SummaryAnchorRenderFormBackfillIT passes: seeds pre-V65 anchors with
    command_name ∈ {'summary', '/summary', 'summary --full', '/summary --full',
    'summary ai --full'}, runs the migration, and asserts render_form is
    'flat' for the --full rows and 'bare' for the rest. (Pattern:
    SourceOriginBackfillIT.)
  - >-
    SummaryAnchorRepository writes render_form on UPSERT and reads it on
    SELECT; AnchorRow carries the new field. SummaryCommandHandler writes
    render_form='flat' when args.full() is true, 'bare' otherwise (the
    CURRENT 2-form code — short/full values are not yet produced and are
    M1-700's concern).
  - >-
    RetryCommandHandler reads render_form from the anchor and dispatches on
    it. The isFullFormAnchor(commandName) helper and its
    hasFlag(commandName, "--full") call site are REMOVED. A 'flat' anchor
    replays the flat ClusterBlockRenderer form; a 'bare' anchor replays the
    categorized-capped form. The dispatch is structured as a switch on
    render_form so M1-700 can add 'short' and 'full' branches without
    restructuring.
  - >-
    RetryCommandHandlerTest.replaysFromRenderFormColumnNotCommandName passes:
    the /retry replay is BYTE-IDENTICAL to the pre-refactor output for both
    a flat anchor and a bare anchor. The test seeds render_form on the anchor
    row directly (not via command_name string-matching).
  - >-
    RetryCommandHandlerTest.oldAnchorBackfilledToFlatForm passes: a pre-V65
    anchor whose command_name contains '--full' is backfilled to
    render_form='flat' by V65, and /retry replays the flat form. A pre-V65
    anchor with command_name 'summary' or '/summary' backfills to 'bare'
    and replays categorized-capped.
  - >-
    docs/design/02-schema.md §2.6.5 summary_anchor is amended: the DDL block
    carries render_form, and a "render_form decision" subsection records the
    typed-dispatch-axis rationale (V37 precedent, backfill rule, why a column
    over normalized command_name). docs/spec/decisions.md adds D70 recording
    the same.
  - >-
    mvn -pl infochat-core verify is green (migration + backfill IT);
    mvn -pl infochat-provider verify is green.
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/SummaryAnchorRenderFormBackfillIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D70
---

# M1-699: summary_anchor render_form column + typed /retry dispatch

## Context

`/retry` replays the last anchored `/summary` in the render form that
`/summary` produced — M1-696's contract. Today it discovers that form by
string-matching the anchor's `command_name`:
`RetryCommandHandler.isFullFormAnchor` does `hasFlag(commandName, "--full")`
(`RetryCommandHandler.java:562-563`). That code already smells —
`RetryCommandHandler.java:557-560` notes "anchor values were never
normalized, so pre-existing rows read `/summary` with a leading slash", and
the boolean check papers over it by treating anything-not-`--full` as
categorized.

M1-700 (blocked_by this ticket) widens `/summary` from two render forms to
four (`--short`, bare, `--full` reclaimed, `--flat` renamed). Extending the
`command_name` string-matching to four forms inherits the existing
fragility at a wider surface. This ticket moves the dispatch axis to a
typed column FIRST, as a pure refactor with zero user-visible behavior
change: add `render_form` (TEXT+CHECK, V37 precedent), backfill it from
`command_name`, write it from the current 2-form code, and switch `/retry`
to read it. M1-700 then adds the `short`/`full` branches to a dispatch
that is already structured for them.

Cites `docs/spec/commands.md §Conversation control` (the `/retry` contract
this ticket preserves by moving the dispatch axis internally) and records
D70 (the column decision).

## Acceptance

The behavioral contract is the YAML `acceptance:` list. "Done" means:

1. **V65 migration** — adds `render_form` TEXT NOT NULL DEFAULT 'bare'
   with CHECK (`bare`,`short`,`full`,`flat`), backfilled from
   `command_name`.
2. **Backfill verified** — `SummaryAnchorRenderFormBackfillIT` seeds old
   `command_name` values and asserts the backfill mapping.
3. **Anchor read/write** — `SummaryAnchorRepository` carries `render_form`;
   `SummaryCommandHandler` writes `flat` for `--full`, `bare` otherwise.
4. **`/retry` reads the column** — dispatch switches on `render_form`;
   `isFullFormAnchor`/`hasFlag(commandName,"--full")` removed; structured
   for M1-700 to add `short`/`full` branches.
5. **Byte-identical replay** — `/retry` output unchanged for both flat and
   bare anchors (pure refactor).
6. **Old anchors backfill correctly** — `--full` → `flat`; else → `bare`;
   replay matches the original form.
7. **Docs** — `02-schema.md §2.6.5` amended; D70 recorded.
8. **Green** — both modules.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing boundary: **this
ticket adds NO render form and renames NO flag.** `--full` still means
flat per-cluster, exactly as today. Only the dispatch axis moves. M1-700
does the rename and the new forms; it is blocked_by this ticket so the
column + typed dispatch land first.

### Authorized test retargeting (test-integrity §8 disclosure)

`RetryCommandHandlerTest` cases that currently assert `isFullFormAnchor` /
`hasFlag(commandName, "--full")` replay are RETARGETED to seed
`render_form` on the anchor row directly. The replayed bytes are
unchanged (pure refactor); only the seed mechanism moves from
string-matching to the typed column. `SummaryCommandHandlerTest` cases
that assert the anchor is written gain an additive `render_form`
assertion (`flat` for `--full`, `bare` otherwise) — the invocation and
the rest of the assertion are unchanged. Disclosed per engineering-rules
§8.

## Notes

**Why a column, not normalized `command_name`.** `command_name` carries
the whole command (verb + tag + flags) as a free-text string, and
`/retry` parses it back into a dispatch decision. Extending that to four
forms (M1-700) inherits the leading-slash / never-normalized fragility
the existing code already complains about. `render_form` is the typed
dispatch axis; `command_name` stays the human-readable/audit string. V37
set the precedent on this exact table: it added `scope_kind` (TEXT+CHECK)
even though `scope_id = user_id` already derived it, with the note
"anchors are ephemeral replay state... a misclassified pre-existing
colliding row costs at most one stale /retry replay"
(`V37__summary_anchor_scope_kind.sql:20`). The same backfill shape and
imperfection tolerance apply.

**Why TEXT+CHECK, not a Postgres enum type.** V37 used TEXT+CHECK. A
Postgres enum needs `ALTER TYPE ADD VALUE` (a migration) for every
future form; TEXT+CHECK just needs an altered CHECK. Match the
precedent.

**Backfill determinism.** Pre-rename `command_name` ∈ {`"summary"`,
`"/summary"`, `"summary --full"`, `"/summary --full"`}, optionally with
a tag (`"summary ai --full"`). Rule: `command_name LIKE '%--full%'` ⇒
`flat`, else ⇒ `bare`. Unambiguous — `--full` is the only form flag
today, always meaning flat. The leading-slash variant maps to `bare`.
No NULL-read window: Flyway runs at boot before the new code serves, so
old rows are backfilled before any `/retry` reads them. The DEFAULT
`'bare'` is a safety net defaulting to the safest replay shape.

**`arg_hash` is not redundant with `render_form`.** `computeArgHash`
(`SummaryCommandHandler:665`) is `SHA-256(rawText.strip())` — a one-way
hash for change detection ("did the args change?"). `render_form` is
derived from `rawText` (the flag is in the text), so `arg_hash` already
changes when the form changes. `render_form` is a separate read-side
column for dispatch; a hash is not recoverable, so the two are not
redundant.

**Forward reference.** M1-700 (the 4-mode render forms) is blocked_by
this ticket and consumes the `short`/`full` values the CHECK already
permits. The CHECK includes all four values now so M1-700 adds no
migration — only code that writes and dispatches on `short`/`full`.
