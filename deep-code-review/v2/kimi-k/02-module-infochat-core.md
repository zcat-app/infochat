# Deep code review: module

**Target:** module infochat-core
**Lens:** module
**Module path:**
    infochat-core/
**Date:** 2026-06-07 01:20
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — V17__price_snapshot.sql:35-52 — `price_snapshot` lacks the FK to `asset_config` that `docs/spec/schema.md` §Operational commits to ("`asset` (FK to `asset_config`)").
- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — `infochat_admin` is a paper principal: six migrations document it as the sole DELETE/TRUNCATE/inspection escape hatch, but the role holds no grant beyond schema USAGE, so every documented operator path fails.
- [low] SECURITY — ThrottledAdminNotifier.java:310 — `getState`'s failure path logs the raw caller-supplied `key` instead of the sanitized form, re-opening the log-line-forgery vector the class's own `sanitize` exists to close; `sanitize` also passes ESC and other C0 controls through.
- [low] PERFORMANCE — V18__chat_tables.sql:74-75 — `idx_chat_message_session_seq` duplicates the implicit PRIMARY KEY index column-for-column; every `chat_message` INSERT/DELETE maintains it for zero query benefit.
- [low] MAINTAINABILITY-RULES-DRIFT — AuditAction.java:21-25, AuditLogWriter.java:14-20 — javadoc references tickets ("V13 (this ticket)", "M1-068 adds", "acceptance item 1") and `{@link}`s a class in infochat-provider, violating the comment policy and leaking the layering direction.
- [low] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — every main source hand-writes `@NonNull` despite §7a's "non-null is the package default … `@NonNull` is no longer written by hand."

## Detail

### F1. price_snapshot lacks the spec-committed FK to asset_config

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:35-52

**Current code:**

```sql
CREATE TABLE price_snapshot (
    id              BIGSERIAL,
    asset           TEXT NOT NULL,
    sub_verb        TEXT NOT NULL,
    vs_currency     TEXT NOT NULL,
    ...
    PRIMARY KEY (id, captured_at)
) PARTITION BY RANGE (captured_at);
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Operational — Price snapshot commits: "Columns: `asset` (FK to `asset_config`), `sub_verb`, `captured_at`, …". No FK exists on `price_snapshot` in V17 or any later migration. The omission is not incidental — V14's own header relies on the FK existing: "rows soft-disabled by the bootstrap loader … remain queryable so prior price_snapshot rows keep their FK target." The soft-disable-never-delete lifecycle of `asset_config` was designed *specifically so this FK could hold*, and then the FK was never declared. Without it, a fetcher bug (or operator typo) can insert snapshots for an `(asset, sub_verb)` pair that has no `asset_config` row, and the Provider's dispatch-time join silently never surfaces them.

The constraint is feasible: PostgreSQL supports FKs *from* a partitioned table to a plain table, and `asset_config`'s PRIMARY KEY is `(asset, sub_verb)`, so a composite FK matches the spec intent (a single-column FK on `asset` alone has no unique target). Note also the spec names the column `currency` where V17 (matching `docs/design/10-asset-commands.md`) uses `vs_currency`; if `vs_currency` is the intended name, that is a one-word spec amendment to make in the same pass.

**Recommended fix:**

```sql
-- VNN__price_snapshot_asset_config_fk.sql
-- asset_config rows are never hard-deleted (V14: REVOKE DELETE; soft-
-- disable lifecycle), so this FK cannot be violated by config removal.
ALTER TABLE price_snapshot
    ADD CONSTRAINT price_snapshot_asset_config_fk
    FOREIGN KEY (asset, sub_verb) REFERENCES asset_config (asset, sub_verb);
```

**Reasoning:**

Restores the referential-integrity commitment the spec makes and that V14's lifecycle design presupposes. The FK is checked once per fetcher tick per asset (a handful of rows per refresh interval) — negligible cost. `ON CONFLICT … DO NOTHING` dedup writes (V38) are unaffected.

**Trade-offs:**

One extra index lookup per snapshot INSERT, and the FK must be validated against existing rows when the migration runs (trivial at current data volume; on a long-lived deployment with months of partitions, `NOT VALID` + `VALIDATE CONSTRAINT` splits the lock cost). The constraint also means a hypothetical future "drop an asset entirely" operation must delete snapshots first — which Invariant 6 already routes through partition drop anyway.

---

### F2. infochat_admin is a paper principal — the documented operator escape hatches do not work

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```sql
-- V2__roles.sql:15-18
--   infochat_admin     — reads audit_log_view (redacted), executes the
--                        approve_quarantine / reject_quarantine
--                        procedures. NOT a superuser; ...

