---
id: M1-772
title: "Slash-command arguments end at the first line"
status: done
created: 2026-08-05
last_updated: 2026-08-06
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    NARROWING THE SANITIZER. `LlmOutputSanitizer`'s flag-span match stays
    whole-message, across newlines, exactly as it is today. Once commands
    are single-line the whole-message scan is merely CONSERVATIVE rather
    than required, and narrowing it to a line is a follow-up that needs
    its own analysis. Narrowing it in THIS ticket would be a hole: the
    sanitizer would stop matching a cross-line pair before every path
    that could still dispatch one is provably gone. `LlmOutputSanitizer
    Core.java` is in `files_scope` for JAVADOC ONLY — two comment blocks
    justify the wide scan by a router behaviour this ticket removes, and
    a reader who narrows the scan would be acting on a false premise.
    Not one executable line in that file changes.
  - >-
    REVERTING M1-766's PAIR SANITIZE. `DisplayHeadline.derive` keeps its
    ONE sanitize call over the anchor/original pair. It is the same
    follow-up as the item above and rests on the same ordering argument.
  - >-
    REJECTING UNKNOWN TOKENS. `/list-sources why? --all` on ONE line
    still dispatches after this ticket, and that is fine — both tokens
    share a line, so the existing per-field sanitize call already
    matches them. Making the ~20 permissive parsers strict is hygiene,
    not security, and is its own ticket.
  - >-
    THE HANDLERS. All 41 `CommandHandler` implementations are untouched.
    Rejecting the multi-line body at the router means no handler ever
    receives one, so every handler keeps its exact current `rawText`
    semantics — including `SummaryCommandHandler`'s whole-body
    `computeArgHash`, `GetSourcesCommandHandler`'s re-serialization, and
    the six `endsWith(" confirm")` suffix tests.
  - >-
    `isConfirmShape` AND THE STEP-4.5 SWEEP BLOCK. Both unchanged. The
    rejection returns before the sweep, so it drains the pending entry
    itself via `takeAny` (see acceptance) rather than teaching the sweep
    about multi-line bodies.
  - >-
    THE BODY CAPS' OWN SKIP. The two pre-existing cap branches return
    ahead of the step-4.5 drain and the step-4.6 anchor clear exactly as
    they do on `main`. They are reachable only with an over-cap body,
    which is why this ticket does not restructure them; whether the
    drain should be hoisted above all three intake rejections at once is
    a follow-up, and touching them here would be scope drift.
  - >-
    THE CHAT PATH. A non-slash body stays multi-line by design —
    `normalize` preserves fenced code blocks specifically so chat can
    carry them. Only bodies that begin with `/` are affected.
