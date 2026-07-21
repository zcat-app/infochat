package app.zcat.infochat.provider.help;

import app.zcat.infochat.provider.bundle.BundleKeys;

import java.util.List;

/**
 * The conceptual topic corpus (M1-649, decision D68): a curated,
 * in-code list of {@link Topic}s whose intent-shaped match text is
 * embedded into {@code doc_embedding} under {@code doc_kind='topic'}
 * by {@link TopicCorpusBuilder} at Provider startup, and whose
 * answers are served as bundle-localized reviewed product copy by
 * the M1-666 delivery path.
 *
 * <p><b>Why a second corpus at all.</b> {@link CommandIntentIndex}
 * (M1-664) lets the bot answer "which command does X" without
 * inventing syntax, because every command answer is composed from
 * the runtime {@code HelpCommandHandler.CATALOGUE}. A second class
 * of question has no such single runtime source: "what is
 * probation", "why can't I post in the group", "what does /forget
 * actually erase", "who can change a source's tags", "unfollow vs
 * delete". This corpus is the drift-exposed half of that feature:
 * the served text IS the answer, so it cannot have the command
 * path's structural guarantee. The mitigation is a guard — but the
 * guard must point at the right source of truth.
 *
 * <p><b>Match surface vs served surface.</b> The match surface
 * ({@link Topic#title()} + {@link Topic#intentWords()}) is the only
 * text embedded into {@code doc_embedding}. The served surface is
 * the bundle value keyed by {@link Topic#answerBundleKey()}, never
 * stored and never re-read from the table. This carries
 * {@code CommandIntentIndex}'s match-not-assert invariant (D66) to
 * topics: a stale or attacker-edited topic row can degrade a match
 * but can never produce wrong served text, because the served text
 * is composed from the in-memory corpus at delivery time (M1-666).
 *
 * <p><b>Staleness guard — guide derivation vs code-fact pin.</b>
 * Each topic carries one of two guards, modelled as a sealed
 * interface so NullAway sees non-null fields everywhere and the
 * builder / tests can switch on the type cleanly:
 * <ul>
 *   <li>{@link GuideDerivation} — the conceptual (mental-model,
 *       rationale) topics. A USER_GUIDE.md anchor + the SHA-256 of
 *       the bytes between the anchor markers is captured here; the
 *       {@code HelpTopicCorpusTest.topicDerivationHashesMatchCurrentUserGuide}
 *       test reds the build when the guide changes under a topic.
 *       Anchor by an explicit HTML-comment id, NOT heading text, so
 *       a heading reword does not red the build for a non-reason.</li>
 *   <li>{@link CodeFactPin} — the code-fact topics whose load-bearing
 *       fact lives in runtime code, not in the guide
 *       ({@code /forget} erasure enumerates
 *       {@code ForgetPurgeService}'s purge categories; probation's
 *       duration reads {@code infochat.probation.duration}). The
 *       guide hash detects guide CHANGE, not guide/code MISMATCH;
 *       for these topics the drift that matters is code-vs-doc, so
 *       the pin is to code and the
 *       {@code HelpTopicCorpusTest.forgetErasureTopicMatchesPurgeService}
 *       / probation-duration tests are the backstop.</li>
 * </ul>
 *
 * <p><b>Tier-flat by construction.</b> Every topic is user-tier:
 * the {@code HelpTopicCorpusTest.noTopicReferencesAdminSurface} test
 * asserts no topic (match text OR answer) names a
 * {@code HelpCommandHandler.HelpTier#BOT_ADMIN} command. The pin is
 * against the bot-admin surface, NOT against
 * {@code LlmOutputSanitizer.CLOSED_LIST} membership — the
 * group-admin commands topics must name ({@code /add-source},
 * {@code /lang}, {@code /follow-tag}, …) are themselves
 * {@code CLOSED_LIST} entries and are expected in topic text. This
 * is necessary but NOT sufficient for delivery safety: the
 * user-reachable commands topics must name are in the sanitizer
 * {@code CLOSED_LIST}, which is exactly why M1-666 delivers
 * deterministically post-sanitize rather than through the model.
 *
 * <p><b>doc_id namespace disjointness.</b> Every topic's
 * {@link Topic#docId()} is {@code "topic:<slug>"} (see {@link #DOC_ID_PREFIX});
 * no command name contains a colon, so the {@code HelpTopicCorpusTest.topicDocIdsDisjointFromCommandNames}
 * test passes by construction and forecloses the V60
 * single-column-PK collision (a topic doc_id colliding with a
 * command name would miss the command row on the doc_kind-scoped
 * DELETE and PK-violate on INSERT, rolling back the batch and
 * silently degrading the corpus).
 *
 * <p>This class holds no in-memory runtime state and is never
 * instantiated. The {@link #CORPUS} list is a static immutable
 * constant consulted read-only by {@link TopicCorpusBuilder} at
 * startup and by the M1-666 delivery path at chat-turn time.
 */
