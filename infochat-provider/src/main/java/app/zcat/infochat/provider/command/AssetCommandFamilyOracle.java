package app.zcat.infochat.provider.command;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NonNull;

/**
 * Per-asset slash command family oracle.
 *
 * <p>The v1 spec lists exactly one family of slash commands —
 * {@code /<asset>} (e.g. {@code /zcash}, {@code /monero}) — that
 * is allowed during slow-start probation per {@code docs/spec/security.md}
 * §Slow-start tier (the closed allowed-set carve-out for asset
 * commands). The concrete asset names are operator-configured at
 * boot via {@code bootstrap-assets.json} per
 * {@code docs/spec/commands.md} §Asset commands plus
 * {@code docs/design/10-asset-commands.md}.
 *
 * <p>This v1 seam returns {@code false} for every input: no asset
 * commands exist on disk until the T2-H ticket lands the
 * bootstrap-fed registry. T2-H displaces this class's
 * implementation (the {@link #isAssetCommand} method body) without
 * changing the interface — every consumer (CommandPermissions)
 * continues to call the same method against the injected bean and
 * receives correct verdicts as soon as the new impl reads the
 * registry. The interface is held stable on purpose so M1-045's
 * probation gate does not need to be revisited when T2-H ships.
 */
@ApplicationScoped
public class AssetCommandFamilyOracle {

    /**
     * Whether the given slash command name (without the leading
     * {@code /}) belongs to the operator-configured asset family.
     *
     * <p>v1 returns {@code false} for every input. T2-H replaces
     * this method body to consult the {@code bootstrap-assets.json}
     * registry without altering the signature.
     */
    public boolean isAssetCommand(@NonNull String slashCommand) {
        return false;
    }
}
