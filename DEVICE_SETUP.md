# Device setup: Galaxy S8+ (SM-G955, One UI 1.0 / Android 9)

These steps turn the phone into an appliance that boots straight into BUSY S8. Run the adb
commands from a computer with `adb` installed (Android platform-tools).

## 1. Factory reset, no accounts

1. Settings → General management → Reset → Factory data reset.
2. In the setup wizard, **skip every account step**: no Google account, **no Samsung account**.
   Connect to Wi-Fi (the bar needs the LAN). Skip Secure Folder: it adds a second Android user,
   and a device owner can't be set while other users exist.
3. Enable developer options (Settings → About phone → Software information → tap *Build number*
   7 times), then Settings → Developer options → **USB debugging** on.
4. Plug into the computer, accept the RSA prompt, and check:

```bash
adb devices
```

## 2. Debloat (optional but recommended)

`pm uninstall --user 0` removes a package for the main user only. It survives reboots and is
reversible: `adb shell cmd package install-existing <package>` brings a package back.
Packages not present on your firmware print `Failure [not installed for 0]`, which is harmless.

Save this as `debloat.txt` and run the loop below it. Everything here is safe to remove for a
kiosk with no accounts. Lines starting with `#` are skipped.

```text
# Bixby
com.samsung.android.bixby.agent
com.samsung.android.bixby.agent.dummy
com.samsung.android.bixby.es.globalaction
com.samsung.android.bixby.wakeup
com.samsung.android.bixby.plmsync
com.samsung.android.bixby.voiceinput
com.samsung.android.bixby.service
com.samsung.android.bixbyvision.framework
com.samsung.android.visionintelligence
com.samsung.systemui.bixby
com.samsung.systemui.bixby2
com.samsung.android.app.spage
com.samsung.android.svoiceime
# Samsung account and cloud (also keeps accounts off the device)
com.osp.app.signin
com.samsung.android.mobileservice
com.samsung.android.scloud
com.samsung.android.samsungpass
com.samsung.android.samsungpassautofill
com.samsung.android.spay
com.samsung.android.spayfw
com.sec.android.app.samsungapps
com.samsung.android.themestore
com.samsung.android.stickercenter
com.sec.android.app.billing
# Samsung apps and services
com.sec.android.app.sbrowser
com.samsung.android.email.provider
com.samsung.android.calendar
com.samsung.android.app.notes
com.samsung.android.app.reminder
com.samsung.android.voc
com.samsung.android.app.tips
com.samsung.android.game.gamehome
com.samsung.android.game.gametools
com.samsung.android.game.gos
com.samsung.android.kidsinstaller
com.sec.android.app.kidshome
com.samsung.android.aremoji
com.samsung.android.app.watchmanagerstub
com.samsung.android.oneconnect
com.samsung.android.beaconmanager
com.samsung.android.easysetup
com.samsung.android.app.sharelive
com.samsung.android.allshare.service.fileshare
com.samsung.android.allshare.service.mediashare
com.sec.android.easyMover
com.samsung.android.smartswitchassistant
com.samsung.android.da.daagent
com.samsung.android.dqagent
com.samsung.android.app.appsedge
com.samsung.android.app.cocktailbarservice
com.samsung.android.service.aircommand
com.samsung.android.app.galaxyfinder
# Third-party preloads
com.facebook.katana
com.facebook.system
com.facebook.appmanager
com.facebook.services
com.microsoft.skydrive
com.microsoft.office.excel
com.microsoft.office.word
com.microsoft.office.powerpoint
com.linkedin.android
# Google apps (BUSY S8 uses no Google services; ML Kit face detection is bundled)
com.android.chrome
com.google.android.youtube
com.google.android.apps.maps
com.google.android.gm
com.google.android.music
com.google.android.videos
com.google.android.apps.docs
com.google.android.apps.photos
com.google.android.talk
com.google.android.apps.tachyon
com.google.android.googlequicksearchbox
com.android.vending
com.google.android.gms
```

```bash
grep -v '^#' debloat.txt | while read -r p; do adb shell pm uninstall --user 0 "$p" < /dev/null; done
```

**Do not remove**: `com.android.systemui`, `com.android.settings`, `com.android.phone`,
`com.android.shell`, any `com.android.providers.*` or `com.samsung.android.providers.*`,
`com.sec.android.inputmethod` (keyboard), `com.samsung.android.knox.*`, or
`com.sec.android.app.launcher` (the fallback launcher, in case BUSY S8 is ever uninstalled).

## 3. Install BUSY S8 and make it device owner

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell dpm set-device-owner com.busys8/.AdminReceiver
```

If this fails with *already some accounts on the device*, remove them in Settings → Accounts
(or debloat `com.osp.app.signin` and `com.google.android.gms`, which own them) and retry.
*Already several users* means Secure Folder or a guest user exists: remove it first.

Then reboot. As device owner, BUSY S8 sets itself as the persistent HOME app, disables the
keyguard and status bar, enters lock-task mode, and keeps the screen on while plugged in.

```bash
adb reboot
```

To undo device owner (for development): `adb shell dpm remove-active-admin com.busys8/.AdminReceiver`
only works for test-only builds, so the reliable way out is a factory reset.

## 4. Network

BUSY S8 serves the BUSY Bar API on **port 8080**, because Android does not let unprivileged
apps listen on port 80. The idle screen shows the address. Give the phone a DHCP reservation in
your router so the address stays fixed.

- Web UI: `http://<phone-ip>:8080/`
- busy-cli: `busy-cli app install --addr <phone-ip>:8080`
- busylib-py: `BusyBar(addr="<phone-ip>:8080")`
- Home Assistant (busy-hacs): add the integration manually with host `<phone-ip>:8080`.
  Zeroconf auto-discovery finds the phone but drops the port, so discovered entries won't connect.

### Optional, rooted phones only: serve on port 80

Last resort if a client can't be pointed at :8080. This redirects inbound :80 to :8080 until
the next reboot; re-run it from a boot script (e.g. a Magisk `service.d` script) to keep it:

```bash
adb shell su -c 'iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080'
```

## 5. Battery

The phone is meant to live on a charger. Charge limiting needs root and is out of scope;
BUSY S8 reports battery level and temperature at `/api/status/power`.
