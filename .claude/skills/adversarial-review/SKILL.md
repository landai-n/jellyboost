---
name: adversarial-review
description: Run the multi-agent adversarial review gate over a finished feature/fix wave's diff — parallel reviewer lenses, each finding verified by a refute-biased skeptic, confirmed findings fixed before merge. Use at the end of every feature or fix wave, before merging its branch (or before its final checkpoint when working directly on main).
---

# /adversarial-review

The semantic quality gate: a Workflow of parallel reviewer agents over the wave's diff,
with every finding adversarially verified before it is allowed to block. It exists because
the mechanical gates cannot see semantic defects — its first run found six confirmed bugs
(an index crossing two projections of the same table among them) in a wave `/verify` had
passed green.

**When:** once per feature/fix wave, at the end — after the implementation commits exist,
before the branch merges to main (or before the final `/checkpoint` when the work landed
directly on main). Not per commit: `/checkpoint` stays the per-sub-task gate, this is the
per-wave one. A wave that is only docs, or a mechanical rename with no behavior change,
may skip it — say so explicitly when doing so.

## What this gate does NOT check (owned elsewhere — do not duplicate)

Reviewer prompts MUST tell agents to skip these classes; a finding in an owned class is
noise unless it is a *semantic instance the owning tool cannot see*:

| Class | Owner |
|---|---|
| Formatting, style, import order | `ktlintCheck` (in `/verify`) |
| Complexity/size thresholds, forbidden imports | `detekt` (in `/verify`) |
| Unit-test regressions | `testDebugUnitTest` (in `/verify`) |
| Static accessibility (contentDescription, touch targets lint can see) | `:app:lintDebug` (in `/verify`) |
| Audit-hazard patterns (`runCatching`, `!!`, `runBlocking`, hardcoded dispatchers, no-locale case ops, `composed {}`) | `scripts/check_patterns.py` |
| Personal/infrastructure identifiers | `scripts/check_identifiers.py` |
| Doc structure/staleness, redaction, a11y test scaffolding presence, build entry point | the other four guardrail scripts |
| Comment voice (narrative vs constraint) | the `comment-voice-gate` pre-commit hook |
| Runtime Compose semantics on a device | the instrumented suite at milestone DoD |

What it DOES check is the audit checklist's territory (CLAUDE.md "Audit-derived review
checklist") — the classes no tool catches: cross-projection index/identity bugs, unfixed
siblings, failure misclassification, lifetime/ownership mistakes, per-tick perf
regressions, untested doc claims, weakened tests, semantic a11y regressions.

## Steps

1. **Scope the diff.** `BASE` = the merge-base with main (worktree branch) or the first
   commit of the wave (direct on main). Small fix (≤ ~10 files): 3 lenses. Full wave: 5.

2. **Launch the Workflow** (session workflow scripts from previous runs are reusable
   templates — `downloads-wave-review` / `series-header-review` are the canonical shapes).
   Structure:
   - `phase('Review')`: one agent per lens, `pipeline()` so each lens's findings verify as
     soon as that lens finishes. Lens set, partitioned so no two lenses own the same class:
     **correctness** (logic bugs with a concrete failing trace), **sibling-parity**
     (compact/wide, both tabs, both call sites, fixtures — the repo's most recidivist
     class), **compose-perf** (stability, skipping, per-emission cost), **a11y-semantics**
     (merged sentences, headings, live regions — static review), **governance-tests**
     (every doc claim pinned, no weakened tests, DECISIONS/STATUS accuracy). Small fixes
     merge compose-perf into sibling-parity and a11y into governance.
   - Each lens prompt carries: the worktree path (read-only), `BASE..BRANCH`, a summary of
     the wave's *intent*, the house rules the diff touches, the do-not-duplicate table
     above, and "an empty findings list is a fine answer".
   - `phase('Verify')`: one refute-biased skeptic per finding (`effort: 'high'`,
     "try hard to REFUTE; default real=false unless the scenario concretely reproduces").
   - Return `{confirmed, refuted}`.

3. **Act on confirmed findings only.** Fix them (delegate back to the implementing agent
   when it still has context; inline when trivial), each fix gated by `/verify` as usual.
   Zero confirmed findings → proceed. A disputed finding needs evidence, not a shrug —
   record the dispute in the report to the user.

4. **Merge and independently `/verify`** the merged tree (never trust the branch's green
   claim), then finish the wave's `/checkpoint` flow.

5. **Report:** confirmed count, refuted count, what was fixed, anything disputed.

## Notes

- The gate is orchestrator-run (it needs model access), so unlike the script gates it
  cannot be enforced by the pre-commit hook — CLAUDE.md's workflow expectations are its
  wiring. Treat "merged without review" exactly like "committed without verify".
- Cost scales with lens count and findings; the refute pass is what keeps
  plausible-but-wrong findings from burning fix cycles. Do not skip it to save tokens.
