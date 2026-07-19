---
name: evalqueueproducer-javadoc-nit
description: "EvalQueueProducer.hasDownstreamRequests() javadoc still says 'before its first emit' but M1-551 made the gate per-emit — fold the one-line fix into the next ticket touching that file"
metadata: 
  type: project
---

`infochat-collector/.../outbox/EvalQueueProducer.java`: the
`hasDownstreamRequests()` javadoc says the rehydrator "polls this before
its first emit", but M1-551 (merged 2026-07-04, commit 1a842496) extended
the gate to EVERY emit. The M1-551 round-1 reviewer flagged this as an
informational nit, not rework-forcing; it was left unfixed deliberately to
keep the reviewed diff identical to the committed diff.

**Why:** a stale comment misdescribing the gate granularity could mislead
a future reader debugging backpressure; too small to justify its own
ticket.

**How to apply:** when a ticket next touches `EvalQueueProducer.java`,
include the one-line javadoc correction in its scope (name it in the
ticket so the reviewer sees it authorized). Do not fix it inline from an
unrelated diff (surgical-changes rule). Delete this memory once fixed.
