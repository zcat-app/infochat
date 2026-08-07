---
id: M1-780
title: "Reply localization defects from the v1.1.0 live test: English relative timestamps and unreachable /summary advice"
status: done
created: 2026-08-06
last_updated: 2026-08-07
blocked_by: []
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ABSOLUTE TIMESTAMPS and the asset reply's `stav k 08:21 UTC` line.
    Only the RELATIVE "N<unit> ago" form is in scope.
  - >-
    Full CLDR/ICU relative-time formatting. Five languages with a small
    fixed unit set do not justify a new formatting dependency; bundle
    keys with a numeric placeholder are the proportionate fix.
  - >-
    Any other reply string. This ticket touches exactly two string
    surfaces — the three relative-time renders and
    `reply.summary.window_too_large_notice` — and the diff must stay
    there.
  - >-
    RAISING THE 50-POST SUMMARIZER LIMIT or changing which posts are
    selected. The limit is a cost control; this ticket only fixes the
    ADVICE given when it is hit.
  - >-
    ADDING A MINUTES SUFFIX to `-w`. That is a real option for making
    the advice actionable, but it is an argument-grammar change with its
    own surface; if chosen, it belongs in its own ticket. Prefer the
    tag-suggestion route here.
  - >-
    The degraded headline render itself, which is correct and was
    verified working (anchor-first blocks included).
