#!/usr/bin/env sh
# Copy the upstream files BUSY S8 ships from upstream/src/ (see fetch.sh) into vendor/.
# Needs python3 with pyyaml + colorlog for the OpenAPI merge.
set -eu
cd "$(dirname "$0")/.."
FW=upstream/src/busybar-firmware
OUT=vendor

rm -rf "$OUT"
mkdir -p "$OUT/shared" "$OUT/openapi" "$OUT/proto"

# Fonts, stock images, animations and sounds (CC-BY-SA-4.0 / OFL-1.1)
cp -R "$FW/assets/shared/fonts" "$OUT/shared/fonts"
cp -R "$FW/assets/shared/images" "$OUT/shared/images"
cp -R "$FW/assets/shared/animations" "$OUT/shared/animations"
cp -R "$FW/assets/shared/sounds" "$OUT/shared/sounds"
cp -R "$FW/assets/images" "$OUT/images"
cp -R "$FW/assets/animations" "$OUT/animations"
cp -R "$FW/assets/sounds" "$OUT/sounds"

# Prebuilt web UI (GPL-2.0-or-later)
cp -R "$FW/assets/frontend-build" "$OUT/frontend"

# Protobuf definitions for /api/status/ws
(cd upstream/src/busybar-protobuf && find . -name '*.proto' | cpio -pdm --quiet "../../../$OUT/proto")

# OpenAPI: per-tag segments plus the merged spec served at /openapi.yaml
SPEC="$FW/applications/services/web_server/openapi"
cp "$SPEC"/*.yaml "$OUT/openapi/"
(cd "$SPEC" && PYTHONPATH="$OLDPWD/$FW/scripts" python3 "$OLDPWD/$FW/scripts/openapi_merge.py" \
    merge "$OLDPWD/$OUT/openapi.yaml" openapi.yaml $(ls *.yaml | grep -v '^openapi.yaml$'))
rm "$OUT/openapi/openapi.yaml"

cp "$FW/LICENSES/GPL-2.0-or-later.txt" "$FW/LICENSES/CC-BY-SA-4.0.txt" "$FW/LICENSES/OFL-1.1.txt" "$OUT/"
echo "vendor/ synced from $(git -C "$FW" rev-parse HEAD)"
