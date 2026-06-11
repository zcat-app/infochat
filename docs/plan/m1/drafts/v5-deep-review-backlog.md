# Deep-review v5 — backlog (items not ticketed 2026-06-11)

Source: `deep-code-review/v5/UNIFIED-REPORT.md` §5 "Cross-lens observations"
+ §6 corrections + the deliberately staged-out remainders. The v5 findings
were cut into tickets **M1-284..M1-312** on 2026-06-11 (HIGHs U-01..U-06 as
M1-284..M1-289; mediums/bundles M1-290..M1-310; sweeps M1-311/M1-312). This
file carries what deliberately did NOT get a ticket, so it isn't lost.
Everything here was verified by the unified report unless marked otherwise;
re-verify the premise at the source file before drafting any ticket
(premise-fail rule).

## Cross-lens observations (report §5 — "no ticket yet")

- `quarantine_review` shared-cursor catch-up can skip a lost
  post-NEEDS_REVIEW event if a later-timestamped quarantine event advanced
  the cursor (fable-5/07 obs). M1-309 touches the listeners — its Notes
  forbid fixing this silently there.
- `LlmRouter.assertAllTasksResolve()` forces both services to carry every
  task's LLM config (Collector ships Provider-task blocks). Design question,
  not a defect.
- Many callers construct their own `SsrfGuardedHttpClient` instead of
  injecting the M1-277 shared producer; each owns a never-closed JDK
  HttpClient (opus-47/03 obs). Consolidation ticket candidate after M1-291.
- Collector inserts `post.title` unbounded — upstream half of U-66's
  byte-cap story (fable-5/07 obs).
- `SignalJsonRpcClient`'s reader is a platform thread in a virtual-threads
  project (mimo/5 obs).
- **Time-sensitive:** V30 partitions are provisioned only through
  **2026-08**; `PartitionCreator` reliability is the durable mechanism
  (opus-48/02 obs). If no ticket has hardened/verified PartitionCreator
  by late July 2026, escalate this line.
- `eval-queue` `@Broadcast` + startup-priority subscription-timing question
  (opus-48/06 obs — honestly deferred; SmallRye semantics unverified).

## Staged-out remainders from ticketed findings

- U-51 remainder: `reply()` ×33, `quoteJsonString()` ×3,
  `fuzzySuggest()`/`sharedPrefixLength()` ×2 (textually diverged) — M1-309
  deliberately shipped only the tokenizer; revisit after it lands.
- U-30 stretch: profile-driven `maxInboundMessageBytes` threading if
  M1-294 takes the minimal single-source + design-amendment fork.
- U-03 optional: scheduled reconciliation for operator-side disables of
  stream sources (M1-286 Notes point here).
- opus-47 CT1: CI guard against literal-append on reply paths (would have
  caught U-43/44/45 mechanically). Tooling idea, evaluate after M1-303.
- U-13 alternative: error-body preview behind an explicit unsafe-debug
  config if operators turn out to need it (M1-292 ships plain removal).

## Verified, leave-as-is (do not re-ticket)

- `EmbeddingResult` redundant construction clone — opus-48/04#F1 itself
  says "no change recommended".
- Writable test-seam fields on `ReadyPromoter`/`PriceSnapshotStore` —
  opus-48/06#F5 Option B ("leave as-is") recorded as defensible.
- `providerName()` underscore-heuristic couples to ArC proxy naming
  (deepseek/04#F1) — robustness note only.
- `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` verb rename — needs a V5
  CHECK-set migration; M1-312 adds the clarifying comment instead.

## Rejected/corrected findings (report §6 — do NOT ticket as written)

- mimo "V21 procedures missing NOTIFY" — FALSE (V32/V48 chain has it).
- deepseek "V10 comments reference a missing migration" — FALSE premise.
- opus-48 delimiter-regex headline — overstated; residual is a small
  hardening tweak for `>`-bearing interiors slipping the `[^>]*` class.
  Optional rider on any future stage-1 regex ticket.
- opus-47 PinHandle.release fix-as-written fails NullAway — the throw-on-
  null residual is ticketed inside M1-291.
- opus-48 `/retry` token order — pre-empted by in-code rationale; residual
  ticketed in M1-306.
- opus-48 "three dead Duration fields" — deliberate fail-fast bindings;
  resolved inside M1-300.
- deepseek side-errors (fabricated minEditInterval values, paraphrase-as-
  quote on /summary availability, wrong interrupt-flag-semantics claim) —
  noted in the affected tickets (M1-293, M1-288, M1-291).
