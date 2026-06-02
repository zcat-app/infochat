#!/usr/bin/env python3
"""Lint that every REQUIRED infochat.* @ConfigProperty in a module's main Java
sources has a base declaration in that module's main application.properties.

Prevents the "test-only config key" class of bug: a key consumed by a
@ConfigProperty in main sources but declared only in
src/test/resources/application.properties boots fine under every @QuarkusTest
(which loads the test config) yet crashes the real service at startup with
NoSuchElementException — the failure mode M1-122 fixed for infochat.reeval.*.

A key is REQUIRED (must have a base declaration) unless one of the following
makes it resolvable when the key is absent — exactly MicroProfile Config's own
"won't fail startup" rules:

  * the @ConfigProperty carries a `defaultValue` attribute, or
  * the injected type is Optional<...> (an absent key yields Optional.empty()).

A "base declaration" is a non-comment, non-profile line in the main
application.properties — i.e. `infochat.foo.bar=...`, NOT
`%laptop.infochat.foo.bar=...`. A base declaration is required (rather than a
declaration under each profile) because every Quarkus config profile inherits
base keys; a key declared only under some %profile blocks would boot some
profiles and crash others.

Scope: this guard checks @ConfigProperty sites only. @Scheduled(every="{...}")
expressions also reference config keys, but they are out of this guard's scope
(M1-122 acceptance item 3 names @ConfigProperty); the keys those expressions
reference are still covered because M1-122 declares all nine infochat.reeval.*
keys (including the @Scheduled poll-interval trio) at base, and the
config-resolution test asserts every operator profile resolves them.

Usage:
  scripts/lint-config-keys.py [<module-dir> ...]

  With no arguments, scans infochat-collector. Each module dir is expected to
  contain src/main/java and src/main/resources/application.properties.

  infochat-provider is NOT scanned by default: it has an intentionally
  operator-mandatory key (infochat.adapters — the operator must choose which
  messaging adapters to enable at deploy time, so there is deliberately no
  committed base default; the §6.7 startup gates enforce it). This guard's
  premise ("a required key without a default must be declared at base") does
  not fit deploy-time-supplied keys, so provider would need a small allowlist
  before it can be scanned cleanly (follow-up). Pass `infochat-provider`
  explicitly if you want the raw report.

Exit codes:
  0 — every required infochat.* @ConfigProperty key resolves to a base
      declaration in its module's main config.
  1 — at least one required key has no base declaration (a test-only key).
  2 — a module dir is missing its java root or properties file (usage error).

The parser is regex-based (no Java AST), mirroring scripts/lint-contracts.py.
It is correct for the @ConfigProperty shapes in this codebase: field injection
(annotation on its own line, type on the next) and constructor-parameter
injection (annotation inline, type immediately after). @ConfigProperty argument
lists here contain no nested parentheses.
"""

import argparse
import re
import sys
from pathlib import Path

DEFAULT_MODULES = ["infochat-collector"]

# Capture the @ConfigProperty argument list (group 1) and the type token that
# follows the closing paren (group 2). [^)]* is safe because these annotations
# carry no nested parens. The type token allows a single generic arg
# (Optional<Path>, Map<String, String>) but not nested generics — none appear
# on an infochat.* @ConfigProperty in this codebase.
CONFIG_PROPERTY_RE = re.compile(
    r'@ConfigProperty\s*\(([^)]*)\)\s*'
    r'([A-Za-z_][\w.]*(?:\s*<[^>]*>)?)',
    re.DOTALL,
)
NAME_RE = re.compile(r'name\s*=\s*"([^"]+)"')
DEFAULT_VALUE_RE = re.compile(r'\bdefaultValue\s*=')
# A base declaration: line starts with the key (no leading % profile prefix,
# no comment marker) followed by = or :.
BASE_DECL_RE = re.compile(r'^([^%#=:\s][^=:\s]*)\s*[=:]')


def required_keys_in_java(java_root):
    """Map key -> list of "path:line" sites for required infochat.* keys."""
    sites = {}
    for path in sorted(java_root.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for match in CONFIG_PROPERTY_RE.finditer(text):
            args = match.group(1)
            type_token = match.group(2).strip()
            name_match = NAME_RE.search(args)
            if not name_match:
                continue
            key = name_match.group(1)
            if not key.startswith("infochat."):
                continue
            if DEFAULT_VALUE_RE.search(args):
                continue
            if type_token.split("<", 1)[0].split(".")[-1] == "Optional":
                continue
            lineno = text.count("\n", 0, match.start()) + 1
            sites.setdefault(key, []).append(f"{path}:{lineno}")
    return sites


def base_keys_in_properties(props_file):
    """Set of keys with a base (non-profile, non-comment) declaration."""
    keys = set()
    for line in props_file.read_text(encoding="utf-8").splitlines():
        match = BASE_DECL_RE.match(line.strip())
        if match:
            keys.add(match.group(1))
    return keys


def check_module(module_dir):
    """Return (missing, error). missing maps key -> sites; error is a string
    when the module layout is unusable."""
    java_root = module_dir / "src" / "main" / "java"
    props_file = module_dir / "src" / "main" / "resources" / "application.properties"
    if not java_root.is_dir():
        return {}, f"{module_dir}: no src/main/java directory"
    if not props_file.is_file():
        return {}, f"{module_dir}: no src/main/resources/application.properties"
    required = required_keys_in_java(java_root)
    declared = base_keys_in_properties(props_file)
    missing = {key: sites for key, sites in required.items() if key not in declared}
    return missing, None


def main(argv):
    parser = argparse.ArgumentParser(
        description="Assert every required infochat.* @ConfigProperty has a "
                    "base declaration in its module's main application.properties.")
    parser.add_argument("modules", nargs="*", default=[],
                        help="module directories to scan "
                             "(default: " + ", ".join(DEFAULT_MODULES) + ")")
    args = parser.parse_args(argv)

    module_names = args.modules if args.modules else DEFAULT_MODULES

    any_missing = False
    usage_error = False
    for name in module_names:
        missing, error = check_module(Path(name))
        if error:
            print(f"ERROR {error}", file=sys.stderr)
            usage_error = True
            continue
        for key in sorted(missing):
            any_missing = True
            sites = ", ".join(missing[key])
            print(f"FAIL {name}: required key '{key}' has no base declaration in "
                  f"src/main/resources/application.properties (consumed at {sites})")

    if usage_error:
        return 2
    if any_missing:
        print("\nEvery required infochat.* @ConfigProperty must have a base "
              "declaration in main config. Add the key(s) above (with per-profile "
              "overrides where the design specifies them); do not rely on the "
              "test-only application.properties.", file=sys.stderr)
        return 1
    print("OK: every required infochat.* @ConfigProperty resolves to a base "
          "declaration in main config (" + ", ".join(module_names) + ").")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
