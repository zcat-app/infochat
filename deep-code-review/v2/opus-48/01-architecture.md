# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-06 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — cross-cutting (poms + docs/design/09-reference.md:41-42) — the module DAG's most important guard ("collector MUST NOT depend on messaging-adapter") is documented as build-enforced and CI-verified, but no enforcer plugin or CI config exists; the DAG holds only by convention.
- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting (ReEvaluationJob.java:162-168 + QuarantineReviewListener.java:143-152) — a `NEEDS_REVIEW` transition produces two independent admin notifications (Collector-direct + Provider-listener), contradicting the spec's single-driver assignment in architecture.md §Inter-service communication.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-provider/.../outbox/QuarantineReviewListener.java:67,143-188 — the listener collapses two distinct `error_class` values (`quarantine_review.pending` vs `quarantine_review.needs_review`) into one fixed throttle key, drifting from the spec's "coalesced per (channel, error_class)" rule, via a re-implemented copy of the shared `ThrottledAdminNotifier`.
- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting (provider/config/InfochatProfile.java + collector/config/InfochatProfile.java + properties) — the active hardware profile has two parallel sources of truth (`quarkus.profile` via a duplicated `InfochatProfile` enum, and the separate `infochat.profile.label` property read with two different defaults).
- [medium] MAINTAINABILITY-RULES-DRIFT — docs/design/09-reference.md:30 — the normative module table claims `infochat-core` is "Pure Java; no Quarkus, no I/O," but core ships JDBC-and-Quarkus-coupled beans.

## Detail

### F1. The module-DAG guard "collector ↛ messaging-adapter" is claimed to be build-enforced but isn't

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** cross-cutting (see CURRENT-CODE) — `pom.xml`, all six module poms, `docs/design/09-reference.md:41-43`
- **Surface:** DAG

**Current code:**

`docs/design/09-reference.md:41`:

```
- `infochat-collector` MUST NOT depend on `infochat-messaging-adapter`. Enforced
  by the parent POM and verified in CI; an attempt to add the dependency fails
  the build with a clear error. This is the architectural guarantee that the
  Collector cannot accidentally become user-facing.
```

`docs/design/09-reference.md:42`:

```
- The three sibling shared modules — `infochat-ssrf`, `infochat-llm-adapter`,
  and `infochat-messaging-adapter` — MUST NOT depend on each other.
```

Grep for any enforcement mechanism across every pom:

```
pattern: enforcer|RestrictImports|banned|ConstrainedDependency|maven-enforcer|import-control
Found 0 total occurrences across 0 files.
```

The parent `pom.xml` `<build>` block contains only `maven-compiler-plugin` (NullAway/Error Prone) in `<pluginManagement>`. There is no `maven-enforcer-plugin`, no `bannedDependencies` rule, and no `.github/workflows/` directory in the repo (`Glob .github/workflows/*` → no files).

**Why this is wrong / suboptimal / risky:**

The design file explicitly labels itself "normative for module dependencies (the build enforces the DAG)" (`09-reference.md:10`). The architecture's first stated reason for the two-service split is blast radius — "A compromised or malfunctioning fetcher cannot reach users directly" (`architecture.md` §Service split). The Collector-never-user-facing property is the load-bearing invariant behind that guarantee, and the design promises it is mechanically enforced ("fails the build with a clear error").

In reality the only thing stopping `infochat-collector` from depending on `infochat-messaging-adapter` is that its pom happens not to list the dependency. A future ticket that adds the dependency — to reuse a DTO, a capability enum, or a formatting helper — would compile and pass `mvn verify` cleanly. The documented "clear error" never fires. The same gap applies to the sibling-isolation rule (line 42): nothing prevents `infochat-llm-adapter` from depending on `infochat-ssrf`. A claimed-but-absent guard is worse than no guard, because reviewers and future tickets trust the documented enforcement and will not hand-check the DAG.

**Recommended fix:**

Add a `maven-enforcer-plugin` execution to the parent `pom.xml` `<pluginManagement>` (activated per-module, or a single reactor-level rule) with a `bannedDependencies` rule:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>enforce-module-dag</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <bannedDependencies>
                        <excludes>
                            <!-- collector must never see the messaging adapter -->
                            <exclude>app.zcat.infochat:infochat-messaging-adapter</exclude>
                        </excludes>
                        <message>infochat-collector MUST NOT depend on infochat-messaging-adapter (docs/design/09-reference.md §9.1): the Collector is headless and must never become user-facing.</message>
                    </bannedDependencies>
                </rules>
                <fail>true</fail>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Bind the `infochat-messaging-adapter` exclude only in the collector's pom (the exclude set is per-module), and add the symmetric sibling-isolation excludes to each of the three shared modules. If the intent is also "verified in CI," either add a CI workflow or strike the "verified in CI" clause from the design note so it stops promising a mechanism that does not exist.

