#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Tests for guard-bash.sh (feeds commands as the hook receives them, JSON
# on stdin) and for .githooks/pre-push. Needs bash, git, jq, python3 only.
set -u
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
fails=0; n=0
t() { # allow|block <command>
  local want=$1 cmd=$2 got
  n=$((n + 1))
  jq -nc --arg c "$cmd" '{tool_input:{command:$c}}' | "$here/guard-bash.sh" >/dev/null 2>&1
  [ $? -eq 2 ] && got=block || got=allow
  [ "$got" = "$want" ] || { echo "FAIL: want $want, got $got: $cmd"; fails=$((fails + 1)); }
}
raw() { # block <stdin text>: input that is not a normal hook JSON
  n=$((n + 1))
  printf '%s' "$2" | "$here/guard-bash.sh" >/dev/null 2>&1
  [ $? -eq 2 ] && got=block || got=allow
  [ "$got" = "$1" ] || { echo "FAIL: want $1, got $got: raw input $2"; fails=$((fails + 1)); }
}
NL=$'\n'

# --- allowed: the legitimate forms
t allow 'git push -u origin claude/work'
t allow 'git push origin claude/work'
t allow 'git push origin HEAD'
t allow 'git push -u origin HEAD'
t allow 'git push   --set-upstream   origin   claude/work'
t allow 'git push --force-with-lease origin claude/work'
t allow 'git push origin --force-with-lease claude/work'
t allow 'git push --force-with-lease=claude/work origin claude/work'
t allow 'git push --force-with-lease=claude/work:abc1234 origin claude/work'
t allow 'git push --force-with-lease --force-if-includes origin claude/work'
t allow 'git push --force-if-includes --force-with-lease=claude/a/b origin claude/a/b'
t allow 'git push --force-with-lease origin claude/work:claude/work'
t allow 'git push origin claude/work:claude/other'
t allow 'git log --oneline -f'
t allow 'git status && git diff'
t allow 'git commit -q -F - <<'"'EOF'${NL}Rebase onto main; git push --force is blocked${NL}EOF"
t allow 'git remote -v'
t allow 'git remote get-url origin'
t allow 'git config --get user.name'
t allow 'grep -rn push README.md'

# --- blocked: reviewer's blockers
t block 'git push origin --del main'
t block 'git push origin --dele main'
t block 'git push --mir'
t block 'git push origin `echo --delete` main'
t block 'git push origin $(echo --delete) main'
t block 'git push --force-with-lease origin claude/x{,:main}'
t block "git push origin claude/x \\${NL}-f"
t block 'echo -f | xargs git push origin claude/x'
t block 'echo --delete main | xargs git push origin'
t block 'git checkout main && git push --force-with-lease origin claude/x'
t block 'git config push.default matching && git push --force-with-lease'
t block 'cd /tmp/other && git push --force-with-lease origin claude/x'
t block 'export GIT_CONFIG_COUNT=1 ; git push --force-with-lease origin claude/x'
t block 'git -C /tmp push --force-with-lease origin claude/x'

# --- blocked: forms of force, delete, mirror
t block 'git push --force origin claude/work'
t block 'git push -f origin claude/work'
t block 'git push -fu origin claude/work'
t block 'git push --force --force-with-lease origin claude/work'
t block 'git push --force-with-lease -f origin claude/work'
t block 'git push --force-with-lease --force-with-lease origin claude/work'
t block 'git push -u -u origin claude/work'
t block 'git push --force-with-lea origin claude/work'
t block 'git push --force-if-incl --force-with-lease origin claude/work'
t block 'git push origin +claude/work'
t block 'git push origin +HEAD:claude/work'
t block 'git push --mirror'
t block 'git push --delete origin claude/work'
t block 'git push -d origin claude/work'
t block 'git push origin :claude/work'
t block 'git push --force-with-lease origin :claude/work'
t block 'git push --prune origin claude/work'
t block 'git push --atomic origin claude/work'
t block 'git push --no-verify origin claude/work'

# --- blocked: wrong refs and remotes
t block 'git push --force-with-lease origin main'
t block 'git push origin main'
t block 'git push --force-with-lease origin HEAD'
t block 'git push --force-with-lease origin HEAD:claude/work'
t block 'git push origin HEAD:main'
t block 'git push origin claude/work:main'
t block 'git push origin main:claude/work:main'
t block 'git push origin claude/work main'
t block 'git push origin refs/heads/claude/work'
t block 'git push origin refs/tags/v1'
t block 'git push origin refs/heads/claude/../main'
t block 'git push origin claude/../main'
t block 'git push origin claude//work'
t block 'git push origin claude/-f'
t block 'git push origin Claude/work'
t block 'git push origin claudex/work'
t block 'git push origin claude/'
t block 'git push upstream claude/work'
t block 'git push https://example.com/x.git claude/work'
t block 'git push --tags origin claude/work'
t block 'git push --all origin'
t block 'git push --force-with-lease=main origin claude/work'
t block 'git push --force-with-lease=claude/work: origin claude/work'
t block 'git push --force-with-lease=claude/work:zzz origin claude/work'
t block 'git push --force-with-lease'
t block 'git push'
t block 'git push origin'

