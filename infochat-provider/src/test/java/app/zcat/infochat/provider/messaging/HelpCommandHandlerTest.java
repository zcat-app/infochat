package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link HelpCommandHandler}'s bundle-driven
 * reply composition per {@code docs/design/03-commands.md} §3.4 and
 * decision D30 (plain text, no markdown).
 *
 * <p>Four invariants are covered, each in its own {@code @Test}:</p>
 * <ol>
 *   <li>The composed reply contains the header text plus the three
 *       MVP command short-help lines (acceptance item 11).</li>
 *   <li>The handler consumes exactly the four MVP bundle keys —
 *       {@code help.header.dm-user}, {@code help.cmd.help.short},
 *       {@code help.cmd.add-source.short}, {@code help.cmd.summary.short}
 *       (acceptance item 12).</li>
 *   <li>A missing bundle key propagates as an exception rather than
 *       silently producing an incomplete reply (DoD §HelpCommandHandler
 *       and bundle-completeness CI alignment).</li>
 *   <li>The reply contains no markdown link syntax and no HTML
 *       anchor tags (acceptance item 13).</li>
 * </ol>
 */
@QuarkusTest
class HelpCommandHandlerTest {

    @Inject
    BundleLoader productionBundleLoader;

    @Test
    void replyContainsHeaderAndThreeMvpCommandShortHelpLines() {
        HelpCommandHandler handler = new HelpCommandHandler();
        handler.bundleLoader = productionBundleLoader;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/help");
        String body = reply.text();

        // Each fragment below is the header value or a command name
        // present in en.properties' MVP entries.
        assertTrue(body.contains("Available"),
                "reply must contain the header from help.header.dm-user: " + body);
        assertTrue(body.contains("/help"), "reply must mention /help: " + body);
        assertTrue(body.contains("/add-source"), "reply must mention /add-source: " + body);
        assertTrue(body.contains("/summary"), "reply must mention /summary: " + body);

        assertEquals("help", handler.name());
    }

    @Test
    void handlerConsumesExactlyTheFourMvpBundleKeys() {
        RecordingBundleLoader spy = new RecordingBundleLoader(Set.of(
                BundleKeys.HELP_HEADER_DM_USER,
                BundleKeys.HELP_CMD_HELP_SHORT,
                BundleKeys.HELP_CMD_ADD_SOURCE_SHORT,
                BundleKeys.HELP_CMD_SUMMARY_SHORT));
        HelpCommandHandler handler = new HelpCommandHandler();
        handler.bundleLoader = spy;

        handler.handle(new ScopeRef.Dm("alice"), "/help");

        assertTrue(spy.lookups.contains(BundleKeys.HELP_HEADER_DM_USER),
                "HELP_HEADER_DM_USER lookup missing; recorded: " + spy.lookups);
        assertTrue(spy.lookups.contains(BundleKeys.HELP_CMD_HELP_SHORT),
                "HELP_CMD_HELP_SHORT lookup missing; recorded: " + spy.lookups);
        assertTrue(spy.lookups.contains(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT),
                "HELP_CMD_ADD_SOURCE_SHORT lookup missing; recorded: " + spy.lookups);
        assertTrue(spy.lookups.contains(BundleKeys.HELP_CMD_SUMMARY_SHORT),
                "HELP_CMD_SUMMARY_SHORT lookup missing; recorded: " + spy.lookups);
    }

    @Test
    void missingBundleKeyCausesHandlerToFailInsteadOfShippingIncompleteReply() {
        // Spy permits no keys — every BundleLoader.get(...) throws as the
        // real BundleLoader would for a missing entry. Handler must
        // propagate; silently catching would defeat the bundle-completeness
        // CI guard.
        RecordingBundleLoader spy = new RecordingBundleLoader(Set.of());
        HelpCommandHandler handler = new HelpCommandHandler();
        handler.bundleLoader = spy;

        assertThrows(IllegalStateException.class,
                () -> handler.handle(new ScopeRef.Dm("alice"), "/help"));
    }

    @Test
    void replyContainsNoMarkdownLinkSyntaxOrHtmlAnchors() {
        HelpCommandHandler handler = new HelpCommandHandler();
        handler.bundleLoader = productionBundleLoader;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/help");
        String body = reply.text();

        assertFalse(containsMarkdownLink(body),
                "reply must not contain markdown link syntax [text](url): " + body);
        assertFalse(body.contains("<a href="),
                "reply must not contain HTML anchor tags: " + body);
    }

    /** Minimal {@code [text](url)} detector: a `[` followed (eventually) by `](` and a closing `)` on the same line. */
    private static boolean containsMarkdownLink(String body) {
        for (String line : body.split("\n", -1)) {
            int open = line.indexOf('[');
            if (open < 0) {
                continue;
            }
            int closeBracketParenOpen = line.indexOf("](", open);
            if (closeBracketParenOpen >= 0 && line.indexOf(')', closeBracketParenOpen) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hand-rolled spy: returns a synthetic stand-in value for every key
     * in {@code allowedKeys}, throws {@link IllegalStateException} for
     * everything else (same shape the real {@link BundleLoader} raises
     * for an unknown key), and records every lookup attempt for
     * assertion. Avoids pulling in Mockito for one test.
     */
    private static final class RecordingBundleLoader extends BundleLoader {
        private final Set<String> allowedKeys;
        final List<String> lookups = new ArrayList<>();

        RecordingBundleLoader(Set<String> allowedKeys) {
            this.allowedKeys = allowedKeys;
        }

        @Override
        public String get(String key) {
            lookups.add(key);
            if (!allowedKeys.contains(key)) {
                throw new IllegalStateException("Missing bundle key: " + key);
            }
            return "value-for:" + key;
        }
    }
}
