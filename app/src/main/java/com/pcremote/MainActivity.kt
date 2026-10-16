package com.pcremote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.limelight.R
import com.limelight.nvstream.wol.WakeOnLanSender
import com.pcremote.agent.agentPair
import com.pcremote.home.HomeActions
import com.pcremote.home.HomeController
import com.pcremote.home.HomeScreen
import com.pcremote.home.PrimaryAction
import com.pcremote.moonlight.Computers
import com.pcremote.moonlight.launchStream
import com.pcremote.nav.Mode
import com.pcremote.nav.Routes
import com.pcremote.onboarding.OnboardingScreen
import com.pcremote.onboarding.PairingDeps
import com.pcremote.onboarding.PairingModel
import com.pcremote.onboarding.PairingOverlay
import com.pcremote.onboarding.scanQr
import com.pcremote.pclist.PcListActivity
import com.pcremote.ui.theme.MidnightTheme
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

const val PREF_ONBOARDED = "pcremote_onboarded"
const val SETUP_GUIDE_URL = "https://github.com/SadikTD/pc-remote/blob/main/docs/setup-sunshine.md"

/** Modes with a screen in this build; Track C adds the rest. */
val IMPLEMENTED_MODES = setOf(Mode.SCREEN, Mode.REMOTE, Mode.MEDIA, Mode.SLIDES, Mode.PAD, Mode.FILES)

class MainActivity : ComponentActivity() {
    private lateinit var computers: Computers
    private lateinit var home: HomeController
    private lateinit var pairing: PairingModel

