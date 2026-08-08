package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * M1-799: the outbound attachment SPI surface — the D74 payload shape
 * and the opt-in gate contract of the default method.
 */
class MessagingAdapterAttachmentSpiTest {

    private static OutboundAttachment attachment() {
        return new OutboundAttachment(
                new ScopeRef.Dm("contact-x"),
                "/tmp/image.png",
                "image/png",
                "image.png",
                "corr-att");
    }

    /**
     * The payload is a file path, never bytes — the D74 shape pinned at
     * the type level: a byte[] component would fail to compile the
     * String assignment.
     */
    @Test
    void thePayloadCarriesAPathNotBytes() {
        OutboundAttachment attachment = attachment();
        String path = attachment.filePath();
        assertEquals("/tmp/image.png", path);
        assertEquals("image/png", attachment.mimeType());
        assertEquals("image.png", attachment.displayFileName());
    }

    /**
     * FAILURE-MODE: an adapter that declares
     * {@code supportsOutboundAttachments = false} and never overrides
     * the default must fail loudly if misinvoked — PERMANENT, never a
     * silent no-op that masquerades as a send.
     */
    @Test
    void defaultSendAttachmentFailsPermanently() {
        MessagingAdapter bareAdapter = new MessagingAdapter() {
            @Override
            public String name() {
                return "bare";
            }

            @Override
            public CapabilityFlags capabilities() {
                return new CapabilityFlags(
                        /* supportsMentionByContactId */ false,
                        /* supportsMembershipEvents   */ false,
                        /* supportsCodeFormatting     */ false,
                        /* supportsMarkdownLinks      */ false,
                        /* maxInboundMessageBytes     */ 100_000,
                        /* maxSendsPerSecond          */ 1,
                        /* supportsMessageEdit        */ false,
                        /* supportsTypingIndicator    */ false,
                        /* minEditInterval            */ Duration.ZERO,
                        /* supportsOutboundAttachments */ false,
                        /* maxOutboundAttachmentBytes  */ 0);
            }

            @Override
            public AdapterTrustLevel trustLevel() {
                return AdapterTrustLevel.HIGH;
            }

            @Override
            public boolean isWellFormedContactId(String contactId) {
                return true;
            }

            @Override
            public MessageHandle send(OutboundMessage msg) {
                return new MessageHandle("bare");
            }

            @Override
            public void update(MessageHandle handle, String body) {
            }

            @Override
            public void finalizeMessage(MessageHandle handle, String body) {
            }

            @Override
            public void setTyping(ScopeRef scope, boolean typing) {
            }

            @Override
            public void setInboundHandler(InboundHandler handler) {
            }
        };

        MessagingException ex = assertThrows(MessagingException.class,
                () -> bareAdapter.sendAttachment(attachment()));
        assertEquals(FailureCategory.PERMANENT, ex.category());
    }
}
