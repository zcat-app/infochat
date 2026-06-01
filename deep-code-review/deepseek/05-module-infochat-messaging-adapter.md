# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [HIGH] MAINTAINABILITY-RULES-DRIFT — `SignalConfig:63-79` — `SignalConfig` eagerly validates at `@PostConstruct` via `Files.exists`/`isWritable` but the `filesystem` state can change between boot and first use; the check provides no runtime guarantee and produces a misleading "fails at boot" security promise that a post-boot filesystem remount defeats
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — `SimpleXConfig:73-88` — `SimpleXConfig.validate()` performs the same elapsed-filesystem check as SignalConfig but is NOT annotated `@Startup`, so validation depends on Provider calling `start()`; a configured but never-started SimpleX adapter skips all validation
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — `SignalJsonRpcClient:87` — `MAX_INBOUND_LINE_CHARS` for signal-cli's localhost daemon is set to 16,384, matching the SPI's `maxInboundMessageBytes`, but `BufferedReader` is not bounded and the character-at-a-time `read()` loop with a `StringBuilder` discards oversize lines one character at a time from the JVM's heap after the OS has already buffered the full TCP segment
- [LOW] MAINTAINABILITY-RULES-DRIFT — `SignalAdapter:93` — `binary`, `dataDir`, `account`, `botAci`, and `daemonEndpoint` are `@Nullable` but only the capability-only constructor sets them null; a null-check reliance on `botAci` for `groupHandler()` throws `IllegalStateException` instead of surfacing the more diagnostic message from `requireConnected()`

## Detail

### F1. `SignalConfig.validate()` provides a misleading boot-time guarantee

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** HIGH
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConfig.java:63-79`

**Current code:**
```java
@PostConstruct
public void validate() {
    Path binaryPath = Path.of(binary);
    if (!Files.exists(binaryPath) || !Files.isExecutable(binaryPath)) {
        throw new IllegalStateException(
                BINARY_KEY + " must point to an existing, executable signal-cli binary: " + binary);
    }
    Path dataDirPath = Path.of(dataDir);
    if (!Files.isDirectory(dataDirPath) || !Files.isWritable(dataDirPath)) {
        throw new IllegalStateException(
                DATA_DIR_KEY + " must be an existing, writable directory: " + dataDir);
    }
    if (account.isBlank()) {
        throw new IllegalStateException(
                ACCOUNT_KEY + " must be a non-empty signal-cli account identifier");
    }
}
```

**Why this is wrong / suboptimal / risky:**

The class Javadoc claims this "fails startup" on bad config, and the `@Startup @ApplicationScoped` design makes `validate()` run eagerly at Provider boot. The filesystem checks (`Files.exists`, `Files.isExecutable`, `Files.isWritable`) use a single elapsed instant: they check that the binary and data directory existed and were writable at boot time. They do NOT check that the paths still exist and are accessible at the time `SignalAdapter.start()` uses them, which can be seconds, minutes, or longer after boot.

The gap: a filesystem remount, a volume detach, an NFS disconnect, or a container restart that shuffles the binary mount between boot and `start()` all defeat the check. The operator sees a clean startup and then gets a crash later at `start()` with a less helpful error path (the `ProcessBuilder.start()` IOException or the `awaitEndpoint` timeout). The current design creates a false confidence: "if boot succeeded, the binary is good" — which is not a guarantee the code delivers.

This is not a security vulnerability because the subprocess runs inside the same trust boundary. It is a spec-drift issue: the Javadoc promises "a misconfigured deployment fails startup, not the first `start()`" but only catches one class of misconfiguration (pre-boot filesystem state). A post-boot filesystem change bypasses the guard.

**Recommended fix:**

Remove the filesystem checks from `validate()` entirely, or rename `validate()` to `checkPreconditions()` and document that it validates boot-time config shape only (non-empty strings, port ranges). Move the elapsed-time filesystem check into `SignalAdapter.start()` where it is actionable. Since the Javadoc already says `SignalAdapter.start()` probes the endpoint via `awaitEndpoint`, the missing binary would be caught there naturally (the `ProcessBuilder.start()` throws `IOException`).

Alternatively, accept that the check is best-effort and update the Javadoc to qualify: "validates config shape at boot time; the binary and data dir are also checked at `start()` time." The `@Startup` annotation remains valuable for catching blank account strings before any traffic arrives.

```java
// Option A: honest doc
@PostConstruct
public void validate() {
    // Shape checks only — the binary must exist and be executable,
    // and the data directory must be a directory. These are
    // boot-time preconditions checked promptly; a post-boot
    // filesystem change can still fail at SignalAdapter.start()
    // (ProcessBuilder.start() throws IOException).
    ...
}
```

**Reasoning:**

The Javadoc currently over-promises. Fixing the doc to match what the code actually guarantees is the simplest remediation that does not change behavior. Moving checks to `start()` would be more correct but requires more invasive changes across the module.

**Trade-offs:**

Moving the checks to `start()` shifts an early-detection mechanism to a later phase. A deployment with a missing binary would crash at the first inbound message (when Provider calls `start()`) rather than at boot. The boot-time `@PostConstruct` check still catches blank accounts and gross config errors, which is its primary value. The documentation change makes the contract honest.

---

### F2. `SimpleXConfig.validate()` is never called for idle adapters

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java:73-88`

