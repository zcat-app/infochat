package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.MessagingAdapter;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the outbound chokepoint routing invariant: the spec claim that
 * every outbound body has its {@code ](} adjacency broken before the
 * transport ({@code docs/spec/security.md} §"Sanitizer output never
 * contains {@code ](}") is true ONLY because every outbound path routes
 * through {@link OutboundDelivery}'s entry points. Nothing in the type
 * system forces that routing — a future caller that invokes
 * {@code adapter.send} directly would bypass the break invisibly (no WARN,
 * no audit row). This test fails the build in that case, making the
 * invariant structural rather than a convention enforced by census.
 *
 * <p>The allowlist is exactly two classes:
 * <ul>
 *   <li>{@link OutboundDelivery} — the chokepoint itself.</li>
 *   <li>{@code DigestDelivery.RecordingAdapter} — a decorator invoked BY
 *       {@code OutboundDelivery.deliverSequenceToGroup}, so its delegated
 *       call is inside the chokepoint, not around it.</li>
 * </ul>
 *
 * <p>Production classes only: {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS}
 * drops the {@code test-classes/} root, so test doubles that implement
 * {@link MessagingAdapter} ({@code FailingMessagingAdapter},
 * {@code RecordingMessagingAdapter}, {@code ScriptedAdapter}) and tests
 * that drive an adapter through {@link OutboundDelivery} are never
 * imported, never mind flagged.
 *
 * <p><b>Detection model — what this guard does and does not catch.</b>
 * A call site is matched by {@code Method name in {send, update,
 * finalizeMessage, sendAttachment} AND target owner assignable to
 * MessagingAdapter}, evaluated
 * over BOTH direct calls ({@code adapter.send(msg)}) and method references
 * ({@code adapter::send}); a future caller that types its receiver as a
 * concrete adapter subtype (e.g. {@code SimpleXAdapter}) or a sub-interface,
 * or that hands {@code adapter::send} to a retry helper / executor, still
 * resolves to a MessagingAdapter-supertype edge and is flagged. The guard
 * catches the accidental shapes — a provider class that calls the adapter
 * directly or passes a method reference — which is how a future bypass would
 * realistically land; it is NOT totality. Residual routes that leave no edge
 * the provider-scoped scan sees are accepted as documented residual risk
 * (redteam 2026-07-26): <b>helper indirection</b> (a static helper in another
 * module wrapping {@code adapter.send}, called from provider — an
 * interprocedural shape a static edge guard cannot trace), <b>sibling-module
 * senders</b> (a class outside the provider calling the adapter directly — see
 * the module-scope note below), <b>reflective invocation</b>
 * ({@code Method.invoke} / {@code MethodHandle} / dynamic proxy — deliberate
 * evasion, outside the threat model's external-adversary scope; the codebase
 * is trusted-reviewed), a <b>body-delivering overload reusing a non-body
 * method name</b> (the drift check classifies the SPI by method name, so an
 * overload like {@code setTyping(ContactId, OutboundDraft)} inherits the
 * existing non-body name's classification — low severity, since the common
 * case of a genuinely new method name IS caught), and <b>direct transport
 * access bypassing the SPI</b> (provider code opening its own socket to the
 * transport — deliberate/grossly-negligent, not accidental).
 *
 * <p><b>Module scope.</b> The guard imports {@code app.zcat.infochat.provider..}
 * only: the spec claim is scoped to "provider main class". A sibling-module
 * sender is therefore a documented residual, bounded today by the module DAG:
 * collector, llm-adapter, ssrf, and messaging-adapter are enforcer-blocked
 * from depending on infochat-messaging-adapter, though infochat-core's edge is
 * convention-only (no enforcer rule) and tracked as a follow-up. If the DAG
 * ever widens, the guard's package scope must widen with it (redteam
 * 2026-07-26, opencode).
 */
class OutboundChokepointArchTest {

    private static final Set<String> ALLOWED_CALLERS = Set.of(
            OutboundDelivery.class.getName(),
            "app.zcat.infochat.provider.digest.DigestDelivery$RecordingAdapter");

