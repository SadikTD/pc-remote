# PC Remote

Control your Windows PC from your Android phone, at home or from anywhere. You can:
- see the PC's screen;
- use the phone as a trackpad, keyboard or gamepad;
- control media and slides;
- move files and the clipboard between devices;
- wake, sleep or shut down the PC.

## How it works

- **Phone app and PC app:** a native Android app and a small Windows app that runs quietly in the tray. Both are written from scratch for this project.
- **Screen streaming:** our own engine. The PC captures the screen and encodes it on the GPU (H.264, HEVC or AV1). The video travels over encrypted UDP with forward error correction, and the phone decodes it in hardware with low-latency settings. On Wi-Fi this is typically about 10 ms from PC to phone.
- **Same network:** the phone finds the PC automatically (mDNS) and connects without any taps.
- **From anywhere:** the PC keeps one idle connection to a small Cloudflare Worker, so the phone can reach it from mobile data. The stream goes direct through UDP hole punching when possible, and through a Cloudflare TURN relay otherwise. See [docs/remote-protocol.md](docs/remote-protocol.md).

## Get it

From [Releases](https://github.com/SadikTD/pc-remote/releases/latest):

- **Windows:** `PC-Remote-Host-2.0.0-setup.exe`. Install it, and it shows a QR code. The only extra piece it installs is the virtual gamepad driver (ViGEmBus).
- **Android:** `PC-Remote-2.0.0.apk`.

Open PC Remote on the phone and scan the code, with the app or any camera app. If the phone can't scan, use **Copy pairing link** on the PC, send the link to the phone, and choose **Paste a pairing link** in the app.

## Build

- **Android:** JDK 17+ and the Android SDK.
  ```
  ./gradlew :app:assembleNonRootRelease
  ```
- **Windows host:** Rust, Node.js and the Visual Studio C++ build tools.
  ```
  cd host && npm install && npx tauri build
  ```
- **Rendezvous Worker:**
  ```
  cd cloud/rendezvous && npm install && npx wrangler deploy
  ```
  For the TURN relay, also set the `TURN_KEY_ID` and `TURN_KEY_TOKEN` secrets.

Protocols: [docs/protocol.md](docs/protocol.md) (control), [docs/stream-protocol.md](docs/stream-protocol.md) (video, audio and input), [docs/remote-protocol.md](docs/remote-protocol.md) (remote access).

## License

GPL-3.0.
