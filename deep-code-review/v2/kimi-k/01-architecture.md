# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-07 00:57
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:235-239 — the spec-committed bootstrap-admin @Startup bean does not exist; a fresh deployment has zero admin rows and cannot mint its first admin or invite in-band.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconciler.java:28-31 — the `quarantine_review` channel's documented correctness mechanism (high-water-mark catch-up) does not deliver the channel's only side effect; missed PENDING / NEEDS_REVIEW events never reach the admin notifier, and the live path can permanently skip actionable events.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java:54-70 — Invariant 6's age-out half is unimplemented: no partition-drop mechanism exists anywhere, while docs/design/02-schema.md §2.4.4 and docs/design/07-deployment.md describe a nightly `partition_pruner` with daily partitions and 4/7/30-day horizons.
- [high] MAINTAINABILITY-RULES-DRIFT — docs/spec/security.md:1032-1034 vs docs/spec/schema.md:587 — spec-internal contradiction: §DB roles grants the Collector `UPDATE` on `price_snapshot` while §Operational commits the table is "INSERT-only; no updates"; V17 implements the wider grant and no code uses it.
- [medium] MAINTAINABILITY-RULES-DRIFT — docs/design/09-reference.md:41 + pom.xml — the DAG guard "collector MUST NOT depend on messaging-adapter" is documented as parent-POM-enforced and CI-verified; no enforcer plugin and no CI configuration exist, so the guard holds by convention only.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java:34 — `TranslationProvider` lives in infochat-messaging-adapter (impl in infochat-provider), contradicting docs/spec/llm.md §SPI shape and docs/design/09-reference.md / 05-llm-and-embeddings.md, which place it in infochat-llm-adapter.
- [medium] MAINTAINABILITY-RULES-DRIFT — docs/design/07-deployment.md:65,103,119,135,310 — the operator runbook documents a config key `infochat.profile=` that deliberately does not exist (M1-005 decision: `quarkus.profile` is the mechanism); three other design notes repeat it.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java:37 — the spec-committed progress-notification pipeline (messaging.md §Progress notifications, architecture principle 7) has zero production consumers in the completed M1 build: no impl of `ProgressNotifier`, and no production reader of `supportsMessageEdit`, `minEditInterval`, `supportsTypingIndicator`, or `supportsCodeFormatting`.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java:13-18 — the Fetcher SPI does not carry the output-type discriminator docs/spec/architecture.md §Ingest SPIs commits to; asset fetching is a parallel non-SPI path, so the spec sentence is false against the code.
- [low] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — 752 hand-written `@NonNull` annotations across 171 main-source files contradict engineering rule §7a ("`@NonNull` is no longer written by hand"), blurring the null-marked-package convention on every SPI signature.

## Detail

### F1. The spec-committed bootstrap-admin seeding bean does not exist; a fresh deployment cannot create its first admin in-band

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** critical
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:227-262 (the only code that touches the bootstrap-admin property)
- **Surface:** trust-boundary

**Current code:**

```java
// AdapterRegistry.java:227-239
// Gate 7: per-adapter bootstrap admin union non-empty per
// docs/spec/deployment.md §Operator inputs item 2 +
// §Bootstrap behavior on startup. Each enabled adapter MAY
// declare `infochat.adapters.<name>.admin=<contact-id>` and
// individual adapters may omit it, but the union across
// activated adapters MUST be non-empty — last-admin
// protection (security.md §Authorization model) is global
// across adapters and only works when the deployment has at
// least one admin row to begin with. The @Startup
// admin-bootstrap bean (deferred per M1-046's notes) will
// later read the same per-adapter property to seed the row;
// this gate makes the operator-input misconfig fail fast at
// boot rather than at first /grant-admin attempt.
```

```properties
# infochat-provider/src/main/resources/application.properties:91-94
# The @Startup admin-bootstrap bean that READS this property and creates
# the row is deferred per M1-046's notes; until that ticket lands, the
# property is a startup invariant only.
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/deployment.md` §Bootstrap behavior on startup commits: "Provider ensures, for every enabled adapter, that its bootstrap-admin user exists and has `is_admin = true` (one bootstrap row per `(adapter, contact_id)`, all audit-logged)", followed by two full subsections specifying the drift semantics and the exact row shape (`registration_state = 'vouched'`, `probation_until = NULL`, audit `details_json.cause = 'bootstrap'`). No code implements any of it. Gate 7 in `AdapterRegistry` only validates that the property is non-blank; nothing ever INSERTs the row (`grep "INSERT INTO users"` across infochat-provider matches only `BanCommandHandler` preban-minting and `InviteCodeConsumer` invite-consume).

