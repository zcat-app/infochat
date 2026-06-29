package app.zcat.infochat.core.testsupport;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression ratchet (M1-495) for the integration-test naming convention
 * (docs/design/08-verification.md §8.2: {@code *Test} = unit, {@code *IT} =
 * integration). A {@code @QuarkusTest} that injects a {@code DataSource} boots
 * DevServices Postgres, so by the convention it is integration-shaped and
 * belongs in the failsafe ({@code *IT}) phase, not the surefire ({@code *Test})
 * phase — running it as {@code *Test} pulls DevServices into {@code mvn test}.
 *
 * <p>The codebase already carries ~89 such classes named {@code *Test} (the
 * historical pattern for DB-backed component tests); renaming all of them is
 * out of M1-495's scope, so they are frozen into
 * {@code integration-test-naming-baseline.txt}. This test asserts the live
 * found-set is a SUBSET of that baseline: a NEW integration-shaped class named
 * {@code *Test} appears in the found-set but not the baseline and fails the
 * build, while renaming a baseline class to {@code *IT} only shrinks the
 * found-set, which the subset check tolerates with no guard edit.
 *
 * <p>This is a plain unit test, NOT a {@code @QuarkusTest}: it only walks the
 * on-disk test sources of every {@code infochat-*} module, so a single guard in
 * {@code infochat-core} can see modules that do not depend on core (the
 * classpath could not). It locates the multi-module root by walking up from the
 * working directory (the module basedir under surefire) until it finds a
 * directory holding both {@code infochat-collector} and {@code infochat-provider}.
 *
 * <p>Detection mirrors, byte-for-byte in intent, how the baseline was generated:
 * a {@code *Test.java} whose source contains {@code @QuarkusTest}, an injection
 * annotation ({@code @Inject} or {@code @SeedDataSource}), and a {@code DataSource}
 * field declaration. The fully-qualified name is the package declaration plus the
 * file name (top-level class name == file name, by Java rule).
 */
class IntegrationTestNamingGuardTest {

    private static final String BASELINE_RESOURCE = "/integration-test-naming-baseline.txt";

    /** A field declaration of type {@code DataSource} — the injected-seam marker. */
    private static final Pattern DATASOURCE_FIELD =
            Pattern.compile("\\bDataSource\\s+[A-Za-z_][A-Za-z0-9_]*\\s*;");

    @Test
    void noNewIntegrationShapedTestIsNamedTest() throws IOException {
        Set<String> found = scanIntegrationShapedUnitNamedTests(locateRepoRoot());
        Set<String> baseline = loadBaseline();

        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(baseline);

        assertTrue(unexpected.isEmpty(),
                "These @QuarkusTest classes inject a DataSource (they boot "
                        + "DevServices Postgres) yet are named *Test, so they run in "
                        + "the surefire unit phase instead of failsafe. Rename each to "
                        + "*IT, or — only if it is genuinely a unit test — add it to "
                        + "src/test/resources" + BASELINE_RESOURCE + ". Offenders: "
                        + unexpected);
    }

    private static Set<String> scanIntegrationShapedUnitNamedTests(Path repoRoot)
            throws IOException {
        Set<String> out = new TreeSet<>();
        List<Path> modules = new ArrayList<>();
        try (Stream<Path> entries = Files.list(repoRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> fileName(p).startsWith("infochat-"))
                    .forEach(modules::add);
        }
        for (Path module : modules) {
            Path testRoot = module.resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(testRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> fileName(p).endsWith("Test.java"))
                        .filter(IntegrationTestNamingGuardTest::isIntegrationShaped)
                        .map(IntegrationTestNamingGuardTest::fullyQualifiedName)
                        .forEach(out::add);
            }
        }
        return out;
    }

    private static boolean isIntegrationShaped(Path javaFile) {
        String source = read(javaFile);
        return source.contains("@QuarkusTest")
                && (source.contains("@Inject") || source.contains("@SeedDataSource"))
                && DATASOURCE_FIELD.matcher(source).find();
    }

    private static String fullyQualifiedName(Path javaFile) {
        String className = fileName(javaFile).substring(0,
                fileName(javaFile).length() - ".java".length());
        for (String line : read(javaFile).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).strip()
                        + "." + className;
            }
        }
        throw new IllegalStateException("no package declaration in " + javaFile);
    }

    /**
     * Walk up from the working directory (the module basedir under surefire)
     * until a directory holds both {@code infochat-collector} and
     * {@code infochat-provider} — the multi-module checkout root.
     */
    private static Path locateRepoRoot() {
        for (@Nullable Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infochat-collector"))
                    && Files.isDirectory(dir.resolve("infochat-provider"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "could not locate the multi-module repo root (a directory containing "
                        + "infochat-collector and infochat-provider) walking up from "
                        + Path.of("").toAbsolutePath());
    }

    private static Set<String> loadBaseline() throws IOException {
        InputStream in = IntegrationTestNamingGuardTest.class.getResourceAsStream(BASELINE_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(BASELINE_RESOURCE + " must be on the test classpath");
        }
        Set<String> out = new TreeSet<>();
        try (in) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalStateException("path has no file name: " + path);
        }
        return name.toString();
    }

    private static String read(Path javaFile) {
        try {
            return Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + javaFile, e);
        }
    }
}