**Current code:**
```java
public void validate() {
    Path binaryPath = Path.of(binary);
    if (!Files.exists(binaryPath) || !Files.isExecutable(binaryPath)) {
        throw new IllegalStateException(
                BINARY_KEY + " must point to an existing, executable simplex-chat binary: " + binary);
    }
    Path dataDirPath = Path.of(dataDir);
    if (!Files.isDirectory(dataDirPath) || !Files.isWritable(dataDirPath)) {
        throw new IllegalStateException(
                DATA_DIR_KEY + " must be an existing, writable directory: " + dataDir);
    }
    if (wsPort < 1 || wsPort > 65535) {
        throw new IllegalStateException(
                WS_PORT_KEY + " must be a TCP port in 1..65535: " + wsPort);
    }
}
```

**Why this is wrong / suboptimal / risky:**

Unlike `SignalConfig`, `SimpleXConfig` has NO `@ApplicationScoped @Startup` annotations and NO `@PostConstruct` lifecycle. It is a plain value object. The `validate()` method is ONLY called from `SimpleXAdapter.start()` (line 170 in the adapter):

```java
cfg.validate();
```

This means validation is entirely gated on the Provider calling `start()`. In the `SignalConfig` case (F1), at least the shape checks run at boot even if the adapter is never started. In the `SimpleXConfig` case, a configured-but-idle adapter (e.g., the operator sets `infochat.adapters=simplex` but the inbound message that triggers lazy-start never arrives) skips ALL validation. A bad binary path or non-writable data directory would only surface when the adapter activates.

This is documented in the class Javadoc ("Provider invokes this during its startup gates; a failure here fails Provider startup"), but it differs from SignalConfig's eager-validation approach. The inconsistency means two adapters with the same config pattern have different validation timing guarantees.

**Recommended fix:**

Make `SimpleXConfig` follow the same pattern as `SignalConfig`: add `@ApplicationScoped @Startup` annotations and a `@PostConstruct validate()` method. This requires adding the `quarkus-arc` dependency and the `@Startup` / `@PostConstruct` imports. The `infochat-messaging-adapter/pom.xml` already declares `quarkus-arc` as a compile dependency. Since `SimpleXConfig` is a `public` class in a library jar, CDI discovery of this bean is Provider-side wiring (just like `SignalConfig`).

Alternatively, document the difference explicitly in the `SimpleXConfig` Javadoc: "Unlike SignalConfig, this class is NOT an eager CDI bean; validation occurs on the first call to `SimpleXAdapter.start()`."

```java
// Option A: match SignalConfig's eager pattern
@ApplicationScoped
@Startup
public final class SimpleXConfig {
    // ... same fields and methods, plus:
    @PostConstruct
    public void eagerValidate() {
        validate();
    }
}
```

**Reasoning:**

The two adapters should have symmetric validation behavior for operator-facing config. The current asymmetry is a spec-drift risk — a test or operator reading SignalConfig's pattern might assume SimpleXConfig works the same way.

**Trade-offs:**

Option A increases coupling to Quarkus Arc (the CDI annotations already exist on `SignalConfig` in this same module, so the dependency precedent is set). Option B is documentation-only. Option A catches bad SimpleX config at boot, which is better for operator experience.

---

