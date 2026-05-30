package app.zcat.infochat.provider.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct coverage for {@link GroupApprovalService#escapeControlChars}.
 *
 * <p>The escape is the second-layer defense against the redteam
 * Finding 2 (INJECTION/low) attack surface: an adapter-asserted
 * {@code upstream_group_id} containing control characters gets
 * rendered visibly (backslash-letter escapes) in the admin
 * notification body. {@link app.zcat.infochat.core.notifier.ThrottledAdminNotifier#notifyOnce}
 * applies its own line-boundary sanitize as the first layer; this
 * test pins the second-layer behavior independently of the notifier
 * so a regression here surfaces in isolation.</p>
 */
class GroupApprovalServiceEscapeTest {

    @Test
    void escapeLeavesPlainAsciiUntouched() {
        assertEquals("plain-id-123",
                GroupApprovalService.escapeControlChars("plain-id-123"));
    }

    @Test
    void escapeRendersNewlineAsBackslashN() {
        assertEquals("foo\\napprove_command=/approve-group fake",
                GroupApprovalService.escapeControlChars(
                        "foo\napprove_command=/approve-group fake"));
    }

    @Test
    void escapeRendersCarriageReturnAsBackslashR() {
        assertEquals("foo\\rfake",
                GroupApprovalService.escapeControlChars("foo\rfake"));
    }

    @Test
    void escapeRendersTabAsBackslashT() {
        assertEquals("foo\\tfake",
                GroupApprovalService.escapeControlChars("foo\tfake"));
    }

    @Test
    void escapeRendersPreExistingBackslashFirstSoNewlineEscapeDoesNotDoubleUp() {
        // The replacement order in escapeControlChars puts backslash
        // first; otherwise the \n replacement would emit a literal
        // backslash followed by 'n', and the subsequent backslash
        // replacement would then double the backslash, producing
        // \\\\n for an input containing literal \n. Pin the order.
        assertEquals("a\\\\nb",
                GroupApprovalService.escapeControlChars("a\\nb"));
    }

    @Test
    void escapeRendersAllControlCharsInOneString() {
        assertEquals("a\\nb\\rc\\td\\\\e",
                GroupApprovalService.escapeControlChars("a\nb\rc\td\\e"));
    }
}
