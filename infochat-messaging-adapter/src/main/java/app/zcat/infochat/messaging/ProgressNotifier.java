package app.zcat.infochat.messaging;

/**
 * Cross-cutting stage-event notifier (decision D31,
 * {@code docs/spec/messaging.md} §Progress notifications). Long-running
 * handlers ({@code /summary}, periodic digest, chat agent) publish
 * stage events here; the implementation turns the stream into a single
 * visibly-evolving message by:
 * <ol>
 *   <li>Acquiring a placeholder via
 *       {@link MessagingAdapter#send} and capturing the
 *       {@link MessageHandle}.</li>
 *   <li>Coalescing pending edits and emitting them via
 *       {@link MessagingAdapter#update} at the next eligible tick,
 *       honoring {@code max(adapterMin, systemFloor)} for cadence.</li>
 *   <li>Calling {@link MessagingAdapter#finalize} on terminal
 *       {@link ProgressStage#COMPLETED} / {@link ProgressStage#FAILED}
 *       inside a try/finally so placeholders are never left dangling.</li>
 * </ol>
 *
 * <p>Implementation behaviors (edit coalescing, cadence enforcement,
 * try/finally finalize, typing-on / typing-off pulses, and the
 * deterministic-localization-bundle lookup that turns each
 * {@link ProgressStage} into a localized user-visible string) are
 * spec-mandated invariants the concrete notifier enforces — they are
 * NOT additional SPI methods. The interface exposes only
 * {@link #publish} so callers cannot bypass the invariants.</p>
 *
 * <p>Stage strings are template-parameterized only with
 * deterministic, sanitized scalar values (post counts,
 * controlled-vocabulary tag names, fixed enum labels). User-authored
 * text is NEVER interpolated — security requirement, prevents
 * reflective injection in screenshots and logs.</p>
 */
public interface ProgressNotifier {

    /**
     * Publish one stage event.
     *
     * @param scope the destination scope (DM contact id or group id);
     *              never null. The notifier maintains per-scope state
     *              (placeholder handle, last-edit timestamp).
     * @param stage the stage that has just been entered; never null.
     */
    void publish(String scope, ProgressStage stage);
}
