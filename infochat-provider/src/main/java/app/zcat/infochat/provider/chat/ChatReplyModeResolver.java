package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the chat-reply pipeline mode for a scope (decision D79, spec
 * {@code docs/spec/llm.md} §Translation flow): the scope's
 * {@code reply_mode} override wins when set, else the deployment
 * default. The configured mode is decisive and never flips mid-turn:
 * the router resolves once per dispatch via this bean and caches the
 * result on {@code InboundContext}.
 */
@ApplicationScoped
public class ChatReplyModeResolver {

    static final String CONFIG_KEY = "infochat.chat.reply-mode";
    static final String MODE_TRANSLATE = "translate";
    static final String MODE_NATIVE = "native";

    private final String deploymentDefault;

    @Inject
    public ChatReplyModeResolver(
            @ConfigProperty(name = CONFIG_KEY, defaultValue = MODE_TRANSLATE) String deploymentDefault) {
        this.deploymentDefault = deploymentDefault;
    }

    /**
     * Resolve the mode for a scope. {@code scopeOverride} is the scope's
     * stored {@code reply_mode} value or {@code null} when unset (the
     * deployment default applies). The configured value is the only
     * input — the mode is decisive for any model and any language.
     */
    public ChatReplyMode resolve(@Nullable String scopeOverride) {
        String configured = scopeOverride != null ? scopeOverride : deploymentDefault;
        if (!MODE_NATIVE.equals(configured)) {
            return ChatReplyMode.TRANSLATE;
        }
        return ChatReplyMode.NATIVE;
    }

    /** The deployment default mode name, for the unset-scope status read. */
    public String deploymentDefault() {
        return deploymentDefault;
    }
}