acceptance:
  - >-
    MULTI-LINE SLASH BODIES ARE REJECTED. `InboundRouter` gains a check,
    placed immediately after the existing slash body cap (the
    `normalized.startsWith("/") && normalized.length() > commandBodyCap`
    branch), that replies with a new fixed
    `error.command.multiline` bundle string and returns when a body
    beginning with `/` carries content beyond its first line. No parse,
    no dispatch, no DB write — the same ordering guarantee the body cap
    already states.
  - >-
    THE CHECK COVERS EVERY LINE TERMINATOR, NOT JUST `\n`. A bare-`\n`
    test is NOT sufficient and must not be shipped: `normalize` splits
    on `\n` alone and `appendNormalized` preserves `\r`, `U+000B`, `\f`,
    `U+0085`, `U+2028` and `U+2029` (none is a bidi control or
    zero-width, and NFKC folds none of them), while the handlers'
    `split("\\s+")` still tokenizes `\r`, `U+000B` and `\f` as
    separators. So `/list-sources\r--all` would dispatch the admin
    listing from a body that never appears to hold a second line. The
    predicate matches the whole `\R` set. A tab is NOT a line boundary
    and stays legal. Carry the reasoning as a javadoc block — the check
    reads as over-broad without it.
  - >-
    THE BUNDLE KEY EXISTS IN ALL FIVE BUNDLES.
    `BundleKeys.ERROR_COMMAND_MULTILINE = "error.command.multiline"`,
    with a value in `en`, `cs`, `es`, `ru` and `tr` — a key missing from
    any one bundle fails the existing bundle-parity test.
  - >-
    NAMED TESTS in `InboundRouterCommandCapTest`: a `/help` body with a
    second line is rejected with the multiline reply and reaches NO
    handler; the same body without the second line dispatches normally;
    a multi-line CHAT body (no leading slash) is unaffected;
    `/list-sources` with `--all` on a second line does NOT dispatch the
    admin listing; the same with a bare `\r` in place of the `\n` also
    does not dispatch; and every member of the `\R` set is rejected
    while a tab is not. The dispatch cases are the security ones and
    must assert on dispatch, not on the reply text.
  - >-
    THE CROSS-LINE DISPATCH CLASS IS CLOSED AT THE FUNNEL, NOT PER
    COMMAND. `RetryCommandHandler.hasFlag` scans every token of the whole
    body for `--digest` and `GetSourcesCommandHandler` re-serializes all
    tokens into its delegated call; both stop being reachable with a
    second line without either file being edited. The census below is
    the evidence that one funnel covers the class.
  - >-
    THE REJECTION DRAINS A PENDING CONFIRM (redteam finding 1). The
    rejection returns ahead of the step-4.5 sweep, so it calls
    `confirmStateService.takeAny(actor, scope)` itself before replying
    and sends `reply.confirm.cancelled` first when an entry was armed.
    The drain is UNCONDITIONAL — it does NOT re-use `isConfirmShape`,
    which would accept `/ban x\nnote confirm` (prefix + ` confirm`
    suffix) and leave that body's pending entry armed. A rejected body
    dispatches nothing and so can never redeem a confirm; draining is
    both simpler and the safe direction. `takeAny` is an in-memory map
    removal, which is why it may precede the parser at all.
  - >-
    THE STEP-4.6 ANCHOR CLEAR IS NOT HOISTED — STATED RESIDUAL. It is a
    DB write, and §Input length caps forbids a DB write for a body that
    never reaches the parser, which is the same reason the two cap
    branches skip it. The anchor is per-(user, scope) and only re-rolls
    that user's own last summary, so one outliving a rejected message
    costs nothing. Recorded in the code comment and here, NOT in
    `docs/spec/commands.md` §/retry: that section's "any non-`/retry`
    input clears the anchor" already has several pre-existing exceptions
    (the ban reply, the probation block, the group-approval short-
    circuit and the step-1.5 rate-cap drop all return ahead of step
    4.6), so an amendment naming only the intake rejections would be
    wrong. Enumerating them all is the follow-up's job, not this
    ticket's.
  - >-
    NAMED TESTS in `InboundRouterConfirmCancelTest`: a multi-line body
    shaped like a confirm (`/ban <target>\n... confirm`) drains the
    pending entry, emits the cancellation THEN the multiline error, and
    reaches NO handler; a multi-line body with nothing pending emits
    exactly one reply. The first case is the security one — it must
    assert `takeAny` was called and that dispatch count is zero.
  - >-
    STALE PREMISES CORRECTED, NOT LEFT FOR A FOLLOW-UP.
    `LlmOutputSanitizerCore`'s two javadoc blocks (the `redactFlagEntry`
    "why it mirrors the parser" paragraph and `isTokenSeparator`) assert
    that the router hands the handler a possibly-multi-line body and
    that the argument run spans lines. Both are false after this change
    and both sit on the file a future narrowing pass would edit, so they
    are corrected here; JAVADOC ONLY, no executable line. Alongside
    them, `docs/spec/security.md` scopes the ASCII-`\s` equivalence to
    `ListSourcesArgs.parse` rather than "the parser" — `CommandTokenizer`
    splits on the wider `Character.isWhitespace`, nothing on the closed
    list is parsed by it today, and a future entry that is would need
    the scan's separator set widened in the same change.
  - >-
    THE SPEC DOES NOT OVERSTATE THE CLOSED DEFECT.
    `docs/spec/commands.md` §Surface conventions must NOT say a later-
    line `--all` "dispatched the admin-only listing":
    `ListSourcesCommandHandler:161-174` re-reads the actor and returns
    `error.list_sources.admin_only_flag` for a non-admin, and
    `RetryCommandHandler:538-548` gates `--digest` the same way. The
    defect was a widened argument run REACHING an authorization-gated
    branch — for an admin, a privileged enumeration (and its audit row)
    from a command they did not type — not an authorization bypass.
  - >-
    SPEC RECORDS THE PARSER RULE. `docs/spec/commands.md` §Surface
    conventions states that a slash command and all its arguments occupy
    ONE line and that a body with content past the first line is
    rejected unparsed, alongside the existing two body caps.
  - >-
    SPEC RE-DERIVES THE SANITIZER SCAN. `docs/spec/security.md` §LLM
    output sanitizer currently justifies the whole-message, across-
    newlines flag scan by "the router hands the handler the entire,
    possibly multi-line, body, and `ListSourcesArgs.parse` tokenizes it
    with `split(\"\\s+\")`". That premise is now false. The section must
    say the scan is retained as DEFENSE IN DEPTH over a parser that no
    longer dispatches across lines, and must NOT claim the cross-line
    residuals are closed — nothing in the sanitizer changed here.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - >-
      New @Test methods only — both target files already exist and are
      MODIFIED, not created. Six cases appended to
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
      and two to
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java;
      every pre-existing test in both classes is untouched.
  preserves:
    - >-
      The intake step order: authorization gates (2/3/4/3.5) before the
      body caps, body caps before the confirm drain (4.5), the anchor
      clear (4.6) and the probation gate (5).
    - >-
      The step-4.5 sweep's own decision tree — the three M1-051
      scenarios in `InboundRouterConfirmCancelTest` are untouched; the
      single-line rule's drain is additive and tested separately.
    - >-
      Every handler's `rawText` contract, including
      `SummaryCommandHandler.computeArgHash` over the whole body.
    - >-
      `LlmOutputSanitizer`'s whole-message flag span and M1-766's
      pair sanitize in `DisplayHeadline.derive`.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Authorization model
