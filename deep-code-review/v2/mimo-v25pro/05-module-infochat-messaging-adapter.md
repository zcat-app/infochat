# Deep code review: module infochat-messaging-adapter
**Target:** module infochat-messaging-adapter | **Lens:** module | **Module path:** infochat-messaging-adapter/ | **Date:** 2026-06-07 | **Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

1. **SIMPLIFICATION / MAINTAINABILITY-RULES-DRIFT: `MessageHandle` is a plain record, not a sealed interface with per-adapter implementations as the design specifies.** The design at `docs/design/06-messaging.md` section 6.2 defines `MessageHandle` as a `sealed interface permits SimplexMessageHandle, SignalMessageHandle, InMemoryMessageHandle`. The actual code at `MessageHandle.java` is a single `record MessageHandle(@NonNull String opaqueValue)`. The design's sealed-permits shape was replaced with a single opaque string wrapper. This is not a security defect (the opacity is preserved by convention), but it means the SPI cannot express adapter-specific handle invariants at the type level -- the `SimplexMessageHandle(chatItemId, scope, correlationId)` and `SignalMessageHandle(timestamp, recipient, original)` records that the design sketches are internal implementation details that never cross the SPI boundary. The actual code achieves the same isolation through package-private records keyed by the opaque string. This works. However, the `InMemoryMessageHandle` record at `impl/inmemory/InMemoryMessageHandle.java` is a public record that is never referenced outside `InMemoryAdapter` -- its fields `(id, original)` duplicate state already stored in the adapter's internal maps. The record is dead surface.

2. **MAINTAINABILITY-RULES-DRIFT: `CapabilityFlags` field `minEditInterval` is `Duration.ZERO` for both SimpleX and Signal, contradicting the design's stated 600ms floor.** The design at section 6.4.2 says `minEditInterval = 600ms` for SimpleX, and section 6.5.2 says `minEditInterval = 600ms` for Signal. Both `SimpleXAdapter.CAPABILITIES` and `SignalAdapter.CAPABILITIES` declare `Duration.ZERO`. The `ProgressNotifier` (which has no concrete implementation yet) is specified to honor `max(adapterMin, systemFloor)` -- so this discrepancy has no runtime impact today because the notifier is not wired. But when the notifier is implemented, the zero floor means no coalescing happens unless the system floor alone enforces it. The design-commitment value is 600ms; shipping zero without a comment explaining the override is a drift that the contract tests do not catch (the `AdapterCapabilityContractTest` does not assert on `minEditInterval`).

3. **MAINTAINABILITY-RULES-DRIFT: `SimpleXAdapter` and `SignalAdapter` use different capability values than the design specifies for `maxMessageBytes` and `maxSendsPerSecond`.** The design says SimpleX: `maxMessageBytes = 4000`, `maxSendsPerSecond = 5`; Signal: `maxMessageBytes = 8000`, `maxSendsPerSecond = 5`. Both adapters declare `maxMessageBytes = 2_000` and `maxSendsPerSecond = 8`. The comments in both say "best-guess defaults not fixed by spec and are expected to be tuned." These are tuning parameters, but the design document is the only reference operators and tests have for expected values, and no comment in the code points the reader at the discrepancy.

