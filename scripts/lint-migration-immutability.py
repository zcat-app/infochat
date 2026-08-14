#!/usr/bin/env python3
"""Reject edits to Flyway migrations that were already present on main."""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path


MIGRATION_PATH = re.compile(r"(?:^|/)db/migration/V[^/]*\.sql$")
SKIP_EXIT = 2


class HistoryUnavailable(Exception):
    """The repository cannot provide the history needed by this lint."""


def run_git(*arguments: str, cwd: Path) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=cwd,
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or "git returned a non-zero status"
        raise HistoryUnavailable(
            f"git {' '.join(arguments)} failed: {detail}"
        )
    return result.stdout


def is_migration_path(path: str) -> bool:
    return MIGRATION_PATH.search(path) is not None


def base_migrations(base: str, cwd: Path) -> set[str]:
    paths = run_git("ls-tree", "-r", "--name-only", base, cwd=cwd)
    return {path for path in paths.splitlines() if is_migration_path(path)}


def changed_migrations(base: str, cwd: Path) -> list[str]:
    raw = run_git(
        "diff",
        "--name-status",
        "--find-renames",
        "-z",
        f"{base}...HEAD",
        "--",
        cwd=cwd,
    )
    tokens = raw.split("\0")
    existing = base_migrations(base, cwd)
    violations = []
    index = 0
    while index < len(tokens) and tokens[index]:
        status = tokens[index]
        index += 1
        if status.startswith("R"):
            old_path = tokens[index]
            new_path = tokens[index + 1]
            index += 2
            if old_path in existing:
                violations.append(
                    f"{status} {old_path} -> {new_path}"
                )
            continue
        path = tokens[index]
        index += 1
        if status != "A" and path in existing:
            violations.append(f"{status} {path}")
    return violations


def lint(cwd: Path) -> int:
    try:
        run_git("rev-parse", "--show-toplevel", cwd=cwd)
        run_git("rev-parse", "--verify", "main^{commit}", cwd=cwd)
        base = run_git("merge-base", "main", "HEAD", cwd=cwd).strip()
        if not base:
            raise HistoryUnavailable("git merge-base returned no base commit")
        violations = changed_migrations(base, cwd)
    except HistoryUnavailable as error:
        print(f"SKIP: migration history could not be checked ({error})")
        return SKIP_EXIT

    if violations:
        print("FAIL: applied Flyway migration content is immutable")
        for violation in violations:
            print(f"FAIL: {violation}")
        return 1

    print(f"OK: no applied migration changes against {base}")
    return 0


def run_command(arguments: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        arguments,
        cwd=cwd,
        text=True,
        capture_output=True,
    )


def git_commit(cwd: Path, message: str) -> None:
    result = run_command(["git", "add", "."], cwd)
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    result = run_command(["git", "commit", "-m", message], cwd)
    if result.returncode != 0:
        raise AssertionError(result.stderr)


def new_fixture(parent: Path) -> Path:
    cwd = parent / "repo"
    cwd.mkdir(parents=True)
    result = run_command(["git", "init", "-b", "main"], cwd)
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    for name, value in (
        ("user.name", "migration lint self-test"),
        ("user.email", "migration-lint-self-test@example.invalid"),
    ):
        result = run_command(["git", "config", name, value], cwd)
        if result.returncode != 0:
            raise AssertionError(result.stderr)
    migration = cwd / "infochat-core/src/main/resources/db/migration/V1__initial.sql"
    migration.parent.mkdir(parents=True)
    migration.write_text("-- initial\n", encoding="utf-8")
    git_commit(cwd, "base migration")
    result = run_command(["git", "checkout", "-b", "feature"], cwd)
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    return cwd


def expect_lint(cwd: Path, expected: int, label: str, contains: str = "") -> None:
    result = run_command([sys.executable, str(Path(__file__).resolve())], cwd)
    output = result.stdout + result.stderr
    if result.returncode != expected or (contains and contains not in output):
        raise AssertionError(
            f"{label}: expected exit {expected} containing {contains!r}, "
            f"got {result.returncode}: {output}"
        )


def self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="migration-lint-") as temporary:
        parent = Path(temporary)

        clean = new_fixture(parent / "clean")
        (clean / "README").write_text("unchanged migration tree\n", encoding="utf-8")
        git_commit(clean, "non-migration change")
        expect_lint(clean, 0, "clean tree")

        added = new_fixture(parent / "added")
        new_migration = added / "infochat-core/src/main/resources/db/migration/V2__next.sql"
        new_migration.write_text("-- new\n", encoding="utf-8")
        git_commit(added, "add migration")
        expect_lint(added, 0, "added migration")

        edited = new_fixture(parent / "edited")
        edited_migration = edited / "infochat-core/src/main/resources/db/migration/V1__initial.sql"
        edited_migration.write_text("-- changed comment only\n", encoding="utf-8")
        git_commit(edited, "edit migration comment")
        expect_lint(edited, 1, "comment-only edit", "V1__initial.sql")

        deleted = new_fixture(parent / "deleted")
        (deleted / "infochat-core/src/main/resources/db/migration/V1__initial.sql").unlink()
        git_commit(deleted, "delete migration")
        expect_lint(deleted, 1, "deleted migration", "V1__initial.sql")

        renamed = new_fixture(parent / "renamed")
        old_path = renamed / "infochat-core/src/main/resources/db/migration/V1__initial.sql"
        new_path = renamed / "infochat-core/src/main/resources/db/migration/V2__renamed.sql"
        old_path.rename(new_path)
        git_commit(renamed, "rename migration")
        expect_lint(renamed, 1, "renamed migration", "V1__initial.sql")

        outside = parent / "outside"
        outside.mkdir()
        expect_lint(outside, SKIP_EXIT, "history unavailable", "SKIP")

    print("SELF-TEST PASS: migration immutability fixture matrix")
    return 0


def main(arguments: list[str]) -> int:
    if arguments == ["--self-test"]:
        return self_test()
    if arguments:
        print("usage: lint-migration-immutability.py [--self-test]", file=sys.stderr)
        return 2
    return lint(Path.cwd())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
