# v1 Track A: Android App Redesign, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Every task is TDD: write the listed tests first, watch them fail, implement, watch them pass, then commit.

**Goal:** Rebuild every Android screen on the "Midnight" design system, add the agent client with QR pairing, and make the APK lightweight.

**Architecture:** One Compose `MainActivity` (single-activity, Navigation Compose) replaces `PcListActivity` and `SettingsActivity`. Moonlight's streaming core and the `Game` activity stay; `Game` gets a restyled overlay. New code goes in `com.pcremote.*`. The agent client speaks `docs/protocol.md` over OkHttp WebSocket (already a dependency).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, kotlinx-serialization-json, OkHttp 5 (existing), Google code scanner (`play-services-code-scanner`), Android Keystore, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-15-pc-remote-v1-design.md`. **Protocol:** `docs/protocol.md`.

## Global Constraints
- minSdk 26. Gradle namespace stays `com.limelight`; new code lives in `com.pcremote.*`; tests in `app/src/test/java/com/pcremote/`.
- Don't modify `app/src/main/jni/**`, `nvstream/**`, `binding/video/**`, `binding/audio/**`.
- Colours, shapes and type come only from `com.pcremote.ui.theme` tokens. No literal colours in screens.
- Release APK (arm64) under 8 MB. No new dependency over 300 KB after R8, except the code scanner (Play-services delegated, about 0 KB of model).
- Build from `P:` (`subst` of the project folder) with `JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"`. Tests: `./gradlew :app:testDebugUnitTest`.
- Every user-visible string goes in `strings.xml` (prefix `pcr_`).
- Existing stage 1 behaviour (auto-reconnect, friendly errors, zoom, toolbar actions, lower-quality offer) must keep working.

