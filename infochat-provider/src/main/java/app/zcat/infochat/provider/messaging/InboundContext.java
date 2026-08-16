package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.ChatAgent;
import app.zcat.infochat.provider.chat.ChatReplyMode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-inbound-dispatch context bean. Carries the originating
 * adapter's {@link app.zcat.infochat.messaging.MessagingAdapter#name()
 * name} from the router into any downstream collaborator that needs to
 * qualify a per-actor lookup. The {@code CommandHandler} SPI signature
 * stays at {@code handle(ScopeRef, String)} so handlers that do NOT
 * need the adapter (e.g. {@code /help}) remain adapter-agnostic; the
 * two handlers that consult the {@code users} table — which is keyed
 * by the V5 {@code (adapter, contact_id)} UNIQUE constraint — read
 * the adapter through {@link #adapterName()}.
 *
 * <p><b>Why a CDI request-scope bean rather than a SPI parameter or
 * a ThreadLocal.</b> Adding a third parameter to
 * {@code CommandHandler.handle} would ripple through every current
 * and future handler regardless of whether it needs the adapter.
 * Extending {@code ScopeRef.Dm} would push adapter identity into a
 * shared messaging-adapter SPI type that intentionally stays minimal
 * (decisions D46-era SPI freeze). A ThreadLocal would be fragile on
 * Quarkus' virtual-thread + reactive dispatch — request-scope is the
 * framework-blessed propagation mechanism for per-dispatch data.
 *
 * <p><b>Concurrency.</b> CDI's request-scope semantics give each
 * inbound dispatch its own context instance; concurrent inbound from
 * different adapters cannot collide on the {@code adapterName} field.
 * The router sets the field once on entry to {@code onMessage} before
 * any handler dispatch runs (see {@link InboundRouter#onMessage}).
 */
@RequestScoped
public class InboundContext {

    // Both fields are set by InboundRouter.onMessage before any handler
    // reads them (request-scoped, one instance per inbound dispatch); the
    // field-init check cannot see that cross-method guarantee.
    @SuppressWarnings("NullAway.Init")
    private String adapterName;
    @SuppressWarnings("NullAway.Init")
    private String senderContactId;
    // A stable identity for this one inbound dispatch — one dispatch = one
    // "operation" (a chat turn, a /summary). StageProgressNotifier keys its
    // per-operation progress state by this so two operations publishing
    // concurrently into the SAME scope (two users' chat turns in one approved
    // group, or a chat turn alongside /summary) never share a placeholder and
    // never finalize each other's message (M1-611). Distinct from
    // senderContactId because the same user can drive two concurrent
    // operations in one scope, which must still be told apart. Request-scoped,
    // so each dispatch gets a fresh value at construction; the queued
    // interruptible path re-seeds it across the dispatch hop — see
    // setOperationId (M1-635).
    private String operationId = UUID.randomUUID().toString();
    // Eagerly defaulted (not router-set) because some reply paths
    // legitimately fire before any language is resolvable — see
    // effectiveLanguage().
    private String effectiveLanguage = "en";
    // The RESOLVED chat-reply pipeline mode for this dispatch (D79), cached
    // at intake so it never flips mid-turn; ChatAgent reads it like
    // effectiveLanguage. Eagerly TRANSLATE (the deployment default).
    private ChatReplyMode replyMode = ChatReplyMode.TRANSLATE;

    // The chat turn's deferred persistence step, stashed by the chat
    // dispatch (InboundRouter.dispatchChat) and run by onMessage ONLY
    // after the reply is delivered. Request-scoped so it cannot leak
    // across dispatches; null on every non-chat reply path. Carried here
    // rather than threaded as a method-local because the chat dispatch
    // seam (dispatchChat) returns only the reply string — its signature
    // is pinned by InboundRouterAcquisitionCountTest's override.
    private ChatAgent.@Nullable PendingCommit pendingChatCommit;

    // Request-end cleanup hooks, keyed by the scope they act on so a scope
    // touched by several publishes within one dispatch registers its drain
    // exactly once (M1-334). Run when this dispatch's request scope is
    // destroyed — see drainAbandonedProgress(). LinkedHashMap keeps the
    // drain order deterministic (registration order).
    private final Map<ScopeRef, Runnable> requestEndCleanups = new LinkedHashMap<>();

    /**
     * The originating adapter's {@code name()} for the current
     * inbound dispatch. Set by {@link InboundRouter#onMessage}
     * before any handler dispatch runs; read by handlers that
     * need to qualify a {@code users} lookup with the adapter
     * column. Returns {@code null} only if a caller invokes a
     * handler outside an inbound dispatch (test code that bypasses
     * the router must set this via {@link #setAdapterName} in setup).
     */
    public String adapterName() {
        return adapterName;
    }

    public void setAdapterName(String adapterName) {
        this.adapterName = adapterName;
    }

    /**
     * The sender's cryptographic contact id for the current inbound
     * dispatch. Available for both DM and group scope — the sender
     * always has a contact id regardless of scope shape. Handlers
     * that need to look up the calling user by
     * {@code (adapter, contact_id)} should use this instead of
     * extracting the contact id from {@link app.zcat.infochat.messaging.ScopeRef},
     * which only carries it for DM scope.
     */
    public String senderContactId() {
        return senderContactId;
    }

    public void setSenderContactId(String senderContactId) {
        this.senderContactId = senderContactId;
    }

    /**
     * A stable per-dispatch operation identity (one inbound dispatch = one
     * operation). Read by {@link StageProgressNotifier} to key its
     * per-operation progress state so two operations publishing concurrently
     * into one scope do not clobber each other's placeholder (M1-611).
     */
    public String operationId() {
        return operationId;
    }

    /**
     * Re-seed this dispatch's operation identity with a purpose-minted id
     * (M1-635). Called only by the queued interruptible stage
     * ({@link InboundRouter}'s queued dispatch variant), on the worker,
     * before any progress publish: the transport thread already opened the
     * turn's acknowledgement placeholder under this id at submit time, so
     * seeding it here makes the worker's own publishes and terminal key to
     * that placeholder instead of the constructor default minting a second,
     * unrelated one. The M1-611 distinctness invariant is preserved — the
     * seeded value is itself a UUID freshly minted for this one turn, never
     * another dispatch's id.
     */
    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    /**
     * The requester's effective scope language for the current inbound
     * dispatch (decision D43), resolved from {@code scope_preferences}
     * by {@link InboundRouter#onMessage} as soon as the scope's id is
     * known — the user's UUID for DM right after the users-row
     * snapshot gates, the group's UUID at the step-4.1 group
     * resolution. Reply paths that fire BEFORE the respective
     * resolution point (the pre-DB size cap, the invite flow for
     * contacts with no users row, group replies ahead of step 4.1)
     * read the {@code "en"} default, which is semantically correct
     * for scopes that cannot have set {@code /lang}: an unregistered
     * contact has no preferences row, and a pending or rejected group
     * never dispatches the command. Handlers and notifiers pass this
     * value to {@code BundleLoader.get(key, langCode)}.
     */
    public String effectiveLanguage() {
        return effectiveLanguage;
    }

    public void setEffectiveLanguage(String effectiveLanguage) {
        this.effectiveLanguage = effectiveLanguage;
    }

    /** The resolved chat-reply pipeline mode for this dispatch (D79), set once at intake by {@link InboundRouter#onMessage}. */
    public ChatReplyMode replyMode() {
        return replyMode;
    }

    public void setReplyMode(ChatReplyMode replyMode) {
        this.replyMode = replyMode;
    }

    /**
     * Stash the chat turn's deferred persistence step for the current
     * dispatch. Set by {@link InboundRouter#dispatchChat} when a chat
     * reply was computed successfully; left unset (null) on every other
     * reply path.
     */
    public void setPendingChatCommit(ChatAgent.PendingCommit pendingChatCommit) {
        this.pendingChatCommit = pendingChatCommit;
    }

    /**
     * Return the stashed deferred persistence step and clear it, or
     * {@code null} if no chat turn was computed this dispatch. Read by
     * {@link InboundRouter#onMessage} after the reply is delivered: a
     * non-null value is run to persist the turn and auto-compress, but
     * only when delivery succeeded.
     */
    public ChatAgent.@Nullable PendingCommit takePendingChatCommit() {
        ChatAgent.PendingCommit pending = this.pendingChatCommit;
        this.pendingChatCommit = null;
        return pending;
    }

    /**
     * Register a cleanup to run when this dispatch's request scope is
     * destroyed, keyed by {@code scope} so repeated registrations for the
     * same scope collapse to one. Used by {@link StageProgressNotifier} to
     * guarantee a progress placeholder opened during this dispatch is
     * finalized even when the handler abandons the operation without a
     * terminal {@code complete()}/{@code fail()} (M1-334).
     */
    void registerProgressCleanup(ScopeRef scope, Runnable cleanup) {
        requestEndCleanups.putIfAbsent(scope, cleanup);
    }

    /**
     * Run every registered request-end cleanup as the request scope is
     * destroyed. Bound to the CDI lifecycle rather than an explicit call in
     * the router so it fires exactly once per dispatch regardless of how the
     * handler exited — a thrown handler still destroys the request context
     * (M1-334). Each registered cleanup is itself a no-op when its scope
     * already terminated normally, so this is harmless on the common path.
     */
    @PreDestroy
    void drainAbandonedProgress() {
        for (Runnable cleanup : requestEndCleanups.values()) {
            cleanup.run();
        }
    }
}
