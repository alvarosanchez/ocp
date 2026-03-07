---
name: create-pr
description: Create a GitHub pull request with gh and drive the Copilot review loop until no additional Copilot comments remain. Use when users ask to create a PR, open a pull request, submit a branch for review, or address Copilot review comments after follow-up pushes.
license: MIT
compatibility: Compatible with git and GitHub CLI based workflows in Agent Skills compatible runtimes.
metadata:
  author: local
  version: "1.0.0"
---

# Create PR

## Goal

Open a pull request from the current feature branch with `gh`, then keep the branch moving through Copilot review cycles until Copilot stops leaving additional review comments.

Success means all of the following are true:

- The current work is on a non-default branch.
- Uncommitted changes are either committed safely or explicitly reported as a blocker.
- A pull request exists for the branch.
- Each Copilot review thread receives a reply describing the action taken.
- Each addressed thread is resolved when permissions allow it.
- Follow-up pushes re-request Copilot review without pinging `@copilot` in comments.
- The loop stops only when Copilot produces no additional actionable comments, or a documented blocker prevents further automation.

## Inputs

- Current repository with `git` and `gh` available.
- A checked-out feature branch with changes ready for review.
- Optional user guidance for PR title, body, base branch, target remote, or draft status.
- Authentication that allows `gh pr create`, `gh api`, and push access.
- A runtime that can inspect and edit code when a review comment requires a fix, or a higher-level workflow that will apply the fix after the skill reports it.

## Outputs

- A created PR URL or PR number.
- A short record of each Copilot review cycle, including fixes, replies, and re-request attempts.
- A clear stop condition: no new Copilot comments, timeout, or permission blocker.

## When To Use

Use this skill when the user asks to:

- create a pull request
- open a PR with GitHub CLI
- submit a branch for review
- handle Copilot review comments
- push follow-up fixes and re-request Copilot review

Do not use this skill when the user asks only to:

- commit changes without creating a PR
- merge a PR
- review code locally without GitHub
- inspect CI status without opening or updating a PR

## Procedure

### 1. Confirm prerequisites

Run these checks first:

```bash
git branch --show-current
git status --short --branch
gh auth status
gh repo view --json nameWithOwner,defaultBranchRef
```

If `gh auth status` fails, stop and report the authentication blocker.

### 2. Ensure the branch is safe for PR creation

Read the current branch name and compare it with the repository default branch.

- Stop if the current branch is `main`, `master`, or the repository's default branch.
- Stop if you are in a detached HEAD state.
- If the branch has no upstream yet, determine which remote should host the branch before PR creation.

Use this pattern to capture the repository owner, repo, and default branch:

```bash
gh repo view --json nameWithOwner,defaultBranchRef --jq '{repo: .nameWithOwner, base: .defaultBranchRef.name}'
```

### 3. Inspect local changes and commit only when allowed

Check whether the worktree is clean:

```bash
git status --short
```

If there are uncommitted changes:

1. Review them with `git diff` and `git diff --staged`.
2. Commit them only if the user explicitly asked for a PR flow that includes committing, or if higher-priority runtime instructions already authorize commits.
3. If commits require explicit user approval in the current runtime, stop and report that approval is required before the PR can be created.

When committing is allowed, prefer one focused commit command over interactive flows:

```bash
git add <relevant-files>
git commit -m "<message>"
```

Never commit obvious secrets, generated credentials, or unrelated dirty files.

### 4. Push the branch

Push before creating the PR so the remote branch exists.

First identify the upstream or the remote you should use:

```bash
git rev-parse --abbrev-ref --symbolic-full-name @{upstream}
git remote -v
```

Treat `git rev-parse ... @{upstream}` failing as a normal case when the branch has no upstream yet. It means you should choose the intended remote and continue with `git push -u`, not treat the run as fatally broken.

If the branch already has an upstream, push normally:

```bash
git push
```

If the branch does not yet have an upstream, push to the intended remote and set upstream explicitly:

```bash
git push -u <remote> "$(git branch --show-current)"
```

Stop and report the error if push fails.

### 5. Create the pull request with `gh`

Before creating a new PR, check whether one already exists for the current branch:

```bash
gh pr view --json number,url,headRefName,baseRefName
```

Treat `gh pr view` failing before PR creation as a normal case: it usually means no PR exists yet for the current branch.

If a PR already exists, switch to updating and reviewing that PR instead of creating a duplicate.