    /** pcremote://pair… links (camera-app scans) start pairing straight away. */
    private fun handlePairLink(intent: Intent?) {
        val link = intent?.dataString?.takeIf { it.startsWith("pcremote://pair") } ?: return
        intent.data = null
        lifecycleScope.launch { pairing.onScanned(link) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    @Suppress("DEPRECATION")
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = com.pcremote.settings.SettingsModel.get(this)
        val darkBars = settings.theme != com.pcremote.settings.ThemeChoice.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        computers = Computers(applicationContext).also { it.bind() }
        home = HomeController(applicationContext, lifecycleScope, computers)
        pairing = PairingModel(PairingDeps(
            agentPair = { uri, device, pin -> agentPair(lifecycleScope, uri, device, pin) },
            moonlightPair = { host, pin -> computers.pair(host, pin) },
            save = { home.store.upsert(it); home.refreshStore() },
            device = Build.MODEL,
        ))
        handlePairLink(intent)
        val legacyOrPaired = home.store.all().isNotEmpty() ||
            com.limelight.computers.ComputerDatabaseManager(this).run { try { getAllComputers().isNotEmpty() } finally { close() } }
        val start = Routes.start(if (legacyOrPaired) 1 else 0, prefs.getBoolean(PREF_ONBOARDED, false))

        setContent {
            var themeTick by remember { mutableIntStateOf(0) }
            val theme = remember(themeTick) { settings.theme }
            val dark = when (theme) {
                com.pcremote.settings.ThemeChoice.LIGHT -> false
                com.pcremote.settings.ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                else -> true
            }
            MidnightTheme(dark = dark, dynamic = remember(themeTick) { settings.dynamicColor }) {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                fun finishOnboarding() {
                    prefs.edit().putBoolean(PREF_ONBOARDED, true).apply()
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
                NavHost(nav, start,
                    enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
                ) {
                    composable(Routes.ONBOARDING) {
                        OnboardingScreen(pairing,
                            onDone = { pairing.reset(); finishOnboarding() },
                            onManual = { startActivity(Intent(this@MainActivity, PcListActivity::class.java)) },
                            onSkip = ::finishOnboarding)
                    }
                    composable(Routes.modePattern(Mode.SCREEN)) { e ->
                        com.pcremote.stream2.StreamScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.modePattern(Mode.REMOTE)) { e ->
                        com.pcremote.modes.RemoteScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.modePattern(Mode.MEDIA)) { e ->
                        com.pcremote.modes.MediaScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.modePattern(Mode.SLIDES)) { e ->
                        com.pcremote.modes.SlidesScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.modePattern(Mode.PAD)) { e ->
                        com.pcremote.modes.PadScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.modePattern(Mode.FILES)) { e ->
                        com.pcremote.modes.FilesScreen(modeId(e), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        com.pcremote.settings.SettingsScreen(settings, onBack = { nav.popBackStack() },
                            onThemeChanged = { themeTick++; if (settings.theme == com.pcremote.settings.ThemeChoice.LIGHT) recreate() })
                    }
                    composable(Routes.HOME) {
                        LifecycleResumeEffect(Unit) { home.start(); onPauseOrDispose { home.stop() } }
                        val cards by home.cards.collectAsState()
                        val thumbs by home.thumbs.collectAsState()
                        val pairState by pairing.state.collectAsState()
                        LaunchedEffect(pairState) {
                            if (pairState is com.pcremote.onboarding.PairingState.Done) { pairing.reset(); home.stop(); home.start() }
                        }
                        HomeScreen(cards, thumbs, IMPLEMENTED_MODES, HomeActions(
                            open = { card, mode ->
                                // v1 hosts (Moonlight/Sunshine) only for legacy cards; our engine otherwise
                                if (mode == Mode.SCREEN && card.legacy) openMode(card.moonlightUuid, mode) else nav.navigate(Routes.mode(card.id, mode))
                            },
                            primary = { card ->
                                when (card.primary) {
                                    PrimaryAction.OPEN -> {
                                        val m = card.modes.firstOrNull { it in IMPLEMENTED_MODES }
                                        when {
                                            m == null -> manualPairing()
                                            m == Mode.SCREEN && card.legacy -> openMode(card.moonlightUuid, m)
                                            else -> nav.navigate(Routes.mode(card.id, m))
                                        }
                                    }
                                    PrimaryAction.WAKE -> wake(home.wolTarget(card))
                                    // A PC found on the network but not paired yet: pair by scanning its code
                                    PrimaryAction.PAIR -> scanQr(this@MainActivity, onUnavailable = ::manualPairing) { raw -> scope.launch { pairing.onScanned(raw) } }
                                    PrimaryAction.OFFLINE_HELP -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_GUIDE_URL)))
                                    PrimaryAction.CHECKING -> Unit
                                }
                            },
                            addPc = { scanQr(this@MainActivity, onUnavailable = ::manualPairing) { raw -> scope.launch { pairing.onScanned(raw) } } },
                            settings = { nav.navigate(Routes.SETTINGS) },
                            refresh = { home.stop(); home.start() },
                            rename = { card, name -> home.rename(card.id, name) },
                            remove = { card -> home.remove(card) },
                            send = { card, m -> home.connection(card.id)?.send(m) },
                        ))
                        PairingOverlay(pairState,
                            onRetry = { pairing.reset(); scanQr(this@MainActivity, onUnavailable = ::manualPairing) { raw -> scope.launch { pairing.onScanned(raw) } } },
                            onDismiss = pairing::reset)
                    }
                }
            }
        }
    }

    private fun modeId(e: androidx.navigation.NavBackStackEntry) =
        java.net.URLDecoder.decode(e.arguments?.getString(Routes.ARG_ID).orEmpty(), "UTF-8")

    private fun manualPairing() = startActivity(Intent(this, PcListActivity::class.java))

    private fun openMode(moonlightUuid: String?, mode: Mode) {
        when (mode) {
            Mode.SCREEN -> moonlightUuid?.let { lifecycleScope.launch { launchStream(this@MainActivity, computers, it) } }
            else -> Unit // Track C
        }
    }

    private fun wake(target: com.limelight.nvstream.http.ComputerDetails?) {
        if (target == null) { Toast.makeText(this, R.string.wol_no_mac, Toast.LENGTH_SHORT).show(); return }
        thread {
            val ok = runCatching { WakeOnLanSender.sendWolPacket(target) }.isSuccess
            runOnUiThread { Toast.makeText(this, if (ok) R.string.wol_waking_msg else R.string.wol_fail, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val up = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> true
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> null
        }
        if (up != null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && com.pcremote.modes.VolumeKeys.onVolumeKey(up, event.repeatCount > 0)) return true
            if (event.action == android.view.KeyEvent.ACTION_UP && com.pcremote.modes.VolumeKeys.active) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        computers.unbind()
    }
}
