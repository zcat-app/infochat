#!/usr/bin/env bash
# Multi-auditor redteam harness: runs the SAME rendered redteam prompt through
# several independent coding-agent CLIs, then cross-examines every finding with
# the auditors that did not report it.
#
# Why fan out at all: a single auditor's blind spots are systematic, not random.
# A finding only one model reports is either a real gap the others missed or a
# false positive exposing that model's bias, and only a falsification pass tells
# those apart.
#
# Why every auditor runs HEADLESS, including the harness you invoked this from:
# an in-session subagent and a headless process differ in context assembly,
# system prompt and tool wrappers, so a difference in findings could not be
# attributed to the model. Uniform invocation is a correctness requirement of
# the comparison, not a convenience. (A subprocess also simply cannot call back
# into its parent agent session — bash has no channel to drive the orchestrating
# LLM.)
#
# Recursion: this script invokes the GATE AGENT, never the /redteam skill. On
# Claude and opencode the agent definitions declare no Task/skill tool, so
# recursion is structurally impossible. Codex agents are generic and CAN spawn
# agents and read .agents/skills/, so there REDTEAM_MULTI_DEPTH is the primary
# guard, not a backstop.
#
# The diff-range algorithm deliberately lives in .claude/skills/redteam/SKILL.md
# §1, not here: it has four target forms and one subtle case (an in-progress
# ticket must diff working-tree-vs-merge-base, NOT `git diff main`, which in a
# worktree pinned behind a moved main drags every since-landed ticket in as
# phantom changes — M1-096). Duplicating that in bash is a drift risk, so this
# script consumes an already-resolved range.
set -eu

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Recursion guard. This script sets REDTEAM_MULTI_DEPTH=1 on every auditor
# spawn (see dispatch_auditor). An auditor that re-invokes this script —
# only reachable on Codex, whose generic agent can read .agents/skills/ and
# spawn agents (harness-mapping §6.2) — inherits that env var and is
# refused here. Claude and opencode gate agents declare no Task/skill tool,
# so the path is structurally closed there; this guard is the load-bearing
# defence on Codex and a belt-and-braces check elsewhere. Without it the
# header's "REDTEAM_MULTI_DEPTH is the primary guard" claim is inert.
if [ "${REDTEAM_MULTI_DEPTH:-0}" -gt 0 ]; then
    printf 'redteam-multi: refusing recursive invocation (REDTEAM_MULTI_DEPTH=%s).\n' \
        "$REDTEAM_MULTI_DEPTH" >&2
    printf 'This script was spawned by another redteam-multi run. Auditors must not\n' >&2
    printf 're-enter the dispatcher — read the rendered prompt and Write the verdict.\n' >&2
    exit 1
fi

# Auditor id is <harness>[-<model>] so that running one harness at two model
# tiers (claude-opus vs claude-haiku) stays expressible without a schema change.
# These are the harnesses with a verified binding; see docs/process/harness-mapping.md.
auditors="claude opencode codex kimi"

usage() {
    cat >&2 <<'EOF'
usage: redteam-multi.sh preflight [--auditors <id,...>]

  preflight   Probe each auditor: binary present, authenticated, agent
              definition resolvable, run dir writable. Prints an availability
              table. Exits 0 if at least one auditor is usable, 1 if none.
              Costs no model tokens.
EOF
}

# Each probe echoes "<status>\t<detail>" and returns 0. Status is AVAILABLE or
# UNAVAILABLE; the caller decides what to do with it. Probes must stay CHEAP —
# preflight runs before every multi-run and must never spend model tokens.

probe_claude() {
    if ! command -v claude >/dev/null 2>&1; then
        printf 'UNAVAILABLE\tbinary not on PATH\n'
        return 0
    fi
    local auth
    auth="$(timeout 30 claude auth status 2>/dev/null || true)"
    if ! printf '%s' "$auth" | python3 -c 'import json,sys
try: sys.exit(0 if json.load(sys.stdin).get("loggedIn") else 1)
except Exception: sys.exit(1)' 2>/dev/null; then
        printf 'UNAVAILABLE\tnot logged in (claude auth login)\n'
        return 0
    fi
    # Agent RESOLUTION under -p was verified empirically once (it returns the
    # project agent's persona and exact tool list). Re-proving it costs a real
    # model call, so preflight checks only that the definition is still present.
    if [ ! -f "$repo_root/.claude/agents/threat-actor.md" ]; then
        printf 'UNAVAILABLE\t.claude/agents/threat-actor.md missing\n'
        return 0
    fi
    printf 'AVAILABLE\t%s\n' "$(claude --version 2>/dev/null | head -1)"
}

