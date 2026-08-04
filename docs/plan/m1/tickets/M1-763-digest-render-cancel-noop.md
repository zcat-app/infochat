---
id: M1-763
title: "Timed-out digest render is never cancelled and keeps spending LLM calls"
status: done
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    `DigestRenderer.java`. The fix belongs at the SUBMISSION site, not
    inside the render. If it turns out the interrupt cannot stop the
    render — because the LLM client swallows it, or because the spend
    happens between two uninterruptible calls — the cooperative-check
    variant threads a cancellation token through `renderSections` and
    every generator it drives, which is a different and much wider
    change. ESCALATE at that point rather than widening in place;
    `DigestRenderer.java` is also contended by M1-759 and M1-762, so a
    silent widening here collides with them.
  - >-
    THE SLOT-WINDOW POLICY. How long a render gets (`slot.windowEnd()`),
    when the worker degrades, and what the degraded renderer emits are
    all unchanged. This ticket does not make the deadline longer, shorter
    or adaptive — it makes the EXISTING deadline actually stop the work.
  - >-
    `CancellationService` / `InFlightTracker`. That machinery is
    chat-turn cancellation, keyed by (userId, scopeKind, scopeId) and
    driven by a user's `/stop`. A scheduled digest render has no user and
    is never registered in the tracker, so reusing it would mean
    inventing a synthetic turn identity. Out of scope; the digest render
    is cancelled by its own future, not by the chat path.
  - >-
    THE AGGREGATE SYSTEM LLM BUDGET. `docs/spec/security.md` §Rate
    limiting claims one exists as "the backstop for digest cost"; it does
    not exist in code. That is a separate, still-undecided spec-vs-code
    gap (see M1-758 out_of_scope, which records it at the spec site).
    This ticket stops ONE leak; it does not supply the missing ceiling
    and must not be read as closing that gap.
  - >-
    The `NEVER re-render after renderSections()` invariant on the success
    path and the cache-write that follows. Untouched.
