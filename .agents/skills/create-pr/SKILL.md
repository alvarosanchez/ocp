---
name: create-pr
description: Create or update a GitHub pull request with gh, including an initial commit when the worktree is dirty, then drive both Copilot review and CI remediation loops until Copilot stops leaving actionable comments and required CI checks are green. Use when users ask to create a PR, open a pull request, submit a branch for review, create a PR from current work, or continue a PR after follow-up fixes.
license: MIT
compatibility: Compatible with git and GitHub CLI based workflows in Agent Skills compatible runtimes.
metadata:
  author: local
  version: "2.2.0"
---

# Create PR

## Goal

Create or update a GitHub PR for the current feature branch, including an initial commit when local changes are present and commits are allowed, then keep the PR moving through a CI-green-first loop followed by a Copilot review loop until there are no remaining actionable Copilot comments and required checks are green.

## When To Use

Use this skill when the user asks to:

- create a PR
- open a PR
- open a pull request
- create a PR from current work
- submit a branch for review
- continue a PR after fixes
- handle Copilot review comments on a PR
- wait for CI and keep fixing until green
- do the full PR workflow with `gh`

Strong trigger phrases include:

- "create a PR"
- "open a PR"
- "open a pull request"
- "submit this branch for review"
- "create PR with gh"
- "look into this and create a PR"
- "fix the review comments on that PR"
- "keep working until Copilot and CI are green"

Do not use this skill when the user asks only to:

- commit changes without creating or updating a PR
- explain GitHub PR workflows
- inspect CI status without opening or updating a PR
- merge a PR

## Skill Layout

This skill uses deterministic shell scripts in `.agents/skills/create-pr/scripts/` for repeated operations:

- `.agents/skills/create-pr/scripts/preflight.sh`
- `.agents/skills/create-pr/scripts/inspect-worktree.sh`
- `.agents/skills/create-pr/scripts/commit-if-dirty.sh`
- `.agents/skills/create-pr/scripts/push-branch.sh`
- `.agents/skills/create-pr/scripts/pr-view.sh`
- `.agents/skills/create-pr/scripts/pr-create.sh`
- `.agents/skills/create-pr/scripts/copilot-review-state.sh`
- `.agents/skills/create-pr/scripts/reply-to-review-comment.sh`
- `.agents/skills/create-pr/scripts/resolve-review-thread.sh`
- `.agents/skills/create-pr/scripts/ci-status.sh`
- `.agents/skills/create-pr/scripts/ci-run-log.sh`
- `.agents/skills/create-pr/scripts/request-copilot-review.sh`

## Operating Procedure

### 1. Preflight

Run:

```bash
./.agents/skills/create-pr/scripts/preflight.sh
```

Stop and report a blocker if authentication fails, the branch is detached, or the current branch matches the repository default branch.

### 2. Inspect and commit dirty worktree changes

Run:

```bash
./.agents/skills/create-pr/scripts/inspect-worktree.sh
```

If the worktree is dirty and commits are allowed by higher-priority runtime rules, create the initial commit before any push or PR creation:

```bash
./.agents/skills/create-pr/scripts/commit-if-dirty.sh "<commit-message>" <relevant-files...>
```

If commits are not allowed in the surrounding runtime, stop and report that PR creation is blocked on user-authorized commit/push.

### 3. Push the branch and ensure upstream exists

Run:

```bash
./.agents/skills/create-pr/scripts/push-branch.sh [remote]
```

Default remote is `origin`.

### 4. Reuse or create the PR

First inspect current PR state:

```bash
./.agents/skills/create-pr/scripts/pr-view.sh
```

If a PR already exists for the branch, reuse it.

If no PR exists, create one with a prepared title/body:

```bash
./.agents/skills/create-pr/scripts/pr-create.sh <base-branch> <title> <body-file> [--draft]
```

Then run `./.agents/skills/create-pr/scripts/pr-view.sh` again and record the PR number and URL.

### 5. Wait for CI first, then Copilot review

CI and Copilot review are both required, but the default loop order is CI first, then Copilot review. Do not treat Copilot review as terminal while required checks are still failing or incomplete.

Run:

```bash
./.agents/skills/create-pr/scripts/ci-status.sh <pr-number>
./.agents/skills/create-pr/scripts/copilot-review-state.sh <owner> <repo> <pr-number>
```

Default bounded waiting policy:

- poll every 30 seconds for the first 5 minutes
- then poll every 60 seconds up to 20 minutes total per cycle
- if neither CI nor Copilot has produced usable state by then, report the timeout clearly