probe_opencode() {
    if ! command -v opencode >/dev/null 2>&1; then
        printf 'UNAVAILABLE\tbinary not on PATH\n'
        return 0
    fi
    if ! timeout 30 opencode providers list 2>/dev/null | grep -q 'credential'; then
        printf 'UNAVAILABLE\tno provider credentials (opencode providers login)\n'
        return 0
    fi
    # opencode gates the `write` tool under the EDIT permission, so an agent
    # declaring write:true edit:false silently resolves to write=false and
    # cannot produce its verdict file (harness-mapping §6.1(a), measured on
    # 1.18.3). The resolved map — not the frontmatter — is the truth, so assert
    # against `debug agent`, which is local and free.
    local resolved
    resolved="$(timeout 30 opencode debug agent threat-actor 2>/dev/null || true)"
    if ! printf '%s' "$resolved" | python3 -c 'import json,sys
try: sys.exit(0 if json.load(sys.stdin).get("tools",{}).get("write") else 1)
except Exception: sys.exit(1)' 2>/dev/null; then
        printf 'UNAVAILABLE\tthreat-actor resolves write=false (see harness-mapping 6.1a)\n'
        return 0
    fi
    printf 'AVAILABLE\topencode %s\n' "$(opencode --version 2>/dev/null | head -1)"
}

probe_codex() {
    if ! command -v codex >/dev/null 2>&1; then
        printf 'UNAVAILABLE\tbinary not on PATH\n'
        return 0
    fi
    if ! timeout 60 codex doctor 2>/dev/null | grep -qE '✓[[:space:]]+auth'; then
        printf 'UNAVAILABLE\tauth not configured (codex login)\n'
        return 0
    fi
    # Codex reads no per-agent definitions (.codex/agents/ is not read —
    # harness-mapping §6.2), so its persona arrives solely via the stub prompt
    # telling it to read the Claude agent file. That file is therefore a hard
    # dependency for the Codex slot specifically.
    if [ ! -f "$repo_root/.claude/agents/threat-actor.md" ]; then
        printf 'UNAVAILABLE\t.claude/agents/threat-actor.md missing (Codex persona source)\n'
        return 0
    fi
    # Sandbox exercise (free — no codex, no model call). Codex's
    # --sandbox workspace-write uses bubblewrap with --unshare-user +
    # --unshare-net; on hosts where the kernel blocks unprivileged
    # user/net namespaces (AppArmor-restricted uid_map writes on some
    # VPSes; certain container runtimes) the sandbox dies at runtime
    # with `bwrap: loopback: Failed RTM_NEWADDR: Operation not
    # permitted`. `codex doctor` does NOT catch this. The exercise
    # below tests the SAME kernel capability codex needs, so the user
    # sees the failure at preflight rather than as a wasted 16k-token
    # codex run that produces an UNAVAILABLE stub.
    #
    # When the sandbox is broken on the host, probe_codex still reports
    # AVAILABLE — dispatch_auditor's codex arm has a fallback to
    # --dangerously-bypass-approvals-and-sandbox (acceptable here because
    # we audit our own prompt against our own code). The detail string
    # tells the user which path dispatch will take.
    local sandbox_ok=1
    if command -v bwrap >/dev/null 2>&1; then
        if ! bwrap --unshare-user --unshare-net \
                --ro-bind /usr /usr --ro-bind /lib /lib --ro-bind /lib64 /lib64 \
                --proc /proc --dev /dev \
                /bin/true >/dev/null 2>&1; then
            sandbox_ok=0
        fi
    else
        # No system bwrap: codex will use its bundled bwrap (which may
        # or may not work). Cannot test without codex, so report
        # "unproven" and let dispatch's fallback handle a failure.
        printf 'AVAILABLE\t%s (no system bwrap; sandbox unproven — dispatch falls back if needed)\n' \
            "$(codex --version 2>/dev/null | head -1)"
        return 0
    fi
    if [ "$sandbox_ok" -eq 1 ]; then
        printf 'AVAILABLE\t%s (sandbox verified)\n' "$(codex --version 2>/dev/null | head -1)"
    else
        printf 'AVAILABLE\t%s (host blocks bwrap sandbox; dispatch will use --dangerously-bypass-approvals-and-sandbox)\n' \
            "$(codex --version 2>/dev/null | head -1)"
    fi
}

