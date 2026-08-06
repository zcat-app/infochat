---
id: M1-780
title: "Relative timestamps stay English inside translated replies"
status: pending
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
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
    Any other reply string. This ticket edits bundles, which serializes
    it against M1-781 and M1-782 — keep the diff to the time strings so
    that serialization stays cheap.
acceptance:
  - >-
    EVERY NEW KEY NEEDS A TWIN IN ALL FIVE BUNDLES. en, cs, es, ru, tr
    are keyset-parity enforced; a key added to one bundle and not the
    others reds the build.
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
    The English rendering is unchanged, byte-for-byte, so existing
    en-scope assertions keep passing.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  preserves:
    - the existing English `/saved` rendering and its current assertions
    - bundle keyset parity across en/cs/es/ru/tr
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
decision_refs:
  - D43
reviews: []
overrides: []
---

## Why

A Czech reply containing the English word "ago" is the most visible kind of
localization miss — it appears on every row of `/saved`.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §3.1).

## Observed

```
Uložené příspěvky (1 z 1 celkem — uložení jsou globální napříč DM a skupinami):
- [e81eebbd…] Klíčová slova „auto" a „typeof" v C23 — uloženo 0m ago — tagy: technology
```

## Census

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

The strings are hardcoded in Java — there is **no** existing bundle key for
them, so the fix adds keys rather than editing values. Three units x five
bundles = 15 new entries. If the grep returns a fourth site at implementation
time, it is in scope and the census line here is updated.

## Expected

```
Uložené příspěvky (1 z 1 celkem — uložení jsou globální napříč DM a skupinami):
- [e81eebbd…] Klíčová slova „auto" a „typeof" v C23 — uloženo před 0 min — tagy: technology
```
