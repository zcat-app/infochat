---
id: M1-603
title: "LlmRouterStartupGuard: a ModelTask with no usable route (missing base-url on a base-url-requiring provider) boots clean and fails 100% of calls silently at runtime — surface it at startup"
status: pending
created: 2026-07-10
last_updated: 2026-07-10
blocked_by: []
files_budget: 6
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardTest.java
  - docs/spec/llm.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The existing local-only conflict scan, the M1-577 provider/base-url/model
    mismatch scan, and router.assertAllTasksResolve()'s registered-provider /
    language-resolvability checks. This ticket ADDS one missing-route check
    alongside them; it does not rework, merge, or change the posture of the
    existing three. In particular the M1-577 mismatch scan's deliberate "skip a
    task whose base-url is empty" (mismatchFinding early-return) stays — the new
    check is what covers the empty-base-url case that comment defers to "first
    call", so the two are complementary, not a rewrite.
  - >-
    The config-GENERATION tooling (prod/switch-llm.sh, prod/scripts/4-llm.sh).
    Those already list every task in LLM_TASKS (classifier added by M1-599), so a
    fresh wizard/regen already writes a classifier block. The gap this ticket
    closes is an ALREADY-generated operator config that predates a new ModelTask;
    the guard validates the config the app actually boots with, which the tooling
    cannot retroactively fix. Do not touch the scripts.
  - >-
    The operator runtime config on any host (prod/runtime/application.properties)
    and the one-off corpus reclassification that recovered the poisoned posts —
    both are host-local operator actions already done manually (2026-07-10),
    gitignored, not repo artifacts.
  - >-
    Any change to how ModelTask, the providers, or LlmRouter RESOLVE a route at
    call time. The runtime routing is correct; the fault is only that a route
    that can never work is not SURFACED at startup. Do not change routing
    behavior, add a call-time retry/alert, or change the {unknown} graceful
    fallback in the eval workers (that fallback is correct — this ticket makes the
    misconfig visible BEFORE it fires, it does not change the fallback).
acceptance:
  - >-
    LlmRouterStartupGuard detects, at @PostConstruct, any ModelTask whose EFFECTIVE
    route cannot possibly succeed because a required base-url is absent — concretely
    a task whose effective provider is openai-compatible (the base-url-requiring
    provider) with no per-task base-url configured and no usable default. The
    detection is derived from ModelTask.values() (like the existing scans) so a
    future NEW task is covered with no guard edit, and it names the offending task
    plus the exact missing config key(s) (e.g. infochat.llm.classifier.base-url) in
    the log line — the same actionable shape the M1-577 mismatch lines use.
  - >-
    A new test in LlmRouterStartupGuardTest reproduces the M1-597 classifier gap
    with a hand-rolled config snapshot: an otherwise-DeepSeek remote-llm config that
    OMITS the classifier block (no infochat.llm.classifier.base-url / .provider)
    while the other six tasks are configured, and asserts the guard flags exactly
    the classifier task (and nothing else). A companion test asserts NO false
    positive for the three supported shapes the existing guard already passes:
    local Ollama (loopback base-url), a correct Anthropic remote, and a correct
    openai-compatible remote with a base-url present.
  - >-
    The posture (WARN-advisory vs fail-fast) is decided in the design and documented
    in docs/spec/llm.md §Per-task routing rules. Rationale to weigh: the classifier
    gap degraded silently for a whole session precisely because the per-call failure
    was WARN-level and unnoticed, which argues for fail-fast (or reusing the existing
    infochat.llm.mismatch-guard.fail-fast opt-in) on a task that PROVABLY cannot
    route; balanced against the guard's established advisory-by-default bias. Pick
    one, state why, and keep it consistent with the M1-577 precedent.
  - >-
    All existing LlmRouterStartupGuard tests (Redaction, LocalOnly, Loopback,
    KeyDerivation, and the mismatch cases) stay green — the new check must not
    change any existing verdict — and mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      LlmRouterStartupGuardTest cases: (a) classifier-block-omitted remote-llm
      snapshot flags exactly CLASSIFIER; (b) no-false-positive for local-Ollama /
      Anthropic-remote / openai-compatible-remote-with-base-url. If the design
      chooses fail-fast, a boot-abort assertion mirroring the existing
      ProviderModelMismatch fail-fast test.
  modifies:
    - >-
      LlmRouterStartupGuard.java — add the missing-required-base-url detection
      (pure static detector + a name/key-listing log line), wired into
      @PostConstruct alongside the existing scans, deriving keys from
      ModelTask.values() via the existing baseUrlKeyFor/providerKeyFor helpers.
  preserves:
    - >-
      Every existing guard verdict and log-redaction behavior; the three supported
      route shapes still pass; the M1-577 mismatch scan's empty-base-url skip stays
      (the new check owns that case).
    - >-
      The eval workers' graceful {unknown} fallback and the runtime routing — the
      guard only surfaces the misconfig earlier, it does not change behavior on a
      correctly-configured deployment.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs: []
