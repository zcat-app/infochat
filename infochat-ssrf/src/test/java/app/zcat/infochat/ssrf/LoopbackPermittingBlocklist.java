package app.zcat.infochat.ssrf;

/**
 * Test-only factory for an {@link IpBlocklist} that permits the loopback
 * range (127.0.0.0/8, ::1) so in-process {@code HttpServer} / WebSocket
 * fixtures bound to localhost can be dialed, while every other blocked range
 * (e.g. {@code 169.254.169.254}) stays blocked.
 *
 * <p>This is the single definition of the test carve-out across all three
 * modules (M1-374). It lives in ssrf TEST sources — never main — and in
 * package {@code app.zcat.infochat.ssrf} so it can reach
 * {@link IpBlocklist}'s package-private {@code (boolean, Supplier)} carve-out
 * constructor. It is exported to infochat-collector and infochat-provider via
 * the ssrf test-jar (see {@code infochat-ssrf/pom.xml}). Because the carve-out
 * is reachable only from here and {@code IpBlocklist} is {@code final}, no
 * production code in any module can build a loopback-permitting blocklist or
 * re-open a blocked range by subclassing — which is the whole point of sealing
 * the class. Replaces the ~18 hand-rolled {@code extends IpBlocklist} doubles
 * that previously overrode the (now {@code private}) {@code isBlockedAgainst}
 * seam.
 */
public final class LoopbackPermittingBlocklist {

    private LoopbackPermittingBlocklist() {
    }

    /**
     * A strict {@link IpBlocklist} except that loopback is permitted. Host
     * interfaces are enumerated per call via {@link HostInterfaceSet#enumerate},
     * exactly as the production no-arg constructor wires them.
     */
    public static IpBlocklist create() {
        return new IpBlocklist(true, HostInterfaceSet::enumerate);
    }
}
