package app.zcat.infochat.provider.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drives a parsed {@link Scenario} against a {@link ConversationBackend}: for each
 * step, send then await the expected reply within the step timeout, capturing the
 * per-step elapsed time. Transport-agnostic — it imports no adapter type — so the
 * same runner drives InMemory in CI and a live SimpleX transport in Phase 4b.
 *
 * <p>Elapsed time is measured with {@link System#nanoTime()}: it is test-scope
 * measurement, not decision logic, so the injected-{@code Clock} rule does not
 * apply (and a movable Clock is explicitly out of scope for M1-539). On the
 * InMemory backend the reply is already present when {@code awaitReply} is called,
 * so the captured latency is near-zero; the point proven here is that the capture
 * mechanism works. Real latency is a Phase-4b live-run assertion.
 */
public final class ScenarioRunner {

    private final ConversationBackend backend;

    public ScenarioRunner(ConversationBackend backend) {
        this.backend = backend;
    }

    /**
     * Run every step in order, returning a per-step report. Throws
     * {@link ScenarioFailedException} on the first step whose reply does not match
     * within its timeout — a non-match is a hard failure, never a skipped step.
     */
    public RunReport run(Scenario scenario) {
        List<StepResult> results = new ArrayList<>();
        List<Scenario.Step> steps = scenario.steps();
        for (int index = 0; index < steps.size(); index++) {
            Scenario.Step step = steps.get(index);
            int stepNumber = index + 1;
            long startNanos = System.nanoTime();
            backend.send(step.send());
            Optional<String> reply = backend.awaitReply(step.expect());
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            if (reply.isEmpty()) {
                throw new ScenarioFailedException(scenario.name(), stepNumber, step, latency);
            }
            results.add(new StepResult(stepNumber, step, reply.get(), latency));
        }
        return new RunReport(scenario.name(), List.copyOf(results));
    }

    /** One step's outcome: which step, the reply that matched, and how long the match took. */
    public record StepResult(int stepNumber, Scenario.Step step, String matchedReply, Duration latency) {}

    /** Every step result for one scenario run, in order. */
    public record RunReport(String scenarioName, List<StepResult> steps) {}

    /** Thrown when a step's reply does not match within its timeout. */
    public static final class ScenarioFailedException extends RuntimeException {

        public ScenarioFailedException(String scenarioName, int stepNumber, Scenario.Step step, Duration waited) {
            super("scenario '" + scenarioName + "' step " + stepNumber + " ("
                    + step.send().scope() + " \"" + step.send().text() + "\") got no reply matching "
                    + step.expect().kind() + " /" + step.expect().pattern() + "/ within "
                    + step.expect().timeout().toMillis() + "ms (waited " + waited.toMillis() + "ms)");
        }
    }
}
