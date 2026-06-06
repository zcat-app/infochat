# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — IpBlocklist.java:166-188 — IPv6 transition ranges (6to4 `2002::/16`, Teredo `2001:0::/32`, NAT64 `64:ff9b::/96`) embed blocked IPv4 targets but pass the v6 blocklist, reopening the loopback/metadata bypass the IPv4-mapped check closes.
- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:33-82 — the class-level javadoc states `ws`/`wss` "are deliberately rejected" and the pipeline is "only http and https", directly contradicting the `checkAndPinForWebSocket` / `resolveForWebSocket` methods on the same class.
- [low] MAINTAINABILITY-RULES-DRIFT — HostInterfaceSet.java:21-26 — class javadoc still describes the abandoned construction-time snapshot semantics that IpBlocklist's M1-026 per-call supplier explicitly replaced.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:191-214 — `blocklist`/`resolverSeam` null-checks are internal-wiring defensive code (§7), not system-boundary config validation.

## Detail

### F1. IPv6 transition ranges (6to4 / Teredo / NAT64) bypass the blocklist

- **Category:** SECURITY
- **Severity:** high
- **Location:** IpBlocklist.java:166-188 (`isBlockedV6`)

**Current code:**

```java
private static boolean isBlockedV6(byte[] raw) {
    if (isAllZeroV6(raw)) {
        return true;
    }
    if (isLoopbackV6(raw)) {
        return true;
    }
    int b0 = raw[0] & 0xFF;
    int b1 = raw[1] & 0xFF;
    // fe80::/10 — link-local.
    if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
        return true;
    }
    // fc00::/7 — unique-local.
    if ((b0 & 0xFE) == 0xFC) {
        return true;
    }
    // ff00::/8 — multicast.
    if (b0 == 0xFF) {
        return true;
    }
    return false;
}
```

**Why this is wrong / suboptimal / risky:**

The blocklist's whole reason for the `isIpv4Mapped` delegation (lines 113-117, 208-215) is that an attacker who can choose the resolved IP must not be able to spell a blocked IPv4 target in an IPv6 form that skips the v4 checks. The class javadoc states this explicitly for `::ffff:0:0/96`. But IPv4-mapped is not the only IPv6 encoding that carries an embedded IPv4 destination:

- **6to4 (`2002::/16`, RFC 3056):** bytes 2-5 of the address are an embedded IPv4 address. `2002:7f00:0001::` is the 6to4 form of `127.0.0.1`; `2002:a9fe:a9fe::` embeds `169.254.169.254` (the cloud-metadata endpoint). On any host with a 6to4 pseudo-interface configured (still common on cloud and some Linux defaults), a `connect()` to `2002:7f00:1::` is routed to `127.0.0.1`.
- **NAT64 (`64:ff9b::/96`, RFC 6052):** the well-known prefix carries an embedded IPv4 in the low 32 bits; `64:ff9b::7f00:1` targets `127.0.0.1` through any NAT64 gateway the host can reach.
- **Teredo (`2001:0000::/32`, RFC 4380):** encodes both a server and a client IPv4 address.

None of these match any branch in `isBlockedV6`, so `isBlocked` returns `false` and the request is dialed. An attacker controlling a DNS record (or a redirect `Location`, which re-enters the same `resolveAndValidate` path) can return one of these AAAA records and reach the metadata endpoint or a loopback service whenever the egress host has the corresponding transition mechanism active. This is the same class of bypass the IPv4-mapped delegation was added to close — it was closed for one encoding and left open for three others. The spec commits to blocking "private, loopback, link-local, multicast, CGNAT, and cloud-metadata ranges (notably `169.254.169.254` and IPv6 equivalents)"; the 6to4/NAT64 forms of those ranges are IPv6 equivalents that reach the same destinations.

The reason this is `high` and not `critical`: exploitation requires the egress host to actually have a route for the chosen transition mechanism (a configured 6to4 interface or a reachable NAT64 gateway). On a host with neither, the `connect()` fails rather than reaching the target. But a fail-closed egress guard must not depend on the host's routing table being hardened — the guard is the control, and on common cloud images 6to4 routing is present.

