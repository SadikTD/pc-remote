# PC Remote

Control your Windows PC from your Android phone while watching its screen: trackpad, keyboard, media keys, shortcuts and pinch-to-zoom, over your home Wi-Fi.

## Get it

- **Phone app:** download the latest APK from [Releases](https://github.com/SadikTD/pc-remote/releases/latest).
- **PC app (Sunshine):** download `Sunshine-Windows-AMD64-installer.msi` from [SadikTD/Sunshine releases](https://github.com/SadikTD/Sunshine/releases/latest).

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
