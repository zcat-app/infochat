package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.ConfirmStateService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin {@link InboundRouter#normalize} against acceptance items (a)–(f)
 * of M1-038: NFKC + bidi-strip + zero-width-strip apply OUTSIDE
 * CommonMark fenced code blocks; lines INSIDE a fence (including the
 * fence delimiters themselves) round-trip byte-for-byte. The size-cap
 * acceptance item (g) is exercised via the end-to-end
 * {@link InboundRouter#onMessage} path: an oversize body returns the
 * {@link InboundRouter#MESSAGE_TOO_LARGE_REPLY} literal AND the
 * {@code NORMALIZE_INVOCATIONS} counter does not advance.
 *
 * <p>The test is plain JUnit (no Quarkus boot) — {@code normalize} is
 * a package-private static helper so the function-level coverage can
 * be exercised directly. The size-cap test instantiates
 * {@link InboundRouter} and wires no-op collaborators by hand because
 * Quarkus boot for one log-and-reply pin is over-weight.</p>
 */
class InboundRouterNormalizeTest {

    @BeforeEach
    void resetNormalizeCounter() {
        InboundRouter.NORMALIZE_INVOCATIONS.set(0);
    }

    // ----- (a) plain text outside fences is normalized as before --------

    /**
     * Plain non-fenced input is NFKC-normalized, bidi-stripped,
     * zero-width-stripped, and trimmed — exactly the M1-035b
     * pre-regression behavior, just expressed by the new per-line
     * normalize loop.
     */
    @Test
    void plainTextOutsideFencesIsNfkcNormalisedBidiStrippedAndTrimmed() {
        // U+FF1A FULLWIDTH COLON → ":" under NFKC; U+202E RTL OVERRIDE
        // is bidi; U+200B ZERO WIDTH SPACE is zero-width; outer spaces
        // are trimmed.
        String body = "   /help‮​：tag   ";
        String got = InboundRouter.normalize(body);
        assertEquals("/help:tag", got);
    }

    // ----- (b) U+FB01 LATIN SMALL LIGATURE FI inside a fence ------------

    /**
     * A fenced block containing U+FB01 (LIGATURE FI) round-trips
     * unchanged. NFKC would otherwise expand it to {@code "fi"}; the
     * carve-out preserves it.
     */
    @Test
    void ligatureFiInsideBacktickFenceRoundTripsUnchanged() {
        String body = "```\nﬁnal\n```";
        String got = InboundRouter.normalize(body);
        assertEquals(body, got);
        assertTrue(got.contains("ﬁ"),
                "ligature character must survive inside the fence; got " + got);
    }

    // ----- (c) fullwidth digit inside a fence ---------------------------

    /**
     * Fullwidth digit U+FF11 (FULLWIDTH DIGIT ONE) is preserved inside
     * the fence; NFKC would otherwise fold it to ASCII "1".
     */
    @Test
    void fullwidthDigitInsideFenceRoundTripsUnchanged() {
        String body = "```\nversion １\n```";
        String got = InboundRouter.normalize(body);
        assertEquals(body, got);
        assertTrue(got.contains("１"),
                "fullwidth digit must survive inside the fence; got " + got);
    }

    // ----- (d) trailing whitespace INSIDE a fenced block is preserved --

    /**
     * Trailing spaces on a line inside a fenced block are preserved
     * verbatim — the spec carve-out says bytes inside fences round-trip
     * unchanged.
     */
    @Test
    void trailingWhitespaceInsideFenceIsPreserved() {
        String body = "```\nfoo   \nbar\t\n```";
        String got = InboundRouter.normalize(body);
        assertEquals(body, got);
    }

    // ----- (e) text BEFORE and AFTER a fenced block is normalized -------

    /**
     * Lines outside the fenced block (both before and after) are
     * subject to NFKC + strips, while the in-fence content round-trips
     * verbatim. The whole-body trim removes the leading newline left
     * by the empty pre-fence prose; the post-fence prose is NFKC-folded.
     */
    @Test
    void textBeforeAndAfterFenceIsNormalizedFenceIsPreserved() {
        // U+FF14 FULLWIDTH DIGIT FOUR outside the fence → "4";
        // U+FF14 inside the fence is preserved.
        String body = "before ４\n```\nin４fence\n```\nafter ４";
        String got = InboundRouter.normalize(body);
        assertEquals("before 4\n```\nin４fence\n```\nafter 4", got);
    }

    // ----- (f) tilde fence carves out the same way ----------------------

    /**
     * A {@code ~~~}-delimited fence behaves identically to a
     * `` ``` ``-delimited one — same in-fence preservation rule.
     */
    @Test
    void tildeDelimitedFenceCarvesOutTheSameWay() {
        String body = "~~~\nﬁnal\n~~~";
        String got = InboundRouter.normalize(body);
        assertEquals(body, got);
        assertTrue(got.contains("ﬁ"),
                "ligature must survive inside a tilde fence; got " + got);
    }

    // ----- (g) size cap fires before normalize --------------------------

    /**
     * An oversize inbound is dropped with
     * {@link InboundRouter#MESSAGE_TOO_LARGE_REPLY} AND the
     * normalization pass is NEVER invoked. The
     * {@code NORMALIZE_INVOCATIONS} counter is the test seam — it is
     * incremented as the first line of {@code normalize}, so a
     * post-call assertion that it stayed at zero proves the cap-checked
     * branch returned before normalize.
     */
    @Test
    void oversizeBodyIsDroppedAndNormalizeIsNeverInvoked() {
        InboundRouter router = new InboundRouter();
        router.maxInboundBodyBytes = 16; // tiny cap so a short ASCII body overflows
        router.commandHandlers = new EmptyHandlerInstance();
        // M1-040: InboundContext is set at the top of onMessage and
        // must be non-null even when the size-cap path returns early.
        router.inboundContext = new InboundContext();
        // M1-044e: rate-cap fires BEFORE size-cap under the new
        // ordering; wire a no-op bucket so the size-cap reaches its
        // check without NPE'ing on the null field.
        router.rateCapBucket = new NoopRateCapBucket();
        // M1-045: oversize body short-circuits at step 1.6 (size cap)
        // BEFORE step 5 (probation), so the two new @Inject fields
        // are not strictly needed here. Wire them anyway for hygiene
        // so a future test addition in this file (or a future
        // re-ordering of the intake steps) does not silently
        // re-introduce a step-5 NPE. See NoopProbationCheck +
        // NoopCommandPermissions class-level javadoc for the
        // log-silent rationale.
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // 32-byte ASCII body, double the cap.
        String oversize = "0123456789ABCDEF0123456789ABCDEF";
        long invocationsBefore = InboundRouter.NORMALIZE_INVOCATIONS.get();

        router.onMessage(
                new InboundMessage(
                        new Identity("alice-contact-id-1234567890abcdef", "Alice", Instant.now()),
                        new ScopeRef.Dm("alice-contact-id-1234567890abcdef"),
                        oversize,
                        Instant.now(),
                        "msg-1"),
                "inmemory");

        assertEquals(1, target.captured.size(),
                "oversize body must produce exactly one too-large reply");
        assertEquals(InboundRouter.MESSAGE_TOO_LARGE_REPLY, target.captured.get(0).text(),
                "the oversize reply must be the fixed too-large literal");
        assertEquals(invocationsBefore, InboundRouter.NORMALIZE_INVOCATIONS.get(),
                "normalize() must NOT be invoked on an oversize body; counter must not advance");
    }

    /**
     * Sanity check: a body at exactly the cap is accepted and flows
     * through normalize as usual. The complement of (g).
     */
    @Test
    void bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns() {
        InboundRouter router = newRouterWithKnownVouchedUser();
        router.maxInboundBodyBytes = 16;
        router.commandHandlers = new EmptyHandlerInstance();
        router.inboundContext = new InboundContext();
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        String atCap = "0123456789ABCDEF"; // exactly 16 bytes
        long invocationsBefore = InboundRouter.NORMALIZE_INVOCATIONS.get();

        router.onMessage(
                new InboundMessage(
                        new Identity("alice-contact-id-1234567890abcdef", "Alice", Instant.now()),
                        new ScopeRef.Dm("alice-contact-id-1234567890abcdef"),
                        atCap,
                        Instant.now(),
                        "msg-1"),
                "inmemory");

        assertNotEquals(invocationsBefore, InboundRouter.NORMALIZE_INVOCATIONS.get(),
                "normalize() MUST be invoked when the body is at-or-below the cap; "
                        + "counter must advance");
    }

    // ----- test doubles -------------------------------------------------

    /**
     * Empty {@link Instance} — the router iterates {@code commandHandlers}
     * to dispatch slash commands; an empty iterator yields the
     * unknown-command reply. The size-cap tests above use chat-mode
     * input, so the iteration runs only on the "/" prefix branch in
     * {@link #bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns}, where the
     * body is non-slash and the dispatch table is not consulted.
     *
     * <p>The other {@link Instance} methods are unused by
     * {@link InboundRouter} and throw {@link UnsupportedOperationException}
     * to fail loudly if a future change starts consuming them — the
     * Quarkus-backed {@link InboundRouterTest} covers the full
     * production wiring path; this stub exists for normalize / size-cap
     * coverage only.</p>
     */
    private static final class EmptyHandlerInstance implements Instance<CommandHandler> {
        @Override
        public Iterator<CommandHandler> iterator() {
            return List.<CommandHandler>of().iterator();
        }

        @Override
        public CommandHandler get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<CommandHandler> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends CommandHandler> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends CommandHandler> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(CommandHandler instance) {
            // no-op
        }

        @Override
        public Handle<CommandHandler> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<CommandHandler>> handles() {
            throw new UnsupportedOperationException();
        }
    }

    /** Captures outbound messages the router sends. */
    private static final class CapturingAdapter implements MessagingAdapter {
        final List<OutboundMessage> captured = new ArrayList<>();

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity assertIdentity(InboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            captured.add(msg);
            return null;
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Helper for {@link #bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns}
     * (the one M1-035b @Test method that calls {@code onMessage} on
     * an at-cap body). The M1-044b splice introduces four new
     * mandatory CDI-injected collaborators on {@link InboundRouter}
     * (rateCapBucket, inviteCodeConsumer, banCheck, bundleLoader)
     * plus a {@link javax.sql.DataSource} used by the new
     * {@code lookupUser} method. The helper returns a router whose
     * {@code lookupUser} is overridden to return a fixed "vouched"
     * snapshot — that skips step 2 (DM unknown) and lets the at-cap
     * chat-mode body flow into the chat-mode-not-in-MVP reply path.
     * The four new collaborator
     * fields receive no-op fakes (the per-method assertion is purely
     * about the normalize-invocation counter, not about which fakes
     * were consulted, so the fakes just need to not NPE).
     */
    private InboundRouter newRouterWithKnownVouchedUser() {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                return Optional.of(new UserSnapshot(UUID.randomUUID(), false, "vouched"));
            }
        };
        router.rateCapBucket = new NoopRateCapBucket();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.banCheck = new NoopBanCheck();
        router.bundleLoader = new NoopBundleLoader();
        // M1-051: step 4.5 confirm-cancel sweep peek call would NPE on
        // a null @Inject field. A Noop returning Optional.empty() keeps
        // the at-cap chat-mode path producing exactly its existing
        // outbound (no extra cancellation reply leaks into the test's
        // captured outbound queue).
        router.confirmStateService = new NoopConfirmStateService();
        // M1-045: step 5 probation gate would NPE on null @Inject
        // fields. The bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns
        // scenario routes a vouched user through step 5, which
        // dereferences both fields. The Noop stand-ins live as
        // top-level classes in this package — see NoopProbationCheck
        // + NoopCommandPermissions class-level javadoc for the
        // log-silent rationale.
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, UUID scopeId) {}
        };
        // dataSource intentionally left null — lookupUser is overridden
        // above so the DataSource field is never accessed.
        return router;
    }

    /** No-op {@link RateCapBucket} — always admits ({@code tryAcquire} = true). */
    private static final class NoopRateCapBucket extends RateCapBucket {
        @Override
        public boolean tryAcquire(String adapter, String contactId) {
            return true;
        }
    }

    /** No-op {@link InviteCodeConsumer} — never invoked because the test router's lookupUser returns non-empty. */
    private static final class NoopInviteCodeConsumer extends InviteCodeConsumer {
        @Override
        public Outcome consume(String adapter, String contactId, String body) {
            throw new UnsupportedOperationException(
                    "inviteCodeConsumer should not run when the user is known (vouched)");
        }
    }

    /** No-op {@link BanCheck} — always reports {@code is_banned=false}. */
    private static final class NoopBanCheck extends BanCheck {
        @Override
        public boolean isBanned(String adapter, String contactId) {
            return false;
        }
    }

    /** No-op {@link BundleLoader} — returns a stub value if asked (the at-cap test does not consume any bundle key). */
    private static final class NoopBundleLoader extends BundleLoader {
        @Override
        public String get(String key) {
            return "noop:" + key;
        }
    }

    /**
     * No-op {@link ConfirmStateService} — always reports no pending
     * confirm state. The at-cap test does not exercise the step 4.5
     * branch behavior, but step 4.5's peek call still fires; this
     * fake keeps the call safe (no NPE on a null @Inject field) and
     * cheap (no clock / map work).
     */
    private static final class NoopConfirmStateService extends ConfirmStateService {
        @Override
        public Optional<ConfirmStateService.PendingConfirm> peek(java.util.UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeAny(java.util.UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeMatching(java.util.UUID actor, ScopeRef scope, String commandName) {
            return Optional.empty();
        }
        @Override
        public void remember(java.util.UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
            // no-op
        }
    }
}
