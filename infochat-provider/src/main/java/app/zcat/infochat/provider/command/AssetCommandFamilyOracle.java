package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.command.asset.AssetRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-asset slash command family oracle. Consulted by
 * {@link CommandPermissions#allowedDuringProbation} to decide whether
 * a slash command belongs to the operator-configured asset family
 * (and is therefore allowed during slow-start probation).
 *
 * <p>The oracle delegates to {@link AssetRegistry} on each call.
 * The no-arg constructor preserves backward compatibility: tests that
 * call {@code new AssetCommandFamilyOracle()} get an oracle that
 * returns {@code false} for all inputs (identical to the pre-swap
 * v1 seam behavior).</p>
 */
@ApplicationScoped
public class AssetCommandFamilyOracle {

    private final @Nullable AssetRegistry assetRegistry;

    /** No-arg constructor — returns false for all inputs. Preserves
     *  the pre-swap contract for pre-existing tests. */
    public AssetCommandFamilyOracle() {
        this.assetRegistry = null;
    }

    @Inject
    public AssetCommandFamilyOracle(@NonNull AssetRegistry assetRegistry) {
        this.assetRegistry = assetRegistry;
    }

    /**
     * Whether the given slash command name (without the leading
     * {@code /}) belongs to the operator-configured asset family.
     */
    public boolean isAssetCommand(@NonNull String slashCommand) {
        // No-arg (test) path: no registry → false for all.
        // CDI (production) path: delegates to the loaded registry.
        return assetRegistry != null && assetRegistry.containsEnabledAsset(slashCommand);
    }
}
