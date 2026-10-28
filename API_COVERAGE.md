# API coverage

Every operation in the pinned OpenAPI spec (`vendor/openapi.yaml`, v27.14.0).
`implemented` = real behaviour on the phone; `stubbed` = schema-valid answer for hardware or services the phone does not have.

Also served: `GET /openapi.yaml`, the web UI at `/`, and 405 + `Allow` for unsupported methods on API paths.

| Tag | Method | Path | operationId | Status | Notes |
|---|---|---|---|---|---|
| Account | DELETE | `/api/account` | unlinkAccount | stubbed | no-op |
| Account | GET | `/api/account/backend` | getAccountBackend | stubbed | empty backend |
| Account | PUT | `/api/account/backend` | setAccountBackend | stubbed | 400: no cloud |
| Account | GET | `/api/account/info` | getAccountInfo | stubbed | never linked |
| Account | POST | `/api/account/link` | linkAccount | stubbed | 503: no cloud |
| Account | GET | `/api/account/status` | getAccountStatus | stubbed | disconnected |
| Apps | DELETE | `/api/apps` | removeApp | implemented |  |
| Apps | POST | `/api/apps/install` | installApp | implemented |  |
| Apps | POST | `/api/apps/launch` | launchApp | implemented | 409 while a session holds the screen |
| Apps | GET | `/api/apps/list` | listApps | implemented |  |
| Apps | POST | `/api/apps/quit` | quitApp | implemented |  |
| Apps | DELETE | `/api/apps/settings` | resetAppSettings | implemented |  |
| Apps | GET | `/api/apps/settings` | getAppSettings | implemented |  |
| Apps | PUT | `/api/apps/settings` | setAppSettings | implemented |  |
| Apps | POST | `/api/apps/stage` | stageApp | implemented |  |
| Assets | DELETE | `/api/assets/upload` | deleteAppAssets | implemented |  |
| Assets | POST | `/api/assets/upload` | uploadAssetWithAppId | implemented |  |
| Assets | DELETE | `/api/audio/play` | stopAudio | implemented |  |
| Assets | POST | `/api/audio/play` | playAudio | implemented |  |
| Assets | DELETE | `/api/display/draw` | clearDisplay | implemented |  |
| Assets | POST | `/api/display/draw` | drawOnDisplay | implemented |  |
| BLE | POST | `/api/ble/disable` |  | implemented |  |
| BLE | POST | `/api/ble/enable` |  | implemented |  |
| BLE | DELETE | `/api/ble/pairing` |  | implemented |  |
| BLE | GET | `/api/ble/status` |  | implemented |  |
| Busy | GET | `/api/busy/profiles/{slot}` | getBusyProfile | implemented |  |
| Busy | PUT | `/api/busy/profiles/{slot}` | setBusyProfile | implemented |  |
| Busy | GET | `/api/busy/snapshot` | getBusySnapshot | implemented |  |
| Busy | PUT | `/api/busy/snapshot` | setBusySnapshot | implemented |  |
| Input | POST | `/api/input` | setInputKey | implemented |  |
| Settings | GET | `/api/access` | getHttpAccess | implemented |  |
| Settings | POST | `/api/access` | setHttpAccess | implemented |  |
| Settings | DELETE | `/api/access/tokens` | deleteAllAccessTokens | implemented |  |
| Settings | GET | `/api/access/tokens` | getAccessTokens | implemented |  |
| Settings | POST | `/api/access/tokens` | createAccessToken | implemented |  |
| Settings | DELETE | `/api/access/tokens/{short_id}` | revokeAccessToken | implemented |  |
| Settings | GET | `/api/audio/volume` | getAudioVolume | implemented |  |
| Settings | POST | `/api/audio/volume` | setAudioVolume | implemented |  |
| Settings | GET | `/api/display/brightness` | getDisplayBrightness | implemented |  |
| Settings | POST | `/api/display/brightness` | setDisplayBrightness | implemented |  |
| Settings | GET | `/api/name` |  | implemented |  |
| Settings | POST | `/api/name` |  | implemented |  |
| Smart Home | DELETE | `/api/smart_home/pairing` |  | stubbed | 503: no Matter |
| Smart Home | GET | `/api/smart_home/pairing` | getSmartHomeCommissioningStatus | stubbed | never paired |
| Smart Home | POST | `/api/smart_home/pairing` | startSmartHomePairing | stubbed | 503: no Matter |
| Smart Home | GET | `/api/smart_home/switch` |  | stubbed | stored only |
| Smart Home | POST | `/api/smart_home/switch` |  | stubbed | stored only |
| Storage | GET | `/api/storage/list` | listStorageFiles | implemented |  |
| Storage | POST | `/api/storage/mkdir` | createStorageDir | implemented |  |
| Storage | GET | `/api/storage/read` | readStorageFile | implemented |  |
| Storage | DELETE | `/api/storage/remove` | removeStorageFile | implemented |  |
| Storage | POST | `/api/storage/rename` | RenameStorageFile | implemented |  |
| Storage | GET | `/api/storage/status` | getStorageStatus | implemented |  |
| Storage | POST | `/api/storage/write` | writeStorageFile | implemented |  |
| Streaming | GET | `/api/screen` |  | implemented |  |
| Streaming | GET | `/api/status/ws` | connectWebSocket | implemented |  |
| System | POST | `/api/log_dump` | dumpLog | stubbed | writes a short state summary |
| System | GET | `/api/status` | getStatus | implemented |  |
| System | GET | `/api/status/device` | getStatusDevice | implemented |  |
| System | GET | `/api/status/firmware` | getStatusFirmware | implemented |  |
| System | GET | `/api/status/power` | getStatusPower | implemented |  |
| System | GET | `/api/status/system` | getStatusSystem | implemented |  |
| System | GET | `/api/transport` | getTransport | implemented |  |
| System | GET | `/api/version` | getVersion | implemented |  |
| Time | GET | `/api/time` | getTime | implemented |  |
| Time | POST | `/api/time/timestamp` | setTimeTimestamp | implemented |  |
| Time | GET | `/api/time/timezone` | getTimeTimezone | implemented |  |
| Time | POST | `/api/time/timezone` | setTimeTimezone | implemented |  |
| Time | GET | `/api/time/tzlist` | getTimeTzlist | implemented |  |
| Updater | POST | `/api/update` | updateFirmware | stubbed | 400: update the APK instead |
| Updater | POST | `/api/update/abort_download` | abortFirmwareDownload | stubbed | no-op |
| Updater | GET | `/api/update/autoupdate` | getAutoupdateSettings | stubbed | stored only |
| Updater | POST | `/api/update/autoupdate` | setAutoupdateSettings | stubbed | stored only |
| Updater | GET | `/api/update/changelog` | getUpdateChangelog | stubbed | 400 |
| Updater | POST | `/api/update/check` | checkFirmwareUpdate | stubbed | always "not available" |
| Updater | POST | `/api/update/install` | installFirmwareUpdate | stubbed | 400 not_available |
| Updater | GET | `/api/update/status` | getFirmwareUpdateStatus | stubbed | idle |
| Wi-Fi | POST | `/api/wifi/connect` |  | stubbed | refused: Android owns Wi-Fi |
| Wi-Fi | POST | `/api/wifi/disconnect` |  | stubbed | refused: Android owns Wi-Fi |
| Wi-Fi | GET | `/api/wifi/networks` | getWifiNetworks | stubbed | current network only |
| Wi-Fi | GET | `/api/wifi/status` |  | implemented |  |