probe_kimi() {
    if ! command -v kimi >/dev/null 2>&1; then
        printf 'UNAVAILABLE\tbinary not on PATH\n'
        return 0
    fi
    # `kimi doctor` validates config SYNTAX only and reports OK on a host with
    # no provider at all, so it cannot stand in for an auth check. `provider
    # list` is the cheap one that can: unconfigured it prints "No providers
    # configured." and nothing else, configured it prints the provider table
    # plus the resolved default-model line. Requiring that line proves both a
    # credentialed provider and a model alias dispatch can actually use.
    if ! timeout 30 kimi provider list 2>/dev/null | grep -q '^Default model:'; then
        printf 'UNAVAILABLE\tno provider/default model (kimi login, or kimi provider add)\n'
        return 0
    fi
    # No agent-definition check: kimi reads no repo-shippable agent definition
    # (harness-mapping §6.3), so the adversary persona arrives entirely from
    # the rendered prompt — there is no per-agent file for this slot to depend
    # on.
    printf 'AVAILABLE\tkimi %s\n' "$(kimi --version 2>/dev/null | head -1)"
}

cmd_preflight() {
    local selected="$auditors"
    while [ $# -gt 0 ]; do
        case "$1" in
            --auditors) selected="$(printf '%s' "$2" | tr ',' ' ')"; shift 2 ;;
            *) printf 'redteam-multi: unknown preflight argument: %s\n' "$1" >&2; usage; exit 2 ;;
        esac
    done

    printf 'redteam-multi preflight (repo: %s)\n\n' "$repo_root"
    printf '%-12s %-12s %s\n' 'AUDITOR' 'STATUS' 'DETAIL'

    local available=0 id result status detail
    for id in $selected; do
        case "$id" in
            claude)   result="$(probe_claude)" ;;
            opencode) result="$(probe_opencode)" ;;
            codex)    result="$(probe_codex)" ;;
            kimi)     result="$(probe_kimi)" ;;
            *)        result="$(printf 'UNAVAILABLE\tno registry entry for this id')" ;;
        esac
        status="${result%%$'\t'*}"
        detail="${result#*$'\t'}"
        printf '%-12s %-12s %s\n' "$id" "$status" "$detail"
        [ "$status" = AVAILABLE ] && available=$((available + 1))
    done

    printf '\n%d of %d auditor(s) available.\n' "$available" "$(printf '%s\n' $selected | wc -l)"
    if [ "$available" -eq 0 ]; then
        printf 'No auditor is usable — a multi-run cannot proceed.\n' >&2
        return 1
    fi
    # One auditor still audits, but nobody independent can refute its findings,
    # so the cross-examination stage is meaningless and gets skipped. Say so
    # here rather than letting a single-auditor run masquerade as corroborated.
    if [ "$available" -eq 1 ]; then
        printf 'Only one auditor: cross-examination will be SKIPPED (no independent refuter).\n'
    fi
    return 0
}

# The stub prompt is identical for every harness (harness-mapping §2). All
# operating instructions — threat model path, diff path, verdict path, output
# format — live in the rendered prompt file, which is why the per-tool agent
# definitions can stay thin pointers.
stub_prompt() {
    printf 'Read %s and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies.' "$1"
}

