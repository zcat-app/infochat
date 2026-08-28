package app.zcat.infochat.collector.partition;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wiring pin (the SemanticSearchToolDefaultLimitWiringTest pattern, plain JUnit): the shipped retention defaults in application.properties keep every derivative table aligned with post per profile (02-schema.md §2.4.4) — base 30, %pi 14.
 */
class PartitionRetentionPropertiesTest {

    private static final List<String> ALIGNED_KEYS = List.of(
        "infochat.partitions.retention-days.post",
        "infochat.partitions.retention-days.post-embedding",
        "infochat.partitions.retention-days.post-entity",
        "infochat.partitions.retention-days.post-reference");

    @Test
    void baseProfileAlignsDerivativeRetentionWithPost() throws IOException {
        for (String key : ALIGNED_KEYS) {
            assertEquals(30, propertyValue(key),
                "base " + key + " must parse to 30 — aligned with post");
        }
    }

    @Test
    void piProfileAlignsDerivativeRetentionWithPost() throws IOException {
        for (String key : ALIGNED_KEYS) {
            assertEquals(14, propertyValue("%pi." + key),
                "%pi " + key + " must parse to 14 — aligned with %pi post");
        }
    }

    // Fails on a missing or duplicated declaration, not only a wrong value,
    // so a profile-scoped silent drift cannot be reintroduced.
    private static int propertyValue(String prefixedKey) throws IOException {
        List<String> declarations = Files.readAllLines(
                Path.of("src/main/resources/application.properties")).stream()
            .map(String::trim)
            .filter(line -> line.startsWith(prefixedKey + "="))
            .toList();
        if (declarations.size() != 1) {
            throw new AssertionError(prefixedKey + " must be declared exactly once in "
                + "src/main/resources/application.properties, found " + declarations.size()
                + ": " + declarations);
        }
        return Integer.parseInt(declarations.get(0).substring(prefixedKey.length() + 1).trim());
    }
}
