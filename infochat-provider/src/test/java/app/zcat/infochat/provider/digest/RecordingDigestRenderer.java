package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Programmable {@link DigestRenderer} stub: counts render calls and
 * returns the configured response as a single section; can be made to
 * block so overlap scenarios can hold an execution mid-render.
 *
 * <p>Overrides {@link DigestRenderer#renderSections} — the entry point
 * {@link DigestWorker} now calls — so the latch, the {@code calls}
 * counter, and the canned-response return all live there. The inherited
 * {@link DigestRenderer#render} is a thin {@code "\n\n"} join over
 * {@code renderSections()}, so calls to either entry point increment the
 * counter and a single-section list makes the join equal the configured
 * response verbatim (the property {@code DigestWorkerClockTest:86}
 * asserts).
 */
final class RecordingDigestRenderer extends DigestRenderer {
    private String response = "default prose";
    private int calls;
    private CountDownLatch entered;
    private CountDownLatch release;
    private List<RenderedSection> multiSections;

    void setResponse(String r) { this.response = r; }
    int callCount() { return calls; }

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
    public List<RenderedSection> renderSections(List<Post> posts, String langCode) {
        calls++;
        if (entered != null) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return multiSections != null ? multiSections : List.of(new RenderedSection(null, response));
    }
}
