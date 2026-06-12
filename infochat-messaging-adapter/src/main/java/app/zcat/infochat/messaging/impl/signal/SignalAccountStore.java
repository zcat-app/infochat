package app.zcat.infochat.messaging.impl.signal;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the bot's ACI from signal-cli's on-disk account store. signal-cli
 * run with {@code --config <data-dir>} keeps an accounts index at
 * {@code <data-dir>/data/accounts.json} whose entries carry
 * {@code {path, environment, number, uuid}} — the {@code uuid} field IS
 * the account's ACI (signal-cli's {@code AccountsStore} writes
 * {@code aci.toString()} into it). Resolving the ACI from the index
 * avoids parsing the much larger per-account data file, whose nested
 * layout has churned across signal-cli versions while the index shape
 * has not — and locating that file would require the index hop anyway
 * (its {@code path} need not equal the account number).
 *
 * <p>The store is a file-I/O system boundary: every failure shape
 * (missing/unreadable file, malformed JSON, no entry for the configured
 * account, absent ACI) is validated here and surfaces as
 * {@link IllegalStateException}. The format is signal-cli-internal and
 * deliberately pinned to the ONE current layout — a future format change
 * fails loudly at adapter startup, never silently. Failure messages name
 * the store path and the config property keys, never the account or ACI
 * values (D37 log hygiene).</p>
 */
final class SignalAccountStore {

    private SignalAccountStore() {
    }

    /**
     * Resolve the ACI of {@code account} from the signal-cli account
     * store under {@code dataDir}. Returns the raw {@code uuid} string
     * from the matching index entry; canonicalization and
     * well-formedness validation are the caller's
     * ({@code SignalAdapter#adoptBotAci}) responsibility.
     *
     * @throws IllegalStateException if the store is missing, unreadable,
     *         or malformed, no entry matches the configured account, or
     *         the matching entry carries no ACI.
     */
    static String readAci(String dataDir, String account) {
        Path storePath = Path.of(dataDir, "data", "accounts.json");
        if (!Files.isReadable(storePath)) {
            throw new IllegalStateException(
                    "signal-cli account store not found or not readable at " + storePath
                            + " (check " + SignalConfig.DATA_DIR_KEY + ")");
        }
        JsonObject store;
        try (Reader fileReader = Files.newBufferedReader(storePath);
             JsonReader jsonReader = Json.createReader(fileReader)) {
            store = jsonReader.readObject();
        } catch (IOException | JsonException e) {
            throw new IllegalStateException(
                    "signal-cli account store at " + storePath + " is not parseable JSON"
                            + " (check " + SignalConfig.DATA_DIR_KEY + ")", e);
        }
        if (!(store.get("accounts") instanceof JsonArray accounts)) {
            throw new IllegalStateException(
                    "signal-cli account store at " + storePath + " has no accounts list"
                            + " (check " + SignalConfig.DATA_DIR_KEY + ")");
        }
        for (JsonValue entryValue : accounts) {
            if (!(entryValue instanceof JsonObject entry)) {
                continue;
            }
            if (!(entry.get("number") instanceof JsonString number)
                    || !number.getString().equals(account)) {
                continue;
            }
            if (entry.get("uuid") instanceof JsonString uuid && !uuid.getString().isBlank()) {
                return uuid.getString();
            }
            throw new IllegalStateException(
                    "signal-cli account store entry at " + storePath + " for the configured"
                            + " account (" + SignalConfig.ACCOUNT_KEY + ") carries no ACI —"
                            + " the account may not have completed registration");
        }
        throw new IllegalStateException(
                "signal-cli account store at " + storePath + " has no entry for the configured"
                        + " account (" + SignalConfig.ACCOUNT_KEY + ")");
    }
}
