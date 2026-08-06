package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ScopeRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
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
 * <p>Six scenarios mirror acceptance items 3..8 of M1-051; the rest
 * pin M1-775's write-time expiry sweep and the payload-vs-retyped-
 * arguments match on the {@code PendingConfirm} SPI.</p>
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
        BanConfirm payload =
                new BanConfirm("target-contact", "spam", "intent-req");

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
        BanConfirm payload =
                new BanConfirm("target-contact", null, "intent-req");

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
                new BanConfirm("target-contact", "spam", "intent-req"));

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
        InviteRevokeConfirm payload =
                new InviteRevokeConfirm(UUID.randomUUID());

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

        BanConfirm first =
                new BanConfirm("first-target", null, "intent-req");
        InviteCreateOpenConfirm second =
                new InviteCreateOpenConfirm("inmemory");

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

        BanConfirm payloadA =
                new BanConfirm("target-a", null, "intent-req");
        BanConfirm payloadB =
                new BanConfirm("target-b", null, "intent-req");
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

    @Test
    void rememberSweepsExpiredEntriesOfOtherActors() {
        // M1-775: expiry used to be lazy only, so an entry armed by an
        // actor who never messages again outlived its deadline for the
        // process lifetime. Any remember now clears every past-deadline
        // entry in the map, not just the writer's own.
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID silentActor = UUID.randomUUID();
        UUID writingActor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("shared-scope-contact");

        service.remember(silentActor, scope,
                new BanConfirm("target-contact", null, "intent-req"));

        service.setClock(fixedAt(T0.plus(TIMEOUT).plusSeconds(1)));
        service.remember(writingActor, scope, new ClearConfirm());

        assertEquals(1, service.size(),
                "the expired entry must be gone, leaving only the entry just written");
        assertFalse(service.peek(silentActor, scope).isPresent(),
                "the silent actor's expired pending must have been swept by the other actor's remember");
    }

    @Test
    void rememberLeavesUnexpiredEntriesOfOtherActorsAlone() {
        // The complement, and the security-relevant half: the sweep walks
        // OTHER actors' entries, so it must remove strictly on deadline.
        // A live entry belonging to another actor is not the writer's to
        // cancel.
        ConfirmStateService service = new ConfirmStateService(fixedAt(T0), TIMEOUT);
        UUID quietActor = UUID.randomUUID();
        UUID writingActor = UUID.randomUUID();
        ScopeRef scope = new ScopeRef.Dm("shared-scope-contact");
        BanConfirm quietPayload = new BanConfirm("target-contact", null, "intent-req");

        service.remember(quietActor, scope, quietPayload);

        service.setClock(fixedAt(T0.plusSeconds(30)));
        service.remember(writingActor, scope, new ClearConfirm());

        assertEquals(2, service.size(),
                "a still-live entry of another actor must survive the write-time sweep");
        assertSame(quietPayload, service.peek(quietActor, scope).orElseThrow(),
                "the other actor's live pending must be untouched, not cancelled");
    }

    @Test
    void banConfirmMatchesOnlyItsOwnTargetContactId() {
        // M1-775's repro at the SPI tier: `/ban bob confirm` must not
        // satisfy a pending ban on alice.
        BanConfirm pending = new BanConfirm("alice-contact", "spam", "intent-req");

        assertTrue(pending.matchesRetypedArguments("alice-contact"),
                "retyping the pending target must be recognized as a confirmation");
        assertFalse(pending.matchesRetypedArguments("bob-contact"),
                "a confirm leg naming a DIFFERENT target must not redeem the pending ban");
        assertFalse(pending.matchesRetypedArguments("bob-contact alice-contact"),
                "the match is on the whole segment, not containment");
        assertFalse(pending.matchesRetypedArguments("ALICE-CONTACT"),
                "contact ids are opaque adapter identities and compare case-sensitively");
        assertFalse(pending.matchesRetypedArguments("alice-contact --reason spam"),
                "a retyped --reason is not part of the identity the confirm leg may restate");
    }

    @Test
    void argumentCarryingPayloadsMatchOnlyTheirOwnIdentifier() {
        // The legitimate argument-carrying legs: each prompt instructs a
        // body that restates the id, so the id must be accepted — but
        // only its own.
        UUID own = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID other = UUID.fromString("99999999-8888-7777-6666-555555555555");

        for (ConfirmStateService.PendingConfirm payload : List.of(
                new QuarantineRejectConfirm(own),
                new InviteRevokeConfirm(own),
                new RemoveSourceConfirm(own),
                new SourceEnableConfirm(own),
                new RejectGroupCommandHandler.RejectGroupConfirm(own))) {
            String label = payload.commandName();
            assertTrue(payload.matchesRetypedArguments(own.toString()),
                    label + ": retyping its own id must redeem the pending action");
            assertTrue(payload.matchesRetypedArguments(own.toString().toUpperCase(Locale.ROOT)),
                    label + ": case is not identity for a UUID");
            assertFalse(payload.matchesRetypedArguments(other.toString()),
                    label + ": a confirm leg naming a DIFFERENT id must not redeem it");
            assertFalse(payload.matchesRetypedArguments(own + " " + other),
                    label + ": the match is on the whole segment, not containment");
        }
    }

    @Test
    void argumentlessPayloadsRejectEveryRetypedArgument() {
        // The census rows left on the interface default: these commands
        // carry no identifying argument, so nothing a body could name
        // identifies them and the default fails closed.
        for (ConfirmStateService.PendingConfirm payload : List.of(
                new ClearConfirm(),
                new ForgetConfirm(),
                new UnfollowTagAllConfirm(),
                new InviteCreateOpenConfirm("inmemory"))) {
            String label = payload.commandName();
            assertFalse(payload.matchesRetypedArguments("anything"),
                    label + ": an argument-less command accepts the bare confirm leg only");
            assertFalse(payload.matchesRetypedArguments("--adapter inmemory"),
                    label + ": a retyped flag is not a confirmation either");
        }
    }
}