4. **SIMPLIFICATION: `SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES` (4000) and `SimpleXAdapter.CAPABILITIES.maxMessageBytes` (2000) disagree.** The codec enforces a 4000-byte outbound cap (matching the design's SimpleX protocol limit), but the adapter declares a 2000-byte capability flag that Provider's chunking layer would use to split messages before they reach the codec. The two-layer defense means Provider chunks at 2000 bytes and the codec then re-validates at 4000 bytes -- the codec cap is never reached. The 2000-byte adapter cap is a deliberate conservative floor per the comments ("best-guess defaults not fixed by spec"), but the two constants' relationship is not documented, and the codec's 4000-byte defense-in-depth wall silently becomes dead surface under the 2000-byte adapter cap.

5. **SIMPLIFICATION: `SignalIdentity.resolve()` and `SimpleXIdentity.resolve()` throw `UnsupportedOperationException`.** Both are skeleton entry points left as stubs. These are not callable in production (Provider-side wiring constructs the identity objects directly), but the `throw` bodies are dead code that would crash if accidentally invoked. The javadoc correctly notes they are stubs, but the code would be cleaner as abstract methods or removed entirely.

6. **MAINTAINABILITY-RULES-DRIFT: `SignalAdapter.start()` throws `IllegalStateException` instead of `MessagingException` for most failure paths.** The SPI's `start()` method is declared `throws MessagingException`. The `SimpleXAdapter.start()` correctly wraps all failures in categorized `MessagingException`. `SignalAdapter.start()` throws bare `IllegalStateException` for: subprocess launch failure, endpoint probe timeout, and JSON-RPC connect failure. The SPI contract lets callers catch `MessagingException`; an `IllegalStateException` would escape as an unchecked exception, bypassing the per-adapter catch that `AdapterRegistry` (design section 6.7) relies on for per-adapter resilience. The comment in `SimpleXAdapter.start()` explicitly calls out this wrapping as intentional ("Throws MessagingException (categorised) on launch / readiness / connect failure so Provider sees the failure via the same exception channel as transport faults"). `SignalAdapter` does not follow the same contract.

7. **MAINTAINABILITY-RULES-DRIFT: `SimpleXSubprocess` uses equal-jitter backoff (`[exp/2, exp]`) while `SignalSubprocess` uses full-jitter backoff (`[0, upperBound)`).** The spec at `docs/spec/messaging.md` section "Failure handling" and the design at section 6.3.6 both commit to "exponential back-off with full jitter." `SimpleXSubprocess.backoffDelay()` computes `[exp/2, exp]` (equal-jitter, not full-jitter). `SignalSubprocess.computeBackoffDelay()` computes `[0, upperBound)` (full-jitter, correct). The two adapters' backoff shapes diverge on a spec-mandated invariant. `SimpleXSubprocess`'s javadoc calls it "equal-jitter" explicitly, so this is a conscious choice documented at the code level -- but it contradicts the spec's "full jitter" commitment.

8. **SIMPLIFICATION: `SimpleXMessageCodec.decode()` includes `resp.path("chatItems").get("itemId")` in its `firstTextual` scan for `chatItemId`, but `resp.path()` already returns a `MissingNode` if the path does not exist, and `MissingNode.get()` also returns `MissingNode` -- so the `firstTextual` helper silently handles this.** The fourth candidate (`resp.path("chatItems").get("chatItemId")`) is defensive over a SimpleX API shape that may not exist. This is fine functionally, but the breadth of the scan (four candidates) means the codec accepts `chatItemId` from multiple JSON shapes, any of which an attacker-controlled inbound frame could populate. The `firstTextual` method only checks `isTextual()` -- it does not validate that the returned string matches the queue-address character set. The `SendAck` path does not run `isValidQueueAddressId` on the `chatItemId`. A `chatItemId` injected from an attacker-controlled `chatItems.itemId` or `chatItems.chatItemId` field would pass through to the handle table and later be pasted into an `/_update item` command. The `chatItemId` IS validated at encode-time by `requireValidQueueAddressId` (defense-in-depth at the codec's outbound boundary), so exploitation is blocked at the second layer -- but the decode-time trust boundary for `chatItemId` is weaker than for `contactId` and `adapterGroupId`, both of which are validated at decode time.

9. **MAINTAINABILITY-RULES-DRIFT: `SignalAdapter` does not declare `@Nullable` annotations on its `@Inject`-injected `SignalConfig` constructor parameters.** `SignalConfig`'s `@Inject` constructor has parameters without `@NonNull` annotations (`@ConfigProperty(name = BINARY_KEY) String binary` etc.). NullAway defaults non-null per the module's `@NullMarked` annotation, so this is technically correct -- bare reference types mean non-null. However, `SimpleXConfig`'s constructor explicitly annotates its first two parameters with `@NonNull`. The inconsistency is cosmetic but worth noting: the SimpleX config reads as more defensive than it needs to be (redundant `@NonNull` on non-null-default types), while Signal's reads as bare.

10. **PERFORMANCE: `SimpleXSubprocess` launches three virtual threads per subprocess (supervisor, stdout drain, stderr drain).** The supervisor thread sleeps in a backoff loop, and the drain threads block on `InputStream.read()`. These are lightweight per virtual-thread economics, but the supervisor's `sleepForBackoff` uses `Thread.sleep` (which blocks the virtual thread's carrier) rather than a `ScheduledExecutorService` or `CompletableFuture.delayedExecutor` (which releases the carrier). `SignalSubprocess` uses a `ScheduledExecutorService` with a single-thread pool for its restart scheduling -- the more carrier-friendly pattern. This matters only at scale (many adapters), but the inconsistency is worth noting.

11. **SIMPLIFICATION: `InMemoryAdapter` does not implement `start()` or `stop()` -- it inherits the no-op defaults from the `MessagingAdapter` interface.** This is correct by design (section 6.6: "no network, no SimpleX dependency"). The `AdapterLifecycleContractTest` explicitly asserts this. No finding -- just confirmation.

12. **MAINTAINABILITY-RULES-DRIFT: `SignalMessageCodec.decode()` includes `line` content in the `IllegalArgumentException` message ("Malformed JSON-RPC envelope: " + line).** The caller (`SignalJsonRpcClient.handleLine`) catches the exception and logs only `e.getClass().getSimpleName()`, so the user-content line never reaches the logger. However, the exception message itself contains user content, and if any other caller caught and logged the full message, D37 would be violated. The `SimpleXMessageCodec` takes the stricter approach: `MalformedFrameException` carries only "frame is not JSON" (fixed text, no frame content). `SignalMessageCodec` embeds the raw line. The inconsistency means `SignalMessageCodec` is one catch-site change away from leaking user content into logs.

## Detail

### D1: SignalAdapter.start() exception contract (finding 6 above -- expanded)

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java`, method `start()`.

The SPI declares `default void start() throws MessagingException`. The `SimpleXAdapter` implementation wraps all startup failures (subprocess launch, WebSocket readiness probe, WebSocket connect) in `MessagingException` with appropriate `FailureCategory`. The `SignalAdapter` throws `IllegalStateException` for the same failure classes:

- Line 212: `throw new IllegalStateException("Failed to start signal-cli subprocess", e);`
- Line 218: `throw new IllegalStateException("signal-cli daemon endpoint ... not reachable ...");`
- Line 233: `throw new IllegalStateException("Failed to connect SignalJsonRpcClient", e);`

`IllegalStateException` is unchecked. A Provider-side `try { adapter.start(); } catch (MessagingException e)` would not catch these -- they would propagate as uncaught exceptions, potentially aborting the startup of other adapters (violating design section 6.7's "per-adapter resilience" rule). The correct behavior is to wrap in `MessagingException(FailureCategory.TRANSIENT, ...)` so the registry's per-adapter catch handles them uniformly.

**Severity:** high. This is a contract violation on the SPI's declared exception type. In practice, the Provider-side wiring for Signal is not yet live (M1-105/M1-107 notes), so the bug is latent -- but it will surface the moment the Signal adapter is wired into the startup path.

### D2: SimpleXSubprocess backoff jitter divergence (finding 7 above -- expanded)

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java`, method `backoffDelay()`.

The method computes `[exp/2, exp]` (equal-jitter), as documented in its own javadoc ("Equal-jitter exponential backoff: the deterministic component doubles each consecutive failure up to max; the jittered delay lands in [exp/2, exp]"). The spec says "full jitter" which is `[0, exp)` (the AWS architecture-blog form referenced in design section 6.3.6). `SignalSubprocess.computeBackoffDelay()` correctly uses `ThreadLocalRandom.current().nextLong(0, upperBound)` -- full jitter.

The practical difference: equal-jitter guarantees a minimum delay of half the exponential value, so the worst-case tight-loop retry is slower than full-jitter. Full-jitter's `[0, exp)` range means some retries fire near-instantly, spreading load more aggressively. For a single-adapter deployment the difference is negligible; for parallel adapter restarts after a shared dependency recovers, full-jitter provides better thundering-herd mitigation.

**Severity:** low. The divergence is documented at the code level, and the SimpleX adapter's tighter floor is conservative (safer for production, just not spec-identical).

### D3: SendAck chatItemId not validated at decode time (finding 8 above -- expanded)

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java`, method `decodeSendAck()`.

The `contactId` and `adapterGroupId` fields are validated by `isValidQueueAddressId()` at decode time (the inbound trust boundary). The `chatItemId` extracted from `SendAck` responses is NOT validated at decode time -- it passes through to the adapter's handle table and is later pasted into `/_update item` commands. The encode-time `requireValidQueueAddressId(chatItemId)` in `encodeEdit()` catches injection at the outbound boundary (defense-in-depth), so the two-layer defense holds. However, the asymmetry means a crafted simplex-chat response with a `chatItemId` containing `@` or `#` would survive decode and only fail at the next encode call -- the invariant "every id pasted into a command string is validated before storage" is not uniformly enforced.

**Severity:** low. The encode-time validation blocks exploitation. The gap is a defense-in-depth inconsistency, not a vulnerability.

### D4: SignalMessageCodec embeds raw line in exception (finding 12 above -- expanded)

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java`, method `decode()`.

Line 98: `throw new IllegalArgumentException("Malformed JSON-RPC envelope: " + line, e);`

The `line` parameter is attacker-influenceable daemon output. The single known caller (`SignalJsonRpcClient.handleLine`) catches `IllegalArgumentException` and logs only `e.getClass().getSimpleName()`, so the user content does not reach SLF4J. But the exception object itself carries the line in its message -- any future caller that logs `e.getMessage()` or passes the exception to a logger would violate D37. The SimpleX codec uses a fixed message ("frame is not JSON") and does not embed the frame.

**Severity:** medium. The current call site is safe, but the exception's message is a latent D37 violation waiting for a second call site.

### D5: InMemoryMessageHandle is dead public surface

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryMessageHandle.java`.

This record is `public` and carries `(long id, OutboundMessage original)`. It is never referenced outside `InMemoryAdapter`. The adapter's internal state is keyed by the opaque `MessageHandle.opaqueValue()` string in `ConcurrentHashMap` maps (`handles`, `history`, `finalized`). The `InMemoryMessageHandle` record is constructed in `send()` but only stored in the `handles` map, which is itself never read by any method other than `requireKnownAndOpen` (which only checks `containsKey`). The record's `id` and `original` fields are never accessed after construction.

The design's sealed-`MessageHandle` approach would have made this record a first-class SPI carrier. With the plain-record `MessageHandle(opaqueValue)` approach, this record is vestigial.

**Severity:** low. Dead surface, not a bug.

### D6: SimpleXSubprocess supervisor thread uses Thread.sleep (finding 10 above -- expanded)

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java`, method `sleepForBackoff()`.

`Thread.sleep(delay.toMillis())` on a virtual thread parks the carrier thread for the duration. `SignalSubprocess` uses `ScheduledExecutorService.schedule()` which releases the carrier. For a single SimpleX adapter the carrier-pin cost is one thread for the backoff duration (typically 1-60 seconds). With JDK 25's virtual threads this is unlikely to starve the carrier pool in practice, but it is an unnecessary pin.

**Severity:** low. Performance nit; no functional impact at v1 scale.

### D7: SimpleXSubprocess drain threads discard all bytes -- no metrics

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java`, method `drainStream()`.

The drain reads bytes and discards them (correct per D37 and design section 6.4.8). It emits one INFO marker per drain lifetime. It does NOT count lines or bytes -- unlike `SignalSubprocess.drainAndLog()` which counts and logs the line count at DEBUG. The INFO marker ("simplex-chat subprocess stdout output suppressed") fires once per drain lifetime regardless of volume. A simplex-chat instance that logs heavily at DEBUG would silently consume I/O bandwidth with no operator-visible signal beyond the single marker. This is a monitoring gap, not a correctness issue.

**Severity:** low. Observability gap.

### D8: SignalAdapter uses JBoss Logger, SimpleXAdapter uses SLF4J Logger

**File:** `SignalAdapter.java` uses `org.jboss.logging.Logger`. `SimpleXAdapter.java` uses `org.slf4j.LoggerFactory`. Both are functional under Quarkus (JBoss Logging is the Quarkus default; SLF4J routes through it). The inconsistency is cosmetic but makes log configuration non-uniform across adapters -- JBoss Logger uses `infof`/`warnf`/`debugf` format strings while SLF4J uses `{}` placeholders. A single logging facade across both adapters would reduce cognitive load.

**Severity:** low. Style inconsistency.

### D9: SignalJsonRpcClient.readerLoop() uses platform thread, not virtual thread

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java`, method `connect()`.

Line 183: `Thread t = new Thread(this::readerLoop, "signal-jsonrpc-reader");`

This creates a platform thread for the daemon's reader loop. The SimpleX adapter uses virtual threads for its drain and supervisor. The Signal reader loop is a long-lived blocking read (`r.read()` per character in the cap-aware loop), which is exactly the workload virtual threads are designed for. A platform thread here consumes a carrier permanently.

**Severity:** low. The reader loop is one thread per Signal adapter instance; at v1 scale (1-2 adapters) this is negligible. But it is inconsistent with the project's virtual-thread-first posture (CLAUDE.md: "commits us to virtual threads + blocking style").

### D10: SimpleXMessageCodec.sendCommand's CompletableFuture is not cancelled on timeout

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java`, method `sendCommand()`.

When `future.get(ackTimeout)` throws `TimeoutException`, the code removes the future from `pending` and throws. But between the timeout and the `pending.remove(corrId)`, the listener thread could complete the future -- the `completePending` method does `pending.remove(corrId)` and then `future.complete(chatItemId)`. If the listener removes and completes after the caller's `pending.remove` but before the caller's `throw`, the completion is wasted (no one is listening). This is benign -- the future is GC'd. But if the listener's `remove` races with the caller's `remove`, both return non-null, and the listener completes a future the caller has already abandoned. No leak, no corruption, just a benign race. The `finally { pending.remove(corrId); }` block in `sendCommand` also runs, so the entry is always cleaned up.

**Severity:** none (benign race, no functional impact).

### D11: SimpleXWebSocketClient.Listener.onClose passes reason string to exception message

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java`, method `Listener.onClose()`.

Line 295: `failAllPending(new MessagingException(FailureCategory.PERMANENT, "WebSocket closed by peer: " + statusCode + " " + reason));`

The `reason` string from the WebSocket close frame is protocol-supplied and could carry arbitrary bytes. This flows into `MessagingException.getMessage()`, which is then passed to `future.completeExceptionally()`. The `sendCommand` catch block unwraps the exception and re-throws with the original message. If any caller logs the exception message, user content leaks. The `SimpleXAdapter.onInbound()` catches `RuntimeException` from the handler and logs only the class name -- but `sendCommand` callers (the adapter's `update`, `finalizeMessage`) propagate the `MessagingException` to Provider, whose logging discipline for `MessagingException` messages is not visible in this module.

**Severity:** medium. The close-reason string is typically a fixed protocol string (e.g., "Normal Closure"), but the WebSocket spec allows arbitrary UTF-8 in the reason field. A malicious simplex-chat instance could embed user content in the close reason.

### D12: Missing `@Nullable` on `SignalGroupHandler` constructor parameters

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java`, constructor.

The constructor accepts `@Nullable InboundHandler` and `@Nullable MembershipHandler`. The class correctly handles null (drops events with a DEBUG log). This is intentional -- the handler is built before Provider registers callbacks, and `SignalAdapter.groupHandler()` re-builds it on each call to capture the latest references. The null-handling is correct and documented.

**Severity:** none (correct design).

### Test coverage observations

The test suite has 26 test files covering:
- Cross-adapter capability contracts (`AdapterCapabilityContractTest`)
- Cross-adapter lifecycle contracts (`AdapterLifecycleContractTest`)
- SPI loadability (`MessagingSpisLoadTest`)
- InMemory adapter behavior and group operations (`InMemoryAdapterTest`, `InMemoryAdapterGroupTest`)
- SimpleX codec round-trips, mention parsing, group handling, subprocess lifecycle, config validation, WebSocket client
- Signal codec round-trips, mention parsing, group handling (including membership isolation), subprocess lifecycle, config validation, JSON-RPC client, adapter skeleton, end-to-end group flow
- Membership dispatch shape (`MembershipDispatchShapeTest`)

**Missing coverage:** No test asserts `minEditInterval` values match the design. No test asserts that `SignalAdapter.start()` wraps failures in `MessagingException` rather than `IllegalStateException` (because the adapter cannot be started without a real signal-cli subprocess -- the skeleton test only checks capabilities). The `SendAck chatItemId` decode-time validation gap has no regression test.
