package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: verifies the six messaging-adapter SPI types compile and
 * are loadable on the infochat-messaging-adapter classpath with the
 * kinds the spec commits to (interface for MessagingAdapter /
 * TranslationProvider / ProgressNotifier, enum for ProgressStage,
 * record for MessageHandle / CapabilityFlags). No behavior is
 * exercised here — that lives with the impl-side tickets that will
 * land concrete SimpleX / Signal / InMemory adapters. The cross-module
 * load test (ingest SPIs + LLM SPIs + Messaging SPIs all visible from
 * the same classpath) is the M1-007 umbrella commit's verification
 * surface, not this one.
 */
class MessagingSpisLoadTest {

    @Test
    void messagingAdapterIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.MessagingAdapter");
        assertNotNull(type);
        assertTrue(type.isInterface(), "MessagingAdapter must be an interface");
    }

    @Test
    void translationProviderIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.TranslationProvider");
        assertNotNull(type);
        assertTrue(type.isInterface(), "TranslationProvider must be an interface");
    }

    @Test
    void progressNotifierIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.ProgressNotifier");
        assertNotNull(type);
        assertTrue(type.isInterface(), "ProgressNotifier must be an interface");
    }

    @Test
    void progressStageIsLoadableEnumWithSpecMandatedValues() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.ProgressStage");
        assertNotNull(type);
        assertTrue(type.isEnum(), "ProgressStage must be an enum");
        // The exact seven values are spec-mandated (docs/spec/messaging.md
        // §Progress notifications). Asserting the count guards against a
        // future drive-by addition that would drift from the bundle-key
        // set (decision D43) and silently produce empty user-visible
        // output for the new stage.
        assertEquals(7, type.getEnumConstants().length,
                "ProgressStage must have exactly seven spec-mandated values");
    }

    @Test
    void messageHandleIsLoadableRecord() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.MessageHandle");
        assertNotNull(type);
        assertTrue(type.isRecord(), "MessageHandle must be a record");
    }

    @Test
    void capabilityFlagsIsLoadableRecord() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.messaging.CapabilityFlags");
        assertNotNull(type);
        assertTrue(type.isRecord(), "CapabilityFlags must be a record");
    }
}
