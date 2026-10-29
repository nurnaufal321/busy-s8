#!/bin/sh
# Removes what install.sh set up.
launchctl bootout "gui/$(id -u)/app.busys8.usb-bridge" 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/app.busys8.usb-bridge.plist"
sudo launchctl bootout system/app.busys8.usb-address 2>/dev/null
sudo rm -f /Library/LaunchDaemons/app.busys8.usb-address.plist
sudo ifconfig lo0 -alias 10.0.4.20 2>/dev/null
echo "removed"
