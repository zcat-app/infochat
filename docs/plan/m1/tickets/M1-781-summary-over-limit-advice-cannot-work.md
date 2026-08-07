---
id: M1-781
title: "/summary over-limit notice gives advice that cannot work"
status: abandoned
created: 2026-08-06
last_updated: 2026-08-07
abandoned_reason: superseded
replaced_by: [M1-780]
blocked_by: [M1-780]
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
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
    THE CURRENT ADVICE IS NOT MERELY IMPRECISE, IT IS UNREACHABLE. On a
    busy corpus no window satisfies the limit: `-w 2h` returned 299
    posts, `-w 1h` would still exceed 50, and `-w 90m` is rejected
    outright ("does not accept a minutes suffix. Use h, d, or w"). The
    notice must stop recommending an action that cannot succeed.
  - >-
    The notice never suggests a window WIDER than the one the caller
    just used. Currently `/summary -w 2h` is answered with
    "e.g. /summary -w 6h".
  - >-
    When narrowing cannot bring the count under the limit, the notice
    points at tag filtering instead, and names `/get-tags` so the user
    can find one. Pinned by a test at a post count where no legal window
    would help.
  - >-
    EVERY BUNDLE GETS THE REWORDED VALUE. `reply.summary.window_too_large_notice`
    exists in en/cs/es/ru/tr and all five must be updated together —
    keyset parity is build-enforced, and a stale translation is a silent
    regression.
  - >-
    The post count and limit stay interpolated ({0}, {1}) so the message
    remains accurate as the profile-driven limit changes.
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  preserves:
    - >-
      The degraded headline+URL+UID render, including M1-759 anchor-first
      blocks, which this ticket does not touch.
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

The message tells the user to do something that provably cannot work on a corpus
of this size, and illustrates it with a window wider than the one that just
failed. The user has no path to prose from this reply.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §3.2).

## Observed

After typing `/summary -w 2h`:

```
This window contains 299 posts — more than the 50-post summarizer limit, so no
prose was generated; showing headlines + source URLs + post UIDs only. Narrow
the window with -w (e.g. /summary -w 6h) to get prose.
```

`6h` is wider than the `2h` that just failed; `1h` would still exceed the limit;
`90m` is rejected by the argument grammar.

## Census

Every bundle carrying the key this ticket rewords. Re-runnable:

```
grep -c reply.summary.window_too_large_notice \
  infochat-provider/src/main/resources/bundles/*.properties
```

As of 2026-08-06: `en:1 cs:1 es:1 ru:1 tr:1` — five files, one occurrence each,
no other key involved. All five change together or keyset parity reds the build.

## Expected

```
This window contains 299 posts — more than the 50-post summarizer limit, so no
prose was generated; showing headlines + source URLs + post UIDs only. At this
volume a narrower window won't help — try a tag, e.g. /summary technology -w 2h.
/get-tags lists what's available.
```

## Relationship to M1-780

`blocked_by: [M1-780]` is pure serialization: both tickets edit all five bundle
files, so running them concurrently guarantees a conflict. There is no logical
dependency.
