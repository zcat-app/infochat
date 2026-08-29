package app.zcat.infochat.provider.chat.tool.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World-seam legs (M1-950): resolution, unknown-world refusal, literal-site fence. */
class RetrievalEvalWorldsTest {

    private static final String PKG_DIR =
            "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval";

    @AfterEach
    void clearWorldProperty() {
        System.clearProperty(RetrievalEvalWorlds.WORLD_PROPERTY);
    }

    @Test
    void resolvesWorldToResourceAndLeaf() {
        RetrievalEvalWorlds.World tech = RetrievalEvalWorlds.fromName("tech");
        assertEquals("tech", tech.name());
        assertEquals("retrieval-eval/golden-set.jsonl", tech.resource());
        assertEquals("results", tech.resultsLeaf());

        RetrievalEvalWorlds.World fam = RetrievalEvalWorlds.fromName("fam");
        assertEquals("fam", fam.name());
        assertEquals("retrieval-eval/golden-set-fam.jsonl", fam.resource());
        assertEquals("results-fam", fam.resultsLeaf());

        assertEquals(tech, RetrievalEvalWorlds.resolve(), "no eval.world set must default to tech");
        System.setProperty(RetrievalEvalWorlds.WORLD_PROPERTY, "fam");
        assertEquals(fam, RetrievalEvalWorlds.resolve());
        System.setProperty(RetrievalEvalWorlds.WORLD_PROPERTY, "tech");
        assertEquals(tech, RetrievalEvalWorlds.resolve());
    }

    @Test
    void resolvedResourcesExistOnTheTestClasspath() {
        for (String world : List.of("tech", "fam")) {
            RetrievalEvalWorlds.World w = RetrievalEvalWorlds.fromName(world);
            assertNotNull(
                    getClass().getClassLoader().getResourceAsStream(w.resource()),
                    w.resource() + " not on the test classpath");
            assertNotNull(
                    getClass().getResourceAsStream(w.classResource()),
                    w.classResource() + " not resolvable in Class form");
        }
    }

    @Test
    void unknownWorldFailsLoudNeverFallsBackToTech() {
        IllegalArgumentException bogus = assertThrows(IllegalArgumentException.class,
                () -> RetrievalEvalWorlds.fromName("bogus"));
        assertTrue(bogus.getMessage().contains("bogus"), "refusal must name the offending world");
        assertTrue(bogus.getMessage().contains("tech") && bogus.getMessage().contains("fam"),
                "refusal must name the valid worlds: " + bogus.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvalWorlds.fromName(null));

        System.setProperty(RetrievalEvalWorlds.WORLD_PROPERTY, "bogus");
        assertThrows(IllegalArgumentException.class, RetrievalEvalWorlds::resolve,
                "resolve() must refuse an unknown eval.world, never silently run tech");
    }

    @Test
    void goldenSetResourceLiteralsLiveOnlyInsideTheSeam() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "repo root not found above " + System.getProperty("user.dir"));
        Path evalPackage = dir.resolve(PKG_DIR);
        // The seam and this test are the world-resolution surface: the
        // test's own assertEquals pins are the contract (acceptance item
        // 1), not a second resolution site.
        List<String> allowed = List.of("RetrievalEvalWorlds.java", "RetrievalEvalWorldsTest.java");
        List<String> offenders = new ArrayList<>();
        try (var files = Files.list(evalPackage)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                if (allowed.contains(f.getFileName().toString())) {
                    continue;
                }
                if (Files.readString(f).contains("golden-set.jsonl")
                        || Files.readString(f).contains("golden-set-fam.jsonl")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "golden-set resource literals outside the RetrievalEvalWorlds seam (P10): " + offenders);
    }
}
