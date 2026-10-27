#!/usr/bin/env sh
# Copy the upstream files BUSY S8 ships from upstream/src/ (see fetch.sh) into vendor/.
# Needs python3 with pyyaml + colorlog for the OpenAPI merge.
#
# vendor/apps_assets/ mirrors the device's /ext/apps_assets/ tree, but keeps source formats:
# the firmware build (`./fbt resources`) turns PNG into .image, frame zips into .anim and WAV
# into .snd; DeviceStorage maps those device names back to the sources at runtime.
set -eu
cd "$(dirname "$0")/.."
FW=upstream/src/busybar-firmware
OUT=vendor
AA="$OUT/apps_assets"

rm -rf "$OUT"
mkdir -p "$AA/shared" "$OUT/openapi" "$OUT/proto"

# Shared assets (fonts OFL-1.1; images, animations, sounds CC-BY-SA-4.0)
cp -R "$FW/assets/shared/fonts" "$AA/shared/fonts"
cp -R "$FW/assets/shared/images/external" "$AA/shared/images"
cp -R "$FW/assets/shared/animations" "$AA/shared/animations"
cp -R "$FW/assets/shared/sounds" "$AA/shared/sounds"

# Per-app assets: images/external/<app>, animations/<app>, sounds/<app>
for dir in "$FW"/assets/images/external/*/; do
    app=$(basename "$dir"); mkdir -p "$AA/$app"; cp -R "$dir" "$AA/$app/images"
done
for dir in "$FW"/assets/animations/*/; do
    app=$(basename "$dir"); mkdir -p "$AA/$app"; cp -R "$dir" "$AA/$app/animations"
done
for dir in "$FW"/assets/sounds/*/; do
    app=$(basename "$dir"); mkdir -p "$AA/$app"; cp -R "$dir" "$AA/$app/sounds"
done

# App resources installed verbatim (e.g. BUSY themes)
for dir in "$FW"/applications/main/*/resources/apps_assets/*/; do
    app=$(basename "$dir"); mkdir -p "$AA/$app"; cp -R "$dir". "$AA/$app/"
done

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