public final class HelpTopicCorpus {

    /** Corpus discriminator on every {@code doc_embedding} read + write for topic rows. */
    public static final String DOC_KIND = "topic";

    /** Prefix on every topic {@code doc_id}, namespacing it away from command_intent's bare-name doc_ids. */
    public static final String DOC_ID_PREFIX = "topic:";

    private HelpTopicCorpus() {
    }

    /**
     * Sealed either/or for a topic's staleness guard. Implementations:
     * {@link GuideDerivation} (conceptual topics with a USER_GUIDE
     * anchor + content hash) and {@link CodeFactPin} (code-fact topics
     * whose load-bearing fact lives in runtime code).
     */
    public sealed interface StalenessGuard permits GuideDerivation, CodeFactPin {
    }

    /**
     * Guide-derivation guard: the topic's served answer is reviewable
     * product copy that derives from a fixed USER_GUIDE.md region.
     * The build reds when that region's bytes change, prompting a
     * re-verification that the topic is still accurate (and a hash
     * update only after that verification).
     *
     * @param slug        the topic slug; matches the {@code <!-- topic:<slug>:begin/end -->}
     *                    anchor markers in USER_GUIDE.md
     * @param contentHash SHA-256 of the UTF-8 bytes strictly between the
     *                    paired anchor markers (begin marker's trailing
     *                    newline excluded, end marker's leading newline
     *                    excluded) — matches the convention
     *                    {@code HelpTopicCorpusTest.topicDerivationHashesMatchCurrentUserGuide}
     *                    applies
     */
    public record GuideDerivation(String slug, String contentHash) implements StalenessGuard {
    }

    /**
     * Code-fact guard: the topic's load-bearing fact lives in runtime
     * code or config (not the guide), and a dedicated test — not a
     * guide hash — pins the fact.
     *
     * @param pinKey a stable human-readable label naming the runtime
     *               source the topic pins to (e.g.
     *               {@code "ForgetPurgeService.PurgeResult categories"},
     *               {@code "infochat.probation.duration"}); the
     *               corresponding {@code HelpTopicCorpusTest} case
     *               re-derives the fact from that source and asserts
     *               the topic answer matches
     */
    public record CodeFactPin(String pinKey) implements StalenessGuard {
    }

    /**
     * One curated conceptual topic. The match surface ({@link #title()}
     * + {@link #intentWords()}) is what {@link TopicCorpusBuilder}
     * embeds into {@code doc_embedding}; the served surface is the
     * bundle value keyed by {@link #answerBundleKey()}, never stored.
     *
     * @param slug            the topic's stable identifier; also its
     *                        {@code target_ref} and the kebab segment
     *                        of its {@code doc_id}
     * @param title           the topic's short title — first phrase of
     *                        the embedded intent doc
     * @param intentWords     intent-shaped question/synonym forms
     *                        (mirrors the role
     *                        {@code CommandIntentSynonyms} plays for
     *                        the command corpus); the embedded doc
     *                        appends these to the title
     * @param answerBundleKey the {@link BundleKeys} constant whose
     *                        bundle value is this topic's served
     *                        answer; the bundle is the source of truth
     *                        for the answer body, so this record never
     *                        carries answer prose
     * @param guard           this topic's staleness guard —
     *                        {@link GuideDerivation} for conceptual
     *                        topics, {@link CodeFactPin} for code-fact
     *                        topics
     */
    public record Topic(String slug,
                        String title,
                        List<String> intentWords,
                        String answerBundleKey,
                        StalenessGuard guard) {

        /**
         * The {@code doc_id} for this topic's {@code doc_embedding}
         * row: {@code "topic:<slug>"}. Namespaced under
         * {@link #DOC_ID_PREFIX} so it cannot collide with a
         * {@code command_intent} doc_id (no command name contains a
         * colon).
         *
         * @return the namespaced doc_id
         */
        public String docId() {
            return DOC_ID_PREFIX + slug;
        }

        /**
         * The {@code target_ref} for this topic's
         * {@code doc_embedding} row. The topic slug itself — the
         * pointer {@link CommandIntentIndex#lookupTopic} returns;
         * the delivery path (M1-666) resolves it back to this
         * {@link Topic} instance via {@link #byTargetRef(String)}.
         *
         * @return the topic slug
         */
        public String targetRef() {
            return slug;
        }
    }

