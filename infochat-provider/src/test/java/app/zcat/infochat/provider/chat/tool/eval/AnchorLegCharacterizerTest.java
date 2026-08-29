package app.zcat.infochat.provider.chat.tool.eval;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.chat.tool.QueryAnchorTranslator;
import app.zcat.infochat.provider.translation.QueryTranslationCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit legs of the M1-945 characterization: pure derivations + the no-expansion prompt corpus (D58 (d)). */
class AnchorLegCharacterizerTest {

    private static final String SCOPE_KIND = "dm";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000945");

    /** Fixture pair shaped like the measured cyber case (0.75 vs 0.25). */
    @Test
    void siblingPairDerivations() {
        List<String> expected = List.of("e1", "e2", "e3", "e4");
        List<String> siblingWindow = List.of("n1", "e1", "n2", "e2", "e3", "n3", "shared", "n4");
        List<String> anchoredWindow = List.of("x1", "shared", "x2", "x3", "e2", "x4", "x5", "x6");

        assertEquals(2, AnchorLegCharacterizer.windowOverlap(siblingWindow, anchoredWindow),
                "window overlap counts every uid present in both windows");
        assertEquals(0, AnchorLegCharacterizer.windowOverlap(List.of("a", "b"), List.of("c", "d")),
                "disjoint windows overlap zero");
        assertEquals(2, AnchorLegCharacterizer.windowOverlap(anchoredWindow, siblingWindow),
                "overlap is order-independent");

        assertEquals(List.of(2, 4, 5), AnchorLegCharacterizer.hitRanks(siblingWindow, expected),
                "sibling hit ranks are 1-based and ascending");
        assertEquals(List.of(5), AnchorLegCharacterizer.hitRanks(anchoredWindow, expected),
                "anchored hit ranks name the single expected hit");
        assertEquals(List.of(), AnchorLegCharacterizer.hitRanks(List.of("x1", "x2"), expected),
                "a window with no expected hit has an empty rank list");

        assertEquals(0.75, AnchorLegCharacterizer.rawRecall(siblingWindow, expected),
                "sibling raw recall is hits over |E|");
        assertEquals(0.25, AnchorLegCharacterizer.rawRecall(anchoredWindow, expected),
                "anchored raw recall is hits over |E|");
        assertEquals(-0.5, AnchorLegCharacterizer.rawRecallDelta(anchoredWindow, siblingWindow, expected),
                "per-pair raw-recall delta is anchored minus sibling (the measured -0.5 cyber shape)");
        assertEquals(0.5, AnchorLegCharacterizer.rawRecallDelta(siblingWindow, anchoredWindow, expected),
                "the delta flips sign with the pair");

        assertThrows(IllegalArgumentException.class,
                () -> AnchorLegCharacterizer.rawRecall(siblingWindow, List.of()),
                "an empty expected set is a contract violation, never a silent 0");
    }

    /** No-expansion failure mode (P8): every issued prompt must be the shipped template, only the three production substitutions. */
    @Test
    void everyIssuedPromptIsByteDerivedFromTheShippedLanguageOnlyPrompt() throws Exception {
        String template = staticStringField("PROMPT_TEMPLATE");
        String openFormat = staticStringField("UNTRUSTED_CONTENT_OPEN_FORMAT");
        String closeFormat = staticStringField("UNTRUSTED_CONTENT_CLOSE_FORMAT");
        PromptCapturingProvider provider = new PromptCapturingProvider();
        QueryAnchorTranslator translator = new QueryAnchorTranslator(
                new LlmRouter(List.of(new LlmRouter.Entry("stub", provider, Set.of())),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                new QueryTranslationCache(), new NeverOpenBreaker(), 500);

        byte[] goldenBytes;
        String resource = RetrievalEvalWorlds.tech().resource();
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " not on the test classpath (M1-928)");
            goldenBytes = in.readAllBytes();
        }
        List<RetrievalGoldenSetLoader.GoldenRow> xling = RetrievalGoldenSetLoader.load(goldenBytes)
                .activeRows().stream().filter(r -> "cross-lingual".equals(r.clazz())).toList();

        for (RetrievalGoldenSetLoader.GoldenRow row : xling) {
            translator.translate(row.query(), row.scopeLang(), SCOPE_KIND, SCOPE_ID);
        }

        assertEquals(xling.size(), provider.userPrompts.size(),
                "one translator call per active cross-lingual row — no leg issues more");
        for (int i = 0; i < xling.size(); i++) {
            String prompt = provider.userPrompts.get(i);
            String query = xling.get(i).query();
            String language = xling.get(i).scopeLang();
            Matcher open = Pattern.compile("<<<UNTRUSTED_CONTENT id=\"([0-9a-f-]{36})\">>>")
                    .matcher(prompt);
            assertTrue(open.find(), "prompt carries the constructed open marker");
            String marker = open.group(1);
            String wrapped = String.format(openFormat, marker) + "\n" + query + "\n"
                    + String.format(closeFormat, marker);
            String expected = template
                    .replace("{{SOURCE_LANGUAGE}}", language)
                    .replace("{{id}}", marker)
                    .replace("{{WRAPPED_QUERY}}", wrapped);
            assertEquals(expected, prompt,
                    "every issued prompt is the shipped template byte-for-byte (row " + i + ")");
        }
    }

    private static String staticStringField(String name) {
        // Reflection, not a retyped copy: the probe must compare against
        // the shipped bytes so template and probe cannot drift together.
        try {
            var field = QueryAnchorTranslator.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read QueryAnchorTranslator." + name, e);
        }
    }

    static final class PromptCapturingProvider implements LlmProvider {
        final List<String> userPrompts = new ArrayList<>();

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            userPrompts.add(userPrompt);
            return new LlmResponse("captured prompt " + userPrompts.size());
        }
    }

    static final class NeverOpenBreaker extends LlmCircuitBreakerRegistry {
        NeverOpenBreaker() {
            super(3, 30_000, Clock.systemUTC(), LlmRouter.ConfigReader.fromMap(Map.of()));
        }

        @Override
        public boolean wouldShortCircuit(ModelTask task) {
            return false;
        }
    }
}
