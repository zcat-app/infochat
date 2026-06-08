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
 *   <li>Calling {@link MessagingAdapter#finalizeMessage} on terminal
 *       {@link ProgressStage#COMPLETED} / {@link ProgressStage#FAILED}
 *       inside a try/finally so placeholders are never left dangling.</li>
 * </ol>
 *
 * <p>Implementation behaviors (edit coalescing, cadence enforcement,
 * try/finally finalize, typing-on / typing-off pulses, and the
 * deterministic-localization-bundle lookup that turns each
 * {@link ProgressStage} into a localized user-visible string) are
 * spec-mandated invariants the concrete notifier enforces — they are
 * NOT additional SPI methods. The interface exposes {@link #publish}
 * for the five non-terminal stages plus the two payload-carrying
 * terminal calls {@link #complete} / {@link #fail}, so callers cannot
 * bypass the invariants.</p>
 *
 * <p><b>Why a payload-carrying terminal call.</b> {@link #publish}
 * renders the non-terminal stages ({@link ProgressStage#STARTED},
 * {@link ProgressStage#RETRIEVING}, {@link ProgressStage#GENERATING},
 * {@link ProgressStage#TRANSLATING}, {@link ProgressStage#FINALIZING})
 * onto the placeholder as coalesced {@code update}s. The terminal
 * message, however, is the actual operation output (e.g. the
 * {@code /summary} content) — which a stage label cannot carry. The
 * caller therefore signals terminal success via {@link #complete}
 * with the real final text and terminal failure via {@link #fail}
 * (which renders a localized failure string). Both turn typing off and
 * finalize the placeholder via try/finally so it is never left
 * dangling.</p>
 *
 * <p>Stage strings are template-parameterized only with
 * deterministic, sanitized scalar values (post counts,
 * controlled-vocabulary tag names, fixed enum labels). User-authored
 * text is NEVER interpolated — security requirement, prevents
 * reflective injection in screenshots and logs.</p>
 */
public interface ProgressNotifier {

    /**
     * Publish one non-terminal stage event
     * ({@link ProgressStage#STARTED}, {@link ProgressStage#RETRIEVING},
     * {@link ProgressStage#GENERATING}, {@link ProgressStage#TRANSLATING},
     * {@link ProgressStage#FINALIZING}). The first publish for a scope
     * acquires the placeholder and turns typing on; subsequent ones
     * render the stage string as a coalesced {@code update}. Terminal
     * stages are delivered via {@link #complete} / {@link #fail}, not
     * here.
     *
     * @param scope the destination scope; never null. The notifier
     *              maintains per-scope state (placeholder handle,
     *              last-edit timestamp).
     * @param stage the stage that has just been entered; never null.
     */
    void publish(ScopeRef scope, ProgressStage stage);

    /**
     * Terminal success. Finalizes the scope's placeholder with the
     * real operation output {@code finalText} and turns typing off,
     * both via try/finally so the placeholder is never left dangling.
     *
     * @param scope     the destination scope; never null.
     * @param finalText the final user-visible body — the actual
     *                  operation output, never a stage label; never null.
     */
    void complete(ScopeRef scope, String finalText);

    /**
     * Terminal failure. Finalizes the scope's placeholder with a
     * localized failure string (resolved from the deterministic
     * localization bundle, never interpolating user input) and turns
     * typing off, both via try/finally.
     *
     * @param scope the destination scope; never null.
     */
    void fail(ScopeRef scope);
}
