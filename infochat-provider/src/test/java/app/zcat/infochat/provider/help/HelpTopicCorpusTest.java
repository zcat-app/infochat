package app.zcat.infochat.provider.help;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.command.ForgetPurgeService;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CommandHelp;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.HelpTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB-free guard tests for {@link HelpTopicCorpus} (M1-649). This class
 * is plain JUnit on purpose: the M1-495 naming ratchet flags DB-backed
 * tests whose class name matches {@code *Test}, so the corpus's
 * non-retrieval invariants live here and the DB-backed retrieval
 * invariants live in {@code TopicCorpusRetrievalIT}.
 *
 * <p>Each acceptance item from M1-649 is covered by exactly one test
 * method below:
 * <ul>
 *   <li>{@link #corpusContainsEveryMandatedTopic()} — acceptance item 1
 *       (the ten mandated topics are all present);</li>
 *   <li>{@link #topicDocIdsDisjointFromCommandNames()} — item 3
 *       (namespaced {@code doc_id} cannot collide with a command_intent
 *       {@code doc_id});</li>
 *   <li>{@link #topicDerivationHashesMatchCurrentUserGuide()} — item 4a
 *       (guide-anchored conceptual topics carry a derivation hash that
 *       reds when the anchored USER_GUIDE.md region changes);</li>
 *   <li>{@link #forgetErasureTopicMatchesPurgeService()} — item 4b
 *       (code-fact topic pins its enumerated categories to
 *       {@link ForgetPurgeService.PurgeResult}'s record components);</li>
 *   <li>{@link #probationTopicDurationMatchesApplicationConfig()} — item 4b
 *       (probation topic's stated duration matches the deployment's
 *       {@code infochat.probation.duration} value);</li>
 *   <li>{@link #noTopicReferencesAdminSurface()} — item 5
 *       (topics are tier-flat — no topic match text or answer value
 *       names a {@link HelpTier#BOT_ADMIN} command);</li>
 *   <li>{@link #matchTextIsIntentShapedAndDisjointFromAnswer()} — item 2
 *       spirit (each topic's match text is composed of title + intent
 *       words and does not contain its EN answer copy).</li>
 * </ul>
 */
class HelpTopicCorpusTest {

    /**
     * Acceptance item 1 — the ten mandated conceptual topics are all
     * present in {@link HelpTopicCorpus#CORPUS}. A topic present in
     * the corpus but missing from this set fails the assertion; a
     * topic in this set but absent from the corpus fails it the other
     * way. The set is the M1-649 acceptance item 1 enumeration
     * verbatim.
     */
    @Test
    void corpusContainsEveryMandatedTopic() {
        Set<String> expectedSlugs = Set.of(
                "getting-access",
                "probation",
                "chat-vs-commands",
                "chat-assistant-boundary",
                "dm-vs-group",
                "unfollow-vs-delete",
                "add-source-requires-tags",
                "personal-vs-shared-tags",
                "clear-vs-forget",
                "forget-erasure");
        Set<String> actualSlugs = HelpTopicCorpus.CORPUS.stream()
                .map(HelpTopicCorpus.Topic::slug)
                .collect(Collectors.toSet());
        assertEquals(expectedSlugs, actualSlugs,
                "the corpus must contain exactly the ten mandated conceptual topics "
                        + "(M1-649 acceptance item 1) — no more, no fewer");
    }

    /**
     * Acceptance item 3 — every topic {@code doc_id} is namespaced
     * under {@link HelpTopicCorpus#DOC_ID_PREFIX} and cannot collide
     * with a {@code command_intent} {@code doc_id}. The namespacing
     * holds by construction (no {@link HelpCommandHandler#CATALOGUE}
     * command name contains a colon), so this test is the regression
     * guard against a future topic whose slug slips the prefix or a
     * future command whose name adopts a colon.
     *
     * <p>Why this matters: V60's {@code doc_embedding} PRIMARY KEY is
     * single-column on {@code doc_id}, and the upsert's DELETE is
     * {@code doc_kind}-scoped. A topic {@code doc_id} colliding with
     * a command name would miss the command row on DELETE and
     * PK-violate on INSERT — rolling back the batch and silently
     * degrading the corpus.
     */
    @Test
    void topicDocIdsDisjointFromCommandNames() {
        Set<String> commandNames = HelpCommandHandler.CATALOGUE.stream()
                .map(CommandHelp::command)
                .collect(Collectors.toSet());
        assertFalse(commandNames.isEmpty(),
                "CATALOGUE must be non-empty; otherwise the disjointness check is vacuous");
        for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
            String docId = topic.docId();
            assertTrue(docId.startsWith(HelpTopicCorpus.DOC_ID_PREFIX),
                    "topic doc_id must be namespaced under '" + HelpTopicCorpus.DOC_ID_PREFIX
                            + "' — got: " + docId);
            assertFalse(commandNames.contains(docId),
                    "topic doc_id must not equal any command name (single-column PK collision hazard): "
                            + docId);
            assertFalse(commandNames.contains(topic.targetRef()),
                    "topic target_ref must not equal any command name either — both columns "
                            + "share the doc_embedding namespace and a collision on target_ref "
                            + "would let a command-side lookup leak a topic ref: " + topic.targetRef());
        }
    }

    /**
     * Acceptance item 4a — every {@link HelpTopicCorpus.GuideDerivation}
     * topic records a content hash of the bytes between its paired
     * {@code <!-- topic:<slug>:begin/end -->} markers in
     * {@code USER_GUIDE.md}. The test reds the build when:
     * <ul>
     *   <li>a marker is missing (distinct loud failure with the slug);</li>
     *   <li>the marker pair's bytes no longer hash to the recorded
     *       value (the guide drifted under the topic — re-verify and
     *       update the hash only after that verification).</li>
     * </ul>
     * Anchor by HTML-comment id, NOT heading text, so a heading
     * reword alone does not red the build for a non-reason.
     *
     * <p>File is located by walking up from {@code user.dir} until
     * {@code USER_GUIDE.md} is found — cwd-robust across surefire /
     * IDE runners.
     */
    @Test
    void topicDerivationHashesMatchCurrentUserGuide() throws Exception {
        Path guide = findUserGuide();
        String content = Files.readString(guide, StandardCharsets.UTF_8);

        for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
            if (!(topic.guard() instanceof HelpTopicCorpus.GuideDerivation guideDerivation)) {
                continue;
            }
            String slug = topic.slug();
            String expectedHash = guideDerivation.contentHash();

            Pattern pattern = Pattern.compile(
                    "<!-- topic:" + Pattern.quote(slug) + ":begin -->\\n(.*?)\\n<!-- topic:"
                            + Pattern.quote(slug) + ":end -->",
                    Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);
            if (!matcher.find()) {
                fail("USER_GUIDE.md is missing the paired anchor markers for guide-anchored topic '"
                        + slug + "'. Expected markers: <!-- topic:" + slug + ":begin --> and "
                        + "<!-- topic:" + slug + ":end --> wrapping the load-bearing prose. "
                        + "Either add the markers (and re-hash) or change the topic's guard.");
            }
            String slice = matcher.group(1);
            String actualHash = sha256(slice.getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedHash, actualHash,
                    "USER_GUIDE.md content under topic '" + slug + "' has drifted (hash mismatch). "
                            + "Re-verify the topic's served answer is still accurate against the "
                            + "updated guide, then update the GuideDerivation's contentHash to "
                            + this.getClass().getSimpleName() + "'s actual. Do not silence this "
                            + "test by reverting the guide.");
        }
    }

    /**
     * Acceptance item 4b — the {@code /forget} erasure topic is a
     * code-fact topic whose load-bearing fact is the set of categories
     * {@link ForgetPurgeService} purges. The guide hash alone would
     * detect guide CHANGE, not guide/code MISMATCH, so this test pins
     * the topic's enumerated purged categories to the runtime source.
     *
     * <p>The runtime set is derived via reflection over
     * {@link ForgetPurgeService.PurgeResult}'s record components:
     * {@code chatMemoryCount → chat_memory},
     * {@code chatSessionCount → chat_session},
     * {@code summaryAnchorCount → summary_anchor},
     * {@code savedPostCount → saved_post}. The test then asserts the
     * EN answer copy names each runtime-purged category by its table
     * name (so a new PurgeResult component fails the test until the
     * answer copy is updated), AND asserts the answer's not-touched
     * enumeration is disjoint from the purged set (so an answer that
     * claims to leave a purged category fails), AND asserts the
     * saves-are-global and personal-summary-anchor facts appear
     * (per D13 and the {@code command_kind='personal'} predicate).
     */
    @Test
    void forgetErasureTopicMatchesPurgeService() throws Exception {
        // The runtime set, derived from the record components the
        // service actually returns. If ForgetPurgeService adds a
        // category, the derived set grows; the assertion below fails
        // until the answer copy names the new table.
        Set<String> runtimePurged = Arrays.stream(ForgetPurgeService.PurgeResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.replaceAll("Count$", ""))
                .map(HelpTopicCorpusTest::camelToSnake)
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("chat_memory", "chat_session", "summary_anchor", "saved_post"),
                runtimePurged,
                "ForgetPurgeService.PurgeResult's record components must derive to the four "
                        + "expected purged categories — a new component means a new category the "
                        + "topic answer must enumerate");

        // The /forget erasure topic MUST be in the corpus.
        Optional<HelpTopicCorpus.Topic> forgetTopic = HelpTopicCorpus.byTargetRef("forget-erasure");
        assertTrue(forgetTopic.isPresent(),
                "the corpus must carry the 'forget-erasure' topic");
        assertEquals(BundleKeys.TOPIC_FORGET_ERASURE_ANSWER, forgetTopic.get().answerBundleKey(),
                "the forget-erasure topic's answer bundle key must be TOPIC_FORGET_ERASURE_ANSWER");

        // The answer MUST name each runtime-purged category.
        String enAnswer = loadOwnKeys("en").getProperty(BundleKeys.TOPIC_FORGET_ERASURE_ANSWER);
        assertNotNull(enAnswer, "EN bundle must define topic.forget-erasure.answer");
        for (String category : runtimePurged) {
            assertTrue(enAnswer.contains(category),
                    "EN /forget-erasure answer must name purged category '" + category
                            + "' verbatim (derived from ForgetPurgeService.PurgeResult) — the "
                            + "service is the single canonical source. Answer was: " + enAnswer);
        }

        // Saves are global per D13; summary anchors are personal-only.
        // Both are scope restrictions on the broader table, and the
        // answer must state them — otherwise an answer that claims
        // "/forget wipes all summary anchors" or "saves from this
        // conversation only" is silently wrong.
        assertTrue(enAnswer.toLowerCase().contains("global"),
                "EN /forget-erasure answer must state that saved_post rows are GLOBAL "
                        + "(per decision D13). Answer was: " + enAnswer);
        assertTrue(enAnswer.toLowerCase().contains("personal"),
                "EN /forget-erasure answer must state that summary_anchor rows are "
                        + "personal-only (per ForgetPurgeService.DELETE_SUMMARY_ANCHOR_SQL's "
                        + "command_kind='personal' predicate). Answer was: " + enAnswer);

        // The not-touched enumeration must be disjoint from the purged
        // set: an answer that names a category as both purged AND
        // not-touched is self-contradictory. Concretely, the answer
        // MUST name each of the load-bearing not-touched surfaces
        // (ban/admin status, group memberships, audit log) — the four
        // surfaces the guide calls out and the topic must mirror.
        Set<String> mandatoryNotTouchedTokens = Set.of(
                "ban", "admin", "group", "audit");
        String lowerAnswer = enAnswer.toLowerCase();
        for (String token : mandatoryNotTouchedTokens) {
            assertTrue(lowerAnswer.contains(token),
                    "EN /forget-erasure answer must enumerate the not-touched surface '" + token
                            + "' (ban/admin status, group memberships, audit log). Answer was: "
                            + enAnswer);
        }
        // The not-touched tokens above are surface names; the purged
        // tokens are table names. The two sets must be disjoint — a
        // topic that claims to both purge AND leave a category is
        // self-contradictory.
        for (String purged : runtimePurged) {
            assertFalse(lowerAnswer.contains("not touch: " + purged)
                            && lowerAnswer.contains(purged),
                    "EN answer is self-contradictory on category '" + purged
                            + "' — it appears in both the purged and not-touched enumerations. "
                            + "Answer was: " + enAnswer);
        }
    }

    /**
     * Acceptance item 4b (probation half) — the probation topic is a
     * code-fact topic whose load-bearing fact is the probation window
     * length, read from {@code infochat.probation.duration} in the
     * main {@code application.properties}. The guide hash alone would
     * not catch the deployment changing the duration while the guide
     * (and the topic) stayed stale-green, so this test pins the
     * answer's stated duration to the live config value.
     *
     * <p>The properties file is read by filesystem path (not
     * classpath) because the test-resources {@code application.properties}
     * shadows the classpath name and carries no probation key.
     */
    @Test
    void probationTopicDurationMatchesApplicationConfig() throws Exception {
        String configValue = readProbationDurationFromMainProperties();
        assertNotNull(configValue,
                "main application.properties must define infochat.probation.duration — "
                        + "the probation topic pins its duration to this key");

        Optional<HelpTopicCorpus.Topic> probationTopic = HelpTopicCorpus.byTargetRef("probation");
        assertTrue(probationTopic.isPresent(),
                "the corpus must carry the 'probation' topic");
        assertTrue(probationTopic.get().guard() instanceof HelpTopicCorpus.CodeFactPin,
                "the probation topic's guard must be a CodeFactPin");

        String enAnswer = loadOwnKeys("en").getProperty(BundleKeys.TOPIC_PROBATION_ANSWER);
        assertNotNull(enAnswer, "EN bundle must define topic.probation.answer");
        assertTrue(enAnswer.contains(configValue),
                "EN probation answer must state the deployment's infochat.probation.duration "
                        + "value verbatim ('" + configValue + "'). Answer was: " + enAnswer);

        String csAnswer = loadOwnKeys("cs").getProperty(BundleKeys.TOPIC_PROBATION_ANSWER);
        assertNotNull(csAnswer, "CS bundle must define topic.probation.answer");
        assertTrue(csAnswer.contains(configValue),
                "CS probation answer must state the deployment's infochat.probation.duration "
                        + "value verbatim ('" + configValue + "') — the value is a config token, "
                        + "not translatable prose. Answer was: " + csAnswer);
    }

    @Test
    void noTopicAnswerNamesARawConfigKey() throws Exception {
        // REPRODUCTION (M1-815, analysis D-2): the D69 topic path bypasses
        // the sanitizer by design, so the bundle text itself is the fix
        // site — no topic answer may name a dotted config key.
        Pattern configKeyToken = Pattern.compile("infochat\\.[a-z]");
        for (String lang : List.of("en", "cs", "tr", "es", "ru")) {
            Properties bundle = loadOwnKeys(lang);
            for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
                String answer = bundle.getProperty(topic.answerBundleKey());
                assertNotNull(answer,
                        lang + " bundle must define " + topic.answerBundleKey());
                assertFalse(configKeyToken.matcher(answer).find(),
                        lang + " topic answer '" + topic.slug()
                                + "' must not name a raw config key: " + answer);
            }
        }
    }

    /**
     * Acceptance item 5 — every topic is user-tier by construction.
     * The pin is against the {@link HelpTier#BOT_ADMIN} surface, NOT
     * against {@code LlmOutputSanitizer.CLOSED_LIST} membership — the
     * group-admin commands topics must name ({@code /add-source},
     * {@code /lang}, {@code /follow-tag}, …) are themselves
     * {@code CLOSED_LIST} entries and are expected in topic text.
     *
     * <p>Concrete consequence: the invite/access-flow topic describes
     * the flow without naming any {@code /invite} subcommand (all
     * bot-admin tier). Necessary but NOT sufficient for delivery
     * safety: the user-reachable commands topics must name are in the
     * sanitizer {@code CLOSED_LIST}, which is exactly why M1-666
     * delivers deterministically post-sanitize rather than through
     * the model.
     */
    @Test
    void noTopicReferencesAdminSurface() throws Exception {
        Set<String> botAdminCommands = HelpCommandHandler.CATALOGUE.stream()
                .filter(e -> e.tier() == HelpTier.BOT_ADMIN)
                .map(CommandHelp::command)
                .collect(Collectors.toSet());
        assertFalse(botAdminCommands.isEmpty(),
                "CATALOGUE must carry at least one BOT_ADMIN command; otherwise the tier-flat "
                        + "pin is vacuous");

        // Build the bot-admin command-name patterns the topic text
        // must not match: a literal "/<name>" followed by a word
        // boundary (so "/invite create" matches but "/invites" does
        // not, and "/ban" matches but "/banana" does not). Pattern
        // mirrors LlmOutputSanitizer.compileClosedListPattern's
        // trailing-lookahead contract.
        List<Pattern> botAdminPatterns = new ArrayList<>();
        for (String name : botAdminCommands) {
            botAdminPatterns.add(Pattern.compile("/" + Pattern.quote(name) + "(?=$|[^a-zA-Z0-9\\-])"));
        }

        Properties en = loadOwnKeys("en");
        Properties cs = loadOwnKeys("cs");

        for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
            // Match surface: title + intent words.
            String matchText = composeMatchText(topic);
            assertNoBotAdminCommand(matchText, topic.slug(), "match text", botAdminPatterns);

            // Served surface: EN + CS answer bundle values.
            String enAnswer = en.getProperty(topic.answerBundleKey());
            assertNotNull(enAnswer,
                    "EN bundle must define " + topic.answerBundleKey() + " for topic " + topic.slug());
            assertNoBotAdminCommand(enAnswer, topic.slug(), "EN answer", botAdminPatterns);

            String csAnswer = cs.getProperty(topic.answerBundleKey());
            assertNotNull(csAnswer,
                    "CS bundle must define " + topic.answerBundleKey() + " for topic " + topic.slug());
            assertNoBotAdminCommand(csAnswer, topic.slug(), "CS answer", botAdminPatterns);
        }
    }

    /**
     * Spirit of acceptance item 2 — each topic's match text is
     * composed of title + intent words (intent-shaped, mirroring the
     * command corpus) and does NOT contain its EN answer copy. The
     * match text is what the embedder sees; embedding the answer
     * would defeat the served-surface / match-surface split (D66
     * carried to topics) and would shift the threshold statistics
     * the recalibration note in {@code CommandIntentIndex.lookupTopic}
     * already flags as a follow-up.
     *
     * <p>CI NOTE: the suite's embedders are stubs (fixed vectors), so
     * this test pins the SHAPE of the match text, not its recall.
     * Real no-shared-word recall is verified by live calibration
     * (the M1-619 pattern), not by CI; the IT covers retrieval
     * plumbing with rigged-distance vectors.
     */
    @Test
    void matchTextIsIntentShapedAndDisjointFromAnswer() throws Exception {
        Properties en = loadOwnKeys("en");
        for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
            String matchText = composeMatchText(topic);
            // Title is the first phrase of the match text.
            assertTrue(matchText.startsWith(topic.title()),
                    "topic '" + topic.slug() + "' match text must start with its title; got: "
                            + matchText);
            // Every intent word appears in the match text.
            for (String word : topic.intentWords()) {
                assertTrue(matchText.contains(word),
                        "topic '" + topic.slug() + "' match text must include intent word '" + word
                                + "'; got: " + matchText);
            }
            // The match text must NOT contain the answer copy (the
            // served surface is the bundle value; embedding it would
            // cross the match-surface / served-surface boundary D66
            // pins for the command corpus and this ticket carries to
            // topics).
            String enAnswer = en.getProperty(topic.answerBundleKey());
            assertNotNull(enAnswer);
            String answerFirstSentence = enAnswer.split("[.!?\\n]", 2)[0];
            assertFalse(matchText.contains(answerFirstSentence),
                    "topic '" + topic.slug() + "' match text must not contain its EN answer copy "
                            + "(match surface and served surface are disjoint by design).");
        }
    }

    // ---------- helpers ----------

    /**
     * Compose the match text exactly as {@link TopicCorpusBuilder}
     * does at startup, so the test pins the same shape the embedder
     * sees. Mirrors {@code CommandIntentIndexBuilder.composeIntentText}.
     */
    private static String composeMatchText(HelpTopicCorpus.Topic topic) {
        StringBuilder sb = new StringBuilder();
        sb.append(topic.title());
        if (!topic.intentWords().isEmpty()) {
            sb.append(". Intent words: ");
            for (int i = 0; i < topic.intentWords().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(topic.intentWords().get(i));
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private static void assertNoBotAdminCommand(String text, String slug, String surface,
                                                 List<Pattern> botAdminPatterns) {
        for (Pattern p : botAdminPatterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                fail("topic '" + slug + "' " + surface + " names a BOT_ADMIN command ('"
                        + m.group() + "') — topics are tier-flat by construction (M1-649 "
                        + "acceptance item 5). Surface was: " + text);
            }
        }
    }

    private static Path findUserGuide() {
        // Walk up from user.dir until USER_GUIDE.md is found —
        // cwd-robust across surefire (module basedir) and IDE runners.
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path current = cwd;
        for (int i = 0; i < 10; i++) {
            Path candidate = current.resolve("USER_GUIDE.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
            if (current == null) {
                break;
            }
        }
        throw new AssertionError(
                "USER_GUIDE.md not found walking up from user.dir=" + cwd
                        + " — the test needs the repo-root guide to verify the topic anchor hashes");
    }

    private static String readProbationDurationFromMainProperties() throws IOException {
        // Locate the main application.properties by walking up from
        // user.dir. Reading via filesystem path (not classpath) is
        // load-bearing: the test-resources application.properties
        // shadows the classpath name and carries no probation key, so
        // the classpath lookup would return null.
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path current = cwd;
        for (int i = 0; i < 10; i++) {
            Path candidate = current.resolve(
                    "infochat-provider/src/main/resources/application.properties");
            if (Files.isRegularFile(candidate)) {
                Properties props = new Properties();
                try (InputStream stream = Files.newInputStream(candidate)) {
                    props.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
                return props.getProperty("infochat.probation.duration");
            }
            current = current.getParent();
            if (current == null) {
                break;
            }
        }
        throw new AssertionError(
                "main application.properties not found walking up from user.dir=" + cwd);
    }

    private static Properties loadOwnKeys(String lang) throws IOException {
        // Mirror BundleLoaderTest.loadOwnKeys so the topic tests can
        // see each bundle's OWN keys without going through @Inject
        // (this class is plain JUnit, not @QuarkusTest).
        String resource = "/bundles/" + lang + ".properties";
        Properties bundle = new Properties();
        try (InputStream stream = HelpTopicCorpusTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "bundle resource not found on classpath: " + resource);
            bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return bundle;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Convert a CamelCase identifier to snake_case
     * (e.g. {@code chatMemory} → {@code chat_memory}). Used to derive
     * the {@code doc_embedding}-style table name from a
     * {@link ForgetPurgeService.PurgeResult} record component name.
     */
    private static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