### 6. Process Copilot review comments after CI is green

Only enter the Copilot remediation loop after required CI checks are green or otherwise successful for the current PR head. If CI is still failing or pending, continue the CI loop first.

Treat a thread as Copilot-owned when the author login matches one of these values:

1. `copilot-pull-request-reviewer[bot]`
2. `github-copilot[bot]`
3. `github-copilot`

Track handled feedback by review comment ID, not only thread ID.

For each new unresolved Copilot comment:

1. inspect the requested change
2. apply the smallest correct fix when necessary
3. reply on the thread even if no code change is needed
4. confirm the reply command actually succeeded from command output
5. resolve the thread immediately after a confirmed reply when no human discussion remains open

Use:

```bash
./.agents/skills/create-pr/scripts/reply-to-review-comment.sh <owner> <repo> <pr-number> <comment-id> <reply-body>
./.agents/skills/create-pr/scripts/resolve-review-thread.sh <thread-id>
```

If reply or resolve fails, report the concrete limitation instead of claiming success.

Thread-resolution enforcement rule:

- Replying is not enough. After a confirmed reply, you MUST attempt `resolve-review-thread.sh` for that thread.
- Do not resolve a Copilot-owned thread unless reply creation has been confirmed, or you explicitly report that the supported reply mechanism failed for that exact thread due to a permissions/platform limitation.
- Treat any unresolved Copilot-owned thread as actionable until resolution is confirmed in the latest fetched review-thread set.
- Do not declare Copilot remediation complete while Copilot-owned threads remain unresolved unless you explicitly report a permissions/platform limitation.

Operational rule for completion checks:

- Never infer Copilot completion from previously handled comment IDs alone.
- After every push, re-fetch the full current review-thread set and treat any unresolved Copilot-owned thread as actionable, even if earlier Copilot threads were already resolved.
- Outdated Copilot-owned threads that remain unresolved are still actionable until resolution is confirmed in the latest fetched thread set.
- A top-level Copilot review summary in `COMMENTED` state is not actionable by itself, but it must trigger a fresh full sweep of review threads because it may accompany newly opened inline comments.

### 7. Wait for CI and remediate failures

CI is part of the loop, not an optional side check.

After PR creation and after every push:

1. run `./.agents/skills/create-pr/scripts/ci-status.sh <pr-number>`
2. if required checks are pending, keep waiting
3. if a required check fails, inspect the failing run with:

```bash
./.agents/skills/create-pr/scripts/ci-run-log.sh <run-id>
```

Then fix only the root cause, run the closest local verification, and continue the loop.

### 8. Commit and push follow-up fixes

If either Copilot or CI remediation changes code, validate locally and then commit/push under the same rules used earlier:

```bash
./.agents/skills/create-pr/scripts/commit-if-dirty.sh "<commit-message>" <relevant-files...>
./.agents/skills/create-pr/scripts/push-branch.sh [remote]
```

If autonomous commits are not allowed by the runtime, stop and report that the loop is blocked on user-authorized commit/push.

### 9. Re-request Copilot review after each follow-up push

After a follow-up push, re-request Copilot review using the remove/add workaround:

```bash
gh pr edit <pr-number> --remove-reviewer @copilot || true
gh pr edit <pr-number> --add-reviewer @copilot
```

Use the skill script wrapper so repository scoping and result validation are consistent:

```bash
./.agents/skills/create-pr/scripts/request-copilot-review.sh <owner> <repo> <pr-number>
```

This is a required step after every remediation push, not an optional best-effort extra. Do not continue to the next wait cycle until you have attempted the re-request and recorded the concrete result.

Validation rule for execution:

- A follow-up push is incomplete until `request-copilot-review.sh` has been run for that PR.
- Treat a successful re-request as grounded only when command output confirms which reviewer slug was accepted.
- Record the PR head SHA at re-request time and use it as the review-completion target for the next Copilot wait cycle.
- If all reviewer-request attempts fail because the repository does not support it, report that limitation explicitly and only continue if automatic Copilot review still appears on subsequent pushes.

If all reviewer-request attempts fail because the repository does not support it, report that limitation and continue waiting only if automatic review still appears on new pushes.

### 10. Repeat the PR loop with CI first, then Copilot

Run this combined cycle until terminal success or a documented blocker:

