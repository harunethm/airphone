# Changelog

## 0.2.0
- New Devices sheet: pair from the phone by the TV's Bluetooth address (pairs with every Android TV, including the
  Mi TV Stick, whose Add accessory screen never lists phones) or by searching nearby (Android TV 12+).
  Android 15+ hosts may refuse the first connection unless they connect to the phone themselves once.
- Paired list puts the connected TV and TVs used before first; headphones and watches are tucked away.
- Clear errors instead of a silent wait: a connect gives up after 15 s; refused, unreachable, wrong-address and
  cancelled pairings each say so, with a short help box.
- Forget a TV from the app (✕). Disconnect stops auto-reconnect until you pick a TV again.

## 0.1.0
- First release: use an Android phone as an air mouse, remote and keyboard for a Mi TV Stick or any Android TV.
- Pairs over Bluetooth as a standard keyboard + mouse; nothing to install on the TV.
- Air mouse (gyro, works held flat or upright) with a speed slider, plus a touchpad: drag to move, tap to click.
- Remote keys: D-pad + OK, Back, Home, Menu, Power, volume, mute, play/pause; hold to repeat.
- Type on the TV with the phone keyboard; Turkish letters are sent as their closest English letter.
- Reconnects to the last TV when the app is opened.