-- V2__roles.sql:65  (the role's ONLY grant anywhere in the schema)
GRANT USAGE ON SCHEMA public TO infochat_admin;

-- V3__heartbeat.sql:22-23
-- ... DELETE is intentionally NOT granted to either application role — only
-- `infochat_admin` may delete heartbeat rows (operator path).

-- V6__sources_tags.sql:101
-- soft-delete only; infochat_admin is the sole DELETE path).

-- V12__invite_code_attempt.sql:51-52
-- the operator-side TRUNCATE under infochat_admin is the only purge
-- path, mirroring audit_log's append-only treatment.

-- V5__identity_audit.sql:246-247
-- infochat_admin can disable the trigger for operator-controlled
-- retention runs.
```

**Why this is wrong / suboptimal / risky:**

No migration grants `infochat_admin` anything beyond `USAGE ON SCHEMA public`. The role cannot SELECT `audit_log_view` (V5 grants it to `infochat_provider` only and REVOKEs PUBLIC), cannot DELETE `heartbeat` rows, cannot TRUNCATE `invite_code_attempt` (TRUNCATE requires the TRUNCATE privilege or ownership), cannot EXECUTE `approve_quarantine`/`reject_quarantine` (V21 grants EXECUTE to `infochat_provider`; V5/V25 REVOKE from PUBLIC on `delete_preban_user` and the default function ACL grants PUBLIC EXECUTE on the V21 functions — but the *comments* assign this to admin, not provider), and cannot `ALTER TABLE … DISABLE TRIGGER` (requires ownership). Every "operator path" the migration corpus and `docs/spec/security.md` §DB roles ("Admin role — operator psql sessions only. Used for migrations, raw quarantine inspection, occasional bulk fixes") attribute to this role is unexercisable.

In v1 this is dormant because the role stays NOLOGIN (V31 deliberately) and the operator connects as the bootstrap superuser. But the comments are the schema's security documentation: six separate migrations tell a future operator (or reviewer) that a least-privilege incident-response role exists when it does not. The first time someone follows the documented path in an incident — `SET ROLE infochat_admin; TRUNCATE invite_code_attempt;` — it fails, and the fallback is the superuser, which is exactly what the D34 least-privilege story exists to avoid.

**Recommended fix:**

```sql
-- VNN__admin_role_grants.sql — make the documented operator paths real.
GRANT SELECT ON audit_log_view TO infochat_admin;
GRANT SELECT ON quarantine TO infochat_admin;          -- raw-original inspection
GRANT DELETE ON heartbeat TO infochat_admin;
GRANT TRUNCATE ON invite_code_attempt TO infochat_admin;
GRANT TRUNCATE ON admin_notification_state TO infochat_admin;
GRANT DELETE ON source TO infochat_admin;              -- Invariant 4 escape hatch
GRANT EXECUTE ON PROCEDURE delete_preban_user(UUID, UUID) TO infochat_admin;
GRANT EXECUTE ON FUNCTION approve_quarantine(UUID, UUID) TO infochat_admin;
GRANT EXECUTE ON FUNCTION reject_quarantine(UUID, UUID) TO infochat_admin;
```

**Reasoning:**

Aligns the grant matrix with what the spec and six migration headers already claim. Each grant is the minimum that makes the corresponding documented sentence true. Trigger-disable and partition-drop require ownership and stay superuser-side; the comments claiming admin can disable triggers (V5:246) should be corrected to say so.

**Trade-offs:**

Widens a currently-zero privilege surface — but the role is NOLOGIN, so the surface is reachable only by a principal that can already `SET ROLE`, i.e. the superuser. If the deliberate v1 stance is instead "the admin role is deferred; the operator path is the superuser," then the correct fix is the inverse: a `spec:`-prefixed pass correcting the six migration comments and the spec sentence so the documentation stops promising an escape hatch that does not exist. Either resolution is acceptable; the current state (documentation and grants disagreeing) is not.

**Alternative options:**

- **Option A** (the grants migration above) — pros: makes D34's three-role story real, ready for the v2 LOGIN re-evaluation V2 promises — cons: privilege surface with no v1 caller.
- **Option B** — documentation-only correction (comments cannot be retro-edited in applied migrations, so this lands as design-note/spec text + a comment-bearing no-op migration) — pros: zero privilege change — cons: admits the three-role model is two-role in v1.

---

### F3. getState logs the raw caller key; sanitize misses non-CR/LF control characters

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:310 (and :116)

**Current code:**

```java
public Optional<AdminNotificationRecord> getState(@NonNull String key) {
    // Sanitize the lookup key the same way notifyOnce does so the
    // two calls with the same caller-supplied key reach the same
    // row (the row was persisted under the sanitized form).
    String safeKey = sanitize(key, MAX_KEY_LENGTH);
    ...
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key);
        return Optional.empty();
    }