    // sendAttachment delivers an outbound payload whose callers must keep the
    // chokepoint's retry/attribution/metrics obligations (analysis P23;
    // M1-801 wires the path through OutboundDelivery).
    private static final Set<String> GUARDED_METHODS =
            Set.of("send", "update", "finalizeMessage", "sendAttachment");

    /**
     * The {@link MessagingAdapter} methods that do NOT deliver or modify an
     * outbound message body, so they are out of the guard's scope. Every
     * declared method of {@code MessagingAdapter} MUST appear in exactly one of
     * {@link #GUARDED_METHODS} or {@code NON_BODY_METHODS}: the
     * {@code spiSurfaceIsFullyClassified} check fails the build if the SPI
     * grows a method that is in neither, so a new outbound-body method cannot
     * bypass the guard silently — the same shape as the sanitizer match-set
     * derivation's CI check (redteam 2026-07-26, finding #3).
     */
    private static final Set<String> NON_BODY_METHODS = Set.of(
            "name", "capabilities", "trustLevel", "isWellFormedContactId",
            "canonicalizeContactId", "setTyping", "setInboundHandler",
            "setMembershipEventHandler", "setGroupInvitationHandler", "joinGroup",
            "connectContact", "start", "stop", "supervisorTerminallyFailed",
            "droppedInboundCount", "connected", "inboundQueueDepth", "bindMetrics");

    @Test
    void spiSurfaceIsFullyClassified() {
        var declared = new TreeSet<String>();
        for (java.lang.reflect.Method m : MessagingAdapter.class.getDeclaredMethods()) {
            declared.add(m.getName());
        }
        var classified = new TreeSet<String>();
        classified.addAll(GUARDED_METHODS);
        classified.addAll(NON_BODY_METHODS);
        assertEquals(declared, classified,
                "MessagingAdapter's declared methods (" + declared
                        + ") no longer match guarded (" + GUARDED_METHODS
                        + ") plus non-body (" + NON_BODY_METHODS
                        + "). Classify each new method as body-delivering "
                        + "(add to GUARDED_METHODS, which makes the chokepoint "
                        + "guard flag its callers) or non-body (add to "
                        + "NON_BODY_METHODS) so the guard cannot drift silently.");
    }

    @Test
    void onlyChokepointClassesCallMutatingMessagingAdapterMethods() throws Exception {
        var classes = new ClassFileImporter(
                List.of(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS))
                .importPackages("app.zcat.infochat.provider..");

        // Sanity: the guard is meaningless if ArchUnit's bundled ASM could not
        // read the bytecode. ArchUnit logs a per-class WARN and SILENTLY DROPS
        // the unreadable class, which would make the assertion below pass
        // vacuously for exactly the dropped classes (a false green). Asserting
        // the imported production-class count equals the .class files on disk
        // catches both a catastrophic ASM/JDK mismatch (drops everything) and a
        // partial drop (some classes unreadable while the two allowlisted ones
        // survive). The count is derived from the production-classes root on the
        // classpath, not the working directory, so it is fork-CWD-independent.
        var importedNames = new TreeSet<String>();
        for (JavaClass c : classes) {
            importedNames.add(c.getName());
        }
        int onDisk = countProductionClassFiles();
        assertEquals(onDisk, importedNames.size(),
                "ArchUnit imported " + importedNames.size() + " provider classes but "
                        + onDisk + " .class files are on disk — the bundled ASM likely "
                        + "cannot read some class files (look for 'Unsupported class file "
                        + "major version' WARNs). A dropped class is invisible to the "
                        + "guard, so the build must fail rather than ship a false green.");

        // Belt-and-braces: even with equal counts, name the allowlisted classes
        // explicitly in the failure message if they are somehow absent.
        var missing = new TreeSet<>(ALLOWED_CALLERS);
        missing.removeAll(importedNames);
        assertTrue(missing.isEmpty(),
                "The allowlisted chokepoint classes " + missing + " were not imported, "
                        + "so the invariant cannot be evaluated. See the count mismatch "
                        + "diagnostic above if ASM failed to read the bytecode.");

        var violators = new TreeSet<String>();
        for (JavaClass caller : classes) {
            // Direct calls: adapter.send(msg) / simplex.send(msg) / ...
            for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                var t = call.getTarget();
                collectViolator(caller.getName(), t.getOwner(), t.getName(),
                        t.getFullName(), call.getOrigin().getFullName(),
                        "call", violators);
            }
            // Method references: adapter::send / simplex::finalizeMessage / ...
            // A method reference compiles to an invokedynamic whose target
            // lives in the bootstrap-method table; javac emits NO direct
            // invoke-edge to MessagingAdapter.send, so getMethodCallsFromSelf
            // never sees it (verified empirically against ArchUnit 1.4.2:
            // DigestCategorizer's Integer::sum reports 0 call-edges but 2
            // reference-edges). ArchUnit exposes these as JavaMethodReference.
            // This is NOT the reflective hole documented above — a method
            // reference is the idiomatic way to hand adapter.send to a retry
            // helper or executor, i.e. exactly the accidental seventh call site
            // the guard exists to catch, so it is in scope.
            for (JavaMethodReference ref : caller.getMethodReferencesFromSelf()) {
                var t = ref.getTarget();
                collectViolator(caller.getName(), t.getOwner(), t.getName(),
                        t.getFullName(), ref.getOrigin().getFullName(),
                        "method-reference", violators);
            }
        }