    /**
     * The curated topic corpus, in display order. The set covers the
     * ten mandated conceptual topics (M1-649 acceptance item 1):
     * invite/access flow, probation, chat-vs-command mental model,
     * the chat assistant's read-only own-scope boundary, DM-vs-group
     * semantics, unfollow-vs-delete ownership, why /add-source
     * requires tags, personal-view vs shared-source tags, /clear vs
     * /forget, and what /forget does and does not erase.
     *
     * <p>Immutable ({@link List#of}); never mutated by the builder
     * or the delivery path.
     *
     * <p><b>Adding a topic.</b> Append a {@link Topic} here, add the
     * paired answer bundle key to {@link BundleKeys} and to BOTH
     * {@code en.properties} and {@code cs.properties} (D43 parity),
     * and — if the topic is conceptual — insert paired
     * {@code <!-- topic:<slug>:begin/end -->} markers in
     * {@code USER_GUIDE.md} and set the {@link GuideDerivation}
     * hash to the SHA-256 the
     * {@code HelpTopicCorpusTest.topicDerivationHashesMatchCurrentUserGuide}
     * test computes. The bundle-completeness CI guard
     * ({@code BundleLoaderTest}) and the topic-presence guard
     * ({@code HelpTopicCorpusTest.corpusContainsEveryMandatedTopic})
     * fail loudly until all three land.
     */
    public static final List<Topic> CORPUS = List.of(
            new Topic(
                    "getting-access",
                    "Getting access to infochat (invite code)",
                    List.of("invite", "access", "register", "join", "get in",
                            "sign up", "code", "onboarding", "welcome",
                            "how do I start", "how do I get in"),
                    BundleKeys.TOPIC_GETTING_ACCESS_ANSWER,
                    new GuideDerivation(
                            "getting-access",
                            "a488c260e52f69578244ad797dc454eb88ca7b57229bc8077da34da2d71b84df")),
            new Topic(
                    "probation",
                    "What probation (slow start) is and when it ends",
                    List.of("probation", "slow start", "restricted", "limited",
                            "unlock", "vouch", "new account", "when full access",
                            "when can I chat", "why can't I post",
                            "why can't I use commands", "chat disabled"),
                    BundleKeys.TOPIC_PROBATION_ANSWER,
                    new CodeFactPin("infochat.probation.duration")),
            new Topic(
                    "chat-vs-commands",
                    "Chatting in plain English vs running slash commands",
                    List.of("chat", "command", "slash", "plain text", "ask",
                            "how do I talk", "what's the difference",
                            "ask vs run", "natural language"),
                    BundleKeys.TOPIC_CHAT_VS_COMMANDS_ANSWER,
                    new GuideDerivation(
                            "chat-vs-commands",
                            "e407fde76d242937953ca791a00ea25480a6637ee8728d1b022f4b3af58c53af")),
            new Topic(
                    "chat-assistant-boundary",
                    "What the chat assistant can and cannot do (read-only, own scope)",
                    List.of("read only", "what can the assistant do",
                            "can it change settings", "can it post",
                            "can it act as admin", "what does it see",
                            "scope", "my data only", "assistant limits",
                            "can it edit"),
                    BundleKeys.TOPIC_CHAT_ASSISTANT_BOUNDARY_ANSWER,
                    new GuideDerivation(
                            "chat-assistant-boundary",
                            "23d2d08d99139d81f3290ff03023f138c3f83ff96684a05857be4119530c727a")),
            new Topic(
                    "dm-vs-group",
                    "Direct messages vs group chats",
                    List.of("dm", "direct message", "group", "mention",
                            "digest", "when reply", "group admin",
                            "what's the difference", "private chat",
                            "group chat", "how do I address the bot"),
                    BundleKeys.TOPIC_DM_VS_GROUP_ANSWER,
                    new GuideDerivation(
                            "dm-vs-group",
                            "329c7c00b00d106b5d137303e6eb3360bcb0531e77931624824b782b3c841f96")),
            new Topic(
                    "unfollow-vs-delete",
                    "Unfollow a source vs delete it",
                    List.of("unfollow", "delete", "remove", "source",
                            "subscribe", "stop seeing", "stop following",
                            "who can delete", "ownership", "per scope",
                            "just for me"),
                    BundleKeys.TOPIC_UNFOLLOW_VS_DELETE_ANSWER,
                    new GuideDerivation(
                            "unfollow-vs-delete",
                            "86e50c487405dfe8a4f561a09c1d43148d4a79567774495759eaa79b8670090a")),
            new Topic(
                    "add-source-requires-tags",
                    "Why /add-source requires tags",
                    List.of("add source", "tags required", "why tags",
                            "sort posts", "mandatory tags", "must have tags",
                            "bootstrap tags", "tag missing",
                            "why do I need tags", "categorize"),
                    BundleKeys.TOPIC_ADD_SOURCE_REQUIRES_TAGS_ANSWER,
                    new GuideDerivation(
                            "add-source-requires-tags",
                            "52b0c8d7cd5065fed475043b3ffe5a0b5baee3a209fe8662ee3cd727c548f826")),
            new Topic(
                    "personal-vs-shared-tags",
                    "Personal follow-tag view vs a source's shared tags",
                    List.of("personal tags", "shared tags", "my view",
                            "follow-tag", "source tags", "bootstrap tags",
                            "who can change", "tune my digest", "filter",
                            "per user", "per source"),
                    BundleKeys.TOPIC_PERSONAL_VS_SHARED_TAGS_ANSWER,
                    new GuideDerivation(
                            "personal-vs-shared-tags",
                            "6cde2f0bb326fef319ecec1eef77d7787fb6eb29c304a552ef23829d5837e019")),
            new Topic(
                    "clear-vs-forget",
                    "What /clear does vs what /forget does",
                    List.of("clear", "forget", "what's the difference",
                            "wipe", "erase", "privacy", "purge",
                            "reset conversation", "what stays",
                            "context vs data"),
                    BundleKeys.TOPIC_CLEAR_VS_FORGET_ANSWER,
                    new GuideDerivation(
                            "clear-vs-forget",
                            "97147dc1fb42c9bc62cb025b93bd25fbcc03f4839e8080e329f302451f4b1f9f")),
            new Topic(
                    "forget-erasure",
                    "What /forget erases and what it leaves untouched",
                    List.of("forget", "what does it erase", "what does it delete",
                            "what stays", "what does it not touch",
                            "ban status", "audit log", "saved posts",
                            "purge categories", "data deletion"),
                    BundleKeys.TOPIC_FORGET_ERASURE_ANSWER,
                    new CodeFactPin("ForgetPurgeService.PurgeResult categories")));

    /**
     * Look up a topic by its {@code target_ref} (the {@link Topic#slug()}).
     * Used by the M1-666 delivery path to resolve the
     * {@link CommandIntentIndex#lookupTopic} pointer back to the
     * in-memory {@link Topic} whose bundle key holds the served
     * answer.
     *
     * @param targetRef the {@code target_ref} returned by
     *                  {@code CommandIntentIndex.lookupTopic} (the topic slug)
     * @return the matching topic, or empty if no topic in the corpus
     *         carries that slug
     */
    public static java.util.Optional<Topic> byTargetRef(String targetRef) {
        for (Topic t : CORPUS) {
            if (t.slug().equals(targetRef)) {
                return java.util.Optional.of(t);
            }
        }
        return java.util.Optional.empty();
    }
}
