package app.zcat.infochat.provider.chat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract of {@link ChatSessionRepository#persistTurn}: parallel
 * writers against one (user, scope) must each receive a distinct seq and
 * never collide on the chat_message primary key. Without the FOR UPDATE on
 * the next_seq read, two writers read the same value and the second INSERT
 * fails with a PK violation.
 */
@QuarkusTest
class ChatSessionRepositoryConcurrencyTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";
    private static final int WRITERS = 8;
    private static final int TURNS_PER_WRITER = 5;

    @Inject @SeedDataSource DataSource dataSource;
    @Inject ChatSessionRepository repository;

    @BeforeEach
    void seedUserAndCleanSession() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, adapter, contact_id, registration_state) "
                  + "VALUES (?, 'inmemory', ?, 'vouched') "
                  + "ON CONFLICT (id) DO NOTHING")) {
                ps.setObject(1, USER_ID);
                ps.setString(2, "csrc-" + USER_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chat_session WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, USER_ID);
                ps.setString(2, SCOPE_KIND);
                ps.setObject(3, SCOPE_ID);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void concurrentPersistTurnsAssignDistinctSeqsWithoutCollision() throws Exception {
        // Start gate so all writers hit persistTurn simultaneously rather
        // than serially as the pool spins up.
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        List<Future<List<Integer>>> futures = new ArrayList<>();
        try {
            for (int writer = 0; writer < WRITERS; writer++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    List<Integer> seqs = new ArrayList<>();
                    for (int turn = 0; turn < TURNS_PER_WRITER; turn++) {
                        seqs.add(repository.persistTurn(
                                USER_ID, SCOPE_KIND, SCOPE_ID, "user", "concurrent turn", 3));
                    }
                    return seqs;
                }));
            }
            startGate.countDown();

            // Future.get rethrows any persistTurn failure (e.g. the PK
            // collision the FOR UPDATE lock must prevent) and fails the test.
            Set<Integer> allSeqs = new HashSet<>();
            int totalTurns = 0;
            for (Future<List<Integer>> future : futures) {
                List<Integer> seqs = future.get();
                totalTurns += seqs.size();
                allSeqs.addAll(seqs);
            }

            assertEquals(WRITERS * TURNS_PER_WRITER, totalTurns);
            assertEquals(WRITERS * TURNS_PER_WRITER, allSeqs.size(),
                    "every turn must land with a distinct seq");
            for (int seq = 0; seq < WRITERS * TURNS_PER_WRITER; seq++) {
                assertTrue(allSeqs.contains(seq),
                        "seq " + seq + " must be assigned exactly once");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
