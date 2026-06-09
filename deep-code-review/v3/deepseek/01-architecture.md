# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-09 01:48
**Reviewer:** senior-developer (sonnet)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/ — V20 missing from Flyway migration sequence (V19 → V21 gap); possible deleted/replaced migration without renumbering
- [MEDIUM] SIMPLIFICATION — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java:100-101,106 — Three capability flags marked "future use" with zero v1 consumers (`supportsAttachments`, `supportsThreading`, `supportsTypingIndicator`); pre-shipping spec-amendment surface creates dead API contract
- [LOW] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java:343-353 — NOTIFY payload regex uses `.find()` which could silently match wrong JSON field if format drifts; QuarantineReviewListener uses the same pattern family but adds a discriminator validation gate that NewPostListener lacks
- [LOW] SECURITY — infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java:348-349, QuarantineReviewListener.java:284-285 — Raw NOTIFY payload JSON embedded in `IllegalArgumentException` messages; reaches logs unredacted (payloads are cursor-only per spec, so exposure is UUID + timestamp, not user content — but the pattern is worth noting)

## Detail

### F1. V20 gap in Flyway migration sequence

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/ (cross-migration)
- **Surface:** schema

**Current code:**

The migration directory lists (excerpt):
```
V19__summary_anchor.sql
V21__quarantine_admin.sql
```

V20 does not exist. The sequence jumps from V19 to V21.

**Why this is wrong / suboptimal / risky:**

Flyway version numbers are monotonically increasing integers. A gap typically indicates a migration was deleted after having been applied to some environments, or was originally created as V20 then renumbered to V21 without cleaning up the V20 file. Either way, a gap in a 46-migration sequence is non-obvious to future readers: it creates ambiguity about whether V20 was intentionally skipped, accidentally deleted, or logically subsumed into another migration.

The gap does not break Flyway (it applies migrations by version order, gaps are fine at runtime). But in a greenfield project with no prior deployments, every migration should be accounted for. A missing number in the sequence is dead space that every future migration author must wonder about.

**Recommended fix:**

Either:
1. Rename V21 through V46 down by one (V21→V20, V22→V21, …, V46→V45), closing the gap; or
2. Add a comment in `docs/design/02-schema.md` or in a `V20__README.md` placeholder noting "V20 intentionally skipped — <reason>."

**Reasoning:**

A contiguous sequence eliminates the "what happened to V20?" question for every reader. The rename is mechanical (46 - 21 + 1 = 26 files) but safe in a single-developer greenfield project. The comment approach is cheaper and preserves existing migration hashes if any environment already applied them.

**Trade-offs:**

- Rename: touches 26 files, requires updating any hard-coded migration version references in tests or design notes.
- Comment: leaves the gap but documents it.

**Alternative options:**

- **Option A** — Renumber V21–V46 down by one (recommended if no environment has applied V21+).
- **Option B** — Add a placeholder `V20__intentionally_skipped.sql` containing only a comment explaining the gap.

---

### F2. Unused capability flags pre-shipped as spec surface

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java:100-101,106
- **Surface:** capability-flag

**Current code:**

```java
// CapabilityFlags.java:100-101,106
boolean supportsAttachments,        // "future use"
boolean supportsThreading,          // "future use"
...
boolean supportsTypingIndicator,    // "future use"
```

These three boolean fields are defined in the `CapabilityFlags` record but have zero consumers in v1. Every adapter must supply values for them; adding an adapter means wiring three dead booleans. The spec (`docs/spec/messaging.md`) says: "Adding a new flag is a spec amendment."

**Why this is wrong / suboptimal / risky:**

Pre-shipping flags violates the spec's own rule — these flags were added without a spec amendment that justifies their existence. Every adapter implementation must now carry dead fields; every capability-flag contract test must assert values for flags that affect no behavior. The cost is small per adapter (three `false` literals) but cumulative: 3 adapters × 3 dead flags = 9 pointless assignments plus corresponding test assertions.

