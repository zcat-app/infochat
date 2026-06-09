# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-09 18:40
**Reviewer:** senior-developer (opus)

## Headline findings

- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:152-167 — javadoc documents an `xmax`-based three-way EMITTED/SUPPRESSED discriminator that the code does not implement (it uses a plain `rs.next()`), so the comment describes a mechanism a reader will look for and not find.
- [low] PERFORMANCE — infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:84-93 — each catalogue pattern is scanned twice per `redact` call (`m.find()` guard followed by `m.replaceAll`), doubling the regex work on the hot log/audit path with no behavioral benefit.

## Detail

### F1. ThrottledAdminNotifier javadoc describes an xmax discriminator the code does not use

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:152-167 (javadoc) vs. 300-306 (`runNotify` implementation)

**Current code:**

The field javadoc on `upsertSql`:

```java
 * <p><b>Discriminator: row presence in RETURNING.</b>
 * {@code ON CONFLICT DO UPDATE ... WHERE <window-elapsed>}
 * runs the UPDATE only when the conditional is true; if false,
 * the row is locked but no SET applies and RETURNING produces
 * NO ROW. So the Java side observes:
 * <ul>
 *   <li>RETURNING returned a row + {@code xmax = 0} → fresh
 *       INSERT (first time for this key) → EMITTED.</li>
 *   <li>RETURNING returned a row + {@code xmax != 0} → UPDATE
 *       fired (window had elapsed) → EMITTED.</li>
 *   <li>RETURNING returned NO ROW → CONFLICT but WHERE filtered
 *       out the UPDATE → within-window → SUPPRESSED.</li>
 * </ul>
```

The actual discrimination in `runNotify`:

```java
try (ResultSet rs = ps.executeQuery()) {
    // RETURNING produces a row iff the INSERT
    // succeeded OR the DO UPDATE WHERE matched —
    // either way an emit happened. No row → CONFLICT
    // but WHERE filtered out the UPDATE → suppressed.
    emitted = rs.next();
}
```

**Why this is wrong / suboptimal / risky:**

The javadoc spends three bullet points on an `xmax = 0` vs `xmax != 0` distinction. `xmax` is never selected by `upsertSql` (the `RETURNING` clause is `RETURNING notification_key`) and never read by the code — `runNotify` collapses both "fresh INSERT" and "UPDATE fired" into the single boolean `emitted = rs.next()`, which is correct because both cases need the same EMITTED outcome. The comment immediately above the implementation (lines 301-304) describes the real, simpler mechanism accurately; the field javadoc contradicts it. A future maintainer reading the class-level documentation will look for an `xmax` projection that does not exist and may conclude the code is buggy or incomplete, or may add the `xmax` column "to match the docs." This violates CLAUDE.md §Coding style "WHY-not-WHAT … don't narrate code that named identifiers already explain" by documenting a design that was considered but not taken.

**Recommended fix:**

Replace the three-bullet `xmax` block with the row-presence rule the code actually implements:

```java
 * <p><b>Discriminator: row presence in RETURNING.</b>
 * {@code ON CONFLICT DO UPDATE ... WHERE <window-elapsed>}
 * runs the UPDATE only when the conditional is true. A fresh
 * INSERT and a WHERE-matched UPDATE both project the row through
 * {@code RETURNING}; a CONFLICT whose WHERE filtered out the
 * UPDATE locks the row but applies no SET, so RETURNING produces
 * no row. The Java side therefore reads exactly one bit:
 * {@code rs.next()} true → EMITTED (insert or window-elapsed
 * update), false → SUPPRESSED (within window). The
 * suppressed branch then bumps {@code suppressed_count} via
 * {@link #SUPPRESSED_BUMP_SQL}.</p>
```

**Reasoning:**

The fix makes the documentation match the code, removing a phantom mechanism. The simpler row-presence rule is correct and is what ships; documenting it accurately is strictly better than documenting a more complex alternative that was not built.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. Redactor scans each catalogue pattern twice per redact call

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:84-93

**Current code:**

