package app.zcat.infochat.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pins restore.sh's {@code flyway_checksum} helper (M1-819) against the PINNED flyway-core: a
 *  migrated Testcontainers PostgreSQL is the oracle — reproduce {@code flyway_schema_history.checksum}
 *  for EVERY applied migration, plus a comment-only-edit mutation case. Linux-gated (bash + awk). */
@EnabledOnOs(OS.LINUX)
class RestoreFlywayChecksumIT extends PostgresSchemaTestBase {

    @Test
    void checksumFunctionMatchesMigratedSchemaHistoryForEveryAppliedMigration() throws Exception {
        Map<String, Long> history = new LinkedHashMap<>();
        try (Connection c = newConnection(); Statement s = c.createStatement();
                ResultSet rows = s.executeQuery(
                        "SELECT version, script, checksum FROM flyway_schema_history "
                                + "WHERE success = true AND script LIKE 'V%.sql' "
                                + "ORDER BY installed_rank")) {
            while (rows.next()) {
                history.put(rows.getString("script"), rows.getLong("checksum"));
            }
        }
        List<Path> checkoutFiles = migrationFiles();
        assertEquals(checkoutFiles.size(), history.size(),
                "the migrated history must cover exactly the checkout's migration set");

        String helper = extractFlywayChecksumFunction();
        for (Map.Entry<String, Long> row : history.entrySet()) {
            Path file = migrationDir().resolve(row.getKey());
            assertTrue(Files.exists(file), "history script missing from the checkout: " + row.getKey());
            assertEquals(row.getValue().longValue(), runHelper(helper, file),
                    "helper checksum diverges from real Flyway for " + row.getKey());
        }
    }

    @Test
    void commentOnlyEditChangesTheComputedChecksum(@TempDir Path tmp) throws Exception {
        Path original = migrationDir().resolve("V50__banned_admin_actor_checks.sql");
        String sql = Files.readString(original);
        int commentAt = sql.indexOf("-- 4. U-56 rider");
        assertTrue(commentAt >= 0, "the mutation anchor comment must exist in V50");
        Path mutated = tmp.resolve("V50__banned_admin_actor_checks.sql");
        Files.writeString(mutated, sql.substring(0, commentAt)
                + "-- comment-only edit (the a60315c3 failure shape)\n"
                + sql.substring(commentAt));

        String helper = extractFlywayChecksumFunction();
        assertNotEquals(runHelper(helper, original), runHelper(helper, mutated),
                "a comment-only edit must change the checksum — the gate exists precisely "
                        + "because Flyway checksums cover comments too");
    }

    // --- helpers ----------------------------------------------------------------

    /** Extract the flyway_checksum function verbatim from the real restore.sh. */
    private String extractFlywayChecksumFunction() throws IOException {
        List<String> lines = Files.readAllLines(repoRoot().resolve("prod/scripts/restore.sh"));
        StringBuilder fn = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (!inside && line.startsWith("flyway_checksum()")) {
                inside = true;
            }
            if (inside) {
                fn.append(line).append('\n');
                if (line.equals("}")) {
                    return fn.toString();
                }
            }
        }
        throw new IllegalStateException("flyway_checksum() not found in prod/scripts/restore.sh");
    }

    /** Run the extracted helper over one file via bash; return the signed checksum. */
    private long runHelper(String helper, Path file) throws Exception {
        Process p = new ProcessBuilder("bash", "-c",
                helper + "flyway_checksum \"$1\"", "-", file.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("flyway_checksum failed (" + rc + ") on "
                    + file.getFileName() + ": " + out);
        }
        return Long.parseLong(out.trim());
    }

    private List<Path> migrationFiles() throws IOException {
        try (var stream = Files.list(migrationDir())) {
            return stream.filter(f -> f.getFileName().toString().matches("V.*\\.sql")).sorted().toList();
        }
    }

    private Path migrationDir() {
        return repoRoot().resolve("infochat-core/src/main/resources/db/migration");
    }

    /** Walk up from the module CWD to the repo root (the dir with docker-compose.yml). */
    private Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("docker-compose.yml not found walking up from " + dir);
    }
}
