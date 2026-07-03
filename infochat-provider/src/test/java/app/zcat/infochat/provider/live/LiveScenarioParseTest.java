package app.zcat.infochat.provider.live;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic parse proof for the 7 transport-relevant live scenario resources
 * (M1-546): the gated live suite skips in CI, so this is what keeps a grammar
 * typo in a live resource from surviving until the next host run.
 */
class LiveScenarioParseTest {

    static final List<String> LIVE_SCENARIOS = List.of(
            "s03-invite-mint-consume.scenario",
            "s04-uninvited-dm-rejected.scenario",
            "s07-group-pending-approve-autopromote.scenario",
            "s10-summary-digest.scenario",
            "s11-zcash.scenario",
            "s12-chat-mode.scenario",
            "s15-happy-path.scenario");

    @Test
    void allSevenTransportRelevantScenarioResourcesParse() throws IOException {
        for (String resource : LIVE_SCENARIOS) {
            Scenario scenario = load(resource);
            assertFalse(scenario.steps().isEmpty(), resource + " parsed to zero steps");
        }
    }

    @Test
    void inviteCodeScenariosCaptureTheCrossStepCode() throws IOException {
        // S3 and S15 chain the minted invite code across steps (M1-545 capture).
        for (String resource : List.of("s03-invite-mint-consume.scenario",
                "s15-happy-path.scenario")) {
            Scenario scenario = load(resource);
            assertTrue(scenario.steps().stream().anyMatch(step -> !step.captures().isEmpty()),
                    resource + " must capture the minted invite code");
        }
    }

    static Scenario load(String resourceName) throws IOException {
        try (InputStream in = LiveScenarioParseTest.class
                .getResourceAsStream("/scenarios/live/" + resourceName)) {
            assertNotNull(in, "scenario resource not found: " + resourceName);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Scenario.parse(resourceName, text);
        }
    }
}