decision_refs:
  - D29
reviews:
  - round: 1
    date: 2026-08-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 1175
      removed: 258
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-05
    category: AUTH-BYPASS
    severity: low
    promise: |
      docs/spec/security.md §Invite-code registration commits that the
      broad-blast-radius admin primitives are confirm-gated, and
      §Authorization model step 4.5 (spec §Surface conventions, "any
      other input cancels") makes the armed window self-closing: the
      pending confirm is drained, and the user acknowledged, as soon as
      the same (user, scope) sends anything that is not the confirm
      shape. The same step carries the /retry anchor clear.
    gap: |
      The new multi-line rejection returns before the step-4.5 confirm
      sweep and the step-4.6 anchor clear. A multi-line slash body
      therefore leaves a pending destructive confirm armed for the rest
      of its TTL, emits no REPLY_CONFIRM_CANCELLED acknowledgement, and
      leaves the /retry anchor in place. The pre-existing body-cap
      branch has the same skip, but it is reachable only with an
      over-cap body, whereas the new branch is reachable with a
      two-character body — the exception widens from a rare shape to a
      trivially-typed one (engineering-rules §10).
    repro: |
      1. Bot admin DMs `/invite create --open`; a PendingConfirm is
         armed under (actorId, DM scope) with a TTL.
      2. The admin sends any slash body carrying a line boundary, e.g.
         `/help\nnote to self`. The router returns with
         error.command.multiline.
      3. The confirm is still armed and no cancellation was
         acknowledged; a later confirm-shaped body still redeems the
         ORIGINAL armed payload. Not cross-principal — PendingConfirm
         is keyed by (actorUserId, scope) — hence low.
    suggested_fix_class: trust-boundary-tightening
    disposition: |
      FIXED IN SCOPE (user decision, 2026-08-05). The rejection now
      drains the pending confirm itself, unconditionally, before
      replying. The step-4.6 anchor clear stays behind the return —
      it is a DB write, the same reason the cap branches skip it.
      That residual is recorded in the code comment and in the
      acceptance item, NOT in commands.md §/retry — a spec amendment
      naming only the intake rejections would be wrong, since that
      sentence already has several pre-existing exceptions. The
      identical skip on the two pre-existing cap branches is left
      alone as out-of-scope: whether to hoist the drain above all
      three intake rejections at once is a follow-up.
redteam_audits:
  - date: 2026-08-05
    verdict: FINDINGS
    base: 0299566d296021742b7d35ac98ac0ab21e613873
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-772-2026-08-05.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      One low AUTH-BYPASS finding: the new multi-line rejection returns
      ahead of the step-4.5 confirm drain, so a trivially-typed body
      now skips a control that previously only an over-cap body could
      skip. FIXED in-branch — see the finding's disposition. All four
      OUT-OF-MODEL items also actioned in-branch (stale
      LlmOutputSanitizerCore javadoc, the commands.md overstatement,
      the ASCII-`\s` over-generalization); the availability
      trade-offs are accepted by design and unchanged. This audit is
      superseded by the re-audit below.
  - date: 2026-08-06
    round: 2
    verdict: CLEAN
    base: 0299566d296021742b7d35ac98ac0ab21e613873
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-772-2026-08-06-r2.md
    out_of_model_count: 5
    note: |
      Re-audit of the remediated diff. CLEAN — no new findings. The
      round-1 finding above is closed by the in-branch fix and its
      entry is retained (not reset to []) so the disposition record
      survives. Four of the five out-of-model items restate
      dispositions already made; the fifth is new and was introduced
      by this diff: the isMultiLineCommand javadoc was inserted
      between isConfirmShape's javadoc and its declaration, orphaning
      the latter. Documentation only, and FIXED in-branch by moving
      the block back — the text is unchanged, so the hunk nets to
      zero against `main`.
clarity_check:
  date: 2026-08-05
  verdict: PASS
  warnings:
    - >-
      Ticket rewritten at the self-check: the original subject was
      superseded by M1-766 (8a76d164) and its census was stale. The
      rewrite targets the root cause the grounding pass found. Lint
      re-run clean on the rewritten file.
  blockers: []
escalation_reason:
---

# M1-772: Slash-command arguments end at the first line

## Context

This ticket was originally "Audit the anchor/original pair as a span",
filed 2026-08-05 from M1-765's round-2 audit. That subject was
**superseded before it started**: M1-766 (`8a76d164`, the same day)
already made `DisplayHeadline.derive` issue ONE sanitize call over the
anchor/original pair, which redacts AND audits a straddling closed-list
entry — strictly more than the audit-only pass the old ticket asked
for — and amended `docs/spec/security.md` to match. The old ticket's
`out_of_scope` also forbade exactly the merged call M1-766 shipped, and
its census was stale (it named three `anchorFirst` sites; M1-766 took
the count to five).

Rewritten 2026-08-05 at the `start` self-check, on the user's
direction, to the root cause found while grounding the old subject.

## The actual defect

The router requires a command word at **position 0** —
`InboundRouter:1081`, `normalized.startsWith("/")`. But it then hands
the handler the **entire multi-line body** (`:1130`, `:1468`), and every
argument parser tokenizes with `split("\\s+")`, in which Java's `\s`
matches `\n`. So arguments are gathered across every line of the
message while the command word is pinned to the first.

That asymmetry is not a decision anyone made — it is a side effect of
the regex. Its consequence is that

```
/list-sources
<arbitrary lines>
--all
```

reaches the admin-only deployment-wide listing branch, and

```
/retry
<arbitrary lines>
--digest
```

reaches the admin digest-retry branch (`RetryCommandHandler:672-676`).

Neither is an authorization bypass — `ListSourcesCommandHandler:161-174`
and `RetryCommandHandler:538-548` both re-check the actor and refuse a
caller without the tier. What the asymmetry produces is a *privileged
branch entered without the caller asking for it*: an admin who pastes a
note under a bare `/list-sources` gets the deployment-wide enumeration,
and its audit row, from a command they did not type.

The same asymmetry is what forces the LLM output sanitizer to scan for
a flag across the **whole message**: `docs/spec/security.md` derives
that scan explicitly from what the parser dispatches. The cross-line
scan is in turn what makes the flag-span deletion wide enough to raise
the content-suppression concern that M1-697, M1-765, M1-766 and the
original M1-772 have each argued about. The complexity is downstream
of a loose parser.

## Why reject rather than truncate

Silently ignoring lines 2+ is the same permissiveness that caused this,
and it is not free: `SummaryCommandHandler:393` hashes the **whole
body** into `summary_anchor.arg_hash`, which is persisted
(`SummaryAnchorRepository:95`) and surfaced to the user through
`/export` (`ExportDataCollector:106`). Truncating changes that stored
value. `GetSourcesCommandHandler:40` re-serializes the body into a
delegated `/list-sources` call. Six handlers confirm-match on the whole
body with `endsWith(" confirm")`.

Rejecting at the router means no handler ever sees a multi-line body,
so all 41 keep their exact current `rawText` semantics and **not one
handler file is edited**. That is the whole argument for this placement.

## Census

The class is **argument parsing that reads past the command's own
line** — i.e. every `CommandHandler` and arg parser, since all of them
tokenize the full body. Re-runnable:

```
grep -rn 'split("\\s+"' --include=*.java infochat-provider/src/main/java/app/zcat/infochat/provider/command/ infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/
grep -rn 'split("\\n"\|\.lines()\|split("\\R"' --include=*.java infochat-provider/src/main/java/app/zcat/infochat/provider/command/
```

The second grep returns **nothing**: no handler splits on newlines, so
no handler is multi-line aware by design. Surveyed 2026-08-05 across
all 41 `CommandHandler` implementations plus `AddSourceArgs`,
`SummaryArgs`, `CommandTokenizer` and `asset/AssetHandler`:

| Group | Count | Disposition |
|---|---|---|
| Never read `rawText` at all | 5 | Unaffected |
| Tokenize `rawText` only | 30 | COVERED — the funnel rejects before they run |
| Confirm-match the whole body (`endsWith(" confirm")`) | 6 | COVERED — canonical one-line confirms are byte-identical after the change |
| Hash the whole body (`SummaryCommandHandler`) | 1 | COVERED — rejection means the hash still sees the body the user sent |
| Re-serialize the body (`GetSourcesCommandHandler`) | 1 | COVERED — a line-2 `--page` stops being folded into the delegated call |

Two sites in that set are the security case rather than hygiene:
`RetryCommandHandler:672-676` (`--digest` anywhere in the body) and
`ListSourcesCommandHandler:478` (`--all` anywhere in the body). Both
close at the funnel.

## What this costs

`CommandTokenizer:24-47` supports double-quoted values, and while
`inQuotes` the whitespace branch is skipped — so `--name="a\nb"` and
`--reason="a\nb"` are the only constructs that can legally parse with
an embedded newline today. After this change both are rejected.

`--name` loses nothing real: `SourceUpsertService.acceptableOverride`
runs `IngestTextNormalizer.stripMetadataField`, which strips ISO
control characters including `\n`, so a multi-line name has never
actually been storable. `--reason` on `/ban` is the one genuine loss,
and a one-line ban reason is not a hardship.

No existing test sends a multi-line slash body (verified by grep over
`infochat-provider/src/test/java`), so no test is retargeted.

## What this unlocks (NOT this ticket)

With cross-line dispatch gone, the sanitizer's whole-message flag scan
becomes conservative rather than load-bearing, and could narrow to a
line. That would in turn remove the reason M1-766 merged the two
sanitize calls in `DisplayHeadline.derive`, and would let the spec drop
its accepted cross-post residual. **Strictly after this ticket** — the
parser must stop dispatching across lines before the detector stops
looking across them. File as a follow-up once this is merged.

## Notes

- **Follow-up to file on merge**: the two body-cap branches return ahead
  of the step-4.5 drain and the step-4.6 anchor clear, exactly as they
  do on `main`. This ticket carries the drain across its own return only.
  Whether to hoist the drain above all three intake rejections at once —
  and whether spec §Surface conventions' "any other input cancels" should
  say "any input that reaches the parser" instead — is one decision about
  pre-existing behaviour and belongs in its own ticket. §/retry's "any
  non-`/retry` input clears the anchor" needs the same treatment and has
  more exceptions: the ban reply, the probation block, the group-approval
  short-circuit and the step-1.5 rate-cap drop all return ahead of step
  4.6 on `main` today.
- The old ticket's §Notes item still stands and is still NOT this
  ticket: `docs/spec/security.md`'s sanitize-call census omits two
  pre-existing one-field `/saved` calls (the `<tag>` filter echo and
  the display-hit sanitizer-2 leg). Whoever next edits that section
  should fold them in.
- `docs/spec/commands.md` is parity-gated
  (`CommandCatalogueParityTest`, `LlmOutputSanitizerTest
  .matchSetEqualsSpecClosedList`). This ticket edits §Surface
  conventions prose only and adds no catalogue row and no closed-list
  entry, but `mvn verify` covers both gates regardless.
- `LangCommandHandler`'s javadoc at `:225-231` claims it returns null
  "when extra tokens follow the code (e.g. `/lang cs xx`)"; the code
  does not do that. Pre-existing, unrelated to this change, worth a
  `text:` commit or a follow-up — do not fix it here.