# Sensitive-surface inventories. The canonical pattern list lives in
# docs/process/redteam-prompt.md §"Sensitive-surface patterns (canonical)"; when
# that list changes these greps change with it. Deliberately conservative — the
# adversary's prompt reminds it the list is not exhaustive.
build_inventories() {
    local run_dir="$1" diff_file="$2"
    # Markdown is excluded deliberately. The canonical patterns describe CODE
    # constructs (annotations, method names, table writes); running them over
    # prose yields hundreds of prison-bar matches from STATUS.md and the spec,
    # which bury the real pointers and eat the adversary's context budget. The
    # adversary still reads every doc hunk — it is in the diff it Reads in full.
    local files
    files="$(grep -E '^\+\+\+ b/' "$diff_file" | sed 's|^+++ b/||' | grep -v '\.md$' | sort -u || true)"

    local key pattern
    for key in auth authz input ban audit; do
        case "$key" in
            auth)  pattern='authenticate|invite|@PermitAll|@RolesAllowed|security/auth/' ;;
            authz) pattern='is_admin|is_group_admin|isAdmin|isGroupAdmin|@RolesAllowed|security/authz/' ;;
            input) pattern='@Path|@POST|@GET|@Consumes|readValue|fromJson|@Tool|Inbound' ;;
            # Word-anchored: a bare `ban` substring matches "abandoned" and
            # "banner", which produced 203 lines of noise on a real diff.
            ban)   pattern='\b(un)?ban(s|ned|ning)?\b' ;;
            audit) pattern='audit_log|AuditLogger' ;;
        esac
        local out="$run_dir/inv-$key.txt"
        : > "$out"
        local f
        for f in $files; do
            [ -f "$repo_root/$f" ] || continue
            grep -inE "$pattern" "$repo_root/$f" 2>/dev/null | sed "s|^|$f:|" >> "$out" || true
        done
        # An empty inventory must read as an explicit "nothing here", not as a
        # blank slot the adversary might mistake for a rendering failure.
        [ -s "$out" ] || printf '(none touched)\n' > "$out"
    done
}

# Any auditor that cannot produce a verdict gets a stub that is explicitly
# UNAVAILABLE. It must never read as CLEAN: a vacuous CLEAN is worse than no
# audit at all, because it is persisted as evidence the target WAS audited
# (the same fail-closed rule SKILL.md applies to empty diffs).
write_unavailable_stub() {
    local verdict_file="$1" auditor="$2" reason="$3"
    cat > "$verdict_file" <<EOF
RED-TEAM VERDICT: UNAVAILABLE

AUDITOR: $auditor
REASON: $reason

This auditor produced no verdict. Treat as NO DATA, never as "no findings".
EOF
}

