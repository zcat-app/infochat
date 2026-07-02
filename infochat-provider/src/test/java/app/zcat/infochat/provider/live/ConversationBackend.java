package app.zcat.infochat.provider.live;

import java.util.Optional;

/**
 * The pluggable seam between a {@link Scenario} and a concrete messaging transport.
 * It deals only in scenario types and plain reply {@code String}s — it imports no
 * adapter or messaging SPI type — so a Phase-4b SimpleX binding drops in without
 * touching the runner core (M1-539 acceptance item 4: {@code ConversationBackend}
 * is the only seam).
 */
public interface ConversationBackend {

    /** Deliver a step's send to the transport. */
    void send(Scenario.Send send);

    /**
     * Wait for a reply satisfying {@code expect}, returning the matching reply body
     * once observed, or {@link Optional#empty()} if none matched within
     * {@code expect.timeout()}. Implemented as a poll-until-match-or-timeout wait,
     * which is trivially satisfied on a synchronous in-JVM backend and genuinely
     * waits on an asynchronous observed-client-side one — hiding that difference
     * from the runner. Considers only replies produced after the preceding
     * {@link #send} (the binding tracks the per-step boundary).
     */
    Optional<String> awaitReply(Scenario.Expect expect);
}
