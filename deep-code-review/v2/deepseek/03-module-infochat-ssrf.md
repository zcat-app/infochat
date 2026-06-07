# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [low] PERFORMANCE — `SsrfGuardedHttpClient.java:130-131` — one virtual thread per `in.read()` call during body read; the overhead is negligible for typical feed sizes but the comment should note the per-read allocation
- [low] SIMPLIFICATION — `IpBlocklist.java:243-250` — `isIpv4Mapped` is only called from `embeddedV4` and always preceded by `isBlockedV6` returning false; the all-zero check (bytes 0-9) duplicates part of what `isAllZeroV6` and `isLoopbackV6` already verified

## Detail

### F1. Virtual thread per in.read() — comment could note the allocation

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:130-131,526-527`

**Current code:**

```java
private static final ThreadFactory BODY_READER_THREAD_FACTORY =
    Thread.ofVirtual().name("ssrf-body-reader-", 0).factory();

// In readBounded:
FutureTask<Integer> readTask = new FutureTask<>(() -> in.read(buf));
BODY_READER_THREAD_FACTORY.newThread(readTask).start();
```

**Why this is wrong / suboptimal / risky:**

A virtual thread is created for every `in.read(buf)` call in the body-read loop. For a 10 MiB body read in 8 KiB chunks, that's ~1280 virtual thread creations. Virtual threads on JDK 25 are cheap (~1-2 μs creation + a few hundred bytes of carrier-thread state), so 1280 creations is ~1-3 ms total — negligible compared to the network I/O. The code is correct and the performance is fine.

The comment at line 129 says "one per read, JDK 25 — cheap enough to spin per read and needs no pool / shutdownNow bookkeeping," which is accurate. The finding is that a reader unfamiliar with virtual thread cost might flag this as excessive. Adding the approximate cost (~1-2 μs per creation, <<1% of the network read time) to the comment would preempt that concern.

**Recommended fix:**

Add to the comment at line 129: "Virtual thread creation cost is ~1-2 μs on JDK 25; for a full 10 MiB body read in 8 KiB chunks (~1280 reads), the total thread-creation overhead is ~2 ms — negligible against the network I/O latency."

**Reasoning:**

Documentation improvement. Makes the performance argument self-contained.

**Trade-offs:**

- None — the fix is strictly better.

---

### F2. Redundant zero-check in isIpv4Mapped

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java:243-250`

**Current code:**

```java
private static boolean isIpv4Mapped(byte[] raw) {
    for (int i = 0; i < 10; i++) {
        if (raw[i] != 0) {
            return false;
        }
    }
    return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
}
```

**Why this is wrong / suboptimal / risky:**

`isIpv4Mapped` is called only from `embeddedV4`, which is called only after `isBlockedV6` returns false. `isBlockedV6` already checks `isAllZeroV6` (bytes 0-15 all zero) and `isLoopbackV6` (bytes 0-14 zero + byte 15 = 1). So when `isIpv4Mapped` runs, it's guaranteed that at least one of bytes 0-9 is non-zero OR bytes 10-11 are not 0xFFFF (otherwise `::ffff:0:0` would have been caught as all-zero). The redundant zero-check in `isIpv4Mapped` doesn't change correctness — it's defense-in-depth against a future refactor that calls `isIpv4Mapped` from a different path. But the defense-in-depth is implicit; a comment would make it explicit.

This is the narrowest possible finding. The code is correct; the redundancy is intentional and safe. The only issue is that the intent (defense-in-depth against future callers) is not documented.

**Recommended fix:**

Add a comment above `isIpv4Mapped`: "Only called after isBlockedV6 returns false, so :: and ::1 never reach here. The zero-check on bytes 0-9 is defense-in-depth against future callers that bypass isBlockedV6."

**Reasoning:**

Makes the redundancy explicit.

**Trade-offs:**

- None — the fix is strictly better.