redteam_findings: []
redteam_audits: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
---

# M1-603: fail LOUD when an LLM task has no usable route

## The problem, in plain English

The app runs several LLM "tasks" — a security check, tagger, entity extractor,
**classifier**, summarizer, chat, and translator. Each task reads its own block
of settings (which server URL to call, which provider dialect, which model) from
the operator's `application.properties`. If a task's block is missing, the task
silently falls back to a built-in default provider.

On 2026-07-10, after rebuilding the running deployment, **every news post came
back classified as `unknown`**. The cause was not a code bug and not the model
misbehaving. It was this:

- The **classifier** task was added to the code in M1-597.
- But the operator's config file on that host had been generated *earlier*
  (2026-07-06), so it had **no `infochat.llm.classifier.*` block** — it only
  configured the six tasks that existed when it was written.
- With no block, the classifier fell back to the profile's default provider
  (Anthropic), which is **not reachable in that deployment**. Every classifier
  call failed to connect (`ConnectException` / `ClosedChannelException`), and the
  worker did the safe thing: it gave up gracefully and stamped the post
  `unknown`.
- Nothing looked broken. The app booted fine. There was no error shown to any
  operator or user. The only symptom was ~4,700 posts quietly labelled `unknown`
  — and the failure log lines were WARN-level, so they scrolled by unnoticed for
  an entire session.

In short: **a task that can never succeed looks identical, at startup, to one
that is perfectly configured.** The misconfiguration only reveals itself as
silently degraded data, one post at a time, long after boot.

This is the *exact* class of failure the existing `LlmRouterStartupGuard` was
built to prevent (see its M1-577 history: it already catches a provider pointed
at the wrong endpoint that would HTTP-400 every call). It simply has a blind
spot: its consistency scan **deliberately skips any task whose base-url is
empty**, with a comment that a missing base-url "surfaces separately at first
call." For a silent ingest task, "first call" is a per-post fallback to
`unknown`, not a visible error — so it never really surfaces.

## The fix, in plain English

Teach `LlmRouterStartupGuard` to notice the one case it currently skips: **a task
whose effective route cannot possibly work because a required piece is missing** —
specifically, a task that resolves to the base-url-requiring provider
(`openai-compatible`) but has **no base-url configured**. When the guard finds
one, it says so at startup, naming the task and the exact missing setting (e.g.
`infochat.llm.classifier.base-url`), instead of letting the app boot into a state
where that task fails 100% of the time in silence.

The detection reuses the guard's existing machinery — it iterates
`ModelTask.values()` and uses the same key-derivation helpers — so this is not
just a classifier fix: **any future task added to the enum is covered
automatically**, which is what would have caught the classifier gap the day
M1-597 shipped.

The one open design choice, left to the plan/clarity step, is the **posture**:
fail the boot outright, or log loudly and continue (WARN), or reuse the existing
`infochat.llm.mismatch-guard.fail-fast` opt-in. The evidence leans toward failing
fast for a task that *provably* cannot route — a WARN is exactly what got missed
this time — but the existing guard is advisory-by-default, so the choice should
be made and justified against that precedent, not assumed.

## Why a startup guard and not something else

- **The config-generation tooling is already correct** (`switch-llm.sh` /
  `4-llm.sh` list every task since M1-599), so future configs get the block. But
  that does nothing for a config generated *before* a new task existed — which is
  precisely what bit us. The guard validates the config the app *actually boots
  with*, which is the only place that catches a stale operator file.
- **A call-time alert** (e.g. "this worker has failed N times in a row") would
  also have caught it, but reactively and after wasting work; failing at boot is
  earlier and cheaper. (Recorded as an alternative, not chosen.)

## Scope

One production file (`LlmRouterStartupGuard`), its test, and the two LLM docs.
The existing local-only, mismatch, and resolvability checks are untouched; the
config tooling, the host runtime config, and the already-completed corpus
reclassification are all explicitly out of scope (see `out_of_scope`).
