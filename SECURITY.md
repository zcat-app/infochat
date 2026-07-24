# Security Policy

infochat is a self-hosted application that handles private messages, user data,
and untrusted remote content. We take security reports seriously and appreciate
responsible disclosure.

## Supported versions

infochat is **pre-1.0**. Security fixes land on the `main` branch (the latest
state of the code); there are no separately maintained release branches yet.
Always run the latest `main`.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
pull requests, or discussions.** Public disclosure before a fix puts every
self-hosted deployment at risk.

Instead, report privately through **either** channel:

- **GitHub private vulnerability reporting** — go to the repository's
  **Security** tab, click **Report a vulnerability** (under "Advisories"), and
  fill in the form with the details below.
- **Email** — **zcat-app@tuta.io**.

Use email if the Security tab shows no "Report a vulnerability" button.

Please include, as far as you can:

- A description of the issue and its security impact (what an attacker can do).
- The component affected — Collector, Provider, the SSRF guard, a messaging
  adapter, the ingest / prompt-injection pipeline, authorization / admin, etc.
- Step-by-step reproduction (a proof-of-concept, request, or input that triggers
  it).
- The version / commit you tested, and your deployment shape (profile, adapters,
  local vs remote LLM).
- Any suggested fix or mitigation, if you have one.

## What to expect

This is a small project, so responses are best-effort rather than bound to a
formal SLA. We will acknowledge your report, investigate, keep you updated, and
credit you in the advisory once a fix is available — unless you prefer to remain
anonymous. Please give us a reasonable chance to ship a fix before any public
disclosure.

## Scope

The threat model that defines what infochat is built to defend against — trust
boundaries, the prompt-injection pipeline, SSRF hardening, the deterministic
authorization layer, per-(user, scope) isolation, and the per-adapter admin
threat surface — is documented in
[docs/spec/security.md](docs/spec/security.md). Reports that show a gap between
what that model promises and what the code delivers are exactly what we want.

**In scope** (examples):

- Bypassing authorization, the ban wall, slow-start probation, or the bot /
  group admin tiers.
- Cross-(user, scope) data leakage.
- SSRF or egress-guard bypass (reaching internal / loopback addresses through a
  source URL or a redirect).
- Prompt injection that escapes the ingest pipeline's containment and reaches a
  privileged action, or drives the LLM to act outside its read-only tool
  surface.
- Secret / credential exposure (API keys, DB credentials, contact ids) in logs
  or output.
- Remote code execution, SQL injection, or container escape.

**Out of scope** (examples):

- Vulnerabilities in third-party dependencies — report those upstream (we will
  still take pointers and update our pin).
- Issues that require an operator to ignore the documented hardening (for
  example, publishing PostgreSQL to `0.0.0.0` against the loopback rule in
  [SETUP_GUIDE.md](SETUP_GUIDE.md)).
- Denial of service from sheer resource exhaustion on an intentionally
  under-provisioned profile (e.g. a Raspberry Pi).
- Social engineering of operators or end users.

Thank you for helping keep infochat — and the people who self-host it — safe.