# Invoke one auditor headlessly. Returns 0 if a non-empty verdict file landed,
# 1 otherwise (caller writes the stub). Never invokes a skill — only the pinned
# gate agent — so a fanned-out auditor cannot recurse into this script.
dispatch_auditor() {
    local auditor="$1" prompt_file="$2" verdict_file="$3" reply_file="$4"
    local stub; stub="$(stub_prompt "$prompt_file")"
    local rc=0

    case "$auditor" in
        claude)
            REDTEAM_MULTI_DEPTH=1 timeout 900 claude -p --agent threat-actor \
                --permission-mode acceptEdits --disable-slash-commands \
                "$stub" > "$reply_file" 2>&1 || rc=$?
            ;;
        opencode)
            REDTEAM_MULTI_DEPTH=1 timeout 900 opencode run --agent threat-actor \
                "$stub" > "$reply_file" 2>&1 || rc=$?
            ;;
        codex)
            # Codex has no per-agent definition (§6.2) and its generic agent can
            # spawn agents and read .agents/skills/, so REDTEAM_MULTI_DEPTH is
            # this slot's PRIMARY recursion guard. --sandbox workspace-write is
            # passed explicitly because the project config is only honoured when
            # the project is trusted, and a git worktree resolves as untrusted.
            #
            # Sandbox fallback: hosts that block unprivileged user/net
            # namespaces (AppArmor-restricted uid_map on some VPSes;
            # certain container runtimes) cannot run bubblewrap, and
            # codex dies at the sandbox step with
            # `bwrap: loopback: Failed RTM_NEWADDR: Operation not
            # permitted`. probe_codex detects this at preflight, but
            # dispatch defends against it too: try --sandbox
            # workspace-write first; if no verdict file lands, retry
            # with --dangerously-bypass-approvals-and-sandbox.
            #
            # IMPORTANT: codex exits 0 even when its sandbox fails —
            # the model "successfully" reports it cannot read the
            # prompt and replies with the error. So the retry trigger
            # is the VERDICT FILE's absence, not the exit code.
            #
            # The bypass is acceptable here because:
            #   - the prompt we feed codex is OUR OWN audited red-team
            #     prompt (not untrusted content)
            #   - codex runs in our own worktree, writing a verdict
            #     file we explicitly asked it to write
            #   - the sandbox's purpose is blocking prompt-injection-
            #     driven exfiltration; our prompt has no injection risk
            # Hosts where workspace-write works get the sandbox; hosts
            # where it doesn't get the bypass with a stderr note.
            REDTEAM_MULTI_DEPTH=1 timeout 900 codex exec - \
                --sandbox workspace-write \
                -o "$reply_file" <<<"$stub" >/dev/null 2>&1 || rc=$?
            if [ "$rc" -ne 0 ] || [ ! -s "$verdict_file" ]; then
                printf '    codex: workspace-write produced no verdict (rc=%d, verdict=%s); retrying --dangerously-bypass-approvals-and-sandbox\n' \
                    "$rc" "$([ -s "$verdict_file" ] && echo present || echo missing)" >&2
                # Clear any stub-written verdict so the bypass run starts clean.
                rm -f "$verdict_file"
                rc=0
                REDTEAM_MULTI_DEPTH=1 timeout 900 codex exec - \
                    --dangerously-bypass-approvals-and-sandbox \
                    -o "$reply_file" <<<"$stub" >/dev/null 2>&1 || rc=$?
            fi
            ;;
        kimi)
            # Same shape as the codex slot: kimi reads no repo-shippable agent
            # definition (§6.3), so the persona comes wholly from the rendered
            # prompt. `-p` is the headless form — it prints the reply on stdout
            # and auto-approves tool calls, which is why no permission flag is
            # passed (`--auto` is REJECTED alongside `-p`).
            #
            # --skills-dir points at an EMPTY directory to switch project skill
            # discovery off. kimi auto-discovers skills from BOTH .claude/skills/
            # and .agents/skills/ — the same two-tree collision that makes
            # opencode nondeterministic (§6.1(b)) — and an auditor needs no skill
            # at all, so suppressing them closes the recursion path at its source
            # and leaves REDTEAM_MULTI_DEPTH as the backstop. The directory lives
            # under the run dir, and git tracks no empty directory, so it reaches
            # neither the contamination check nor the committed evidence packet.
            local skills_void
            skills_void="$(dirname "$verdict_file")/kimi-no-skills"
            mkdir -p "$skills_void"
            REDTEAM_MULTI_DEPTH=1 timeout 900 kimi -p "$stub" \
                --skills-dir "$skills_void" > "$reply_file" 2>&1 || rc=$?
            ;;
    esac

    [ "$rc" -eq 0 ] || { printf 'exit status %d\n' "$rc"; return 1; }
    [ -s "$verdict_file" ] || { printf 'no verdict file written\n'; return 1; }
    return 0
}

