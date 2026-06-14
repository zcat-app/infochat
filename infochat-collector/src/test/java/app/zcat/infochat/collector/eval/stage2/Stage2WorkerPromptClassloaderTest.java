package app.zcat.infochat.collector.eval.stage2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the classloader choice in {@link Stage2Worker#loadPromptTemplate()}
 * (M1-354 / opus-47 collector F4): the security-judge prompt MUST load via the
 * class's own loader, never the thread context classloader, so a foreign
 * {@code prompts/security-judge.md} on a stray classpath entry cannot shadow it
 * and silently weaken the Stage 2 injection check.
 *
 * <p>Plain JUnit (no {@code @QuarkusTest}): {@code loadPromptTemplate} is a
 * static resource read with no CDI collaborators, so a hostile TCCL plus a
 * direct call is the deterministic way to prove the loader choice.
 */
class Stage2WorkerPromptClassloaderTest {

    private static final String HOSTILE_PROMPT = "HOSTILE-SHADOW security-judge prompt — must never load";

    @Test
    void loadsRealPromptDespiteHostileContextClassLoader() throws Exception {
        // A foreign classpath root whose own prompts/security-judge.md would
        // shadow the real one IF the thread context classloader were consulted.
        Path hostileRoot = Files.createTempDirectory("stage2-hostile-cp");
        Path promptDir = Files.createDirectories(hostileRoot.resolve("prompts"));
        Files.writeString(promptDir.resolve("security-judge.md"), HOSTILE_PROMPT);

        // parent=null: no delegation, so this loader serves ONLY the hostile
        // resource — exactly the foreign-classpath shadowing F4 describes.
        try (URLClassLoader hostileLoader =
                 new URLClassLoader(new URL[]{hostileRoot.toUri().toURL()}, null)) {

            String realPrompt = readClasspathResource(
                Stage2Worker.class.getClassLoader(), Stage2Worker.PROMPT_RESOURCE);

            // Sanity: the hostile loader genuinely shadows the resource when
            // consulted directly, so the assertion below is meaningful and not a
            // coincidence of parent delegation finding the real copy first.
            assertEquals(HOSTILE_PROMPT,
                readClasspathResource(hostileLoader, Stage2Worker.PROMPT_RESOURCE),
                "the hostile loader must serve its shadow copy when consulted directly");

            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(hostileLoader);
            try {
                String loaded = Stage2Worker.loadPromptTemplate();
                assertEquals(realPrompt, loaded,
                    "loadPromptTemplate must return the real prompt via the class's own loader, "
                        + "not the hostile TCCL shadow");
                assertFalse(loaded.contains("HOSTILE-SHADOW"),
                    "the hostile shadow prompt must never be loaded");
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private static String readClasspathResource(ClassLoader loader, String path) throws IOException {
        try (InputStream in = loader.getResourceAsStream(path)) {
            assertNotNull(in, "resource must be present on the loader: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
