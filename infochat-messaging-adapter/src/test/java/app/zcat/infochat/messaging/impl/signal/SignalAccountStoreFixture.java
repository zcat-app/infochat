package app.zcat.infochat.messaging.impl.signal;

import jakarta.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes signal-cli account-store layouts into a test-supplied data
 * directory, mirroring the real on-disk shape {@code SignalAccountStore}
 * reads: an accounts index at {@code <data-dir>/data/accounts.json} with
 * entries {@code {path, environment, number, uuid}} (signal-cli's
 * {@code AccountsStorage} record; {@code uuid} carries the ACI).
 *
 * <p>Public (not package-private) for the same reason as
 * {@link FakeSignalCli}: the provider module's production-shape IT
 * consumes it from another package via the messaging-adapter test-jar.</p>
 */
public final class SignalAccountStoreFixture {

    private SignalAccountStoreFixture() {
    }

    /**
     * Write a well-formed account store under {@code dataDir} whose
     * single index entry maps {@code account} to {@code aci}. Returns
     * the path of the written {@code accounts.json}.
     */
    public static Path writeStore(Path dataDir, String account, String aci) {
        return write(dataDir, Json.createObjectBuilder()
                .add("accounts", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("path", "0")
                                .add("environment", "LIVE")
                                .add("number", account)
                                .add("uuid", aci)))
                .add("version", 2)
                .build()
                .toString());
    }

    /**
     * Write a store whose {@code accounts.json} is not parseable JSON.
     * Returns the path of the written file.
     */
    public static Path writeMalformedStore(Path dataDir) {
        return write(dataDir, "{not json at all");
    }

    private static Path write(Path dataDir, String content) {
        try {
            Path data = Files.createDirectories(dataDir.resolve("data"));
            return Files.writeString(data.resolve("accounts.json"), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
