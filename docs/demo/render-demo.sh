#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_ROOT"

./docs/demo/prepare-demo.sh
rm -f docs/demo/interactive-mode.gif
vhs docs/demo/interactive-mode.tape
