package app.zcat.infochat.provider.messaging;

import java.util.ArrayList;
import java.util.List;

/** Ordered append-only log of collaborator method invocations. */
final class CallLog {
    final List<String> calls = new ArrayList<>();
}
