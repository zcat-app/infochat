package app.zcat.infochat.provider.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a parsed {@link Scenario} against a {@link ConversationBackend}: for each
 * step, send then await the expected reply within the step timeout, capturing the
 * per-step elapsed time. Cross-step data flow lives here, not in the backend:
 * {@code ${name}} placeholders in a send are substituted from earlier steps'
 * {@link Scenario.Capture} bindings before delivery (M1-545). Transport-agnostic —
 * it imports no adapter type — so the same runner drives InMemory in CI and a live
 * SimpleX transport in Phase 4b.
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
        Map<String, String> bindings = new HashMap<>();
        List<Scenario.Step> steps = scenario.steps();
        for (int index = 0; index < steps.size(); index++) {
            Scenario.Step step = steps.get(index);
            int stepNumber = index + 1;
            Scenario.Send resolved = resolvePlaceholders(step.send(), bindings, scenario.name(), stepNumber);
            long startNanos = System.nanoTime();
            backend.send(resolved);
            Optional<String> reply = backend.awaitReply(step.expect());
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            if (reply.isEmpty()) {
                throw new ScenarioFailedException(scenario.name(), stepNumber, step, latency);
            }
            applyCaptures(step, reply.get(), bindings, scenario.name(), stepNumber);
            results.add(new StepResult(stepNumber, step, reply.get(), latency));
        }
        return new RunReport(scenario.name(), List.copyOf(results));
    }

    /**
     * A {@code ${name}} reference in send text or an address token. The name
     * charset must stay in sync with {@link Scenario}'s capture-name validation,
     * or a bound name could be unreferenceable.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    /** A send with every {@code ${name}} placeholder replaced from {@code bindings}. */
    private Scenario.Send resolvePlaceholders(Scenario.Send send, Map<String, String> bindings,
            String scenarioName, int stepNumber) {
        List<String> addresses = new ArrayList<>(send.addresses().size());
        for (String address : send.addresses()) {
            addresses.add(substitute(address, bindings, scenarioName, stepNumber));
        }
        String text = substitute(send.text(), bindings, scenarioName, stepNumber);
        return new Scenario.Send(send.scope(), List.copyOf(addresses), text);
    }

    private String substitute(String input, Map<String, String> bindings,
            String scenarioName, int stepNumber) {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = bindings.get(name);
            if (value == null) {
                // Fail before the send: the raw ${...} text must never reach a transport.
                throw new ScenarioFailedException(scenarioName, stepNumber,
                        "send references unbound placeholder '${" + name + "}' — nothing was sent");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    /** Bind each of the step's captures from the matched reply; a no-match is a hard failure. */
    private void applyCaptures(Scenario.Step step, String matchedReply, Map<String, String> bindings,
            String scenarioName, int stepNumber) {
        for (Scenario.Capture capture : step.captures()) {
            Matcher matcher = capture.regex().matcher(matchedReply);
            if (!matcher.find()) {
                throw new ScenarioFailedException(scenarioName, stepNumber,
                        "capture '" + capture.name() + "' regex /" + capture.regex().pattern()
                                + "/ found no match in the matched reply");
            }
            bindings.put(capture.name(), matcher.group(1));
        }
    }

    /** One step's outcome: which step, the reply that matched, and how long the match took. */
    public record StepResult(int stepNumber, Scenario.Step step, String matchedReply, Duration latency) {}

    /** Every step result for one scenario run, in order. */
    public record RunReport(String scenarioName, List<StepResult> steps) {}

    /**
     * Thrown when a step's reply does not match within its timeout, or when its
     * cross-step data flow breaks (unbound placeholder, capture regex no-match).
     */
    public static final class ScenarioFailedException extends RuntimeException {

        public ScenarioFailedException(String scenarioName, int stepNumber, Scenario.Step step, Duration waited) {
            super("scenario '" + scenarioName + "' step " + stepNumber + " ("
                    + step.send().scope() + " \"" + step.send().text() + "\") got no reply matching "
                    + step.expect().kind() + " /" + step.expect().pattern() + "/ within "
                    + step.expect().timeout().toMillis() + "ms (waited " + waited.toMillis() + "ms)");
        }

        public ScenarioFailedException(String scenarioName, int stepNumber, String problem) {
            super("scenario '" + scenarioName + "' step " + stepNumber + ": " + problem);
        }
    }
}
