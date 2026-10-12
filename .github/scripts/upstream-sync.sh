#!/usr/bin/env bash
# Merges storytold/vectorcraft's main into a sync branch on top of BASE (see docs/upstream-sync.md).
# Usage: upstream-sync.sh BASE SYNC_BRANCH
# Writes to $GITHUB_OUTPUT (and stdout): status = up-to-date | pending | merged | conflict,
# upstream_sha, count (upstream commits not yet in BASE), conflicts (space-separated paths).
# Only Cargo.lock conflicts are resolved here (take upstream's, let cargo re-add our crates);
# anything else is left for a person, since picking a side can lose behaviour.
set -euo pipefail

base=${1:?base branch}
sync=${2:?sync branch}
upstream_url=${UPSTREAM_URL:-https://github.com/storytold/vectorcraft.git}
upstream_branch=${UPSTREAM_BRANCH:-main}

out() {
  echo "$1=$2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi
}

git config user.name >/dev/null || git config user.name "github-actions[bot]"
git config user.email >/dev/null || git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$upstream_url"
git fetch --no-tags upstream "+refs/heads/$upstream_branch:refs/remotes/upstream/$upstream_branch"
git fetch --no-tags origin "+refs/heads/$base:refs/remotes/origin/$base"
git fetch --no-tags origin "+refs/heads/$sync:refs/remotes/origin/$sync" 2>/dev/null || true

up=$(git rev-parse "upstream/$upstream_branch")
out upstream_sha "$up"
out count "$(git rev-list --count "origin/$base..$up")"

if git merge-base --is-ancestor "$up" "origin/$base"; then
  out status up-to-date
  exit 0
fi
# A sync branch that already holds this upstream commit and still sits on BASE was handled by an
# earlier run (its PR is open, waiting on CI or a person).
if git rev-parse -q --verify "origin/$sync" >/dev/null &&
  git merge-base --is-ancestor "$up" "origin/$sync" &&
  git merge-base --is-ancestor "origin/$base" "origin/$sync"; then
  out status pending
  exit 0
fi

# Build on an open sync branch (it may carry a person's fix for a red gate) unless it has landed;
# bring BASE into it first so landing stays a fast-forward. Never rewrite it.
if git rev-parse -q --verify "origin/$sync" >/dev/null &&
  ! git merge-base --is-ancestor "origin/$sync" "origin/$base"; then
  git checkout -q -B "$sync" "origin/$sync"
  if ! git merge-base --is-ancestor "origin/$base" HEAD; then
    if ! git merge --no-ff --no-edit -m "Merge $base into $sync" "origin/$base"; then
      conflicts=$(git diff --name-only --diff-filter=U | tr '\n' ' ' | sed 's/ $//')
      git merge --abort
      out conflicts "$conflicts"
      out status conflict
      exit 0
    fi
  fi
else
  git checkout -q -B "$sync" "origin/$base"
fi
msg="Merge upstream storytold/vectorcraft@${up:0:12}"
if ! git merge --no-ff --no-edit -m "$msg" "$up"; then
  conflicts=$(git diff --name-only --diff-filter=U | tr '\n' ' ' | sed 's/ $//')
  if [ "$conflicts" = "Cargo.lock" ]; then
    git checkout --theirs Cargo.lock
    cargo metadata --format-version 1 >/dev/null
    git add Cargo.lock
    git commit -q --no-edit
  else
    git merge --abort
    out conflicts "$conflicts"
    out status conflict
    exit 0
  fi
fi

# Our crates (crates/ffi and its dependencies) may need lock entries upstream's lockfile lacks.
cargo metadata --format-version 1 >/dev/null
if ! git diff --quiet -- Cargo.lock; then
  git add Cargo.lock
  git commit -q -m "Cargo.lock: refresh after merging upstream ${up:0:12}"
fi
out status merged
