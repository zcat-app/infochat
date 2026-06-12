package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Records {@link ThrottledAdminNotifier#notifyOnce(String, String, String)}
 * calls so {@link OutboundDelivery}'s cap-exhaustion path can be asserted
 * without a database. Always reports {@link NotifyOutcome#EMITTED}; the
 * once-per-window throttling is the real notifier's own (separately tested)
 * contract — here we only assert OutboundDelivery delegates with the right
 * {@code (channel, error_class)} key.
 */
final class RecordingAdminNotifier extends ThrottledAdminNotifier {

    record Notification(String key, String errorClass, String message) {}

    final List<Notification> notifications = new ArrayList<>();

    @Override
    public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
        notifications.add(new Notification(key, errorClass, message));
        return NotifyOutcome.EMITTED;
    }
}
