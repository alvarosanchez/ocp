# GraphQL Queries For `create-pr`

Use these templates with `gh api graphql` when handling review threads.

Large PRs may need pagination because the examples below cap thread and review counts.

## List review threads for a pull request

```bash
gh api graphql -f query='query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          comments(first: 20) {
            nodes {
              id
              databaseId
              body
              url
              createdAt
              author {
                login
              }
            }
          }
        }
      }
    }
  }
}' -F owner=<owner> -F repo=<repo> -F number=<pr-number>
```

Use `databaseId` when you need the REST comment ID for replies, and use `id` when you need the GraphQL thread ID for resolution. Track handled feedback by `databaseId` so a later Copilot cycle cannot hide inside an already-seen thread.

## Resolve a review thread

```bash
gh api graphql -f query='mutation($threadId: ID!) {
  resolveReviewThread(input: {threadId: $threadId}) {
    thread {
      id
      isResolved
    }
  }
}' -F threadId=<thread-id>
```

## Preferred: detect PR review summaries

```bash
gh api graphql -f query='query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviews(first: 20) {
        nodes {
          id
          state
          submittedAt
          author {
            login
          }
          body
        }
      }
    }
  }
}' -F owner=<owner> -F repo=<repo> -F number=<pr-number>
```

Use this query when you need to distinguish a fresh Copilot review event from previously handled thread comments. If the PR has more than 20 reviews, paginate rather than assuming the latest review is in the first page.
