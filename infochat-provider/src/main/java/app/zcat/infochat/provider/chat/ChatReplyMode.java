package app.zcat.infochat.provider.chat;

/**
 * The chat-reply translation pipeline mode for a turn (decision D79,
 * spec {@code docs/spec/llm.md} §Translation flow). TRANSLATE is the
 * deployment default and today's behavior exactly: the reply is
 * generated in English and the display leg translates it for non-en
 * scopes. NATIVE generates the reply in the scope's declared /lang
 * language and skips the display leg. The mode is resolved once per
 * dispatch at intake (scope override, else deployment default, gated
 * on the bar-clearing registry) and cached on
 * {@link app.zcat.infochat.provider.messaging.InboundContext}, so it
 * never flips mid-turn.
 */
public enum ChatReplyMode {
    TRANSLATE,
    NATIVE
}
