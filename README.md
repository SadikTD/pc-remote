# PC Remote

Control your Windows PC from your Android phone while watching its screen: trackpad, keyboard, media keys, shortcuts and pinch-to-zoom, over your home Wi-Fi.

## Get it

From [Releases](https://github.com/SadikTD/pc-remote/releases/latest):

- **Windows:** `PC-Remote-Host-1.0.0-setup.exe`. It installs everything (including Sunshine for screen sharing and the gamepad driver) and shows a QR code.
- **Android:** `PC-Remote-1.0.0-arm64.apk` (most phones) or `-armv7.apk` (older phones).

Open PC Remote on your phone and scan the code, with the app or any camera app. That's it.

Setup steps: [docs/setup-sunshine.md](docs/setup-sunshine.md).

## Build

Needs JDK 17+ and the Android SDK/NDK. The NDK build fails on paths containing spaces, so build from a path without them.

```
git clone --recurse-submodules https://github.com/SadikTD/pc-remote.git
cd pc-remote
./gradlew :app:assembleDebug
```

## License

GPL-3.0. Based on [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android); the PC side is [Sunshine](https://github.com/LizardByte/Sunshine) (forked at [SadikTD/Sunshine](https://github.com/SadikTD/Sunshine)).