Gather a concise title and body from the actual branch diff. Then create the PR with explicit flags instead of relying on an interactive editor. Prefer `--body-file -` so multi-line text is shell-safe.

```bash
gh pr create --title "<title>" --body-file - <<'EOF'
<body>
EOF
```

If the user provided a base branch, honor it even when it is not the repository default branch:

```bash
gh pr create --base "<base-branch>" --title "<title>" --body-file - <<'EOF'
<body>
EOF
```

Use `--draft` only when the user asked for a draft PR:

```bash
gh pr create --draft --title "<title>" --body-file - <<'EOF'
<body>
EOF
```

After creation, record the PR number and URL for all later API calls:

```bash
gh pr view --json number,url,headRefName,baseRefName
```

### 6. Wait for the initial Copilot review

GitHub may trigger the first Copilot review automatically after PR creation. Poll for review activity before taking any follow-up action.

Use a bounded wait loop with backoff. A deterministic default is:

- Poll every 30 seconds for the first 5 minutes.
- Then poll every 60 seconds up to 15 minutes total.
- If no Copilot review appears after 15 minutes, report the timeout and, if appropriate for the repository, request Copilot review explicitly with the same re-request command used later.

Use these calls to inspect review state:

```bash
gh pr view <pr-number> --comments
gh api -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" repos/<owner>/<repo>/pulls/<pr-number>/comments
gh api graphql -f query='query($owner: String!, $repo: String!, $number: Int!) { repository(owner: $owner, name: $repo) { pullRequest(number: $number) { reviewThreads(first: 100) { nodes { id isResolved comments(first: 20) { nodes { id databaseId body url author { login } createdAt } } } } } } }' -F owner=<owner> -F repo=<repo> -F number=<pr-number>
```

See `references/graphql-queries.md` for reusable GraphQL templates.

### 7. Identify Copilot review threads

Treat a thread as a Copilot thread when the comment author login matches a known Copilot review identity. Start with this ordered list for observed comment authors:

1. `copilot-pull-request-reviewer[bot]`
2. `github-copilot[bot]`
3. `github-copilot`

Track handled feedback by review comment ID, not only by thread ID. A later Copilot cycle can add a new comment to an existing thread.

Unless the caller persists that state, assume the handled-comment list lasts only for the current continuous skill run.

If the repository uses a different Copilot identity, record the actual login from the API response and continue with that value.

### 8. Process each Copilot thread

For every unresolved Copilot thread that has not yet been handled in the current cycle:

1. Read the thread carefully.
2. Decide whether code changes are necessary.
3. If changes are necessary, make the smallest correct fix.
4. If changes are not necessary, prepare a reply that explains why no code change was needed.
5. Reply on the thread in all cases. Do not leave a Copilot comment without an explanation.
6. If the runtime cannot inspect or edit code directly, stop after describing the required fix and report the blocker instead of pretending the comment was fully addressed.

Reply to the specific Copilot review comment you are addressing, usually the latest unhandled Copilot comment in the thread. Use the review comment reply endpoint for thread comments:

```bash
gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" repos/<owner>/<repo>/pulls/comments/<comment-id>/replies -f body='<reply>'
```

If reply creation fails because of permissions, API support, or repository policy:

- record the failure clearly
- leave the thread unresolved
- stop the loop if replying is required by the current workflow and no fallback is available

Reply content should always state one of these outcomes:

- what you changed and why it addresses the comment
- why the existing code is already correct
- why the comment cannot be fully addressed yet and what remains blocked

### 9. Resolve the thread when possible

After replying, resolve the review thread only when the repository permissions allow it and there is no remaining unresolved human discussion in that same thread.

Use the GraphQL mutation from `references/graphql-queries.md`:

```bash
gh api graphql -f query='mutation($threadId: ID!) { resolveReviewThread(input: {threadId: $threadId}) { thread { id isResolved } } }' -F threadId=<thread-id>
```

If thread resolution fails because of permissions or API support:

- keep the reply
- leave the thread unresolved
- record the limitation in your status output

Do not pretend the thread is resolved when the API call failed.

### 10. Commit and push follow-up fixes

If you changed code while addressing comments, validate the changes with the normal project checks, then commit and push them under the same commit-safety rules used earlier.

```bash
git status --short
git add <relevant-files>
git commit -m "<message>"
git push
```

