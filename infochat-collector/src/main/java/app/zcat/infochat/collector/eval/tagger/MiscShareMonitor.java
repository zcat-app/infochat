package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayDeque;
import java.util.Deque;

/** Sliding-window monitor for the Others-top 'misc' leaf's share of tagger completions (M1-865, decision 5, analysis P16) — {@link NoTagsRateMonitor}'s M1-735 shape (min sample, strict exceed, notifier coalescing as throttle, cold-start silence, restart-blindness bounded by the sample floor) under a DISTINCT error class: a rising misc share means the leaf list no longer covers the content mix, a different runbook from a dead tagger or per-post failures. Bootstrap-fallback completions count as not-misc. Parameters: docs/design/05-llm-and-embeddings.md §5.4.2. */
@ApplicationScoped
public class MiscShareMonitor {

    /** The Others-top leaf whose share this monitor watches. */
    public static final String MISC_LEAF = "misc";

    /** Canonical error class for a sustained misc share — never tagger.sustained_no_tags, never tagger.fallback_to_bootstrap. */
    public static final String ERROR_CLASS_SUSTAINED_MISC_SHARE = "tagger.sustained_misc_share";

    @ConfigProperty(name = "infochat.tagger.misc-share-window-size", defaultValue = "50")
    int windowSize;

    @ConfigProperty(name = "infochat.tagger.misc-share-min-sample", defaultValue = "20")
    int minSample;

    @ConfigProperty(name = "infochat.tagger.misc-share-threshold", defaultValue = "0.10")
    double threshold;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    /** One entry per recorded completion, {@code true} for a resolved-misc outcome; bounded at {@link #windowSize}, running count avoids a re-scan. */
    private final Deque<Boolean> window = new ArrayDeque<>();
    private int miscCount;

    @PostConstruct
    void init() {
        if (windowSize < 1) {
            throw new IllegalStateException(
                "MiscShareMonitor: infochat.tagger.misc-share-window-size must be >= 1; got "
                    + windowSize);
        }
        if (minSample < 1 || minSample > windowSize) {
            throw new IllegalStateException(
                "MiscShareMonitor: infochat.tagger.misc-share-min-sample must be in "
                    + "[1, window-size=" + windowSize + "]; got " + minSample);
        }
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalStateException(
                "MiscShareMonitor: infochat.tagger.misc-share-threshold must be in (0, 1]; got "
                    + threshold);
        }
    }

    /** Record one completed outcome; {@code misc} only when the resolved stored leaf is {@value #MISC_LEAF} (fallbacks and no-tags count as not-misc). Synchronized — the monitor must not depend on its caller's threading. */
    public synchronized void record(boolean misc) {
        window.addLast(misc);
        if (misc) {
            miscCount++;
        }
        while (window.size() > windowSize) {
            if (Boolean.TRUE.equals(window.removeFirst())) {
                miscCount--;
            }
        }
        int sample = window.size();
        if (sample < minSample) {
            return;
        }
        double share = (double) miscCount / sample;
        if (share > threshold) {
            // notifyOnce supplies the throttle: per-key coalescing emits
            // one ADMIN-NOTIFY per cooldown window, so a sustained
            // condition alarms once per cooldown rather than once per post.
            throttledAdminNotifier.notifyOnce(
                ERROR_CLASS_SUSTAINED_MISC_SHARE,
                ERROR_CLASS_SUSTAINED_MISC_SHARE,
                "Tagger misc share " + miscCount + "/" + sample
                    + " exceeds threshold " + threshold
                    + " over the recent completion window — the leaf list may no longer"
                    + " cover the content mix; consider growing the tree");
        }
    }

    /** Test seam: reset the shared in-memory window — the same role {@code StubLlmProvider#reset()} plays for the stubbed LLM. */
    synchronized void reset() {
        window.clear();
        miscCount = 0;
    }
}