### F3. Oversize-line character-at-a-time drain in SignalJsonRpcClient reader loop

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:87,326-370`

**Current code:**
```java
private static final int MAX_INBOUND_LINE_CHARS = 16_384;
```

```java
private void readerLoop() {
    Socket s = socket;
    if (s == null) { return; }
    try (BufferedReader r = new BufferedReader(
            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        boolean overflow = false;
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') {
                if (overflow) {
                    LOG.warnf("dropped inbound JSON-RPC line exceeding %d-char cap",
                            MAX_INBOUND_LINE_CHARS);
                } else if (sb.length() > 0) {
                    handleLine(sb.toString());
                }
                sb.setLength(0);
                overflow = false;
                continue;
            }
            if (sb.length() >= MAX_INBOUND_LINE_CHARS) {
                overflow = true;
            } else {
                sb.append((char) c);
            }
        }
    } catch (IOException e) {
        LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
    }
}
```

**Why this is wrong / suboptimal / risky:**

The character-at-a-time pattern with `BufferedReader.read()` is correct functionally but creates three issues:

1. **Inefficient overflow handling**: When a line exceeds `MAX_INBOUND_LINE_CHARS`, the reader continues reading individual characters and discarding them one at a time in Java heap space. The OS has already buffered the full TCP segment (typically 64 KiB on loopback). The code neither reads in bulk to a discard buffer nor signals `Socket.setReceiveBufferSize()`. A sustained stream of oversize lines would keep the JVM busy discarding single characters.

2. **`BufferedReader.readLine()` would be simpler for the normal (non-overflow) path**: The only reason `BufferedReader.readLine()` is not used is that it has no length bound. But an alternative is `read(char[], int, int)` in a loop with a fixed-size buffer, which gives free bulk reads AND bounded accumulation without character-by-character dispatch. The current pattern re-discovers line splitting that `BufferedReader` already does internally.

3. **StringBuilder capacity**: `StringBuilder` starts at default capacity (16) and grows by doubling. For most lines (JSON-RPC responses are typically a few hundred bytes) this is fine. For a near-cap 16,384-char line, the StringBuilder grows through several allocations: 16 -> 34 -> 70 -> ... -> 16,384 (~11 allocations). Pre-sizing to `MAX_INBOUND_LINE_CHARS` would reduce this to one allocation.

**Recommended fix:**

Replace the character-at-a-time loop with a bulk-read approach using a pre-allocated char buffer and manual line scanning:

```java
private void readerLoop() {
    Socket s = socket;
    if (s == null) { return; }
    try (BufferedReader r = new BufferedReader(
            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        char[] buf = new char[MAX_INBOUND_LINE_CHARS];
        int pos = 0;
        boolean overflow = false;
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') {
                if (overflow) {
                    LOG.warnf("dropped inbound JSON-RPC line exceeding %d-char cap",
                            MAX_INBOUND_LINE_CHARS);
                } else if (pos > 0) {
                    handleLine(new String(buf, 0, pos));
                }
                pos = 0;
                overflow = false;
                continue;
            }
            if (pos >= MAX_INBOUND_LINE_CHARS) {
                overflow = true;
            } else {
                buf[pos++] = (char) c;
            }
        }
        // trailing data without terminator (peer half-closed)
        if (pos > 0 && !overflow) {
            handleLine(new String(buf, 0, pos));
        }
    } catch (IOException e) {
        LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
    }
}
```

**Reasoning:**

Pre-allocating the char array eliminates StringBuilder growth allocations on every line and reduces allocation churn for lines of any length up to the cap. The character-at-a-time read is still required (we need to scan for `\n`), but the storage is a fixed `char[]` instead of a dynamic `StringBuilder`.

**Trade-offs:**

The fixed `char[]` of 16,384 elements (32 KB) is permanently live while the reader thread is running. This is a 32 KB per-connection overhead. For the single signal-cli connection in v1, this is negligible. Character-at-a-time read is slower than bulk `readLine()` for normal lines, but the difference on localhost TCP is immeasurable at the scale of one bot.

---

### F4. `SignalAdapter` null field reliance for error messages

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:91-95`

**Current code:**
```java
@Nullable private final String binary;
@Nullable private final String dataDir;
@Nullable private final String account;
@Nullable private final String botAci;
@Nullable private final InetSocketAddress daemonEndpoint;
```

**Why this is wrong / suboptimal / risky:**

The five fields are `@Nullable` because the capability-only no-arg constructor exists. Every transport method (`send`, `update`, `finalize`, `setTyping`) delegates to `requireConnected()`, which checks only `client` (not the config fields). The `start()` method checks each field individually and produces a specific error message naming the missing field.

However, `groupHandler()` (line 321-328) only checks `botAci` and throws with a message that says "requires the production constructor." If `binary`, `dataDir`, `account`, or `daemonEndpoint` are null (because the no-arg constructor was used), `groupHandler()` would throw for `botAci` (which happens to be checked first). The developer reading the stack trace sees "botAci is needed" when the real root cause is "no fields were set."

This is not a production bug (the capability-only constructor is for test use only). But the ordering of null checks means the most diagnostic message is silent, and a narrower one surfaces.

The same pattern exists in `SimpleXAdapter:155-163` where the `requireWired()` check yields a clearer message than the field-by-field check that follows it.

**Recommended fix:**

Consolidate the null-check into one method with a diagnostic message naming ALL missing fields:

```java
private void requireFullyWired() {
    if (binary == null || dataDir == null || account == null
            || botAci == null || daemonEndpoint == null) {
        throw new IllegalStateException(
                "SignalAdapter requires the production constructor (binary, dataDir, account, botAci, daemonEndpoint). "
                + "Fields null: "
                + (binary == null ? "binary " : "")
                + (dataDir == null ? "dataDir " : "")
                + (account == null ? "account " : "")
                + (botAci == null ? "botAci " : "")
                + (daemonEndpoint == null ? "daemonEndpoint " : ""));
    }
}
```

Then call this once from both `start()` and `groupHandler()` instead of inline checks.

**Reasoning:**

A diagnostic message should name what is wrong, not just what the method happened to check first. Consolidating the check into one place removes the ordering dependency.

**Trade-offs:**

Slightly more code in the adapter (`~10` lines for the helper). The existing per-field check in `start()` already produces a usable message; this fix only improves the `groupHandler()` path, which is test-only code.
