package app.zcat.infochat.provider.command;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Authorization-time predicate over slash command names for the
 * slow-start probation tier.
 *
 * <p>Per {@code docs/spec/security.md} §Slow-start tier, a probation
 * user may only invoke commands in the spec-closed allowed set
 * (read-only catalogue + privacy/locale levers + {@code /stop}
 * carve-out) plus the operator-configured asset-command family.
 * Every other slash command — including all admin commands and the
 * chat-mode sentinel — fails closed.
 *
 * <p>The allowed-set is enumerated verbatim from the spec into the
 * {@link #ALLOWED} constant. The asset-command family is delegated
 * to {@link AssetCommandFamilyOracle} (an empty v1 seam that T2-H
 * displaces with the bootstrap-fed registry).
 *
 * <p>{@link #allowedDuringProbation} is called by
 * {@code InboundRouter}'s step 5 (authorization step between ban
 * check and parse). The router fails closed on unknown command
 * names: a non-allowed-and-non-asset name returns false, so a
 * probation user invoking an unknown slash receives the same
 * {@code error.probation.blocked} reply as one invoking a
 * spec-blocked write.
 */
@ApplicationScoped
public class CommandPermissions {

    /**
     * Closed set of slash commands a probation user may invoke,
     * verbatim from {@code docs/spec/security.md} §Slow-start tier.
     * The eleven entries are: the read-only catalogue
     * ({@code help, status, get-tags, get-sources, list-sources,
     * summary, saved, export}), the user's privacy/locale levers
     * ({@code forget, lang}), and the {@code /stop} carve-out
     * (idempotent no-op, no side effect — see spec §Slow-start
     * tier "{@code /stop} is not blocked").
     */
    private static final Set<String> ALLOWED = Set.of(
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

    private final AssetCommandFamilyOracle assetCommandFamilyOracle;

    @Inject
    public CommandPermissions(AssetCommandFamilyOracle assetCommandFamilyOracle) {
        this.assetCommandFamilyOracle = assetCommandFamilyOracle;
    }

    /**
     * Whether {@code slashCommand} (without the leading {@code /})
     * is allowed during slow-start probation. Returns true iff the
     * name is in the spec's closed allowed-set OR delegates to the
     * asset oracle. Unknown names fail closed.
     */
    public boolean allowedDuringProbation(String slashCommand) {
        return ALLOWED.contains(slashCommand) || assetCommandFamilyOracle.isAssetCommand(slashCommand);
    }
}
