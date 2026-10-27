# BUSY S8

A self-contained [BUSY Bar](https://busy.app) emulator for a Samsung Galaxy S8+ (SM-G955,
Android 9). One APK renders the bar's 72×16 RGB front display and 160×80 back display on the
phone's AMOLED panel and serves the BUSY Bar HTTP API on the LAN, so the upstream web UI,
JS apps, busylib-py/ts, busy-cli and the Home Assistant integration can talk to the phone.

Unofficial; not affiliated with Flipper Devices. See [NOTICE](NOTICE).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Kiosk shell, LED renderer, `POST/DELETE /api/display/draw` | done |
| 2 | `busy_timer` port, `/api/busy/*`, physical controls | |
| 3 | Rest of the API, status WebSocket, bundled web UI | |
| 4 | QuickJS runtime for JS apps | |
| 5 | Auto brightness, burn-in guard, presence, auto ON CALL | |

Endpoint-level status: [API_COVERAGE.md](API_COVERAGE.md). Upstream versions:
[UPSTREAM.md](UPSTREAM.md).

## Layout

| Module | Kind | Contents |
|---|---|---|
| `core` | JVM | Framebuffers, LVGL font reader, draw-element parser, canvas service, renderer |
| `server` | JVM | Ktor (CIO) HTTP API |
| `app` | Android | Kiosk activity, Compose LED panel, foreground service, device-owner policies |
| `vendor/` | data | Pinned upstream assets, web UI, OpenAPI spec, protobufs |
| `upstream/` | scripts | `fetch.sh` (clone pinned repos), `sync.sh` (refresh `vendor/`) |

`core` and `server` are plain JVM modules, so they are tested on the desktop without an emulator.

## Build

Requirements: JDK 17, Android SDK with platform 28 and 35 (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew test
```

```bash
./gradlew :app:assembleDebug
```

## Run on the emulator

Create an AVD that matches the phone: system image `system-images;android-28;google_apis;arm64-v8a`
(no Play Store, so `dpm` works), 1440×2960, 560 dpi, hardware keyboard on.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell dpm set-device-owner com.busys8/.AdminReceiver
```

```bash
adb shell am start -n com.busys8/.MainActivity
```

```bash
adb forward tcp:8080 tcp:8080
```

Draw something:

```bash
curl -X POST localhost:8080/api/display/draw -d '{"application_name":"curl","elements":[{"id":"0","type":"text","text":"BUSY","font":"extra_large","x":36,"y":8,"align":"center","color":"#FF2200FF"}]}'
```

## Run on the phone

See [DEVICE_SETUP.md](DEVICE_SETUP.md): factory reset without accounts, debloat, install, set
device owner. The idle back display shows the address to use (`<ip>:8080`).

## Refreshing upstream

Edit the commits in `upstream/fetch.sh` and `UPSTREAM.md`, then:

```bash
./upstream/fetch.sh
```

```bash
./upstream/sync.sh
```

`sync.sh` needs Python 3 with `pyyaml` and `colorlog` (for the firmware's OpenAPI merge script).

## Licence

GPL-2.0-or-later, because it ports firmware code and bundles the firmware's web UI. Bundled
assets keep their own licences (CC-BY-SA-4.0, OFL-1.1); see [REUSE.toml](REUSE.toml).
