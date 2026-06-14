package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
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
 * {@code error.router.message_too_large} bundle reply AND the
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

    /**
     * The implicit directional marks U+061C (ARABIC LETTER MARK),
     * U+200E (LEFT-TO-RIGHT MARK), U+200F (RIGHT-TO-LEFT MARK) are
     * stripped outside fences. NFKC does not remove them, so only the
     * explicit bidi-control strip covers them — a disguised {@code /}
     * prefix must not survive via these marks.
     */
    @Test
    void implicitDirectionalMarksAreStrippedOutsideFences() {
        String body = "؜/he‎lp‏";
        String got = InboundRouter.normalize(body);
        assertEquals("/help", got);
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
     * An oversize inbound is dropped with the
     * {@code error.router.message_too_large} bundle reply AND the
     * normalization pass is NEVER invoked. The
     * {@code NORMALIZE_INVOCATIONS} counter is the test seam — it is
     * incremented as the first line of {@code normalize}, so a
     * post-call assertion that it stayed at zero proves the cap-checked
     * branch returned before normalize.
     */
    @Test
    void oversizeBodyIsDroppedAndNormalizeIsNeverInvoked() {
        InboundRouter router = new InboundRouter();
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.maxInboundBodyBytes = 16; // tiny cap so a short ASCII body overflows
        router.commandHandlers = new EmptyHandlerInstance();
        // M1-040: InboundContext is set at the top of onMessage and
        // must be non-null even when the size-cap path returns early.
        router.inboundContext = new InboundContext();
        // M1-044e: rate-cap fires BEFORE size-cap under the new
        // ordering; wire a no-op bucket so the size-cap reaches its
        // check without NPE'ing on the null field.
        router.rateCapBucket = new NoopRateCapBucket();
        // §7a wiring: the registered/stranger split is consulted right
        // before the rate cap on every dispatch.
        router.registeredContactSet = new NoopRegisteredContactSet();
        // The size-cap reply resolves through the bundle (D43); the
        // Noop returns "noop:<key>".
        router.bundleLoader = new NoopBundleLoader();
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
        assertEquals("noop:" + BundleKeys.ERROR_ROUTER_MESSAGE_TOO_LARGE, target.captured.get(0).text(),
                "the oversize reply must be the fixed too-large bundle entry");
        assertEquals(invocationsBefore, InboundRouter.NORMALIZE_INVOCATIONS.get(),
                "normalize() must NOT be invoked on an oversize body; counter must not advance");
    }

    /**
     * Single-sourcing (M1-336): the {@code adapter.message.bytes} value
     * recorded for an inbound body equals the same UTF-8 byte length the
     * size cap tests it against — both read one {@code Utf8.byteLength}
     * walk. A multibyte body (18 bytes / 9 chars) proves the recorded
     * value is the byte length, not the char count, and that the cap
     * rejected the body on that very value.
     */
    @Test
    void recordedInboundBytesEqualTheCapTestedByteLength() {
        InboundRouter router = new InboundRouter();
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.maxInboundBodyBytes = 16; // tiny cap so an 18-byte body overflows
        router.commandHandlers = new EmptyHandlerInstance();
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.bundleLoader = new NoopBundleLoader();
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        router.adapterMetrics = new AdapterMetrics(registry);
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // 9 × U+00E9 (é): 18 UTF-8 bytes but 9 Java chars — over the 16-byte cap.
        String body = "é".repeat(9);
        int expected = Utf8.byteLength(body);

        router.onMessage(
                new InboundMessage(
                        new Identity("alice-contact-id-1234567890abcdef", "Alice", Instant.now()),
                        new ScopeRef.Dm("alice-contact-id-1234567890abcdef"),
                        body,
                        Instant.now(),
                        "msg-1"),
                "inmemory");

        assertEquals("noop:" + BundleKeys.ERROR_ROUTER_MESSAGE_TOO_LARGE,
                target.captured.get(0).text(),
                "the over-cap multibyte body must be rejected by the size cap");
        assertEquals((double) expected,
                registry.get("adapter.message.bytes")
                        .tags("adapter", "inmemory", "direction", "inbound")
                        .summary().totalAmount(),
                "the recorded byte length must equal the single Utf8.byteLength the cap tested");
        assertTrue(expected > router.maxInboundBodyBytes,
                "guard: the recorded length is the value that tripped the cap (byte length, not char count)");
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

    /**
     * Helper for {@link #bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns}
     * (the one M1-035b @Test method that calls {@code onMessage} on
     * an at-cap body). The M1-044b splice wires mandatory
     * CDI-injected collaborators on {@link InboundRouter}
     * (rateCapBucket, inviteCodeConsumer, bundleLoader)
     * plus a {@link javax.sql.DataSource} used by the new
     * {@code lookupUser} method. The helper returns a router whose
     * {@code lookupUser} is overridden to return a fixed "vouched"
     * snapshot — that skips step 2 (DM unknown) and lets the at-cap
     * chat-mode body flow into the chat-mode-not-in-MVP reply path.
     * The collaborator
     * fields receive no-op fakes (the per-method assertion is purely
     * about the normalize-invocation counter, not about which fakes
     * were consulted, so the fakes just need to not NPE).
     */
    private InboundRouter newRouterWithKnownVouchedUser() {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(UUID.randomUUID(), "vouched", false, null));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.rateCapBucket = new NoopRateCapBucket();
        // §7a wiring: the registered/stranger split is consulted right
        // before the rate cap on every dispatch.
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
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
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        // dataSource intentionally left null — lookupUser is overridden
        // above so the DataSource field is never accessed.
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        return router;
    }
}
