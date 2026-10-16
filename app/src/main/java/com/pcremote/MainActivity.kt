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
import com.pcremote.R
import com.pcremote.agent.agentPair
import com.pcremote.home.HomeActions
import com.pcremote.home.HomeController
import com.pcremote.home.HomeScreen
import com.pcremote.home.PrimaryAction
import com.pcremote.nav.Mode
import com.pcremote.nav.Routes
import com.pcremote.onboarding.OnboardingScreen
import com.pcremote.onboarding.PairingDeps
import com.pcremote.onboarding.PairingModel
import com.pcremote.onboarding.PairingOverlay
import com.pcremote.onboarding.scanQr
import com.pcremote.ui.theme.MidnightTheme
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

const val PREF_ONBOARDED = "pcremote_onboarded"
const val SETUP_GUIDE_URL = "https://github.com/SadikTD/pc-remote#readme"

/** Modes with a screen in this build. */
val IMPLEMENTED_MODES = setOf(Mode.SCREEN, Mode.REMOTE, Mode.MEDIA, Mode.SLIDES, Mode.PAD, Mode.FILES)

class MainActivity : ComponentActivity() {
    private lateinit var home: HomeController
    /** The paste-a-link dialog (phones without a usable camera). */
    private var manualOpen by mutableStateOf(false)
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
        home = HomeController(applicationContext, lifecycleScope)
        pairing = PairingModel(PairingDeps(
            agentPair = { uri, device, pin -> agentPair(lifecycleScope, uri, device, pin) },
            save = { home.store.upsert(it); home.refreshStore() },
            device = Build.MODEL,
        ))
        handlePairLink(intent)
        com.pcremote.update.Updater.schedule(this)
        // transfer progress and update prompts are notifications
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val start = Routes.start(if (home.store.all().isNotEmpty()) 1 else 0, prefs.getBoolean(PREF_ONBOARDED, false))

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
                if (manualOpen) com.pcremote.onboarding.ManualPairDialog(
                    onPair = { link -> manualOpen = false; scope.launch { pairing.onScanned(link) } },
                    onDismiss = { manualOpen = false })
                NavHost(nav, start,
                    enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
                ) {
                    composable(Routes.ONBOARDING) {
                        OnboardingScreen(pairing,
                            onDone = { pairing.reset(); finishOnboarding() },
                            onManual = ::manualPairing,
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
                            open = { card, mode -> nav.navigate(Routes.mode(card.id, mode)) },
                            primary = { card ->
                                when (card.primary) {
                                    PrimaryAction.OPEN -> {
                                        card.modes.firstOrNull { it in IMPLEMENTED_MODES }?.let { nav.navigate(Routes.mode(card.id, it)) }
                                    }
                                    PrimaryAction.WAKE -> wake(card)
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
                            connection = { card -> home.connection(card.id) },
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

    private fun manualPairing() { manualOpen = true }

    private fun wake(card: com.pcremote.home.PcCardUi) {
        thread {
            val ok = home.wake(card)
            val msg = when (ok) {
                null -> R.string.pcr_wol_no_mac
                true -> if (com.pcremote.home.HomeAssistant.get(this).configured) R.string.pcr_wol_sent_ha else R.string.pcr_wol_sent
                false -> R.string.pcr_wol_failed
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
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

}
