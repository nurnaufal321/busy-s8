# Upstream

BUSY S8 tracks a fixed version of the [busy-app](https://github.com/busy-app) repositories.
`upstream/fetch.sh` clones each one at the commit below into `upstream/src/` (git-ignored), and
`upstream/sync.sh` copies what the APK ships into `vendor/`, which is committed.

| Repo | Commit | Date | Used for |
|---|---|---|---|
| busybar-firmware | `4d1c24ac38e4f745231713c9cdfaaef5669362ae` | 2026-10-26 | API contract (v27.14.0), ported services, assets, web UI, integration tests |
| busybar-protobuf | `dba670e2ddb5cda511af997ca5fcb1254e90917f` | firmware submodule `assets/proto` | `/api/status/ws` wire format |
| clock-app | `dd3727d408318a58018212d64c1f276e24912539` | 2026-10-19 | JS runtime acceptance test |
| weather-app | `f2c858332f0dcf4fc88d156045bf8bceaea05e9a` | 2026-10-22 | JS runtime acceptance test |
| busy-tools | `e0f77a7187fb15b2e0a8b3b4d5712a8d05160088` | 2026-10-21 | `busy-cli` build/install flow |
| busylib-py | `466fcf6892e4b940a631a89fe569faf6756568f5` | 2026-10-21 | Compatibility tests |
| busylib-ts | `088d58868c5a624358b307e0d71c26b0cdcf2944` | 2026-09-18 | Web UI client; compatibility tests |
| busy-hacs | `0113671037f681147b8e853a53c053aeefa589ff` | 2026-10-21 | Home Assistant compatibility |

## What comes from where

### Copied verbatim (`vendor/`)
| vendor path | firmware path | Licence |
|---|---|---|
| `apps_assets/shared/fonts/*.font` | `assets/shared/fonts/` (lv_font_conv output) | OFL-1.1 |
| `apps_assets/shared/{images,animations,sounds}` | `assets/shared/...` (PNG, frame zips, WAV) | CC-BY-SA-4.0 |
| `apps_assets/<app>/{images,animations,sounds}` | `assets/images/external/<app>`, `assets/animations/<app>`, `assets/sounds/<app>` | CC-BY-SA-4.0 |
| `apps_assets/busy/themes` | `applications/main/busy/resources/apps_assets/busy/themes` | GPL-2.0-or-later |
| `frontend/` | `assets/frontend-build/` (prebuilt web UI) | GPL-2.0-or-later |
| `openapi/*.yaml` | `applications/services/web_server/openapi/` | GPL-2.0-or-later |
| `openapi.yaml` | merged by `scripts/openapi_merge.py`, served at `/openapi.yaml` | GPL-2.0-or-later |
| `proto/` | busybar-protobuf | see that repo |

`vendor/apps_assets` mirrors the device's `/ext/apps_assets`. The firmware build converts PNG
to LVGL `.image`, frame zips to `.anim` and WAV to `.snd`; `DeviceStorage` maps those device
names back to the bundled sources.

### Ported to Kotlin
| BUSY S8 | Firmware source | Notes |
|---|---|---|
| `core/.../LvFont.kt` | LVGL `lv_binfont_loader.c` (format), `services/font_registry` | lv_font_conv `bin` reader; font name map from `api_display.c` |
| `core/.../Elements.kt` | `web_server/http_api/api_display.c` | Draw-request parsing, defaults and error strings |
| `core/.../Canvas.kt` | `services/canvas/canvas.c` | Ownership, priority, deferral (1.5 s), timeouts |
| `core/.../CanvasRenderer.kt` | `services/canvas/canvas_widgets.c`, `gui/modules/{label,countdown}.c` | Anchor placement, label metrics, circular scroll, countdown format |
| `core/.../Xpm.kt` | `lib/xpm` behaviour as documented in `assets.yaml` | XPM2 subset |
| `core/.../Anim.kt` | `gui/modules/anim_player.c`, `lib/anim_file` | Plays frame zips and binary `.anim`; section and loop semantics |
| `engine/.../timer/*` | `services/busy_timer/*` | State machine, snapshot/profile JSON, defaults, persistence; no MQTT/Matter |
| `engine/.../input/Input.kt` | `services/input/input.c` | Short/long/repeat timing |
| `engine/.../apps/Desktop.kt` | `services/desktop`, `services/canvas` input handling, `busy_timer_util.c` | Mode switch → app, canvas input precedence |
| `engine/.../apps/busy/*` | `applications/main/busy` (scenes, widgets, presets) | All session scenes; no transitions or setup screens yet |
| `server/.../BusyServer.kt`, `BusyRoutes.kt` | `web_server/web_server_i.h`, `http_api/api_busy.c`, `api_input.c` | Reply bodies, CORS headers |
| `server/.../Auth.kt`, `AccessRoutes.kt` | `http_api/api_root.c`, `services/api_tokens` | Access modes, password, hashed tokens, JS allowlist, X-API-Sem-Ver |
| `server/.../SystemRoutes.kt`, `StorageRoutes.kt`, `AppsRoutes.kt`, `StubRoutes.kt` | `http_api/api_*.c` | Status formats (incl. the uptime quirk), storage, audio (.snd = raw PCM), apps |
| `server/.../StateProto.kt`, `StatusStream.kt` | `services/state_publisher`, `api_status_streaming.c`, busybar-protobuf | Hand-written proto3 encoder; enable / send-all protocol, 4 clients, 100 ms frames |
| `engine/.../jsapps/JsApps.kt` | `services/js_app_installer`, `lib/js_app` | tar/tgz staging, install keys, manifest and settings schema |
| `engine/.../jsapps/JsRuntime.kt`, `resources/jsrunner/prelude.js` | `services/js_runner/*` | Globals, input mapping, `X-busybar-fetch: js`, `localhost` → own API, localStorage file format, end-when-idle; QuickJS (quickjs-kt) instead of JerryScript |
| `engine/.../apps/JsLauncher.kt` | `system/js_app_launcher`, `main/apps_menu` | Resume-mode launch, low-priority refusal, long BACK force-quit, canvas cleanup; menu lists apps by name |
| `core/.../AnimBin.kt` | `documentation/File Formats.dox.md`, `scripts/seq2anim.py`, `scripts/flipper/rle.py` | Binary `.anim` decoder: mask RLE/bitmap, pixel RAW/RLE/QOI-like |
| `engine/.../apps/ListMenu.kt`, `SettingsApp.kt`, BUSY setup scenes | `gui/modules/var_item_list.c`, `main/settings_menu`, `busy_scene_setup*.c` | Dial/OK/BACK editing; settings pages the phone can serve |