**Reasoning:**

`maven-enforcer-plugin` `bannedDependencies` fails the `validate` phase with exactly the "clear error" the design note promises, including transitive pulls (a module that drags messaging-adapter in transitively is caught too — a hand-written pom check is not). This converts the most important architectural invariant from convention to a machine-checked gate, matching how the project already treats nullability (NullAway) and test-integrity (the reviewer gates). It is the cheapest possible way to make the documented guarantee true.

**Trade-offs:**

Adds one plugin execution and a few seconds to the `validate` phase. No runtime cost. The rule must be kept in sync if the DAG legitimately changes — but that is the point: a deliberate DAG change should require editing the guard.

---

### F2. `NEEDS_REVIEW` transitions fire two independent admin notifications across two modules

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/.../eval/reeval/ReEvaluationJob.java:152-171` and `infochat-provider/.../outbox/QuarantineReviewListener.java:143-152`
- **Surface:** NOTIFY / audit

**Current code:**

`ReEvaluationJob.transitionToNeedsReview` (collector) both emits the `quarantine_review` NOTIFY *and* fires its own admin notification:

```java
quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.POST,
    candidate.postId(), QuarantineNotifyEmitter.NewStatus.NEEDS_REVIEW);
// ... (transaction commits) ...
throttledAdminNotifier.notifyOnce(
    ERROR_CLASS_REEVAL_CAP_EXHAUSTION,
    ERROR_CLASS_REEVAL_CAP_EXHAUSTION,
    "Re-eval cap exhausted for post_id=" + candidate.postId());
```

`QuarantineReviewListener.handleEvent` (provider) consumes that same NOTIFY and *also* fires an admin notification:

```java
boolean advanced = providerStateDao.advanceCursor(
        CHANNEL, eventTime, targetKind, targetId.toString());
if (advanced && isActionable(newStatus)) {   // isActionable: PENDING or NEEDS_REVIEW
    fireAdminNotification(targetKind, targetId, newStatus);
}
```

**Why this is wrong / suboptimal / risky:**

`architecture.md` §Inter-service communication is explicit about who owns the notification:

> Consumer behavior: the Provider drives the throttled admin notifier (`security.md` §Failure handling) on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions — these are the two transitions that require admin attention.

The spec assigns the `NEEDS_REVIEW` admin notification to the Provider, reached over the `quarantine_review` channel. The Collector's `ReEvaluationJob` independently fires a second notification (`ERROR_CLASS_REEVAL_CAP_EXHAUSTION`) for the same transition. The two notifications use different throttle keys in `admin_notification_state` and run in different processes, so they do not coalesce — every cap-exhaustion event pages the operator twice with two different `ADMIN-NOTIFY` log lines for one logical event. This is the kind of duplicate-signal noise the spec's per-`(channel, error_class)` coalescing rule (`security.md` §Failure handling) exists to prevent, and it splits one spec-defined responsibility across two modules.

Note the `re_eval_released` notification in the same class (the BENIGN auto-release path, `ReEvaluationJob.applyBenign`) is *not* a drift: `security.md` §Re-evaluation job explicitly commits to a Collector-side `re_eval_released` notification, and the `quarantine_review` listener does not fire on `BENIGN_CLOSED`. Only the `NEEDS_REVIEW` path double-fires.

**Recommended fix:**

Pick one owner per the spec and remove the other. Since `architecture.md` names the Provider as the driver for `NEEDS_REVIEW`, drop the Collector-side `notifyOnce` in `transitionToNeedsReview` and let the `quarantine_review` NOTIFY → `QuarantineReviewListener` be the sole notification path:

```java
quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.POST,
    candidate.postId(), QuarantineNotifyEmitter.NewStatus.NEEDS_REVIEW);
// NEEDS_REVIEW admin notification is the Provider's job
// (architecture.md §Inter-service communication); the
// quarantine_review NOTIFY above is what drives it.
LOG.infof("ReEvaluationJob: cap exhausted for post_id=%s — transitioned to NEEDS_REVIEW",
    candidate.postId());
