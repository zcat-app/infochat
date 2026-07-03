package app.zcat.infochat.provider.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A declarative conversation scenario: an ordered list of {@link Step}s, each a
 * send paired with an expectation over the bot's reply. Parsed from a plain-text
 * resource so an operator can edit it without recompiling, and it carries no
 * adapter or messaging type — so the SAME scenario drives any
 * {@link ConversationBackend} (InMemory here in CI, a real SimpleX transport in
 * Phase 4b). (M1-539)
 *
 * <p>Grammar (one directive per line; blank lines and {@code #} comments ignored):
 * <pre>
 *   send dm    &lt;contactId&gt; &lt;text...&gt;
 *   send group &lt;groupId&gt; &lt;senderContactId&gt; &lt;text...&gt;
 *   expect substring &lt;timeoutMs&gt; &lt;literal text...&gt;
 *   expect regex     &lt;timeoutMs&gt; &lt;java regex...&gt;
 *   capture &lt;name&gt; &lt;java regex with a capturing group...&gt;
 * </pre>
 * Each {@code send} must be immediately followed by its {@code expect}; the pair
 * forms one {@link Step}. Zero or more {@code capture} directives may follow an
 * {@code expect}: each applies its regex to that step's MATCHED reply at run time
 * and binds the first capturing group to {@code name}, for substitution into later
 * sends' {@code ${name}} placeholders (text and address tokens; later captures may
 * shadow earlier ones). This gives scenarios cross-step data flow — e.g. an invite
 * code minted in one reply and consumed in a later send (M1-545).
 */
public record Scenario(String name, List<Step> steps) {

    /** Conversation scope of a send. Scope-specific routing tokens live in {@link Send#addresses()}. */
    public enum Scope {
        /** Direct message; one address token: the contact id. */
        DM(1),
        /** Group mention; two address tokens: the group id and the sender's contact id. */
        GROUP(2);

        /** How many routing tokens the grammar carries for this scope before the message text. */
        final int addressArity;

        Scope(int addressArity) {
            this.addressArity = addressArity;
        }
    }

    /** How a reply is matched against an {@link Expect#pattern()}. */
    public enum MatchKind { SUBSTRING, REGEX }

    /**
     * One send plus the expectation its reply must satisfy within the step timeout,
     * and the captures (possibly none) to bind from the matched reply.
     */
    public record Step(Send send, Expect expect, List<Capture> captures) {

        /** A step with no captures — the shape every pre-M1-545 scenario has. */
        public Step(Send send, Expect expect) {
            this(send, expect, List.of());
        }
    }

    /**
     * A message to deliver. {@code addresses} carries the scope-specific routing
     * tokens as raw strings (DM: {@code [contactId]}; GROUP:
     * {@code [groupId, senderContactId]}). The backend binding interprets them,
     * which is what keeps this core type free of any adapter identity type.
     */
    public record Send(Scope scope, List<String> addresses, String text) {}

    /**
     * A run-time binding: {@code regex} is applied to the owning step's matched
     * reply and its first capturing group is bound to {@code name}. Validated at
     * parse time to compile and carry at least one capturing group.
     */
    public record Capture(String name, Pattern regex) {}

    /** A reply predicate plus the per-step timeout the runner waits for it. */
    public record Expect(MatchKind kind, String pattern, Duration timeout) {

        /** Whether {@code reply} satisfies this expectation (substring containment or regex find). */
        public boolean matches(String reply) {
            return switch (kind) {
                case SUBSTRING -> reply.contains(pattern);
                case REGEX -> Pattern.compile(pattern).matcher(reply).find();
            };
        }
    }

    /**
     * Parse the line grammar above into an ordered scenario. Throws
     * {@link IllegalArgumentException} on any malformed line so a bad scenario
     * file fails loudly at load time rather than mis-running.
     */
    public static Scenario parse(String name, String text) {
        List<Step> steps = new ArrayList<>();
        Send pendingSend = null;
        int lineNumber = 0;
        for (String rawLine : text.split("\n", -1)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] head = line.split("\\s+", 2);
            String directive = head[0];
            String remainder = head.length > 1 ? head[1] : "";
            switch (directive) {
                case "send" -> {
                    if (pendingSend != null) {
                        throw new IllegalArgumentException(
                                "line " + lineNumber + ": 'send' before the prior send's 'expect'");
                    }
                    pendingSend = parseSend(remainder, lineNumber);
                }
                case "expect" -> {
                    if (pendingSend == null) {
                        throw new IllegalArgumentException(
                                "line " + lineNumber + ": 'expect' with no preceding 'send'");
                    }
                    steps.add(new Step(pendingSend, parseExpect(remainder, lineNumber)));
                    pendingSend = null;
                }
                case "capture" -> {
                    if (pendingSend != null) {
                        throw new IllegalArgumentException(
                                "line " + lineNumber + ": 'capture' between a 'send' and its 'expect'");
                    }
                    if (steps.isEmpty()) {
                        throw new IllegalArgumentException(
                                "line " + lineNumber + ": 'capture' with no preceding step");
                    }
                    // Attach to the step the preceding expect just completed.
                    Step last = steps.get(steps.size() - 1);
                    List<Capture> captures = new ArrayList<>(last.captures());
                    captures.add(parseCapture(remainder, lineNumber));
                    steps.set(steps.size() - 1, new Step(last.send(), last.expect(), List.copyOf(captures)));
                }
                default -> throw new IllegalArgumentException(
                        "line " + lineNumber + ": unknown directive '" + directive + "'");
            }
        }
        if (pendingSend != null) {
            throw new IllegalArgumentException("scenario '" + name + "': trailing 'send' with no 'expect'");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("scenario '" + name + "': no steps");
        }
        return new Scenario(name, List.copyOf(steps));
    }

    private static Send parseSend(String remainder, int lineNumber) {
        String[] scopeSplit = remainder.split("\\s+", 2);
        Scope scope = switch (scopeSplit[0]) {
            case "dm" -> Scope.DM;
            case "group" -> Scope.GROUP;
            default -> throw new IllegalArgumentException(
                    "line " + lineNumber + ": unknown scope '" + scopeSplit[0] + "' (expected dm|group)");
        };
        String afterScope = scopeSplit.length > 1 ? scopeSplit[1] : "";
        // Peel off exactly addressArity routing tokens, then the rest is the message text.
        String[] parts = afterScope.split("\\s+", scope.addressArity + 1);
        if (parts.length < scope.addressArity + 1) {
            throw new IllegalArgumentException("line " + lineNumber + ": scope " + scope
                    + " needs " + scope.addressArity + " address token(s) then message text");
        }
        List<String> addresses = new ArrayList<>(scope.addressArity);
        for (int i = 0; i < scope.addressArity; i++) {
            addresses.add(parts[i]);
        }
        return new Send(scope, List.copyOf(addresses), parts[scope.addressArity]);
    }

    private static Capture parseCapture(String remainder, int lineNumber) {
        String[] parts = remainder.split("\\s+", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": capture needs <name> <regex with a capturing group>");
        }
        String name = parts[0];
        // The name charset must stay in sync with ScenarioRunner's ${name}
        // placeholder pattern, or a bound name could be unreferenceable.
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("line " + lineNumber + ": capture name '" + name
                    + "' must match [A-Za-z][A-Za-z0-9_]*");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(parts[1]);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": capture regex does not compile: " + e.getMessage(), e);
        }
        if (regex.matcher("").groupCount() < 1) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": capture regex has no capturing group to bind");
        }
        return new Capture(name, regex);
    }

    private static Expect parseExpect(String remainder, int lineNumber) {
        String[] parts = remainder.split("\\s+", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": expect needs <substring|regex> <timeoutMs> <pattern>");
        }
        MatchKind kind = switch (parts[0]) {
            case "substring" -> MatchKind.SUBSTRING;
            case "regex" -> MatchKind.REGEX;
            default -> throw new IllegalArgumentException(
                    "line " + lineNumber + ": unknown match kind '" + parts[0] + "' (expected substring|regex)");
        };
        long timeoutMillis;
        try {
            timeoutMillis = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": timeout '" + parts[1] + "' is not an integer (ms)", e);
        }
        return new Expect(kind, parts[2], Duration.ofMillis(timeoutMillis));
    }
}
