package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderResult;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * Programmable {@link DigestRenderer} stub: counts render calls and
 * returns the configured response as a single section; can be made to
 * block so overlap scenarios can hold an execution mid-render.
 *
 * <p>Overrides {@link DigestRenderer#renderSections} — the entry point
 * {@link DigestWorker} calls — so the latch, the {@code calls}
 * counter, and the canned-response return all live there. The stub ignores
 * the {@code DigestMode} argument: worker tests pin orchestration, not
 * per-mode rendering (that is DigestRendererTest/SectionsTest's surface).
 * A single-section list makes the worker's {@code "\n\n"} join equal the
 * configured response verbatim (the property {@code DigestWorkerClockTest:86}
 * asserts).
 *
 * <p>The {@code groupId} argument (M1-756) is ignored for the same reason
 * as the mode: it selects the display-hit translation cache partition,
 * which is rendering, not orchestration.
 *
 * <p>Synthesis accounting (M1-912) defaults to 0/0 — nothing attempted —
 * which the worker's zero-prose rule reads as non-degraded.
 */
final class RecordingDigestRenderer extends DigestRenderer {
    private String response = "default prose";
    private int calls;
    private CountDownLatch entered;
    private CountDownLatch release;
    private List<RenderedSection> multiSections;
    private int synthesisTotal;
    private int synthesisDegraded;

    void setResponse(String r) { this.response = r; }
    int callCount() { return calls; }

    /** Configures the {@link RenderResult} synthesis counts (M1-912). */
    void setSynthesisAccounting(int total, int degraded) {
        this.synthesisTotal = total;
        this.synthesisDegraded = degraded;
    }

    /**
     * Override the default single-section response with a multi-section
     * list — used by the worker test that proves an N-section render
     * produces N sends through the chokepoint.
     */
    void setMultiSections(List<RenderedSection> sections) { this.multiSections = sections; }

    /** Make renderSections() signal entry then block until released. */
    void setBlocking(CountDownLatch entered, CountDownLatch release) {
        this.entered = entered;
        this.release = release;
    }

    @Override
    public RenderResult renderSections(List<Post> posts, String langCode,
                                        DigestMode mode, UUID groupId) {
        calls++;
        if (entered != null) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<RenderedSection> sections =
                multiSections != null ? multiSections : List.of(new RenderedSection(null, response));
        return new RenderResult(sections, synthesisTotal, synthesisDegraded);
    }
}