```

**Reasoning:**

Single-owner notification matches the spec, halves the operator's page volume for the most security-relevant transition, and keeps the coalescing window meaningful. The Provider path is the correct survivor because it also covers the restart/catch-up case via the reconciler and because the spec names it explicitly.

**Trade-offs:**

If the Provider is down when the Collector transitions a post to `NEEDS_REVIEW`, the live NOTIFY is dropped and the admin is not paged until the Provider restarts and the reconciler advances the cursor (the reconciler advances the cursor but does *not* re-fire missed notifications — see `QuarantineReviewReconciler` javadoc). The admin still sees the backlog on the next `/quarantine list`. If that gap is judged unacceptable, the alternative is to make the Collector the sole owner instead (see Option B) — but that contradicts the current spec text and would require a spec amendment.

**Alternative options:**

- **Option A** (recommended) — remove the Collector-side `notifyOnce`; Provider listener is the sole notifier, per spec.
- **Option B** — make the Collector the sole owner (drop the listener's `fireAdminNotification` for `NEEDS_REVIEW`) and amend `architecture.md` §Inter-service communication to reassign the duty. Pros: no dependence on Provider liveness for the page. Cons: contradicts the current spec; the `quarantine_review` cursor still must advance, so the listener stays half-wired; loses the catch-up symmetry the channel was designed for.

---

### F3. `QuarantineReviewListener` coalesces all actionable statuses into one throttle key

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-provider/.../outbox/QuarantineReviewListener.java:67,87-106,143-188`
- **Surface:** NOTIFY / audit

**Current code:**

```java
private static final String ADMIN_NOTIFY_KEY = "quarantine-review-actionable";
...
private void fireAdminNotification(String targetKind, UUID targetId, String newStatus) {
    ...
    String errorClass = "quarantine_review." + newStatus.toLowerCase();
    ...
    try (PreparedStatement ps = conn.prepareStatement(getUpsertSql())) {
        ps.setString(1, ADMIN_NOTIFY_KEY);   // <-- constant key for PENDING and NEEDS_REVIEW alike
        ps.setString(2, errorClass);
        ...
```

The inline UPSERT conflicts on `notification_key` only:

```java
sql = "INSERT INTO admin_notification_state ... VALUES (?, ?, ?, 1, 0, ?) "
        + "ON CONFLICT (notification_key) DO UPDATE SET ... "
        + "WHERE admin_notification_state.last_notified_at + " + interval
        + " <= EXCLUDED.last_notified_at "
        + "RETURNING notification_key";
```

**Why this is wrong / suboptimal / risky:**

`security.md` §Failure handling commits that "Admin notifications are coalesced per `(channel, error_class)`", and §Throttled NEEDS_REVIEW notifications repeats it for this exact case. The throttle granularity is supposed to be `error_class`. Here the conflict/throttle bucket is the fixed string `quarantine-review-actionable`; `error_class` is written into the row but never participates in the throttle predicate. The result is that a `PENDING` quarantine insert (Stage-1 hit) and a `NEEDS_REVIEW` post transition (re-eval gave up classifying hostile content) share a single 1-hour throttle bucket. A burst of Stage-1 quarantines therefore suppresses the `NEEDS_REVIEW` page — collapsing two semantically distinct admin signals the spec deliberately keeps separate. `NEEDS_REVIEW` is the durable "the system gave up; this stays hidden until an admin acts" signal; masking it behind unrelated `PENDING` traffic is precisely the failure the per-`error_class` rule prevents.

The root cause is that this class re-implements the shared `ThrottledAdminNotifier` (infochat-core, reachable from the Provider) inline. The shared notifier's contract puts the discriminator in the *key* ("Caller's responsibility to pick a low-cardinality key", javadoc on `notifyOnce`), and other callers follow it (e.g. `FetchScheduler` uses per-source keys like `asset-source-failed:zcash:price`). The inline copy drifted from that convention and hard-coded one key.

**Recommended fix:**

Delete the inline UPSERT and `getUpsertSql()` and route through the shared notifier, encoding the status in the key so each `error_class` gets its own bucket:

```java
@Inject ThrottledAdminNotifier adminNotifier;
...
private void fireAdminNotification(String targetKind, UUID targetId, String newStatus) {
    String key = "quarantine-review:" + newStatus.toLowerCase();   // pending | needs_review
    String errorClass = "quarantine_review." + newStatus.toLowerCase();
    adminNotifier.notifyOnce(key, errorClass,
        "Quarantine review action needed: " + targetKind + " " + targetId + " → " + newStatus);
}
```