More importantly, if a future v2 ticket adds behavior for `supportsTypingIndicator`, the flag already exists in the record — the ticket author may forget that adding the flag was supposed to require a spec amendment, because the flag appears to already have been vetted. The spec's procedural guard ("spec amendment required") is weakened by the presence of unvetted flags.

**Recommended fix:**

Remove `supportsAttachments`, `supportsThreading`, and `supportsTypingIndicator` from the record. Adapters drop the three corresponding constructor arguments. Tests drop the corresponding assertions. When v2 needs a flag, add it with the spec amendment the rule requires.

**Reasoning:**

The record is explicitly "closed" per its class Javadoc. Dead fields violate that closure. Removing them shrinks the adapter contract surface to exactly what v1 uses.

**Trade-offs:**

- Adapter implementations need a one-line constructor-arg removal each (3 adapters × 3 args = 9 deletions).
- If a v2 ticket is already drafted referencing these flags by name, it would need a "define the flag" step added — but that step is exactly what the spec amendment rule requires anyway.

---

### F3. NewPostListener NOTIFY payload regex uses unanchored find()

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java:343-353
- **Surface:** NOTIFY

**Current code:**

```java
// NewPostListener.java:343-353
static Payload parsePayload(String json) {
    Matcher readyAtMatcher = READY_AT_PATTERN.matcher(json);
    Matcher postIdMatcher = POST_ID_PATTERN.matcher(json);
    if (!readyAtMatcher.find() || !postIdMatcher.find()) {
        throw new IllegalArgumentException(
            "new_post payload must contain both 'ready_at' and 'post_id' fields; got: "
                + json);
    }
    return new Payload(
        UUID.fromString(postIdMatcher.group(1)),
        Instant.parse(readyAtMatcher.group(1)));
}
```

**Why this is wrong / suboptimal / risky:**

The regex patterns use `Matcher.find()` which scans the entire JSON string for a match anywhere. If a future NOTIFY payload format adds a nested object that happens to contain a field named `"ready_at"` or `"post_id"` (e.g., `{"meta": {"ready_at": "..."}}`), `find()` would match the wrong occurrence without the parser noticing the format changed. The QuarantineReviewListener's `parsePayload` has the same pattern family but adds an explicit discriminator validation gate (`target_kind ∈ {"quarantine", "post"}`) that would catch format drift — NewPostListener has no equivalent gate.

The `new_post` payload format is simpler (only two fields) and has been stable across the entire M1 build, so the practical risk is very low. However, the asymmetry between the two listeners is itself a maintenance smell: a reader expects both NOTIFY parsers to apply the same defensive posture.

**Recommended fix:**

Add a structural validation gate analogous to QuarantineReviewListener's discriminator check. For `new_post`, the simplest gate is verifying that both required fields appear exactly once at the top level — e.g., by checking that `find()` matches and that a second `find()` from the same matcher returns false (no duplicate field). Alternatively, switch from regex to a lightweight JSON parser (the project already depends on Jackson).

```java
static Payload parsePayload(String json) {
    Matcher readyAtMatcher = READY_AT_PATTERN.matcher(json);
    Matcher postIdMatcher = POST_ID_PATTERN.matcher(json);
    if (!readyAtMatcher.find() || !postIdMatcher.find()) {
        throw new IllegalArgumentException(
            "new_post payload must contain both 'ready_at' and 'post_id'");
    }
    // Defend against duplicate fields: a second match means format drift.
    if (readyAtMatcher.find() || postIdMatcher.find()) {
        throw new IllegalArgumentException(
            "new_post payload contains duplicate field; possible format drift");
    }
    return new Payload(
        UUID.fromString(postIdMatcher.group(1)),
        Instant.parse(readyAtMatcher.group(1)));
}
```

**Reasoning:**

Matches QuarantineReviewListener's defensive posture. The duplicate-field check costs one extra `find()` call per field (nanoseconds). The raw-JSON-in-message removal in the error path is a separate finding (F4).

**Trade-offs:**

- Adds 4 lines of validation code.
- A true format migration (v2 adds a field) would need to update this gate — but that's the point: the gate makes format changes explicit.

