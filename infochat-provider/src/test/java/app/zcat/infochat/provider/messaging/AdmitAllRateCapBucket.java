package app.zcat.infochat.provider.messaging;

import java.util.UUID;

/**
 * Admits everything across all bucket families the router consults.
 * Outside CDI the inherited implementations NPE on their null
 * {@code @ConfigProperty} fields, so each is answered directly —
 * {@link NoopRateCapBucket} covers only the transport-level 3-arg
 * form, which is not enough for a chat-mode group dispatch.
 */
final class AdmitAllRateCapBucket extends RateCapBucket {

    @Override
    public boolean tryAcquire(String adapter, String contactId, boolean registered) {
        return true;
    }

    @Override
    public boolean tryAcquireGroupCommand(UUID groupId) {
        return true;
    }

    @Override
    public boolean tryAcquireGroupLlm(UUID groupId) {
        return true;
    }
}