**Reasoning:**

Per-status keys restore the spec's `(channel, error_class)` coalescing granularity and stop a Stage-1 quarantine flood from masking a `NEEDS_REVIEW` page. Reusing the core notifier removes a second, subtly-different copy of the throttle UPSERT (the inline copy also lacks the core notifier's input sanitization and degraded-DB fallback in `notifyOnce`), eliminating the drift surface entirely and shrinking the class.

**Trade-offs:**

`admin_notification_state` gains one extra row (two stable keys instead of one). Cardinality stays bounded and tiny — the opposite of a concern. None otherwise; the fix is strictly closer to spec.

---

### F4. The active hardware profile has two parallel sources of truth

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** property

**Current code:**

`infochat-collector/.../config/InfochatProfile.java:26-32` (and the byte-identical `infochat-provider/.../config/InfochatProfile.java`) argues against a separate profile key, and resolves from the Quarkus profile chain:

```java
 * <p><b>Why no separate {@code infochat.profile} key.</b> ... Introducing a
 * separate key would create two sources of truth for the active profile; this
 * enum reuses the built-in and validates that the active Quarkus profile chain
 * contains one of the four allowed names.
```

Yet a separate `infochat.profile.label` property exists and is read in three places with two different defaults:

```
infochat-collector/.../eval/stage2/StartupReleaseOnStage2FailureWarn.java:82
    @ConfigProperty(name = "infochat.profile.label", defaultValue = "unknown")
infochat-provider/.../command/StatusCommandHandler.java:61
    @ConfigProperty(name = "infochat.profile.label", defaultValue = "laptop")
infochat-provider/.../summary/EligiblePostQuery.java:67
    @ConfigProperty(name = "infochat.profile.label", defaultValue = "laptop")
```

The provider copy of the enum also carries a stale promise:

```java
 * <p>This file is duplicated between Collector and Provider in v1; the
 * duplication goes away once {@code infochat-core} lands in M1-007a.
```

**Why this is wrong / suboptimal / risky:**

There are now two mechanisms that answer "what profile am I running?": the `InfochatProfile` enum (reads `quarkus.profile` / the SmallRye profile chain) and the `infochat.profile.label` property (set per-profile as `%laptop.infochat.profile.label=laptop`, etc., and read by three beans). The enum's own javadoc explicitly warns that a separate key "would create two sources of truth" — and that is exactly what `infochat.profile.label` is. They can disagree: if an operator sets `quarkus.profile=vps` but forgets the (separately-maintained) `%vps.infochat.profile.label`, the base files set no `infochat.profile.label` at all, so the readers fall through to their `@ConfigProperty` defaults — and those defaults are inconsistent (`"unknown"` in one bean, `"laptop"` in two). A `/status` reply could report `laptop` while the Stage-2 warning bean reports `unknown`, on the same VPS deployment.

Secondarily, the `InfochatProfile` enum is duplicated verbatim across both services. The provider copy's javadoc says the duplication "goes away once `infochat-core` lands in M1-007a"; `infochat-core` is the first module in the reactor and has long since landed, so the promised consolidation never happened. The four-profile name set (`laptop`/`vps`/`pi`/`remote-llm`) is a cross-module contract now encoded in three independent places (two enum copies + the `%profile.` property prefixes).

**Recommended fix:**

1. Make `infochat.profile.label` derive from the single source rather than being a hand-maintained parallel key — produce it from `InfochatProfile.resolveOrThrow(config.getProfiles())` via one CDI producer, so the three readers inject the resolved value instead of a default-bearing `@ConfigProperty`. If a literal property is preferred for `/status` rendering, set it once in a producer, not per-profile in every properties file.
2. Move the value half of `InfochatProfile` (the enum constants, `fromConfigName`, `resolveOrThrow`) into `infochat-core` so both services share one copy; keep only the Quarkus-coupled `Validator` inner class in each service if needed.
3. Make the three readers' defaults agree (or remove the default and let resolution fail loudly, matching `InfochatProfile.resolveOrThrow`'s fail-fast posture).

**Reasoning:**

Collapsing to one source of truth is what the enum's own javadoc demands; the `infochat.profile.label` key reintroduced the very split the enum was written to avoid. Sharing the enum from core removes the third encoding of the profile-name contract and deletes the stale "duplication goes away" comment by actually doing it.

**Trade-offs:**

