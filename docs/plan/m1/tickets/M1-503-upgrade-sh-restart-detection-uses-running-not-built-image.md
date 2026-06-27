---
id: M1-503
title: "upgrade.sh restart gate compares the running image to itself, never deploys a rebuilt app"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 2
files_scope:
  - prod/scripts/upgrade.sh
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # The fix does NOT add per-service image-name derivation (deriving the compose
  # project/image name `<project>-<service>` in-script to `docker image inspect`
  # the freshly-built tag). That is the REJECTED alternative — fragile across
  # compose versions and project-name sources. The chosen fix lets
  # `docker compose up -d` decide recreation, which it already does correctly.
  - "In-script derivation of the built image name/id to fix built_image_id()"
  # No change to the backup, git-pull, config-diff, or auto-rollback steps —
  # only the Step-5 restart-decision and its Step-4 image snapshot are touched.
  - "Changes to upgrade.sh backup / pull / config-diff / rollback logic"
  # apps.sh and 7-apps.sh already call `compose up -d --wait` directly and do not
  # have this gate; they are not changed.
  - "apps.sh / 7-apps.sh"
  # The M1-476 'stopped app counts as changed → gets (re)started' behavior is
  # PRESERVED by the fix (compose up -d starts a stopped service); not a removal.
  - "Removing the stopped-app (re)start behavior"
acceptance:
  - >-
    After a rebuild that produces a NEW image for an app whose container is
    already running on the OLD image, `upgrade.sh` recreates that app onto the
    new image. It no longer false-negatives to "nothing to restart" and exits
    without deploying. (Root cause: `built_image_id()` reads `docker compose
    images -q <svc>`, which reports the RUNNING container's image — not the
    freshly-built `:latest` tag — so the Step-5 comparison was always
    running-vs-running and the changed flag never set while an app was up.)
  - >-
    A true no-op rebuild (no source change → cache-hit → byte-identical image)
    does NOT needlessly recreate or restart the running containers — zero
    downtime is preserved. (The chosen mechanism, `docker compose up -d`,
    recreates only services whose resolved image/config actually changed and
    leaves an unchanged service running, so this property holds without the
    buggy manual image-id comparison.)
  - >-
    The §Topology ordering is preserved: the Collector is brought up first with
    `--wait` (its healthcheck runs Flyway under the §7.8.5 advisory lock) and
    must pass before the Provider is started — i.e. the fix routes through the
    existing `start_apps` helper, it does not reorder or drop the wait.
  - >-
    A stopped app is still (re)started by the upgrade (the M1-476 behavior):
    `docker compose up -d` starts a service whose container is absent/stopped.
  - >-
    Any now-unused helper introduced only for the broken gate
    (`built_image_id`, and `running_image_id`/the Step-4 `running_before`
    snapshot if they become dead after the change) is removed, not left
    orphaned — per §Surgical-changes (clean up orphans your own change creates).
  - >-
    docs/design/07-deployment.md §7.11 step 6 ("Restart in §Topology order, only
    when the image changed" — the "compares the freshly-built image id against
    what that app was running" / "prints nothing to restart and exits" wording)
    is updated to describe the corrected mechanism: restart via `compose up -d`,
    which recreates only the services whose image actually changed (a cache-hit
    rebuild leaves them running with zero downtime). Step 5's "snapshots the
    image id each app is currently running" sentence is reconciled if that
    snapshot is removed.
  - >-
    Verification (inert shell+docs diff — `mvn verify` is N/A, no
    Java/config/DB change): `bash -n prod/scripts/upgrade.sh` is clean, and the
    fix is exercised against the live deployment — a running app on a stale
    image plus a fresh rebuild now recreates the app onto the new image, a
    no-op rebuild does not churn the containers, and a stopped app is started —
    recorded in the round's verification notes. (This ticket exists because the
    2026-06-27 production upgrade hit exactly this: upgrade.sh built the M1-502
    collector image but reported "nothing to restart" and left the old container
    serving; the operator had to `compose up -d` the collector by hand.)
spec_refs:
  - docs/spec/deployment.md §Operator inputs
---

## Problem

`prod/scripts/upgrade.sh` rebuilds the two app images from source, then decides
whether to restart each app by comparing "the freshly-built image id" against
"what the app was running" (Step 5 / §7.11 step 6). The comparison is broken:

```sh
built_image_id() {            # intended: the id `compose build` just produced
  compose images -q "$1"      # actually: the image the RUNNING container uses
}
```

`docker compose images -q <service>` reports the image of the service's existing
container, **not** the freshly-built `:latest` tag. So while an app is running,
`built_image_id()` returns the same id as the pre-build `running_before`
snapshot, the `changed` flag is never set, and the script prints:

```
no change — both apps are already running the freshly-built image at <sha>;
nothing to restart.
```

even though a new image was just built and tagged. The rebuilt app is never
deployed. This defeats the script's core purpose for the **common** case
(apps running, source changed) — the only path that worked was a *stopped*
app (empty running id → counted as changed).

Observed in production 2026-06-27: upgrade.sh built the M1-502 collector image
(`0dd9b0a84994`, fixed) but left the old container (`b816041188fa`, pre-fix)
running and reported "nothing to restart"; the operator deployed the fix by
running `docker compose up -d infochat-collector` by hand. Confirmation:

```
running container image : b816041188fa…   (pre-rebuild)
collector:latest (built): 0dd9b0a84994…   (the new image, tagged)
compose images -q        : b816041188fa…   (tracks the RUNNING container)
```

After a manual recreate, `compose images -q` then returned `0dd9…` — i.e. it
only ever reflects the running container, never the build output.

## Fix

Drop the manual image-id comparison and let `docker compose up -d` decide
recreation. Compose recreates only the services whose resolved image/config
actually changed and leaves an unchanged (cache-hit) service running, so the
"zero downtime on a no-op rebuild" intent is preserved **without** the broken
detection. Concretely: remove the `running_before` snapshot, the
`built_image_id()` helper (and `running_image_id()` if it becomes dead), and the
`changed`-gated early-exit, and always route the restart through the existing
`start_apps` helper (Collector `up -d --wait` first, then Provider) behind the
existing confirm gate. This is a net simplification.

### Alternatives considered

- **Fix `built_image_id()` to inspect the built tag** (`docker image inspect
  -f '{{.Id}}' <project>-<service>:latest`): rejected — deriving the compose
  project name and the default image-name template in-script is fragile across
  compose versions and project-name sources (`COMPOSE_PROJECT_NAME`, directory
  basename, `-p`). `compose up -d` already encapsulates the correct comparison,
  so the helper is redundant once the gate is removed.
</content>
