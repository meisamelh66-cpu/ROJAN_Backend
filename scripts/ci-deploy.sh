#!/usr/bin/env bash
# CI/CD deployment wrapper - fetches and checks out an exact commit, then
# delegates to the existing scripts/deploy.sh unchanged. Designed to be
# the SSH forced-command target for the GitHub Actions deploy key (not
# yet wired in - see ROJAN_CICD_Deployment_Architecture_Fix_Plan_v1.md).
#
# deploy.sh deliberately never does its own git pull/checkout (operator
# controls that explicitly) - this script exists specifically to add that
# step safely for an automated, unattended caller, without changing
# deploy.sh itself at all.
#
# Input: the target commit SHA, via $SSH_ORIGINAL_COMMAND - this is how
# OpenSSH exposes a forced-command session's originally-requested command
# even though the forced command is what actually runs, letting the
# GitHub Actions workflow pass e.g. `ssh ... "$GITHUB_SHA"` and have the
# server-side wrapper receive it. For manual/local testing without SSH,
# set the same env var directly:
#   SSH_ORIGINAL_COMMAND=<sha> ./scripts/ci-deploy.sh
#
# Rollback notes: this script has no state of its own - if it misbehaves,
# the fix is to point the authorized_keys `command=` restriction back at
# scripts/deploy.sh directly (the prior, already-proven-working state);
# nothing here needs to be undone on its own. Because every deploy
# targets an exact SHA, "rolling back" a bad deploy is simply re-invoking
# this script with the previous known-good SHA - scripts/rollback.sh
# remains available as the simpler fallback for reverting to the
# immediately-prior commit without needing to look up its SHA.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TARGET_REF="${SSH_ORIGINAL_COMMAND:-}"

if [ -z "$TARGET_REF" ]; then
  echo "No target commit provided (expected via \$SSH_ORIGINAL_COMMAND)." >&2
  exit 1
fi

# Strict validation - a bare 7-40 char hex commit SHA only. This is the
# one place external input reaches a script that runs with rojan-deploy's
# full sudo/Docker/filesystem grants, so it is treated as untrusted
# regardless of the fact that the SSH key already authenticated - never a
# branch name, never a ref expression, never anything that could contain
# shell metacharacters.
if ! [[ "$TARGET_REF" =~ ^[a-fA-F0-9]{7,40}$ ]]; then
  echo "Target must be a commit SHA (7-40 hex characters), got: $TARGET_REF" >&2
  exit 1
fi

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "GITHUB_TOKEN is not set - required for the HTTPS git fetch below." >&2
  exit 1
fi

# 2026-08-29 deploy hardening: this VPS's git sync used to go over the SSH remote
# (git@github-backend:...). Confirmed by repeated live reproduction that GitHub's SSH frontend
# intermittently hangs mid key-exchange from this VPS, independent of retry count or KEX algorithm
# - the exact same failure mode already root-caused and fixed for the website's deploy pipeline
# (see apps/website's .github/workflows/web-production-deploy.yml, "STEP: GIT SYNC"). Applying the
# same fix here: sync over HTTPS (443) instead of SSH (22). The origin remote is a plain
# https://github.com/... URL with no embedded token (safe to print via `git remote -v`); the real
# credential is supplied per-invocation via a transient `http.extraheader` - the same mechanism
# actions/checkout itself uses - computed once, never written to .git/config or otherwise
# persisted to disk, and unset immediately after the fetch loop.
git remote set-url origin https://github.com/meisamelh66-cpu/ROJAN_Backend.git
git remote -v

echo "==> Fetching and checking out $TARGET_REF"
# Fetching the exact SHA (not just `git fetch origin`) guarantees the
# commit is retrieved even when it isn't the tip of any locally-tracked
# branch/ref - required to deploy an exact SHA reliably regardless of
# whether it's already present locally.
AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)"
GIT_FETCH_ATTEMPTS=3
GIT_FETCH_TIMEOUT_SECONDS=30
GIT_FETCH_RETRY_DELAY_SECONDS=10
fetch_succeeded=0
for attempt in $(seq 1 "$GIT_FETCH_ATTEMPTS"); do
  echo "git fetch origin $TARGET_REF over HTTPS (attempt $attempt/$GIT_FETCH_ATTEMPTS, ${GIT_FETCH_TIMEOUT_SECONDS}s timeout)"
  if timeout "${GIT_FETCH_TIMEOUT_SECONDS}s" git -c http.extraheader="$AUTH_HEADER" fetch origin "$TARGET_REF"; then
    fetch_succeeded=1
    break
  fi
  echo "git fetch attempt $attempt failed or timed out."
  if [ "$attempt" -lt "$GIT_FETCH_ATTEMPTS" ]; then
    echo "retrying in ${GIT_FETCH_RETRY_DELAY_SECONDS}s..."
    sleep "$GIT_FETCH_RETRY_DELAY_SECONDS"
  fi
done
unset AUTH_HEADER
if [ "$fetch_succeeded" -ne 1 ]; then
  echo "git fetch origin $TARGET_REF (HTTPS) failed after $GIT_FETCH_ATTEMPTS attempts - aborting deploy." >&2
  exit 1
fi
git checkout "$TARGET_REF"

echo "==> Delegating to scripts/deploy.sh (unchanged)"
exec ./scripts/deploy.sh
