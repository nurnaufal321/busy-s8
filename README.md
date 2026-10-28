# BUSY S8

A self-contained [BUSY Bar](https://busy.app) emulator for a Samsung Galaxy S8+ (SM-G955,
Android 9). One APK shows the bar's 72×16 RGB front display on the phone's AMOLED panel and
serves the BUSY Bar HTTP API on the LAN, so the upstream web UI, JS apps, busylib-py/ts,
busy-cli and the Home Assistant integration can talk to the phone.

The phone has one screen, and it faces the room like the bar's front display. The 160×80 back
display (the side facing the bar's owner) is still emulated: apps draw to it through the API,
and it is visible in the web UI's screen stream, but it isn't shown on the phone.

Unofficial; not affiliated with Flipper Devices. See [NOTICE](NOTICE).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Kiosk shell, LED renderer, `POST/DELETE /api/display/draw` | done |
| 2 | `busy_timer` port, BUSY app, `/api/busy/*`, `/api/input`, phone controls, burn-in protection | done |
| 3 | Rest of the API, status stream, bundled web UI, mDNS, setup and settings screens | done |
| 3b | Bluetooth bridge for the official BUSY app | done: pairs and works, no cloud account |
| 4 | QuickJS runtime for JS apps, APPS menu, binary `.anim` | done |
| 5 | Auto brightness, presence, auto ON CALL | done |

Endpoint-level status: [API_COVERAGE.md](API_COVERAGE.md). Upstream versions:
[UPSTREAM.md](UPSTREAM.md).

## Layout

| Module | Kind | Contents |
|---|---|---|
| `core` | JVM | Framebuffers, LVGL font reader, animation player, draw-element parser, canvas service, renderer |
| `engine` | JVM | `busy_timer` port, input service, desktop/mode switch, BUSY app and other built-in apps, QuickJS app runtime |
| `server` | JVM | Ktor (CIO) HTTP API, protobuf status stream, web UI, firmware-style auth |
| `app` | Android | Kiosk activity, Compose LED panel, phone controls, Android platform (battery, Wi-Fi, clock, brightness, volume), mDNS |
| `vendor/` | data | Pinned upstream assets, web UI, OpenAPI spec, protobufs |
| `upstream/` | scripts | `fetch.sh` (clone pinned repos), `sync.sh` (refresh `vendor/`) |

`core`, `engine` and `server` are plain JVM modules, so they are tested on the desktop without an
emulator. `./gradlew :engine:test` also writes previews of the BUSY app's screens to
`engine/build/previews/`.

## Controls

| Phone | Bar |
|---|---|
| Volume up / down, or drag up / down | Dial clockwise / counter-clockwise: move through menus, change values, ±5 min in a session |
| Tap | START (start, pause, resume) |
| Long-press | BACK (stop a session; resume when paused; leave a menu or app) |
| Keep holding, about 2 s | Long BACK: force-quits a JS app |
| Swipe right / left | Mode switch to the next / previous position: BUSY, CUSTOM, OFF, APPS, SETTINGS |
| Wave over the proximity sensor | OK (dial button: skip the current interval) |
| Bixby key, if One UI delivers it | Mode switch, next position |

OFF is the bar's sleep: the screen goes fully black. In menus (BUSY → Setup, and SETTINGS) the
dial moves between items, a tap starts or stops editing the item (it turns yellow), and a
long-press stops editing or goes back; leaving the timer setup saves it. CUSTOM is a second
profile, by default an endless KEEP OUT timer; change it under its own Setup. On the emulator, a keyboard works too: arrows, Enter = OK,
Space = START, Esc/Backspace = BACK, 1–5 = switch positions.

APPS lists the installed JS apps; turn the dial to pick one and tap to start it. A running app
gets the dial, tap (START), long-press (BACK) and the proximity wave (OK); most apps quit on BACK,
and holding about 2 s always force-quits.

## Official BUSY app (Bluetooth)

Follow the official BUSY app's instructions: move the switch to SETTINGS and open Bluetooth.
The phone shows "Pairing mode..." and appears in the app as "BUSY Bar". When you connect,
Android's pairing prompt appears on the phone; accept it, and the screen shows "Connected". The
app then controls the phone over Bluetooth like a real bar. Leaving the screen without pairing
(or 5 minutes) turns Bluetooth off again. With a phone paired, SETTINGS → Bluetooth offers
"Forget device". Linking a BUSY account does not work (it needs Flipper's cloud).

## JS apps

Apps built with [busy-cli](https://www.npmjs.com/package/@busy-app/cli) install and run as on a
real bar, from the app's folder:

```bash
npx busy-cli app install --addr busybar.local:8080
```

```bash
npx busy-cli app run --addr busybar.local:8080
```

The upstream clock and weather apps run unmodified. Weather's forecast normally comes from
Flipper's apps backend, which only answers real bars (it checks the bar's device certificate),
so the phone serves that API itself (`/weather/v1/forecast` and `/locations`) from
[Open-Meteo](https://open-meteo.com). With the location on Auto, the bar's time zone picks the
city, e.g. Asia/Singapore → Singapore; set a city in the app's settings in the web UI for
anywhere else.

## Sensors

- **Auto brightness** (SETTINGS → Brightness → Auto) is the firmware's own algorithm on the
  phone's light sensor: one sample a second, a 5-sample average, lux on a log scale to 16
  levels, then the front display's brightness curve.
- **Presence** (SETTINGS → Sensors, off by default): the front camera looks for faces about twice
  a second with Android's built-in face detector, which finds upright faces turned towards the
  phone; after *Away after* minutes without one, the panel goes
  dark, and it lights up again when someone looks at it. Touching the phone also counts, and the
  first touch or key on a dark panel only wakes it.
- **Auto ON CALL** (SETTINGS → Sensors, off by default): the microphone feeds a voice activity
  detector; about 3 seconds of talk within 6 starts the CUSTOM profile with the `on_call` theme as
  an endless session, and a minute of silence ends it. It never replaces a session someone else
  started, and leaves alone a session that was changed after it started.

Camera frames and audio are processed in memory and never stored or sent. While the camera is
on a green dot shows in the top-right corner, and an orange one while the microphone is.

The panel guards against AMOLED burn-in: the LED grid orbits by half an LED every 2 minutes,
and static content dims to 40% after 5 minutes.

## Build

Requirements: JDK 17, Android SDK with platform 28 and 36 (`ANDROID_HOME` or `local.properties`).

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
device owner.

On the LAN the phone answers as **`http://busybar.local:8080`** (the name follows the device
name, as on the real bar); SETTINGS → Network shows the address too. Open that URL in a browser
for BUSY's own web UI: live screen, timer profiles, settings, draw tool and apps.

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
