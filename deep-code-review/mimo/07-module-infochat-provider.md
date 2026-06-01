# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-02 00:05
**Reviewer:** senior-developer (mimo)

## Headline findings

- [HIGH] MAINTAINABILITY-RULES-DRIFT — DigestScheduler.java:175 — `queryActiveGroups` omits `approval_status = 'approved'` filter, violating spec commitment that only approved groups receive periodic digests
- [MEDIUM] SIMPLIFICATION — BanCommandHandler, GrantAdminCommandHandler, RevokeAdminCommandHandler — identical `quoteJsonString` method duplicated across three command handlers

## Detail

### F1. DigestScheduler queries all non-removed groups, not just approved ones

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** HIGH
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java:174-176

**Current code:**

```java
private List<GroupRow> queryActiveGroups() {
    List<GroupRow> groups = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, timezone FROM groups WHERE removed_at IS NULL")) {
```

**Why this is wrong / suboptimal / risky:**

The spec at `docs/spec/commands.md` §Periodic group digests states explicitly:

> "The digest scheduler selects groups where `approval_status = 'approved' AND removed_at IS NULL` (D47)."

And:

> "Pending and rejected groups never receive periodic digests."

The current query filters only on `removed_at IS NULL`. A group with `approval_status = 'pending'` or `approval_status = 'rejected'` that has not been removed will be picked up by `queryActiveGroups` and have digest slots scheduled. The `DigestWorker` does not re-check `approval_status` before rendering and delivering, so a pending or rejected group could receive a periodic digest message — a direct spec violation.

**Recommended fix:**

```java
private static final String SELECT_ACTIVE_GROUPS_SQL =
        "SELECT id, timezone FROM groups "
                + "WHERE approval_status = 'approved' AND removed_at IS NULL";

private List<GroupRow> queryActiveGroups() {
    List<GroupRow> groups = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_GROUPS_SQL)) {
```

**Reasoning:**

The query must mirror the spec's two-predicate gate exactly. Adding `approval_status = 'approved'` to the WHERE clause is the minimal, correct fix. The `DigestWorker` receiving a slot for a non-approved group would be a downstream defense-in-depth issue, but the scheduler is the correct place to enforce this — the spec's language ("The digest scheduler selects groups where...") names the scheduler as the enforcement point.

**Trade-offs:**

None — the fix is strictly better. It eliminates a class of spec-violating behavior with a one-clause addition to an existing query.

---

### F2. `quoteJsonString` duplicated across three command handlers

- **Category:** SIMPLIFICATION
- **Severity:** MEDIUM
- **Location:**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java:462-486
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java:368-392
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java:364-388

**Current code:**

All three files contain a byte-identical `quoteJsonString` method:

```java
private static String quoteJsonString(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            default -> {
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
    }
    sb.append('"');
    return sb.toString();
}
```

**Why this is wrong / suboptimal / risky:**

Three identical copies of the same method across three files in the same package. CLAUDE.md §Coding style says "three similar lines beats a class" as a bias against premature abstraction, but this is 25 identical lines in three places — not three similar lines. Any future fix to JSON escaping (e.g. adding a missing control character) must be applied in all three locations, which is a maintenance hazard.

**Recommended fix:**

Extract to a package-private utility method in a shared location. The most natural place is a small `JsonEscapes` utility class in `app.zcat.infochat.provider.command`:

```java
// app/zcat/infochat/provider/command/JsonEscapes.java
package app.zcat.infochat.provider.command;

final class JsonEscapes {
    private JsonEscapes() {}

    static String quoteJsonString(String s) {
        // ... the shared implementation
    }
}
```

Then each handler replaces its local `quoteJsonString` with `JsonEscapes.quoteJsonString(...)`.

**Reasoning:**

One copy eliminates the diverge-on-edit risk. The class is package-private so it stays within the command package boundary and does not widen the public API surface.

**Trade-offs:**

One additional file (4 lines of class boilerplate + the method). The alternative — leaving three copies — has zero upside and ongoing maintenance risk.