---

### F4. Raw NOTIFY payload JSON in error log messages

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java:348-349, infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:284-285
- **Surface:** NOTIFY

**Current code:**

```java
// NewPostListener.java:348-349
throw new IllegalArgumentException(
    "new_post payload must contain both 'ready_at' and 'post_id' fields; got: "
        + json);

// QuarantineReviewListener.java:284-285
throw new IllegalArgumentException(
    "quarantine_review payload must contain target_kind, target_id, "
        + "and new_status fields; got: " + json);
```

Both listeners include the raw `json` payload in the `IllegalArgumentException` message. These exceptions are caught and logged at `LOG.errorf`:

```java
// NewPostListener.java:321-322
LOG.errorf(e, "NewPostListener: unparseable payload (dropped): %s",
        n.getParameter());
```

So the raw payload appears twice: once in the exception message (via `e.getMessage()`) and once in the log format argument (`n.getParameter()`).

**Why this is wrong / suboptimal / risky:**

The NOTIFY payload is cursor-only per spec (`ready_at` + `post_id` for `new_post`; `target_kind` + `target_id` + `new_status` for `quarantine_review`). These fields are UUIDs, timestamps, and enum values — not user content. So the exposure is negligible in practice.

However, embedding unvalidated wire data in log messages is a pattern that can propagate: a future developer copying this pattern for a channel that carries richer payloads would inadvertently log user data. The `new_post` payload is already logged via `n.getParameter()` in the catch block — the duplicate in the exception message adds no value.

**Recommended fix:**

Remove `+ json` from the `IllegalArgumentException` constructor in both `parsePayload` methods. The payload is already available in the caller's log statement via `n.getParameter()`. The exception message becomes:

```java
throw new IllegalArgumentException(
    "new_post payload must contain both 'ready_at' and 'post_id' fields");
```

**Reasoning:**

One authoritative log site (the catch block) is sufficient for debugging. Removing the payload from the exception message prevents the double-log and sets the right precedent for future NOTIFY channels.

**Trade-offs:**

- An operator reading only the exception message (without the surrounding log line) would not see the malformed payload. In practice, the stack trace and log line are always consumed together.

---

## Synthesizer-relevant observations

- SPI inventory was empty under the `**/spi/*.java` glob — the project's adapter/ingest contracts live in package roots (`MessagingAdapter`, `CapabilityFlags` in `messaging-adapter`; `Fetcher`, `StreamSource` in `core/ingest/`; `RedactionHook` in `core/audit/`). The architecture reviewer found these by name rather than by path convention.
- The module DAG is build-enforced: infochat-collector has a Maven enforcer rule (`ban-messaging-adapter-from-collector`) that explicitly prohibits depending on infochat-messaging-adapter. This is the only DAG edge that needs enforcement (the other five modules naturally follow the DAG via their dependency declarations).
- NOTIFY payload contracts match between producer and consumer: `QuarantineNotifyEmitter` produces `{"target_kind":"...","target_id":"...","new_status":"..."}` and `QuarantineReviewListener.parsePayload` consumes exactly those three fields with discriminator validation. `ReadyPromoter` produces `{"ready_at":"...","post_id":"..."}` and `NewPostListener.parsePayload` consumes exactly those two fields.
- Capability flag `supportsMarkdownLinks=false` is validated at Provider startup (AdapterRegistry gate 3, line 182-188). All three adapters declare `false`. Gate 4 validates `supportsMentionByContactId` consistency with group SPI wiring. Gate 5 enforces production-exclusion (inmemory cannot run alongside other adapters). Gate 6 enforces LOW-trust opt-in.
- Audit log coverage spans both services: `AuditLogWriter` from infochat-core is injected in infochat-collector (BootstrapLoader) and infochat-provider (AdminBootstrap, InviteCommandHandler, UnbanCommandHandler, LlmOutputSanitizer). The `AuditAction` enum defines the closed verb set; all audit-write sites use the enum, not raw strings.
