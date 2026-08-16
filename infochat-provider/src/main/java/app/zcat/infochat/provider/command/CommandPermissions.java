package app.zcat.infochat.provider.command;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

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
     * Closed, ordered list of slash commands a probation user may invoke,
     * verbatim from {@code docs/spec/security.md} §Slow-start tier.
     * The eleven entries are: the read-only catalogue
     * ({@code help, status, get-tags, get-sources, list-sources,
     * summary, saved, export}), the user's privacy/locale levers
     * ({@code forget, lang}), and the {@code /stop} carve-out
     * (idempotent no-op, no side effect — see spec §Slow-start
     * tier "{@code /stop} is not blocked").
     *
     * <p>Ordered (a {@link List}, not an unordered {@code Set}) so it can
     * seed the single canonical probation-command listing that the welcome
     * reply, the rejection reply, and {@code /help} all render from — the
     * one source those three surfaces cannot drift apart on (M1-590). The
     * eleven names are distinct, so {@link List#contains} is an exact
     * membership test for the probation gate.
     */
    private static final List<String> ALLOWED = List.of(
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
            "reply-mode",
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

    /**
     * The single canonical, ordered list of command names (without the
     * leading {@code /}) a probation user may invoke: the static
     * {@link #ALLOWED} set followed by the operator-enabled asset-command
     * names. This is the one source the welcome reply, the rejection
     * reply, and {@code /help}'s probation listing all render from, so the
     * three surfaces cannot drift (M1-590). The predicate
     * {@link #allowedDuringProbation} accepts exactly this set (static
     * names OR the asset family), so the list and the gate stay in lockstep.
     */
    public List<String> probationAllowedCommandNames() {
        List<String> names = new ArrayList<>(ALLOWED);
        names.addAll(assetCommandFamilyOracle.enabledAssetCommandNames());
        return names;
    }

    /**
     * The {@link #probationAllowedCommandNames()} list rendered as a
     * comma-separated, slash-prefixed string ({@code "/help, /status, …"})
     * for the welcome and rejection replies (which show a single inline
     * list). {@code /help} renders its own per-command line form from the
     * same {@link #probationAllowedCommandNames()} source — the display
     * format differs, the underlying command set does not.
     */
    public String renderProbationCommandList() {
        StringBuilder rendered = new StringBuilder();
        for (String name : probationAllowedCommandNames()) {
            if (rendered.length() > 0) {
                rendered.append(", ");
            }
            rendered.append('/').append(name);
        }
        return rendered.toString();
    }
}
