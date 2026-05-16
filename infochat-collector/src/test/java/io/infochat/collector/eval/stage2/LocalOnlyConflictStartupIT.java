package io.infochat.collector.eval.stage2;

import io.infochat.llm.routing.LlmRouterStartupGuard;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the local-only conflict detection from M1-033 acceptance
 * items 8 / 9 / 10. When {@code infochat.llm.local-only=true} is
 * set alongside any per-task {@code base-url} pointing at a
 * non-loopback host (e.g. {@code https://api.openai.com/v1}), the
 * {@link LlmRouterStartupGuard} must REFUSE Collector startup with
 * a fatal log line naming the offending {@code SECURITY_JUDGE} task
 * and the offending base-url.
 *
 * <h2>Test mechanism (package-private validator call)</h2>
 * <p>Per M1-033 ticket Risk #3 / Implementation notes option (b):
 * this IT calls the guard's package-private
 * {@link LlmRouterStartupGuard#validateLocalOnlyConfiguration(Map)}
 * with a hand-rolled config snapshot. The alternative — booting a
 * separate Quarkus instance under a @TestProfile that sets the
 * conflict and asserting boot failure — would require either a
 * second Quarkus context per @Test method (heavy) or a
 * @QuarkusTestResource that wraps Quarkus.run (fragile across
 * Quarkus minor versions). The direct-call shape exercises the
 * exact same pure-function validator the production @Startup path
 * invokes; the @QuarkusTest annotation here keeps the test in the
 * same Failsafe execution pool as the other Stage 2 ITs and
 * confirms the guard bean's classpath wiring is intact for the
 * happy path.
 *
 * <h2>Happy-path coverage</h2>
 * <p>The class boots under the default test profile (local-only
 * implicit-false from {@code infochat.llm.local-only=false} at the
 * base level), which exercises the guard's "no conflict, no
 * throw" path implicitly: Collector startup would refuse and this
 * test would never reach @Test if the guard misfired on a clean
 * config.
 */
@QuarkusTest
class LocalOnlyConflictStartupIT {

    /**
     * The single @Test for this IT. Exercises three scenarios via
     * helper invocations (Quarkus boot already verified the
     * happy-path "no conflict" branch by the time this method
     * runs):
     *
     * <ol>
     *   <li>Local-only=true + non-loopback SECURITY_JUDGE base-url
     *       → throws LocalOnlyConflictException whose message names
     *       both the task (SECURITY_JUDGE) and the offending
     *       base-url.</li>
     *   <li>Local-only=true + loopback SECURITY_JUDGE base-url
     *       → does NOT throw (e.g. {@code http://localhost:11434/v1}).</li>
     *   <li>Local-only=false + non-loopback SECURITY_JUDGE base-url
     *       → does NOT throw (the flag is the master switch; without
     *       it the guard is a no-op).</li>
     * </ol>
     */
    @Test
    void localOnlyTrueWithRemoteSecurityJudgeBaseUrlRefusesStartup() {
        // 1. Non-loopback under local-only=true → throw.
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put("infochat.llm.security.base-url", "https://api.openai.com/v1");

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "non-loopback base-url under local-only=true must throw");
        String msg = ex.getMessage();
        assertTrue(msg.contains("SECURITY_JUDGE"),
            "fatal message must name the SECURITY_JUDGE task; got: " + msg);
        assertTrue(msg.contains("https://api.openai.com/v1"),
            "fatal message must name the offending base-url; got: " + msg);
        assertTrue(msg.contains("local-only"),
            "fatal message must mention local-only; got: " + msg);

        // 2. Loopback under local-only=true → no throw.
        Map<String, String> okLocal = new LinkedHashMap<>();
        okLocal.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        okLocal.put("infochat.llm.security.base-url", "http://localhost:11434/v1");
        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(okLocal),
            "loopback base-url under local-only=true must NOT throw");

        // 3. Non-loopback under local-only=false → no throw.
        Map<String, String> noFlag = new LinkedHashMap<>();
        noFlag.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        noFlag.put("infochat.llm.security.base-url", "https://api.openai.com/v1");
        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(noFlag),
            "non-loopback base-url under local-only=false must NOT throw");
    }
}
