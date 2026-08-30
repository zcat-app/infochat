---
name: sweep-deferred-lane-debts
description: "A ticket that defers work to a lane ('the harness extension is the eval lane's own') creates a debt no gate tracks; sweep the ticket corpus for deferral markers before the lane's next ticket runs. Cost: a shipped feature went a week unmeasured and a byte-identical reading was misread as determinism proof."
metadata:
  type: feedback
---

# Sweep deferred-to-lane debts before the lane's next ticket

A ticket may explicitly defer part of its work to a lane — M1-938
deferred the eval harness's window arm with the verbatim acceptance
line "the harness extension is the eval lane's own; the flip is the
owner-run delta". Nothing in the flow tracks that debt: four eval-lane
tickets were filed and run afterward without anyone sweeping for it,
so the ruler stayed blind to the shipped feature. Worse, the blindness
was readable as its opposite: the feature's golden rows scored
byte-identically across its landing, and that byte-identity was quoted
as a determinism proof — it equally proved the instrument could not
see the feature. The one-line "nothing here measures the chat agent's
tool choice" disclosure in the record's do-not-settle section was not
a substitute for the owed extension; disclosed ≠ executed.

Rule: when a lane (eval, docs, ops, any) picks up its next ticket,
grep the ticket corpus for deferral language naming that lane
("the eval lane's own", "a follow-up ticket owns", "deferred to",
"owner-run delta owed") and surface every hit in the analysis brief.
Filing a follow-up ticket is still the user's call
([[ask-before-creating-tickets]]) — but the debt must at least be
named, not silently inherited as blindness.
