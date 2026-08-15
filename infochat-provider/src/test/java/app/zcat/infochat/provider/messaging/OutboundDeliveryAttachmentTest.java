package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.image.ImageSpool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
                fileName, UUID.randomUUID().toString(), null);
    }

    /** M1-842 item 6: the preview never touches the spool — one file
     * per image at delivery time, none after the reclaim. */
    private static OutboundAttachment previewCarryingAttachment(
            ImageSpool spool, String fileName, int bytes, String preview) throws IOException {
        Path file = spool.write(fileName, new byte[bytes]);
        return new OutboundAttachment(
                new ScopeRef.Dm("contact-1"), file.toString(), "image/png",
                fileName, UUID.randomUUID().toString(), preview);
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

    /** M1-842 item 6 (D75 no-retention): the preview never touches the
     * spool — exactly one file per image at delivery time, and none after
     * the reclaim. */
    @Test
    void previewCarryingDeliverySpoolsExactlyOneFilePerImage() throws IOException {
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        List<Integer> filesAtDeliveryTime = new ArrayList<>();
        MessagingAdapter snapshottingAdapter = new MessagingAdapter() {
            @Override
            public String name() {
                return "snapshotting";
            }

            @Override
            public CapabilityFlags capabilities() {
                return new CapabilityFlags(false, false, false, false, 65_536, 1,
                        false, false, Duration.ZERO, true, 1_048_576);
            }

            @Override
            public boolean isWellFormedContactId(String contactId) {
                return true;
            }

            @Override
            public MessageHandle send(OutboundMessage msg) {
                throw new UnsupportedOperationException("not exercised by attachment tests");
            }

            @Override
            public void update(MessageHandle handle, String body) {
                throw new UnsupportedOperationException("not exercised by attachment tests");
            }

            @Override
            public void finalizeMessage(MessageHandle handle, String body) {
                throw new UnsupportedOperationException("not exercised by attachment tests");
            }

            @Override
            public void setTyping(ScopeRef scope, boolean isTyping) {
                // no-op
            }

            @Override
            public AdapterTrustLevel trustLevel() {
                return AdapterTrustLevel.LOW;
            }

            @Override
            public void setInboundHandler(MessagingAdapter.InboundHandler handler) {
                // no-op
            }

            @Override
            public void sendAttachment(OutboundAttachment attachment) {
                try (var entries = Files.list(tempDir)) {
                    filesAtDeliveryTime.add((int) entries.count());
                } catch (IOException e) {
                    throw new IllegalStateException("spool dir unreadable", e);
                }
            }
        };
        OutboundAttachment attachment = previewCarryingAttachment(
                spool, "pic.png", 100, "data:image/png;base64,iVBORw0KGgo=");

        boolean delivered = delivery().deliverAttachment(snapshottingAdapter, attachment, spool, null);

        assertTrue(delivered);
        assertEquals(List.of(1), filesAtDeliveryTime,
                "the preview lives in memory and the record only — no second spool file");
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.count(),
                    "the single spool file is reclaimed on completion");
        }
    }
}
