---
name: multi-model-review
description: >
  Independent two-reviewer code review of the current uncommitted changes (or a
  named diff/commit range) in Webnovel-Archiver-Android: dispatch read-only
  reviewers on two different model families through T3 `delegate_task` — GLM
  (Z.ai provider, glm-5.3, effort max) and GPT-6.1 Sol (ChatGPT provider,
  reasoningEffort high) — then verify every finding against the code yourself,
  fix the valid ones, run the validation android/AGENTS.md prescribes, and report
  a per-reviewer verdict table. USE THIS when the user asks for a "multi-model
  review", "cross-model review", "have GLM and Sol review this", "get a second
  opinion from other models", "review with two models", or runs
  /multi-model-review. Do NOT use for a single-model or self review (use
  /code-review), for reviewing a GitHub PR's comments, or as permission to
  commit or push — committing is a separate step the user must request in the
  current message.
---

# Skill: multi-model-review

Two reviewers from different model families catch different bugs; the parent's
job is to brief them well, then act as the skeptical judge. Reviewer output is
**input, not truth** — nothing is fixed until you have confirmed it in the code.

## Hard rules

- Reviewers are **read-only**. They may run targeted unit tests, nothing that
  writes to the tree or touches a device.
- The parent fixes and validates; reviewers never do.
- **No commit, stage, or push** as part of this skill. Per root `AGENTS.md`
  (Git Workflow), that happens only when the user asks in the current message.
- Preserve unrelated worktree changes. If the tree mixes the change under review
  with another thread's work, scope the brief (and your fixes) to the files that
  belong to the change, and tell the reviewers which files are out of scope.
- Device rules from root `AGENTS.md` apply to your own validation: emulator
  only (`emulator-*` serial, debug variant), never the phone unless authorized
  in the current message.

## Reviewer roster

| Reviewer | `providerInstanceId` | `model` | `options` |
|----------|----------------------|---------|-----------|
| GLM | `claudeAgent_zai` | `glm-5.3` | `{ "effort": "max" }` |
| Sol | `codex_cc8f9fad-2ed1-4ce0-9909-8c3e49007e07` | `gpt-6.1-sol` | `{ "reasoningEffort": "high" }` |

- The option names differ: Z.ai uses `effort`, the ChatGPT provider uses
  `reasoningEffort`. Sol defaults to `low`, so always pass `high` explicitly.
- The user wants Sol at **high**, never `max`/`xhigh`/`ultra`.
- The user originally asked for "GLM 5.1"; the catalog offers `glm-5.3` and
  `glm-5.3-flash`. Default to `glm-5.3`. If the user names a model or effort
  that the catalog does not list, **say so and ask** — never silently
  substitute a different model.

## Pipeline

### 1. Confirm providers (once per thread)

Load T3 tools with ToolSearch if they are deferred
(`select:mcp__t3-code__orchestrator_capabilities,mcp__t3-code__delegate_task`).
Call `orchestrator_capabilities` once before the first `delegate_task`, using
`search: "glm"` and `search: "gpt-6.1-sol"` (the short list hides models).
Check that both providers have `canRunChildTask: true` and that the model ids
and option values in the roster still appear. If either is missing or disabled,
report that and ask; do not fall back to native subagents or another provider.

### 2. Scope the change

Before writing the brief, gather what the reviewers need:

```bash
git status --short
git diff --stat            # or: git diff --stat <range>
git ls-files --others --exclude-standard   # untracked files belong in the review
```

Decide which files are in scope (see Hard rules about mixed worktrees). Write a
3–8 line summary of **what changed and why**, naming the key file paths.

### 3. Dispatch both reviewers

Send both `delegate_task` calls in the **same** response, with the same brief:

```jsonc
// GLM
{
  "title": "GLM review: <short change name>",
  "role": "review",
  "mode": "async",
  "target": {
    "providerInstanceId": "claudeAgent_zai",
    "model": "glm-5.3",
    "options": { "effort": "max" }
  },
  "task": "<brief from the template below>"
}
// Sol
{
  "title": "Sol review: <short change name>",
  "role": "review",
  "mode": "async",
  "target": {
    "providerInstanceId": "codex_cc8f9fad-2ed1-4ce0-9909-8c3e49007e07",
    "model": "gpt-6.1-sol",
    "options": { "reasoningEffort": "high" }
  },
  "task": "<brief from the template below>"
}
```

- Leave `isolation` at the default (`shared`). Both reviewers read the same
  checkout at once, which is safe because neither writes. A `worktree` would
  **drop the uncommitted changes** under review.
- Keep the returned `taskId`s. Tell the user in one line that both reviews are
  running, then **end your turn**. Completion notifications re-invoke you. Do
  not poll (`task_status`, sleep loops, Monitor, ScheduleWakeup).

### 4. Review brief template

Children get **no parent history**, so the brief must stand alone. Fill in the
`<…>` parts:

Use this machine's absolute repo path (`git rev-parse --show-toplevel`). It
differs between the user's machines, so never copy a path from an older brief.