Moving the enum to core requires core to keep its (already-present) Quarkus compile dependency — see F5; this is consistent with how `ThrottledAdminNotifier` and `AbstractInstanceLockGuard` already live in core. No runtime cost.

---

### F5. Design note claims `infochat-core` is "Pure Java; no Quarkus, no I/O" — the code is neither

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `docs/design/09-reference.md:30,39`
- **Surface:** DAG

**Current code:**

`docs/design/09-reference.md:30` (the normative module table):

```
| `infochat-core` | (none) | Domain entities, schema-level types, shared
utilities. Pure Java; no Quarkus, no I/O. |
```

`docs/design/09-reference.md:39`:

```
- `infochat-core` MUST stay free of Quarkus, JAX-RS, and Hibernate.
  Test-friendly and reusable.
```

Contradicted by core's own production code:

```
infochat-core/.../core/notifier/ThrottledAdminNotifier.java   -> @Inject DataSource, JDBC UPSERT (I/O), @ConfigProperty
infochat-core/.../core/startup/AbstractInstanceLockGuard.java:3  import io.quarkus.runtime.Quarkus;
infochat-core/.../core/startup/AbstractInstanceLockGuard.java:4  import io.quarkus.runtime.Startup;
infochat-core/.../core/log/Redactor.java:3                       import io.quarkus.logging.LoggingFilter;
```

and by `infochat-core/pom.xml`, which carries `quarkus-core`, `microprofile-config-api`, CDI, and JDBC dependencies (provided scope).

**Why this is wrong / suboptimal / risky:**

`09-reference.md` declares itself "normative for module dependencies" (line 10). The module table tells every future ticket what is allowed to live in `infochat-core`. The "Pure Java; no Quarkus, no I/O" / "MUST stay free of Quarkus" claim is false today: `ThrottledAdminNotifier` was deliberately relocated into core (per its own javadoc) and performs JDBC I/O under CDI/MicroProfile, and `AbstractInstanceLockGuard`/`Redactor` import `io.quarkus.*`. A developer who trusts the note will either wrongly reject placing legitimately-shared infrastructure in core (forcing more duplication like F4's `InfochatProfile`), or notice the contradiction and lose confidence in the normative table. Either way the documented contract no longer describes the code.

**Recommended fix:**

Update `09-reference.md` to describe the actual constraint. Core is not "pure Java, no I/O"; it is "no Quarkus *extensions* in runtime scope (so downstream apps own their extension set); Quarkus/CDI/JDBC APIs allowed at provided scope; no JAX-RS, no Hibernate." For example:

```
| `infochat-core` | (none) | Domain entities, schema-level types, shared
infrastructure (audit writer, throttled admin notifier, instance-lock
guard). Quarkus/CDI/MicroProfile/JDBC APIs are compile-time (provided)
only — no Quarkus *extensions* in runtime scope, no JAX-RS, no Hibernate. |
```

and replace line 39's "MUST stay free of Quarkus" with the provided-scope-only wording the pom already enforces.

**Reasoning:**

The note should describe the invariant the build actually holds (the `infochat-core/pom.xml` comment already articulates the real rule: "NO Quarkus extensions live here... downstream Quarkus apps stay in charge of which extensions their runtime pulls in"). Aligning the normative table with that rule removes a false constraint and makes F4's enum-consolidation obviously permissible.

**Trade-offs:**

None — this is a documentation correction to match shipped code. If the project instead wants the original purity to be *true*, that is a much larger refactor (relocate `ThrottledAdminNotifier` and the startup guards out of core), which contradicts the deliberate M1-082 relocation and is not warranted.

## Synthesizer-relevant observations

- The `new_price_snapshot` channel has no production consumer in `infochat-provider/src/main` (only a test asserts the round-trip). This is consistent with spec: the Provider cache is explicitly optional and the table read is the correctness guarantee (`commands.md` §Asset commands), so the absent listener is not a finding.
- The `new_post` payload contract is honoured by both producers (`ReadyPromoter` inline JSON and the `approve_quarantine` procedure's `jsonb_build_object`) and the `NewPostListener` parser; the `to_jsonb(timestamptz)` → `Instant.parse` round-trip is covered by `QuarantineProcedureNotifyIT.approveNewPostPayplaysAsIso8601Instant`, so the historical V25 format bug (documented in V32) is genuinely closed — not a finding.
- The `supportsMarkdownLinks == false` v1 invariant IS enforced at startup (`AdapterRegistry` gate 3 throws), and all three adapters declare it false — the capability-flag surface is sound where checked.
