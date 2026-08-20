package app.zcat.infochat.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Proves the V14 checksum repair runbook end-to-end: drift fails boot validation, the documented repair restores it. */
@EnabledOnOs(OS.LINUX)
class V14HeaderCommentRepairIT extends PostgresSchemaTestBase {

    /** The pre-correction header lines the edit replaced (V14__asset_config.sql:32-34). */
    private static final String PRE_EDIT_COMMENT = """
            -- active → failed on consecutive failures (D42); operator recovery is
            -- the runbook SQL in docs/design/10-asset-commands.md §10.8b (no
            -- chat-command equivalent in v1).
            """;

    private static final String POST_EDIT_COMMENT = """
            -- active → failed on consecutive failures (D42); operator recovery is
            -- /asset-enable (docs/design/10-asset-commands.md §10.8b), with the
            -- §10.8b SQL as the host-level fallback when the Provider is down.
            """;

    @Test
    void driftedV14ChecksumFailsValidateAndDocumentedRepairRestoresBoot(@TempDir Path tmp) throws Exception {
        Path v14 = migrationDir().resolve("V14__asset_config.sql");
        String edited = Files.readString(v14);
        assertTrue(edited.contains(POST_EDIT_COMMENT), "the V14 header must carry the post-correction text");
        String preEditContent = edited.replace(POST_EDIT_COMMENT, PRE_EDIT_COMMENT);
        assertNotEquals(edited, preEditContent, "the pre-edit header must differ from the edited one");
        Path preEditFile = tmp.resolve("V14__asset_config.sql");
        Files.writeString(preEditFile, preEditContent);

        long postEditChecksum = checksumOf(v14);
        long preEditChecksum = checksumOf(preEditFile);
        assertNotEquals(preEditChecksum, postEditChecksum,
                "the comment-only edit must change the checksum — that drift is this runbook's subject");

        // Boot state: the edited migration set applied at container start.
        assertEquals(postEditChecksum, historyChecksum(),
                "the migrated history must hold the checksum of the on-disk V14");

        // Every pre-edit deployment carries the pre-edit checksum.
        setHistoryChecksum(preEditChecksum);

        // The crash-loop shape: the next migrate-at-start fails validation.
        assertThrows(FlywayException.class, () -> flyway().validate());

        // The documented repair: per-row checksum UPDATE keyed to version '14'.
        setHistoryChecksum(postEditChecksum);

        // Validation passes and the boot proceeds.
        flyway().validate();
        flyway().migrate();
        assertEquals(postEditChecksum, historyChecksum());
    }

    private long historyChecksum() throws SQLException {
        try (Connection c = newConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(
                        "SELECT checksum FROM flyway_schema_history "
                                + "WHERE success = true AND version = '14'")) {
            assertTrue(r.next(), "V14 must be recorded in flyway_schema_history");
            return r.getLong("checksum");
        }
    }

    private void setHistoryChecksum(long checksum) throws SQLException {
        try (Connection c = newConnection();
                PreparedStatement p = c.prepareStatement(
                        "UPDATE flyway_schema_history SET checksum = ? WHERE version = '14'")) {
            p.setLong(1, checksum);
            assertEquals(1, p.executeUpdate(), "the repair must hit exactly the V14 history row");
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    /** Run restore.sh's flyway_checksum helper over one file via bash; return the signed checksum. */
    private long checksumOf(Path file) throws Exception {
        String helper = extractFlywayChecksumFunction();
        Process p = new ProcessBuilder("bash", "-c", helper + "flyway_checksum \"$1\"", "-", file.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("flyway_checksum failed (" + rc + ") on "
                    + file.getFileName() + ": " + out);
        }
        return Long.parseLong(out.trim());
    }

    /** Extract the flyway_checksum function verbatim from the real restore.sh. */
    private String extractFlywayChecksumFunction() throws IOException {
        var lines = Files.readAllLines(repoRoot().resolve("prod/scripts/restore.sh"));
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
