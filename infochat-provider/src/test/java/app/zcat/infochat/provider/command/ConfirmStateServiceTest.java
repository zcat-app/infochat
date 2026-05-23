package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ScopeRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit service-tier coverage for {@link ConfirmStateService}.
 * Bypasses CDI per the M1-049 test-pyramid push: the service is
 * instantiated with the package-private test constructor that takes a
 * controllable {@link Clock} + explicit timeout, so the deadline-boundary
 * scenarios are deterministic without a Quarkus boot.
 *
 * <p>Six scenarios mirror acceptance items 3..8 of M1-051.</p>
 */
class ConfirmStateServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-05-23T12:00:00Z");

    private static Clock fixedAt(Instant moment) {
        return Clock.fixed(moment, ZoneOffset.UTC);
    }

    @Test
    void rememberThenTakeMatchingReturnsValueAndRemovesEntry() {
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("actor-contact");
        ConfirmStateService.PendingConfirm.Ban payload =
                new ConfirmStateService.PendingConfirm.Ban("target-contact", "spam");

        service.remember(actor, scope, payload);

        Optional<ConfirmStateService.PendingConfirm> taken =
                service.takeMatching(actor, scope, "ban");
        assertTrue(taken.isPresent(), "takeMatching with the same commandName must return the stored value");
        assertSame(payload, taken.get(),
                "takeMatching must return the exact stored payload instance");

        // A second takeMatching returns empty — the first call removed the entry.
        assertFalse(service.takeMatching(actor, scope, "ban").isPresent(),
                "second takeMatching must return empty (entry removed)");
        assertFalse(service.peek(actor, scope).isPresent(),
                "peek after takeMatching must return empty");
        assertEquals(0, service.size(), "internal map must be empty after takeMatching");
    }

    @Test
    void takeMatchingWithDifferentCommandNameReturnsEmptyAndPreservesEntry() {
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("actor-contact");
        ConfirmStateService.PendingConfirm.Ban payload =
                new ConfirmStateService.PendingConfirm.Ban("target-contact", null);

        service.remember(actor, scope, payload);

        Optional<ConfirmStateService.PendingConfirm> taken =
                service.takeMatching(actor, scope, "invite:create:open");
        assertFalse(taken.isPresent(),
                "takeMatching with a different commandName must return empty");

        // The original entry survives — a peek for it returns the payload.
        Optional<ConfirmStateService.PendingConfirm> peeked = service.peek(actor, scope);
        assertTrue(peeked.isPresent(), "peek must still return the original pending entry");
        assertSame(payload, peeked.get(),
                "peek must return the same instance the mismatched takeMatching left in place");

        // Confirming the entry is still present and is still takeable by its own name.
        assertTrue(service.takeMatching(actor, scope, "ban").isPresent(),
                "takeMatching by the original commandName must still succeed");
    }

    @Test
    void takeMatchingPastDeadlineReturnsEmptyAndRemovesExpiredEntry() {
        // Fixed clock at T0 for the remember, advanced clock at T0+timeout+1s
        // for the take. Test seam: setClock() rebinds the service's clock
        // between the two operations.
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("actor-contact");
        service.remember(actor, scope,
                new ConfirmStateService.PendingConfirm.Ban("target-contact", "spam"));

        // Advance fake clock past the deadline (deadline = T0 + 60s; pick T0 + 61s).
        service.setClock(fixedAt(T0.plus(TIMEOUT).plusSeconds(1)));

        Optional<ConfirmStateService.PendingConfirm> taken =
                service.takeMatching(actor, scope, "ban");
        assertFalse(taken.isPresent(),
                "takeMatching past the deadline must return empty");

        // Lazy expiry removed the entry — a subsequent peek returns empty.
        assertFalse(service.peek(actor, scope).isPresent(),
                "peek after past-deadline takeMatching must return empty (expired entry removed)");
        assertEquals(0, service.size(),
                "internal map must be empty after past-deadline takeMatching");
    }

    @Test
    void takeAnyReturnsValueAndRemovesEntry() {
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("actor-contact");
        ConfirmStateService.PendingConfirm.InviteRevoke payload =
                new ConfirmStateService.PendingConfirm.InviteRevoke(UUID.randomUUID());

        service.remember(actor, scope, payload);

        Optional<ConfirmStateService.PendingConfirm> taken = service.takeAny(actor, scope);
        assertTrue(taken.isPresent(),
                "takeAny must return the stored value regardless of commandName");
        assertSame(payload, taken.get(),
                "takeAny must return the exact stored payload instance");
        assertEquals(0, service.size(),
                "takeAny must remove the entry from the internal map");
        assertFalse(service.peek(actor, scope).isPresent(),
                "peek after takeAny must return empty");
    }

    @Test
    void secondRememberOverwritesFirstPendingForSameKey() {
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("actor-contact");

        ConfirmStateService.PendingConfirm.Ban first =
                new ConfirmStateService.PendingConfirm.Ban("first-target", null);
        ConfirmStateService.PendingConfirm.InviteCreateOpen second =
                new ConfirmStateService.PendingConfirm.InviteCreateOpen("inmemory");

        service.remember(actor, scope, first);
        service.remember(actor, scope, second);

        // takeMatching for the FIRST commandName must return empty — the
        // second remember replaced the first.
        assertFalse(service.takeMatching(actor, scope, "ban").isPresent(),
                "second remember must overwrite the first; takeMatching for the FIRST "
                        + "commandName must return empty");

        // takeMatching for the SECOND commandName returns the second value.
        Optional<ConfirmStateService.PendingConfirm> taken =
                service.takeMatching(actor, scope, "invite:create:open");
        assertTrue(taken.isPresent(),
                "takeMatching for the SECOND commandName must return the overwriting value");
        assertSame(second, taken.get(),
                "takeMatching must return the overwriting instance, not the first one");
        assertNotSame(first, taken.get(),
                "the second remember replaced the first; the first instance must be gone");
    }

    @Test
    void remembersForDifferentActorIdsAreIsolated() {
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("shared-scope-contact");
        // Same scope literal across actorA and actorB to prove the
        // actor id alone is sufficient to isolate the entries; per-scope
        // isolation is verified inline by the ScopeRef record equality.

        ConfirmStateService.PendingConfirm.Ban payloadA =
                new ConfirmStateService.PendingConfirm.Ban("target-a", null);
        ConfirmStateService.PendingConfirm.Ban payloadB =
                new ConfirmStateService.PendingConfirm.Ban("target-b", null);
        service.remember(actorA, scope, payloadA);
        service.remember(actorB, scope, payloadB);

        Optional<ConfirmStateService.PendingConfirm> takenA =
                service.takeMatching(actorA, scope, "ban");
        Optional<ConfirmStateService.PendingConfirm> takenB =
                service.takeMatching(actorB, scope, "ban");

        assertTrue(takenA.isPresent(), "actor A must have its own pending");
        assertSame(payloadA, takenA.get(),
                "actor A's take must return actor A's stored value, not actor B's");
        assertTrue(takenB.isPresent(), "actor B must have its own pending");
        assertSame(payloadB, takenB.get(),
                "actor B's take must return actor B's stored value, not actor A's");
    }
}
