# /tick reopen

Bring a `deferred` ticket back to `pending`: `/tick reopen <id>`.

- Refuse for `abandoned` (terminal — reviving is a fresh deliberate
  decision: `/tick analyze` a new brief) and for any ticket whose
  `deferred_on` is not `done` (say what it still waits on).
- Set `status: pending`, clear `deferred_on`/`deferred_reason`, update
  `last_updated`, regenerate `STATUS-TICK.md`.
