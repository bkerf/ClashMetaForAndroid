#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
LOCAL_BRANCH="${LOCAL_BRANCH:-main}"
ORIGIN_REMOTE="${ORIGIN_REMOTE:-origin}"
DEFAULT_UPSTREAM_URL="https://github.com/MetaCubeX/ClashMetaForAndroid.git"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Error: sync-upstream.sh must be run inside a git repository" >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "Error: working tree is dirty. Commit or stash changes before syncing." >&2
  exit 1
fi

if ! git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1; then
  echo "Remote '$UPSTREAM_REMOTE' not found. Adding it now (URL: $DEFAULT_UPSTREAM_URL)."
  git remote add "$UPSTREAM_REMOTE" "$DEFAULT_UPSTREAM_URL"
fi

declare -r CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
trap 'git checkout "$CURRENT_BRANCH" >/dev/null 2>&1 || true' EXIT

git fetch "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"

git checkout "$LOCAL_BRANCH"

# Use fast-forward merge to avoid creating merge commits.
git merge --ff-only "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"

git push "$ORIGIN_REMOTE" "$LOCAL_BRANCH"

echo "✔ Sync complete: $LOCAL_BRANCH now matches $UPSTREAM_REMOTE/$UPSTREAM_BRANCH and was pushed to $ORIGIN_REMOTE."