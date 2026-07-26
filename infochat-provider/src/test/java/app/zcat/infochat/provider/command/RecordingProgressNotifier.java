package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link StageProgressNotifier} for handler-tier tests: captures
 * the published stages, the terminal {@code complete} payload, the
 * per-section {@code deliverFresh} sends (M1-695), and {@code fail}
 * invocations without touching any adapter. Lets a {@code /summary} test
 * observe the self-delivered terminal body (the handler returns
 * {@code null} on the terminal path) instead of a handler return value.
 *
 * <p>A subclass of the concrete notifier rather than an SPI
 * implementation because {@code SummaryCommandHandler} injects the
 * concrete type (M1-695: {@code deliverFresh} is a public non-SPI
 * terminal, same precedent as {@code completeDelivered}).</p>
 */
final class RecordingProgressNotifier extends StageProgressNotifier {

    private final List<ProgressStage> published = new ArrayList<>();
    private final List<String> freshSends = new ArrayList<>();
    private @Nullable String completedText;
    private int failCount;

    @Override
    public void publish(ScopeRef scope, ProgressStage stage) {
        published.add(stage);
    }

    @Override
    public void complete(ScopeRef scope, String finalText) {
        this.completedText = finalText;
    }

    @Override
    public boolean deliverFresh(ScopeRef scope, String text) {
        freshSends.add(text);
        return true;
    }

    @Override
    public void fail(ScopeRef scope) {
        this.failCount++;
    }

    List<ProgressStage> publishedStages() {
        return List.copyOf(published);
    }

    /** The argument to the last {@code complete} call, or null if none. */
    @Nullable String completedText() {
        return completedText;
    }

    /** Every {@code deliverFresh} payload, in send order. */
    List<String> freshSends() {
        return List.copyOf(freshSends);
    }

    int failCount() {
        return failCount;
    }
}
