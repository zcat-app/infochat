package app.zcat.infochat.provider.chat.tool.eval;

import java.util.Map;
import java.util.TreeSet;

/** World resolution for the two-leg retrieval instrument (M1-950). */
final class RetrievalEvalWorlds {

    static final String WORLD_PROPERTY = "eval.world";

    /** A world's name, its golden-set resource (classloader form), and its results leaf. */
    record World(String name, String resource, String resultsLeaf) {
        /** The Class-form spelling for {@code Class.getResourceAsStream} consumers. */
        String classResource() {
            return "/" + resource;
        }
    }

    // The ONLY place in the eval package that spells a golden-set
    // resource path — a second literal site silently reads the tech set
    // under a fam label (P10, two-world-retrieval-instrument analysis).
    private static final World TECH =
            new World("tech", "retrieval-eval/golden-set.jsonl", "results");
    private static final World FAM =
            new World("fam", "retrieval-eval/golden-set-fam.jsonl", "results-fam");

    private static final Map<String, World> WORLDS = Map.of(
            TECH.name(), TECH,
            FAM.name(), FAM);

    static World tech() {
        return TECH;
    }

    static World fam() {
        return FAM;
    }

    static World fromName(String name) {
        World world = name == null ? null : WORLDS.get(name);
        if (world == null) {
            throw new IllegalArgumentException("unknown eval.world '" + name
                    + "' — expected one of " + new TreeSet<>(WORLDS.keySet())
                    + "; refusing rather than running the default world");
        }
        return world;
    }

    static World resolve() {
        return fromName(System.getProperty(WORLD_PROPERTY, TECH.name()));
    }

    private RetrievalEvalWorlds() {
    }
}