The deferral comment points at "M1-046's notes", but M1-046 is `status: done` (it explicitly excluded the bean: "any bootstrap-admin @Startup bean exercise — deferred per the T1-E handoff; this ticket assumes admins exist (test seeds them via direct INSERT)") and the M1 board shows 0 pending / 0 in-progress tickets. The deferral target no longer exists; the gap survived to the end of the milestone because every test seeds admins via raw INSERT, so nothing in CI can observe it.

Consequence chain on a fresh production deployment: zero `is_admin = true` rows exist → `/invite create` is admin-only, so no invite code can ever be issued → DM registration requires an invite (D44) → group interaction requires prior DM registration (D47) → no user can ever interact with the bot. The documented bootstrap path is the missing bean; the only recovery is an operator raw-SQL INSERT under the Admin role, which the spec describes as an escape hatch for "occasional bulk fixes", not as the bootstrap mechanism. Last-admin protection and the per-adapter admin threat profile (security.md §Per-adapter admin threat profile) are all written against rows this bean was supposed to create.

**Recommended fix:**

```java
// infochat-provider/.../bootstrap/AdminBootstrap.java (new)
@Startup
@Priority(240) // before the reconcilers/listeners; after Flyway
@ApplicationScoped
public class AdminBootstrap {
    @Inject DataSource dataSource;
    @Inject AdapterRegistry adapterRegistry;
    @Inject AuditLogWriter auditLogWriter;

    @PostConstruct
    @Transactional
    void seed() {
        Config config = ConfigProvider.getConfig();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            String contactId = config.getOptionalValue(
                "infochat.adapters." + adapter.name() + ".admin", String.class).orElse("");
            if (contactId.isBlank()) continue;
            // deployment.md §Bootstrap-seeded admin row shape:
            // vouched, no probation, is_admin=true; idempotent on
            // (adapter, contact_id); prior admin rows untouched
            // (§Bootstrap admin drift).
            // INSERT ... ON CONFLICT (adapter, contact_id)
            //   DO UPDATE SET is_admin = TRUE
            // + audit row with details_json.cause = 'bootstrap'
            //   only when a row was created or promoted.
        }
    }
}
```

**Reasoning:**

The fix implements the exact contract deployment.md already specifies — idempotent per-(adapter, contact_id) upsert, vouched state, audit-before-effect, leave prior admins in place. Gate 7 already guarantees the union is non-empty, so the bean always seeds at least one admin and the invite → registration chain becomes reachable. An integration test that boots the Provider with a configured admin and asserts the `users` row plus the audit row closes the CI blind spot the direct-INSERT seeding created.

**Trade-offs:**

None — the fix is strictly better. The only design care point is ordering (the bean must run after migrations and adapter activation so `adapterRegistry.activatedAdapters()` is populated), which the existing `@Priority` ladder already accommodates.

---

### F2. `quarantine_review` catch-up advances the cursor without delivering the channel's only side effect; the live path can permanently skip actionable events

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconciler.java:28-31, QuarantineReviewListener.java:143-156, 260-273
- **Surface:** NOTIFY

**Current code:**

```java
// QuarantineReviewReconciler.java:28-31
 * <p>Unlike the live listener, the reconciler only advances the cursor
 * — it does not fire admin notifications for missed events. Admin
 * notifications are best-effort and the admin will see the quarantine
 * queue on the next {@code /quarantine list} invocation regardless.
```

```java
// QuarantineReviewListener.java:143-152
    boolean handleEvent(@NonNull String targetKind, @NonNull UUID targetId,
                        @NonNull String newStatus, @NonNull Instant eventTime) throws SQLException {
        boolean advanced = providerStateDao.advanceCursor(
                CHANNEL, eventTime, targetKind, targetId.toString());

        if (advanced && isActionable(newStatus)) {
            fireAdminNotification(targetKind, targetId, newStatus);
        }
        return advanced;
    }
```

```java
// QuarantineReviewListener.java:260-263 — payload carries no timestamp;
// the listener reads the row's CURRENT timestamp, which may already
// reflect a LATER transition than the event being processed.
    private @Nullable Instant lookupEventTime(String targetKind, UUID targetId) throws SQLException {
        String sql = "quarantine".equals(targetKind)
                ? "SELECT updated_at FROM quarantine_review_view WHERE id = ?"
                : "SELECT status_changed_at FROM post WHERE id = ?";
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/architecture.md` §Inter-service communication defines the channel's consumer behavior: "the Provider drives the throttled admin notifier (security.md §Failure handling) on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions", with "Correctness mechanism: high-water mark on Provider side", and §Catch-up states "NOTIFY is the latency optimization; the high-water mark is the correctness guarantee" and "the high-water mark advances both fields **in the same DB transaction** as the side effect it triggers". Three independent gaps void that guarantee for this channel:

