# Measurement records

Evidence behind decisions that were settled by measurement rather than by
argument. Each file records what was measured, on what, with which harness, and
what the numbers do **not** support.

**These files are not spec and not design notes.** They carry no directions.
Nothing is built from them. A measurement record justifies a decision; the
decision itself lives in `docs/spec/decisions.md` and is written to be
self-contained, so **no spec row cites a file in this folder** — a spec that
needs its evidence attached to be understood is not stating a direction.

They are kept because a decision whose evidence exists only in a gitignored
scratch folder is unauditable: a later reader cannot tell whether a row was
measured or assumed, and cannot re-check it when the world changes.

| record | settles |
|---|---|
| [translator-slot.md](translator-slot.md) | Which model fills `ModelTask.TRANSLATOR`, and whether the English pivot (D29) needs a dedicated MT model. Answer: nothing beats the model already in the slot. |
| [track-a-screening-in-progress.md](track-a-screening-in-progress.md) | Track A slot screening, round 1 (run 1, mechanical only): which local candidates lead per task. **In progress** — no slot decision yet; T5 run 2, judged predicates and the gold panel are outstanding. |
| [lang-quality.md](lang-quality.md) | Whether local candidates can chat in cs/es/tr/ru well enough to simplify the English-pivot pipeline, and their EN↔X translation quality. Answer: chat simplification supported for all four languages with gemma-4-26b-a4b; qwen fails L0 hygiene despite tying on judgement; all three locals tie the incumbent on translation. |

## Conventions

- **Numbers are final or absent, never estimated.** An arm is measured or it is
  not listed.
- **State what the numbers do not settle.** Every record carries that section;
  a measurement that only reports its wins is advocacy.
- **Corrections stay visible.** When later measurement falsifies an earlier
  claim, the record says so rather than quietly editing history — the point is
  that the next reader can trust what survived.