        assertTrue(violators.isEmpty(),
                "MessagingAdapter.send/update/finalizeMessage/sendAttachment must be "
                        + "called only by OutboundDelivery or DigestDelivery.RecordingAdapter "
                        + "(the outbound chokepoint); any other caller bypasses the "
                        + "spec's ]( adjacency-break guarantee. Violators: " + violators);
    }

    /**
     * Records a violator if the given target method is one of the guarded
     * mutating {@link MessagingAdapter} methods reachable from a caller that is
     * not on the allowlist. {@code kind} annotates whether the edge was a
     * direct call or a method reference, so a future bypass is diagnosable.
     * Matching is by method name + owner assignability, NOT by constant-pool
     * owner name: ArchUnit's owner is the bytecode receiver (the static
     * declared type), which for a call/reference written against a concrete
     * adapter subtype (e.g. SimpleXAdapter) is that subtype, not
     * MessagingAdapter; {@code isAssignableTo} resolves the subtype to its
     * MessagingAdapter supertype so a concrete-typed receiver is flagged just
     * like an interface-typed one.
     */
    private static void collectViolator(String callerName, JavaClass targetOwner,
            String targetName, String targetFullName, String originFullName,
            String kind, Set<String> violators) {
        if (!GUARDED_METHODS.contains(targetName)) {
            return;
        }
        if (!targetOwner.isAssignableTo(MessagingAdapter.class)) {
            return;
        }
        if (!ALLOWED_CALLERS.contains(callerName)) {
            violators.add(callerName + " -> " + targetFullName
                    + " (from " + originFullName + ", " + kind + ")");
        }
    }

    /**
     * Counts the {@code .class} files under the provider module's production
     * classes root ({@code target/classes}), located via a production class's
     * own resource URL so the result does not depend on the test fork's
     * working directory.
     */
    private static int countProductionClassFiles() throws Exception {
        String resPath = "app/zcat/infochat/provider/messaging/OutboundDelivery.class";
        URI uri = OutboundChokepointArchTest.class.getClassLoader()
                .getResource(resPath).toURI();
        Path classFile = Paths.get(uri);
        Path classesRoot = classFile;
        for (String segment : resPath.split("/")) {
            classesRoot = classesRoot.getParent();
        }
        try (var stream = Files.walk(classesRoot)) {
            return (int) stream.filter(p -> p.toString().endsWith(".class"))
                    .count();
        }
    }
}
