package app.zcat.infochat.provider.bundle;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-622 subscription-guidance copy check. The subscription model
 * (D59, M1-621) is implicit-bootstrap + digest-only /follow-tag; that
 * model is only good UX if the in-band copy explains it. This test pins
 * the three guidance pieces to their bundle keys in BOTH shipped
 * languages (D43 bilateral), so a translation drop or a revert to the
 * pre-M1-621 opt-in framing fails the build:
 *
 * <ol>
 *   <li>the fresh-DM welcome states the user already follows every source
 *       and that /follow-tag focuses the DIGEST while chat stays broad;</li>
 *   <li>the /follow-tag and /unfollow-tag success replies clarify that the
 *       change affects the digest only, not chat/RAG retrieval;</li>
 *   <li>the empty-window /summary reply nudges toward /follow-tag.</li>
 * </ol>
 *
 * <p>The assertions target the guidance substrings, not the full copy —
 * the exact wording is reviewed like code but is free to change; the
 * digest-vs-chat distinction and the implicit-following framing are the
 * invariants. Welcome substrings deliberately avoid apostrophes: the
 * welcome key is a MessageFormat template, so {@code bundleLoader.get}
 * returns the RAW form with doubled {@code ''} (M1-590), and an
 * apostrophe-bearing substring would spuriously miss.</p>
 */
@QuarkusTest
class SubscriptionGuidanceCopyTest {

    @Inject
    BundleLoader bundleLoader;

    @Test
    void welcomeStatesImplicitFollowingAndDigestFocusInBothLanguages() {
        String en = bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH, "en");
        assertTrue(en.contains("already following all our standard sources"),
                "en welcome must state the user already follows every source (D59 implicit "
                        + "bootstrap); got: " + en);
        assertTrue(en.contains("/follow-tag"),
                "en welcome must point at /follow-tag to focus the digest; got: " + en);
        assertTrue(en.contains("chat always searches everything"),
                "en welcome must state chat stays broad; got: " + en);
        assertFalse(en.contains("Content starts once you follow"),
                "en welcome must NOT retain the pre-M1-621 opt-in framing ('Content starts "
                        + "once you follow sources'); got: " + en);

        String cs = bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH, "cs");
        assertTrue(cs.contains("sledujete všechny naše standardní zdroje"),
                "cs welcome must state the user already follows every source; got: " + cs);
        assertTrue(cs.contains("/follow-tag"),
                "cs welcome must point at /follow-tag to focus the digest; got: " + cs);
        assertTrue(cs.contains("chat vždy prohledává"),
                "cs welcome must state chat stays broad; got: " + cs);
        assertFalse(cs.contains("Obsah začne přicházet"),
                "cs welcome must NOT retain the pre-M1-621 opt-in framing; got: " + cs);
    }

    @Test
    void followAndUnfollowTagRepliesClarifyDigestOnlyInBothLanguages() {
        String[] enKeys = {
                BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL,
                BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_IN_PLACE,
                BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL,
                BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE,
        };
        for (String key : enKeys) {
            String en = bundleLoader.get(key, "en");
            assertTrue(en.contains("chat still searches all your sources"),
                    "en tag reply " + key + " must clarify chat stays broad (digest-only "
                            + "narrowing); got: " + en);
            String cs = bundleLoader.get(key, "cs");
            assertTrue(cs.contains("chat stále prohledává všechny vaše zdroje"),
                    "cs tag reply " + key + " must clarify chat stays broad; got: " + cs);
        }
    }

    @Test
    void emptyWindowSummaryNudgesTowardFollowTagInBothLanguages() {
        String en = bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, "en");
        assertTrue(en.contains("/follow-tag"),
                "en empty-window /summary reply must nudge toward /follow-tag; got: " + en);
        String cs = bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, "cs");
        assertTrue(cs.contains("/follow-tag"),
                "cs empty-window /summary reply must nudge toward /follow-tag; got: " + cs);
    }
}
