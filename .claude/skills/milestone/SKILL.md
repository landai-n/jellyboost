---
name: milestone
description: Start or finish a Jellyboost milestone (M0-M10) from docs/PLAN.md. Use when asked to start work on a milestone, or to close one out with full device verification, docs, commit, and tag. Args - "start Mn" or "finish Mn".
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
     app kill"), perform it (build with `gradlew-remote :app:assembleDebug`, then
     `adb install -r app/build/outputs/apk/debug/app-debug.apk` — the build host and this
     machine share a debug keystore, so the APK installs over a locally-built one; drive
     the device/emulator, inspect logs/`adb shell` state as needed) and record **pass/fail**
     for each one explicitly.
   - If anything fails, fix it (or `/diverge` if the DoD item itself is wrong) and re-walk
     the failed checks — do not mark the milestone finished with an outstanding failure.
3. Run the **instrumented accessibility suite** on the connected device — it is not part of
   `/verify` (which must stay device-free) and this is where it is owed:

   ```bash
   adb shell input keyevent KEYCODE_WAKEUP   # the OEM ROM refuses installs with the screen off
   gradlew-remote connectedDebugAndroidTest
   ```

   These are the ATF + Compose-semantics tests that hold the 2026-08-05 accessibility audit's
   fixes in place (merged card nodes, live regions, chrome traversal order, the player's
   controls-reveal action). A failure here is a DoD failure — fix it, don't skip it.
4. Run `/verify` — must be fully green.
5. Update `STATUS.md` (move milestone's items from Next to Done, set the next milestone's
   name/DoD as the new current milestone or note it's pending) and any relevant
   `docs/features/*.md` / `docs/ARCHITECTURE.md`.
6. Commit: `chore: complete M<N>` (small, scoped to the doc/status updates plus anything
   still uncommitted from the milestone's work — prefer that the milestone's actual code
   already landed via prior `/checkpoint`s).
7. Tag: `git tag m<N>`.

## Notes

- "Manual verification on a device" is not optional or skippable by reasoning about the
  code — actually drive it via `adb`/emulator and report concrete results per check.
- If `adb`/a device or emulator is unavailable, say so explicitly and treat the milestone
  as **not finished** rather than assuming success.
