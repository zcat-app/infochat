package app.zcat.infochat.collector.bootstrap;

import app.zcat.infochat.core.util.TagNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no {@code @QuarkusTest}, no DataSource) for
 * {@link BootstrapLoader}'s collect-then-throw tag validation (M1-578).
 * The loader is constructed bare with {@code dataSource} left null, so
 * the test doubles as proof that validation throws BEFORE any DB
 * interaction — a connection borrow would NPE instead of throwing the
 * asserted {@link IllegalStateException}.
 */
class BootstrapLoaderTest {

    @Test
    void allInvalidTagsAcrossSourcesAreReportedInOneFailure(@TempDir Path tempDir) throws IOException {
        Path fixture = tempDir.resolve("multi-invalid-tags.json");
        Files.writeString(fixture, """
            [
              {"kind":"rss","identifier":"https://a.example/feed.xml","name":"A","category":"news",
               "tags":["GLM AI", "java", "Spring I/O"]},
              {"kind":"rss","identifier":"https://b.example/feed.xml","name":"B","category":"news",
               "tags":["-leading-dash", "security"]}
            ]
            """);

        BootstrapLoader loader = new BootstrapLoader();
        loader.sourcesFilePath = fixture.toString();

        IllegalStateException ex = assertThrows(IllegalStateException.class, loader::runLoad);
        String message = ex.getMessage();

        assertTrue(message.contains("'GLM AI'"),
            "message must name invalid tag 'GLM AI'; got: " + message);
        assertTrue(message.contains("'Spring I/O'"),
            "message must name invalid tag 'Spring I/O'; got: " + message);
        assertTrue(message.contains("'-leading-dash'"),
            "message must name invalid tag '-leading-dash'; got: " + message);
        assertTrue(message.contains("https://a.example/feed.xml"),
            "message must name the source of the first two invalid tags; got: " + message);
        assertTrue(message.contains("https://b.example/feed.xml"),
            "message must name the source of the third invalid tag; got: " + message);
        assertTrue(message.contains(TagNormalizer.TAG_NAME_PATTERN.pattern()),
            "message must state the reason (the tag character-class rule); got: " + message);
        assertFalse(message.contains("'java'"),
            "valid tag 'java' must not be flagged; got: " + message);
        assertFalse(message.contains("'security'"),
            "valid tag 'security' must not be flagged; got: " + message);
    }
}
