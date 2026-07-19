---
name: handler-input-not-always-normalized
description: Command handlers can receive UN-normalized text (fenced-code carve-out), so a handler-side check must normalize the value itself, not trust the router.
metadata:
  type: project
---

**A command handler cannot assume its input was NFKC-normalized.**
`InboundRouter.normalize` works PER LINE and appends fence-opener and in-fence
lines **verbatim** (the spec's deliberate fenced-code carve-out), while the
command-vs-chat routing decision reads only the **whole body's first character**
(`normalized.startsWith("/")`). So a message whose line 1 is `/add-source ...`
dispatches as a command while a fence opened on line 2 leaves line 3
un-normalized — and `CommandTokenizer` will swallow those newlines into a single
quoted argument.

**Why:** any handler-side validation justified by "the router already
normalized this" is unsound on that path. Concretely (M1-659 r3 audit): U+FF0F
FULLWIDTH SOLIDUS survived an ASCII-`/` check inside a fence, was stored and
echoed, and folded to a real `/` when a bot admin pasted the reply back
unfenced — an executable `/grant-admin`.

**How to apply:** normalize at the check (`Normalizer.normalize(v, NFKC)`) so
correctness depends on nothing upstream. Two companion lessons from the same
ticket, both cheap to re-derive wrongly:
- **Never enumerate blank-rendering codepoints.** A character-CATEGORY predicate
  cannot decide "does this slash open a word" — BOTH sides of any partition
  contain blanks: the reject side fell to U+2800 (`OTHER_SYMBOL`), the accept
  side to the Hangul fillers U+115F/U+1160/U+3164 (`OTHER_LETTER`). Two
  successive fixes of the same *shape* is the signal the shape is wrong; go
  absolute (ban the character) rather than tune the predicate a third time.
- **Code review is weak evidence on Unicode/normalization claims.** In M1-659
  the reviewer APPROVEd two separate bypasses, and in round 4 explicitly
  examined the fenced-code case and got it BACKWARDS (it assumed a fence would
  route the message to chat mode). All three real bypasses were found by
  `/redteam`, never by review. Tell the next reviewer when a prior round's
  conclusion was falsified, or it inherits it.

Related: [[outbound-reflection-guard-closed]], [[redteam-remediation-needs-reaudit]],
[[verify-subagent-quotes-before-pinning]].
