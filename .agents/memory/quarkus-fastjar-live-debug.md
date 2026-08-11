---
name: quarkus-fastjar-live-debug
description: Fast-jar transformed-bytecode.jar shadows lib/main (jar overlays silently no-op); adapter dispatch threads have foreign TCCL — @ConfigProperty at lazy ARC create is TCCL-keyed and crashes live-only.
metadata: 
  type: reference
---

Two live-debugging facts from the F-live-1/M1-543 diagnosis (2026-07-02):

1. **Jar-overlay probes don't work for transformed classes.** Bind-mounting a
   rebuilt module jar over `/app/lib/main/<jar>` in the provider image
   silently does nothing for any class that also sits in
   `/app/quarkus/transformed-bytecode.jar` — the fast-jar RunnerClassLoader
   loads those first. Check with `unzip -l transformed-bytecode.jar` before
   trusting an overlay; otherwise rebuild the image (`docker compose build
   infochat-provider`, Dockerfile uses `-DskipTests` by design).

2. **Adapter-owned dispatch threads have a foreign context classloader.**
   SimpleX's `simplex-inbound-dispatch` virtual thread is created lazily by a
   JDK-internal HttpClient thread; its TCCL has no registered MicroProfile
   Config, so any TCCL-keyed lookup (notably `@ConfigProperty` injection at
   lazy ARC bean creation) throws SmallRye "no config for classloader" —
   live-only, since `@QuarkusTest` dispatches from the JUnit thread. Fixed at
   the provider boundary by the `AdapterRegistry` classloader pin (M1-543);
   any NEW MessagingAdapter callback setter must also be wired through
   `runWithApplicationClassLoader` (redteam out-of-model note in
   the live audit). See [[simplex-live-frame-capture]],
   [[live-e2e-active-handoff]].
