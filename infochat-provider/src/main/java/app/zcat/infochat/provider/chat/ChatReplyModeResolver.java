package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Resolves the chat-reply pipeline mode for a scope (decision D79, spec
 * {@code docs/spec/llm.md} §Translation flow): the scope's
 * {@code reply_mode} override wins when set, else the deployment
 * default; a NATIVE result additionally requires the deployment's chat
 * model and the scope language to form a pair the bar-clearing
 * {@link ChatReplyModeRegistry} clears. An uncleared pair resolves
 * TRANSLATE at resolution time, logged — the override stays stored so
 * the pair clearing later activates it without a further command
 * (commands.md §Conversation control). The mode never flips mid-turn:
 * the router resolves once per dispatch via this bean and caches the
 * result on {@code InboundContext}.
 */
@ApplicationScoped
public class ChatReplyModeResolver {

    private static final Logger log = LoggerFactory.getLogger(ChatReplyModeResolver.class);

    static final String CONFIG_KEY = "infochat.chat.reply-mode";
    static final String MODE_TRANSLATE = "translate";
    static final String MODE_NATIVE = "native";

    private final String deploymentDefault;
    private final String chatModel;
    private final ChatReplyModeRegistry registry;

    @Inject
    public ChatReplyModeResolver(
            @ConfigProperty(name = CONFIG_KEY, defaultValue = MODE_TRANSLATE) String deploymentDefault,
            @ConfigProperty(name = "infochat.llm.chat.model") String chatModel,
            ChatReplyModeRegistry registry) {
        this.deploymentDefault = deploymentDefault;
        this.chatModel = chatModel;
        this.registry = registry;
    }

    /**
     * Resolve the mode for a scope. {@code scopeOverride} is the scope's
     * stored {@code reply_mode} value or {@code null} when unset (the
     * deployment default applies); {@code scopeLanguage} is the scope's
     * declared /lang. Scope ids ride the log line so an operator can
     * trace a stored-but-inactive native setting to its gate.
     */
    public ChatReplyMode resolve(@Nullable String scopeOverride, String scopeLanguage,
                                 String scopeKind, UUID scopeId) {
        String configured = scopeOverride != null ? scopeOverride : deploymentDefault;
        if (!MODE_NATIVE.equals(configured)) {
            return ChatReplyMode.TRANSLATE;
        }
        if (registry.clears(chatModel, scopeLanguage)) {
            return ChatReplyMode.NATIVE;
        }
        log.info("reply-mode native configured for scopeKind={} scopeId={} but (chat model,"
                + " language) is not cleared by the bar-clearing registry; resolving translate",
                scopeKind, scopeId);
        return ChatReplyMode.TRANSLATE;
    }

    /**
     * Whether a native setting takes effect for the scope language — the
     * registry gate alone, for the /reply-mode handler's stored-but-inactive
     * confirmations and status reads.
     */
    public boolean nativeClears(String scopeLanguage) {
        return registry.clears(chatModel, scopeLanguage);
    }

    /** The deployment default mode name, for the unset-scope status read. */
    public String deploymentDefault() {
        return deploymentDefault;
    }
}
