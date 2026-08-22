---
name: psql-multi-statement-single-transaction
description: "psql -c carrying several statements runs them as ONE implicit transaction — a failing LAST statement silently rolls back the earlier writes, and the earlier successes leave no trace."
metadata:
  type: project
---

`psql -c "UPDATE ...; INSERT ...; SELECT ..."` wraps the whole string in a
single implicit transaction. If the last statement fails, every earlier
statement rolls back with it — and because the earlier statements did not
fail, nothing in the output tells you their effects are gone. Cost one A1
bootstrap cycle in the v2.0.0 campaign (remediation writes silently undone).

**How to apply:** for any multi-write drive, issue separate `-c` calls (each
its own transaction, each error independent), or use a `-f` script with
explicit `COMMIT;` boundaries, or `--single-transaction` when all-or-nothing
is actually the intent. After any batched write, re-read the rows you think
you wrote before building on them.
