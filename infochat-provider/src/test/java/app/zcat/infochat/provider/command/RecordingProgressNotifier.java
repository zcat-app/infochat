package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ProgressNotifier;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link ProgressNotifier} for handler-tier tests: captures
 * the published stages, the terminal {@code complete} payload, and
 * {@code fail} invocations without touching any adapter. Lets a
 * {@code /summary} test observe the self-delivered terminal body (the
 * handler now returns {@code null} on the terminal path) instead of a
 * handler return value.
 */
final class RecordingProgressNotifier implements ProgressNotifier {

    private final List<ProgressStage> published = new ArrayList<>();
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

    int failCount() {
        return failCount;
    }
}
