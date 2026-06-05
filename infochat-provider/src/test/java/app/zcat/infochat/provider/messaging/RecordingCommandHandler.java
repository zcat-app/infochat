package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Instant;
import java.util.UUID;

/**
 * Test {@link CommandHandler} that records its dispatch into the
 * {@link CallLog} and returns a deterministic body keyed on its name.
 */
final class RecordingCommandHandler implements CommandHandler {
    private final CallLog log;
    private final String name;

    RecordingCommandHandler(CallLog log, String name) {
        this.log = log;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        log.calls.add("handler.handle(" + name + ")");
        return new OutboundMessage(
                scope,
                "handler-reply:" + name,
                Instant.now(),
                UUID.randomUUID().toString());
    }
}
