# AirPhone

Use an Android phone as an air mouse + remote for a Xiaomi Mi TV Stick (or any Android TV / Bluetooth HID host).
Nothing to install on the TV: the phone pairs as a standard Bluetooth keyboard + mouse.

**[Website + install](https://harunethm.github.io/airphone/)** · **[Releases](https://github.com/harunethm/airphone/releases)**

<p align="center"><img src="assets/screenshots/remote.png" alt="AirPhone remote screen" width="260"></p>

## Features
- **Air mouse**: tilt/turn the phone to move the TV cursor (gyro, works flat or tilted), speed slider.
- **Touchpad**: drag to move, tap to click.
- **Remote**: D-pad + OK, Back, Home, Menu, Power, Volume −/+, Mute, Play/Pause (hold to auto-repeat).
- **Keyboard**: type into the "Type on TV" field and it's typed live on the TV.

## Requirements
- Phone: Android 9+ with the Bluetooth HID Device profile (most phones; some OEM ROMs remove it — the app tells you).
- Gyroscope for air mouse (touchpad works without).

## Install
Open **https://harunethm.github.io/airphone/** on the phone, tap **Download APK**, and allow "install unknown apps"
for the browser once. Or build it yourself:
```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pair (once)
Android TV's own "Add accessory" screen never lists a phone (it only shows keyboards, mice, remotes and
speakers), so pairing starts on the phone. Have the TV remote at hand to confirm the code.

1. Open AirPhone → allow Bluetooth permission. The **Devices** sheet opens.
2. Add the TV, either way:
   - **TV's Bluetooth address** (pairs with every Android TV, and the only way on Android TV 9–11 such as the
     Mi TV Stick): on the TV open **Settings → Device Preferences → About → Status → Bluetooth address**, type it
     in, tap **Pair**.
   - **Search nearby** (Android TV 12+): open **Settings → Remotes & Accessories → Add accessory** on the TV so it
     becomes visible, then pick it in the list.
3. Confirm the same code on the phone and on the TV. It connects by itself; next time it reconnects to the last TV
   when the app opens.

Computers and tablets can also pair from their side: **Devices → Computer or tablet? Pair from it instead…**

Can't connect? The Devices sheet shows what failed. If the TV was reset or you removed the phone there, tap ✕ to
forget the TV in AirPhone and add it again.

## Limits
- Hosts on Android 15 (and newer Google TV builds) only accept a keyboard they connected to themselves the first
  time. On a phone/tablet host, tap AirPhone in its Bluetooth settings once; after that AirPhone reconnects by itself.
- The link drops while the app is in the background or the phone screen is off (Android unregisters
  background HID apps); it reconnects in a few seconds when you reopen the app. The screen stays on while the app is open.
- Keyboard uses a US layout: Turkish letters are sent as the closest English letter (ş→s, ç→c, ğ→g, ı→i, ö→o, ü→u);
  other characters with no US key (emoji, €) are skipped.
- **Power** sends a sleep/power key; waking the stick from the phone is not guaranteed — use the TV remote/HDMI-CEC.
