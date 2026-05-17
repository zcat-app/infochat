package io.infochat.messaging;

/**
 * Checked exception raised by every {@link MessagingAdapter} method
 * that can fail at the transport layer ({@link MessagingAdapter#send},
 * {@link MessagingAdapter#update}, {@link MessagingAdapter#finalize}).
 * Per {@code docs/design/06-messaging.md} §6.2 and
 * {@code docs/spec/messaging.md} §Failure handling.
 *
 * <p>Every constructor REQUIRES the {@link FailureCategory} at throw
 * site — there is no zero-arg or category-less constructor. This
 * encodes the spec's "an adapter that cannot tell the two apart MUST
 * default to PERMANENT" as a forcing function rather than a downstream
 * re-derivation: the throwing code makes the choice. Callers
 * ({@link ProgressNotifier}, the outbound retry layer) branch on
 * {@link #category()} without heuristic re-classification.</p>
 */
public class MessagingException extends Exception {

    private final FailureCategory category;

    public MessagingException(FailureCategory category, String message) {
        super(message);
        this.category = category;
    }

    public MessagingException(FailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public FailureCategory category() {
        return category;
    }
}
