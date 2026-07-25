---
id: M1-691
title: "Carry the markdown-link guarantee at the outbound delivery chokepoint"
status: done
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The closed-list command-redaction control: CLOSED_LIST membership,
    compileClosedListPattern's boundary rule, redactFlagEntry's span rule,
    the flatten regex, and the per-occurrence LLM_OUTPUT_SANITIZED audit
    emission. This ticket relocates the `](` control only. The adjacency
    break is EXTRACTED verbatim (same one-line semantics) so it has one
    declaration and two callers; no redaction behaviour changes.
  - >-
    The unit any existing sanitize() call is given. Not one of the 12
    call sites changes its input. Narrowing them to one author's field —
    and thereby stopping URLs reaching the closed-list pass — is M1-697
    and must not be pre-empted here.
  - >-
    DigestRenderer, ClusterBlockRenderer, SummaryProseGenerator and the
    /summary and /retry render forms. They are M1-697's files. This
    ticket makes M1-697's narrowing SAFE (the link guarantee stops
    depending on the sanitize unit) but performs none of it.
  - >-
    The ingest-side write boundaries: SourceUpsertService.acceptableOverride
    (the /add-source --name constraint) and PostPersister.normalizeUrlForStorage
    (the post.url constraint). The investigation below establishes what they
    do and do not block; tightening either is a separate write-side surface
    with its own handler and audit row.
  - >-
    Adapter-side rendering and the supportsMarkdownLinks / supportsCodeFormatting
    capability flags. The guarantee is asserted over the bytes the Provider
    hands the transport, independent of what any adapter renders.
acceptance:
  - >-
    LlmOutputSanitizer exposes the existing `](` adjacency break as a
    reusable static method whose body is the current one-liner
    (`text.replace("](", "] (")`) unchanged, and neutralizeResidualLinkSyntax
    delegates to it, so the mechanism has exactly ONE declaration in the
    codebase. No CLOSED_LIST, flatten-regex, span-rule or audit change.
  - >-
    OutboundDelivery applies that break to the message body at all five
    public entry points — deliver, deliverToGroup, deliverSequenceToGroup,
    updateInPlace, finalizeInPlace — before the adapter call, so a render
    path cannot deliver `](` regardless of how it assembled its bytes. The
    transform is idempotent and loses no characters, so bare URLs survive
    (D30) and re-application is a no-op.
  - >-
    OutboundDeliveryTest asserts, for each of the five entry points, that a
    body containing `](` reaches the adapter as `] (` and that a body with
    no `](` reaches the adapter byte-identical.
  - >-
    DigestDelivery's per-section slug lookup survives a body rewrite. Its
    RecordingAdapter currently recovers the category slug from an
    IdentityHashMap keyed on the exact OutboundMessage instance, so the
    chokepoint rebuilding the record to neutralize its body would drop that
    section's delivery row and degrade M1-652 replay to a duplicate. Re-key
    the map on correlationId — already unique per section, already carrying
    the slug, and unaffected by any body transform. A DigestDeliveryTest
    case pins that a section whose body contains `](` still records its
    delivery.
  - >-
    DegradedDigestRendererTest gains a case pinning the LAYERING: a
    sourceDisplayName of the form `Acme](https://evil.example/x` appears
    VERBATIM in DegradedDigestRenderer's own return value — the renderer
    does not and need not carry the guarantee — and a comment at the
    assembly point names OutboundDelivery as where it is carried, so a
    future edit that assumes local safety is visibly wrong.
  - >-
    The investigation outcome is recorded in this ticket's body: which
    operands can carry `](` into delivered bytes, at which attacker tier,
    and why a closed-list token cannot reach either operand. Established by
    reading the write paths and by probing java.net.URI, not by assumption.
  - >-
    docs/spec/security.md §"Sanitizer output never contains `](`" is
    restated as an OUTBOUND guarantee carried once at OutboundDelivery, so
    assembling sanitizer output with operands the sanitizer never saw no
    longer escapes it. Every forward reference to M1-691 in that file and
    in §Deterministic-reply echo surfaces is replaced with the outcome, and
    the /summary --full + /retry residual is re-attributed to M1-697 rather
    than left ownerless.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  preserves:
    - >-
      All tests currently green on main. In particular every existing
      assertFalse(contains("](")) assertion — SummaryIT:132,
      SummaryProseGeneratorTest:112, HelpCommandHandlerTest:228,
      DegradedDigestRendererTest:43 — must stay green unmodified; the
      boundary control makes them more robustly true, never less.
    - >-
      LlmOutputSanitizerTest in full. The extraction is a pure refactor of
      one private method's body into a reusable one; every existing
      sanitizer assertion must pass without edit.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D17
  - D30
