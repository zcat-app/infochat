package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.command.asset.AssetRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Per-asset slash command family oracle. Consulted by
 * {@link CommandPermissions#allowedDuringProbation} to decide whether
 * a slash command belongs to the operator-configured asset family
 * (and is therefore allowed during slow-start probation).
 *
 * <p>The oracle delegates to {@link AssetRegistry} on each call.</p>
 */
@ApplicationScoped
public class AssetCommandFamilyOracle {

    private final AssetRegistry assetRegistry;

    @Inject
    public AssetCommandFamilyOracle(AssetRegistry assetRegistry) {
        this.assetRegistry = assetRegistry;
    }

    /**
     * Whether the given slash command name (without the leading
     * {@code /}) belongs to the operator-configured asset family.
     */
    public boolean isAssetCommand(String slashCommand) {
        return assetRegistry.containsEnabledAsset(slashCommand);
    }
}
