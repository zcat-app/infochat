package app.zcat.infochat.provider.chat.tool;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring pin: the shipped semantic-search default window is declared TWICE
 * (the {@code @ConfigProperty} defaultValue on {@link SemanticSearchTool}'s
 * injected constructor parameter and the base line in
 * {@code application.properties}) under an explicit must-not-drift comment.
 * This test pins BOTH declarations to one value and pins the key
 * base-only (no profile override), so a mutation moving one side — or
 * adding a profile-scoped split — fails. Plain reflection off the
 * constructor parameter, per the ConfigDefaultsConvergenceTest pattern
 * adapted to constructor injection; no container needed.
 *
 * <p>The properties side reads the MAIN application.properties off the
 * filesystem (surefire CWD = module basedir, ChatMemoryPrunerTest's
 * pattern): the test classpath carries its own application.properties,
 * which would shadow main.
 */
class SemanticSearchToolDefaultLimitWiringTest {

    private static final String KEY = "infochat.chat.semantic-limit";

    @Test
    void semanticLimitDefaultIsSixteenAndMatchesProperties() throws Exception {
        assertEquals("16", constructorDefaultValue(),
                KEY + " @ConfigProperty defaultValue must be \"16\"");

        List<String> declarations = propertyLines().stream()
                .filter(line -> line.contains(KEY))
                .toList();
        assertEquals(1, declarations.size(),
                KEY + " must be declared exactly once in application.properties "
                        + "(base-only, no profile override)");
        String line = declarations.get(0);
        assertTrue(line.startsWith(KEY + "="),
                "the single " + KEY + " declaration must be the unprefixed base "
                        + "line, got: " + line);
        assertEquals(16, Integer.parseInt(line.substring(KEY.length() + 1).trim()),
                "the base " + KEY + " declaration must parse to 16");
    }

    /**
     * The defaultValue off the {@code @ConfigProperty}-annotated constructor
     * parameter — the annotation rides the parameter, not a field, so the
     * reflective walk goes over constructors.
     */
    private static String constructorDefaultValue() {
        for (Constructor<?> candidate : SemanticSearchTool.class.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter parameter : candidate.getParameters()) {
                ConfigProperty annotation = parameter.getAnnotation(ConfigProperty.class);
                if (annotation != null && KEY.equals(annotation.name())) {
                    return annotation.defaultValue();
                }
            }
        }
        throw new AssertionError(
                "SemanticSearchTool has no @" + ConfigProperty.class.getSimpleName()
                        + " constructor parameter for " + KEY);
    }

    private static List<String> propertyLines() throws IOException {
        return Files.readAllLines(Path.of("src/main/resources/application.properties")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
