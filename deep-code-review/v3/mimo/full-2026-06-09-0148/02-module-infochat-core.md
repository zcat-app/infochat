# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-09
**Reviewer:** senior-developer (mimo)

## Headline findings

- [low] PERFORMANCE — `infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:81` — `System.nanoTime()` called on every `charAt` invocation inside the watchdog; high-frequency timer read on the hot path.

## Detail

### F1. `InterruptibleCharSequence.charAt` calls `System.nanoTime()` on every character access

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** `infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:158-160`

**Current code:**
```java
@Override
public char charAt(int index) {
    if (System.nanoTime() > deadlineNanos) {
        throw new RegexInterruptedException();
    }
    return delegate.charAt(index);
}
```

**Why this is wrong / suboptimal / risky:**

The `InterruptibleCharSequence` wraps the input for every `Pattern` in the `CATALOGUE` list (7 patterns). The regex engine calls `charAt` potentially millions of times on adversarial inputs (the whole point of the watchdog is catastrophic backtracking). `System.nanoTime()` is a native call that, on Linux, typically reads the TSC or falls back to a syscall. On the regex watchdog hot path, this means every character access incurs a timer read, which adds measurable overhead on adversarial inputs where the watchdog is most needed.

This is not a correctness issue — the watchdog works as designed. The concern is that the mechanism intended to protect against DoS (regex timeout) itself adds cost proportional to the number of character accesses, which is the exact quantity that explodes under backtracking.

The typical mitigation is to check the timer every N-th character access (e.g. every 1024th) rather than every character. This preserves the watchdog's fail-closed property (worst case: the timer fires a few thousand characters late, which at `nanoTime` granularity is still sub-millisecond) while reducing the overhead on the hot path from one native call per character to one per 1024 characters.

**Recommended fix:**
```java
private int checkCounter;

@Override
public char charAt(int index) {
    if ((++checkCounter & 0x3FF) == 0 && System.nanoTime() > deadlineNanos) {
        throw new RegexInterruptedException();
    }
    return delegate.charAt(index);
}
```

**Reasoning:**

The bitmask `& 0x3FF` (1024) amortizes the timer read across character accesses. The counter is per-`InterruptibleCharSequence` instance (created fresh per pattern per input), so no cross-call state leakage. The worst-case overshoot is 1023 character accesses after the deadline fires — at the regex engine's per-character cost, this is negligible compared to the 100ms default budget.

**Trade-offs:**

The fix adds a mutable field (`checkCounter`) to the inner class and a bitwise-AND on every `charAt` call. The AND is a single CPU instruction and is effectively free compared to the `delegate.charAt` indirection and the timer read it replaces. The watchdog fires at most 1023 characters late, which is sub-microsecond at regex-engine speeds — the 100ms budget is not materially affected. The fix is strictly better for adversarial inputs where the watchdog matters most.
