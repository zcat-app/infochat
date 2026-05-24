package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;
import org.jspecify.annotations.NonNull;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;

// post_reference table is v2-deferred (no migration exists). Returns
// empty list until the table and its ingest pipeline land.
@ApplicationScoped
public class GetReferencesTool implements ChatToolRegistry.ChatTool {

    @Override
    public @NonNull String execute(@NonNull UUID userId, @NonNull String scopeKind,
                                    @NonNull UUID scopeId, @NonNull Map<String, Object> args) {
        return "[]";
    }
}
