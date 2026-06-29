package app.zcat.infochat.messaging;

/**
 * Closed set of scopes an inbound message or outbound reply can be
 * addressed to. Sealed so the dispatch surface is exhaustive at the
 * compiler — a new scope kind is a spec amendment, not a per-adapter
 * invention. Per {@code docs/design/06-messaging.md} §6.2 and
 * {@code docs/spec/messaging.md} §Identity and groups.
 *
 * <p>The two permitted records carry the adapter-local identifier
 * the inbound message originated from:</p>
 * <ul>
 *   <li>{@link Dm} — direct-message scope. The {@code contactId} is the
 *       cryptographic, stable contact id (decision D10), NOT a display
 *       name.</li>
 *   <li>{@link Group} — group-mention scope. The {@code adapterGroupId}
 *       is the adapter-defined stable group identifier. Group-scope
 *       dispatch is live in v1: the group handlers build a
 *       {@code ScopeRef.Group} and dispatch the group-mention turn
 *       (e.g. {@code SignalGroupHandler}, {@code SimpleXGroupHandler}).</li>
 * </ul>
 */
public sealed interface ScopeRef {

    /** Direct-message scope, carrying the cryptographic contact id. */
    record Dm(String contactId) implements ScopeRef {}

    /** Group-mention scope, carrying the adapter-defined stable group id. */
    record Group(String adapterGroupId) implements ScopeRef {}
}
