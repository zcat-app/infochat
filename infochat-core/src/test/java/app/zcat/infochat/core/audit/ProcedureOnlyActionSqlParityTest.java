package app.zcat.infochat.core.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins {@link ProcedureOnlyAction} ↔ the audit verbs the SECURITY
 * DEFINER procedures write directly in SQL. The enum exists so the
 * {@code /audit --action} read path can resolve those verbs by one
 * symbol; this test is the drift guard — renaming a verb on the SQL
 * side OR the Java side without the other fails the build. It is the
 * full-set sibling of {@link TargetKindCheckParityTest} (which pins
 * {@code TargetKind} the same way) and supersedes the single-verb
 * cross-check in {@code SchemaHardeningIT}.
 *
 * <p>Why scan <em>every</em> migration, not just the current chain
 * head: migrations are immutable once shipped, so a future
 * {@code CREATE OR REPLACE} that renamed a verb would leave the old
 * spelling in its original file. Taking the union across all
 * migrations means any such rename grows the SQL set past the enum and
 * fails here, which is the point — these four verbs are a stable
 * contract, not a free-to-rename label.</p>
 *
 * <p>The SQL-side verb is the quoted literal written into the
 * {@code action} column, which in every audit_log INSERT is
 * immediately followed by its {@code target_kind} literal. Anchoring
 * on a {@link TargetKind} {@code dbValue} (itself a pinned closed set)
 * extracts exactly those four verbs and nothing else — status/enum
 * literals elsewhere in the migrations are not followed by a
 * target_kind and never match. Parses the migration text rather than
 * querying the DB so the pin runs as a plain unit test with no
 * container.</p>
 */
class ProcedureOnlyActionSqlParityTest {

    // The action-column literal is always immediately followed by the
    // target_kind literal; anchoring on the pinned TargetKind set is
    // what makes this match exactly the procedure-written audit verbs.
    private static final Pattern AUDIT_VERB = Pattern.compile(
            "'([A-Z][A-Z0-9_]+)'\\s*,\\s*'(?:" + targetKindAlternation() + ")'");

    @Test
    void procedureOnlyActionMirrorsSecurityDefinerAuditVerbsExactly() throws IOException {
        Set<String> sqlVerbs = readAllMigrations().stream()
                .flatMap(ProcedureOnlyActionSqlParityTest::extractVerbs)
                .collect(Collectors.toSet());
        Set<String> enumNames = Arrays.stream(ProcedureOnlyAction.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(sqlVerbs, enumNames,
                "ProcedureOnlyAction constants must mirror the audit verbs the "
                        + "SECURITY DEFINER procedures write in SQL exactly — a "
                        + "rename on either side needs the other (and a migration, "
                        + "for the SQL side) in the same change");
    }

    private static Stream<String> extractVerbs(String migrationSql) {
        Matcher m = AUDIT_VERB.matcher(migrationSql);
        Stream.Builder<String> verbs = Stream.builder();
        while (m.find()) {
            verbs.add(m.group(1));
        }
        return verbs.build();
    }

    private static String targetKindAlternation() {
        return Arrays.stream(TargetKind.values())
                .map(TargetKind::dbValue)
                .collect(Collectors.joining("|"));
    }

    private static Set<String> readAllMigrations() throws IOException {
        URL dir = ProcedureOnlyActionSqlParityTest.class.getResource("/db/migration");
        assertNotNull(dir, "db/migration must be on the test classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files
                    .filter(p -> p.toString().endsWith(".sql"))
                    .map(ProcedureOnlyActionSqlParityTest::read)
                    .collect(Collectors.toSet());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("db/migration URL is not a valid URI", e);
        }
    }

    private static String read(Path migration) {
        try (InputStream in = Files.newInputStream(migration)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading migration " + migration, e);
        }
    }
}