# --- blocked: shell tricks
t block 'git push origin claude/work;git push -f'
t block 'git push origin claude/work && git status'
t block 'git status | git push origin claude/work'
t block 'git push origin claude/work > /dev/null'
t block 'git push origin claude/work 2>&1'
t block 'git push origin "claude/work"'
t block "git push origin 'claude/work'"
t block 'git push origin claude/work ""'
t block 'git pu""sh -f origin claude/work'
t block 'g""it push --force origin x'
t block 'git "push" origin main'
t block '/usr/bin/git push --force-with-lease origin claude/work'
t block 'g?t push origin main'
t block '$G push origin main'
t block 'git push $FLAGS origin claude/work'
t block 'git $SUB origin main'
t block 'git push origin $BRANCH'
t block 'git push origin "$(echo main)"'
t block 'eval "git push -f origin claude/work"'
t block 'bash -c "git push --force origin x"'
t block 'echo $(git push -f origin x)'
t block 'GIT_DIR=/x git push --force-with-lease origin claude/work'
t block 'GIT_SSH_COMMAND=evil git push origin claude/work'
t block 'env git push origin claude/work'
t block 'command git push origin claude/work'
t block 'sudo git push origin claude/work'
t block 'nice git push origin claude/work'
t block 'git -c remote.origin.push=+main push origin claude/work'
t block 'git -c alias.p="push -f" p'
t block 'git --git-dir=/x push origin claude/work'
t block 'git push --receive-pack=evil origin claude/work'
t block 'git push --repo=x origin claude/work'
t block 'git push -o x origin claude/work'
t block 'git push origin claude/work --exec=evil'
t block 'git push origin "claude/work'
t block 'git push origin claude/work	-f'
t block 'bash <<EOF'"${NL}git push -f origin main${NL}EOF"
t block 'git commit -m x && git push origin claude/work'

# --- blocked: other ways to move main or rewrite the remote
t block "git config alias.pf 'push --force origin main'"
t block 'git config alias.st status'
t block 'git config remote.origin.url https://example.com/x.git'
t block 'git config --global url.x.insteadOf y'
t block 'git config push.default matching'
t block 'git config branch.claude/work.remote other'
t block 'git config branch.claude/work.pushRemote other'
t block 'git remote add evil https://example.com/x.git'
t block 'git remote set-url origin https://example.com/x.git'
t block 'git remote rename origin old'
t block 'git remote remove origin'
t block 'GIT_CONFIG_COUNT=1 git status'
t block 'gh api -X PATCH repos/o/r/git/refs/heads/main'
t block 'gh api --method PUT repos/o/r/git/refs/heads/main -f sha=abc'
t block 'gh api repos/o/r/git/refs -f ref=refs/heads/main'
t block 'gh api repos/o/r/merges -f base=main -f head=x'
t block 'curl -X PATCH https://api.github.com/repos/o/r/git/refs/heads/main'
t block 'wget --method=POST https://api.github.com/repos/o/r/merges'
t block 'git commit -n -m x'
t block 'git config core.hooksPath /dev/null'