```text
You are a READ-ONLY code reviewer for the native Android (Kotlin) app in
<absolute repo root> (Gradle wrapper under android/;
project rules in AGENTS.md and android/AGENTS.md — read them first).

READ-ONLY RULES
- Do not edit, create, delete, format, stage, commit, stash, revert, or check
  out anything. Do not run :app:formatKotlin or any task that rewrites sources.
- Do not use adb, an emulator, or any physical device.
- You MAY run targeted unit tests, e.g.
  android/gradlew -p android :app:testInstrumentationUnitTest --tests "<FQCN or *.ClassName>"
- Another reviewer may be reading this checkout at the same time; that is fine
  because neither of you writes.

WHAT CHANGED AND WHY
<3–8 line summary: the feature/fix, the motivation, the key design decisions.>
Key files:
- <path> — <what changed>
- <path> — <what changed>
<Out of scope (unrelated in-progress work, ignore): <paths or "none">.>

HOW TO SEE THE CHANGE
- Run `git status` and `git diff` <or: `git diff <range>`> from the repo root.
- Untracked files are part of the change: read them in full
  (`git ls-files --others --exclude-standard`).
- Read surrounding code and callers, not just the diff hunks.

WHAT TO LOOK FOR (in priority order)
1. Correctness bugs and data-loss risks (library/story JSON, downloaded
   chapters, backups, reading progress).
2. Edge cases: empty/null/legacy values, first run, very large libraries.
3. Cross-feature interactions (sync, downloads, backup/restore, EPUB, TTS,
   reader, archives, tabs).
4. Compatibility with existing persisted JSON and older backups — new fields
   must deserialize from old data; removed fields must not break restore.
5. Concurrency: coroutines/threads, main-thread I/O, races between engines
   and UI, lifecycle/fold transitions.
6. UI: wrong state, stale rows, layout on folded/unfolded widths.
7. Missed callers, stale docs/AGENTS.md, missing or weak tests for planning
   logic.
8. Simplifications — last, and only if clearly worthwhile.

REPORT FORMAT
- Findings ordered most-severe first. For each:
  - Severity: P1 (crash/data loss) · P2 (broken behavior) · P3 (minor/polish)
  - Location: path:line
  - One-sentence description
  - Concrete failure scenario (inputs/state → wrong result)
  - Confidence: high / medium / low
- No padding, praise, or restating the diff. If you find nothing at a
  severity, say nothing about it.
- End with a short "Could not verify" list (things you suspected but could
  not confirm, tests you could not run).
```

### 5. Triage (after both reports arrive)

If only one report has arrived, wait for the other unless the user says
otherwise. If a reviewer fails or times out, say so and continue with the
other report.

For **every** finding, open the cited code and confirm it yourself:

- **Valid** — the failure scenario actually happens. Fix it.
- **Invalid** — wrong reading of the code, impossible state, or not a bug.
- **Already handled** — guarded elsewhere (cite where).

Give each a one-line reason. Deduplicate across reviewers (note "both" when
they agree, since that is a useful signal). Fix all valid findings, staying
inside the change's scope. When a fix touches planning logic, add or update the
matching JUnit test (`android/AGENTS.md`: "Add or update the matching JUnit
test when changing planning logic"). If a valid finding needs a product
decision or a large redesign, do not guess — list it for the user.

Do not re-dispatch reviewers to check your fixes unless the user asks. If they
ask, start a **new** `delegate_task` with the original brief plus the findings
and your fixes. Do not use `t3_thread_send` to the old child.

### 6. Validate

Pick validation from `android/AGENTS.md` → "Build and Validation":

- Planning/core logic: narrowest relevant tests first
  (`android/gradlew -p android :app:testInstrumentationUnitTest --tests "…"`).
- Kotlin production changes: relevant tests + `:app:lintKotlin` + `:app:detekt`.
- Broad or cross-cutting changes: the per-change gate
  `android/gradlew -p android :app:lintKotlin :app:ci`.
- UI, navigation, lifecycle, foldable, or service changes: also build the
  debug APK and exercise the flow on the emulator (`build-and-install-apk`,
  `dev-launch-screen`, or `emulator-qa` skills).

Capture **Gradle's own** exit code. A pipe such as `| tail` reports the exit
code of `tail`:

```bash
android/gradlew -p android :app:lintKotlin :app:ci --console=plain > "$LOG" 2>&1
echo "gradle exit: $?"
tail -n 40 "$LOG"
```

If `:app:lintKotlin` fails on your own edits, running `:app:formatKotlin` is
acceptable for the parent (never for reviewers). It reformats the whole module,
so check `git diff --stat` afterwards and revert any formatting it applied to
files outside the change's scope.

### 7. Report to the user

- One table per reviewer (or one merged table with a "Raised by" column):

  | # | Finding (path:line) | Severity | Verdict | Reason |
  |---|---------------------|----------|---------|--------|

- What was fixed, file by file, including tests added or updated.
- Valid findings left unfixed, and why (needs a decision, out of scope).
- Validation actually run, with the result, and anything not run (emulator,
  instrumentation tests). Per `android/AGENTS.md`, never claim device
  verification from a build alone.
- State that nothing was committed or pushed. Offer to commit only if the
  user wants it.

## Anti-patterns

- Fixing a finding because a reviewer sounded confident, without reading the
  code.
- Briefs that say "review my changes" with no summary or file list. The child
  has no context and will review the wrong files or the unrelated work.
- `isolation: "worktree"` for a review of uncommitted changes, which gives an
  empty diff.
- Leaving Sol at its default `low`, or raising it to `max`.
- Quietly swapping in a different model when the requested one is missing.
- Polling the children or sending a follow-up into an old child thread.
- Running `:app:formatKotlin` and leaving reformatted unrelated files.
- Committing at the end because the review is done.
