# M1-698 redteam-multi disposition (kimi + opencode, 2026-07-26)

Auditor set per user request: kimi + opencode (single-sourced wrapper
`scripts/redteam-multi.sh`). Three audit rounds; this file records the
final disposition of every finding. Evidence packets:
`docs/plan/m1/redteam-multi/M1-698-2026-07-26/` (r1),
`.../-r2/` (r2), `.../-r3/` (r3).

## Findings and dispositions

### Round 1 (4 findings)
- **A (medium, kimi + opencode substantively agree)** — guard matched
  the call target by exact owner NAME (`MessagingAdapter.equals(owner)`),
  so a future caller typing its receiver as a concrete adapter subtype
  (`SimpleXAdapter`) or sub-interface compiled to a target owner ≠
  MessagingAdapter and was skipped. **REMEDIATED** r2: switched to
  `target.getOwner().isAssignableTo(MessagingAdapter.class)`; proved with
  a negative-control probe (`TempBypassProbe` calling `simplex.send`) that
  the guard now fails on a concrete-typed receiver.
- **B (low, kimi; opencode noted)** — reflective invocation
  (`Method.invoke` / `MethodHandle` / dynamic proxy) leaves no bytecode
  edge. **ACCEPTED residual** — deliberate-evasion shape; the threat
  model's adversaries are external (feed publishers, chat users, hostile
  LLM endpoints) and the codebase is trusted-reviewed. Recorded in the
  test Javadoc and the spec paragraph.
- **C (low, kimi)** — fail-loud sanity check only asserted the two
  allowlisted classes imported; a future partial ASM/JDK skew that
  dropped other classes while keeping the two readable would pass
  vacuously. **REMEDIATED** r2: added an imported-count == on-disk-`.class`
  count check derived from the production-classes resource URL
  (CWD-independent).

### Round 2 (2 findings; A and C confirmed closed)
- **R2-1 (medium, kimi)** — method references (`adapter::send`) compile to
  `invokedynamic`; ArchUnit exposes them only via `getMethodReferencesFromSelf()`,
  which the guard did not iterate. kimi verified empirically
  (`Integer::sum`, `MessagingAdapter::name`). Distinct from B: a method
  reference is idiomatic (`retryHelper.send(adapter::send, msg)`), an
  accidental shape. **REMEDIATED** r3: added a
  `getMethodReferencesFromSelf()` loop sharing the name/assignability/
  allowlist check; proved with a negative-control probe (`TempRefProbe`
  with `adapter::send` and `simplex::send`) that both shapes now fail.
- **R2-2 (low, opencode)** — guard scoped to the provider module;
  spec overclaimed "structural/unlandable in CI". **DISPOSITIONED** r3
  by softening the spec (see "Spec honest narrow" below).

### Round 3 (3 findings; all prior fixes confirmed still closed)
- **R3-1 (medium, opencode, AUDIT-EVASION)** — the spec paragraph claimed
  "enforcer rules block the [messaging-adapter] edge in every other
  module", but **infochat-core has no maven-enforcer `bannedDependencies`
  rule** (verified: only collector / llm-adapter / ssrf / messaging-adapter
  have it; the parent pluginManagement carries the version only). The
  spec claim was therefore FALSE for core. **DISPOSITIONED**: the claim
  is removed from the spec; the spec now states core's edge is
  convention-only and tracked as a follow-up. The structural fix (add
  the enforcer rule to infochat-core/pom.xml) is **out of this ticket's
  files_scope** → flagged as a follow-up ticket, not folded in here.
- **R3-2 (medium, kimi)** — helper indirection: a static helper
  `MessagingAdapters.send(adapter, msg)` in the messaging-adapter module
  + a provider call routed through it defeats both the owner-assignability
  filter (call targets the helper, not MessagingAdapter) and the
  provider-scoped import (helper's `adapter.send` edge is in another
  module). Interprocedural — a static edge guard cannot trace it.
  **ACCEPTED residual** (documented in the spec + test Javadoc); the
  census stays the backstop.
- **R3-3 (low, kimi)** — `GUARDED_METHODS` hardcoded; SPI growth (a new
  body-delivering method like `edit`) bypasses silently. **REMEDIATED** r3:
  added `spiSurfaceIsFullyClassified` — asserts MessagingAdapter's
  declared methods == guarded ∪ non-body, so the SPI cannot grow a method
  the guard does not classify (mirrors the match-set derivation CI check).

## Spec honest narrow (round 3)
The recurring findings across three rounds all sprang from one root
cause: the new spec paragraph overclaimed what a static bytecode-edge
guard can deliver ("totality", "structural", "unlandable in CI"). The
paragraph was rewritten to state precisely what the `mvn verify` gate
enforces — no provider class outside the two allowlisted may hold a
direct call or method-reference edge to the three outbound-body methods
— and to name the three residual routes (helper indirection,
sibling-module sender, reflection) as accepted documented residual risk
rather than claimed closed. The guard still ships real value: it makes
the most likely accidental bypass shapes build-breaking, and the census
stays the backstop for the residual routes.

## Follow-up ticket (out of files_scope)
- **infochat-core enforcer rule.** Add a `bannedDependencies` rule to
  `infochat-core/pom.xml` excluding `infochat-messaging-adapter`, matching
  the rules already present in collector / llm-adapter / ssrf /
  messaging-adapter, so the module-DAG property the guard relies on is
  enforced rather than convention-only for the core edge. Out of scope for
  M1-698 (`files_scope` is provider test + pom + security spec).

## Round 4 (converged)
- **opencode: CLEAN.**
- **R4-1 (low, kimi) — overload-collapsing:** the drift assertion and the
  call-edge match classify SPI methods by NAME only, so a future
  body-delivering overload reusing a non-body name (e.g.
  `setTyping(ContactId, OutboundDraft)`) leaves the name set identical,
  passes the drift assertion, and bypasses the call-edge guard. **ACCEPTED
  residual** per user decision — the common case (a genuinely new method
  name) IS caught by the drift assertion; only the unusual overload-reuse
  case slips, the spec already renounces totality, and the census stays the
  backstop. Recorded in the spec + test Javadoc.
- **R4-2 (kimi out-of-model) — direct-transport bypass:** provider code
  opening its own socket to the transport subprocess / Signal daemon RPC
  produces no `MessagingAdapter` edge at all. Deliberate/grossly-negligent,
  not accidental; outside the guard's accidental-drift mission. **Added** to
  the residual enumeration in the spec + test Javadoc per user decision.

## Final state
The guard (direct calls + method references + concrete-typed receivers +
SPI-name drift, scoped to provider main classes) ships with an honest,
narrower spec claim and five documented residual routes. `mvn verify` is
green (round-4 log at `target/m1-tick-test-M1-698-r4.log`). Proceeding to
review.

## Auditor performance note
kimi produced the deeper bytecode-shape findings (method-reference edge
model, empirical Integer::sum probe, SPI-drift) across rounds; opencode
produced the sharper infrastructure finding (missing core enforcer rule,
falsifying the spec claim) in round 3. The two were complementary —
neither model's finding set was a subset of the other's — which is the
case for multi-auditor cross-examination.
