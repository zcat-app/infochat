package app.zcat.infochat.messaging.impl.signal;


import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;

/**
 * {@link app.zcat.infochat.messaging.impl.signal.SignalAdapter}-internal
 * per-handle state record. Per {@code docs/design/06-messaging.md} §6.1
 * each concrete adapter owns its handle carrier under its own
 * {@code impl/}/{@code signal/} package — the SPI's
 * {@link MessageHandle} record stays the opaque return type, and this
 * record holds the signal-cli-only state ({@code timestamp} echoed back
 * from the {@code send} response + the destination identifier) that the
 * adapter needs to apply subsequent {@code updateMessage} edits
 * deterministically.
 *
 * <p>The opacity invariant from {@code docs/spec/messaging.md}
 * §Message handles holds end-to-end: callers see only the opaque
 * {@link MessageHandle} returned by the adapter; this record never
 * escapes the {@code impl.signal} package (it is package-private). The
 * SignalJsonRpcClient maintains an internal map keyed by the SPI
 * handle's {@code opaqueValue} string to look this record up before
 * issuing the JSON-RPC {@code updateMessage} request.</p>
 *
 * @param timestamp the {@code timestamp} field signal-cli returns in
 *                  the send response; reused as
 *                  {@code targetSentTimestamp} on subsequent edits.
 * @param recipient the destination identifier (Signal ACI as
 *                  lowercase UUID, or E.164 phone) signal-cli requires
 *                  on every {@code updateMessage} request — the
 *                  protocol does not derive it from the timestamp.
 * @param original  the original outbound message — carries the scope and
 *                  the {@code correlationId} the edit-failure fresh-send
 *                  fallback reuses (design §6.5.7).
 * @param fellBack  true once an unrecoverable {@code editMessage} has
 *                  switched this handle to fresh-send fallback; every
 *                  subsequent update/finalize then fresh-sends without
 *                  re-attempting the doomed edit (design §6.3.8).
 */
record SignalMessageHandle(long timestamp, String recipient, OutboundMessage original, boolean fellBack) {

    /** A copy of this handle switched into fresh-send fallback mode. */
    SignalMessageHandle asFallenBack() {
        return new SignalMessageHandle(timestamp, recipient, original, true);
    }
}
