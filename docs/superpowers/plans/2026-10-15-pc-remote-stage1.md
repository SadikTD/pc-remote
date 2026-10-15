# PC Remote Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app (a moonlight-android fork) that finds a Sunshine PC, pairs, and streams the desktop with a friendlier trackpad/keyboard/toolbar UI, quality presets, auto-reconnect and clear error messages.

**Architecture:** This repo root becomes a fork of `moonlight-android`. Moonlight's streaming core (`nvstream/`, `binding/video`, `binding/audio`, JNI) is not touched. New logic goes in small, unit-tested Kotlin files under `com.pcremote`; new screens are Jetpack Compose. Moonlight's `Game` activity stays as the stream screen and gets small, targeted hooks.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Java 17, Android Gradle Plugin 9.4 (built-in Kotlin), NDK (as upstream), JUnit 4. PC side: stock Sunshine.

**Spec:** `docs/superpowers/specs/2026-10-15-pc-remote-stage1-design.md`

## Deviations from the spec (found while reading upstream code)

1. **Layout:** the repo root *is* the fork (upstream's `app/` sits at the root next to `docs/`), not an `android/` subfolder. That keeps `git merge upstream/master` and the `moonlight-common-c` submodule working without path rewrites.
2. **Adaptive bitrate:** the GameStream protocol Sunshine speaks cannot change bitrate mid-stream. Instead: after 10 s of sustained "poor connection", the toolbar shows a **Lower quality** chip; one tap steps the preset down and reconnects (~2 s).
3. **Bandwidth probe:** there is no probe endpoint without PC-side code. Auto quality is picked from the phone's Wi-Fi link speed and band (`WifiManager`), which catches the common case (2.4 GHz or a weak link).
4. **Pan while zoomed:** one-finger drag already moves the mouse in trackpad mode, so **two-finger drag pans** while zoomed (and scrolls when not zoomed).
5. **Already in Moonlight, reused as-is:** trackpad tap / two-finger-tap right click / two-finger scroll / tap-hold drag (`RelativeTouchContext`), direct touch (`AbsoluteTouchContext`), perf overlay stats, Wake-on-LAN, PIN pairing, mDNS discovery, HEVC→H.264 decoder fallback on crash (`consecutiveCrashCount` in `MediaCodecDecoderRenderer`). No tasks re-implement these.

## Global Constraints

- minSdk 26 (Android 8.0); targetSdk/compileSdk as upstream (36/37).
- License GPL-3 (keep upstream `LICENSE.txt`).
- applicationId `com.pcremote.app` (debug suffix `.debug`); app label "PC Remote".
- Gradle `namespace` stays `com.limelight` (so `R` and upstream code are untouched). New code lives in package `com.pcremote` (`app/src/main/java/com/pcremote/`); tests live in `app/src/test/java/com/pcremote/`.
- Do not modify: `app/src/main/jni/**`, `nvstream/**`, `binding/video/**`, `binding/audio/**`.
- Only the `nonRoot` product flavor exists (the `root` flavor has `maxSdk 25`, which conflicts with minSdk 26).
- Unit test command: `./gradlew :app:testDebugUnitTest` (Windows: `gradlew.bat`).
- Sunshine's default desktop app is named exactly `Desktop`.

## Review Focus

1. **User taps Disconnect or quits on the PC** → must close cleanly, never auto-reconnect. Pinned in Task 4 (`graceful termination is never retried`).
2. **Phone rotates or the stream resizes while zoomed** → zoom must reset, not leave the picture off-screen. Pinned in Task 5 (`setContentSize resets zoom`).
3. **Phone on mobile data / Ethernet / USB tether (no Wi-Fi info)** → auto quality falls back to Smooth with no Wi-Fi tip. Pinned in Task 2 (`no wifi info falls back to smooth without hint`).
4. **Sunshine's `Desktop` app renamed or removed** → connect still launches the first available app instead of failing. Pinned in Task 8 (`falls back to first app when no Desktop`).
5. **Wrong PIN, or the PC drops mid-pairing** → a plain-language message, and the PC list keeps polling. Pinned in Task 8 (`pair outcome messages`).

---

### Task 1: Fork, rebrand and toolchain

**Files:**
- Repo root: becomes a git clone of moonlight-android (existing `docs/` stays untracked, then is committed)
- Modify: `build.gradle` (root), `app/build.gradle`, `app/src/main/res/values/strings.xml` (only if label strings live there; labels come from `resValue` in `build.gradle`)

**Interfaces:**
- Produces: a buildable app `com.pcremote.app.debug` with Kotlin + Compose enabled and a JUnit test source set.

- [ ] **Step 1: Turn the folder into the fork**

```bash
cd "D:/All Tools Source Code/PC Remote"
git init -b main
git remote add upstream https://github.com/moonlight-stream/moonlight-android.git
git fetch upstream
git checkout -b main upstream/master --force
git submodule update --init --recursive
```

Expected: `app/`, `build.gradle`, `LICENSE.txt` present; `docs/` still there (untracked).

- [ ] **Step 2: Add the Compose compiler plugin to the root `build.gradle`**

In `buildscript { dependencies { ... } }` add below the AGP classpath line:

```groovy
        classpath 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21'
```

(AGP 9 has built-in Kotlin, so don't apply `kotlin-android`. If Gradle sync reports a Kotlin version mismatch, set this version to the Kotlin version AGP 9.4 bundles, as shown in the sync error.)

- [ ] **Step 3: Edit `app/build.gradle`**

At the top, under `apply plugin: 'com.android.application'`:

```groovy
apply plugin: 'org.jetbrains.kotlin.plugin.compose'
```

In `defaultConfig`: change `minSdk 21` → `minSdk 26`.

In `buildFeatures` add `compose = true`.

Delete the whole `root { ... }` block from `productFlavors` (keep `nonRoot`), and in `nonRoot` change:

```groovy
            applicationId "com.pcremote.app"
```

In `buildTypes.debug` change the label lines to:

```groovy
            resValue "string", "app_label", "PC Remote (Debug)"
            resValue "string", "app_label_root", "PC Remote (Debug)"
```

In `buildTypes.release` change `applicationIdSuffix ".unofficial"` → remove that line (we have our own applicationId), and set:

```groovy
            resValue "string", "app_label", "PC Remote"
            resValue "string", "app_label_root", "PC Remote"
```

Add to `dependencies`:

```groovy
    implementation platform('androidx.compose:compose-bom:2025.10.00')
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.11.0'
    implementation 'androidx.compose.material:material-icons-extended'
    debugImplementation 'androidx.compose.ui:ui-tooling'
    testImplementation 'junit:junit:4.13.2'
```

(Use the newest stable BOM / activity-compose if these are outdated; the versions just need to be stable and mutually compatible.)

- [ ] **Step 4: Remove root-flavor-only sources**

```bash
git grep -n "PRODUCT_FLAVOR\|ROOT_BUILD" -- app/
```

`BuildConfig.ROOT_BUILD` is still generated by the `nonRoot` flavor (`false`), so Java references compile unchanged. Leave `Android.mk` as is; it only checks `PRODUCT_FLAVOR`.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/nonRoot/debug/`.

- [ ] **Step 6: Smoke test on a phone**

Run: `./gradlew :app:installDebug`, open "PC Remote (Debug)". Expected: upstream Moonlight PC list appears, and it installs alongside a real Moonlight.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: fork moonlight-android as PC Remote, enable Kotlin+Compose, minSdk 26"
```

---

### Task 2: Quality presets, auto-pick and Wi-Fi hint

**Files:**
- Create: `app/src/main/java/com/pcremote/quality/QualityPreset.kt`
- Create: `app/src/main/java/com/pcremote/quality/QualityPrefs.kt`
- Test: `app/src/test/java/com/pcremote/quality/QualityPresetTest.kt`

**Interfaces:**
- Produces:
  - `enum class QualityPreset(width, height, fps, bitrateKbps) { BATTERY, SMOOTH, SHARP }` with `fun stepDown(): QualityPreset`
  - `data class LinkInfo(val linkMbps: Int, val freqMhz: Int, val rssi: Int)`
  - `object LinkAdvisor { fun pick(link: LinkInfo?): QualityPreset; fun bitrateFor(preset, link: LinkInfo?): Int; fun hint(link: LinkInfo?): WifiHint? }`
  - `enum class WifiHint { BAND_2_4_GHZ, WEAK_SIGNAL }`
  - `object QualityPrefs { fun apply(ctx: Context, preset: QualityPreset, bitrateKbps: Int); fun current(ctx): QualityPreset; fun isAuto(ctx): Boolean; fun setAuto(ctx, Boolean); fun readLink(ctx): LinkInfo?; fun shouldShowHint(ctx, hint): Boolean }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pcremote.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityPresetTest {
    @Test fun `fast 5ghz link picks sharp`() {
        assertEquals(QualityPreset.SHARP, LinkAdvisor.pick(LinkInfo(866, 5180, -50)))
    }

    @Test fun `2_4ghz link picks smooth and hints`() {
        val link = LinkInfo(144, 2437, -50)
        assertEquals(QualityPreset.SMOOTH, LinkAdvisor.pick(link))
        assertEquals(WifiHint.BAND_2_4_GHZ, LinkAdvisor.hint(link))
    }

    @Test fun `weak signal hints`() {
        assertEquals(WifiHint.WEAK_SIGNAL, LinkAdvisor.hint(LinkInfo(400, 5180, -75)))
    }

    @Test fun `no wifi info falls back to smooth without hint`() {
        assertEquals(QualityPreset.SMOOTH, LinkAdvisor.pick(null))
        assertNull(LinkAdvisor.hint(null))
        assertEquals(QualityPreset.SMOOTH.bitrateKbps, LinkAdvisor.bitrateFor(QualityPreset.SMOOTH, null))
    }

    @Test fun `bitrate capped to a quarter of link speed with floor`() {
        assertEquals(10_000, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(40, 5180, -50)))
        assertEquals(3_000, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(6, 2437, -80)))
        assertEquals(QualityPreset.SHARP.bitrateKbps, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(866, 5180, -50)))
    }

    @Test fun `step down goes sharp to smooth to battery and stops`() {
        assertEquals(QualityPreset.SMOOTH, QualityPreset.SHARP.stepDown())
        assertEquals(QualityPreset.BATTERY, QualityPreset.SMOOTH.stepDown())
        assertEquals(QualityPreset.BATTERY, QualityPreset.BATTERY.stepDown())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.quality.QualityPresetTest`
Expected: FAIL, unresolved reference `LinkAdvisor`.

- [ ] **Step 3: Implement `QualityPreset.kt`**

```kotlin
package com.pcremote.quality

enum class QualityPreset(val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int) {
    BATTERY(1280, 720, 30, 5_000),
    SMOOTH(1280, 720, 60, 10_000),
    SHARP(1920, 1080, 60, 20_000);

    fun stepDown(): QualityPreset = entries[(ordinal - 1).coerceAtLeast(0)]
}

data class LinkInfo(val linkMbps: Int, val freqMhz: Int, val rssi: Int)

enum class WifiHint { BAND_2_4_GHZ, WEAK_SIGNAL }

object LinkAdvisor {
    private const val MIN_BITRATE_KBPS = 3_000

    fun pick(link: LinkInfo?): QualityPreset =
        if (link != null && link.freqMhz >= 5_000 && link.linkMbps >= 300 && link.rssi >= -67) QualityPreset.SHARP
        else QualityPreset.SMOOTH

    // ponytail: link speed is the phone<->router PHY rate, not end-to-end throughput; a real probe needs PC-side code (stage 3).
    fun bitrateFor(preset: QualityPreset, link: LinkInfo?): Int {
        if (link == null) return preset.bitrateKbps
        return minOf(preset.bitrateKbps, link.linkMbps * 1000 / 4).coerceAtLeast(MIN_BITRATE_KBPS)
    }

    fun hint(link: LinkInfo?): WifiHint? = when {
        link == null -> null
        link.freqMhz in 2_400..2_500 -> WifiHint.BAND_2_4_GHZ
        link.rssi < -70 -> WifiHint.WEAK_SIGNAL
        else -> null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.quality.QualityPresetTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Implement `QualityPrefs.kt`** (writes the same keys upstream `PreferenceConfiguration` reads)

```kotlin
package com.pcremote.quality

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.preference.PreferenceManager

object QualityPrefs {
    private const val KEY_PRESET = "pcremote_preset"
    private const val KEY_AUTO = "pcremote_auto_quality"
    private const val KEY_HINT_SHOWN = "pcremote_hint_shown_"

    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun apply(ctx: Context, preset: QualityPreset, bitrateKbps: Int = preset.bitrateKbps) {
        prefs(ctx).edit()
            .putString(KEY_PRESET, preset.name)
            .putString("list_resolution", "${preset.width}x${preset.height}")
            .putString("list_fps", preset.fps.toString())
            .putInt("seekbar_bitrate_kbps", bitrateKbps)
            .putBoolean("checkbox_enable_perf_overlay", true) // stats must be on for the toolbar readout; toolbar toggles visibility
            .apply()
    }

    fun current(ctx: Context): QualityPreset =
        prefs(ctx).getString(KEY_PRESET, null)?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
            ?: QualityPreset.SMOOTH

    fun isAuto(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)
    fun setAuto(ctx: Context, auto: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, auto).apply()

    @Suppress("DEPRECATION")
    fun readLink(ctx: Context): LinkInfo? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo ?: return null
        if (info.linkSpeed <= 0) return null
        return LinkInfo(info.linkSpeed, info.frequency, info.rssi)
    }

    /** Each hint is shown once per install. */
    fun shouldShowHint(ctx: Context, hint: WifiHint): Boolean {
        val key = KEY_HINT_SHOWN + hint.name
        if (prefs(ctx).getBoolean(key, false)) return false
        prefs(ctx).edit().putBoolean(key, true).apply()
        return true
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pcremote/quality app/src/test/java/com/pcremote/quality
git commit -m "feat: quality presets, Wi-Fi based auto pick and hints"
```

---

### Task 3: Friendly connection error messages

**Files:**
- Create: `app/src/main/java/com/pcremote/stream/ConnectionErrors.kt`
- Modify: `app/src/main/res/values/strings.xml` (append strings)
- Modify: `app/src/main/java/com/limelight/Game.java` (`stageFailed` and `connectionTerminated`, ~lines 2252–2372)
- Test: `app/src/test/java/com/pcremote/stream/ConnectionErrorsTest.kt`

**Interfaces:**
- Consumes: `MoonBridge.ML_ERROR_*` constants (Java compile-time constants; inlined by Kotlin, so tests never load the native lib).
- Produces: `data class FriendlyError(@StringRes val title: Int, @StringRes val message: Int, val retryable: Boolean)`; `object ConnectionErrors { fun forTermination(errorCode: Int, portsBlocked: Boolean): FriendlyError; fun forStageFailure(stage: String, portsBlocked: Boolean): FriendlyError }`

- [ ] **Step 1: Add strings** (append inside `<resources>` in `app/src/main/res/values/strings.xml`)

```xml
    <string name="pcr_err_lost_title">Connection lost</string>
    <string name="pcr_err_lost_msg">The connection to your PC dropped. Check that the PC is on and both devices are on the same Wi-Fi.</string>
    <string name="pcr_err_no_video_title">No picture from the PC</string>
    <string name="pcr_err_no_video_msg">Your PC is reachable but isn\'t sending video. Make sure Sunshine is running and your PC isn\'t locked on a secure screen (UAC prompt or login screen).</string>
    <string name="pcr_err_blocked_title">Blocked by a firewall</string>
    <string name="pcr_err_blocked_msg">Your PC\'s firewall or router is blocking the stream. Allow Sunshine through Windows Firewall (Sunshine\'s installer can do this), then retry.</string>
    <string name="pcr_err_protected_title">Protected content</string>
    <string name="pcr_err_protected_msg">The PC is showing protected content (for example a DRM video) that can\'t be streamed.</string>
    <string name="pcr_err_start_title">Couldn\'t start streaming</string>
    <string name="pcr_err_start_msg">Your PC didn\'t answer. Make sure Sunshine is installed and running on the PC. Need help? Open the setup guide from the PC list.</string>
    <string name="pcr_err_decoder_title">Video decoder problem</string>
    <string name="pcr_err_decoder_msg">Your phone couldn\'t start its video decoder. Retry. PC Remote switches to a safer video format automatically.</string>
    <string name="pcr_retry">Retry</string>
    <string name="pcr_close">Close</string>
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.pcremote.stream

import com.limelight.R
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorsTest {
    @Test fun `blocked ports win over any code`() {
        val e = ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, portsBlocked = true)
        assertEquals(R.string.pcr_err_blocked_title, e.title)
        assertTrue(e.retryable)
    }

    @Test fun `no video traffic explains sunshine and secure screen`() {
        assertEquals(R.string.pcr_err_no_video_msg,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, false).message)
        assertEquals(R.string.pcr_err_no_video_msg,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_FRAME, false).message)
    }

    @Test fun `protected content is not retryable`() {
        assertFalse(ConnectionErrors.forTermination(MoonBridge.ML_ERROR_PROTECTED_CONTENT, false).retryable)
    }

    @Test fun `unknown code is a retryable lost connection`() {
        val e = ConnectionErrors.forTermination(-12345, false)
        assertEquals(R.string.pcr_err_lost_title, e.title)
        assertTrue(e.retryable)
    }

    @Test fun `video stage failure is a decoder problem`() {
        assertEquals(R.string.pcr_err_decoder_title, ConnectionErrors.forStageFailure("video decoder", false).title)
        assertEquals(R.string.pcr_err_start_title, ConnectionErrors.forStageFailure("RTSP handshake", false).title)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ConnectionErrorsTest`
Expected: FAIL, unresolved reference `ConnectionErrors`.

- [ ] **Step 4: Implement `ConnectionErrors.kt`**

```kotlin
package com.pcremote.stream

import androidx.annotation.StringRes
import com.limelight.R
import com.limelight.nvstream.jni.MoonBridge

data class FriendlyError(@StringRes val title: Int, @StringRes val message: Int, val retryable: Boolean)

object ConnectionErrors {
    private val BLOCKED = FriendlyError(R.string.pcr_err_blocked_title, R.string.pcr_err_blocked_msg, true)

    fun forTermination(errorCode: Int, portsBlocked: Boolean): FriendlyError = when {
        portsBlocked -> BLOCKED
        errorCode == MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC || errorCode == MoonBridge.ML_ERROR_NO_VIDEO_FRAME ->
            FriendlyError(R.string.pcr_err_no_video_title, R.string.pcr_err_no_video_msg, true)
        errorCode == MoonBridge.ML_ERROR_PROTECTED_CONTENT ->
            FriendlyError(R.string.pcr_err_protected_title, R.string.pcr_err_protected_msg, false)
        else -> FriendlyError(R.string.pcr_err_lost_title, R.string.pcr_err_lost_msg, true)
    }

    fun forStageFailure(stage: String, portsBlocked: Boolean): FriendlyError = when {
        portsBlocked -> BLOCKED
        stage.contains("video", ignoreCase = true) ->
            FriendlyError(R.string.pcr_err_decoder_title, R.string.pcr_err_decoder_msg, true)
        else -> FriendlyError(R.string.pcr_err_start_title, R.string.pcr_err_start_msg, true)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ConnectionErrorsTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Wire into `Game.java`**

Add a helper method to `Game` (next to `stopConnection()`):

```java
    // Shows a friendly error with Retry (relaunch this stream) / Close.
    void showFriendlyError(com.pcremote.stream.FriendlyError err, String technicalDetail) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(err.getTitle())
                .setMessage(getString(err.getMessage()) + "\n\n(" + technicalDetail + ")")
                .setCancelable(false)
                .setNegativeButton(R.string.pcr_close, (d, w) -> finish());
        if (err.getRetryable()) {
            b.setPositiveButton(R.string.pcr_retry, (d, w) -> {
                startActivity(getIntent());
                finish();
            });
        }
        if (!isFinishing()) b.show();
    }
```

In `stageFailed(...)`, inside `if (!displayedFailureDialog) { ... }`, replace everything after `LimeLog.severe(stage + " failed: " + errorCode);` (the Toast, `dialogText` building and `Dialog.displayDialog(...)`) with:

```java
                    boolean blocked = portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0;
                    showFriendlyError(com.pcremote.stream.ConnectionErrors.INSTANCE.forStageFailure(stage, blocked),
                            stage + " error " + errorCode);
```

In `connectionTerminated(...)`, replace the whole `if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) { ... } else { finish(); }` block with:

```java
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        boolean blocked = portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0;
                        showFriendlyError(com.pcremote.stream.ConnectionErrors.INSTANCE.forTermination(errorCode, blocked),
                                "error " + errorCode);
                    }
                    else {
                        finish();
                    }
```

- [ ] **Step 7: Build and manual check**

Run: `./gradlew :app:installDebug`. Start a stream, then quit Sunshine on the PC (tray icon → Quit).
Expected: a "Connection lost" dialog with Retry/Close; Retry relaunches the stream once Sunshine is running again.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pcremote/stream/ConnectionErrors.kt app/src/test/java/com/pcremote/stream app/src/main/res/values/strings.xml app/src/main/java/com/limelight/Game.java
git commit -m "feat: plain-language connection errors with Retry"
```

---

### Task 4: Auto-reconnect

**Files:**
- Create: `app/src/main/java/com/pcremote/stream/ReconnectPolicy.kt`
- Modify: `app/src/main/java/com/limelight/Game.java` (`connectionTerminated`, `connectionStarted`, new constant)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/pcremote/stream/ReconnectPolicyTest.kt`

**Interfaces:**
- Consumes: `showFriendlyError(...)` and `ConnectionErrors` from Task 3.
- Produces: `object ReconnectPolicy { const val MAX_ATTEMPTS = 3; fun shouldRetry(errorCode: Int, portsBlocked: Boolean, attemptsSoFar: Int): Boolean; fun delayMs(attemptsSoFar: Int): Long }`; `Game.EXTRA_RECONNECT_ATTEMPT = "PcrReconnectAttempt"`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pcremote.stream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun `graceful termination is never retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_GRACEFUL_TERMINATION, false, 0))
    }

    @Test fun `protected content and blocked ports are not retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_PROTECTED_CONTENT, false, 0))
        assertFalse(ReconnectPolicy.shouldRetry(-1, true, 0))
    }

    @Test fun `drops retried up to three times`() {
        assertTrue(ReconnectPolicy.shouldRetry(-1, false, 0))
        assertTrue(ReconnectPolicy.shouldRetry(-1, false, 2))
        assertFalse(ReconnectPolicy.shouldRetry(-1, false, 3))
    }

    @Test fun `delay grows linearly`() {
        assertEquals(1000L, ReconnectPolicy.delayMs(0))
        assertEquals(3000L, ReconnectPolicy.delayMs(2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ReconnectPolicyTest`
Expected: FAIL, unresolved reference `ReconnectPolicy`.

- [ ] **Step 3: Implement `ReconnectPolicy.kt`**

```kotlin
package com.pcremote.stream

import com.limelight.nvstream.jni.MoonBridge

object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(errorCode: Int, portsBlocked: Boolean, attemptsSoFar: Int): Boolean =
        errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION &&
            !portsBlocked &&
            ConnectionErrors.forTermination(errorCode, false).retryable &&
            attemptsSoFar < MAX_ATTEMPTS

    fun delayMs(attemptsSoFar: Int): Long = 1000L * (attemptsSoFar + 1)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ReconnectPolicyTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Add string**

```xml
    <string name="pcr_reconnecting">Reconnecting… (%1$d/%2$d)</string>
```

- [ ] **Step 6: Wire into `Game.java`**

Add next to the other `EXTRA_` constants:

```java
    public static final String EXTRA_RECONNECT_ATTEMPT = "PcrReconnectAttempt";
```

In `connectionTerminated(...)`, inside `if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {` and **before** the `showFriendlyError(...)` line from Task 3, insert:

```java
                        int attempt = getIntent().getIntExtra(EXTRA_RECONNECT_ATTEMPT, 0);
                        if (com.pcremote.stream.ReconnectPolicy.INSTANCE.shouldRetry(errorCode, blocked, attempt)) {
                            notificationOverlayView.setText(getString(R.string.pcr_reconnecting,
                                    attempt + 1, com.pcremote.stream.ReconnectPolicy.MAX_ATTEMPTS));
                            notificationOverlayView.setVisibility(View.VISIBLE);
                            final Intent again = new Intent(getIntent()).putExtra(EXTRA_RECONNECT_ATTEMPT, attempt + 1);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (isFinishing()) return; // user backed out while waiting
                                startActivity(again);
                                finish();
                                overridePendingTransition(0, 0);
                            }, com.pcremote.stream.ReconnectPolicy.INSTANCE.delayMs(attempt));
                            return;
                        }
```

(`blocked` is the variable from Task 3, so move its declaration above this block. Import `android.os.Handler`/`android.os.Looper` if they aren't already imported.)

In `connectionStarted()`, inside its `runOnUiThread` body, add as the first line so that a later drop gets a fresh 3 tries:

```java
                getIntent().putExtra(EXTRA_RECONNECT_ATTEMPT, 0);
```

- [ ] **Step 7: Manual check**

Install, stream, then turn the phone's Wi-Fi off for ~2 s and back on.
Expected: "Reconnecting… (1/3)" on the stream screen, then the stream resumes. Wi-Fi off for 15 s → after 3 tries, the "Connection lost" dialog. Quitting the stream from the PC (Sunshine web UI → Applications → stop) → closes with no reconnect.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pcremote/stream/ReconnectPolicy.kt app/src/test/java/com/pcremote/stream/ReconnectPolicyTest.kt app/src/main/java/com/limelight/Game.java app/src/main/res/values/strings.xml
git commit -m "feat: auto-reconnect up to 3 times on dropped streams"
```

---

### Task 5: Pinch-to-zoom and pan

**Files:**
- Create: `app/src/main/java/com/pcremote/stream/ZoomPan.kt`
- Modify: `app/src/main/java/com/limelight/Game.java` (fields, `surfaceChanged`, finger branch of `handleMotionEvent` ~lines 1993–2125)
- Test: `app/src/test/java/com/pcremote/stream/ZoomPanTest.kt`

**Interfaces:**
- Produces: `class ZoomPan { val scale: Float; val tx: Float; val ty: Float; val isZoomed: Boolean; fun setContentSize(w: Float, h: Float); fun pinch(factor: Float, focusX: Float, focusY: Float); fun pan(dx: Float, dy: Float); fun toStreamX(x: Float): Float; fun toStreamY(y: Float): Float }`. Coordinates are relative to the stream view's unscaled top-left, with pivot (0,0).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pcremote.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPanTest {
    private fun zp() = ZoomPan().apply { setContentSize(1000f, 500f) }

    @Test fun `pinch keeps focus point fixed`() {
        val z = zp()
        z.pinch(2f, 500f, 250f)
        assertEquals(2f, z.scale, 0.001f)
        // stream point under the focus is unchanged
        assertEquals(500f, z.toStreamX(500f), 0.001f)
        assertEquals(250f, z.toStreamY(250f), 0.001f)
    }

    @Test fun `scale clamped between 1 and 4`() {
        val z = zp()
        z.pinch(10f, 0f, 0f); assertEquals(4f, z.scale, 0.001f)
        z.pinch(0.01f, 0f, 0f); assertEquals(1f, z.scale, 0.001f)
        assertFalse(z.isZoomed)
    }

    @Test fun `pan cannot move the picture off screen`() {
        val z = zp()
        z.pinch(2f, 0f, 0f)
        z.pan(5000f, 5000f)
        assertEquals(0f, z.tx, 0.001f); assertEquals(0f, z.ty, 0.001f)
        z.pan(-5000f, -5000f)
        assertEquals(-1000f, z.tx, 0.001f); assertEquals(-500f, z.ty, 0.001f)
    }

    @Test fun `setContentSize resets zoom`() {
        val z = zp()
        z.pinch(3f, 100f, 100f)
        assertTrue(z.isZoomed)
        z.setContentSize(500f, 1000f)
        assertEquals(1f, z.scale, 0.001f); assertEquals(0f, z.tx, 0.001f)
    }

    @Test fun `unzoomed mapping is identity`() {
        val z = zp()
        assertEquals(123f, z.toStreamX(123f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ZoomPanTest`
Expected: FAIL, unresolved reference `ZoomPan`.

- [ ] **Step 3: Implement `ZoomPan.kt`**

```kotlin
package com.pcremote.stream

class ZoomPan {
    var scale = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set
    private var w = 0f
    private var h = 0f

    val isZoomed get() = scale > 1.01f

    fun setContentSize(w: Float, h: Float) {
        this.w = w; this.h = h
        scale = 1f; tx = 0f; ty = 0f
    }

    fun pinch(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (scale * factor).coerceIn(1f, 4f)
        // keep the stream point under the fingers fixed on screen
        val sx = toStreamX(focusX); val sy = toStreamY(focusY)
        scale = newScale
        tx = focusX - sx * scale
        ty = focusY - sy * scale
        clamp()
    }

    fun pan(dx: Float, dy: Float) { tx += dx; ty += dy; clamp() }

    fun toStreamX(x: Float) = (x - tx) / scale
    fun toStreamY(y: Float) = (y - ty) / scale

    private fun clamp() {
        tx = tx.coerceIn(w - w * scale, 0f)
        ty = ty.coerceIn(h - h * scale, 0f)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ZoomPanTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Wire into `Game.java`**

Fields (next to `touchContextMap`):

```java
    private final com.pcremote.stream.ZoomPan zoomPan = new com.pcremote.stream.ZoomPan();
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean zoomGestureActive;
    private float lastPanX, lastPanY;

    private void applyZoom() {
        streamView.setPivotX(0); streamView.setPivotY(0);
        streamView.setScaleX(zoomPan.getScale()); streamView.setScaleY(zoomPan.getScale());
        streamView.setTranslationX(zoomPan.getTx()); streamView.setTranslationY(zoomPan.getTy());
    }
```

In `onCreate`, after `streamView` is found:

```java
        scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                        zoomGestureActive = true;
                        for (TouchContext c : touchContextMap) c.cancelTouch();
                        return true;
                    }
                    @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                        zoomPan.pinch(d.getScaleFactor(), d.getFocusX() - streamView.getLeft(), d.getFocusY() - streamView.getTop());
                        applyZoom();
                        return true;
                    }
                });
```

In `surfaceChanged(...)`, add at the top:

```java
        zoomPan.setContentSize(streamView.getWidth(), streamView.getHeight());
        applyZoom();
```

In `handleMotionEvent`, at the very start of the finger branch (`// This case is for fingers` → `else {`), right after the virtual-controller configuration check:

```java
                scaleDetector.onTouchEvent(event);
                if (zoomGestureActive) {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        zoomGestureActive = false;
                    }
                    return true;
                }
                // While zoomed, two-finger drag pans instead of scrolling.
                if (zoomPan.isZoomed() && event.getPointerCount() == 2) {
                    float cx = (event.getX(0) + event.getX(1)) / 2, cy = (event.getY(0) + event.getY(1)) / 2;
                    if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                        for (TouchContext c : touchContextMap) c.cancelTouch();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        zoomPan.pan(cx - lastPanX, cy - lastPanY);
                        applyZoom();
                    }
                    lastPanX = cx; lastPanY = cy;
                    return true;
                }
```

Replace the existing offset block:

```java
                if (view != streamView && !prefConfig.touchscreenTrackpad) {
                    xOffset = -streamView.getX();
                    yOffset = -streamView.getY();
                }
```

with (Android already inverse-transforms events delivered to the scaled `streamView` itself; only events from the parent view need mapping):

```java
                if (view != streamView && !prefConfig.touchscreenTrackpad) {
                    int ai = event.getActionIndex();
                    float rx = event.getX(ai) - streamView.getLeft(), ry = event.getY(ai) - streamView.getTop();
                    xOffset = zoomPan.toStreamX(rx) - event.getX(ai);
                    yOffset = zoomPan.toStreamY(ry) - event.getY(ai);
                }
```

- [ ] **Step 6: Manual check**

Install and stream. Pinch out on a small taskbar icon → it enlarges around your fingers; two-finger drag pans; pinch in → back to 1×, and two-finger drag scrolls the PC again. In direct-touch mode while zoomed, a tap lands exactly on the icon under your finger. Rotate the phone → zoom resets.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pcremote/stream/ZoomPan.kt app/src/test/java/com/pcremote/stream/ZoomPanTest.kt app/src/main/java/com/limelight/Game.java
git commit -m "feat: pinch-to-zoom and two-finger pan on the stream"
```

---

### Task 6: Shortcut and media-key sequences

**Files:**
- Create: `app/src/main/java/com/pcremote/stream/Shortcuts.kt`
- Test: `app/src/test/java/com/pcremote/stream/ShortcutsTest.kt`

**Interfaces:**
- Consumes: `KeyboardPacket.KEY_DOWN/KEY_UP/MODIFIER_*` (Java constants).
- Produces: `data class KeyEvt(val keyMap: Short, val direction: Byte, val modifiers: Byte)`; `enum class Shortcut { WIN, ALT_TAB, COPY, PASTE, ESC, PLAY_PAUSE, PREV, NEXT, VOL_DOWN, VOL_UP, MUTE; fun events(): List<KeyEvt> }`. `keyMap` uses Moonlight's encoding `(0x80 shl 8) or vk`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pcremote.stream

import com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN
import com.limelight.nvstream.input.KeyboardPacket.KEY_UP
import com.limelight.nvstream.input.KeyboardPacket.MODIFIER_CTRL
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutsTest {
    private fun km(vk: Int) = ((0x80 shl 8) or vk).toShort()

    @Test fun `single key is down then up`() {
        assertEquals(listOf(KeyEvt(km(0xB3), KEY_DOWN, 0), KeyEvt(km(0xB3), KEY_UP, 0)),
            Shortcut.PLAY_PAUSE.events())
    }

    @Test fun `ctrl c presses ctrl first, releases it last, carries modifier`() {
        assertEquals(listOf(
            KeyEvt(km(0x11), KEY_DOWN, MODIFIER_CTRL),
            KeyEvt(km(0x43), KEY_DOWN, MODIFIER_CTRL),
            KeyEvt(km(0x43), KEY_UP, MODIFIER_CTRL),
            KeyEvt(km(0x11), KEY_UP, 0),
        ), Shortcut.COPY.events())
    }

    @Test fun `every shortcut releases everything it pressed`() {
        for (s in Shortcut.entries) {
            val ev = s.events()
            assertEquals(s.name, ev.count { it.direction == KEY_DOWN }, ev.count { it.direction == KEY_UP })
            assertEquals(s.name, 0.toByte(), ev.last().modifiers)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ShortcutsTest`
Expected: FAIL, unresolved reference `Shortcut`.

- [ ] **Step 3: Implement `Shortcuts.kt`**

```kotlin
package com.pcremote.stream

import com.limelight.nvstream.input.KeyboardPacket

data class KeyEvt(val keyMap: Short, val direction: Byte, val modifiers: Byte)

private const val VK_CONTROL = 0x11
private const val VK_MENU = 0x12 // Alt
private const val VK_LWIN = 0x5B

private fun modifierBit(vk: Int): Int = when (vk) {
    VK_CONTROL -> KeyboardPacket.MODIFIER_CTRL.toInt()
    VK_MENU -> KeyboardPacket.MODIFIER_ALT.toInt()
    VK_LWIN -> KeyboardPacket.MODIFIER_META.toInt()
    else -> 0
}

/** Keys are pressed in order and released in reverse order. Windows virtual-key codes. */
enum class Shortcut(private vararg val vks: Int) {
    WIN(VK_LWIN), ALT_TAB(VK_MENU, 0x09), COPY(VK_CONTROL, 0x43), PASTE(VK_CONTROL, 0x56), ESC(0x1B),
    PLAY_PAUSE(0xB3), PREV(0xB1), NEXT(0xB0), VOL_DOWN(0xAE), VOL_UP(0xAF), MUTE(0xAD);

    fun events(): List<KeyEvt> {
        val out = ArrayList<KeyEvt>()
        var mods = 0
        for (vk in vks) {
            mods = mods or modifierBit(vk)
            out += KeyEvt(keyMap(vk), KeyboardPacket.KEY_DOWN, mods.toByte())
        }
        // Release in reverse. A modifier key's own release already reports it as not held.
        for (vk in vks.reversed()) {
            mods = mods and modifierBit(vk).inv()
            out += KeyEvt(keyMap(vk), KeyboardPacket.KEY_UP, mods.toByte())
        }
        return out
    }

    private fun keyMap(vk: Int) = ((0x80 shl 8) or vk).toShort()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.ShortcutsTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pcremote/stream/Shortcuts.kt app/src/test/java/com/pcremote/stream/ShortcutsTest.kt
git commit -m "feat: shortcut and media key sequences"
```

---

### Task 7: Stream toolbar, connection dot, mode switch, Lower quality

**Files:**
- Create: `app/src/main/java/com/pcremote/stream/StreamToolbar.kt`
- Create: `app/src/main/java/com/pcremote/stream/PoorConnectionWatch.kt`
- Modify: `app/src/main/java/com/limelight/Game.java` (touch-context init, `connectionStatusUpdate`, `onCreate`, `onPerfUpdate`)
- Test: `app/src/test/java/com/pcremote/stream/PoorConnectionWatchTest.kt`

**Interfaces:**
- Consumes: `Shortcut.events()` (Task 6), `QualityPrefs.current/apply`, `QualityPreset.stepDown` (Task 2), `NvConnection.sendKeyboardInput(short, byte, byte, byte)`, `Game.toggleKeyboard()`.
- Produces:
  - `class PoorConnectionWatch(thresholdMs: Long = 10_000) { fun update(poor: Boolean, nowMs: Long): Boolean }` (returns true once poor has lasted ≥ threshold)
  - `enum class LinkState { GOOD, POOR, RECONNECTING }`
  - `class ToolbarState { var link by mutableStateOf(LinkState.GOOD); var perfText by mutableStateOf(""); var showPerf by mutableStateOf(true); var trackpad by mutableStateOf(true); var offerLowerQuality by mutableStateOf(false) }`
  - `interface ToolbarActions { fun shortcut(s: Shortcut); fun keyboard(); fun setTrackpad(on: Boolean); fun lowerQuality(); fun disconnect() }`
  - `object StreamToolbarHost { @JvmStatic fun attach(parent: FrameLayout, state: ToolbarState, actions: ToolbarActions) }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pcremote.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoorConnectionWatchTest {
    @Test fun `fires only after sustained poor`() {
        val w = PoorConnectionWatch(10_000)
        assertFalse(w.update(true, 0))
        assertFalse(w.update(true, 9_999))
        assertTrue(w.update(true, 10_000))
    }

    @Test fun `good status resets the timer`() {
        val w = PoorConnectionWatch(10_000)
        w.update(true, 0)
        w.update(false, 5_000)
        assertFalse(w.update(true, 12_000))
        assertTrue(w.update(true, 22_000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.PoorConnectionWatchTest`
Expected: FAIL, unresolved reference `PoorConnectionWatch`.

- [ ] **Step 3: Implement `PoorConnectionWatch.kt`**

```kotlin
package com.pcremote.stream

class PoorConnectionWatch(private val thresholdMs: Long = 10_000) {
    private var poorSince = -1L

    fun update(poor: Boolean, nowMs: Long): Boolean {
        if (!poor) { poorSince = -1; return false }
        if (poorSince < 0) poorSince = nowMs
        return nowMs - poorSince >= thresholdMs
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.stream.PoorConnectionWatchTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Implement `StreamToolbar.kt`**

```kotlin
package com.pcremote.stream

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class LinkState { GOOD, POOR, RECONNECTING }

class ToolbarState {
    var link by mutableStateOf(LinkState.GOOD)
    var perfText by mutableStateOf("")
    var showPerf by mutableStateOf(true)
    var trackpad by mutableStateOf(true)
    var offerLowerQuality by mutableStateOf(false)
}

interface ToolbarActions {
    fun shortcut(s: Shortcut)
    fun keyboard()
    fun setTrackpad(on: Boolean)
    fun lowerQuality()
    fun disconnect()
}

object StreamToolbarHost {
    @JvmStatic
    fun attach(parent: FrameLayout, state: ToolbarState, actions: ToolbarActions) {
        parent.addView(ComposeView(parent.context).apply {
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { StreamToolbar(state, actions) } }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun StreamToolbar(state: ToolbarState, a: ToolbarActions) {
    var open by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        // Top-left: connection dot + live stats
        Row(Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val c = when (state.link) { LinkState.GOOD -> Color(0xFF34C759); LinkState.POOR -> Color(0xFFFFCC00); LinkState.RECONNECTING -> Color(0xFFFF3B30) }
            Box(Modifier.size(10.dp).background(c, CircleShape))
            if (state.showPerf && state.perfText.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(state.perfText, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
        if (state.offerLowerQuality) {
            AssistChip(onClick = a::lowerQuality, label = { Text("Lower quality for smoother stream") },
                modifier = Modifier.align(Alignment.TopCenter))
        }
        // Bottom-right: collapsible toolbar
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            AnimatedVisibility(open && media) {
                Bar {
                    Btn(Icons.Default.SkipPrevious, "Previous") { a.shortcut(Shortcut.PREV) }
                    Btn(Icons.Default.PlayArrow, "Play/Pause") { a.shortcut(Shortcut.PLAY_PAUSE) }
                    Btn(Icons.Default.SkipNext, "Next") { a.shortcut(Shortcut.NEXT) }
                    Btn(Icons.Default.VolumeDown, "Volume down") { a.shortcut(Shortcut.VOL_DOWN) }
                    Btn(Icons.Default.VolumeUp, "Volume up") { a.shortcut(Shortcut.VOL_UP) }
                    Btn(Icons.Default.VolumeOff, "Mute") { a.shortcut(Shortcut.MUTE) }
                }
            }
            AnimatedVisibility(open) {
                Bar {
                    Btn(Icons.Default.Keyboard, "Keyboard") { a.keyboard() }
                    Btn(Icons.Default.MusicNote, "Media keys") { media = !media }
                    Btn(Icons.Default.Window, "Windows key") { a.shortcut(Shortcut.WIN) }
                    Btn(Icons.Default.SwapHoriz, "Alt+Tab") { a.shortcut(Shortcut.ALT_TAB) }
                    Btn(Icons.Default.ContentCopy, "Copy") { a.shortcut(Shortcut.COPY) }
                    Btn(Icons.Default.ContentPaste, "Paste") { a.shortcut(Shortcut.PASTE) }
                    Btn(Icons.Default.Close, "Escape") { a.shortcut(Shortcut.ESC) }
                    Btn(if (state.trackpad) Icons.Default.Mouse else Icons.Default.TouchApp,
                        if (state.trackpad) "Trackpad mode (tap to switch to direct touch)" else "Direct touch (tap to switch to trackpad)") {
                        state.trackpad = !state.trackpad; a.setTrackpad(state.trackpad)
                    }
                    Btn(Icons.Default.Speed, "Show stats") { state.showPerf = !state.showPerf }
                    Btn(Icons.Default.PowerSettingsNew, "Disconnect") { a.disconnect() }
                }
            }
            SmallFloatingActionButton(onClick = { open = !open }, containerColor = Color(0xCC222222)) {
                Icon(if (open) Icons.Default.ExpandMore else Icons.Default.Menu, contentDescription = "Toolbar", tint = Color.White)
            }
        }
    }
}

@Composable private fun Bar(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.padding(bottom = 8.dp).background(Color(0xCC222222), RoundedCornerShape(24.dp)).padding(4.dp), content = content)

@Composable private fun Btn(icon: ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
```

- [ ] **Step 6: Wire into `Game.java`**

Make `Game` implement `com.pcremote.stream.ToolbarActions` (add to the `implements` list). Add fields:

```java
    private final com.pcremote.stream.ToolbarState toolbarState = new com.pcremote.stream.ToolbarState();
    private final com.pcremote.stream.PoorConnectionWatch poorWatch = new com.pcremote.stream.PoorConnectionWatch(10_000);
```

Extract the touch-context loop (≈ lines 495–505) into a method and call it where the loop was:

```java
    private void initTouchContexts(boolean trackpad) {
        for (int i = 0; i < touchContextMap.length; i++) {
            if (touchContextMap[i] != null) touchContextMap[i].cancelTouch();
            touchContextMap[i] = trackpad
                    ? new RelativeTouchContext(conn, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, streamView, prefConfig)
                    : new AbsoluteTouchContext(conn, i, streamView);
        }
    }
```

Call site: `initTouchContexts(prefConfig.touchscreenTrackpad);`

At the end of `onCreate` (after the connection setup), attach the toolbar and hide the old text overlay (its stats now go to the toolbar):

```java
        toolbarState.setTrackpad(prefConfig.touchscreenTrackpad);
        performanceOverlayView.setAlpha(0f);
        com.pcremote.stream.StreamToolbarHost.attach((FrameLayout) streamView.getParent(), toolbarState, this);
```

In `onPerfUpdate(final String text)`, inside its `runOnUiThread`, add `toolbarState.setPerfText(text);`.

In `connectionStatusUpdate(...)`, at the start of the `run()` body (before the `disableWarnings` early return):

```java
                boolean poor = connectionStatus == MoonBridge.CONN_STATUS_POOR;
                toolbarState.setLink(poor ? com.pcremote.stream.LinkState.POOR : com.pcremote.stream.LinkState.GOOD);
                if (poorWatch.update(poor, android.os.SystemClock.uptimeMillis())
                        && com.pcremote.quality.QualityPrefs.INSTANCE.current(Game.this) != com.pcremote.quality.QualityPreset.BATTERY) {
                    toolbarState.setOfferLowerQuality(true);
                }
```

In the reconnect branch from Task 4 (just before `postDelayed`), add `toolbarState.setLink(com.pcremote.stream.LinkState.RECONNECTING);`.

Implement the actions:

```java
    @Override public void shortcut(com.pcremote.stream.Shortcut s) {
        if (conn == null) return;
        for (com.pcremote.stream.KeyEvt e : s.events()) {
            conn.sendKeyboardInput(e.getKeyMap(), e.getDirection(), e.getModifiers(), (byte) 0);
        }
    }
    @Override public void keyboard() { toggleKeyboard(); }
    @Override public void setTrackpad(boolean on) {
        prefConfig.touchscreenTrackpad = on;
        initTouchContexts(on);
    }
    @Override public void lowerQuality() {
        com.pcremote.quality.QualityPreset next = com.pcremote.quality.QualityPrefs.INSTANCE.current(this).stepDown();
        com.pcremote.quality.QualityPrefs.INSTANCE.setAuto(this, false);
        com.pcremote.quality.QualityPrefs.INSTANCE.apply(this, next, next.getBitrateKbps());
        startActivity(new Intent(getIntent()).putExtra(EXTRA_RECONNECT_ATTEMPT, 0));
        finish();
    }
    @Override public void disconnect() { finish(); }
```

(`finish()` triggers upstream's `onStop` → `stopConnection()`, which ends gracefully, so no reconnect fires.)

- [ ] **Step 7: Manual check**

Install, stream. Tap ☰ → each button works on the PC: Win opens Start, Alt+Tab switches, copy/paste between apps, media keys control Spotify/YouTube volume and playback. The mode button flips between trackpad and direct touch without reconnecting. The stats readout shows FPS and latency next to a green dot. Throttle the router or walk to a far room → the dot turns yellow; after ~10 s "Lower quality…" appears; tapping it reconnects at the next preset down.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pcremote/stream app/src/test/java/com/pcremote/stream/PoorConnectionWatchTest.kt app/src/main/java/com/limelight/Game.java
git commit -m "feat: stream toolbar with shortcuts, media keys, mode switch, link status and lower-quality offer"
```

---

### Task 8: PC list, guided pairing, Wake-on-LAN, one-tap Desktop

**Files:**
- Create: `app/src/main/java/com/pcremote/pclist/PcListLogic.kt`
- Create: `app/src/main/java/com/pcremote/pclist/PcListActivity.kt`
- Create: `docs/setup-sunshine.md`
- Modify: `app/src/main/AndroidManifest.xml` (launcher moves from `.PcView` to `com.pcremote.pclist.PcListActivity`)
- Test: `app/src/test/java/com/pcremote/pclist/PcListLogicTest.kt`

**Interfaces:**
- Consumes: `ComputerManagerService.ComputerManagerBinder` (`startPolling(ComputerManagerListener)`, `stopPolling()`, `getUniqueId()`, `getComputer(uuid)`, `invalidateStateForComputer(uuid)`), `NvHTTP(...)`, `NvHTTP.getAppList()`, `NvHTTP.getPairState()`, `PairingManager.generatePinString()`, `pair(serverInfo, pin)`, `getPairedCert()`, `WakeOnLanSender.sendWolPacket(ComputerDetails)`, `ServerHelper.doStart(Activity, NvApp, ComputerDetails, binder)`, `PlatformBinding.getCryptoProvider(Context)`, `QualityPrefs`/`LinkAdvisor` (Task 2), existing `AddComputerManually` and `StreamSettings` activities.
- Produces:
  - `enum class PcAction { CONNECT, PAIR, WAKE, CHECKING }`
  - `object PcListLogic { fun actionFor(state: ComputerDetails.State, pair: PairingManager.PairState?, hasMac: Boolean): PcAction; fun pickDesktop(apps: List<NvApp>): NvApp?; @StringRes fun pairOutcomeMessage(state: PairingManager.PairState?, pcInGame: Boolean): Int? }`

- [ ] **Step 1: Add strings** (append to `strings.xml`)

```xml
    <string name="pcr_pc_list_title">Your PCs</string>
    <string name="pcr_searching">Looking for PCs on your Wi-Fi… Make sure Sunshine is running on your PC.</string>
    <string name="pcr_connect">Connect</string>
    <string name="pcr_pair">Pair</string>
    <string name="pcr_wake">Wake up</string>
    <string name="pcr_checking">Checking…</string>
    <string name="pcr_add_manually">Add PC by IP</string>
    <string name="pcr_setup_guide">Setup guide</string>
    <string name="pcr_settings">Settings</string>
    <string name="pcr_pair_title">Pair with %1$s</string>
    <string name="pcr_pair_steps">1. On your PC, open Sunshine (tray icon → Open Sunshine, or https://localhost:47990).\n2. Go to the PIN tab.\n3. Type this PIN and a name for this phone, then click Send.</string>
    <string name="pcr_pair_wrong_pin">That PIN didn\'t match. Try again; a new PIN will be shown.</string>
    <string name="pcr_pair_failed">Pairing failed. Make sure Sunshine is running and try again.</string>
    <string name="pcr_pair_ingame">Stop the current stream on the PC first, then pair.</string>
    <string name="pcr_pair_busy">Another device is pairing with this PC right now. Try again in a moment.</string>
    <string name="pcr_no_apps">Sunshine has no apps to stream. Add the \"Desktop\" app in Sunshine → Applications.</string>
    <string name="pcr_hint_24">You\'re on 2.4 GHz Wi-Fi. Switch your phone to your router\'s 5 GHz network for a much smoother stream.</string>
    <string name="pcr_hint_weak">Weak Wi-Fi signal. Move closer to the router, or connect your PC with a cable, for a smoother stream.</string>
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.pcremote.pclist

import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails.State
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.PairingManager.PairState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcListLogicTest {
    @Test fun `action per state`() {
        assertEquals(PcAction.CONNECT, PcListLogic.actionFor(State.ONLINE, PairState.PAIRED, true))
        assertEquals(PcAction.PAIR, PcListLogic.actionFor(State.ONLINE, PairState.NOT_PAIRED, true))
        assertEquals(PcAction.WAKE, PcListLogic.actionFor(State.OFFLINE, PairState.PAIRED, true))
        assertEquals(PcAction.CHECKING, PcListLogic.actionFor(State.OFFLINE, null, false))
        assertEquals(PcAction.CHECKING, PcListLogic.actionFor(State.UNKNOWN, null, true))
        assertEquals(PcAction.CHECKING, PcListLogic.actionFor(null, null, true)) // not polled yet
    }

    @Test fun `picks Desktop case-insensitively`() {
        val apps = listOf(NvApp("Steam Big Picture", 1, false), NvApp("desktop", 2, false))
        assertEquals(2, PcListLogic.pickDesktop(apps)!!.appId)
    }

    @Test fun `falls back to first app when no Desktop`() {
        assertEquals(7, PcListLogic.pickDesktop(listOf(NvApp("Steam", 7, false)))!!.appId)
        assertNull(PcListLogic.pickDesktop(emptyList()))
    }

    @Test fun `pair outcome messages`() {
        assertNull(PcListLogic.pairOutcomeMessage(PairState.PAIRED, false))
        assertEquals(R.string.pcr_pair_wrong_pin, PcListLogic.pairOutcomeMessage(PairState.PIN_WRONG, false))
        assertEquals(R.string.pcr_pair_ingame, PcListLogic.pairOutcomeMessage(PairState.FAILED, true))
        assertEquals(R.string.pcr_pair_failed, PcListLogic.pairOutcomeMessage(PairState.FAILED, false))
        assertEquals(R.string.pcr_pair_busy, PcListLogic.pairOutcomeMessage(PairState.ALREADY_IN_PROGRESS, false))
        assertEquals(R.string.pcr_pair_failed, PcListLogic.pairOutcomeMessage(null, false)) // PC dropped mid-pair
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.pclist.PcListLogicTest`
Expected: FAIL, unresolved reference `PcListLogic`.

- [ ] **Step 4: Implement `PcListLogic.kt`**

```kotlin
package com.pcremote.pclist

import androidx.annotation.StringRes
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.PairingManager.PairState

enum class PcAction { CONNECT, PAIR, WAKE, CHECKING }

object PcListLogic {
    fun actionFor(state: ComputerDetails.State?, pair: PairState?, hasMac: Boolean): PcAction = when (state) {
        ComputerDetails.State.ONLINE -> if (pair == PairState.PAIRED) PcAction.CONNECT else PcAction.PAIR
        ComputerDetails.State.OFFLINE -> if (hasMac) PcAction.WAKE else PcAction.CHECKING
        else -> PcAction.CHECKING
    }

    fun pickDesktop(apps: List<NvApp>): NvApp? =
        apps.firstOrNull { it.appName.equals("Desktop", ignoreCase = true) } ?: apps.firstOrNull()

    /** null = success (no message). A null state means the PC dropped mid-pair (exception). */
    @StringRes
    fun pairOutcomeMessage(state: PairState?, pcInGame: Boolean): Int? = when (state) {
        PairState.PAIRED -> null
        PairState.PIN_WRONG -> R.string.pcr_pair_wrong_pin
        PairState.ALREADY_IN_PROGRESS -> R.string.pcr_pair_busy
        PairState.FAILED -> if (pcInGame) R.string.pcr_pair_ingame else R.string.pcr_pair_failed
        else -> R.string.pcr_pair_failed
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.pcremote.pclist.PcListLogicTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Implement `PcListActivity.kt`**

```kotlin
package com.pcremote.pclist

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManually
import com.limelight.utils.ServerHelper
import com.pcremote.quality.LinkAdvisor
import com.pcremote.quality.QualityPrefs
import com.pcremote.quality.WifiHint
import com.pcremote.settings.SettingsActivity
import kotlin.concurrent.thread

class PcListActivity : ComponentActivity() {
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private val pcs = mutableStateMapOf<String, ComputerDetails>()
    private var pairingPin by mutableStateOf<Pair<String, String>?>(null) // (pc name, PIN)

    private val listener = ComputerManagerListener { d ->
        if (d.uuid != null) runOnUiThread { pcs[d.uuid] = ComputerDetails(d) }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val bd = b as ComputerManagerService.ComputerManagerBinder
            thread { bd.waitForReady(); binder = bd; runOnUiThread { bd.startPolling(listener) } }
        }
        override fun onServiceDisconnected(n: ComponentName) { binder = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, ComputerManagerService::class.java), conn, BIND_AUTO_CREATE)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    override fun onResume() { super.onResume(); binder?.startPolling(listener) }
    override fun onPause() { super.onPause(); binder?.stopPolling() }
    override fun onDestroy() { super.onDestroy(); unbindService(conn) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.pcr_pc_list_title)) }, actions = {
                IconButton({ startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/LizardByte/Sunshine/releases/latest"))) }) {
                    Icon(Icons.Default.HelpOutline, stringResource(R.string.pcr_setup_guide)) }
                IconButton({ startActivity(Intent(this@PcListActivity, SettingsActivity::class.java)) }) {
                    Icon(Icons.Default.Settings, stringResource(R.string.pcr_settings)) }
            })
        }, floatingActionButton = {
            ExtendedFloatingActionButton({ startActivity(Intent(this, AddComputerManually::class.java)) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.pcr_add_manually)) })
        }) { pad ->
            if (pcs.isEmpty()) {
                Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.pcr_searching))
                    }
                }
            } else LazyColumn(Modifier.padding(pad)) {
                items(pcs.values.sortedBy { it.name }, key = { it.uuid }) { PcRow(it) }
            }
        }
        pairingPin?.let { (name, pin) ->
            AlertDialog(onDismissRequest = {}, confirmButton = {},
                title = { Text(stringResource(R.string.pcr_pair_title, name)) },
                text = { Column {
                    Text(pin, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.pcr_pair_steps))
                } })
        }
    }

    @Composable
    private fun PcRow(pc: ComputerDetails) {
        val action = PcListLogic.actionFor(pc.state, pc.pairState, pc.macAddress != null)
        ListItem(
            headlineContent = { Text(pc.name ?: "PC") },
            supportingContent = { Text(pc.activeAddress?.address ?: "") },
            leadingContent = { Icon(Icons.Default.Computer, null) },
            trailingContent = {
                val label = when (action) {
                    PcAction.CONNECT -> R.string.pcr_connect; PcAction.PAIR -> R.string.pcr_pair
                    PcAction.WAKE -> R.string.pcr_wake; PcAction.CHECKING -> R.string.pcr_checking
                }
                Button({ onAction(pc, action) }, enabled = action != PcAction.CHECKING) { Text(stringResource(label)) }
            },
            modifier = Modifier.clickable(enabled = action != PcAction.CHECKING) { onAction(pc, action) },
        )
    }

    private fun onAction(pc: ComputerDetails, action: PcAction) {
        when (action) {
            PcAction.CONNECT -> connect(pc)
            PcAction.PAIR -> pair(pc)
            PcAction.WAKE -> thread {
                val ok = runCatching { WakeOnLanSender.sendWolPacket(pc) }.isSuccess
                toast(if (ok) R.string.wol_waking_msg else R.string.wol_fail)
            }
            PcAction.CHECKING -> Unit
        }
    }

    private fun http(pc: ComputerDetails) = NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort,
        binder!!.uniqueId, pc.serverCert, PlatformBinding.getCryptoProvider(this))

    private fun connect(pc: ComputerDetails) {
        val b = binder ?: return
        if (QualityPrefs.isAuto(this)) {
            val link = QualityPrefs.readLink(this)
            val preset = LinkAdvisor.pick(link)
            QualityPrefs.apply(this, preset, LinkAdvisor.bitrateFor(preset, link))
            LinkAdvisor.hint(link)?.takeIf { QualityPrefs.shouldShowHint(this, it) }?.let {
                toast(if (it == WifiHint.BAND_2_4_GHZ) R.string.pcr_hint_24 else R.string.pcr_hint_weak, long = true)
            }
        }
        thread {
            val app = runCatching { PcListLogic.pickDesktop(http(pc).appList) }.getOrNull()
            runOnUiThread {
                if (app == null) toast(R.string.pcr_no_apps, long = true)
                else ServerHelper.doStart(this, app, pc, b)
            }
        }
    }

    private fun pair(pc: ComputerDetails) {
        val b = binder ?: return
        thread {
            b.stopPolling()
            var state: PairingManager.PairState? = null
            try {
                val h = http(pc)
                if (h.pairState == PairingManager.PairState.PAIRED) state = PairingManager.PairState.PAIRED
                else {
                    val pin = PairingManager.generatePinString()
                    runOnUiThread { pairingPin = (pc.name ?: "PC") to pin }
                    val pm = h.pairingManager
                    state = pm.pair(h.getServerInfo(true), pin)
                    if (state == PairingManager.PairState.PAIRED) {
                        b.getComputer(pc.uuid).serverCert = pm.pairedCert
                        b.invalidateStateForComputer(pc.uuid)
                    }
                }
            } catch (e: Exception) { /* PC unreachable mid-pair: state stays null */ }
            val msg = PcListLogic.pairOutcomeMessage(state, pc.runningGameId != 0)
            runOnUiThread {
                pairingPin = null
                b.startPolling(listener)
                if (msg != null) toast(msg, long = true) else connect(b.getComputer(pc.uuid) ?: pc)
            }
        }
    }

    private fun toast(res: Int, long: Boolean = false) = runOnUiThread {
        Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
```

(Point the help icon at `docs/setup-sunshine.md` on the project's GitHub once the repo is published. Until then it opens Sunshine's releases page.)

- [ ] **Step 7: Make it the launcher** (in `AndroidManifest.xml`)

Remove the `<intent-filter>` containing `MAIN`/`LAUNCHER` from `.PcView` (leave PcView declared; upstream code still references it), and add inside `<application>`:

```xml
        <activity
            android:name="com.pcremote.pclist.PcListActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

- [ ] **Step 8: Write `docs/setup-sunshine.md`**

```markdown
# Setting up your PC

1. Download Sunshine for Windows from https://github.com/LizardByte/Sunshine/releases/latest (the `.exe` installer) and run it.
2. When the installer asks, allow it to add firewall rules.
3. Open https://localhost:47990 on the PC, create a username and password.
4. Make sure the PC and phone are on the same Wi-Fi (5 GHz is best; a cable to the PC is even better).
5. Open PC Remote on your phone. Your PC appears automatically; tap **Pair** and type the PIN shown on your phone into Sunshine's **PIN** tab.
6. Tap **Connect**. That's it.

**PC not showing up?** Tap **Add PC by IP** and enter the PC's IP (run `ipconfig` on the PC and look for *IPv4 Address*).
**Wake from sleep:** turn on *Wake on LAN* in your PC's BIOS and network adapter settings, then pair once while the PC is awake.
```

- [ ] **Step 9: Build** (`SettingsActivity` comes from Task 9; if you run this task first, temporarily comment out the settings `IconButton`)

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Manual check**

Fresh install, Sunshine running on the PC → the PC appears within seconds with **Pair**. Tap it → a big PIN with steps; enter it in Sunshine → the dialog closes and the desktop stream starts directly (no app grid). Back out → the button shows **Connect**. Put the PC to sleep → **Wake up** works once WoL is set up. Rename Sunshine's Desktop app → Connect still streams the first app.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/pcremote/pclist app/src/test/java/com/pcremote/pclist app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml docs/setup-sunshine.md
git commit -m "feat: Compose PC list with guided pairing, Wake-on-LAN and one-tap Desktop"
```

---

### Task 9: Settings screen with presets

**Files:**
- Create: `app/src/main/java/com/pcremote/settings/SettingsActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `QualityPreset`, `QualityPrefs.current/apply/isAuto/setAuto` (Task 2), upstream `com.limelight.preferences.StreamSettings` activity for advanced options.

- [ ] **Step 1: Add strings**

```xml
    <string name="pcr_auto_quality">Automatic quality</string>
    <string name="pcr_auto_quality_desc">Picks the best preset for your Wi-Fi each time you connect</string>
    <string name="pcr_preset_SMOOTH">Smooth: 720p · 60 FPS · lowest lag</string>
    <string name="pcr_preset_SHARP">Sharp: 1080p · 60 FPS · needs 5 GHz Wi-Fi</string>
    <string name="pcr_preset_BATTERY">Battery saver: 720p · 30 FPS</string>
    <string name="pcr_advanced">Advanced settings</string>
```

- [ ] **Step 2: Implement `SettingsActivity.kt`**

```kotlin
package com.pcremote.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.preferences.StreamSettings
import com.pcremote.quality.QualityPreset
import com.pcremote.quality.QualityPrefs

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }

    @Composable
    private fun Screen() {
        var auto by remember { mutableStateOf(QualityPrefs.isAuto(this)) }
        var preset by remember { mutableStateOf(QualityPrefs.current(this)) }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.pcr_settings), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pcr_auto_quality))
                    Text(stringResource(R.string.pcr_auto_quality_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(auto, { auto = it; QualityPrefs.setAuto(this@SettingsActivity, it) })
            }
            Spacer(Modifier.height(8.dp))
            for (p in listOf(QualityPreset.SMOOTH, QualityPreset.SHARP, QualityPreset.BATTERY)) {
                val label = when (p) {
                    QualityPreset.SMOOTH -> R.string.pcr_preset_SMOOTH
                    QualityPreset.SHARP -> R.string.pcr_preset_SHARP
                    QualityPreset.BATTERY -> R.string.pcr_preset_BATTERY
                }
                Row(Modifier.fillMaxWidth().selectable(selected = !auto && preset == p, enabled = !auto) {
                    preset = p; QualityPrefs.apply(this@SettingsActivity, p)
                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !auto && preset == p, onClick = null, enabled = !auto)
                    Spacer(Modifier.width(12.dp)); Text(stringResource(label))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.pcr_advanced), Modifier.fillMaxWidth()
                .clickable { startActivity(Intent(this@SettingsActivity, StreamSettings::class.java)) }
                .padding(vertical = 12.dp))
        }
    }
}
```

- [ ] **Step 3: Register in `AndroidManifest.xml`**

```xml
        <activity
            android:name="com.pcremote.settings.SettingsActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Material.NoActionBar" />
```

- [ ] **Step 4: Build and manual check**

Run: `./gradlew :app:installDebug`. Open Settings → Auto is on and the presets are greyed out. Turn Auto off, pick Sharp, connect → the stats show 1920x1080 at 60 FPS. "Advanced settings" opens Moonlight's full settings.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests in `com.pcremote.*` PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/pcremote/settings app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: settings screen with Smooth/Sharp/Battery presets and auto quality"
```

---

### Task 10: End-to-end device verification

**Files:** none (record results in `docs/superpowers/plans/2026-10-15-pc-remote-stage1-results.md`).

- [ ] **Step 1: Latency check.** PC on Ethernet, phone on 5 GHz, Sharp preset, perf overlay visible. Record "average network latency" and "average decoding time" over 60 s. Target: total under 40 ms.
- [ ] **Step 2: Smoothness check.** Drag a window in circles for 30 s; the overlay's frame-drop counters stay near 0.
- [ ] **Step 3: Review Focus walk-through.** Run each of the five Review Focus scenarios on the device and note pass/fail.
- [ ] **Step 4: Commit results**

```bash
git add docs/superpowers/plans/2026-10-15-pc-remote-stage1-results.md
git commit -m "docs: stage 1 device verification results"
```
