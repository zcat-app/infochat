package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.command.asset.AssetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit pin of the spec-closed allowed/blocked matrix for
 * slow-start probation per {@code docs/spec/security.md} §Slow-start
 * tier. The matrix is closed: every command in the v1 catalogue
 * carries a hard verdict.
 *
 * <p>The test exercises {@link CommandPermissions#allowedDuringProbation}
 * directly with a real {@link AssetCommandFamilyOracle} stub. Three
 * shapes are pinned:
 *
 * <ol>
 *   <li>The eleven explicitly-allowed commands return true
 *       (read-only catalogue + privacy/locale levers + {@code /stop}
 *       carve-out).</li>
 *   <li>The nineteen explicitly-blocked commands return false
 *       (chat-mode-equivalent writes, LLM-invoking writes, every
 *       admin command).</li>
 *   <li>Unknown command names and the chat-mode sentinel return
 *       false (fail-closed).</li>
 * </ol>
 *
 * <p>The asset-command family delegation is pinned with a tiny
 * stub oracle that recognizes only {@code "zcash"}: the production
 * v1 oracle returns false unconditionally, but the test substitutes
 * a non-empty stub so the delegation seam is exercised end-to-end
 * (a regression in the delegation would surface here even though
 * the v1 production behavior is "always false").
 */
class CommandPermissionsTest {

    /** The 11 explicitly-allowed commands per spec §Slow-start tier. */
    private static final List<String> SPEC_ALLOWED = List.of(
            "help",
            "status",
            "get-tags",
            "get-sources",
            "list-sources",
            "summary",
            "saved",
            "export",
            "forget",
            "lang",
            "stop");

    /** The 19 explicitly-blocked commands per spec §Slow-start tier. */
    private static final List<String> SPEC_BLOCKED = List.of(
            "add-source",
            "save",
            "unsave",
            "follow-tag",
            "unfollow-tag",
            "clear",
            "compress",
            "group-timezone",
            "retry",
            "ban",
            "unban",
            "invite",
            "vouch",
            "grant-admin",
            "revoke-admin",
            "promote",
            "demote",
            "quarantine",
            "audit");

    private CommandPermissions permissions;

    @BeforeEach
    void setUp() {
        permissions = new CommandPermissions(new AssetCommandFamilyOracle(new AssetRegistry()));
    }

    @Test
    void allowedCommandsReturnTrue() {
        for (String name : SPEC_ALLOWED) {
            assertTrue(permissions.allowedDuringProbation(name),
                    "expected /" + name + " to be ALLOWED during probation per spec §Slow-start tier");
        }
        // Explicit pins for the four spec-flagged carve-outs:
        // - "stop" is the only non-read-only-or-locale-or-privacy entry
        //   (idempotent no-op carve-out).
        // - "forget" is the privacy lever (blocking would undermine D37).
        // - "lang" is the locale lever (blocking would lock non-English
        //   new users out of help during the most needed window).
        assertTrue(permissions.allowedDuringProbation("stop"));
        assertTrue(permissions.allowedDuringProbation("forget"));
        assertTrue(permissions.allowedDuringProbation("lang"));
    }

    @Test
    void blockedCommandsReturnFalse() {
        for (String name : SPEC_BLOCKED) {
            assertFalse(permissions.allowedDuringProbation(name),
                    "expected /" + name + " to be BLOCKED during probation per spec §Slow-start tier");
        }
        // Explicit pin for the spec's first blocked write
        // (add-source is the primary write a probation user might
        // attempt; the gate MUST refuse).
        assertFalse(permissions.allowedDuringProbation("add-source"));
    }

    @Test
    void unknownCommandFailsClosed() {
        // Spec rule: any name not in the closed allowed-set and not
        // in the asset family fails closed. A typo or a future
        // command name yet-to-be-added returns false.
        assertFalse(permissions.allowedDuringProbation("does-not-exist"));
        assertFalse(permissions.allowedDuringProbation(""));
        // The chat-mode sentinel is whatever non-slash sentinel the
        // router maps non-slash bodies to. Any non-allowed name
        // works; we pin "chat-mode" specifically to match the
        // router's convention.
        assertFalse(permissions.allowedDuringProbation("chat-mode"));
    }

    @Test
    void assetCommandFamilyDelegatesToOracle() {
        // The v1 oracle returns false unconditionally — so a
        // probation user invoking /zcash today would be blocked
        // (no asset registry on disk until T2-H). Test with a
        // non-empty stub to confirm the delegation seam works:
        // when the oracle yields true, CommandPermissions yields
        // true; T2-H's new impl plugs in here without any further
        // change to CommandPermissions.
        Set<String> family = Set.of("zcash", "monero");
        CommandPermissions withStub = new CommandPermissions(new AssetCommandFamilyOracle(new AssetRegistry()) {
            @Override
            public boolean isAssetCommand(String slashCommand) {
                return family.contains(slashCommand);
            }
        });
        assertTrue(withStub.allowedDuringProbation("zcash"));
        assertTrue(withStub.allowedDuringProbation("monero"));
        assertFalse(withStub.allowedDuringProbation("add-source"));
    }
}
