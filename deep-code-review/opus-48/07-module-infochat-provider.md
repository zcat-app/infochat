# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java:175 — digest scheduler selects groups by `removed_at IS NULL` only and never filters `approval_status = 'approved'`, so pending and rejected groups receive periodic digests in violation of commands.md §Periodic group digests.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:513-530 — the chat-mode body cap is enforced after the anchor-clear DELETE and confirm/probation reads, so an oversized chat-mode message performs DB writes the spec says it must never reach.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java:326-336 — the only digest integration test seeds exclusively `approval_status='approved'` groups, so it cannot detect the missing approval-status filter (masks F1).
- [low] SECURITY — infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:191 — multi-word closed-list tokens are matched with a single literal space, so an LLM emitting `/invite  create` or a newline-split admin token evades the outbound strip.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java:37-48 — the class Javadoc still describes the pre-T2-F "group scope short-circuits, not in v1" behavior that the method body no longer implements.

## Detail

### F1. Digest scheduler fires for pending and rejected groups

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java:171-187 (and DigestWorker.java:77-133, which also does not re-check)

**Current code:**

```java
private List<GroupRow> queryActiveGroups() {
    List<GroupRow> groups = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, timezone FROM groups WHERE removed_at IS NULL")) {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groups.add(new GroupRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("timezone")));
            }
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to query active groups for digest scheduling", e);
    }
    return groups;
}
```

**Why this is wrong / suboptimal / risky:**

commands.md §Periodic group digests is explicit and unambiguous: "The digest scheduler selects groups where `approval_status = 'approved' AND removed_at IS NULL` (D47). ... Pending and rejected groups never receive periodic digests." The query above omits the `approval_status = 'approved'` predicate, and neither `processSlot` nor `DigestWorker.executeSlot` re-checks approval status before firing a `DigestSlot` event and calling `adapter.send(...)`.

The effect is a contract break with a security dimension: a group in `pending` state (a `@mention` arrived but no admin has approved it) or in `rejected` state (an admin explicitly rejected it) will still be picked up on the next slot window and have a full digest — post headlines, source URLs, and LLM prose summarizing the group's would-be subscriptions — delivered into it. The entire D47 group-authorization gate (security.md §Authorization model step 3.5) exists to keep the bot silent in unapproved groups; the digest path bypasses that gate entirely because it is system-initiated and never routes through `GroupApprovalCheck`. A rejected group whose admin deliberately stopped interaction continues to receive bot output indefinitely.

This is also inconsistent with `RejectGroupCommandHandler`, which the spec says must "stop digests for this group"; with the query as written, rejection has no effect on digest scheduling.

**Recommended fix:**

```java
private List<GroupRow> queryActiveGroups() {
    List<GroupRow> groups = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, timezone FROM groups "
                         + "WHERE approval_status = 'approved' AND removed_at IS NULL")) {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groups.add(new GroupRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("timezone")));
            }
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to query active groups for digest scheduling", e);
    }
    return groups;
}
```

**Reasoning:**

The fix restores the exact spec predicate at the single point where the candidate group set is computed. Filtering at the scheduler query (rather than re-checking per slot in the worker) is the cheapest correct place: a non-approved group never produces a `DigestSlot` event, so no missed-slot audit row, no sentinel cache write, and no worker fan-out occurs for it. A group that transitions away from `approved` via `/reject-group` is excluded from the very next scheduling pass, matching the spec's "excluded from the next scheduling pass" wording.

**Trade-offs:**

None — the fix is strictly more correct and strictly cheaper (fewer rows iterated, no work for unapproved groups).

---

### F2. Chat-mode body cap runs after DB writes the spec forbids for oversized messages

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:509-543

**Current code:**

```java
// Step 4.6 — anchor-clear on non-/retry input (M1-065).
if (!"retry".equals(commandName)) {
    UUID anchorActorId = snapshot.get().id();
    UUID anchorScopeId = resolveChatScopeId(msg.scope(), anchorActorId, adapterName);
    summaryAnchorRepository.clear(anchorActorId, anchorScopeId);
}

// Step 6 — Parse + dispatch ...
        if (normalized.length() > chatBodyCap) {
            body = bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE);
        } else {
            ...
            body = chatAgent.handle(actorId, scopeKind, scopeId, normalized);
        }
```

**Why this is wrong / suboptimal / risky:**

commands.md §Input length caps commits, for the chat-mode body cap: "Beyond the cap → friendly error, no chat agent invocation, no LLM call," and the cross-cutting clause "an oversized message never reaches the parser, the chat agent, the LLM, or any DB query past the rate-limit counter increment."

