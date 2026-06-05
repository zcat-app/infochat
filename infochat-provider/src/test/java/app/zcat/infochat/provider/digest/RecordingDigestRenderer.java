package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Programmable {@link DigestRenderer} stub: counts render calls and
 * returns the configured response; can be made to block so overlap
 * scenarios can hold an execution mid-render.
 */
final class RecordingDigestRenderer extends DigestRenderer {
    private String response = "default prose";
    private int calls;
    private CountDownLatch entered;
    private CountDownLatch release;

    void setResponse(String r) { this.response = r; }
    int callCount() { return calls; }

    /** Make render() signal entry then block until released. */
    void setBlocking(CountDownLatch entered, CountDownLatch release) {
        this.entered = entered;
        this.release = release;
    }

    @Override
    public String render(List<Post> posts, String langCode) {
        calls++;
        if (entered != null) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return response;
    }
}