acceptance:
  - >-
    EVERY NEW OR CHANGED KEY NEEDS A TWIN IN ALL FIVE BUNDLES. en, cs,
    es, ru, tr are keyset-parity enforced; a key added to one bundle and
    not the others, or a value reworded in one and left stale in
    another, reds the build.
  - >-
    A relative timestamp shown inside a non-English reply contains no
    English words. Pinned by a test asserting the `cs` rendering of a
    just-saved post contains neither `ago` nor a bare `m`/`d` unit
    suffix.
  - >-
    RUSSIAN NEEDS PLURAL FORMS THAT ENGLISH DOES NOT. `ru` distinguishes
    1 / 2-4 / 5+ ("1 минуту", "2 минуты", "5 минут"). Decide once
    whether the keys carry per-plural variants or the wording sidesteps
    plurals, and record which. Do not ship a form that is ungrammatical
    at common values.
  - >-
    The English rendering of `/saved` is unchanged, byte-for-byte, so
    existing en-scope assertions keep passing.
  - >-
    THE CURRENT OVER-LIMIT ADVICE IS NOT MERELY IMPRECISE, IT IS
    UNREACHABLE. On a busy corpus no window satisfies the limit: `-w 2h`
    returned 299 posts, `-w 1h` would still exceed 50, and `-w 90m` is
    rejected outright ("does not accept a minutes suffix. Use h, d, or
    w"). The notice must stop recommending an action that cannot
    succeed.
  - >-
    The notice never suggests a window WIDER than the one the caller
    just used. Previously `/summary -w 2h` was answered with
    "e.g. /summary -w 6h".
  - >-
    When narrowing cannot bring the count under the limit, the notice
    points at tag filtering instead, and names `/get-tags` so the user
    can find one. Pinned by a test at a post count where no legal window
    would help.
  - >-
    The post count and limit stay interpolated ({0}, {1}) so the message
    remains accurate as the profile-driven limit changes.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  preserves:
    - the existing English `/saved` rendering and its current assertions
    - >-
      The degraded headline+URL+UID render, including M1-759 anchor-first
      blocks, which this ticket does not touch.
    - bundle keyset parity across en/cs/es/ru/tr
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
decision_refs:
  - D43
redteam_findings: []
redteam_audits:
  - date: 2026-08-07
    verdict: CLEAN
    base: 0d4d70ab2fae6d0c243aba50b20a23f31beac43b
    head: "working tree, branch m1/M1-780-reply-localization-timestamps-summary-advice"
    verdict_file: docs/plan/m1/redteam/M1-780-2026-08-07.md
    out_of_model_count: 0
    note: |
      Gate-fired (security_relevant: true, operator-set) ahead of review
      per the audit-first ordering. CLEAN on first pass; no remediation,
      no re-audit owed.
reviews:
  - round: 1
    date: 2026-08-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 146
      removed: 23
    note: |
      Redteam gate ran first (operator-set security_relevant: true) and
      returned CLEAN on this exact diff, so review saw the final tree.
      Green verify log reused per the M1-272 skip path — only
      docs/lifecycle files changed since it ran.
overrides: []
---

## Why

Two user-visible reply-quality defects from the same live test, unified
because both edit all five bundle files — running them as separate
tickets bought nothing but an artificial `blocked_by` serialization
(M1-781 was absorbed into this ticket on 2026-08-07).

A Czech reply containing the English word "ago" is the most visible kind
of localization miss — it appears on every row of `/saved`. And a notice
that tells the user to do something that provably cannot work on a corpus
of this size leaves them with no path to prose.

Found during the v1.1.0 live test
(`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §3.1 and §3.2).

`security_relevant: true` is operator-set (2026-08-07) to force the
redteam gate ahead of review — the ticket's surface (bundle strings,
two command handlers) would not infer the flag on its own.

## Observed — relative timestamps

```
Uložené příspěvky (1 z 1 celkem — uložení jsou globální napříč DM a skupinami):
- [e81eebbd…] Klíčová slova „auto" a „typeof" v C23 — uloženo 0m ago — tagy: technology
```

## Observed — over-limit advice

After typing `/summary -w 2h`:

```
This window contains 299 posts — more than the 50-post summarizer limit, so no
prose was generated; showing headlines + source URLs + post UIDs only. Narrow
the window with -w (e.g. /summary -w 6h) to get prose.
```

`6h` is wider than the `2h` that just failed; `1h` would still exceed the
limit; `90m` is rejected by the argument grammar.

## Census

### Relative-time render sites

Every relative-time render site. Re-runnable:

```
grep -rn '"ago"\|d ago\|h ago\|m ago' --include=*.java infochat-provider/src/main/java
```

As of 2026-08-06 this returns exactly three, all in one method:

| file:line | literal |
|---|---|
| `SavedCommandHandler.java:547` | `days + "d ago"` |
| `SavedCommandHandler.java:551` | `hours + "h ago"` |
| `SavedCommandHandler.java:554` | `minutes + "m ago"` |

The strings are hardcoded in Java — there is **no** existing bundle key
for them, so the fix adds keys rather than editing values. Three units x
five bundles = 15 new entries. If the grep returns a fourth site at
implementation time, it is in scope and the census line here is updated.

### The notice key

Every bundle carrying the key this ticket rewords. Re-runnable:

```
grep -c reply.summary.window_too_large_notice \
  infochat-provider/src/main/resources/bundles/*.properties
```

As of 2026-08-06: `en:1 cs:1 es:1 ru:1 tr:1` — five files, one occurrence
each, no other key involved. All five change together or keyset parity
reds the build.

## Expected — relative timestamps

```
Uložené příspěvky (1 z 1 celkem — uložení jsou globální napříč DM a skupinami):
- [e81eebbd…] Klíčová slova „auto" a „typeof" v C23 — uloženo před 0 min — tagy: technology
```

Plural decision (recorded per acceptance): the wording **sidesteps
plurals** — no per-plural key variants. cs/es/ru use invariable unit
abbreviations (`před {0} min`, `hace {0} min`, `{0} мин. назад`), tr
pairs the numeral with the singular (`{0} dakika önce`), and en keeps
the legacy byte-identical `{0}m ago` forms.

## Expected — over-limit advice

```
This window contains 299 posts — more than the 50-post summarizer limit, so no
prose was generated; showing headlines + source URLs + post UIDs only. At this
volume a narrower window won't help — try a tag, e.g. /summary technology -w 2h.
/get-tags lists what's available.
```
