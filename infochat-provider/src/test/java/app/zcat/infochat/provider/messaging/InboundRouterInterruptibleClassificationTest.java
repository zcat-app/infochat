package app.zcat.infochat.provider.messaging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pin {@link InboundRouter#isInterruptible} — the D35 dispatch
 * classification — as a table (M1-640). The load-bearing row is
 * {@code /retry --digest → false}: D61 excludes {@code /retry --digest}
 * from the M1-636 per-user concurrency cap, and that exclusion is sound
 * only while the command stays D35 <em>non</em>-interruptible, so it
 * dispatches inline on the single-threaded transport path and
 * self-serializes to one concurrent call per adapter identity. A future
 * refactor that moved {@code /retry --digest} onto the offloaded
 * worker/async path — or rewrote the flag match as a naive
 * {@code contains("--digest")} — would silently void that
 * self-serialization bound; this test turns that silent regression into
 * a red build. The classification IS the security boundary
 * ({@code docs/spec/security.md} §Rate limiting), which is why the pin
 * exists at all.
 *
 * <p>Plain JUnit (no Quarkus boot): {@code isInterruptible} is a
 * package-private pure static function of the step-1.7-normalized body,
 * so the table can call it directly — the same seam
 * {@link InboundRouterNormalizeTest} uses for {@code normalize}.</p>
 */
class InboundRouterInterruptibleClassificationTest {

    private record Case(String normalized, boolean expectedInterruptible, String why) {}

    /**
     * The D35 membership table. {@code /retry --digest} is the
     * non-interruptible row the D61 cap-exclusion rests on; {@code /retry}
     * (no flag), {@code /summary}, and a non-slash chat body are the
     * interruptible/offloaded class; an unknown slash command falls
     * through to non-interruptible (only {@code /summary} and flagless
     * {@code /retry} are offloaded — everything else dispatches inline).
     */
    @Test
    void pinsD35DispatchClassification() {
        List<Case> table = List.of(
                new Case("/retry --digest", false,
                        "D61 self-serialization precondition: --digest stays inline"),
                new Case("/retry", true,
                        "flagless /retry is offloaded to the worker seam"),
                new Case("/summary", true,
                        "/summary is offloaded to the worker seam"),
                new Case("/image -p a red bicycle", true,
                        "/image joins the D35 class: per-user ceiling and /stop apply (M1-803)"),
                new Case("what's new today?", true,
                        "non-slash chat body dispatches on the interruptible path"),
                new Case("/frobnicate", false,
                        "an unknown slash command is not in the offloaded set"));

        for (Case row : table) {
            assertEquals(row.expectedInterruptible(),
                    InboundRouter.isInterruptible(row.normalized()),
                    () -> "isInterruptible(\"" + row.normalized() + "\") — " + row.why());
        }
    }

    /**
     * The whitespace-token-equality edge the classification shares with
     * {@code RetryCommandHandler.hasFlag} (InboundRouter javadoc: "the
     * two classifications may not drift"). {@code --digest} is matched as
     * a whole {@code split("\\s+")} token, NOT as a substring: a token
     * that merely CONTAINS the flag text ({@code "--digestx"},
     * {@code "x--digest"}) is not the flag and the command stays
     * interruptible; the flag as any later whole token
     * ({@code "/retry foo --digest"}) is non-interruptible. These are the
     * rows a naive {@code contains("--digest")} rewrite would get wrong.
     */
    @Test
    void pinsWhitespaceTokenEqualityEdge() {
        List<Case> table = List.of(
                new Case("/retry --digestx", true,
                        "trailing-char token is not the flag → stays interruptible"),
                new Case("/retry x--digest", true,
                        "leading-char token is not the flag → stays interruptible"),
                new Case("/retry foo --digest", false,
                        "flag as a later whole token → non-interruptible"));

        for (Case row : table) {
            assertEquals(row.expectedInterruptible(),
                    InboundRouter.isInterruptible(row.normalized()),
                    () -> "isInterruptible(\"" + row.normalized() + "\") — " + row.why());
        }
    }
}
