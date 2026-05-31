# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [HIGH] SECURITY — `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:324-352` (cross-cutting) — `audit_log_view`'s `redact_contact_id` / `redact_secrets_jsonb` are still no-op stubs, so `/audit` surfaces raw contact ids and unredacted `details_json` to the Provider, contradicting the spec's view-layer redaction commitment.
- [LOW] MAINTAINABILITY-RULES-DRIFT — `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:125` — the SPI method named `finalize` collides with `Object.finalize()`, a deprecated/special method name.

## Detail

### F1. `audit_log_view` redaction is unimplemented; Provider reads raw contact ids and secrets

- **Category:** SECURITY
- **Severity:** high
- **Location:** cross-cutting (see CURRENT-CODE) — `V5__identity_audit.sql:324-352`, `AuditCommandHandler.java:179-204`, `DefaultRedactionHook.java:14-21`
- **Surface:** schema / spec-internal (audit)

**Current code:**

`infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:324-352`:

```sql
CREATE OR REPLACE FUNCTION redact_contact_id(input TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN input;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION redact_secrets_jsonb(input JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN input;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE VIEW audit_log_view AS
SELECT
    ...
    redact_contact_id(actor_contact_id) AS actor_contact_id,
    ...
    redact_contact_id(target_contact_id) AS target_contact_id,
    ...
    redact_secrets_jsonb(details_json) AS details_json
FROM audit_log;
```

`infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java:179-201` (reads the view and renders the contact id straight into the reply):

```java
String dataSql = "SELECT created_at, action, actor_contact_id, target_kind, target_id "
        + "FROM audit_log_view" + where
        + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
...
        rs.getString("actor_contact_id") != null
                ? rs.getString("actor_contact_id") : "-",
```

`infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java:14-21` (write-time hook explicitly defers contact-id redaction to the view):

```java
 * <p>The hook does NOT touch {@code target_contact_id} or any
 * contact-id-shaped substring inside {@code details_json}. Per
 * spec §Secrets handling, contact-id redaction is "outside the
 * audit log" and is handled at read time by the
 * {@code audit_log_view} (V5 §2.1.9).</p>
```

The companion test `AuditCommandHandlerTest.java:84-86` documents the gap verbatim: "redact_contact_id is a V5 stub (returns input as-is) — real masking lands when the redaction function is implemented."

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Operational ("Audit log view") makes an explicit, closed-list spec commitment:

> The closed list of redacted columns is the spec-level commitment:
> `actor_contact_id` — redacted to prefix + ellipsis + suffix form ... `target_contact_id` — same redaction ... `details_json` — passed through the secrets-catalogue redactor ... so any matched API-key shape or contact-id-shaped value is masked **before the Provider sees the row**.

`docs/spec/security.md` §DB roles places the redaction at the view boundary precisely because "The Provider role has `SELECT` on the view (never on the underlying table), so the view is the Provider's only read path into audit history." The whole architecture of that boundary is that the DB role grant + the view body together guarantee the Provider physically cannot read an unredacted contact id. Today the grant half is enforced (Provider has SELECT only on the view) but the redaction half is a pass-through, so the guarantee is hollow.

Concrete threat: a bot admin runs `/audit` (or `/audit --actor <x>`). Every row's `actor_contact_id` is rendered into the chat reply in full — a SimpleX queue address or Signal ACI/phone number in plaintext. The spec's prefix+ellipsis+suffix masking is the control that keeps a screenshot of `/audit` output, or the admin's own (possibly lower-trust) adapter client, from leaking the full cryptographic identity of every actor in the audit trail. `details_json` is worse: the write-time `DefaultRedactionHook` only runs for rows inserted through `AuditLogWriter`. Rows written by the SQL stored procedures (`approve_quarantine`, `reject_quarantine` in V21, `delete_preban_user` in V5/V24) and by the V27 data-migration `INSERT` bypass the Java hook entirely, so their `details_json` is redacted *only* by the view — which is a no-op. Any secret-shaped or contact-id-shaped value placed in those payloads reaches the admin unmasked.

This is genuine spec-drift (a code path that contradicts a `docs/spec/` commitment), and because the contract it breaks is a confidentiality control at a trust boundary, it is a SECURITY finding rather than a plain MAINTAINABILITY one.

**Recommended fix:**

Supersede the two stub bodies with real redactors in a new forward migration (the V5 functions were authored with `CREATE OR REPLACE FUNCTION` precisely so the body can be swapped without touching the view or the grants). The contact-id redactor must implement the same prefix+ellipsis+suffix shape the non-audit `Redactor` already uses, and `redact_secrets_jsonb` must apply the secrets catalogue:

