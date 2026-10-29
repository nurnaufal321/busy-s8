#!/bin/sh
# Starts the desktop-app relay (tools/usb-bridge.py) automatically on macOS:
#  - a LaunchDaemon gives the Mac 10.0.4.20 at every boot (needs sudo, asked once here);
#  - a LaunchAgent runs the relay whenever you are logged in, restarting it if it stops.
# Usage: tools/macos/install.sh [phone address, default busybar.local:8080]
# Remove with tools/macos/uninstall.sh.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE="$(cd "$HERE/.." && pwd)/usb-bridge.py"
PHONE="${1:-busybar.local:8080}"
PYTHON="$(command -v python3)"

sudo cp "$HERE/app.busys8.usb-address.plist" /Library/LaunchDaemons/
sudo chown root:wheel /Library/LaunchDaemons/app.busys8.usb-address.plist
sudo launchctl bootout system/app.busys8.usb-address 2>/dev/null || true
sudo launchctl bootstrap system /Library/LaunchDaemons/app.busys8.usb-address.plist

AGENT="$HOME/Library/LaunchAgents/app.busys8.usb-bridge.plist"
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>app.busys8.usb-bridge</string>
    <key>ProgramArguments</key>
    <array><string>$PYTHON</string><string>-u</string><string>$BRIDGE</string><string>$PHONE</string></array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>ThrottleInterval</key><integer>10</integer>
    <key>StandardOutPath</key><string>$HOME/Library/Logs/busy-s8-usb-bridge.log</string>
    <key>StandardErrorPath</key><string>$HOME/Library/Logs/busy-s8-usb-bridge.log</string>
</dict>
</plist>
PLIST
launchctl bootout "gui/$(id -u)/app.busys8.usb-bridge" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT"
echo "10.0.4.20 -> $PHONE now starts at login (log: ~/Library/Logs/busy-s8-usb-bridge.log)"