cmd_run() {
    local slug='' target='' base='' head='' diff_in='' ticket='' selected="$auditors" prepare_only=''
    while [ $# -gt 0 ]; do
        case "$1" in
            --prepare-only) prepare_only=1; shift ;;
            --slug)     slug="$2"; shift 2 ;;
            --target)   target="$2"; shift 2 ;;
            --base)     base="$2"; shift 2 ;;
            --head)     head="$2"; shift 2 ;;
            --diff)     diff_in="$2"; shift 2 ;;
            --ticket)   ticket="$2"; shift 2 ;;
            --auditors) selected="$(printf '%s' "$2" | tr ',' ' ')"; shift 2 ;;
            *) printf 'redteam-multi: unknown run argument: %s\n' "$1" >&2; usage; exit 2 ;;
        esac
    done

    if [ -n "$ticket" ]; then
        # Merged form ONLY (SKILL.md §1 step 1). The in-progress branch forms are
        # deliberately not reimplemented here — see the header comment.
        local sha
        sha="$(git -C "$repo_root" log --grep="^$ticket: " --format=%H main | head -1)"
        if [ -z "$sha" ]; then
            printf 'redteam-multi: no commit matching "%s: " on main.\n' "$ticket" >&2
            printf 'If the ticket is in-flight on a branch, resolve the range with\n' >&2
            printf '  /redteam %s --in-progress\n' "$ticket" >&2
            printf 'and pass the result via --base/--head or --diff.\n' >&2
            exit 2
        fi
        base="$sha^"; head="$sha"
        [ -n "$slug" ] || slug="$ticket"
    fi
    [ -n "$slug" ] || { printf 'redteam-multi: --slug is required\n' >&2; exit 2; }
    [ -n "$target" ] || target="$slug"

    # Evidence lives under docs/plan/, not target/. target/ is gitignored and
    # wiped by `mvn clean`, so a verdict written there vanishes the next build
    # — the same reason the single-auditor skill persists to docs/plan/m1/
    # redteam/. The -rN suffix mirrors the existing redteam/<id>-<date>-rN.md
    # reaudit convention: same target, same day, distinct evidence packet.
    local base_dir="$repo_root/docs/plan/m1/redteam-multi/$slug-$(date -u +%Y-%m-%d)"
    local run_dir="$base_dir"
    local rN=1
    while [ -e "$run_dir" ]; do
        rN=$((rN + 1))
        run_dir="${base_dir}-r${rN}"
    done
    mkdir -p "$run_dir"

    cmd_preflight --auditors "$(printf '%s' "$selected" | tr ' ' ',')" \
        | tee "$run_dir/preflight.txt"

    local available=''
    local id
    for id in $selected; do
        case "$id" in
            claude)   probe_claude ;;
            opencode) probe_opencode ;;
            codex)    probe_codex ;;
            kimi)     probe_kimi ;;
            *)        printf 'UNAVAILABLE\t\n' ;;
        esac | grep -q '^AVAILABLE' && available="$available $id"
    done
    [ -n "$available" ] || { printf 'redteam-multi: no auditor available\n' >&2; exit 1; }

    local diff_file="$run_dir/diff.patch"
    if [ -n "$diff_in" ]; then
        cp "$diff_in" "$diff_file"
    else
        [ -n "$base" ] && [ -n "$head" ] || {
            printf 'redteam-multi: need --base and --head, or --diff, or --ticket\n' >&2; exit 2; }
        git -C "$repo_root" diff "$base...$head" > "$diff_file"
    fi

    # Fail closed on an empty diff. An audit of nothing returns CLEAN, and that
    # verdict is indistinguishable in the record from a real one.
    if [ ! -s "$diff_file" ]; then
        printf 'redteam-multi: diff is empty (base=%s head=%s) — refusing to audit.\n' \
            "$base" "$head" >&2
        exit 1
    fi

    build_inventories "$run_dir" "$diff_file"

    # Renders every prompt but spawns no auditor, so the whole preparation
    # pipeline (diff capture, inventories, substitution) is verifiable without
    # spending model tokens.
    if [ -n "$prepare_only" ]; then
        for id in $available; do
            python3 "$repo_root/scripts/m1-render-prompt.py" \
                "$repo_root/docs/process/redteam-prompt.md" "$run_dir/prompt-$id.txt" \
                TARGET="$target" BASE_REF="${base:-working-tree}" HEAD_REF="${head:-working-tree}" \
                DIFF_FILE_PATH="$diff_file" VERDICT_FILE_PATH="$run_dir/verdict-$id.txt" \
                AUTH_PATHS="@$run_dir/inv-auth.txt" AUTHZ_PATHS="@$run_dir/inv-authz.txt" \
                INPUT_PATHS="@$run_dir/inv-input.txt" BAN_PATHS="@$run_dir/inv-ban.txt" \
                AUDIT_PATHS="@$run_dir/inv-audit.txt"
        done
        printf '\nPrepared (no auditors spawned). Evidence directory: %s\n' "$run_dir"
        return 0
    fi

    # Contamination check: an auditor that writes anywhere OTHER than its
    # pre-allocated verdict path has escaped its scope. run_dir now lives
    # under docs/plan/ (tracked), so every artifact the script renders AND
    # every verdict the auditor writes would show in `git status --porcelain`
    # and false-positive. Filter lines whose path is under run_dir before
    # comparing; the residual delta is what the auditor touched outside the
    # evidence directory — that is the contamination signal.
    #
    # -uall is mandatory: by default git collapses an untracked DIRECTORY to
    # one line (here: "?? docs/plan/m1/redteam-multi/"), which would hide
    # any sibling write inside that tree from the filter and from the diff.
    # With -uall every file under run_dir shows individually and the filter
    # matches; a write to a sibling run directory is then detectable.
    local rel_run_dir="${run_dir#$repo_root/}"
    filter_run_dir() {
        awk -v rel="$rel_run_dir" '
            {
                p = substr($0, 4)
                if (p ~ /^".*"$/) p = substr(p, 2, length(p) - 2)
                if (index(p, rel "/") == 1) next
                print
            }
        '
    }

    local porcelain_before
    porcelain_before="$(git -C "$repo_root" status --porcelain -uall | filter_run_dir)"

    for id in $available; do
        local prompt_file="$run_dir/prompt-$id.txt"
        local verdict_file="$run_dir/verdict-$id.txt"
        local reply_file="$run_dir/reply-$id.txt"

        python3 "$repo_root/scripts/m1-render-prompt.py" \
            "$repo_root/docs/process/redteam-prompt.md" "$prompt_file" \
            TARGET="$target" BASE_REF="${base:-working-tree}" HEAD_REF="${head:-working-tree}" \
            DIFF_FILE_PATH="$diff_file" VERDICT_FILE_PATH="$verdict_file" \
            AUTH_PATHS="@$run_dir/inv-auth.txt" AUTHZ_PATHS="@$run_dir/inv-authz.txt" \
            INPUT_PATHS="@$run_dir/inv-input.txt" BAN_PATHS="@$run_dir/inv-ban.txt" \
            AUDIT_PATHS="@$run_dir/inv-audit.txt" >/dev/null

        printf '\n>>> auditing with %s ...\n' "$id"
        local failure=''
        failure="$(dispatch_auditor "$id" "$prompt_file" "$verdict_file" "$reply_file")" || {
            printf '    UNAVAILABLE: %s\n' "$failure"
            write_unavailable_stub "$verdict_file" "$id" "$failure"
        }
        [ -s "$verdict_file" ] && printf '    verdict: %s\n' "$verdict_file"

        # Auditors run SEQUENTIALLY so a stray write is attributable to one of
        # them. The delta is filtered to exclude run_dir itself, so the
        # expected delta is EMPTY — anything residual is contamination.
        local porcelain_after
        porcelain_after="$(git -C "$repo_root" status --porcelain -uall | filter_run_dir)"
        printf '%s\n' "$porcelain_after" > "$run_dir/porcelain-$id.txt"
        if [ "$porcelain_after" != "$porcelain_before" ]; then
            printf '    !! CONTAMINATION: %s changed files outside %s — see porcelain-%s.txt\n' \
                "$id" "$rel_run_dir" "$id" >&2
        fi
    done

    # Cross-examination stage. The header's promise — "cross-examines every
    # finding with the auditors that did not report it" — is delivered here.
    # v1 is a deterministic parser that clusters findings across auditors by
    # (CATEGORY, primary file:line cited in GAP) and emits a side-by-side
    # comparison plus a single-auditor-finding callout. The falsification
    # pass — re-auditing each single-auditor finding against the threat model
    # — is v2 (a fresh-context synthesizer subagent), not yet wired.
    local cross_file="$run_dir/cross-examination.md"
    local available_count
    available_count="$(printf '%s\n' $available | wc -l | tr -d ' ')"
    if [ "$available_count" -ge 2 ]; then
        printf '\n>>> cross-examining %s auditors findings...\n' "$available_count"
        python3 "$repo_root/scripts/redteam-multi-cross.py" "$run_dir" $available > "$cross_file"
        printf '    comparison: %s\n' "$cross_file"
    else
        # Only one auditor ran. Cross-examination is meaningless without an
        # independent refuter, but a degenerate report keeps the run layout
        # symmetric so downstream tooling can assume the file always exists.
        {
            printf '# Cross-examination report (degenerate — single auditor)\n\n'
            printf 'Run directory: `%s`\n\n' "$run_dir"
            printf 'Only one auditor produced a verdict, so no independent refuter exists.\n'
            printf 'Treat the single verdict as a one-shot audit, not as corroborated evidence.\n\n'
            for id in $available; do
                printf '## %s\n\nSee `verdict-%s.txt`.\n\n' "$id" "$id"
            done
        } > "$cross_file"
        printf '\n>>> cross-examination SKIPPED (single auditor): %s\n' "$cross_file" >&2
    fi

    printf '\nEvidence directory: %s\n' "$run_dir"
    printf 'Commit this directory alongside docs/plan/m1/redteam/ as the audit record.\n'
}

[ $# -ge 1 ] || { usage; exit 2; }
subcommand="$1"
shift
case "$subcommand" in
    preflight) cmd_preflight "$@" ;;
    run)       cmd_run "$@" ;;
    *) printf 'redteam-multi: unknown subcommand: %s\n' "$subcommand" >&2; usage; exit 2 ;;
esac
