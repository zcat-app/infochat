package app.zcat.infochat.core.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link TargetKind} ↔ V5 CHECK-set parity. The enum exists to
 * make the closed {@code audit_log.target_kind} set a compile-time
 * contract; this test is the drift guard — adding a value to either
 * side without the other fails the build. Parses the migration text
 * rather than querying {@code information_schema} so the pin runs as
 * a plain unit test with no container.
 */
class TargetKindCheckParityTest {

    @Test
    void targetKindEnumMirrorsV5CheckSetExactly() throws IOException {
        Matcher checkConstraint = Pattern
                .compile("CHECK \\(target_kind IN \\(([^)]+)\\)\\)")
                .matcher(readV5Migration());
        assertTrue(checkConstraint.find(),
                "V5 must contain the target_kind CHECK constraint");

        Set<String> checkSet = Arrays.stream(checkConstraint.group(1).split(","))
                .map(value -> value.trim().replaceAll("^'|'$", ""))
                .collect(Collectors.toSet());
        Set<String> enumSet = Arrays.stream(TargetKind.values())
                .map(TargetKind::dbValue)
                .collect(Collectors.toSet());

        assertEquals(checkSet, enumSet,
                "TargetKind dbValues must mirror the V5 target_kind CHECK set "
                        + "exactly — a value added to either side needs the other "
                        + "side (and a migration, for the SQL side) in the same "
                        + "change");
    }

    private static String readV5Migration() throws IOException {
        try (InputStream migration = TargetKindCheckParityTest.class
                .getResourceAsStream("/db/migration/V5__identity_audit.sql")) {
            assertNotNull(migration, "V5__identity_audit.sql must be on the test classpath");
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
