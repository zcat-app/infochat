package app.zcat.infochat.provider.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Test double for ChatSessionRepository: serves canned turns keyed by
// (user, scope), mirroring the WHERE clause of the real readTurns query
// so prompt-builder tests can prove the builder asks for exactly the
// (user, scope) it was invoked with.
class StubChatSessionRepository extends ChatSessionRepository {

    record StoredTurn(UUID userId, String scopeKind, UUID scopeId, Turn turn) {}

    private final List<StoredTurn> stored;

    StubChatSessionRepository(List<StoredTurn> stored) {
        super(null);
        this.stored = stored;
    }

    @Override
    public List<Turn> readTurns(UUID userId, String scopeKind, UUID scopeId) {
        List<Turn> matching = new ArrayList<>();
        for (StoredTurn storedTurn : stored) {
            if (storedTurn.userId().equals(userId)
                    && storedTurn.scopeKind().equals(scopeKind)
                    && storedTurn.scopeId().equals(scopeId)) {
                matching.add(storedTurn.turn());
            }
        }
        return matching;
    }
}