1. **Catch-up never fires the side effect.** If a quarantine `PENDING` insert (or a `post → NEEDS_REVIEW` transition) lands while the Provider is down, the reconciler advances the cursor past it at startup and deliberately does not notify. The event is consumed forever — the next live event inside the same throttle window will be suppressed as a duplicate of a notification that never went out. The class comment's rationale ("admin notifications are best-effort") is an implementation-side re-write of the spec's contract; nothing in architecture.md or security.md declares this channel's consumer behavior live-path-only.
2. **The live path gates the notification on the cursor CAS.** Because the payload omits `reviewed_at`, `lookupEventTime` reads the row's *current* timestamp. Row A's `PENDING` event processed after row A's verdict (or any later-timestamped event from another row) advances the cursor beyond row B's earlier-timestamped `PENDING`; when B's NOTIFY is dispatched, `advanced == false` and the actionable notification is suppressed. The same suppression occurs when emitters across two services (Stage-1 inserts in the Collector, approve/reject procedures invoked by the Provider, the TTL job) commit out of `updated_at` order, since NOTIFY delivery follows commit order, not timestamp order.
3. **Cursor advance and side effect are not atomic.** `handleEvent` is not `@Transactional`: `advanceCursor` commits in its own autocommit transaction, then `fireAdminNotification` runs on a separate connection. `NewPostHandler.handle` (the same pattern's sibling) wraps both in one `@Transactional` boundary precisely citing the spec's same-transaction rule — this listener does not.

The lost signal is the one defense-relevant transition the spec singles out as requiring admin attention. The practical failure case is exactly the common one: Provider bounced overnight, one post quarantined in the gap, admin never paged, the post silently auto-rejects 14 days later via the admin-review TTL.

**Recommended fix:**

```java
// 1. Live path — notify on actionable status regardless of the CAS
//    result; the throttled notifier (single notification_key +
//    suppressed_count) already collapses duplicates:
boolean handleEvent(...) throws SQLException {
    boolean advanced = providerStateDao.advanceCursor(
            CHANNEL, eventTime, targetKind, targetId.toString());
    if (isActionable(newStatus)) {
        fireAdminNotification(targetKind, targetId, newStatus);
    }
    return advanced;
}

// 2. Reconciler — after the cursor scan, fire one throttled
//    notification if any actionable state exists (state-based, so it
//    is idempotent and needs no per-event replay):
//    SELECT count(*) FROM quarantine_review_view WHERE status = 'PENDING'
//    UNION ALL SELECT count(*) FROM post WHERE status = 'NEEDS_REVIEW'
//    → if > 0, fireAdminNotification(...) once.

// 3. Annotate handleEvent @Transactional (mirroring NewPostHandler) so
//    the cursor advance and the admin_notification_state upsert commit
//    atomically per the spec's same-transaction rule.
```

**Reasoning:**

The admin notification is state-triggered, not event-precise — the `admin_notification_state` upsert is keyed by a single `notification_key` with a throttle window, so duplicate fires are absorbed by design. Decoupling it from the cursor arithmetic removes all three loss windows at zero risk of notification storms: the cursor remains the dedup mechanism for *processing*, the throttle remains the dedup mechanism for *paging*. The reconciler change converts catch-up from cursor bookkeeping into the correctness guarantee the spec claims it is.

**Trade-offs:**

A duplicate NOTIFY now performs one extra throttled-upsert round-trip (previously short-circuited by `advanced == false`). Negligible against quarantine event rates. If the project instead decides admin notifications genuinely are best-effort, that is a spec amendment to architecture.md §Inter-service communication — the code comment cannot carry that decision alone.

---

### F3. Invariant 6's partition-drop half is unimplemented and the design notes describe a pruner, cadence, and horizons that do not match the code

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java:54-80 (create-only); docs/design/02-schema.md:806-820; docs/design/07-deployment.md:222
- **Surface:** schema

**Current code:**

```java
// PartitionCreator.java:54-59 — creates next month's partitions; no
// class anywhere drops one (grep "PartitionPruner|partition-prune|DROP"
// over src/** matches nothing).
    @Scheduled(every = "{infochat.partitions.check-interval}")
    void onTick() {
        YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        try {
            provision(nextMonth);
```

```markdown
<!-- docs/design/02-schema.md:806-816 -->
### 2.4.4 Partition lifecycle

A nightly `partition_pruner` job:

1. Creates `_yyyymmdd` partitions for tomorrow on `post`, `post_entity`,
   `post_embedding`, `post_reference`, and `price_snapshot` (§2.7.2).
2. `DROP PARTITION` on partitions whose end date is older than the
   per-table retention horizon:
   - `post` — 30 days (laptop/vps/remote-llm), 14 days (pi)
   - `post_entity`, `post_embedding`, `post_reference` — 4 days (all profiles)
   - `price_snapshot` — 7 days (all profiles)
```

```properties
# docs/design/07-deployment.md:222 — a property no code reads:
infochat.collector.partition-prune-cron=0 30 3 * * ?
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` Invariant 6 commits: "`post`, `post_reference`, `post_embedding`, `price_snapshot` ... are partitioned and aged out by partition drop"; "`post` carries a fixed, profile-driven retention horizon (decision D33)". The implementation has the create half only (`PartitionCreator`, monthly cadence). With M1 fully done (210/210 tickets, none pending), there is no drop mechanism, no scheduled job, and no documented operator runbook step that performs it — V17's comment delegates to "the partition rotator ticket (operator-driven)", which never landed.

Three concrete consequences:

1. **Unbounded growth.** All five partitioned tables grow forever. `post_embedding` (a 768-dim vector per post) and `post_reference` are the heavy ones; on the `pi` profile this contradicts the entire hardware-profile premise.
2. **Retention is a privacy/footprint commitment, not just a size knob.** D33's "fixed retention horizon" determines how long fetched third-party content is held; it currently evaluates to "indefinitely".
3. **The design notes lie in both directions.** 02-schema §2.4.4 says daily `_yyyymmdd` partitions and a nightly pruner; the code creates monthly `_YYYYMM` partitions and prunes nothing. The documented 4-day horizon for `post_entity`/`post_embedding`/`post_reference` is *unimplementable* at monthly granularity (dropping a monthly partition only after its newest row passes 4 days retains up to ~34 days). 07-deployment documents a cron property that nothing reads. Retrieval code already leans on the horizon being real (`infochat.linking.lookback-days=4` is annotated "matches the pipeline's READY-retention horizon" — a horizon that does not exist).

**Recommended fix:**

```java
// infochat-collector/.../partition/PartitionPruner.java (new) —
// same owner-datasource pattern as PartitionCreator:
@Scheduled(cron = "{infochat.collector.partition-prune-cron}")
void onTick() {
    // For each PartitionedTable: enumerate child partitions from
    // pg_inherits/pg_class, parse the YYYYMM suffix, and
    // DROP TABLE <child> when the partition's END bound is older
    // than now() - retentionHorizon(table, profile).
    // Horizons are profile-driven properties
    // (infochat.retention.<table>), defaulted from design notes.
}
```

Simultaneously amend docs/design/02-schema.md §2.4.4 and §2.7.2 to the implemented monthly cadence and recompute honest horizons (e.g. "post_entity/embedding/reference: dropped when the whole month passes the horizon; effective retention ≤ horizon + 31 days"), or switch the three short-horizon tables to weekly partitions if the 4-day target is load-bearing.

**Reasoning:**

The pruner closes the only unenforced clause of a spec invariant and makes the design note true again. Partition drop is O(1) and runs on the owner datasource exactly like the creator, so the role-split story is unchanged. Making the horizons properties keeps them profile-driven per D33.

**Trade-offs:**

Monthly granularity means actual retention overshoots the nominal horizon by up to one month; honest documentation or finer cadence for the 4-day tables is required — that is a real decision the fix forces, not a silent default.

---

### F4. Spec contradicts itself on `price_snapshot` write privileges; V17 implements the wider grant

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** docs/spec/security.md:1032-1034; docs/spec/schema.md:587; infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:85
- **Surface:** spec-internal

**Current code:**

```markdown
<!-- docs/spec/security.md:1032-1034 -->
- **Collector role** — `INSERT/UPDATE` on ingest-owned tables
  (including `price_snapshot` and `asset_config`); ...
```

```markdown
<!-- docs/spec/schema.md:587 (§Operational — Price snapshot) -->
  `raw_payload` (JSONB — exactly the upstream response's relevant
  fragment, kept for forensic replay). **INSERT-only**; no updates.
```

```sql
-- V17__price_snapshot.sql:85
GRANT SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector;
```

**Why this is wrong / suboptimal / risky:**

Two spec files disagree: schema.md commits `price_snapshot` is INSERT-only with no updates (and `PriceSnapshotStore`, the table's only writer, honors that — `ON CONFLICT ... DO NOTHING`, never `DO UPDATE`), while security.md's role matrix grants the Collector `UPDATE` on it, and V17 faithfully implements the wider grant. The spec is the canonical contract; an internal contradiction means future tickets can cite either sentence and both are "spec-compliant".

The practical exposure is least-privilege erosion at the D34 trust boundary: a SQL-injection foothold or compromise in the Collector can silently rewrite price history and the `raw_payload` column that schema.md designates as the forensic-replay record. security.md's own closing claim for the role split ("a SQL-injection bug in the Provider cannot ... mutate price snapshots") shows the property was considered worth defending — it is simply not extended to the Collector side, where no legitimate UPDATE exists either (`ON CONFLICT DO NOTHING` requires no UPDATE privilege).

**Recommended fix:**

```sql
-- V39__price_snapshot_insert_only.sql
REVOKE UPDATE ON price_snapshot FROM infochat_collector;
```

```markdown
<!-- security.md §DB roles, Collector bullet -->
- **Collector role** — `INSERT/UPDATE` on ingest-owned tables
  (including `asset_config`; **`INSERT`-only on `price_snapshot`** —
  the table is INSERT-only per schema.md §Operational); ...
```

**Reasoning:**

Aligns both spec files with the invariant the schema and the only writer already obey, and removes an unused privilege from the role whose blast radius D34 exists to bound. No code change is needed — nothing performs the UPDATE today.

**Trade-offs:**

None — the fix is strictly better. (If a future correction-of-bad-snapshot workflow is wanted, the spec already routes it through the Admin role.)

---

### F5. The "collector must not depend on messaging-adapter" guard is documented as build-enforced and CI-verified; neither mechanism exists

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** docs/design/09-reference.md:41; pom.xml (no enforcer plugin); repository root (no CI config)
- **Surface:** DAG

**Current code:**

```markdown
<!-- docs/design/09-reference.md:41 -->
- `infochat-collector` MUST NOT depend on `infochat-messaging-adapter`.
  Enforced by the parent POM and verified in CI; an attempt to add the
  dependency fails the build with a clear error. This is the
  architectural guarantee that the Collector cannot accidentally become
  user-facing.
```

```
pom.xml — pluginManagement contains only maven-compiler-plugin
(Error Prone + NullAway). No maven-enforcer-plugin in any of the
seven poms; no .github/, .gitlab-ci.yml, Jenkinsfile, or ci/ in the
repository.
```

**Why this is wrong / suboptimal / risky:**

The DAG itself is currently honored (verified: collector's pom depends on core/ssrf/llm-adapter only; the three shared modules have no inter-module deps; provider depends on all four). But the file that declares itself "normative for module dependencies (the build enforces the DAG)" promises an enforcement mechanism that does not exist. The guarded property is the architecture's #1 blast-radius guarantee ("the Collector cannot accidentally become user-facing", architecture.md §Service split reason 1). A future ticket adding the dependency — e.g. to reuse an adapter-side DTO — would compile, pass `mvn verify`, and ship; everyone who reads 09-reference.md will assume the build would have caught it, which is precisely how convention-only guards rot.

**Recommended fix:**

```xml
<!-- infochat-collector/pom.xml -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>ban-messaging-adapter</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <excludes>
              <exclude>app.zcat.infochat:infochat-messaging-adapter</exclude>
            </excludes>
            <message>infochat-collector MUST NOT depend on infochat-messaging-adapter
              (docs/design/09-reference.md §9.1): the Collector is headless.</message>
          </bannedDependencies>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Add the symmetric `bannedDependencies` excludes to `infochat-ssrf`, `infochat-llm-adapter`, and `infochat-messaging-adapter` for the "siblings must not depend on each other" rule (09-reference.md:42), and either add the CI workflow or strike "and verified in CI" from the design note.

**Reasoning:**

`bannedDependencies` checks transitive resolution, so the guard cannot be bypassed via an intermediate dependency, and the failure message teaches the rule at the moment of violation. The design note becomes true.

**Trade-offs:**

A few seconds of enforcer execution per build. If adding the plugin is declined, the honest alternative is editing the design note to say the rule is convention-only — worse, but at least not false.

---

### F6. `TranslationProvider` lives in the wrong module against spec and both design notes

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java:34; infochat-provider/src/main/java/app/zcat/infochat/provider/translation/LlmTranslationProvider.java:32
- **Surface:** SPI

**Current code:**

```java
// infochat-messaging-adapter/.../messaging/TranslationProvider.java:1,34
package app.zcat.infochat.messaging;
...
public interface TranslationProvider {
```

```markdown
<!-- docs/spec/llm.md:29-33 -->
The LLM adapter exposes pluggable interfaces (decision D32):

- **`LlmProvider`** — chat completion + structured-output classification.
- **`EmbeddingProvider`** — text → vector batch.
- **`TranslationProvider`** — text + (from, to) → text.
```

```markdown
<!-- docs/design/09-reference.md:32 -->
| `infochat-llm-adapter` | (none) | `LlmProvider`, `EmbeddingProvider`,
`TranslationProvider` SPIs and impls. |
```

**Why this is wrong / suboptimal / risky:**

Spec llm.md says the LLM adapter exposes `TranslationProvider`; design 09-reference and design 05-llm-and-embeddings (§5.1 package tree, listing `TranslationProvider.java`, `LlmTranslationProvider.java`, `NoopTranslationProvider.java` under `infochat-llm-adapter/`) agree. The code places the interface in `infochat-messaging-adapter` and the only impl (`LlmTranslationProvider`) in `infochat-provider`; the parent pom comment (pom.xml:70-75) re-frames messaging-adapter as "the Provider-side presentation-layer SPI module (MessagingAdapter, TranslationProvider, ProgressNotifier)". Three artifacts now describe three different placements. Whichever placement is intended, the contract surface is ambiguous: a future translation ticket drafted from design 05's package tree would create a duplicate SPI in llm-adapter, and messaging.md's §Required SPI surface — the contract that defines what the messaging module owns — does not mention translation at all.

**Recommended fix:**

Move `TranslationProvider` to `infochat-llm-adapter` (`app.zcat.infochat.llm`) and `LlmTranslationProvider` with it (it consumes `LlmRouter`, which already lives there; design 05 §5.1 places both there). `infochat-provider` already depends on `infochat-llm-adapter`, so the two consumers (`TranslationPipeline`, tests) need only an import sweep.

**Reasoning:**

Restores the spec/design/code triangle to one consistent story with the smallest doc churn (zero spec edits, zero design edits, mechanical code move). It also keeps messaging-adapter's surface aligned with messaging.md's §Required SPI surface, which is transport-only.

**Trade-offs:**

A cross-module move touches every import site and the pom comment.

**Alternative options:**

- **Option A** (the recommended move above)
- **Option B** — keep the messaging-adapter placement and amend llm.md §SPI shape + design 09/05 + D32 to record it — pros: no code churn; cons: requires a spec amendment to legitimize what reads as presentation-layer scope creep, and leaves an LLM-flavoured SPI in a module that must not depend on the LLM module, so its default impl can never live next to it.

---

### F7. Design notes document an `infochat.profile` config key that deliberately does not exist

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** docs/design/07-deployment.md:65,103,119,135,310; docs/design/01-architecture.md:629; docs/design/05-llm-and-embeddings.md:15; docs/design/02-schema.md:1378
- **Surface:** property

**Current code:**

```markdown
<!-- docs/design/07-deployment.md:103,119,135 (operator runbook) -->
2. Edit `application.properties`: `infochat.profile=...`.
1. System properties              `-Dinfochat.profile=pi`
infochat.profile=laptop                          # laptop|vps|pi|remote-llm
```

```java
// infochat-collector/.../config/InfochatProfile.java:26-32
 * <p><b>Why no separate {@code infochat.profile} key.</b> CLAUDE.md and the
 * spec consistently use the phrase "infochat.profile" — that is the
 * <i>concept</i> name. The actual configuration <i>mechanism</i> is Quarkus'
 * built-in profile system ({@code quarkus.profile} / {@code QUARKUS_PROFILE}).
```

**Why this is wrong / suboptimal / risky:**

M1-005 deliberately decided against a separate `infochat.profile` key; the implementation reads the Quarkus profile chain and fails fast in NORMAL launch mode when no infochat profile is active (`InfochatProfile.Validator`). The design notes were never swept: 07-deployment — the operator runbook — instructs setting `infochat.profile=...` in four places including the env-var table (`INFOCHAT_PROFILE | optional | both | Override infochat.profile`), and three other design files repeat the key. An operator following the runbook sets a property nothing reads; the deployment then refuses to start with a message about `QUARKUS_PROFILE` that contradicts the document they just followed. The fail-fast validator prevents silent misconfiguration (this is why the severity is medium, not high — without it, a vps operator would silently run with `release-on-stage2-failure=true` from the base defaults), but a runbook that reliably produces a startup crash on first deploy is still a broken deployment contract.

**Recommended fix:**

Sweep the four design files: replace `infochat.profile=<x>` instructions with `quarkus.profile=<x>` / `QUARKUS_PROFILE=<x>`, replace the `INFOCHAT_PROFILE` env-var row with `QUARKUS_PROFILE`, and add one sentence in 07-deployment §Configuration: "`infochat.profile` is the concept name used by spec text; the mechanism is the Quarkus profile system — there is no `infochat.profile` property." Spec files need no change (deployment.md commits no concrete keys).

**Reasoning:**

Design notes are the operational source of truth for concrete keys ("Concrete property keys (`infochat.*`)" is explicitly design-tier per architecture.md §What lives in design notes); they currently document a key the code rejected by recorded decision. The sweep is the entire fix.

**Trade-offs:**

None — the fix is strictly better.

---

### F8. The spec-committed progress-notification pipeline has no implementation and its SPI surface has zero production consumers

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java:37 (no impl anywhere); infochat-provider — no reference to ProgressNotifier/ProgressStage/MessageHandle/setTyping in src/main
- **Surface:** SPI

**Current code:**

```java
// infochat-messaging-adapter/.../ProgressNotifier.java:37
public interface ProgressNotifier {
// grep "implements ProgressNotifier" across src/main: no matches.
// grep "ProgressStage|ProgressNotifier|setTyping|MessageHandle" across
// infochat-provider/src/main: no matches.
// grep "capabilities()." across all src/main: the only flag read is
// AdapterRegistry.java:182 (supportsMarkdownLinks startup gate) and
// :192 (supportsMentionByContactId gate).
```

```java
// infochat-provider/.../command/SummaryCommandHandler.java:126 — the
// long-running LLM handler returns one message; no placeholder, no
// stage events, no typing pulse:
    public OutboundMessage handle(ScopeRef scope, String rawText) {
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/messaging.md` §Progress notifications commits: "Long-running handlers (`/summary`, periodic digest, chat agent) publish stage events to a cross-cutting `ProgressNotifier` (decision D31)" with a five-step behavioral contract (placeholder via `send()`, typing on, coalesced `update`s, guaranteed `finalize` via try/finally). `docs/spec/architecture.md` principle 7 makes it an architectural principle. M1 is complete (210/210 done, 0 pending) and: no `ProgressNotifier` impl exists; `/summary`, the digest worker, and the chat agent send single-shot messages; `MessagingAdapter.update` / `finalizeMessage` / `setTyping` — implemented and contract-tested in all three adapters — are never called by production code; and four capability flags (`supportsMessageEdit`, `minEditInterval`, `supportsTypingIndicator`, `supportsCodeFormatting`) have zero readers. Half of the messaging SPI's method surface is currently dead weight maintained by every adapter, and the spec's D30/D31 commitments (monospace rendering when `supportsCodeFormatting`, progress UX) are silently unmet. With no pending ticket covering it, this is drift, not work-in-progress: the next milestone planning pass will read messaging.md, see the contract, and have no signal that nothing implements it.

**Recommended fix:**

Either (a) ticket and implement the notifier consumer: a `ProgressNotifier` impl in infochat-provider that wraps `send`/`update`/`finalizeMessage`/`setTyping` per messaging.md's five steps, wired into `SummaryCommandHandler`, `DigestWorker`, and the chat agent; or (b) if v1 deliberately ships without progress UX, amend messaging.md §Progress notifications and architecture.md principle 7 to mark the pipeline as deferred (a spec amendment, since both present it as v1 surface), and note in the SPI javadoc that `update`/`finalizeMessage`/`setTyping` are forward surface for that amendment.

**Reasoning:**

The SPI side is finished and tested; the cost of (a) is one consumer class plus three call-site changes. If (b) is chosen, recording the descope in spec keeps the contract honest — today the spec promises behavior the finished milestone does not deliver, which is exactly the drift the spec-wins rule exists to prevent.

**Trade-offs:**

Option (a) adds runtime behavior late in the milestone (edit-rate handling per adapter); option (b) admits a UX regression against the spec. Doing neither leaves a contract that misleads every future reader.

---

### F9. The Fetcher SPI does not carry the output-type discriminator the spec commits to

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java:13-18
- **Surface:** SPI

**Current code:**

```java
// Fetcher.java:13-18
 * <p>The SPI is deliberately minimal in v1: ...
 * Pagination, retry, backoff, and the asset-Fetcher output-type
 * discriminator are implementation concerns or follow-up tickets;
 * they are intentionally NOT method-shape commitments here.</p>
```

```markdown
<!-- docs/spec/architecture.md §Ingest SPIs — Output type -->
Asset Fetchers (decision D39) produce `price_snapshot` rows
instead and write **directly** to the `price_snapshot` table ...
The Fetcher SPI carries an output-type discriminator so the
Collector's per-tick dispatch routes the result to the right sink.
```

**Why this is wrong / suboptimal / risky:**

The spec sentence is false against the code: the `Fetcher` SPI has no discriminator, and asset fetching is not routed through the Fetcher SPI at all — `AssetSnapshotFetcher` + `AssetDataSource` (coingecko/kraken/bitfinex sources) form a separate scheduled path writing through `PriceSnapshotStore`. The implemented shape satisfies the spec's *intent* (right sink, no Stage 1/2, no source rows) and is arguably simpler than a discriminated union on one SPI. But the spec is the canonical contract; a future asset-source ticket drafted from architecture.md would look for the discriminator and not find it, and the SPI javadoc's "follow-up tickets" deferral points at tickets that no longer exist (M1 complete).

**Recommended fix:**

```markdown
<!-- architecture.md §Ingest SPIs — Output type, replace the last sentence -->
Asset fetching does not flow through the `Fetcher` SPI: asset
snapshot sources implement a dedicated snapshot-source contract and
the Collector schedules them per host, independent of the post
Fetcher dispatch. The `Fetcher` SPI therefore has exactly one output
type (normalized posts) and carries no output-type discriminator.
```

**Reasoning:**

The separate-path shape is the better design (no union types on the post pipeline's SPI; asset code cannot accidentally reach the outbox) and is already built and tested. Amending the spec to match it is the engineering-rules-sanctioned path (§4 "push back when simpler exists" resolved in the simpler direction); adding a discriminator to satisfy the sentence would be machinery with no consumer.

**Trade-offs:**

None — the fix is strictly better.

---

### F10. 752 hand-written `@NonNull` annotations contradict the §7a null-marking convention

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** SPI

**Current code:**

```java
// infochat-core/.../ingest/Fetcher.java:33
List<NormalizedPost> fetch(long sourceId, @NonNull String identifier);

// infochat-messaging-adapter/.../MessagingAdapter.java:95
MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException;

// grep "@NonNull" over **/src/main/java: 752 occurrences across 171 files,
// spanning all six modules including every SPI signature.
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7a: "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable`...; `@NonNull` is no longer written by hand." The parent pom confirms `AnnotatedPackages=app.zcat.infochat` is active with `NullAway:ERROR`. The 752 hand-written `@NonNull`s are therefore all redundant — and worse than redundant on the contract surface: when some parameters in a signature carry `@NonNull` and others do not, a reader can no longer trust that an unannotated parameter means "never null by package default" versus "the author forgot the annotation under the old convention". The ambiguity is most costly exactly where this lens looks — SPI signatures that adapter implementers across modules read as contracts.

**Recommended fix:**

```
Mechanical sweep: delete every `@NonNull` annotation (and the then-unused
`org.jspecify.annotations.NonNull` imports) from src/main; run `mvn verify`
— NullAway's package-default enforcement is unchanged, so a green build
proves the sweep altered no contract.
```

**Reasoning:**

The build, not the annotation, is the §7a enforcement mechanism (decision D48); removing the hand-written annotations restores the documented reading rule "bare type = never null, `@Nullable` = nullable" with zero behavioral risk because NullAway treats the two forms identically inside annotated packages.

**Trade-offs:**

A large but content-free diff (171 files) that will dominate one commit's blame; doing it as a single dedicated sweep commit keeps it out of feature history.

## Synthesizer-relevant observations

- The skill-supplied SPI inventory (`*/src/main/java/**/spi/*.java`) was empty because no module uses an `spi/` package. The actual SPI set verified for this review: `Fetcher` + `StreamSource` (infochat-core `core.ingest`), `LlmProvider` + `EmbeddingProvider` (infochat-llm-adapter), `MessagingAdapter` + `TranslationProvider` + `ProgressNotifier` (infochat-messaging-adapter).
- The `new_price_snapshot` channel has a spec-compliant producer (`PriceSnapshotStore`, transactional INSERT-then-NOTIFY) and no Provider-side consumer; correctness holds because `AssetSnapshotReader` reads the table per command and there is no in-process cache to invalidate. The spec's "cache flushed on reconnect" mechanism describes a cache that does not exist — trivially satisfied, not drift; recorded here rather than as a finding.
- `new_post` end-to-end checks out: producer payloads (ReadyPromoter, V32 `approve_quarantine`) and the consumer parse agree on shape; `NewPostHandler` implements the same-transaction cursor rule with a pre-advance existence check; payload parsing at both listeners is defensive (system boundary honored). Invariant 2's last-admin trigger uses the spec-mandated `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE`; audit redaction is implemented at both write (DefaultRedactionHook) and read (V31 view functions) layers per security.md.
- The module DAG itself is honored by all seven poms (collector: core+ssrf+llm-adapter; provider: all four shared modules; shared modules: no inter-module deps) — F5 concerns the absent enforcement, not a violation.
