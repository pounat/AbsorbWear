# AbsorbWear

The Wear OS companion to [Absorb](https://github.com/pounat/absorb), for Audiobookshelf and BookOrbit servers. Native Kotlin and Compose, written for a round watch.

Still early. It ships as a sideload next to each Absorb release and carries the same version number as the phone app it was built with. For now there is only the GitHub build, which pairs with the Absorb APK from GitHub. A Play Store version comes later.

## What it does

- Signs in from the phone over the Data Layer, or by hand on the watch.
- A card per book you're listening to, with play on the watch or on the phone right on the card.
- Plays on the watch to Bluetooth headphones, streaming or downloaded, and reports sessions to the server like the phone does.
- Controls the phone's playback: play, pause, skips, chapters, bookmarks, sleep timer, volume on the crown.
- Hands a book between the two, picking up where the other left off.
- Pauses the book when the watch sees you fall asleep, on the watch or on the phone, and rewinds to where sleep set in. Playing again right away counts as a wrong call and puts the position back.
- Browses the server's shelves, playlists, collections and podcast episodes, with search, a book page with details, and a library switcher.
- Follows the phone's playback settings and theme color.
- Watch face complications for the current book.

## Installing

Grab `absorbwear-<version>.apk` from the [Absorb release](https://github.com/pounat/absorb/releases) that matches your phone build and sideload it to the watch over wireless debugging:

```
adb connect <watch ip>:<port>
adb install -r absorbwear-<version>.apk
```

## Pairing rule

The watch app only talks to a phone app with the same package name and the same signing key. The `github` flavor pairs with the Absorb GitHub build, the `dev` flavor with Absorb Dev. A Play build of the phone app only works if Play signs it with the same key.

## Building

Needs an Android SDK with API 35 and a JDK 17 or newer; the one bundled with Android Studio works.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDevRelease
```

Signing reads `key.properties` from the repo root, or falls back to `../absorb/android/key.properties` when Absorb is checked out next door. Without either you get an unsigned build that won't pair with a signed phone app.

The version comes from `-PversionName` and `-PversionCode`, which the Absorb release workflow passes in from the phone's pubspec. Local builds use the numbers in `app/build.gradle.kts`, bumped with the phone app.

Install on a watch paired for wireless debugging with `.\scripts\install-watch.ps1`, or by hand with `adb install -r` on the APK under `app/build/outputs/apk`. `scripts/find-watch-port.py` finds the port Wear OS picked for wireless debugging when it has changed again.

## License

GPLv3, like Absorb. See [LICENSE](LICENSE).
