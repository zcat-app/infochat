---
id: M1-786
title: "Remediate post and saved_post bodies stored before the plain-text fix"
status: pending
created: 2026-08-06
last_updated: 2026-08-07
flow: tick
reproduction: Stage1BodyRemediationIT.storedBodyWithMarkupAndEntitiesBecomesPlainText
              — to-be-written: the seed rows need M1-784's fixed pipeline, so
              this test cannot exist until that lands; `start` writes it and
              runs it RED first. It seeds a post row and a saved_post
              row whose body is the pre-fix stored form
              (<p>Hello <a href="https://x.test">link</a></p> and
              We&#39;re working on it!!) and asserting both read back as
              "Hello link" and "We're working on it!!" after the remediation
              job runs. RED before this ticket because no code rewrites an
              already-stored body: post.body has exactly three writers —
              PostPersister.java:188 (INSERT), Stage1Pipeline.java:427 (UPDATE,
              gated on the stage1_done cursor) and approve_quarantine's
              placeholder replace
              (V69__approve_quarantine_verdict_owed_guard.sql:150).
              Corroborating live measurement:
              .scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md:94-95 (142/353 = 40 %
              of a fresh 3-hour corpus carry &#NN; entities).
analysis_ref: docs/plan/m1/tick-analysis/ingest-corrupts-post-body-text.md
blocked_by: [M1-784, M1-787, M1-788]
decomposed_from: M1-776
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/
  - infochat-core/src/main/resources/db/migration/
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - post.body_en, saved_post.body_en and post.body_summary — LLM-derived from
    the corrupted body; re-deriving them costs a generative call per row and is
    a separate decision (disposed in §Census)
  - quarantine.original_html — the verbatim pre-redaction span, deliberately
    stored as captured; rewriting it would falsify the audit record
  - post.title and saved_post.title — never HTML-stripped by Stage 1 on any
    path, so they are not corrupted by this defect
  - re-running Stage 2, changing post.status, ready_at, or any per-stage cursor
    flag
  - re-implementing the conversion in SQL or as a regex — the job calls the
    same Stage 1 code path M1-784 ships
  - infochat-provider/**
acceptance:
  - Stage1BodyRemediationIT.storedBodyWithMarkupAndEntitiesBecomesPlainText passes
    — the reproduction; a pre-fix post.body and a pre-fix saved_post.body both
    read back as plain text
  - Stage1BodyRemediationIT.remediationOutputMatchesTheLivePipelineForTheSameInput passes
    (P11) — asserts the job's output for a given input equals what
    Stage1Pipeline produces for it, so no second decoder can drift
  - Stage1BodyRemediationIT.aRemediatedRowIsNeverConvertedTwice passes (P14) —
    failure mode: a row whose remediated body legitimately contains a literal
    <b> must be untouched by a second run; asserts the marker column gates it
  - Stage1BodyRemediationIT.remediationNeverDamagesAQuarantinePlaceholder passes
    (P7) — failure mode: a row carrying [REDACTED:<id>] is rewritten with the
    marker byte-exact, so approve_quarantine's literal replace still matches
    (docs/spec/security.md §Quarantine workflow)
  - Stage1BodyRemediationIT.aPayloadRevealedByRemediationIsFlaggedAndRedacted passes
    (P9) — failure mode: a stored body whose decoded form carries a
    delimiter-injection token gets a quarantine row and a placeholder, never a
    literal payload
  - Stage1BodyRemediationIT.savedPostSnapshotIsRemediated passes (P12) — the
    /saved surface the defect was observed on renders saved_post.body, which no
    post-retention partition drop ever reaches
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1BodyRemediationIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/schema.md §Posts and derivatives
  - docs/design/04-security.md §4.2 Layered ingest security
decision_refs:
  - D13
  - D20
---

# M1-786: Remediate post and saved_post bodies stored before the plain-text fix

## Context

M1-784 makes Stage 1 store plain text. It does not touch a single row already
written, and nothing else will: `post.body` has exactly three writers —
`PostPersister.java:188` (INSERT of a fresh row), `Stage1Pipeline.java:427`
(UPDATE gated on the `stage1_done` cursor, so a processed post is never
revisited) and `approve_quarantine`'s placeholder replace
(`V69__approve_quarantine_verdict_owed_guard.sql:150`). Re-fetching does not
help either: `PostPersister`'s `WHERE NOT EXISTS (SELECT 1 FROM post WHERE uid = ?)`
pre-filter (`PostPersister.java:159`) dedups the same item across ticks.

So without this ticket, the reported symptom stays on screen for the existing
corpus — and permanently for bookmarks, because `SaveCommandHandler` snapshots
`post.body` into `saved_post.body` (`SaveCommandHandler.java:143`–`146`,
`:375`) precisely so that post retention cannot break the bookmark
(`docs/spec/schema.md` §Posts and derivatives, Invariant 6). `/saved` — the
surface the live run actually observed the defect on — renders that snapshot.

This ticket is optional and deferrable: the pipeline is correct without it.
Analysis: `docs/plan/m1/tick-analysis/ingest-corrupts-post-body-text.md`.

## Root cause

Not a code defect — a data residue. Rows written between the Stage 1
implementation and M1-784 carry the OWASP serializer's output: allowlisted tags
standing and `= ' " @ + \`` rewritten as numeric entities. `post.search_tsv`
is GENERATED over `coalesce(body_en, body, '')`
(`V74__post_english_anchor.sql:58`–`63`), so those rows' lexical index entries
carry the corruption too — and will regenerate automatically the moment `body`
is UPDATEd.

## Pitfalls

- P7: Damaging a `[REDACTED:<id>]` marker while rewriting. Remediated rows
  include quarantined ones; `approve_quarantine` restores with a literal
  `replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html)`
  (`V69…sql:150`), so one altered byte makes an admin's approve a silent no-op.
- P9: The remediation *decodes*. Converting a stored body reveals text the
  original scan never saw — the same mechanism that makes M1-784 need M1-785's
  guard. A conversion that writes without scanning reintroduces the defect on
  the remediation path, this time on content that already passed Stage 1 and is
  visible to users.
- P11: Re-implementing the conversion in SQL or as a regex. It would be a
  second decoder, guaranteed to disagree with the OWASP-policy parse on the
  first malformed tag, and SQL cannot call the Java sink at all. The job must
  drive the same code path M1-784 ships.
- P12: Remediating `post.body` and stopping there. `saved_post.body` is a
  permanent per-user copy (D13, per-user-globally) that post retention never
  reaches, and it is the surface the defect was observed on.
- P14: Converting a row twice. The conversion is safe exactly once: an
  old-pipeline row cannot contain a raw `<` that is not a real tag, because the
  serializer escaped every literal one. After conversion it can — a post about
  HTML now legitimately reads `use <b> for bold` — and a second pass would strip
  it. At-most-once per row must be a property of the schema, not of the
  operator remembering.

## Approach

Derived from `docs/design/04-security.md` §4.2 step 4 (the conversion is the
one M1-784 implements) and `docs/spec/security.md` §Ingest pipeline (a body
Stage 1 writes is a body Stage 1 scanned).

**Files to touch**

- One new Flyway migration adding a nullable `TIMESTAMPTZ` remediation marker
  to `post` and to `saved_post` (PG fast default, no table rewrite), plus the
  collector-role grants those columns need.
- One new job class under
  `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/`
  and its IT.

**Steps, in order**

1. Migration first: the marker columns are what make step 3 safe, so they land
   before any rewriting code exists (P14).
2. Extract nothing new — call M1-784's sink and M1-785's scan through the same
   entry point `Stage1Pipeline` uses. If that entry point is not reachable
   without also flipping `stage1_done`, add the narrowest package-private
   method that exposes convert-plus-scan and nothing else (P11).
3. Batch loop, bounded per tick, over rows with a NULL marker: read the body,
   convert, scan the converted string, redact any match and insert its
   quarantine row, then write the body and stamp the marker in one transaction
   (P7, P9, P14). A row whose conversion is a no-op still gets its marker.
4. `post.status`, `ready_at` and every per-stage cursor flag are left alone —
   this is a representation repair, not a re-evaluation.
5. Same loop shape for `saved_post` (P12). Its rows carry no `fetched_at`
   partition key and no quarantine rows of their own, so a snapshot whose scan
   matches is redacted in place and recorded; the underlying post is never
   touched from this path.

**Controls to preserve (engineering-rules §10)**

The path being re-parameterized is "a body that has already passed Stage 1".
Its obligations, carried:

- Every stored body is a scanned body — carried by step 3's scan, not assumed
  from the row's original `stage1_done`.
- `[REDACTED:<id>]` byte-exact so `/quarantine approve` still restores —
  carried by step 3 and pinned by its test (P7).
- `quarantine.original_html` untouched: the span was captured verbatim at scan
  time and is the audit record; rewriting it would make an admin's restore
  reinstate text that was never in the post.
- No status transition, no `ready_at` re-stamp: `ready_at` re-stamping would
  move posts in `searchPosts` ordering, which `docs/spec/security.md`
  §Prompt-injection defenses relies on being stable.

**Pitfall → mitigation**: P7 → step 3; P9 → step 3's scan; P11 → step 2;
P12 → step 5; P14 → steps 1 and 3.

## Definition of done

- A pre-fix `post.body` and a pre-fix `saved_post.body` both read back as plain
  text after the job runs.
- The job's output for a given input equals the live pipeline's output for the
  same input.
- A row already remediated is never converted a second time.
- Quarantine placeholders survive byte-exact.
- A payload revealed by the conversion is flagged and redacted, not stored
  literal.
- No post status, `ready_at` or stage cursor changes.
- `mvn verify` from the repo root is green.

## Verification

- P7 → `Stage1BodyRemediationIT.remediationNeverDamagesAQuarantinePlaceholder`
  — seeds a body carrying a `[REDACTED:<id>]` marker plus markup; asserts the
  marker is present byte-exact afterwards and that `approve_quarantine`
  restores the span.
- P9 → `Stage1BodyRemediationIT.aPayloadRevealedByRemediationIsFlaggedAndRedacted`
  — seeds a stored body whose decoded form carries `` ```system ``; asserts the
  remediated body contains no literal payload and that a quarantine row exists
  with `rule_id` `stage1.delimiter_injection`.
- P11 → `Stage1BodyRemediationIT.remediationOutputMatchesTheLivePipelineForTheSameInput`
  — runs both paths over the same input and asserts equality, so a second
  decoder cannot drift in.
- P12 → `Stage1BodyRemediationIT.savedPostSnapshotIsRemediated` — seeds a
  corrupted `saved_post.body` with no surviving `post` row and asserts it is
  remediated anyway.
- P14 → `Stage1BodyRemediationIT.aRemediatedRowIsNeverConvertedTwice` — runs
  the job twice over a row whose remediated text legitimately contains `<b>`;
  asserts the second run does not touch it.
- reproduction → `Stage1BodyRemediationIT.storedBodyWithMarkupAndEntitiesBecomesPlainText`.
- full suite → `mvn verify` from the repo root.

## Out-of-scope

Derived LLM fields are not re-derived: `post.body_en`, `saved_post.body_en` and
`post.body_summary` were produced by a model from the corrupted text, and
regenerating them costs a generative call per row — a cost and privacy decision
(`docs/spec/security.md` §Secrets handling names the ingest translator as an
unattended continuous consumer) that belongs to the operator, not to this
ticket.

`quarantine.original_html` is left exactly as captured; it is the audit record
of what the publisher sent.

Titles are untouched — Stage 1 never HTML-stripped them on any path, so they
are not corrupted by this defect.

No status transition, no `ready_at` re-stamp, no re-run of Stage 2, and no
change to any `*_done` cursor flag: this repairs a representation, it does not
re-evaluate a post.

The conversion is not re-implemented — the job drives M1-784's sink and
M1-785's scan.

No pre-existing test is modified.

## Census

The class this ticket disposes of is "columns holding a Stage-1-derived copy of
a post body". Re-runnable enumeration:

```bash
grep -rniE 'body[a-z_]*\s+(TEXT|tsvector)' infochat-core/src/main/resources/db/migration/
```

Disposition as of 2026-08-06 (five hits, plus one column the grep cannot see
because of its name):

| column | declared at | disposition |
|---|---|---|
| `post.body` | `V7__joins_post.sql:142` | **fix** — the primary target |
| `saved_post.body` | `V15__saved_post.sql:59` | **fix** — permanent snapshot, `/saved` renders it (P12) |
| `post.body_summary` | `V7__joins_post.sql:143` | out-of-scope — LLM-derived; re-deriving is a generative cost decision |
| `post.body_en` | `V74__post_english_anchor.sql:50` | out-of-scope — same |
| `saved_post.body_en` | `V78__saved_post_english_anchor.sql:39` | out-of-scope — same |
| `post.search_tsv` | `V74__post_english_anchor.sql:58`–`63` | automatic — GENERATED over `body`; regenerates on the UPDATE, no action |
| `quarantine.original_html` | `V10__quarantine.sql:49` | out-of-scope — the verbatim captured span; rewriting it falsifies the audit record |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-786-*.md
```