58 implemented, 23 stubbed, 0 missing, of 81 operations.

## Conformance

Checked with schemathesis against the emulator using the firmware suite's own scope
(`tests/integration/frontend/schema_conformance`: GET and POST, its skip list, 10 examples each):
everything passed (before phase 3b, BLE enable/disable answered 503; they are implemented now).
The suite's wrong-method check (405 + `Allow`) passes for all 40 candidate paths.

## Differences from a real bar

- **Port 8080**, not 80 (Android); mDNS announces `busybar.local` and `busybar-<mac>._http._tcp`.
- **Access** defaults to `enabled` (open on the LAN): a real bar is unlocked over USB first. Requests from `127.0.0.1` (adb forwards, JS apps) are treated like USB and always allowed.
- **Status stream** sends the front display only, uncompressed (`PLAIN`), like the firmware's streamer; power and Wi-Fi are polled every 5 s.
- **No MQTT/cloud sync** of timer snapshots and profiles; Matter (`trigger_smart_home`) is stored but has no effect.
- **Binary `.anim` files** (what JS apps ship) are decoded; uploaded `.image` files (LVGL binary) are not yet. PNG uploads and the bundled animations work. `.snd` and WAV sounds play.
- **JS apps run on QuickJS**, not JerryScript, with the firmware's globals reimplemented (`engine/src/main/resources/jsrunner/prelude.js`): `fetch` (with `Headers`, `Request`, `Response`, a minimal `ReadableStream`, `AbortController`), `URL`/`URLSearchParams`, `listen("input")`, `localStorage`, timers, `console`, plus `TextEncoder`/`TextDecoder`. Apps get ES2023 rather than JerryScript's subset, so code that runs here may not run on a bar. `fetch`'s `dispatcher: {connect: {useDeviceKey: true}}` is ignored: there is no device key for Flipper's apps backend. A relative URL resolves against the bar itself, and the phone serves the backend's weather API (`/weather/v1/forecast`, `/weather/v1/locations`) from Open-Meteo, so public builds of weather-app work. Auto location uses the bar's time zone instead of geo-IP.
- **Bluetooth (official BUSY app)**: the phone advertises and serves GATT like the bar (`app/.../BleBridge.kt`). Tested 2026-10-28 with the official app on Android: it found "BUSY Bar", paired (Android shows its pairing prompt on the phone; accept it there), and then ran its whole session over the UART bridge: status, profiles (edits applied), screen, storage, name, brightness, volume, time zone, updates. It keeps retrying `POST /api/account/link`, which answers 503 because account linking needs Flipper's cloud. SETTINGS → Bluetooth is the bar's pairing screen (ble_settings): unpaired it shows "Pairing mode..." with Bluetooth on, and turns Bluetooth off again when left unpaired or after 5 minutes; paired, it offers "Forget device". Unlike the bar, the phone cannot limit advertising to the paired phone.
- **Auto brightness** uses the phone's light sensor with the firmware's mapping. The phone-only Sensors settings (presence, auto ON CALL) are not in the API or the web UI: the firmware has no such settings to map them to.
- **App heap** is a fixed 32 MB for every app; the manifest's `heap_size` is not enforced.