# --- round 2: heredoc wrappers, continuations, hook tampering, ref APIs
t allow 'git add a.md b.md && git commit -q -F - <<'"'EOF'${NL}Fix gh api -X PATCH repos/o/r/git/refs and git push${NL}EOF"
t allow 'git status && git commit -F - <<"MSG"'"${NL}text${NL}MSG"
t allow 'git commit -q --allow-empty -F - <<'"'EOF'${NL}x${NL}EOF"
t allow './.claude/hooks/test-guard-bash.sh'
t allow 'git commit -m "fix" --amend'
t block 'echo "<<EOF"'"${NL}git push --force origin claude/x${NL}EOF"
t block 'echo "<<EOF"'"${NL}gh api -X PATCH repos/o/r/git/refs/heads/main${NL}EOF"
t block 'echo "<<EOF"'"${NL}git config core.hookspath /dev/null${NL}EOF"
t block 'echo "<<EOF"'"${NL}git config remote.origin.url evil${NL}EOF"
t block 'echo "<<EOF"'"${NL}git remote add evil x${NL}EOF"
t block 'git commit -F - <<EOF'"${NL}git push -f${NL}EOF"
t block 'git commit -q -F - <<'"'EOF'${NL}msg${NL}EOF${NL}git push --force origin main${NL}EOF"
t block 'git commit -q -F - <<'"'EOF'${NL}msg${NL}EOF${NL}git push --force origin main"
t block 'git commit -q -F - <<'"'EOF'${NL}msg${NL}EOF ; git push -f"
t block 'git add x && git commit -q -F - <<'"'EOF'${NL}msg${NL}EOF${NL}rm .githooks/pre-push"
t block "git pu\\${NL}sh --delete origin main"
t block "gh api repos/o/r/git/ref\\${NL}s -X PATCH"
t block "git commit --no-ver\\${NL}ify -m x"
t block 'git config core.hookspath /dev/null'
t block 'git config core.hooksPath /dev/null'
t block 'git -c "core.hooksP""ath=/dev/null" commit -m x'
t block 'git config core.hooks""Path /dev/null'
t block 'git --config-env core.hooksPath=X status'
t block 'git --config-env=alias.x=Y status'
t block 'git -c include.path=/tmp/f status'
t block 'git config include.path /tmp/f'
t block 'git config includeIf.gitdir:/.path /tmp/f'
t block 'git config --unset core.hooksPath'
t block 'rm -rf .githooks'
t block 'mv .githooks x'
t block 'cp /tmp/x .git/hooks/pre-push'
t block 'ln -s /dev/null .githooks/pre-push'
t block 'chmod -x .githooks/pre-push'
t block 'echo true > .claude/hooks/guard-bash.sh'
t block 'echo x >> .claude/settings.json'
t block 'echo x | tee .claude/hooks/guard-push.py'
t block 'sed -i s/a/b/ .githooks/pre-push'
t block 'truncate -s 0 .git/config'
t block 'gh pr merge 5 --squash'
t block 'gh api graphql -f query=x'
t block 'gh api graphql -f query="mutation { mergePullRequest(input: {}) { x } }"'
t block 'gh api -X PUT repos/o/r/contents/README.md -f message=x'
t block 'gh api --method=DELETE repos/o/r/branches/main/protection'
t block 'gh api -X POST repos/o/r/git/refs -f ref=x'
t block 'gh api repos/o/r/pulls/1/merge -X PUT'
t block 'curl -X POST https://api.github.com/graphql -d x'
t block 'curl -X PUT https://api.github.com/repos/o/r/contents/x'
t block 'wget --method=PATCH https://api.github.com/repos/o/r/git/refs/heads/main'
t block 'git send-pack origin main'
t block 'git receive-pack .'
t block 'git http-push origin main'
t block 'git update-ref refs/heads/main HEAD'
t block 'git commit -nm x'
t block 'git commit -anm x'
t block 'git commit -n'
t block 'git commit --no-ver -m x'
t block 'git commit --no-verif -m x'
t block 'git -C . commit --no-verify -m x'
t block 'git merge -n x'
t block 'git rebase --no-verify main'
t block 'git am --no-v x.patch'
t block 'git cherry-pick -n x'

# --- blocked: guard can't read its input (fails closed)
raw block ''
raw block 'not json'
raw block '{"tool_input":{}}'
raw block '{"tool_input":{"command":""}}'
raw block '{"tool_input":{"command":42}}'

# --- pre-push hook, in a throwaway repo
git init -q --bare "$tmp/remote.git"
git init -q -b main "$tmp/repo"
git -C "$tmp/repo" config user.email t@example.com
git -C "$tmp/repo" config user.name t
git -C "$tmp/repo" remote add origin "$tmp/remote.git"
git -C "$tmp/repo" commit -q --allow-empty -m init
git -C "$tmp/repo" push -q origin main
git -C "$tmp/repo" fetch -q origin
git -C "$tmp/repo" config core.hooksPath "$root/.githooks"
pp() { # allow|block <git push args...>
  local want=$1 got; shift
  n=$((n + 1))
  git -C "$tmp/repo" push -q "$@" >/dev/null 2>&1 && got=allow || got=block
  [ "$got" = "$want" ] || { echo "FAIL: pre-push want $want, got $got: git push $*"; fails=$((fails + 1)); }
}
git -C "$tmp/repo" commit -q --allow-empty -m second
pp allow origin HEAD:claude/work
pp block origin HEAD:main
pp block origin HEAD:refs/tags/v1
pp block origin HEAD:other
pp block origin :claude/work
pp block --delete origin claude/work
pp block origin :main
pp allow origin HEAD:claude/other

echo "$n cases, $fails failed"
[ "$fails" -eq 0 ]
