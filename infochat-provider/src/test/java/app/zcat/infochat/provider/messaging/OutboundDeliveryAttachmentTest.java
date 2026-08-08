package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.image.ImageSpool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit — {@link OutboundDelivery} (no-op sleeper, recording
 * doubles) plus an {@link ImageSpool} over {@code @TempDir}: the
 * attachment path's gates, ladder, attribution and reclaim (P2/P3/P23). */
class OutboundDeliveryAttachmentTest {

    @TempDir
    Path tempDir;

    private static OutboundDelivery delivery() {
        return new OutboundDelivery(
                new RecordingAdminNotifier(), new RecordingGroupRepository(),
                3, 0L, 2.0, 3, millis -> { });
    }

    private static OutboundAttachment attachment(ImageSpool spool, String fileName, int bytes)
            throws IOException {
        Path file = spool.write(fileName, new byte[bytes]);
        return new OutboundAttachment(
                new ScopeRef.Dm("contact-1"), file.toString(), "image/png",
                fileName, UUID.randomUUID().toString());
    }

    @Test
    void transientFailureRetriesThenSucceeds() throws IOException {
        // The attachment path preserves the text path's TRANSIENT ladder:
        // one transient failure, then the retry delivers (P23).
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        AttachmentFailingMessagingAdapter adapter =
                new AttachmentFailingMessagingAdapter("chanA", 1, FailureCategory.TRANSIENT,
                        true, 1_048_576);
        OutboundAttachment attachment = attachment(spool, "pic.png", 100);

        boolean delivered = delivery().deliverAttachment(adapter, attachment, spool, null);

        assertTrue(delivered, "delivery should succeed on the retry");
        assertEquals(2, adapter.attachmentAttempts.get(),
                "one failure then one successful retry");
        assertEquals(1, adapter.successfulAttachments.get());
        assertEquals(List.of(attachment), adapter.received,
                "the retry passes the same payload");
    }

    @Test
    void permanentGroupFailureFeedsTheBotRemovedCounter() throws IOException {
        // P23: an image send into a group that fails permanently feeds the
        // BOT_REMOVED counter exactly as a text send does.
        RecordingGroupRepository repo = new RecordingGroupRepository();
        OutboundDelivery delivery = new OutboundDelivery(
                new RecordingAdminNotifier(), repo, 3, 0L, 2.0, 3, millis -> { });
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            AttachmentFailingMessagingAdapter adapter =
                    AttachmentFailingMessagingAdapter.alwaysFailing(
                            "chanA", FailureCategory.PERMANENT, 1_048_576);
            boolean delivered = delivery.deliverAttachment(
                    adapter, attachment(spool, "pic-" + i + ".png", 100), spool, groupId);
            assertFalse(delivered, "a permanent failure aborts the attachment send");
        }

        assertEquals(List.of(groupId), repo.removed,
                "three consecutive permanent group attachment failures soft-remove the group");
    }

    @Test
    void neverInvokesAFalseFlagAdapter() throws IOException {
        // P2 / messaging.md:139-140: an adapter declaring
        // supportsOutboundAttachments=false is never invoked (the flag is
        // checkable statically, pre-charge).
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        AttachmentFailingMessagingAdapter adapter =
                AttachmentFailingMessagingAdapter.withoutAttachmentSupport("chanA");

        boolean delivered = delivery().deliverAttachment(
                adapter, attachment(spool, "pic.png", 100), spool, null);

        assertFalse(delivered, "a false-flag adapter is refused");
        assertEquals(0, adapter.attachmentAttempts.get(),
                "the false-flag adapter is never invoked");
    }

    @Test
    void refusesOverCeilingPayloadsBeforeInvoking() throws IOException {
        // P2 / messaging.md:139-140: an over-maxOutboundAttachmentBytes
        // payload is refused before the SPI call — the never-invoked
        // assertion is the discriminating one.
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        AttachmentFailingMessagingAdapter adapter =
                AttachmentFailingMessagingAdapter.succeeding("chanA", 10);

        boolean delivered = delivery().deliverAttachment(
                adapter, attachment(spool, "pic.png", 100), spool, null);

        assertFalse(delivered, "an over-ceiling payload is refused");
        assertEquals(0, adapter.attachmentAttempts.get(),
                "the adapter is never invoked for an over-ceiling payload");
    }

    @Test
    void successfulDeliveryReclaimsTheSpoolFile() throws IOException {
        // messaging.md:134-138: after the adapter reports delivery
        // completion (the sendAttachment return), the spool file is
        // reclaimed — delete-on-completion, not left for the sweeper.
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        AttachmentFailingMessagingAdapter adapter =
                AttachmentFailingMessagingAdapter.succeeding("chanA", 1_048_576);
        OutboundAttachment attachment = attachment(spool, "pic.png", 100);
        Path spooled = Path.of(attachment.filePath());
        assertTrue(Files.exists(spooled), "the payload is spooled before delivery");

        boolean delivered = delivery().deliverAttachment(adapter, attachment, spool, null);

        assertTrue(delivered);
        assertTrue(Files.notExists(spooled),
                "the spool file is reclaimed on delivery completion");
    }
}