If the current runtime forbids autonomous commits, stop after making the fix and report that a user-authorized commit and push are now required before the loop can continue.

### 11. Re-request Copilot review after each follow-up push

New pushes are not guaranteed to trigger another Copilot review automatically. Re-request the review without writing a comment and without mentioning `@copilot` anywhere.

Try these reviewer identities in order until one succeeds. The requestable reviewer slug may differ from the comment-author login you saw in review threads:

```bash
gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" repos/<owner>/<repo>/pulls/<pr-number>/requested_reviewers -F 'reviewers[]=github-copilot[bot]'
gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" repos/<owner>/<repo>/pulls/<pr-number>/requested_reviewers -F 'reviewers[]=copilot-pull-request-reviewer[bot]'
gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" repos/<owner>/<repo>/pulls/<pr-number>/requested_reviewers -F 'reviewers[]=github-copilot'
```

Request only one reviewer identity per attempt. If the first call succeeds, do not send the fallback.

If the reviewer is already requested or already pending, treat that as a no-op and continue waiting. If all attempts fail with validation or unsupported-reviewer errors, stop and report that Copilot review could not be re-requested through the API in this repository.

### 12. Repeat the review loop with a hard cap

Run the cycle below until Copilot stops leaving additional comments:

1. Wait for a new Copilot review using the same bounded polling strategy.
2. Fetch unresolved review threads again.
3. Process only new Copilot comments whose comment IDs were not handled in prior cycles.
4. Reply and resolve each thread.
5. Commit and push any resulting fixes.
6. Re-request Copilot review if anything was pushed.

Use a maximum of 5 Copilot review cycles by default. If the fifth cycle still produces new comments, stop and report that manual intervention is required to prevent an unbounded automation loop.

### 13. Stop only on a clear terminal state

Finish the skill run only when one of these conditions is true:

- Copilot produced a review cycle with no additional comments.
- No unresolved Copilot threads remain, and no new Copilot comments appeared after the latest push.
- Authentication, permissions, or review-request support blocked further automation and you reported the blocker clearly.
- The review loop hit the maximum cycle limit.

Do not merge the PR as part of this skill unless a higher-priority instruction explicitly expands the scope.

## Error Handling And Edge Cases

- If the worktree includes unrelated dirty files, do not commit them just to create the PR.
- If no Copilot review arrives and explicit re-request also fails, stop and report the repository limitation.
- If GraphQL thread listing returns partial data, fall back to `gh pr view --comments` and report reduced automation.
- If GraphQL results hit page-size limits on large PRs, continue with pagination-aware queries or report that only a partial thread set was inspected.
- If reply creation succeeds but thread resolution fails, keep the reply and continue.
- If a thread contains both Copilot feedback and unresolved human discussion, do not auto-resolve the entire thread unless your reply fully closes the remaining human context too.
- If a comment requests a change you intentionally reject, reply with a concise explanation before resolving or reporting the limitation.
- If the same logical issue keeps returning in new Copilot cycles, stop after the cycle cap and summarize the repeated feedback.

## Safety Rules

- Treat commits, pushes, and PR creation as externally visible actions. Follow any higher-priority runtime rules that require explicit user authorization.
- Never force-push unless the user explicitly requested a history rewrite.
- Never re-request Copilot review by writing a comment that mentions `@copilot`.
- Never resolve a thread silently; always leave a reply first.
- Never claim a fix was applied, a thread was resolved, or a review was re-requested unless the command output confirms it.
- Never merge as part of this skill's default flow.

## Examples

These prompts should trigger this skill:

- "Create a PR for my current branch and handle the Copilot review comments."
- "Open a pull request with gh, fix Copilot feedback, and keep looping until Copilot is done."
- "Submit this feature branch for review and re-request Copilot after each follow-up push."

These prompts should not trigger this skill:

- "Commit my local changes."
- "Merge the current pull request once CI passes."
- "Explain how GitHub Copilot code review works."

## Validation Checklist

- [ ] Frontmatter is valid YAML.
- [ ] `name` is `create-pr` and matches the folder name.
- [ ] `description` states both capability and trigger context.
- [ ] The skill stays focused on PR creation and Copilot review loops, not merge automation.
- [ ] All references to extra files resolve relative to the skill root.
- [ ] No instruction tells the agent to mention `@copilot` in a comment.
- [ ] The review loop has a bounded wait strategy and a maximum cycle cap.
