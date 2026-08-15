package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.tool.QueryAnchorTranslator;
import app.zcat.infochat.provider.image.ComfyUIClient;
import app.zcat.infochat.provider.image.ImagePreviewGenerator;
import app.zcat.infochat.provider.image.ImageSpool;
import app.zcat.infochat.provider.image.PngMetadataStrip;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The {@code /image} command handler (D73/D75/D76; commands.md §Content,
 * docs/design/future/image-generation.md): gates in spec order with the
 * flag gate before charging, D35-interruptible, D76 refund boundary. */
@ApplicationScoped
public class ImageCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageCommandHandler.class);

    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    InboundContext inboundContext;

    @Inject
    DataSource dataSource;

    @Inject
    ImageCreditGate imageCreditGate;

    @Inject
    ComfyUIClient comfyUIClient;

    @Inject
    ImageSpool imageSpool;

    @Inject
    ImagePreviewGenerator imagePreviewGenerator;

    @Inject
    OutboundDelivery outboundDelivery;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    QueryAnchorTranslator queryAnchorTranslator;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    StageProgressNotifier progressNotifier;

    /** D73 config gate: unset base-url, no command. */
    @ConfigProperty(name = "infochat.image.base-url")
    Optional<String> imageBaseUrl;

    /** The profile-driven prompt length cap — rejected OVER CAP BEFORE
     * ANY GATE runs (the bound lives at the parser). */
    @ConfigProperty(name = "infochat.image.prompt-max-chars", defaultValue = "500")
    int promptMaxChars;

    /** Server-side output-size ceiling bounding the strip's IHDR check on
     * every output (and the parser's {@code --resolution} check). */
    @ConfigProperty(name = "infochat.image.max-output-pixels", defaultValue = "5000000")
    long maxOutputPixels;

    /** Server-side output-size floor bounding the parser's {@code --resolution} check. */
    @ConfigProperty(name = "infochat.image.min-output-pixels", defaultValue = "16384")
    long minOutputPixels;

    /** The per-model steady-state seconds the setup step seeds from the
     * container re-measurement; unset → position shown without an ETA. */
    @ConfigProperty(name = "infochat.image.steady-state-seconds")
    Optional<Double> steadyStateSeconds;

    @Override
    public String name() {
        return "image";
    }

    @Override
    public @Nullable OutboundMessage handle(ScopeRef scope, String rawText) {
        String language = inboundContext.effectiveLanguage();

        // D73 runtime gating: an absent feature behaves as absent — the
        // router's own unknown-command body, byte for byte.
        if (imageBaseUrl.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND, language));
        }

        ImageCommandParser.ParseResult parsed =
                ImageCommandParser.parse(rawText, promptMaxChars, maxOutputPixels, minOutputPixels);
        if (parsed instanceof ImageCommandParser.Failure failure) {
            return reply(scope, format(failure.bundleKey(), failure.interpolationArgs().toArray()));
        }
        ImageCommandParser.Success success = (ImageCommandParser.Success) parsed;
        String prompt = success.prompt();
        Optional<ImageCommandParser.Resolution> resolution = success.resolution();

        String adapterName = inboundContext.adapterName();
        Optional<UUID> actorOpt = lookupUserId(adapterName, inboundContext.senderContactId());
        if (actorOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, language));
        }
        UUID actorId = actorOpt.get();
        String scopeKind = EligiblePostQuery.scopeKindOf(scope);
        Optional<UUID> scopeIdOpt = resolveScopeId(scope, actorId, adapterName);
        if (scopeIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, language));
        }
        UUID scopeId = scopeIdOpt.get();

        // P2: the capability flag is STATIC — check it before charging, so
        // an undeliverable generation never consumes a credit (D76).
        MessagingAdapter adapter = resolveAdapter(adapterName);
        if (!adapter.capabilities().supportsOutboundAttachments()) {
            return reply(scope, bundleLoader.get(BundleKeys.IMAGE_ERROR_NO_ATTACHMENT_SUPPORT, language));
        }

        // Slot-before-bucket: adopt the D35 turn (the QUEUED→RUNNING
        // transition /stop needs) before any gate draws, so a rejection
        // consumes nothing.
        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(actorId, scopeKind, scopeId);
        if (slot == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT, language));
        }
        try {
            if (slot.isCancelled()) {
                progressNotifier.complete(scope, stoppedTerminal(language));
                return null;
            }
            UUID groupId = scope instanceof ScopeRef.Group ? scopeId : null;

            ImageCreditGate.GateResult gateResult = imageCreditGate.charge(actorId, groupId);
            if (gateResult instanceof ImageCreditGate.Rejected rejected) {
                return reply(scope, format(rejected.bundleKey(), rejected.interpolationArgs().toArray()));
            }

            int queueDepth;
            try {
                queueDepth = comfyUIClient.queueDepth();
            } catch (ComfyUIClient.BreakerOpenException e) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, "failed");
                return reply(scope, bundleLoader.get(BundleKeys.IMAGE_ERROR_BREAKER_OPEN, language));
            } catch (ComfyUIClient.UnreachableException e) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, "failed");
                return reply(scope, bundleLoader.get(BundleKeys.IMAGE_ERROR_BACKEND_UNREACHABLE, language));
            } catch (IOException e) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, "failed");
                return reply(scope, bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED, language));
            } catch (InterruptedException e) {
                imageCreditGate.refund(actorId, groupId);
                Thread.currentThread().interrupt();
                writeAuditRow(actorId, scopeId, "stopped");
                progressNotifier.complete(scope, stoppedTerminal(language));
                return null;
            }
            if (imageCreditGate.queueOverBudget(queueDepth)) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, "failed");
                return reply(scope, queueBusyBody(queueDepth, language));
            }

            return runGeneration(scope, language, prompt, resolution, actorId, scopeKind, scopeId,
                    groupId, adapter, queueDepth, slot);
        } finally {
            inFlightTracker.release(actorId, scopeKind, scopeId, slot);
            slot.releaseWorker();
        }
    }

    /** The committed attempt (translation leg → job → strip → spool → deliver
     * → sanitized echo → content-free audit); self-delivered: every terminal
     * lands on the placeholder and the method returns null. */
    private @Nullable OutboundMessage runGeneration(
            ScopeRef scope, String language, String prompt,
            Optional<ImageCommandParser.Resolution> resolution, UUID actorId,
            String scopeKind, UUID scopeId, @Nullable UUID groupId,
            MessagingAdapter adapter, int queueDepth,
            InFlightTracker.CancellationHandle slot) {
        boolean delivered = false;
        String outcome = "failed";
        try {
            progressNotifier.publish(scope, ProgressStage.STARTED);

            String englishPrompt = prompt;
            if (!language.equalsIgnoreCase("en")) {
                progressNotifier.publish(scope, ProgressStage.TRANSLATING);
                // Reused unchanged: en no-op, failure-fallback ships the
                // original prompt (degraded adherence, not an error).
                englishPrompt = queryAnchorTranslator.translate(prompt, language, scopeKind, scopeId);
            }
            progressNotifier.publishStageText(scope, generatingBody(queueDepth));

            byte[] png;
            try {
                // --resolution IS the output contract: the target reaches the
                // serializer as per-job latent/fit dims (design Final decision 7).
                png = resolution.isEmpty()
                        ? comfyUIClient.generate(englishPrompt)
                        : comfyUIClient.generate(englishPrompt,
                                resolution.get().width(), resolution.get().height());
            } catch (ComfyUIClient.JobCancelledException e) {
                if (!e.jobStarted()) {
                    imageCreditGate.refund(actorId, groupId);
                }
                outcome = "stopped";
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope, stoppedTerminal(language));
                delivered = true;
                return null;
            } catch (ComfyUIClient.JobTimeoutException e) {
                if (!e.jobStarted()) {
                    imageCreditGate.refund(actorId, groupId);
                }
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_TIMEOUT, language));
                delivered = true;
                return null;
            } catch (ComfyUIClient.BreakerOpenException e) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_BREAKER_OPEN, language));
                delivered = true;
                return null;
            } catch (ComfyUIClient.GraphRejectedException e) {
                // The backend refused BEFORE any job ran — refund (D76).
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED, language));
                delivered = true;
                return null;
            } catch (ComfyUIClient.UnreachableException e) {
                imageCreditGate.refund(actorId, groupId);
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_BACKEND_UNREACHABLE, language));
                delivered = true;
                return null;
            } catch (InterruptedException e) {
                // /stop inside submit: no prompt id, job fate unreadable —
                // conservatively started, NO refund (D76 refunds only what
                // is KNOWN never to have run; design-notes refund table).
                Thread.currentThread().interrupt();
                outcome = "stopped";
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope, stoppedTerminal(language));
                delivered = true;
                return null;
            } catch (IOException e) {
                if (slot.isCancelled() || Thread.currentThread().isInterrupted()) {
                    outcome = "stopped";
                    writeAuditRow(actorId, scopeId, outcome);
                    progressNotifier.complete(scope, stoppedTerminal(language));
                    delivered = true;
                    return null;
                }
                // An over-cap fetch or a transport failure AFTER the job
                // started: the GPU ran — no refund (D76).
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED, language));
                delivered = true;
                return null;
            }

            byte[] stripped;
            try {
                stripped = PngMetadataStrip.strip(png, maxOutputPixels);
            } catch (PngMetadataStrip.InvalidPngException e) {
                // Loud, content-free: the message is structural-only
                // (dimensions, offsets, bound — D75), so it is safe to log.
                SafeLog.warn(log, Objects.requireNonNull(e.getMessage(),
                        "ImageCommandHandler: InvalidPngException without a message"), e);
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED, language));
                delivered = true;
                return null;
            }

            // M1-842 P2: the decode input is the STRIPPED bytes — the
            // strip's IHDR pixel bound is what bounds the preview raster.
            // M1-842 P10: a null preview degrades to the plain file form,
            // never a failure.
            String imagePreview = imagePreviewGenerator.generate(stripped, maxOutputPixels);

            String fileName = "image-" + UUID.randomUUID() + ".png";
            Path spoolFile;
            try {
                spoolFile = imageSpool.write(fileName, stripped);
            } catch (IOException e) {
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED, language));
                delivered = true;
                return null;
            }

            // The Provider refuses over-ceiling payloads pre-invocation
            // (messaging.md §Required SPI surface); the chokepoint's own
            // ceiling gate stays as backstop; no refund — the GPU ran (D76).
            if (stripped.length > adapter.capabilities().maxOutboundAttachmentBytes()) {
                imageSpool.delete(spoolFile.toString());
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_ATTACHMENT_OVER_LIMIT, language));
                delivered = true;
                return null;
            }

            OutboundAttachment attachment = new OutboundAttachment(
                    scope, spoolFile.toString(), "image/png", fileName,
                    UUID.randomUUID().toString(), imagePreview);
            if (!outboundDelivery.deliverAttachment(adapter, attachment, imageSpool, groupId)) {
                writeAuditRow(actorId, scopeId, outcome);
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_SEND_FAILED, language));
                delivered = true;
                return null;
            }

            outcome = "delivered";
            writeAuditRow(actorId, scopeId, outcome);
            // The echo is attacker-influenced text in the bot's voice:
            // sanitize with the echo field ALONE as the redaction unit,
            // then interpolate (P6).
            String sanitizedEcho = llmOutputSanitizer.sanitize(englishPrompt);
            progressNotifier.complete(scope, format(BundleKeys.IMAGE_REPLY_ECHO, sanitizedEcho));
            delivered = true;
            return null;
        } catch (RuntimeException e) {
            if (slot.isCancelled()) {
                progressNotifier.complete(scope, stoppedTerminal(language));
            } else {
                SafeLog.error(log, "ImageCommandHandler generation failed", e);
                progressNotifier.fail(scope);
            }
            delivered = true;
            return null;
        } finally {
            if (!delivered) {
                progressNotifier.fail(scope);
            }
        }
    }

    /** The GENERATING stage string: queue position plus, iff the per-model
     * constant is configured, the coarse ETA (position + 1) × constant;
     * unset constant → position without an ETA, never a lie. */
    private String generatingBody(int queueDepth) {
        int position = queueDepth + 1;
        if (steadyStateSeconds.isEmpty()) {
            return format(BundleKeys.IMAGE_PROGRESS_GENERATING_NO_ETA, Integer.toString(position));
        }
        long etaSeconds = Math.round(position * steadyStateSeconds.get());
        return format(BundleKeys.IMAGE_PROGRESS_GENERATING_ETA,
                Integer.toString(position), Long.toString(etaSeconds));
    }

    /** The queue-depth refusal with the same coarse backlog estimate. */
    private String queueBusyBody(int queueDepth, String language) {
        if (steadyStateSeconds.isEmpty()) {
            return bundleLoader.get(BundleKeys.IMAGE_ERROR_QUEUE_BUSY_NO_ETA, language);
        }
        long backlogSeconds = Math.max(1, Math.round(queueDepth * steadyStateSeconds.get()));
        return format(BundleKeys.IMAGE_ERROR_QUEUE_BUSY, Long.toString(backlogSeconds));
    }

    /** The content-free IMAGE_GENERATE row (D75): actor, scope, outcome —
     * never the prompt, never a hash. Parks an armed interrupt across the
     * write (the M1-763 pattern) so a stopped turn's audit still lands. */
    private void writeAuditRow(UUID actorId, UUID scopeId, String outcome) {
        boolean interrupted = Thread.interrupted();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .actorUserId(actorId)
                        .actorContactId(inboundContext.senderContactId())
                        .actorAdapter(inboundContext.adapterName())
                        .action(AuditAction.IMAGE_GENERATE)
                        .targetKind(TargetKind.USER)
                        .targetId(actorId.toString())
                        .scopeId(scopeId)
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"outcome\":\"" + outcome + "\"}")
                        .build();
                auditLogWriter.write(conn, row);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ImageCommandHandler.writeAuditRow failed for user=" + actorId, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private MessagingAdapter resolveAdapter(String adapterName) {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        throw new IllegalStateException(
                "ImageCommandHandler: no activated adapter named '" + adapterName + "'");
    }

    private Optional<UUID> resolveScopeId(ScopeRef scope, UUID actorId, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(actorId);
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId());
        };
    }

    private Optional<UUID> lookupUserId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ImageCommandHandler.lookupUserId failed", e);
        }
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ImageCommandHandler.lookupGroupId failed", e);
        }
    }

    private String stoppedTerminal(String language) {
        return bundleLoader.get(BundleKeys.PROGRESS_STOPPED, language);
    }

    private String format(String bundleKey, Object... args) {
        String template = bundleLoader.get(bundleKey, inboundContext.effectiveLanguage());
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