1. wait for CI state
2. investigate failing CI checks
3. fix code when needed
4. verify locally
5. commit and push when allowed
6. re-request Copilot review after pushes
7. wait for Copilot review completion for the re-requested latest green head
8. confirm Copilot has either posted review activity for that head or hit a clearly reported timeout/limitation
9. process new Copilot comments
10. fix code when needed
11. verify locally
12. commit and push when allowed
13. re-request Copilot review after pushes
14. wait again for CI first, then Copilot

Copilot review completion rule:

- Do not treat `zero unresolved threads` alone as proof that Copilot is finished for the latest green head.
- After a re-request, wait until Copilot has produced observable review activity for that head (review, thread, or comment) or until the bounded wait policy expires and you report the timeout/limitation explicitly.
- Treat Copilot review completion for terminal-state purposes as valid only when the newest relevant Copilot review is tied to the current PR head SHA, not an older push.
- Always re-fetch the current PR head SHA after every push and compare it to the commit OID of the latest Copilot review before concluding that the latest head has been reviewed.
- Operationally, do not rely on human inspection alone: `copilot-review-state.sh` must surface the current `headRefOid`, the newest Copilot review `commit.oid`, and enough thread state to decide whether the latest head has actually been reviewed.
- After CI turns green for a pushed head, you MUST run `copilot-review-state.sh` again and treat the loop as incomplete unless the newest Copilot review commit matches the current head SHA or the bounded wait timeout has been reached and reported explicitly.
- Only after that completion check may you decide that there are no actionable Copilot comments remaining.

Default caps:

- maximum 5 Copilot remediation cycles
- maximum 5 CI remediation cycles
- maximum 8 combined push/fix cycles total

If a cap is reached, stop and report why the loop did not converge.

### 11. Terminal success condition

Stop only when all of these are true:

- no unresolved required CI failures remain
- required checks are green or otherwise successful
- Copilot review for the latest green CI head has completed or timed out with an explicit reported limitation
- the latest Copilot review used for completion corresponds to the current PR head SHA
- no new actionable Copilot comments remain after the latest green CI head has been reviewed
- zero unresolved Copilot-owned review threads remain in the latest fetched review-thread set
- after the last thread resolution, the full review-thread set has been re-fetched once more and still shows zero unresolved Copilot-owned threads
- the branch reflects all fixes already pushed to the PR

Do not merge the PR as part of this skill unless a higher-priority instruction explicitly expands scope.

## Error Handling And Edge Cases

- If the worktree includes unrelated dirty files, do not commit them just to create the PR.
- If the branch has no commits relative to base, stop and report that there is nothing to open a PR from.
- If the branch has not been pushed, push it before attempting PR creation.
- If `gh pr create` returns `Head sha can't be blank` or `Head ref must be a branch`, treat that as a pushed-branch/pre-PR-state problem, not a mysterious gh failure.
- If no Copilot review arrives and explicit re-request fails, report the limitation clearly.
- If CI is pending for too long, report the exact run IDs and statuses instead of saying only that it is "still running."
- If CI fails repeatedly for the same reason after fixes, stop at the cycle cap and summarize the repeated failure.
- If reply creation succeeds but thread resolution fails, keep the reply and record the limitation.
- If the supported REST reply path fails, report that exact per-thread limitation and do not claim that the thread was commented on.
- If a thread contains unresolved human discussion, do not auto-resolve it unless your reply closes the remaining context too.

## Safety Rules

- Treat commits, pushes, PR creation, and PR comments as externally visible actions.
- Follow higher-priority runtime rules that require explicit user authorization for commits or pushes.
- Never force-push unless the user explicitly requested a history rewrite.
- Never mention `@copilot` in a comment to trigger review.
- Never claim a fix, push, reply, check status, or green CI result unless command output confirms it.
- Never merge as part of this skill’s default flow.

## Validation Checklist

- [ ] Frontmatter is valid YAML.
- [ ] `name` is `create-pr` and matches the folder name.
- [ ] `description` includes PR creation, dirty-worktree commit handling, Copilot review, and CI loop triggers.
- [ ] The workflow includes an initial commit step for dirty worktrees when commits are allowed.
- [ ] The workflow treats CI as a required wait-and-remediate loop, not just Copilot review.
- [ ] Repeated shell operations live under `.agents/skills/create-pr/scripts/`.
- [ ] Trigger phrases are broad enough to catch plain "create a PR" requests.
- [ ] The skill does not instruct the agent to mention `@copilot`.
- [ ] The loop has bounded wait policies and bounded remediation caps.
