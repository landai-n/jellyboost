---
name: milestone
description: Start or finish a jellyfin-native milestone (M0-M10) from docs/PLAN.md. Use when asked to start work on a milestone, or to close one out with full device verification, docs, commit, and tag. Args - "start Mn" or "finish Mn".
---

# /milestone

**Arguments:** `start <Mn>` or `finish <Mn>` (e.g. `start M1`, `finish M3`).

Milestones are defined in the "Milestones" section of `docs/PLAN.md`, each with its own
Definition of Done (DoD).

## `start <Mn>`

1. Find milestone `<Mn>` in `docs/PLAN.md` and read its full DoD line.
2. Update `STATUS.md`:
   - Set "## Current milestone" to `<Mn> — <name>` and copy/paraphrase its DoD.
   - Populate "### Next" with the concrete next steps implied by the DoD (break it into
     actionable items).
3. Leave "### Done" and "### Known issues" as accurate reflections of prior state (don't
   fabricate progress that hasn't happened).

## `finish <Mn>`

A milestone is **NOT finished** if any DoD item fails — do not fudge this.

1. Read `<Mn>`'s DoD in `docs/PLAN.md` in full.
2. Walk the DoD's **manual verification** on a real/emulated device via `adb`:
   - For each check the DoD implies (e.g. "installs and shows dark themed empty screen",
     "airplane-mode toggle swaps app within ~1s", "2GB movie resumes from byte offset after
     app kill"), perform it (install via `adb install`/`./gradlew installDebug`, drive the
     device/emulator, inspect logs/`adb shell` state as needed) and record **pass/fail**
     for each one explicitly.
   - If anything fails, fix it (or `/diverge` if the DoD item itself is wrong) and re-walk
     the failed checks — do not mark the milestone finished with an outstanding failure.
3. Run `/verify` — must be fully green.
4. Update `STATUS.md` (move milestone's items from Next to Done, set the next milestone's
   name/DoD as the new current milestone or note it's pending) and any relevant
   `docs/features/*.md` / `docs/ARCHITECTURE.md`.
5. Commit: `chore: complete M<N>` (small, scoped to the doc/status updates plus anything
   still uncommitted from the milestone's work — prefer that the milestone's actual code
   already landed via prior `/checkpoint`s).
6. Tag: `git tag m<N>`.

## Notes

- "Manual verification on a device" is not optional or skippable by reasoning about the
  code — actually drive it via `adb`/emulator and report concrete results per check.
- If `adb`/a device or emulator is unavailable, say so explicitly and treat the milestone
  as **not finished** rather than assuming success.
