package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Aggregate detector for a wholly non-functioning tagger stage
 * (M1-735; the M1-726 round-1 LOW red-team finding, AUDIT-EVASION).
 *
 * <p>M1-726 made a clean {@code {"tags":[]}} a terminal per-post
 * success — no retry, no bootstrap fallback, no per-post notification.
 * That is the correct disposition for ONE genuinely untaggable post,
 * but it left a tagger that answers empty to EVERY post emitting zero
 * operational signal: no {@code tagger_fallback} marker, no WARN, no
 * notifier call — and the invalid-rate observability cited in
 * {@code docs/spec/llm.md} §Failure handling cannot fire either,
 * because the all-empty case reports N=0 valid AND M=0 invalid on
 * every post. Per {@code docs/spec/security.md} §Trust boundaries
 * item 9 a hostile or compromised endpoint is in scope, and an
 * operator model swap to a regressed model produces the identical
 * reply.</p>
 *
 * <p>The distinguishing datum is the RATE, not the post: normal
 * operation produces a trickle of no-tags outcomes (birthday wishes,
 * cat videos — M1-726's motivating cases); a dead tagger produces
 * ~100%. This monitor keeps an in-memory sliding window over the
 * tagger's recent completions and fires {@link ThrottledAdminNotifier}
 * with the DISTINCT error class {@link #ERROR_CLASS_SUSTAINED_NO_TAGS}
 * when the no-tags share in the window exceeds the configured
 * threshold over a configured minimum sample. The notifier's per-key
 * coalescing supplies the throttle semantics, so a sustained condition
 * alarms once per cooldown, not per post.</p>
 *
 * <h2>Window semantics</h2>
 *
 * <ul>
 *   <li><b>Cold start is silent.</b> Below
 *       {@code infochat.llm.tagger.no-tags-alert.min-sample} recorded
 *       completions the window never fires, even at 100% no-tags — a
 *       fresh collector tagging its first handful of posts cannot
 *       false-alarm.</li>
 *   <li><b>Only the share fires it.</b> Once the sample floor is met,
 *       the alert fires when {@code noTags / windowContents} strictly
 *       exceeds {@code infochat.llm.tagger.no-tags-alert.threshold};
 *       a normal trickle stays far below.</li>
 *   <li><b>Restart-blindness is bounded.</b> The window is in-memory
 *       (the collector is a single instance per deployment), so a
 *       restart resets it; a genuinely sustained condition re-fires
 *       after the minimum sample refills — the blind period is bounded
 *       by the sample floor, not the window size.</li>
 * </ul>
 *
 * <p>A bootstrap-fallback completion counts as NOT no-tags: that path
 * already alarms under {@code tagger.fallback_to_bootstrap}, and the
 * two error classes carry different meanings and different operator
 * runbooks. Parameter values are recorded in
 * {@code docs/design/05-llm-and-embeddings.md} §5.4.2.
 */
@ApplicationScoped
public class NoTagsRateMonitor {

    /**
     * Canonical error class emitted when the no-tags share of recent
     * tagger completions exceeds the configured threshold. DISTINCT
     * from {@link TaggerWorker#ERROR_CLASS_TAGGER_FALLBACK}: the
     * bootstrap fallback means "the tagger failed on individual posts",
     * this class means "the tagger stage is answering empty to
     * (nearly) everything" — different runbooks.
     */
    public static final String ERROR_CLASS_SUSTAINED_NO_TAGS = "tagger.sustained_no_tags";

    @ConfigProperty(name = "infochat.llm.tagger.no-tags-alert.window-size", defaultValue = "50")
    int windowSize;

    @ConfigProperty(name = "infochat.llm.tagger.no-tags-alert.min-sample", defaultValue = "20")
    int minSample;

    @ConfigProperty(name = "infochat.llm.tagger.no-tags-alert.threshold", defaultValue = "0.9")
    double threshold;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    /**
     * The sliding window: one entry per recorded completion,
     * {@code true} for a no-tags outcome. Bounded at
     * {@link #windowSize}; the running {@link #noTagsCount} avoids a
     * re-scan per record.
     */
    private final Deque<Boolean> window = new ArrayDeque<>();
    private int noTagsCount;

    @PostConstruct
    void init() {
        if (windowSize < 1) {
            throw new IllegalStateException(
                "NoTagsRateMonitor: infochat.llm.tagger.no-tags-alert.window-size must be >= 1; got "
                    + windowSize);
        }
        if (minSample < 1 || minSample > windowSize) {
            throw new IllegalStateException(
                "NoTagsRateMonitor: infochat.llm.tagger.no-tags-alert.min-sample must be in "
                    + "[1, window-size=" + windowSize + "]; got " + minSample);
        }
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalStateException(
                "NoTagsRateMonitor: infochat.llm.tagger.no-tags-alert.threshold must be in (0, 1]; got "
                    + threshold);
        }
    }

    /**
     * Record one completed tagger outcome. {@code noTags} is true only
     * for the LLM-answered empty proposal ({@code tags='{}'} with no
     * fallback); every other completion — tagged, or the bootstrap
     * fallback — counts as a non-no-tags sample. Fires the throttled
     * alert when the window's no-tags share exceeds the threshold over
     * the minimum sample. Synchronized so a concurrent caller (the
     * scheduler is serial per the SKIP policy, but the monitor must
     * not depend on its caller's threading) cannot interleave window
     * mutation with the share computation.
     */
    public synchronized void record(boolean noTags) {
        window.addLast(noTags);
        if (noTags) {
            noTagsCount++;
        }
        while (window.size() > windowSize) {
            if (Boolean.TRUE.equals(window.removeFirst())) {
                noTagsCount--;
            }
        }
        int sample = window.size();
        if (sample < minSample) {
            return;
        }
        double share = (double) noTagsCount / sample;
        if (share > threshold) {
            // notifyOnce supplies the throttle: per-key coalescing
            // emits one ADMIN-NOTIFY per cooldown window and counts
            // the rest as suppressed, so a sustained condition alarms
            // once per cooldown rather than once per post.
            throttledAdminNotifier.notifyOnce(
                ERROR_CLASS_SUSTAINED_NO_TAGS,
                ERROR_CLASS_SUSTAINED_NO_TAGS,
                "Tagger no-tags share " + noTagsCount + "/" + sample
                    + " exceeds threshold " + threshold
                    + " over the recent completion window — the tagger stage may be dead, "
                    + "degraded, or serving a regressed model");
        }
    }

    /**
     * Test seam: the window is in-memory and the CDI bean is shared
     * across the whole Quarkus test instance, so a per-test slate
     * needs an explicit reset — the same role
     * {@code StubLlmProvider#reset()} plays for the stubbed LLM.
     */
    synchronized void reset() {
        window.clear();
        noTagsCount = 0;
    }
}
