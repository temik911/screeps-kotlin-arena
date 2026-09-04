#!/bin/zsh
# Landing: bring a session branch into the shared `main` (see "Parallel sessions" in CLAUDE.md, rule 5).
#   tools/land.sh [--tag <arena>-vN] [--no-push] [--no-stub]
# Run from your worktree, on your session branch. Steps, each of which stops the landing when it fails:
#   1. the branch is not main, has no uncommitted changes, and is rebased on main (main is an ancestor of HEAD);
#   2. ./gradlew build;
#   3. every tools/stub/*/regress.sh — every scenario must end with the enemy spawn destroyed and zero errors;
#   4. fast-forward main: `git fetch . <branch>:main` when main is checked out nowhere, else `git merge --ff-only`
#      in the checkout that has main (only if that tree is clean — the one thing a session may do to the root);
#   5. tag the landed commit if asked;
#   6. push main (and the tag) to origin — pushing main is part of landing; session branches are pushed by hand.
set -e
cd "$(git rev-parse --show-toplevel)"
tag=""; push=1; stub=1
while (( $# )); do
  case "$1" in
    --tag) tag="$2"; shift 2;;
    --no-push) push=0; shift;;
    --no-stub) stub=0; shift;;
    *) echo "land: unknown option $1"; exit 2;;
  esac
done
branch=$(git branch --show-current)
if [[ -z "$branch" || "$branch" == main ]]; then echo "land: run on a session branch, not on main or a detached HEAD"; exit 1; fi
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then echo "land: uncommitted changes — commit by explicit paths first"; exit 1; fi
if ! git merge-base --is-ancestor main HEAD; then echo "land: main has moved — git rebase main, rebuild, rerun the stub, then land again"; exit 1; fi
if [[ "$(git rev-parse main)" == "$(git rev-parse HEAD)" ]]; then echo "land: nothing to land — main already is $(git rev-parse --short HEAD)"; exit 0; fi

echo "land: build"
./gradlew build -q

if (( stub )); then
  for r in tools/stub/*/regress.sh; do
    [[ -f "$r" ]] || continue
    echo "land: stub $r"
    out=$(zsh "$r" land)
    print -r -- "$out"
    if [[ -z "$out" ]] || print -r -- "$out" | grep -vqE 'ENEMY SPAWN DESTROYED.*errors: 0 '; then
      echo "land: stub regression failed — a scenario did not destroy the enemy spawn with zero errors"; exit 1
    fi
  done
fi

mainwt=$(git worktree list --porcelain | awk '/^worktree /{w=$2} /^branch refs\/heads\/main$/{print w}')
if [[ -n "$mainwt" ]]; then
  if [[ -n "$(git -C "$mainwt" status --porcelain --untracked-files=no)" ]]; then
    echo "land: the checkout holding main ($mainwt) has uncommitted changes — ask the operator"; exit 1
  fi
  echo "land: fast-forward main in $mainwt"
  git -C "$mainwt" merge --ff-only "$branch"
else
  echo "land: fast-forward main"
  git fetch . "$branch:main"
fi
if [[ -n "$tag" ]]; then git tag "$tag" main; echo "land: tagged $tag"; fi
if (( push )); then
  git push origin main
  if [[ -n "$tag" ]]; then git push origin "$tag"; fi
fi
echo "land: main is now $(git log --oneline -1 main)"