```sql
-- Vnn__audit_view_redaction.sql
CREATE OR REPLACE FUNCTION redact_contact_id(input TEXT)
RETURNS TEXT AS $$
BEGIN
    IF input IS NULL OR length(input) <= 10 THEN
        RETURN input;  -- too short to split; design notes define the floor
    END IF;
    -- prefix + ellipsis + suffix, matching docs/spec/security.md §Secrets handling
    RETURN left(input, 4) || '…' || right(input, 4);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- redact_secrets_jsonb: apply the same closed catalogue the Java Redactor
-- uses (API-key shapes + contact-id shapes). If a pure-SQL port is
-- impractical, route ALL audit reads through a SECURITY DEFINER function
-- that calls the Java-side redactor, or have the view call a PL/Java /
-- pl/pgSQL regex pass mirroring core/log/Redactor.
```

If a faithful SQL port of the secrets catalogue is not practical, the alternative is to keep `details_json` redaction authoritative at write time (the `DefaultRedactionHook` path) AND make every SQL-procedure audit insert route its `details_json` through the same catalogue before insert — but the *contact-id* columns still need the view-level redactor because they are populated by raw column copy, not by the hook.

**Reasoning:**

The fix restores the invariant the spec actually promises: the Provider's only audit read path returns masked identities and masked secrets, enforced at the boundary the role-grant matrix was designed around. Putting the contact-id redaction in the view (not the Java renderer) is correct because multiple write paths feed `audit_log` and only the view is common to every Provider read; redacting in `AuditCommandHandler` alone would miss any future Provider audit-read site.

**Trade-offs:**

A SQL-level secrets redactor duplicates the catalogue that already lives in `core/log/Redactor`, creating a second editing site that can drift from the Java one. That is a real maintenance cost, but it is the price of honoring the "view is the Provider's only read path" architecture; the duplication can be guarded with a CI test that feeds the same fixtures through both redactors and asserts equal output.

**Alternative options:**

- **Option A** (the recommended fix above) — real redactors in the view body.
- **Option B** — drop the SQL redactor functions, revoke the Provider's direct view SELECT, and expose audit reads only through a `SECURITY DEFINER` function that calls into a single redaction implementation. Pros: one redaction code site. Cons: larger change; moves the read path off the plain view the spec describes, which is itself a (smaller) spec deviation.

---

### F2. SPI method named `finalize` shadows `Object.finalize()`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:125`
- **Surface:** SPI

**Current code:**

```java
void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException;
```

**Why this is wrong / suboptimal / risky:**

`finalize` is the name of the `Object.finalize()` method, deprecated for removal since JDK 9 and removed-track in later releases. The two-argument SPI method is an overload, not an override, so there is no functional bug today — but the collision is a readability and tooling hazard on a contract that is part of the cross-module surface (it appears in the `MessagingAdapter` SPI, every adapter impl, and the progress-notifier call path). Static analyzers and `javac` lint flag `finalize` declarations; a reader scanning the interface has to confirm it is the progress-finalize semantic, not the GC finalizer. CLAUDE.md §Descriptive names asks for identifiers a new reader understands without context; `finalize` actively misleads here.

**Recommended fix:**

```java
void complete(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException;
```

Rename across the SPI, the three adapter impls, and the progress-notifier caller. `complete` matches the spec's own terminal-state vocabulary (`messaging.md` §Progress notifications uses "terminal `COMPLETED`").

**Reasoning:**

A name that does not collide with a special `Object` method removes the lint noise and the momentary ambiguity on every read of the interface, with no behavioral change.

**Trade-offs:**

A rename touches every adapter impl and the notifier caller — a mechanical but non-trivial diff across the module boundary. Given the method is in-process-only and has no serialized/persisted name dependence, the rename is otherwise risk-free.

## Synthesizer-relevant observations

The cross-module contract surface is in good shape where it was checked and those areas are deliberately omitted from findings: the 6-module Maven DAG matches `09-reference.md` byte-for-byte (collector correctly has no messaging-adapter dependency; the three sibling shared modules do not depend on each other); the `supportsMarkdownLinks=false` startup gate is enforced over the full activated-adapter set in `AdapterRegistry`; the `new_post` and `quarantine_review` NOTIFY producer/consumer payload shapes agree and both honor the same-transaction emit rule; the `provider_state` compare-and-swap cursor uses the compound `(cursor_high, cursor_low_kind, cursor_low_id)` tuple the spec requires, and both channels' rows are seeded with `ON CONFLICT DO NOTHING`; profile names (`laptop`/`vps`/`pi`/`remote-llm`) are consistent across the property surface; the `users.registration_state` CHECK was correctly narrowed to drop `group_only` in V27. The `new_price_snapshot` channel is emitted by the Collector but has no production consumer in the Provider (the asset reader always reads the DB row directly) — this is consistent with the spec's "table read is the correctness guarantee, NOTIFY is the latency optimization" framing and is not a finding, but a synthesizer comparing modules may notice the emitted-but-unconsumed channel.
