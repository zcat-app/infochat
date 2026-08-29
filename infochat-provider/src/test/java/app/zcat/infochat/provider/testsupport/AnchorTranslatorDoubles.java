package app.zcat.infochat.provider.testsupport;

import app.zcat.infochat.provider.chat.tool.QueryAnchorTranslator;

import java.util.UUID;

/** Passthrough anchor-translator double for direct-construction ChatAgent tests. */
public final class AnchorTranslatorDoubles {

    private AnchorTranslatorDoubles() {
    }

    public static QueryAnchorTranslator passthrough() {
        return new Passthrough();
    }

    private static final class Passthrough extends QueryAnchorTranslator {
        Passthrough() {
            super(null, null, null, 500);
        }

        @Override
        public String translate(String query, String sourceLanguage,
                                String scopeKind, UUID scopeId) {
            return query;
        }
    }
}