```java
for (Pattern pattern : CATALOGUE) {
    Matcher m = pattern.matcher(new InterruptibleCharSequence(current, deadlineNanos));
    if (m.find()) {
        if (m.groupCount() > 0) {
            current = m.replaceAll("$1" + Matcher.quoteReplacement(REDACTED));
        } else {
            current = m.replaceAll(Matcher.quoteReplacement(REDACTED));
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

`Matcher.replaceAll` internally calls `reset()` and then re-scans the entire input from position 0. The preceding `m.find()` therefore performs a full first scan whose only product is the boolean used to decide whether to call `replaceAll` — which itself re-scans. For inputs that contain no match (the overwhelmingly common case for ordinary log lines: every line is scanned by all seven patterns), each pattern still runs `find()` to completion. For inputs that do match, the matched portion is scanned twice. This is the redaction hot path: it runs on every console `LogRecord` message and every `String` parameter (the `isLoggable` filter loops over `record.getParameters()`), and on every `audit_log` write via `DefaultRedactionHook`. The `find()`-then-`replaceAll` pair doubles the regex cost, and `groupCount()` is a static property of the compiled pattern, not a per-match result, so it does not require a prior `find()`.

`Matcher.replaceAll` returns the input unchanged when there is no match, so the `if (m.find())` guard buys nothing functionally — `current = m.replaceAll(...)` unconditionally yields the same result.

**Recommended fix:**

```java
for (Pattern pattern : CATALOGUE) {
    Matcher m = pattern.matcher(new InterruptibleCharSequence(current, deadlineNanos));
    String replacement = pattern.matcher("").groupCount() > 0
            ? "$1" + Matcher.quoteReplacement(REDACTED)
            : Matcher.quoteReplacement(REDACTED);
    current = m.replaceAll(replacement);
}
```

Or, since `groupCount()` is constant per compiled pattern, precompute the replacement strings once alongside `CATALOGUE` so the per-call loop is a single `replaceAll`:

```java
// built once next to CATALOGUE
static final List<String> REPLACEMENTS = CATALOGUE.stream()
        .map(p -> p.matcher("").groupCount() > 0
                ? "$1" + Matcher.quoteReplacement(REDACTED)
                : Matcher.quoteReplacement(REDACTED))
        .toList();
// in redact():
for (int i = 0; i < CATALOGUE.size(); i++) {
    Matcher m = CATALOGUE.get(i).matcher(new InterruptibleCharSequence(current, deadlineNanos));
    current = m.replaceAll(REPLACEMENTS.get(i));
}
```

**Reasoning:**

Both forms remove the redundant `find()` scan, halving the worst-case work and eliminating a full scan on the common no-match path. Behavior is unchanged: `replaceAll` on a no-match input returns the same string instance, so the assignment is a no-op exactly as the guarded version was. The watchdog deadline still applies because the `InterruptibleCharSequence` wrapper is still consulted on every `charAt` during the single scan.

**Trade-offs:**

The precompute variant adds one static list and couples it positionally to `CATALOGUE`; if that coupling feels fragile, the inline `pattern.matcher("").groupCount()` form keeps everything in the loop at the cost of constructing a throwaway empty matcher per pattern per call (cheap, but still per-call). The inline form is the safer minimal change.

**Alternative options:**

- **Option A** (the inline `replaceAll` without the `find()` guard, above) — minimal diff, no new field.
- **Option B** — precompute `REPLACEMENTS` once — removes the per-call `groupCount` matcher construction entirely; cons: positional coupling to `CATALOGUE`.

## Synthesizer-relevant observations

- The write-side Java secret catalogue (`Redactor.CATALOGUE`) and the read-side SQL mirror (`redact_secrets_jsonb`, V33) are hand-kept in sync by textual identity and guarded only by `RedactorSqlParityIT`. The IT itself documents a known blind spot: because the Anthropic family `sk-ant-…` is a strict prefix of the OpenAI `sk-…` family, dropping only the Anthropic line from the SQL mirror is shadowed by the OpenAI pattern and not detectable by sample masking. This is a cross-module contract (core owns both halves) worth noting for the architecture lens — the parity guarantee has a corner that the test cannot cover, and the two regex strings live in two languages with different `\s` semantics (the reason the separator class is spelled out explicitly).
- The test source tree in this module is deliberately excluded from NullAway/Error Prone (`pom.xml` `default-testCompile` clears the inherited `compilerArgs`), with a comment deferring test-tree onboarding to a follow-up. Main sources are gated. This is a documented, intentional gap rather than a violation, but the deferral spans the whole module's `src/test` and is relevant to any cross-module assessment of how complete the D48 NullAway rollout actually is.
