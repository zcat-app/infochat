package app.zcat.infochat.messaging.impl.signal;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Top-level package-private test double for signal-cli's TCP JSON-RPC
 * daemon. Binds an ephemeral local port, accepts one client connection
 * from {@link SignalJsonRpcClient}, captures outbound request lines on
 * a queue the test driver polls, and emits canned responses /
 * notifications back to the client. Not extracted as an inner class
 * per the project's avoid-inner-class-fakes rule.
 *
 * <p>Wire format matches signal-cli: line-delimited JSON envelopes,
 * one per {@code "\n"}. No backpressure, no flow control — tests stay
 * within a handful of requests per case.</p>
 */
final class FakeSignalCli implements AutoCloseable {

    private final ServerSocket server;
    private final BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
    private final Thread acceptThread;
    private final Object writeLock = new Object();

    private volatile Socket clientConn;
    private volatile BufferedWriter clientWriter;

    FakeSignalCli() throws IOException {
        this.server = new ServerSocket();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.acceptThread = new Thread(this::acceptLoop, "fake-signal-cli-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    InetSocketAddress endpoint() {
        return new InetSocketAddress("127.0.0.1", server.getLocalPort());
    }

    /** Block up to {@code timeoutMs} for the next outbound JSON-RPC line. */
    JsonObject nextOutbound(long timeoutMs) throws InterruptedException {
        JsonObject msg = received.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (msg == null) {
            throw new AssertionError(
                    "FakeSignalCli received no outbound JSON-RPC within " + timeoutMs + " ms");
        }
        return msg;
    }

    void respondSuccess(String requestId, JsonObject result)
            throws IOException, InterruptedException {
        sendLine(Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", requestId)
                .add("result", result)
                .build()
                .toString());
    }

    void respondError(String requestId, int code, String message)
            throws IOException, InterruptedException {
        sendLine(Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", requestId)
                .add("error", Json.createObjectBuilder()
                        .add("code", code)
                        .add("message", message))
                .build()
                .toString());
    }

    void pushNotification(String method, JsonObject params)
            throws IOException, InterruptedException {
        sendLine(Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("method", method)
                .add("params", params)
                .build()
                .toString());
    }

    /**
     * Emit a raw line verbatim, bypassing the JSON envelope builder.
     * Used by the inbound-size-cap regression test to feed an
     * oversize line and verify SignalJsonRpcClient's reader survives
     * and continues processing subsequent normal lines.
     */
    void pushRawLine(String raw) throws IOException, InterruptedException {
        sendLine(raw);
    }

    @Override
    public void close() throws IOException {
        Socket c = clientConn;
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore close-after-close
            }
        }
        server.close();
    }

    private void acceptLoop() {
        try {
            Socket s = server.accept();
            this.clientConn = s;
            this.clientWriter = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> readLoop(s), "fake-signal-cli-read");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            // Server closed; expected during teardown.
        }
    }

    private void readLoop(Socket s) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try (JsonReader jr = Json.createReader(new StringReader(line))) {
                    received.add(jr.readObject());
                }
            }
        } catch (IOException e) {
            // Connection closed; expected during teardown.
        }
    }

    private void sendLine(String line) throws IOException, InterruptedException {
        BufferedWriter w = awaitWriter();
        synchronized (writeLock) {
            w.write(line);
            w.write('\n');
            w.flush();
        }
    }

    private BufferedWriter awaitWriter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (clientWriter == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        BufferedWriter w = clientWriter;
        if (w == null) {
            throw new AssertionError("FakeSignalCli has no client connection");
        }
        return w;
    }
}