reviews:
  - round: 1
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 523
      removed: 132
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-25
  verdict: WARN
  warnings:
    - >-
      self-check: the pre-refine Notes' hypothesis that display_name writes
      are bot-admin-gated is FALSE — /add-source has non-admin branches
      (SourceUpsertService.Outcome.SUBSCRIBED_EXISTING) and the group arm is
      group-admin, below bot admin. Corrected in the body below.
    - >-
      refined 2026-07-25 from "decide whether degraded-digest assembly
      operands need sanitizing" after the investigation showed the defect is
      not local to DegradedDigestRenderer. escalation_reason budget-breach.
escalation_reason:
---

# M1-691: Carry the markdown-link guarantee at the outbound delivery chokepoint

## Investigation outcome (acceptance item 5)

The original question was whether `DegradedDigestRenderer`'s unsanitized
assembly operands need sanitizing. Both do carry `](`, and neither can
carry a closed-list token — but sanitizing them locally is the wrong fix,
and the reason is the whole point of this ticket.

**`source.display_name` — YES for `](`, at group-admin tier.** Two write
paths: `BootstrapLoader` (operator-authored JSON, trusted) and
`SourceUpsertService` via `/add-source --name`. The latter's
`acceptableOverride` (`SourceUpsertService.java:371-393`) NFKC-normalizes,
runs `stripMetadataField`, and rejects empty / >80 chars / **any** `/`.
`](` contains no slash, so `--name "Acme](https://evil.example/x"` is
stored verbatim. Only the INSERT arm writes the column (the
`ON CONFLICT DO UPDATE` arm never touches `display_name`), so it needs a
fresh `(kind, identifier)`. `/add-source` in a group requires group admin
or bot admin; in a DM any non-banned user. The degraded digest is a
**group broadcast** (`DigestWorker.java:209,233` → `deliverToGroup`) and
`EligiblePostQuery` admits group-subscribed sources, so a group admin —
below bot admin — reaches every member. That is exactly the tier
`acceptableOverride` was written for in M1-659; it closed slashes and not
brackets.

**`post.url` — YES for `](`, at NO privilege tier.** `normalizeUrlForStorage`
(`PostPersister.java:240-254`) requires only that the stripped string parse
as a `java.net.URI` with an http/https scheme. Java's `URI` permits `]` and
`(` in the **query and fragment** components — probed directly:
`https://x.example/#](` and `https://x.example/?q=](` both parse and both
return scheme `https`; only the *path* rejects brackets. So any RSS feed's
`<link>` can carry the sequence with no privilege at all. This is the
stronger of the two vectors.

**Closed-list admin-command token — NO, on both operands.** Every
`CLOSED_LIST` entry begins with `/`; `containsSlash` (`:431-433`) rejects
that for `--name`, bootstrap names are operator-authored, and a stored URL
always begins with `http`.

**Cross-operand adjacency cannot form.** The assembly separators are
`" — "` and `"\n"`, both non-empty, so a title ending `]` can never abut a
following operand's `(`.

## Why the local fix is the wrong fix

`LlmOutputSanitizer.sanitize()` welds together two controls whose correct
scopes are incompatible:

| Control | Protects against | Correct unit | Correct placement |
|---|---|---|---|
| closed-list redaction | reader copy-pastes a privileged command | ONE author's free-text field | each interpolation of untrusted text |
| `](` adjacency break | renderer builds a link hiding the real URL | the WHOLE delivered message | once, at the outbound boundary |

Every call site must pick one unit, and whichever it picks is wrong for
the other control:

- **Narrow unit** (one title) → redaction correct, link guarantee leaks.
  That is this ticket's finding: `sanitize(title)` joined with a
  `display_name` and `url` the sanitizer never saw.