```

```java
private static String sanitize(String s, int maxLen) {
    String stripped = s.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
    ...
```

**Why this is wrong / suboptimal / risky:**

The class's own doctrine (javadoc on `sanitize`, lines 99-113) is that caller-supplied strings are line-boundary-hostile and must be sanitized before reaching the log sink — "the cost of trusting every future caller to pre-sanitize is a forgery vulnerability the moment one caller forgets." `getState` computes `safeKey` and then logs the *raw* `key` on the failure path. A newline-bearing key (the exact attack `notifyOnceStripsControlCharactersFromInputs` pins for `notifyOnce`) forges arbitrary extra log lines whenever `getState` hits a SQLException. The JUL `Redactor` filter does not strip newlines, so it does not save this path.

Secondarily, `sanitize` strips only CR/LF/NUL. ESC (0x1B) and the remaining C0 controls pass through into both the WARN line and the persisted `notification_key`. ANSI escape sequences in a line an operator `tail`s can visually overwrite or hide content in a terminal — a weaker but real variant of the same forgery the method exists to prevent.

**Recommended fix:**

```java
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
        return Optional.empty();
    }
```

and widen the strip to all C0 controls:

```java
private static String sanitize(String s, int maxLen) {
    StringBuilder stripped = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        stripped.append(c < 0x20 ? ' ' : c);
    }
    ...
```

**Reasoning:**

The one-token `safeKey` change closes the only call site in the class that bypasses its own boundary rule. The C0 widening makes the boundary match the threat (terminal rendering, not just grep line semantics) and is what `JsonEscaper` in the same module already does for the analogous JSON boundary.

**Trade-offs:**

None — the fix is strictly better. (The loop replaces three chained `String.replace` calls and is no slower.)

---

### F4. idx_chat_message_session_seq duplicates the primary-key index

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V18__chat_tables.sql:69-75

**Current code:**

```sql
    PRIMARY KEY (user_id, scope_kind, scope_id, seq),
    ...
);

CREATE INDEX idx_chat_message_session_seq
    ON chat_message(user_id, scope_kind, scope_id, seq);
