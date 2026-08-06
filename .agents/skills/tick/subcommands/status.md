# /tick status

Regenerate `docs/plan/m1/STATUS-TICK.md` from tick-ticket frontmatter and
print the summary:

```bash
python3 scripts/regen-status.py 'docs/plan/m1/tick-tickets/M1-*.md' docs/plan/m1/STATUS-TICK.md
```

Print the script's four-line summary verbatim plus any `WARNING:` lines
(stderr). Non-zero exit → surface stderr, refuse to proceed, leave the tree
unchanged. Never hand-edit the board.

## /tick measure

A/B comparison of the tick flow vs the m1 flow:

```bash
python3 scripts/tick-measure.py
```

Print the table verbatim. The m1 board is the baseline; the point of the
parallel flow is that this table gets better. `--json` emits machine-
readable output. Run it at milestone boundaries and when asked "is the new
flow better?" — never assert an improvement without it.
