#!/bin/bash
#
# Publishes TrainBeat to GitHub: first run creates the repository and pushes
# everything, later runs just push what changed.
#
# What is deliberately NOT uploaded: the SoundFonts (150 MB of somebody else's
# work, and GitHub refuses single files over 100 MB anyway), the build output,
# the bench jar and its renders, and local.properties, which holds the path of
# this Mac and belongs to nobody else.
#
# Run it from anywhere:
#     bash tools/github-init.sh
#
set -u

USER_NAME="malexmura-coder"
REPO_NAME="TrainBeat"
BRANCH="main"

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
echo "project: $ROOT"

if [ ! -f "settings.gradle.kts" ] || [ ! -d "app/src/main" ]; then
    echo "This does not look like the TrainBeat project. Stopping."
    exit 1
fi

# ---------------------------------------------------------------- ignore list
# Written every time, so a file that should never leave this Mac cannot start
# leaving it because the list drifted.
cat > .gitignore <<'EOF'
# build output
build/
app/build/
.gradle/
.kotlin/
.cxx/
captures/

# local to this machine
local.properties
.idea/
*.iml
.DS_Store

# not ours to distribute, and too large for GitHub
soundfonts/
*.sf2
*.SF2
*.sf3

# bench material and its renders
tools/*.jar
tools/out/
tools/midi/
_to_delete/
_stage/
_stage_test/

# bench inputs: they point at folders on this Mac
scan-folder.txt
render-file.txt

# scratch
*.log
EOF
echo "wrote .gitignore"

# ---------------------------------------------------------------- git identity
if [ -z "$(git config --global user.email 2>/dev/null)" ]; then
    git config --global user.email "m.alex.mura@gmail.com"
    git config --global user.name "Alessandro Mura"
    echo "set the git identity (name and email)"
fi

# ---------------------------------------------------------------- repository
if [ ! -d .git ]; then
    git init -q
    git symbolic-ref HEAD "refs/heads/$BRANCH"
    echo "created the local repository"
else
    echo "local repository already here"
fi

git add -A
if git diff --cached --quiet 2>/dev/null; then
    echo "nothing new to commit"
else
    MSG="${1:-TrainBeat v4: gapless looping, MIDI playback, loop trimmer}"
    git commit -q -m "$MSG"
    echo "committed: $MSG"
fi

# ------------------------------------------------- refuse to push a huge file
BIG=$(git ls-files -z | xargs -0 find -size +90M 2>/dev/null)
if [ -n "$BIG" ]; then
    echo
    echo "STOP: these files are over 90 MB and GitHub will refuse them:"
    echo "$BIG"
    echo "Remove them from the repository (git rm --cached <file>) and run again."
    exit 1
fi

COUNT=$(git ls-files | wc -l | tr -d ' ')
SIZE=$(git ls-files -z | xargs -0 du -ch 2>/dev/null | tail -1 | awk '{print $1}')
echo
echo "about to upload $COUNT files, $SIZE in total"

# ---------------------------------------------------------------- the remote
if git remote get-url origin >/dev/null 2>&1; then
    echo "remote already set: $(git remote get-url origin)"
else
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        echo "creating the repository on GitHub with gh..."
        gh repo create "$USER_NAME/$REPO_NAME" --public --source=. --remote=origin || {
            echo "gh could not create it; falling back to the manual route"
        }
    fi
    if ! git remote get-url origin >/dev/null 2>&1; then
        echo
        echo "Create the repository by hand, it takes twenty seconds:"
        echo "  1. open https://github.com/new"
        echo "  2. Repository name: $REPO_NAME"
        echo "  3. Public. Do NOT tick README, .gitignore or licence."
        echo "  4. Create repository"
        echo
        read -r -p "Done? Press Enter to continue, or Ctrl-C to stop. "
        git remote add origin "git@github.com:$USER_NAME/$REPO_NAME.git"
        echo "remote set to git@github.com:$USER_NAME/$REPO_NAME.git"
    fi
fi

# ---------------------------------------------------------------- push
echo
echo "pushing..."
if git push -u origin "$BRANCH"; then
    echo
    echo "done: https://github.com/$USER_NAME/$REPO_NAME"
else
    echo
    echo "The push failed. The usual reasons, in order of likelihood:"
    echo "  - the SSH key is not on GitHub yet: check with  ssh -T git@github.com"
    echo "  - the repository does not exist under that name"
    echo "  - the branch is called something else: check with  git branch"
    exit 1
fi

# ---------------------------------------------------------------- what is missing
echo
[ -f LICENSE ] || echo "note: there is no LICENSE file. F-Droid will not accept the app without one."
[ -f README.md ] || echo "note: there is no README.md. Not required, but it is the first thing a visitor reads."
[ -d fastlane/metadata/android/en-US ] || echo "note: no fastlane metadata yet - that is what F-Droid reads for the store page."