The byte-level defense (`max-inbound-body-bytes`, default 65536) fires early at line 335, but the chat-mode body cap (`chat.body-cap`, default 2048 characters) is only checked at line 530 — after the step 4.6 anchor-clear, which issues a `summaryAnchorRepository.clear(...)` DELETE, and after the step 4.5 confirm peek and step 5 probation reads. A chat-mode message between 2048 characters and 64 KB therefore passes the early byte gate, performs a DB write (the anchor DELETE) plus several reads, and only then is rejected by the chat-mode cap. That is precisely the "DB query past the rate-limit counter increment" the spec rules out for an oversized message.

The anchor DELETE is also semantically wrong on this path: an oversized message is not valid input, yet it clears the user's `/retry` anchor as if it were a real non-`/retry` command, so an oversized typo silently destroys the ability to `/retry` the previous summary.

**Recommended fix:**

Apply the chat-mode body cap for non-slash input immediately after normalization (alongside the existing byte cap), before the step 4.5/4.6/5 side-effecting steps:

```java
// Step 1.7b — chat-mode body cap, before any anchor/confirm/probation
// side effects (commands.md §Input length caps: an oversized chat-mode
// message reaches no DB query past the rate-limit counter).
if (!normalized.startsWith("/") && normalized.length() > chatBodyCap) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE));
    return;
}
```

placed right after the `normalized.isEmpty()` guard at line 342, and remove the now-redundant `length() > chatBodyCap` branch from the dispatch block. The cap needs the resolved snapshot only for the probation/confirm steps it now precedes, and it depends on nothing but `normalized`, so it can move up safely.

**Reasoning:**

Moving the cap to the top of the body-content phase makes the spec guarantee hold structurally: an oversized chat-mode message hits the friendly error and returns before the anchor DELETE, the confirm peek, the probation reads, and the chat-agent call. It also stops oversized input from clobbering the `/retry` anchor.

**Trade-offs:**

The cap moves above the probation gate, so an oversized chat-mode message from a probation user now gets the "too large" reply rather than the probation reply. Both are friendly deterministic errors with no LLM/write cost, and chat mode is blocked during probation regardless, so the user-visible difference is immaterial and the spec orders the length cap before parsing in any case.

---

### F3. The digest integration test only seeds approved groups, masking the F1 gap

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java:321-336

**Current code:**

```java
// approval_status='approved' (M1-112): bypass the D47 step-3.5
// gate so /retry --digest reaches dispatch. ...
exec(conn,
        "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                + " VALUES (?, 'inmemory', ?, 'Digest IT Group 1', 'UTC', 'approved')"
                + " ON CONFLICT (adapter, upstream_group_id)"
                + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
        GROUP_1, UPSTREAM_G1);
```

**Why this is wrong / suboptimal / risky:**

Every group the digest round-trip IT inserts is `approval_status='approved'`. The test exercises the happy path and proves digests reach approved groups, but it has no negative case — a `pending` or `rejected` group that must NOT receive a digest. Because the scheduler query (F1) never filters approval status, the missing filter is invisible to this suite: there is no group in the fixture whose presence in the delivered set would fail an assertion. This is the gap that let F1 land green. Per verification.md the test suite is supposed to prove the spec's commitments; the "pending/rejected groups never receive periodic digests" commitment is currently unverified.

This is not a test-integrity violation in the §8 sense (nothing was weakened or disabled); it is a coverage gap that co-exists with, and conceals, the F1 defect.

**Recommended fix:**

Add a negative-case group to the fixture and assert it is never scheduled or delivered to:

```java
// A pending and a rejected group must NOT receive a digest slot.
exec(conn,
        "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                + " VALUES (?, 'inmemory', ?, 'Digest IT Pending', 'UTC', 'pending')"
                + " ON CONFLICT (adapter, upstream_group_id)"
                + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'pending'",
        GROUP_PENDING, UPSTREAM_PENDING);
// ... after tickAt(...) fires the slot window:
assertThat(deliveredGroupIds()).doesNotContain(GROUP_PENDING);
assertThat(summaryCacheRowsFor(GROUP_PENDING)).isEmpty();
```

**Reasoning:**

A negative assertion on a non-approved group is the test that fails today against the current scheduler and passes once F1 is fixed. It converts the spec commitment into an enforced invariant and prevents a future edit from re-introducing the gap.

**Trade-offs:**

Slightly larger fixture and one more Testcontainers row. None material.

---

### F4. Closed-list sanitizer can be evaded by irregular whitespace in multi-word tokens

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:87-118, 187-209

**Current code:**