acceptance:
  - >-
    THE DEFECT, stated so the fix is verifiable: `renderFuture` is a
    `CompletableFuture` built by `CompletableFuture.supplyAsync(...,
    renderExecutor)`, and `CompletableFuture.cancel(boolean)` documents
    that its `mayInterruptIfRunning` argument "has no effect in this
    implementation because interrupts are not used to control
    processing". So `renderFuture.cancel(true)` at `DigestWorker.java`
    completes the future exceptionally for the WAITER and leaves the
    render running to completion on `renderExecutor` — spending its
    per-cluster prose calls, its per-section roll-up calls and, since
    M1-756, its per-headline translator calls, all AFTER the worker has
    already degraded, emitted the degraded content and moved on.
  - >-
    THE COMMENT ASSERTS THE OPPOSITE OF WHAT THE CODE DOES and must stop
    doing so. It reads "Cancel the orphaned render rather than leaving it
    to run past the slot window ... the get(timeout) above stops waiting
    but does not stop the work behind the future. (M1-494 13#F4)" — the
    second half is correct about `get`, and the first half is false about
    `cancel`. Note the anchor: this code was ADDED to close a red-team
    finding, so the finding it claims to close is still open.
  - >-
    A RENDER THAT OVERRUNS ITS SLOT WINDOW STOPS MAKING GENERATIVE CALLS.
    This is the observable property; the mechanism is the implementer's
    choice, but the in-place candidate is submitting the render through
    `renderExecutor.submit(...)` and holding the returned `Future`, whose
    `cancel(true)` DOES interrupt, unlike `CompletableFuture`.
    `renderExecutor` is a `newVirtualThreadPerTaskExecutor`, so the
    interrupt lands on a virtual thread and surfaces at its next
    interruptible point. (Corrected at start: that executor is a
    `ThreadPerTaskExecutor` whose `submit` returns a `ThreadBoundFuture`,
    NOT the `FutureTask` this ticket originally named — the interrupting
    behaviour is the same, only the class was misidentified.)
  - >-
    VERIFY THE INTERRUPT ACTUALLY REACHES THE SPEND before declaring the
    ticket done, and say so in the commit. An interrupt only stops work
    at an interruptible point; if the generative calls run through a
    client that swallows `InterruptedException` or blocks
    uninterruptibly, the future is cancelled and the calls continue —
    the same defect with a different mechanism. If that is what the code
    does, ESCALATE (the cooperative-token variant is out_of_scope above);
    do not declare victory on the future's state alone.
  - >-
    THE TEST ASSERTS SPEND, NOT CANCELLATION STATE. A test that asserts
    `future.isCancelled()` passes TODAY against the broken code and is
    exactly the vacuous shape M1-762 item 2 exists to kill. Assert
    instead that a render which overruns its window makes NO FURTHER
    provider calls after the worker degrades — count calls on a spy,
    with the render blocked on a latch the test controls.
  - >-
    Time driving the deadline stays on the injected `java.time.Clock`
    (`Duration.between(clock.instant(), slot.windowEnd())` is already
    correct per CLAUDE.md §"Injectable time in decision logic"). Do not
    introduce `Instant.now()` while restructuring the submission.
  - >-
    The degraded-path behaviour after a timeout is byte-unchanged:
    `degradedRenderer.render(collection.posts())`, `isDegraded = true`,
    and the same cache write. Only the fate of the ORPHANED render
    changes.
  - >-
    THE INTERRUPT MUST NOT COST AN AUDIT ROW (added by the redteam-finding
    refine, 2026-08-04; engineering-rules §10 — the rerouted path carried an
    audit obligation the original acceptance never enumerated). Making the
    cancel real is what creates this: with an armed interrupt flag, JDBC
    socket I/O on a VIRTUAL thread fails with `SocketException: Closed by
    interrupt`, so `LlmOutputSanitizer.emitAuditRows` throws and the
    `LLM_OUTPUT_SANITIZED` rows for that sanitize call are never written —
    against `docs/spec/security.md` §LLM output sanitizer, which promises
    "Every match is audit-logged ... counted, never throttled". This is
    reachable, not theoretical: `DigestRenderer` batches ALL prose via
    `summaryProseGenerator.generate(...)` and sanitizes it in a LATER loop,
    so prose generated BEFORE the interrupt is sanitized AFTER it.
    `emitAuditRows` must complete its write even when the calling thread's
    interrupt flag is already set.
  - >-
    CANCELLATION MUST SURVIVE THAT FIX. Whatever makes the audit write
    immune to the flag must leave the flag SET when it returns — otherwise
    the render resumes full-speed LLM calls and this ticket's entire
    spend-stopping property is silently undone. Assert both halves: the row
    lands AND the flag is still set afterwards.
  - >-
    THE AUDIT TEST MUST RUN ON A VIRTUAL THREAD. Verified at refine time:
    an interrupted PLATFORM thread completes socket I/O normally, so an
    interrupt test on the JUnit thread passes against the BROKEN code and is
    vacuous. `renderExecutor` is `newVirtualThreadPerTaskExecutor`, so the
    virtual thread is also the faithful reproduction.
  - >-
    SCOPE OF THE AUDIT FIX IS ONE SITE, established by the §Census
    reachability walk. Two classes in `DigestRenderer`'s collaborator graph
    touch a `DataSource`: `LlmOutputSanitizer` (hardened here) and
    `PostReferenceEdgeSource`. Only the first needs hardening, and the
    second's exemption is a stated disposition rather than an omission: its
    `neighborsAmong` is a read-only parameterized SELECT with no audit
    obligation and no write, and it runs as the FIRST statement of
    `renderSections` — before any generative call, hence before the slot
    deadline can fire. An interrupt reaching it would abort a render whose
    output is discarded anyway, costing no audit row. (The round-1 census
    claimed the sanitizer was the ONLY such class; that was wrong — it
    enumerated a hand-picked class list instead of walking invocations, and
    the round-2 red-team caught it. Corrected here.)
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  preserves:
    - >-
      `DigestWorkerClockTest` and every existing `DigestWorkerTest`
      assertion, including the success-path "never re-render" invariant
      and the degraded-on-timeout content.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 695
      removed: 19
redteam_audits:
  - date: 2026-08-04
    verdict: FINDINGS
    base: 9310aca8e720864342241beb10fe1f9517675e13
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-763-2026-08-04.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Run at the /m1-tick run redteam gate, ahead of review, against the
      uncommitted working tree (0 commits on branch). One low finding, an
      engineering-rules §10 control-preservation issue: the real interrupt
      this ticket introduces newly exposes the sanitizer's audit write on
      the render thread. The remedy would touch LlmOutputSanitizer.java
      and/or DigestRenderer.java — both outside files_scope, and
      DigestRenderer is explicitly out_of_scope — so it is a user scope
      decision rather than an in-band fix. Out-of-model item 2 (no aggregate
      LLM budget) is pre-existing and already recorded against M1-758.
  - date: 2026-08-04
    round: 2
    verdict: CLEAN
    base: 9310aca8e720864342241beb10fe1f9517675e13
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-763-2026-08-04-r2.md
    findings_count: 0
    out_of_model_count: 4
    note: |
      Mandatory re-audit: the round-1 in-branch fix invalidated the audit that
      prompted it. The adversary got the round-1 finding verbatim, was told not
      to assume it closed, was pointed at the remediation itself as new attack
      surface (it manipulates thread interrupt state inside a shared audit path
      used by ChatAgent, SavedCommandHandler, DisplayHeadline,
      ClusterBlockRenderer and the ingest-translation surface), and was
      explicitly authorised to return CLEAN. Verdict CLEAN — round-1 finding
      re-verified closed against the code, and the park-and-restore introduced
      no new finding (finally covers the throw path, the no-match early return
      is byte-unchanged, and the D35 /stop path keys off InFlightTracker's own
      AtomicBoolean rather than the interrupt status, so the park cannot
      suppress a cancellation). Out-of-model item 3 caught a REAL error in this
      ticket's own §Census — it enumerated by hand-picked name instead of by
      invocation and missed PostReferenceEdgeSource; the census has been
      rewritten as a field-reachability walk and the acceptance item corrected.
      No code change followed from it (that class is a read-only SELECT with no
      audit obligation), so this verdict still covers the shipped diff.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-04
    category: AUDIT-EVASION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer (line 455) — "Every match
      is audit-logged; rows aggregate per distinct token per sanitize call
      and carry the exact occurrence count — counted, never throttled."
    gap: |
      The diff turns a documented no-op into a real thread interrupt on the
      digest render path, and that interrupt can abort the sanitizer's own
      audit-log transaction. DigestWorker.java now submits via
      renderExecutor.submit(...) so renderFuture.cancel(true) delivers a
      genuine Thread.interrupt() into the render's virtual thread. The
      render thread issues sanitize calls that write audit rows
      (DigestRenderer.java:796, :837; TranslationPipeline.java:156, :288),
      each bottoming out in LlmOutputSanitizer.emitAuditRows, which opens a
      pooled JDBC connection and commits. On an already-interrupted thread
      that write can fail (Agroal's interruptible connection acquisition;
      pgjdbc socket I/O on a virtual thread), and the catch converts it to
      IllegalStateException — so the LLM_OUTPUT_SANITIZED rows for that
      sanitize call are never written. Delivered bytes are still audited:
      the degrade path re-sanitizes every collected headline on the
      uninterrupted worker thread. What is lost is the row for closed-list
      tokens found in LLM-authored prose and translator output inside the
      DISCARDED render — a detection/observability loss, not a delivery
      bypass.
    repro: |
      1. Adversary injects into an RSS/Nostr source a group subscribes to.
      2. They post enough items to make the next scheduled digest expensive
         (the scheduled route draws no per-user/per-group rate bucket).
      3. The render overruns slot.windowEnd(); the worker times out and now
         genuinely interrupts the render thread mid-flight.
      4. Content crafted so the summarizer or translator echoes a privileged
         token is still matched and stripped, but emitAuditRows fails on the
         interrupted thread; no LLM_OUTPUT_SANITIZED row lands.
      5. The operator sees no evidence the model was steered into emitting
         privileged command strings on that slot.
    suggested_fix_class: audit-log-coverage
clarity_check:
  date: 2026-08-04
  verdict: PASS
  warnings:
    - >-
      lint: 0 blockers, 0 warnings.
    - >-
      self-check: every ticket claim about existing code verified true
      (supplyAsync at DigestWorker.java:215, the false comment + cancel at
      :233-237, virtual-thread executor at :59, clock-driven deadline at
      :210). One PROSE defect fixed inline: the ticket named `FutureTask`
      as the interrupting mechanism, but `newVirtualThreadPerTaskExecutor`
      is a `ThreadPerTaskExecutor` whose `submit` returns a
      `ThreadBoundFuture`. Probed on this project's JDK 25: the
      CompletableFuture form does NOT interrupt, the submit form DOES.
      Scope and intent unchanged.
    - >-
      acceptance item 4 (does the interrupt reach the spend) resolved
      IN FAVOUR of the in-place fix, not escalation: the render's LLM
      floor is a single `HttpClient.send` at LlmHttpSupport.java:186,
      which is interruptible AND whose catch at :190-195 RE-ARMS the
      interrupt flag rather than swallowing it. Probed: with the flag
      set, `send` throws InterruptedException in 0ms and opens NO
      socket. The escalate trigger ("a client that swallows
      InterruptedException or blocks uninterruptibly") does not fire.
    - >-
      §10 control preservation: the diff reroutes the render SUBMISSION
      path. Enumerated what the old path did incidentally — get()
      exception wrapping (ExecutionException, identical), executor
      rejection (probed: both forms throw RejectedExecutionException
      synchronously, identical), the InterruptedException flag-restore
      in the existing catch (untouched), the degraded path and cache
      write (untouched). Nothing dropped; no consumer depends on the
      future being a CompletableFuture.
  blockers: []
escalation_reason:
---

# M1-763: Timed-out digest render is never cancelled

## Context

Surfaced by the M1-756 red-team (2026-08-04, out-of-model item 2) and
verified against `main` at `2698edbf` — it is **pre-existing**, not
introduced by M1-756. M1-756's entire `DigestWorker` delta is the single
`slot.groupId()` argument added to the `renderSections` call; the
submission and cancel sites are untouched by it.

What M1-756 changed is the **cost** of the bug. Before it, an orphaned
render burned per-cluster prose and per-section roll-up calls. Since it,
the same orphaned render also burns up to
`infochat.digest.translation-max-per-render` translator calls per render
— on a path where the worker has already given up, already emitted
degraded content, and already moved to the next group.

## Why the existing comment makes this worse, not better

The site carries a comment claiming the cancel works, anchored to a
red-team finding ID (`M1-494 13#F4`). A future reader auditing digest
cost will find an explicit, sourced assurance that orphaned renders are
stopped, and stop looking. That is the failure mode worth fixing even if
the spend turns out to be small: the code documents a control it does
not have.

## The mechanism, precisely

```java
CompletableFuture<List<RenderedSection>> renderFuture =
        CompletableFuture.supplyAsync(() -> digestRenderer.renderSections(...), renderExecutor);
...
renderFuture.cancel(true);   // <-- mayInterruptIfRunning is IGNORED
```

`CompletableFuture.cancel`'s contract is explicit that the flag has no
effect, because `CompletableFuture` does not use interrupts to control
processing. The call transitions the future to cancelled — which the
already-timed-out waiter no longer cares about — and does nothing to the
task running on `renderExecutor`.

`ExecutorService.submit(...)` returns a `Future` which DOES honour
`cancel(true)` by interrupting the running thread. That is the smallest
change that could work, which is why `files_scope` is two files.

## The honest caveat

An interrupt stops work only at an interruptible point. Whether the
generative calls in `renderSections` — cluster prose, category roll-up,
and the M1-756 translator leg — actually observe it depends on the LLM
client beneath them. `acceptance:` therefore requires verifying the
interrupt reaches the spend, and escalating if it does not, rather than
asserting on the future's own state. A cancelled future with a still-
spending render is this exact bug wearing a different hat.

## Census

JDBC reachable from `renderSections` — the enumeration behind the
audit-durability acceptance item's claim that exactly one site needs
hardening. Added by the redteam-finding refine.

**Corrected in round 2.** The first version of this census listed a
hand-picked set of classes and concluded `LlmOutputSanitizer` was the only
one touching a `DataSource`. That was false: it enumerated by name instead of
by invocation, and missed `renderSections` → `ClusterTraversal.cluster` →
`PostReferenceEdgeSource`, which opens a connection on the render thread. The
round-2 red-team caught it. The walk below follows declared collaborator
FIELDS from `DigestRenderer`, so a class cannot be missed by not having been
thought of.

Re-runnable from the repo root:

```bash
python3 - <<'PY'
import os, re, collections
BASES = ["infochat-provider/src/main/java/app/zcat/infochat/provider",
         "infochat-core/src/main/java/app/zcat/infochat/core"]
idx = {}
for base in BASES:
    for dp, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(".java"): idx.setdefault(f[:-5], os.path.join(dp, f))
FIELD = re.compile(r"^\s{4}(?:@Inject\s+)?(?:(?:private|protected|public|static|final|volatile)\s+)*"
                   r"([A-Z][A-Za-z0-9_]+)(?:<[^;=]*>)?\s+[a-z][A-Za-z0-9_]*\s*[;=]", re.M)
strip = lambda s: re.sub(r"//.*$", "", re.sub(r"/\*.*?\*/", "", s, flags=re.S), flags=re.M)
seen, order, q = set(), [], collections.deque(["DigestRenderer"])
while q:
    c = q.popleft()
    if c in seen or c not in idx: continue
    seen.add(c); order.append(c)
    for k in sorted(set(FIELD.findall(strip(open(idx[c], encoding="utf-8", errors="replace").read())))):
        if k in idx and k not in seen: q.append(k)
for c in order:
    if re.search(r"\bDataSource\b|\bgetConnection\s*\(",
                 open(idx[c], encoding="utf-8", errors="replace").read()):
        print(f"{c:28s} {idx[c]}")
PY
```

Current result: 13 classes in the collaborator graph, of which exactly **2**
touch a `DataSource`.

| Class | Reached via | Disposition |
|---|---|---|
| `LlmOutputSanitizer` | `renderSections` → `sanitize` (:553, :796) and `DisplayHeadline.of` (:837) | **the one site hardened** — `emitAuditRows` writes `LLM_OUTPUT_SANITIZED` and owes the spec's durability commitment |
| `PostReferenceEdgeSource` | `renderSections` → `ClusterTraversal.cluster` (:261) → `neighborsAmong` | **no hardening needed** — read-only parameterized SELECT, no write and no audit obligation, and it runs as the FIRST statement of `renderSections`, before any generative call and therefore before the slot deadline can fire. An interrupt reaching it aborts a render whose output is discarded anyway |

The other 11 (`SummaryProseGenerator`, `CategoryRollupGenerator`,
`TranslationPipeline`, `LlmTranslationProvider`, `TranslationCache`,
`ClusterTraversal`, `ClusterProminence`, `DigestCategorizer`, `BundleLoader`,
`DisplayHeadline`, `DigestRenderer` itself) do zero JDBC.

## Notes

- `security_relevant: true`: the promise at stake is `docs/spec/security.md`
  §Rate limiting. An unbounded orphaned render is a cost/DOS surface, and
  the deployment has no aggregate LLM ceiling to catch it — see the
  out_of_scope entry, and M1-758 where that gap is recorded at the spec
  site.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-763-digest-render-cancel-noop.md`
