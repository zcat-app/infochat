package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.util.List;

/**
 * Programmable {@link DegradedDigestRenderer} stub: counts render
 * calls and returns the configured response.
 */
final class RecordingDegradedRenderer extends DegradedDigestRenderer {
    private String response = "default headlines";
    private int calls;

    void setResponse(String r) { this.response = r; }
    int callCount() { return calls; }

    @Override
    public String render(List<Post> posts) {
        calls++;
        return response;
    }
}
