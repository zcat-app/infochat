package app.zcat.infochat.messaging.impl.inmemory;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-double implementation of {@link MessagingAdapter}. Per
 * {@code docs/design/06-messaging.md} §6.6 this is a CDI-eligible bean
 * (no CDI annotations yet — added when M1-035b's AdapterRegistry
 * authors the producer) that lives in the production classpath but is
 * only activated by the test-time deployment shape
 * ({@code infochat.adapters=inmemory} exclusively per decision D46).
 *
 * <p>The adapter does no network I/O — outbound calls record the
 * {@link OutboundMessage} on an internal list and return an opaque
 * {@link MessageHandle}; {@link #deliverDm(String, String)} synthesises
 * an {@link InboundMessage} with {@link ScopeRef.Dm} + a fresh
 * {@link Identity} and synchronously dispatches it through the
 * registered {@link InboundHandler}.</p>
 *
 * <p>Thread-safety: all mutable state lives in concurrent collections
 * ({@link CopyOnWriteArrayList} for sent / typing / per-handle
 * history, {@link ConcurrentHashMap} for the handle table, an
 * {@link AtomicLong} for handle ids). Tests may dispatch from the test
 * thread while the registered handler does its own work — concurrency
 * cost is negligible at test scale and prevents a flaky test from
 * masking a real bug.</p>
 *
 * <p>The default no-arg constructor produces a
 * {@link AdapterTrustLevel#LOW} instance per design §6.6 — the
 * test-harness default that makes accidental privilege escalation
 * impossible. Tests that exercise admin paths use the secondary
 * constructor with {@link AdapterTrustLevel#HIGH}.</p>
 */
public final class InMemoryAdapter implements MessagingAdapter {

    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ true,
            /* supportsCodeFormatting     */ true,
            /* supportsMarkdownLinks      */ false,
            /* supportsMultilineCode      */ true,
            /* supportsAttachments        */ false,
            /* supportsThreading          */ false,
            /* maxMessageBytes            */ 100_000,
            /* maxInboundMessageBytes     */ 100_000,
            /* maxSendsPerSecond          */ 10_000,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ true,
            /* minEditInterval            */ Duration.ZERO);

    private final AdapterTrustLevel trustLevel;
    private final AtomicLong handleIdGen = new AtomicLong();

    private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
    private final List<TypingEvent> typingEvents = new CopyOnWriteArrayList<>();
    private final Map<String, InMemoryMessageHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, List<UpdateEvent>> history = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalized = new ConcurrentHashMap<>();
    private final Map<String, Identity> knownIdentities = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();
    private final List<MembershipEvent> membershipEvents = new CopyOnWriteArrayList<>();

    // Null until a handler registers via the setter; every read guards on
    // null (an un-attached adapter is a test-author bug, surfaced loudly).
    private volatile @Nullable InboundHandler handler;
    private volatile @Nullable MembershipHandler membershipHandler;

    /** Production-and-default constructor — declares LOW trust. */
    public InMemoryAdapter() {
        this(AdapterTrustLevel.LOW);
    }

    /**
     * Test-only secondary constructor that lets admin-path tests opt
     * into HIGH trust. Per design §6.6 the default is LOW so a test
     * forgetting to specify cannot accidentally land in an admin
     * privilege path.
     */
    public InMemoryAdapter(AdapterTrustLevel trustLevel) {
        this.trustLevel = trustLevel;
    }

    @Override
    public String name() {
        return "inmemory";
    }

    @Override
    public CapabilityFlags capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        return trustLevel;
    }

    @Override
    public MessageHandle send(OutboundMessage msg) throws MessagingException {
        long id = handleIdGen.incrementAndGet();
        MessageHandle handle = new MessageHandle("inmem-" + id);
        InMemoryMessageHandle internal = new InMemoryMessageHandle(id, msg);
        handles.put(handle.opaqueValue(), internal);
        history.put(handle.opaqueValue(), new CopyOnWriteArrayList<>(
                List.of(new UpdateEvent(msg.text(), false))));
        sent.add(msg);
        return handle;
    }

    @Override
    public void update(MessageHandle handle, String body) throws MessagingException {
        requireKnownAndOpen(handle).add(new UpdateEvent(body, false));
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) throws MessagingException {
        requireKnownAndOpen(handle).add(new UpdateEvent(body, true));
        finalized.put(handle.opaqueValue(), Boolean.TRUE);
    }

    @Override
    public void setTyping(ScopeRef scope, boolean typing) {
        typingEvents.add(new TypingEvent(scope, typing));
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        this.handler = handler;
    }

    @Override
    public void setMembershipEventHandler(MembershipHandler handler) {
        this.membershipHandler = handler;
    }

    /**
     * Test helper: synthesise a DM-scope {@link InboundMessage} from
     * the given contact id and text, and synchronously invoke the
     * registered {@link InboundHandler}. The Identity surfaced is
     * stable across calls for the same contact id (so tests that
     * verify identity stability can rely on the same instance being
     * delivered for every message with the same id).
     */
    public void deliverDm(String contactId, String text) {
        InboundHandler current = handler;
        if (current == null) {
            // Adapter boundary: a test driver dispatching to an
            // un-attached adapter is the test author's bug, not a
            // production failure mode. Surface it loudly.
            throw new IllegalStateException(
                    "InMemoryAdapter.deliverDm called before setInboundHandler");
        }
        Identity sender = knownIdentities.computeIfAbsent(
                contactId,
                cid -> new Identity(cid, cid, Instant.now()));
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Dm(contactId),
                text,
                Instant.now(),
                "inmem-msg-" + handleIdGen.incrementAndGet());
        current.onMessage(msg);
    }

    // -- Group primitives (test infrastructure) --------------------------------

    /** Register a group for subsequent member and mention operations. */
    public void createGroup(String groupId) {
        groups.putIfAbsent(groupId, ConcurrentHashMap.newKeySet());
    }

    /** Add a member to an existing group. */
    public void addMember(String groupId, String contactId) {
        Set<String> members = groups.get(groupId);
        if (members == null) {
            throw new IllegalStateException(
                    "Group not registered: " + groupId);
        }
        members.add(contactId);
    }

    /** Remove a member and fire a {@link MembershipEvent.UserLeft}. */
    public void removeMember(String groupId, String contactId) {
        Set<String> members = groups.get(groupId);
        if (members == null) {
            throw new IllegalStateException(
                    "Group not registered: " + groupId);
        }
        members.remove(contactId);
        dispatchMembershipEvent(new MembershipEvent.UserLeft(groupId, contactId));
    }

    /** Remove the bot from a group and fire a {@link MembershipEvent.BotRemoved}. */
    public void removeBot(String groupId) {
        dispatchMembershipEvent(new MembershipEvent.BotRemoved(groupId));
    }

    /**
     * Record the event, then deliver it by invoking the registered
     * {@link MembershipHandler} directly — the one membership dispatch
     * shape the SPI commits to (see {@code setMembershipEventHandler}
     * javadoc); the same shape SignalGroupHandler uses. A null handler
     * skips delivery but still records: membership-event tests may
     * assert via {@link #membershipEvents()} without registering one.
     */
    private void dispatchMembershipEvent(MembershipEvent event) {
        membershipEvents.add(event);
        MembershipHandler current = membershipHandler;
        if (current != null) {
            current.onEvent(event);
        }
    }

    /**
     * Synthesise a group-scope {@link InboundMessage} from the given
     * group, sender, and text, and synchronously invoke the registered
     * {@link InboundHandler}. Mirrors {@link #deliverDm} for groups.
     */
    public void deliverGroupMention(String groupId,
                                    String senderContactId,
                                    String text) {
        InboundHandler current = handler;
        if (current == null) {
            throw new IllegalStateException(
                    "InMemoryAdapter.deliverGroupMention called before setInboundHandler");
        }
        Identity sender = knownIdentities.computeIfAbsent(
                senderContactId,
                cid -> new Identity(cid, cid, Instant.now()));
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Group(groupId),
                text,
                Instant.now(),
                "inmem-msg-" + handleIdGen.incrementAndGet());
        current.onMessage(msg);
    }

    /** Snapshot of membership events recorded by this adapter. */
    public List<MembershipEvent> membershipEvents() {
        return List.copyOf(membershipEvents);
    }

    /** Whether a group is registered. */
    public boolean hasGroup(String groupId) {
        return groups.containsKey(groupId);
    }

    /** Snapshot of current members in a group. */
    public Set<String> groupMembers(String groupId) {
        Set<String> members = groups.get(groupId);
        if (members == null) {
            return Set.of();
        }
        return Set.copyOf(members);
    }

    // -- Existing query accessors ------------------------------------------------

    /** Snapshot of every {@link OutboundMessage} passed to {@link #send}. */
    public List<OutboundMessage> sentMessages() {
        return List.copyOf(sent);
    }

    /**
     * Snapshot of the per-handle update history (including the
     * initial send body and the finalize body when present). Order is
     * insertion order: send → update*… → finalize?.
     */
    public List<UpdateEvent> updateHistory(MessageHandle handle) {
        List<UpdateEvent> events = history.get(handle.opaqueValue());
        if (events == null) {
            return Collections.emptyList();
        }
        return List.copyOf(events);
    }

    /** Snapshot of every {@link TypingEvent} passed to {@link #setTyping}. */
    public List<TypingEvent> typingEventHistory() {
        return List.copyOf(typingEvents);
    }

    /** Reset all adapter state — test fixture isolation. */
    public void reset() {
        sent.clear();
        typingEvents.clear();
        handles.clear();
        history.clear();
        finalized.clear();
        knownIdentities.clear();
        groups.clear();
        membershipEvents.clear();
        handleIdGen.set(0);
    }

    private List<UpdateEvent> requireKnownAndOpen(MessageHandle handle) throws MessagingException {
        // Adapter boundary: handles are returned to test code that may
        // misuse them (the SPI's opacity is enforced by Javadoc, not
        // the type system). Surface the misuse as a PERMANENT
        // MessagingException so the failure mode lines up with what
        // production adapters would raise for the same misuse.
        if (!handles.containsKey(handle.opaqueValue())) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    "Unknown handle: " + handle.opaqueValue());
        }
        if (Boolean.TRUE.equals(finalized.get(handle.opaqueValue()))) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    "Handle already finalized: " + handle.opaqueValue());
        }
        // send() populates `handles` and `history` together under the same
        // key, and entries are only ever cleared together (reset()); a known,
        // open handle therefore always has a history list.
        return Objects.requireNonNull(history.get(handle.opaqueValue()));
    }

    /** Update / finalize event recorded per handle in the order applied. */
    public record UpdateEvent(String body, boolean isFinal) {}

    /** Typing on/off event recorded in the order applied. */
    public record TypingEvent(ScopeRef scope, boolean typing) {}
}