```java
static final List<String> CLOSED_LIST = List.of(
        ...
        "/invite create",
        "/invite list",
        "/invite revoke",
        "/quarantine list",
        ...
        "/list-sources --all",
        "/list-sources --include-deleted",
        ...);
...
for (String token : CLOSED_LIST) {
    Pattern p = Pattern.compile(Pattern.quote(token) + "(?=$|[^a-zA-Z0-9\\-])");
    ...
}
```

**Why this is wrong / suboptimal / risky:**

The multi-word entries (`/invite create`, `/quarantine approve`, `/list-sources --all`, etc.) are matched with `Pattern.quote`, which encodes the single literal space between words. An LLM that emits `/invite  create` (two spaces), `/invite\tcreate`, or a newline between the words produces a string a human reader still parses as the admin command, but the sanitizer leaves untouched. The sanitizer is defense-in-depth — admin commands still require `is_admin=true` to do anything, and a copy-pasted reply cannot self-escalate — so the blast radius is the social-engineering surface the spec wants closed (security.md §LLM output sanitizer), not direct privilege escalation. That is why this is low and not higher.

**Recommended fix:**

Normalize internal whitespace to a flexible matcher for the space-bearing tokens:

```java
// Build the matcher from the token with internal runs of whitespace
// allowed to be any 1+ whitespace, so "/invite  create" and
// "/invite\ncreate" are caught the same as "/invite create".
String pattern = Arrays.stream(token.split(" "))
        .map(Pattern::quote)
        .collect(Collectors.joining("\\s+"));
Pattern p = Pattern.compile(pattern + "(?=$|[^a-zA-Z0-9\\-])");
```

(Single-word tokens like `/ban` are unaffected — `split(" ")` yields one element and the join is a no-op.)

**Reasoning:**

The match set is derived from the spec's closed list; what the spec wants is that the *command*, not its exact byte spacing, is stripped. Allowing `\s+` between the words matches the human-readable form an attacker would actually use while keeping the per-occurrence WARN/audit accounting intact (one match → one row, unchanged).

**Trade-offs:**

A token with a literal multi-space that the model legitimately intended as prose (vanishingly unlikely for `/invite   create`) would still be flattened to `[redacted command]`. The closed list is all admin verbs, so over-stripping here is harmless.

---

### F5. LangCommandHandler Javadoc contradicts its implemented behavior

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java:37-48

**Current code:**

```java
 *   <li><b>Permission gate.</b> DM scope is the caller's own scope
 *       ... Group scope
 *       short-circuits to
 *       {@link BundleKeys#ERROR_LANG_GROUP_ADMIN_NOT_IN_V1} per the
 *       M1-054 ... SPI-freeze precedent — the frozen
 *       {@code CommandHandler.handle(ScopeRef, String)} SPI does not
 *       carry the inbound caller's contact id in group scope, so the
 *       handler cannot consult {@code group_membership} to identify
 *       a group admin. T2-F lands the actor seam and the group-admin
 *       proceed path.</li>
```

**Why this is wrong / suboptimal / risky:**

The Javadoc states group scope unconditionally short-circuits with "not in v1" because the handler "cannot consult `group_membership`." The actual `handle` body (lines 119-131) does resolve the sender via `inboundContext.senderContactId()`, looks up the group, and proceeds when the caller is a bot admin or group admin — exactly the group-admin proceed path the Javadoc says is deferred to a future ticket. CLAUDE.md §Comment important code requires why-comments to track the code; a doc block that describes behavior the method no longer has is worse than no comment, because a maintainer reading it will believe `/lang` is DM-only in groups and may "restore" the (now-incorrect) short-circuit. `FollowTagCommandHandler` has the same live group-admin path with a similarly stale block (lines 37-41).

**Recommended fix:**

Rewrite the permission-gate bullet to describe the implemented behavior:

```java
 *   <li><b>Permission gate.</b> DM scope is the caller's own scope.
 *       Group scope resolves the sender via
 *       {@code InboundContext.senderContactId()} and proceeds only
 *       when the caller is a bot admin or the group's group admin
 *       (commands.md §Conversation control: "/lang ... Group: group
 *       admin only"); otherwise it returns
 *       {@link BundleKeys#ERROR_LANG_GROUP_ADMIN_NOT_IN_V1}.</li>
```

and apply the equivalent correction to the `FollowTagCommandHandler` class Javadoc.

**Reasoning:**

Aligning the doc with the code removes a concrete trap for the next editor and makes the (correct) group-admin enforcement self-documenting. No behavior change.

**Trade-offs:**

None — comment-only.
