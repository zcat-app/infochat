package app.zcat.infochat.provider.dev;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.messaging.InterruptibleDispatcher;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Dev-only terminal harness: a file-driven bridge that hand-drives the real
 * command/chat/group pipeline through the in-memory adapter, so a developer can
 * exercise a running app from a terminal (append directives to a file, tail the
 * replies) without a SimpleX/Signal account (M1-414).
 *
 * <p><b>Why a file, not HTTP.</b> The provider has no inbound HTTP surface by
 * design ("deaf to calls"; only loopback health probes, no REST dependency). An
 * HTTP harness would add a REST engine to the prod jar. This rides the existing
 * {@code quarkus-scheduler} instead: {@link #poll()} tails an input file and
 * writes captured replies to an output file — no listening socket, no new
 * dependency.
 *
 * <p><b>Why it can never ship in prod.</b> The harness injects inbound under an
 * arbitrary contact id, bypassing the adapter's cryptographic identity layer
 * (trust boundary 1) — though NOT the authorization logic: an injected message
 * still flows through the ban check, invite gate, probation, and per-(user,scope)
 * isolation. Two gates keep it out of production. The build-time gate is
 * load-bearing: this bean is excluded from any build that does not set
 * {@code infochat.dev.harness.enabled} ({@link IfBuildProperty},
 * {@code enableIfMissing=false}), so the production never-ships guarantee rests
 * on it alone. The runtime gate is defense in depth: the {@link InMemoryAdapter}
 * bean it injects always exists, but that adapter only has a wired
 * {@code InboundHandler} once {@code AdapterRegistry} activates it under
 * {@code infochat.adapters=inmemory} (decision D46) — without activation,
 * {@code deliverDm}/{@code deliverGroupMention} throw rather than inject, so the
 * harness cannot reach the pipeline unless the inmemory adapter is in force too.
 *
 * <p>Directive grammar (one per line in the input file):
 * <pre>
 *   dm &lt;contactId&gt; &lt;text...&gt;
 *   group &lt;groupId&gt; &lt;senderContactId&gt; &lt;text...&gt;
 * </pre>
 */
@IfBuildProperty(name = "infochat.dev.harness.enabled", stringValue = "true")
@ApplicationScoped
public class DevTerminalHarness {

    // Bound on how long one directive waits for its interruptible reply to
    // land before the harness gives up and writes whatever was captured.
    // Generous — the harness is a dev tool and this only guards against a
    // hung worker, never a tuning knob.
    private static final Duration INTERRUPTIBLE_CAPTURE_TIMEOUT = Duration.ofSeconds(30);

    private final InMemoryAdapter adapter;
    private final InterruptibleDispatcher interruptibleDispatcher;
    private final Path inputFile;
    private final Path outputFile;

    // Byte offset of the next unread line in the input file. Only the
    // single-threaded scheduler invokes poll(), so no synchronization is needed.
    private long inputOffset;

    DevTerminalHarness(
            InMemoryAdapter adapter,
            InterruptibleDispatcher interruptibleDispatcher,
            @ConfigProperty(name = "infochat.dev.harness.input-file",
                    defaultValue = "data/dev-harness-in.txt") String inputFile,
            @ConfigProperty(name = "infochat.dev.harness.output-file",
                    defaultValue = "data/dev-harness-out.txt") String outputFile) {
        this.adapter = adapter;
        this.interruptibleDispatcher = interruptibleDispatcher;
        this.inputFile = Path.of(inputFile);
        this.outputFile = Path.of(outputFile);
    }

    /**
     * Tail the input file and process every newly-appended complete line. The
     * interactive trigger; an integration test invokes this directly rather than
     * waiting on the timer, setting an arbitrarily large poll-interval to keep it
     * out of the way. That suppresses the periodic firings but NOT the one
     * Quarkus's {@code IntervalTrigger} performs at startup, so this method still
     * runs once before a test has written any directive (M1-679).
     */
    @Scheduled(every = "{infochat.dev.harness.poll-interval:1s}")
    void poll() {
        for (String line : readNewCompleteLines()) {
            String directive = line.strip();
            if (!directive.isEmpty()) {
                processDirective(directive);
            }
        }
    }

    /**
     * Return the lines appended since the last poll, holding back any trailing
     * partial line (one without a terminating newline) so a half-written append
     * is never parsed as a directive. A shrunk file (truncated/rotated) restarts
     * from the beginning.
     */
    private List<String> readNewCompleteLines() {
        if (!Files.exists(inputFile)) {
            return List.of();
        }
        byte[] all;
        try {
            all = Files.readAllBytes(inputFile);
        } catch (IOException e) {
            throw new UncheckedIOException("dev harness failed to read " + inputFile, e);
        }
        if (all.length < inputOffset) {
            inputOffset = 0;
        }
        String pending = new String(all, (int) inputOffset,
                all.length - (int) inputOffset, StandardCharsets.UTF_8);
        int lastNewline = pending.lastIndexOf('\n');
        if (lastNewline < 0) {
            return List.of();
        }
        String complete = pending.substring(0, lastNewline + 1);
        inputOffset += complete.getBytes(StandardCharsets.UTF_8).length;
        return List.of(complete.split("\n"));
    }

    private void processDirective(String directive) {
        String[] verbAndRest = directive.split("\\s+", 2);
        String rest = verbAndRest.length > 1 ? verbAndRest[1] : "";
        switch (verbAndRest[0]) {
            case "dm" -> {
                String[] args = rest.split("\\s+", 2);
                if (args.length < 2) {
                    writeReplies(List.of("ERROR: usage: dm <contactId> <text>"));
                    return;
                }
                injectAndCapture(() -> adapter.deliverDm(args[0], args[1]));
            }
            case "group" -> {
                String[] args = rest.split("\\s+", 3);
                if (args.length < 3) {
                    writeReplies(List.of(
                            "ERROR: usage: group <groupId> <senderContactId> <text>"));
                    return;
                }
                injectAndCapture(() ->
                        adapter.deliverGroupMention(args[0], args[1], args[2]));
            }
            default -> writeReplies(List.of("ERROR: unknown directive: " + verbAndRest[0]));
        }
    }

    /**
     * Run one injection and write the replies the adapter recorded for it. Both
     * sources are drained: {@code sentMessages()} for direct replies and
     * {@code finalizedBodies()} for replies delivered via the progress notifier
     * (e.g. {@code /summary}, which sends a placeholder then finalizes the real
     * summary in place). Cursoring on the pre-injection sizes isolates this
     * directive's replies from earlier ones in the same session.
     *
     * <p><b>Interruptible dispatch (M1-634).</b> The D35 interruptible class
     * (chat-mode, {@code /summary}, {@code /retry} re-roll) no longer delivers
     * inline: {@code onMessage} returns once the stage is handed to the
     * {@link InterruptibleDispatcher} worker pool, so the reply is not on the
     * adapter yet when this method resumes. Await pool quiescence before
     * capturing — without it the harness would silently drop every
     * interruptible directive's reply. The wait is bounded so a hung worker
     * cannot stall the scheduler thread forever; a non-interruptible directive
     * finds the pool already idle and does not wait.
     */
    private void injectAndCapture(Runnable injection) {
        int sentBefore = adapter.sentMessages().size();
        int finalizedBefore = adapter.finalizedBodies().size();
        injection.run();
        awaitInterruptibleDispatchIdle();
        List<OutboundMessage> sent = adapter.sentMessages();
        List<String> finalized = adapter.finalizedBodies();
        List<String> replies = new ArrayList<>();
        for (OutboundMessage message : sent.subList(sentBefore, sent.size())) {
            replies.add(message.text());
        }
        replies.addAll(finalized.subList(finalizedBefore, finalized.size()));
        writeReplies(replies);
    }

    /**
     * Block the scheduler thread until the interruptible worker pool is idle
     * (this directive's offloaded reply has been delivered), bounded by
     * {@link #INTERRUPTIBLE_CAPTURE_TIMEOUT}. Directives are processed one at a
     * time on the single scheduler thread and each awaits its own completion,
     * so a zero in-flight count means this directive's worker finished. Uses
     * {@code nanoTime} for the deadline — a mechanical timeout, not a business
     * decision gate, so the injected-Clock rule does not apply.
     */
    private void awaitInterruptibleDispatchIdle() {
        long deadlineNanos = System.nanoTime() + INTERRUPTIBLE_CAPTURE_TIMEOUT.toNanos();
        while (interruptibleDispatcher.inFlightTaskCount() != 0) {
            if (System.nanoTime() - deadlineNanos > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeReplies(List<String> replies) {
        if (replies.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        for (String reply : replies) {
            out.append(reply).append('\n');
        }
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("dev harness failed to write " + outputFile, e);
        }
    }
}