**Recommended fix:**

Treat any IPv6 address carrying an embedded IPv4 destination the same way `::ffff:0:0/96` is treated: extract the embedded v4 and run it through `isBlockedV4`, in addition to keeping the existing range checks.

```java
private static boolean isBlockedV6(byte[] raw) {
    if (isAllZeroV6(raw)) {
        return true;
    }
    if (isLoopbackV6(raw)) {
        return true;
    }
    int b0 = raw[0] & 0xFF;
    int b1 = raw[1] & 0xFF;
    // fe80::/10 — link-local.
    if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
        return true;
    }
    // fc00::/7 — unique-local.
    if ((b0 & 0xFE) == 0xFC) {
        return true;
    }
    // ff00::/8 — multicast.
    if (b0 == 0xFF) {
        return true;
    }
    // 6to4 (2002::/16, RFC 3056): bytes 2-5 are an embedded IPv4
    // destination. 2002:7f00:0001:: routes to 127.0.0.1 on any host
    // with 6to4 configured; reuse the v4 blocklist on the embedded form.
    if (b0 == 0x20 && b1 == 0x02) {
        return isBlockedV4(new byte[] { raw[2], raw[3], raw[4], raw[5] });
    }
    // NAT64 well-known prefix (64:ff9b::/96, RFC 6052): low 32 bits are
    // the embedded IPv4 destination reached through a NAT64 gateway.
    if (b0 == 0x00 && b1 == 0x64 && (raw[2] & 0xFF) == 0xFF && (raw[3] & 0xFF) == 0x9B
            && allZero(raw, 4, 12)) {
        return isBlockedV4(new byte[] { raw[12], raw[13], raw[14], raw[15] });
    }
    return false;
}

private static boolean allZero(byte[] raw, int from, int to) {
    for (int i = from; i < to; i++) {
        if (raw[i] != 0) {
            return false;
        }
    }
    return true;
}
```

Teredo (`2001:0000::/32`) is harder to exploit (it requires a Teredo relay and the embedded client address is obfuscated), so the minimum viable fix is 6to4 + NAT64; a conservative alternative is to block all three prefixes outright (see Option B) since the bot has no legitimate need to fetch a feed addressed by a transition-mechanism literal.

**Reasoning:**

The fix reuses the already-trusted `isBlockedV4` against the embedded destination, so the loopback / metadata / RFC-1918 / CGNAT coverage applies uniformly across every encoding that can name an IPv4 target, closing the asymmetry where one encoding (`::ffff`) was guarded and three were not. It also tracks the spec's "IPv6 equivalents" language directly.

**Trade-offs:**

Adds two branches and a small helper. A legitimate feed published at a literal 6to4/NAT64 public address would now be filtered through the v4 blocklist (which would still pass it if the embedded v4 is public) — no real-world feed is addressed this way, so there is no practical loss.

**Alternative options:**

- **Option A** (the recommended fix above) — decode and re-check the embedded IPv4.
- **Option B** — block `2002::/16`, `2001:0::/32`, and `64:ff9b::/96` wholesale regardless of the embedded address. Pros: simpler, no embedded-address decode, defends against the Teredo obfuscation too. Cons: would also block a (hypothetical, nonexistent in practice) feed reachable only via a public 6to4 address.

---

### F2. Class javadoc claims ws/wss are rejected and the client is http-only, contradicting the WebSocket methods on the same class

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:33-82 (class javadoc), contradicted by lines 119-121, 502-538

**Current code:**

```java
 * <ol>
 *   <li><strong>Scheme allowlist</strong> — only {@code http} and
 *       {@code https} are dialed. {@code ws} and {@code wss} are
 *       deliberately rejected for now: the WebSocket transport
 *       wrapper for {@code StreamSource} consumes the same
 *       {@link IpBlocklist} policy class but is its own
 *       implementation (carved out per the ticket's
 *       {@code out_of_scope}).</li>
```

