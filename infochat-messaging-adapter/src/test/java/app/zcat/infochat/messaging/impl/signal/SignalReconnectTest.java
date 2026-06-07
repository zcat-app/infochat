package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Pins the M1-185 restart→reconnect contract for the Signal transport:
 * after the supervisor restarts the signal-cli subprocess, the adapter
 * reconnects its JSON-RPC client and traffic resumes; a send attempted
 * during the outage gap fails TRANSIENT (recoverable, Provider retries);
 * and an inbound frame pushed after the reconnect is delivered exactly
 * once (the old reader/dispatcher is torn down before the new
 * connection serves).
 *
 * <p>Wiring mirrors {@link SignalInboundDispatchTest}: production
 * adapter constructor pointed at {@link FakeSignalCli}, the
 * package-private {@code attachClient}/{@code attachSubprocess} seams
 * instead of {@code start()} (which needs a real signal-cli binary),
 * and a real {@link SignalSubprocess} over {@code /bin/sh}. The
 * supervised restart is driven through {@code restartHung()} — the
 * production force-kill path — so the death→onExit→doRestart→listener
 * sequence is the real one, not a simulation.</p>
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SignalReconnectTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(1);
    private static final String ACCOUNT = "+15551111111";
    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String PEER = "+15557654321";

    // Same aggressive curve as SignalSubprocessTest.FAST_BACKOFF — the
    // supervised respawn completes within tens of milliseconds instead
    // of the production 250 ms base.
    private static final SignalSubprocess.BackoffPolicy FAST_BACKOFF =
            new SignalSubprocess.BackoffPolicy(/* baseMs */ 10, /* factor */ 2.0, /* capMs */ 200);

    @Test
    void sendDuringOutageFailsTransient() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli", "/tmp/signal-data", ACCOUNT, BOT_ACI, fake.endpoint());
            SignalSubprocess sp = new SignalSubprocess(
                    new ProcessBuilder("/bin/sh", "-c", "sleep 30"),
                    fake.endpoint(), FAST_BACKOFF, /* maxRestarts */ 5);
            sp.start();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), ACCOUNT, new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, sp::restartHung);
            client.connect();
            try {
                adapter.attachSubprocess(sp);
                adapter.attachClient(client);
                // Sever the transport connection WITHOUT a subprocess
                // restart: the daemon connection is dead and no reconnect
                // is coming, so the send sits squarely in the outage gap.
                fake.killClientConnection();
                MessagingException failure = null;
                try {
                    adapter.send(outbound("during-outage"));
                } catch (MessagingException e) {
                    failure = e;
                }
                assertNotNull(failure, "send into a dead transport must fail");
                assertEquals(FailureCategory.TRANSIENT, failure.category(),
                        "outage-gap send must classify TRANSIENT so Provider's"
                                + " retry machinery treats the outage as recoverable");
            } finally {
                client.disconnect();
                sp.stop();
            }
        }
    }

    @Test
    void sendSucceedsAfterSupervisedRestart() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli", "/tmp/signal-data", ACCOUNT, BOT_ACI, fake.endpoint());
            SignalSubprocess sp = new SignalSubprocess(
                    new ProcessBuilder("/bin/sh", "-c", "sleep 30"),
                    fake.endpoint(), FAST_BACKOFF, /* maxRestarts */ 5);
            sp.start();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), ACCOUNT, new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, sp::restartHung);
            client.connect();
            Thread responder = startSendResponder(fake);
            try {
                adapter.attachSubprocess(sp);
                adapter.attachClient(client);
                int generationBeforeKill = fake.connectionGeneration();
                fake.killClientConnection();
                // Force the supervised restart through the production path:
                // SIGKILL → onExit → doRestart → restart listener →
                // adapter reconnect (endpoint probe + disconnect + connect).
                sp.restartHung();
                // The reconnect produces two new connections at the fake: the
                // awaitEndpoint probe (closed unread) and the JSON-RPC connect.
                fake.awaitConnectionGeneration(generationBeforeKill + 2, 10_000);
                MessageHandle handle = sendUntilSuccess(adapter, 10_000);
                assertNotNull(handle,
                        "send must succeed once the transport reconnected after"
                                + " the supervised restart");
            } finally {
                responder.interrupt();
                client.disconnect();
                sp.stop();
            }
        }
    }

    @Test
    void inboundDeliveredExactlyOnceAfterReconnect() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli", "/tmp/signal-data", ACCOUNT, BOT_ACI, fake.endpoint());
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            SignalSubprocess sp = new SignalSubprocess(
                    new ProcessBuilder("/bin/sh", "-c", "sleep 30"),
                    fake.endpoint(), FAST_BACKOFF, /* maxRestarts */ 5);
            sp.start();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), ACCOUNT, new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, sp::restartHung);
            client.connect();
            try {
                adapter.attachSubprocess(sp);
                adapter.attachClient(client);
                int generationBeforeKill = fake.connectionGeneration();
                fake.killClientConnection();
                sp.restartHung();
                fake.awaitConnectionGeneration(generationBeforeKill + 2, 10_000);
                fake.pushNotification("receive",
                        receiveParams("after-reconnect", 1700000099000L));
                InboundMessage first = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "inbound pushed after the reconnect must reach the handler");
                assertEquals("after-reconnect", first.text());
                // Exactly once: a half-dead prior reader/dispatcher would
                // surface a duplicate within this settle window.
                assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                        "inbound frame must be delivered exactly once across the reconnect");
            } finally {
                client.disconnect();
                sp.stop();
            }
        }
    }

    /**
     * Retry-send until success or deadline, asserting every interim
     * failure is TRANSIENT — the Provider-retry view of the outage. The
     * gap between the fake accepting the reconnect's socket and the
     * client wiring its writer/reader is microseconds wide but real;
     * polling absorbs it without weakening the category assertion.
     */
    private static MessageHandle sendUntilSuccess(SignalAdapter adapter, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        MessagingException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return adapter.send(outbound("post-restart"));
            } catch (MessagingException e) {
                assertEquals(FailureCategory.TRANSIENT, e.category(),
                        "failures while the reconnect is settling must stay TRANSIENT");
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("send did not succeed within " + timeoutMs
                + " ms of the supervised restart; last failure: " + last);
    }

    /**
     * Background acker: answer every outbound {@code send} request with a
     * success result so {@code adapter.send} calls can complete. Exits on
     * interrupt or when no request arrives for 15 s.
     */
    private static Thread startSendResponder(FakeSignalCli fake) {
        return Thread.ofVirtual().name("fake-signal-send-responder").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    JsonObject request = fake.nextOutbound(15_000);
                    if ("send".equals(request.getString("method"))) {
                        fake.respondSuccess(request.getString("id"), Json.createObjectBuilder()
                                .add("timestamp", 1700000050000L)
                                .add("results", Json.createArrayBuilder())
                                .build());
                    }
                }
            } catch (AssertionError | Exception e) {
                // nextOutbound timeout / interrupt / teardown IO — done.
            }
        });
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm(PEER), text, Instant.now(), "corr-" + text);
    }

    private static JsonObject receiveParams(String body, long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", PEER)
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("sourceName", "Alice")
                        .add("sourceDevice", 1)
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", body)))
                .build();
    }
}