- **Wide unit** (assembled prose) → link guarantee correct, redaction
  over-reaches. That is M1-697 gap 1 (one post's redaction span deletes a
  co-clustered post's headline) — and it also **eats URLs**, because
  `compileClosedListPattern` (`:241-253`) applies no leading-boundary
  requirement, so an ordinary feed URL whose path is `/audit`, `/pending`,
  `/digest` or `/lang` is rewritten to `[redacted command]`.

So M1-675 narrowed, M1-694 widened, M1-697 is filed to narrow again, and
this ticket exists because narrowing leaked. **No choice of unit satisfies
both controls**, which is why the ticket stream has not converged. The fix
is to stop making the link guarantee depend on the unit at all.

## The change

Relocate the `](` control to `OutboundDelivery`, which already declares
itself "the single Provider-side outbound-delivery chokepoint" — every chat
reply, progress placeholder/finalize, periodic digest and group
announcement routes through its five entry points, and `DigestDelivery` is
a decorating `MessagingAdapter` *downstream* of it, not a bypass. Applied
there the property is correct **by construction**: no render path can
forget it, no assembly can evade it, and operand joins are covered without
any per-operand reasoning.

The closed-list control stays exactly where it is. Narrowing its unit —
and thereby keeping URLs away from it — becomes safe, and is M1-697's job.

## Census

The class is "places that can put bytes on the wire" and "places that
declare the `](` mechanism". Both are enumerable and small.

Outbound entry points (`grep -n "public .*deliver\|public .*InPlace"
infochat-provider/.../messaging/OutboundDelivery.java`) — 5 on 2026-07-25:

| Entry point | Body carrier | Disposition |
|---|---|---|
| `deliver` | `OutboundMessage.text()` | **in scope** — break before `adapter.send` |
| `deliverToGroup` | `OutboundMessage.text()` | **in scope** — same |
| `deliverSequenceToGroup` | `List<OutboundMessage>` | **in scope** — map over the list |
| `updateInPlace` | `String body` | **in scope** — break before `adapter.update` |
| `finalizeInPlace` | `String body` | **in scope** — break before `adapter.finalizeMessage` |

The bodies are captured in each method's `DeliveryOp` lambda closure, so
the transform belongs at the top of each of the five methods, not in the
shared `execute()` they funnel into — `execute` never sees the bytes.

Declarations of the `](` break (`grep -rn 'replace("](' --include=*.java`)
— 1 on 2026-07-25: `LlmOutputSanitizer.java:480`. It stays the single
declaration; this ticket adds a second *caller*, not a second copy.

Sanitize call sites (`grep -rn "\.sanitize(" --include=*.java` over both
modules) — 12 on 2026-07-25. **All 12 are out of scope and none changes
its input in this ticket**; their disposition is M1-697's census, which
already enumerates them.

## Blast radius (measured, not assumed)

- `](` occurrences in `en.properties` / `cs.properties`: **0**. The
  transform is byte-identical on all bot-authored template text.
- `](` in test sources outside `LlmOutputSanitizerTest`: every occurrence
  is an `assertFalse(contains("]("))` **assertion**, not fixture data —
  `SummaryIT:132`, `SummaryProseGeneratorTest:112`,
  `HelpCommandHandlerTest:228,715,722`, `DegradedDigestRendererTest:43`,
  `AssetDataSourceContractTest:78`. Forced test changes: **zero**.
- Idempotent: `"] ("` contains no `"]("`, so double application is a no-op
  and stacking with the sanitizer's own internal break is safe.

## Residual

If a future help topic or chat reply legitimately needs to *show* markdown
link syntax, the boundary alters it. Nothing does today (0 occurrences in
the bundles), and the LLM chat path already passes through the same
transform inside `sanitize()`, so this is not a new constraint on that
path.

## Notes

- The pre-refine body asserted `source.display_name` was bot-admin-gated.
  That was wrong and is corrected above; `/add-source` has non-admin
  branches and its group arm is group-admin.
- **The identity coupling is why this ticket touches `DigestDelivery`.**
  Relocating a control to a chokepoint means the chokepoint may now rewrite
  bytes it previously only forwarded, and any downstream code that keyed on
  the *object* rather than on its stable identifier silently loses its
  hook (engineering-rules §10). `DigestDelivery` is the one such caller;
  the census above confirms the other four entry points hand their body
  straight to the adapter with nothing keyed on the instance. Today the
  sequence path carries no `](` because its bodies pass whole through
  `sanitize()` — but M1-697 narrows exactly those units, so the coupling
  becomes live the moment M1-697 lands. Fixing it here, not there.
- Do not delete `neutralizeResidualLinkSyntax`'s call inside
  `applyMarkdownLinkStrip`. It serves a second purpose the boundary does
  not cover: stopping the sanitizer from *manufacturing* link syntax
  during canonicalization and redaction (M1-676 rounds 1–3). The boundary
  is defence in depth added below it, not a replacement.
