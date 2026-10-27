#!/usr/bin/env sh
# Clone every upstream repo at the commit pinned in UPSTREAM.md into upstream/src/.
set -eu
cd "$(dirname "$0")"
mkdir -p src

pin() { # name sha
    if [ ! -d "src/$1/.git" ]; then
        git clone -q "https://github.com/busy-app/$1.git" "src/$1"
    fi
    git -C "src/$1" fetch -q --depth 1 origin "$2"
    git -C "src/$1" checkout -q "$2"
    echo "$1 @ $2"
}

pin busybar-firmware 4d1c24ac38e4f745231713c9cdfaaef5669362ae
pin busybar-protobuf dba670e2ddb5cda511af997ca5fcb1254e90917f
pin clock-app        dd3727d408318a58018212d64c1f276e24912539
pin weather-app      f2c858332f0dcf4fc88d156045bf8bceaea05e9a
pin busy-tools       e0f77a7187fb15b2e0a8b3b4d5712a8d05160088
pin busylib-py       466fcf6892e4b940a631a89fe569faf6756568f5
pin busylib-ts       088d58868c5a624358b307e0d71c26b0cdcf2944
pin busy-hacs        0113671037f681147b8e853a53c053aeefa589ff