**Why this is wrong / suboptimal / risky:**

The class-level javadoc is the contract a reader of a security-critical egress guard relies on to understand what the class does and does not gate. It states, as the first numbered pipeline step, that `ws`/`wss` are "deliberately rejected" and that the WebSocket transport is "its own implementation" elsewhere. That is no longer true: this same class now carries `checkAndPinForWebSocket(URI)` (line 502), `resolveForWebSocket(URI)` (line 536), `WEBSOCKET_SCHEMES = Set.of("ws", "wss")` (line 121), and the `PinnedDial` handle — the wss relay path lives here, in this class. The `SsrfGuardedHttpClientTest.rejectsWebsocketSchemeForNow` test (lines 68-80) also still asserts the obsolete "ws/wss is rejected by this wrapper" behavior on the `get` entrypoint, which is true only for `get` and actively misleads about the class as a whole.

A reviewer auditing whether the `wss://` relay path is SSRF-gated (the exact question the spec's transport-agnostic clause raises) reads this javadoc and concludes the WebSocket path is somewhere else, or absent. CLAUDE.md §"Comment important, crucial, or complex code" makes accurate documentation of invariant-carrying code a requirement, not a nicety; a stale invariant statement on a security boundary is worse than no comment because it asserts a false guarantee.

**Recommended fix:**

```java
 * <ol>
 *   <li><strong>Scheme allowlist</strong> — {@link #get} dials only
 *       {@code http} and {@code https}; the WebSocket entrypoints
 *       ({@link #checkAndPinForWebSocket}, {@link #resolveForWebSocket})
 *       accept only {@code ws} and {@code wss}. The two surfaces do not
 *       overlap: the JDK {@code HttpClient.send} cannot dial ws/wss and
 *       {@code WebSocket.Builder} cannot dial http/https. The
 *       IP-blocklist + DNS-pinning pipeline is identical for both.</li>
```

**Reasoning:**

This restates the actual current behavior — already documented correctly at lines 111-118 on the `HTTP_SCHEMES`/`WEBSOCKET_SCHEMES` constants — at the class level where a reader looks first, removing the false "WebSocket is elsewhere" claim. The test name/comment at `SsrfGuardedHttpClientTest.java:68-80` should likewise be reframed as "`get` rejects ws/wss" rather than "this wrapper rejects ws/wss," but that is a comment-only change and not itself a behavior bug.

**Trade-offs:**

None — strictly better; documentation matched to code.

---

### F3. HostInterfaceSet javadoc describes the abandoned construction-time snapshot semantics

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** HostInterfaceSet.java:21-26

**Current code:**

```java
 * <p>The {@code IpBlocklist}'s no-arg constructor snapshots this set at
 * construction time and consults it on every {@link IpBlocklist#isBlocked}
 * call. The snapshot intentionally captures interfaces at JVM start; a
 * cloud VM whose IPs change after startup is treated as an
 * out-of-scope refresh-cadence concern (the spec does not commit to a
 * refresh policy).
```

**Why this is wrong / suboptimal / risky:**

This is the exact behavior IpBlocklist's M1-026 change reversed. IpBlocklist's no-arg constructor now passes `HostInterfaceSet::enumerate` as a per-call `Supplier` (IpBlocklist.java:74-76, 100-108) and the javadoc there (lines 41-49) explains at length why the snapshot was wrong and that interfaces are re-enumerated "on the very next call." HostInterfaceSet still tells the reader the opposite: that the set is snapshotted at construction and post-startup interface changes are out of scope. Two files in the same module now assert contradictory things about the same defense. A reader who lands on HostInterfaceSet first will believe a freshly-attached cloud EIP is not covered, which is the precise info-leak the per-call change closed.

**Recommended fix:**

```java
 * <p>The {@code IpBlocklist} no-arg constructor wires
 * {@link #enumerate} as a per-call supplier, so each
 * {@link IpBlocklist#isBlocked} invocation re-enumerates the host's
 * interfaces — a VPN tunnel, hot-plugged NIC, or freshly-attached
 * cloud EIP brought up after JVM start is seen on the next call.
```

**Reasoning:**

Aligns this file's documentation with the actual per-call wiring and with IpBlocklist's own javadoc, removing the contradiction.

**Trade-offs:**

None — strictly better.

---

### F4. Internal-wiring null-checks in the resolver-seam constructor

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:191-214

**Current code:**

```java
if (blocklist == null) {
    throw new IllegalArgumentException("blocklist must be configured");
}
if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
    throw new IllegalArgumentException("timeout must be configured");
}
...
if (resolverSeam == null) {
    throw new IllegalArgumentException("resolver seam must be configured");
}
```

**Why this is wrong / suboptimal / risky:**

The timeout / body-cap / redirect-cap checks are legitimate: the spec states "an unset timeout is a configuration error," and these values originate from operator config, so validating them is system-boundary validation that §7 explicitly permits. The `blocklist == null` and `resolverSeam == null` checks are different — `IpBlocklist` and the resolver `Function` are internal collaborators wired by other infochat code (the no-arg and 7-arg constructors always supply non-null values), not values that cross a config/parse/IO boundary. Per engineering-rules §7, a null-check between two internal classes for a parameter callers cannot legally pass null for is defensive code for an impossible scenario, "scope drift." The §7a parameter-contract complement is the intended mechanism here: annotate the params `@NonNull` instead of guarding at runtime.

This is `low` because the checks are harmless and cheap; it is flagged because this is the module a reviewer holds to the §7 standard most strictly, and the mixed presence (correct config checks alongside incorrect dependency checks) is the kind of pattern that gets copied.

**Recommended fix:**

```java
public SsrfGuardedHttpClient(@NonNull IpBlocklist blocklist,
                             @NonNull Duration connectTimeout,
                             @NonNull Duration requestTimeout,
                             @NonNull Duration readTimeout,
                             @NonNull Duration bodyReadDeadline,
                             long bodyCap,
                             int redirectCap,
                             @NonNull Function<String, List<InetAddress>> resolverSeam) {
    // Keep the config-value validations (zero/negative timeouts,
    // non-positive caps) — those are system-boundary config checks
    // the spec mandates ("an unset timeout is a configuration error").
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be configured");
    }
    // ... remaining timeout / cap range checks unchanged ...
    // Drop the blocklist == null and resolverSeam == null guards;
    // the @NonNull contract covers them.
    this.blocklist = blocklist;
    ...
}
```

**Reasoning:**

Replacing the dependency null-checks with `@NonNull` annotations gives the caller the contract at signature-read time (§7a) while removing runtime branches that can only fire on a programming error inside the module, satisfying §7. The Duration value-range checks stay because they validate config content, not wiring.

**Trade-offs:**

If a future caller in another module passes a literal `null` despite the annotation, the failure surfaces as an `NPE` at first use rather than an `IllegalArgumentException` at construction. Given JSpecify enforcement and that all current callers wire non-null, this is acceptable; the contract is now machine-checked rather than runtime-asserted.

## Synthesizer-relevant observations

- Cross-module (not located in infochat-ssrf): `NostrRelayConnection.peerIpDiverged()` (infochat-collector, lines 280-305) implements the spec's "any peer-IP change observed at the socket layer is a hard close" via DNS re-resolution, but uses **intersection** semantics — it returns "not diverged" if the re-resolved set shares *any* address with the pinned set. The spec language is "any peer-IP change." A rebind that returns `[originalIP, attackerIP]` after the first hop, or that adds addresses without removing the original, is not treated as divergence. Whether this matches intent (the live socket stays bolted to the originally-pinned IP regardless) or under-enforces the spec should be assessed against `security.md` §SSRF by whoever owns the collector module; the SSRF library itself (`resolveForWebSocket`) correctly returns the full re-resolved set and re-applies the blocklist, so the policy choice lives in the consumer.