```

**Why this is wrong / suboptimal / risky:**

The PRIMARY KEY already creates a unique btree index on exactly `(user_id, scope_kind, scope_id, seq)` in that order. `idx_chat_message_session_seq` is column-for-column identical, so the planner never prefers it and every `chat_message` INSERT and DELETE (chat is the highest-write-rate user-state table — one row per turn, plus `/clear` cascade deletes) maintains a second index for zero benefit.

**Recommended fix:**

```sql
-- VNN__drop_redundant_chat_message_index.sql
DROP INDEX idx_chat_message_session_seq;
```

**Reasoning:**

Removes pure write amplification and storage. No query can regress: any plan that used the dropped index uses `chat_message_pkey` identically.

**Trade-offs:**

None — the fix is strictly better.

---

### F5. Javadoc references tickets, migrations-as-changes, and a provider-module class

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java:21-25 (representative; also AuditLogWriter.java:14-20)

**Current code:**

```java
 *   <li>V13 (this ticket) adds {@link #LLM_OUTPUT_SANITIZED} for
 *       the per-occurrence sanitizer hit audit row.</li>
 *   <li>M1-068 adds {@link #CHAT_MODE} for the per-request audit
 *       row written by {@link app.zcat.infochat.provider.chat.ChatAgent}
 *       before the LLM call in chat-mode dispatch. ...
```

**Why this is wrong / suboptimal / risky:**

The project comment policy (CLAUDE.md §Coding style, "Comment important, crucial, or complex code") is explicit: "Don't reference the current ticket, fix, or callers ('used by X', 'added for the Y flow', …) — that belongs in the commit message and rots as the codebase evolves." `AuditAction`'s javadoc is structured as a changelog ("V13 (this ticket) adds", "M1-068 adds", "M1-051 adds") rather than a description of what each verb records. `AuditLogWriter`'s class javadoc cites "acceptance item 1" twice — a reference meaningless outside the ticket that introduced it.

The `{@link app.zcat.infochat.provider.chat.ChatAgent}` is worse than rot: infochat-core does not (and per the 6-module DAG must not) depend on infochat-provider, so the link can never resolve and the core module's API documentation names its consumer — the inverse of the layering the DAG enforces. The same per-verb comments name Provider command handlers (`UnbanCommandHandler` shapes, `/list-groups` paging) throughout.

**Recommended fix:**

```java
 *   <li>{@link #LLM_OUTPUT_SANITIZED} — one row per LLM-output
 *       sanitizer match (per-occurrence, never throttled, per
 *       security.md §LLM output sanitizer).</li>
 *   <li>{@link #CHAT_MODE} — one row per chat-mode request, written
 *       before the LLM call; records actor + scope, never
 *       user-authored prose.</li>
```

**Reasoning:**

Each verb's comment should say what the row *records* and which spec sentence requires it — those are timeless. Which ticket added it, and which class in a downstream module writes it, belong in `git log`. Dropping the cross-module `{@link}` also removes the only place in the module where core's documentation points up the DAG.

**Trade-offs:**

Loses the in-source archaeology of when each verb appeared; `git log -S LLM_OUTPUT_SANITIZED` recovers it on demand.

---

### F6. Hand-written @NonNull contradicts the §7a null-marked-package contract

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// Fetcher.java:33
List<NormalizedPost> fetch(long sourceId, @NonNull String identifier);

// NormalizedPost.java:44-53
public record NormalizedPost(
        long sourceId,
        @NonNull String upstreamIdentifier,
        ...

// Similar in StreamSource, AuditLogWriter, RedactionHook, Redactor,
// SafeLog, ThrottledAdminNotifier, AbstractInstanceLockGuard, JsonEscaper.
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7a: "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable` …; `@NonNull` is no longer written by hand." The parent pom confirms `app.zcat.infochat` is the NullAway annotated package. Every main source in this module nonetheless hand-writes `@NonNull` on parameters, returns, and record components. The annotations are dead weight under the build's semantics, and they undermine the contract's readability benefit: a reader who sees `@NonNull` on some signatures and bare types on others must wonder whether the bare ones are intentionally nullable — exactly the ambiguity §7a exists to remove.

**Recommended fix:**

```java
// Delete the @NonNull annotations and their imports; keep @Nullable.
List<NormalizedPost> fetch(long sourceId, String identifier);
```

**Reasoning:**

Makes the code match the stated, machine-checked convention: bare type = never null, `@Nullable` = the only annotation. NullAway verifies nothing changes semantically (`mvn verify` stays green — the annotations were redundant under `AnnotatedPackages`).

**Trade-offs:**

A mechanical, many-file diff with zero behavior change — under §1 (surgical changes) it should land as its own dedicated cleanup ticket, not ride along with feature work. Until that ticket runs, new code in this module should simply stop adding `@NonNull`.

---

Synthesizer-relevant observations (not module findings):

- The hand-written `@NonNull` pattern (F6) is repo-wide — ~60 files across infochat-provider and infochat-collector carry the same redundant annotations; a single cleanup ticket should cover all modules.
- `docs/spec/schema.md` §Operational says `price_snapshot` is "INSERT-only; no updates" while `docs/spec/security.md` §DB roles grants the Collector "INSERT/UPDATE on ingest-owned tables (including price_snapshot)"; V17 follows security.md (GRANT UPDATE). The two spec files should be reconciled — a spec-tier concern, not a migration defect.
- `docs/spec/schema.md` names the snapshot quote column `currency`; V17 and `docs/design/10-asset-commands.md` use `vs_currency` (noted inside F1).