## Review Focus
1. **Pairing QR scanned while the phone is on a different network than every listed IP** → a clear "Can't reach your PC. Are you on the same Wi-Fi?" message, not a spinner forever. Test: `AgentConnectionTest.allHostsUnreachableFailsWithReason`.
2. **Certificate fingerprint mismatch** (someone else's PC on that IP) → the connection is refused and nothing is stored. Test: `PinnedTrustTest.mismatchRejects`.
3. **PC known to Moonlight but not to the agent** (stage 1 PIN-paired PCs) → still listed on Home with Screen mode only; other modes say "Install PC Remote on your PC". Test: `HomeModelTest.legacyPcScreenOnly`.
4. **System font scale at 200%** → nothing clips on Home or onboarding. Check: compose preview at `fontScale = 2f`, plus emulator check.
5. **Rotation during onboarding / scan** → the step is kept. `rememberSaveable` state; emulator check.

---

### Task A1: Midnight design system
**Files:** create `com/pcremote/ui/theme/{Tokens.kt, Theme.kt, Motion.kt, Haptics.kt}`; test `ui/theme/TokensTest.kt`.
**Produces:** `object Midnight { val bg, surface, surfaceHigh, border, accent, accent2, ok, warn, bad, text, textDim : Color }`, `MidnightTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = false, content)`, `Shapes` (card 24 dp, control 16 dp, pill 50%), `Springs.snappy/gentle: SpringSpec<Float>`, `rememberHaptics(): Haptics { tick(); click(); heavy() }` (respects a `haptics_enabled` pref).
**Tests (write first):**
- `text on every surface meets WCAG AA`: contrast(text, bg/surface/surfaceHigh) ≥ 4.5, contrast(textDim, surface) ≥ 3.0. Include a `contrast(a, b)` helper in Tokens.kt.
- `light palette derived from same tokens also meets AA`.
**Done:** tests pass; `MidnightTheme` wraps the existing screens without visual regressions.
- [ ] RED → GREEN → commit `feat(ui): Midnight design system`

### Task A2: Protocol model + pairing URI
**Files:** add `org.jetbrains.kotlin.plugin.serialization` plus `kotlinx-serialization-json`; create `com/pcremote/agent/{Protocol.kt, PairUri.kt}`; tests `agent/ProtocolTest.kt`, `agent/PairUriTest.kt`.
**Produces:** `sealed interface Msg` with `@Serializable @SerialName("<t>")` classes for every type in protocol.md (class discriminator `t`); `object Wire { fun encode(m: Msg): String; fun decode(s: String): Msg? }` (unknown `t` → null; `ignoreUnknownKeys`); `data class PairUri(name, hosts: List<String>, port: Int, fingerprint: String, otp: String) { companion fun parse(s: String): PairUri? }`.
**Tests:**
- Round-trip every message type; decoding `{"t":"mouse.move","dx":1,"dy":2,"extra":1}` ignores `extra`; unknown `t` → null.
- `PairUri.parse` accepts the protocol example; rejects a wrong scheme, a missing `fp`, an `fp` that isn't 64 hex chars, an empty `h`, and a port outside 1..65535; decodes a URL-encoded name.
- [ ] RED → GREEN → commit `feat(agent): protocol model and pairing URI`

### Task A3: Agent connection
**Files:** `com/pcremote/agent/{PinnedTrust.kt, AgentConnection.kt}`; tests `PinnedTrustTest.kt`, `AgentConnectionTest.kt`.
**Produces:**
- `PinnedTrust.trustManager(fpHex)` and `sslSocketFactory(fpHex)`: accept only a leaf certificate whose SHA-256 DER hex equals `fpHex`; the hostname verifier accepts any name.
- `class AgentConnection(hosts, port, fp, scope, socketFactory = OkHttp)` with:
  - `state: StateFlow<AgentState>` where `AgentState` is one of `Connecting`, `Online(pc: PcInfo, caps: Set<String>)`, `Failed(reason: FailReason)` (`UNREACHABLE`, `UNAUTHORIZED`, `REVOKED`, `BAD_OTP`, `PIN_TIMEOUT`, `CERT_MISMATCH`), and `Closed`
  - `incoming: SharedFlow<Msg>`; `binary: SharedFlow<ByteArray>`
  - `suspend fun pair(otp, device, pin): Result<String /*token*/>`
  - `suspend fun hello(token, device)`
  - `fun send(m: Msg)`, `fun sendBinary(b: ByteArray)`, `fun close()`
  - Hosts are tried in order with a 2 s connect timeout each. A ping every 10 s; reconnect with backoff 1/2/5/10 s while not `Closed`.
- The socket goes through an interface (`WsFactory`) so tests can use a fake.
**Tests:**
- `mismatchRejects` / `matchAccepts` with two generated certificates.
- `allHostsUnreachableFailsWithReason` → `Failed(UNREACHABLE)` after trying every host.
- `helloSuccessGoesOnline`, `revokedErrorGoesFailedRevoked`, `pairReturnsToken`, and `badOtp` → `Failed(BAD_OTP)`.
- `reconnectsAfterDropUntilClosed` (fake clock).
- [ ] RED → GREEN → commit `feat(agent): pinned WSS connection with pair/hello`

### Task A4: Paired PC store
**Files:** `com/pcremote/agent/{SecretBox.kt, PcStore.kt}`; test `PcStoreTest.kt` (covers `PcStore` logic over an in-memory `KeyValue` interface; `SecretBox` is Android-only).
**Produces:**
- `data class PairedPc(id, name, hosts, port, fp, token, moonlightUuid: String?, macs)`.
- `class PcStore(kv: KeyValue, box: SecretBox)` with `all(): List<PairedPc>`, `upsert(pc)`, `remove(id)`, `byMoonlightUuid(uuid)`. Tokens are encrypted with an AES-GCM key in the Android Keystore (`SecretBox`).
**Tests:** upsert/replace by id; remove; the token is stored encrypted (the raw KV value never contains the token); `byMoonlightUuid` lookup.
- [ ] RED → GREEN → commit `feat(agent): encrypted paired-PC store`

### Task A5: Navigation shell + MainActivity
**Files:** `com/pcremote/MainActivity.kt`, `com/pcremote/nav/Routes.kt`; manifest: `MainActivity` becomes the launcher and `PcListActivity`/`SettingsActivity` are removed (their logic moves into A6–A9); test `nav/RoutesTest.kt`.
**Produces:** routes `onboarding`, `home`, `pc/{id}/remote|media|slides|pad|files`, `settings`, `settings/advanced`. The start destination is `onboarding` when there's no paired or Moonlight PC and the `onboarded` flag isn't set, otherwise `home`.
**Tests:** `startDestination(pcs=0, onboarded=false) == onboarding`; any PC → `home`; route builders encode ids.
- [ ] RED → GREEN → commit `feat(ui): single-activity navigation shell`

### Task A6: Onboarding + QR pairing
**Files:** `com/pcremote/onboarding/{OnboardingScreen.kt, PairingViewModel.kt}`; test `PairingViewModelTest.kt`.
**Flow:** 3 pager steps (Welcome hero with animated gradient; "Get PC Remote for Windows" with a QR code and the short link `github.com/SadikTD/pc-remote/releases`; "Scan the code on your PC" → `GmsBarcodeScanning`).

On a scan:
1. `PairUri.parse`.
2. `AgentConnection.pair` with a random PIN, while Moonlight pairing runs in parallel with the same PIN (reuse the `PcListActivity.pair` logic, moved here).
3. Save to `PcStore`, with `moonlightUuid` matched by host IP from `ComputerManagerService`.
4. Navigate to `home`.

States: `Idle`, `Scanning`, `Pairing(step)`, `Done`, `Error(@StringRes)`. A manual-IP link at the bottom opens the stage 1 PIN flow.
**Tests (ViewModel with fakes):** an invalid QR shows "That's not a PC Remote code"; `BAD_OTP` gives "Code expired, scan the new one"; `UNREACHABLE` gives the same-Wi-Fi message; `CERT_MISMATCH` gives the security message and nothing is stored; success stores the PC with its token.
- [ ] RED → GREEN → commit `feat(onboarding): 3-step onboarding with QR pairing`

### Task A7: Home
**Files:** `com/pcremote/home/{HomeScreen.kt, HomeModel.kt, PcCard.kt}`; test `HomeModelTest.kt`.
**Produces:**
- `HomeModel` merges Moonlight `ComputerDetails` (polling) with `PcStore` plus live `AgentConnection`s.
- Each card is a `PcCardUi(id, name, online, streaming, thumb: ImageBitmap?, modes: List<Mode>, primary: Action)`. `Mode` is `SCREEN`, `REMOTE`, `MEDIA`, `SLIDES`, `PAD` or `FILES`; `Action` is `OPEN`, `WAKE`, `PAIR` or `OFFLINE_HELP`.
- Agent connections are opened only while Home is visible (lifecycle-aware), and `thumb.sub` is sent only then.

**Visuals:** large glass cards, a breathing status dot, the thumbnail cross-fading in, mode chips in a horizontal row, a shared-element transition into a mode, pull-to-refresh, a "+" FAB that scans, and a long-press sheet with Rename/Remove/Power.
**Tests:**
- `legacyPcScreenOnly`: a Moonlight-only PC gets `[SCREEN]`.
- An agent PC that's online gets every mode its caps allow.
- Offline with a MAC → `WAKE`; offline without a MAC → `OFFLINE_HELP`.
- `streaming` is reflected from the `status` message.
- [ ] RED → GREEN → commit `feat(home): live PC cards with modes`

### Task A8: Screen mode overlay restyle
**Files:** replace `com/pcremote/stream/StreamToolbar.kt` with `StreamOverlay.kt`, `EdgePanel.kt`, `KeyboardBar.kt`, `CoachMarks.kt`; `Game.java` swaps the `StreamToolbarHost.attach` call for `StreamOverlayHost.attach`; test `stream/KeyboardBarModelTest.kt`.
**Produces:**
- A right-edge handle (a 16 dp drag zone). Swiping in opens a glass `EdgePanel` with sections Keys · Media · Shortcuts · Mode (trackpad/direct) · Quality · Disconnect.
- `KeyboardBar` sits above the IME with sticky modifiers: tap = one-shot, double-tap = lock. It has Esc, Tab, arrows and an F1–F12 flyout.
- A stats pill (dot + ms); tapping it expands to full stats.
- Coach marks shown once (`coach_shown` pref).
- Same `ToolbarActions` interface, plus `sendKey(vk, down)`.

**Tests:** `KeyboardBarModel`: a one-shot modifier is applied to the next key then released; a double-tapped modifier stays locked until tapped again; the emitted `KeyEvt` sequence for Ctrl(one-shot)+C equals `Shortcut.COPY.events()`.
- [ ] RED → GREEN → commit `feat(stream): edge panel, keyboard bar, stats pill, coach marks`

### Task A9: Settings redesign
**Files:** `com/pcremote/settings/SettingsScreen.kt` (replaces `SettingsActivity`); `SettingsModel.kt`; test `SettingsModelTest.kt`.
**Sections:**
- Appearance: theme (System/Dark/Light), dynamic colour.
- Stream quality: Auto / Smooth / Sharp / Battery / Advanced. Advanced opens upstream `StreamSettings`, themed with Midnight via a style override, and turns Auto off (the stage 1 behaviour).
- Controls: haptics, natural scrolling, pointer speed 0.5–3×, gyro sensitivity.
- Connection: access from anywhere (shown only with an agent).
- About: version, GitHub link, licences.

**Tests:** pointer speed is clamped to 0.5..3; natural scrolling inverts `dy` in `ScrollMapper.map(dy)`; theme pref round-trip.
- [ ] RED → GREEN → commit `feat(settings): Midnight settings screen`

### Task A10: Lightweight pass
**Files:** `app/build.gradle` (release: `minifyEnabled true`, `shrinkResources true`; `splits.abi { enable true; include 'arm64-v8a','armeabi-v7a'; universalApk false }`; signing config read from `~/.pcremote/keystore.properties`, falling back to debug signing when it's missing); delete `AppView.java`, `PcView.java`, `grid/**`, `PosterContentProvider.java`, `ShortcutTrampoline.java`, `utils/ShortcutHelper.java`, `utils/TvChannelHelper.java`, and the related layouts and manifest entries (fix references in `Game.java`/`ServerHelper.java`); remove the Leanback/TV feature flags.
**Check (instead of a unit test):** `./gradlew :app:assembleRelease`, then:
- arm64 APK under 8 MB (record the number);
- `testDebugUnitTest` green;
- an emulator smoke test: launch, Home renders, Settings opens, and a stream to an unreachable host shows the friendly error.
- [ ] Build → measure → commit `perf: R8, ABI splits, remove legacy Moonlight UI`

### Task A11: Release
Build the release APKs, rename them `PC-Remote-1.0.0-arm64.apk` / `-armv7.apk`, and attach them to a GitHub release `v1.0.0` on `SadikTD/pc-remote`, together with the Host installer from Track B if it's ready. **Only after the user confirms the release.**
