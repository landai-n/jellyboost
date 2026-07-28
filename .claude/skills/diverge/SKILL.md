---
name: diverge
description: Log a divergence from docs/PLAN.md in DECISIONS.md before making the diverging change. Use whenever an implementation decision would contradict or go beyond what the plan specifies — args: a short description of the divergence.
---

# /diverge

**Arguments:** a short description of the divergence (what you're about to do differently
from the plan, and why).

Record non-trivial divergences from `docs/PLAN.md` in `DECISIONS.md` **before or with** the
diverging change — never silently. This is a hard governance requirement (see
`docs/PLAN.md` "Governance" section and `CLAUDE.md`).

## Steps

1. Identify the exact line(s) in `docs/PLAN.md` that the plan specifies for this area.
   Quote it verbatim — this becomes "plan-said" below.

2. Append a new entry to the end of `DECISIONS.md`, following its existing template:

   ```
   ## YYYY-MM-DD — <short title>
   - **Scope:** <files/feature affected>
   - **Plan said:** <verbatim quote of the relevant docs/PLAN.md line(s)>
   - **Done instead:** <what was actually done>
   - **Reason:** <why>
   ```

   Use `date +%F` for the date. Keep "scope" concrete (file paths or feature names), and
   "done instead"/"reason" concise but specific enough that a future reader understands
   the decision without re-deriving it.

3. Only after the entry is written, make the diverging change.

## Notes

- Design choices already marked `[D]` in `docs/PLAN.md` are pre-approved and already seeded
  into `DECISIONS.md` — they do not need a new entry.
- If the divergence surfaces mid-`/verify` (e.g. a test is wrong), log it here first, then
  fix the test/code, then re-run `/verify`.
- This entry should land in the same commit (or an earlier commit) as the diverging change
  when you next `/checkpoint` — don't let the log fall behind the code.
