package app.zcat.infochat.llm.impl;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local SSE mock endpoint shared by the streaming provider tests —
 * the repo's provider-test posture (JDK {@code HttpServer}, no Quarkus
 * boot, no WireMock), extended with the control the streaming failure
 * modes need: {@link #SseMockServer(long, BodyWriter) a declared
 * response length the writer undercuts} forces the abrupt mid-body
 * drop a clean server close cannot produce.
 */
final class SseMockServer implements AutoCloseable {

    @FunctionalInterface
    interface BodyWriter {
        void write(OutputStream stream) throws Exception;
    }

    private final HttpServer server;
    private final String baseUrl;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    SseMockServer(BodyWriter writer) throws IOException {
        this(200, 0, writer);
    }

    /**
     * @param status the HTTP status the response carries (a non-2xx
     *        exercises the pre-body status rejection).
     * @param declaredResponseLength when positive, the Content-Length
     *         the response headers declare; a writer that outputs fewer
     *         bytes and closes produces the premature end-of-body the
     *         transport-class mid-stream failure tests need. Zero means
     *         chunked (stream as you write).
     */
    SseMockServer(int status, long declaredResponseLength, BodyWriter writer) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            receivedBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, status == 200 ? declaredResponseLength : -1);
            try (OutputStream os = exchange.getResponseBody()) {
                try {
                    writer.write(os);
                    os.flush();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    String baseUrl() {
        return baseUrl;
    }

    List<String> receivedBodies() {
        return receivedBodies;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** One SSE frame per payload, blank-line terminated. */
    static byte[] sseFrames(String... dataPayloads) {
        StringBuilder out = new StringBuilder();
        for (String payload : dataPayloads) {
            out.append("data: ").append(payload).append("\n\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
