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
import androidx.compose.foundation.combinedClickable
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
import com.limelight.utils.UiHelper
import com.pcremote.quality.LinkAdvisor
import com.pcremote.quality.QualityPrefs
import com.pcremote.quality.WifiHint
import com.pcremote.settings.SettingsActivity
import kotlin.concurrent.thread

class PcListActivity : ComponentActivity() {
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private val pcs = mutableStateMapOf<String, ComputerDetails>()
    private var pairingPin by mutableStateOf<Pair<String, String>?>(null) // (pc name, PIN)
    private var pairing = false
    private var pairGen = 0 // bumped on Cancel so the abandoned pairing thread's result is ignored

    private val listener = ComputerManagerListener { d ->
        if (d.uuid != null) runOnUiThread { pcs[d.uuid] = ComputerDetails(d) }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val bd = b as ComputerManagerService.ComputerManagerBinder
            thread { bd.waitForReady(); runOnUiThread { binder = bd; bd.startPolling(listener) } }
        }
        override fun onServiceDisconnected(n: ComponentName) { binder = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, ComputerManagerService::class.java), conn, BIND_AUTO_CREATE)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    override fun onResume() { super.onResume(); if (!pairing) binder?.startPolling(listener) }
    override fun onPause() { super.onPause(); binder?.stopPolling() }
    override fun onDestroy() { super.onDestroy(); unbindService(conn) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.pcr_pc_list_title)) }, actions = {
                IconButton({ openSetupGuide() }) {
                    Icon(Icons.Default.HelpOutline, stringResource(R.string.pcr_setup_guide)) }
                IconButton({ startActivity(Intent(this@PcListActivity, SettingsActivity::class.java)) }) {
                    Icon(Icons.Default.Settings, stringResource(R.string.pcr_settings)) }
            })
        }, floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startActivity(Intent(this, AddComputerManually::class.java)) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.pcr_add_manually)) })
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
                dismissButton = { TextButton({ cancelPairing() }) { Text(stringResource(android.R.string.cancel)) } },
                title = { Text(stringResource(R.string.pcr_pair_title, name)) },
                text = { Column {
                    Text(pin, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.pcr_pair_steps))
                } })
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun PcRow(pc: ComputerDetails) {
        val action = PcListLogic.actionFor(pc.state, pc.pairState, pc.macAddress != null)
        var menu by remember { mutableStateOf(false) }
        Box { ListItem(
            headlineContent = { Text(pc.name ?: "PC") },
            supportingContent = { Text(pc.activeAddress?.address ?: "") },
            leadingContent = { Icon(Icons.Default.Computer, null) },
            trailingContent = {
                val label = when (action) {
                    PcAction.CONNECT -> R.string.pcr_connect
                    PcAction.PAIR -> R.string.pcr_pair
                    PcAction.WAKE -> R.string.pcr_wake
                    PcAction.OFFLINE -> R.string.pcr_offline
                    PcAction.CHECKING -> R.string.pcr_checking
                }
                Button({ onAction(pc, action) }, enabled = action != PcAction.CHECKING) { Text(stringResource(label)) }
            },
            modifier = Modifier.combinedClickable(onLongClick = { menu = true }) {
                if (action != PcAction.CHECKING) onAction(pc, action)
            },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.pcr_remove_pc)) }, onClick = {
                menu = false
                UiHelper.displayDeletePcConfirmationDialog(this@PcListActivity, pc, { removePc(pc) }, null)
            })
        } }
    }

    // Forgets the PC and its pairing on this phone; it reappears unpaired if discovered again.
    private fun removePc(pc: ComputerDetails) {
        val b = binder ?: return
        b.removeComputer(pc)
        pcs.remove(pc.uuid)
    }

    private fun onAction(pc: ComputerDetails, action: PcAction) {
        when (action) {
            PcAction.CONNECT -> connect(pc)
            PcAction.PAIR -> pair(pc)
            PcAction.WAKE -> thread {
                val ok = runCatching { WakeOnLanSender.sendWolPacket(pc) }.isSuccess
                toast(if (ok) R.string.wol_waking_msg else R.string.wol_fail)
            }
            PcAction.OFFLINE -> openSetupGuide()
            PcAction.CHECKING -> Unit
        }
    }

    private fun openSetupGuide() =
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SadikTD/pc-remote/blob/main/docs/setup-sunshine.md")))

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
            val apps = try { http(pc).appList } catch (e: Exception) { null }
            runOnUiThread {
                val problem = PcListLogic.connectProblem(apps)
                if (problem != null) toast(problem, long = true)
                else ServerHelper.doStart(this, PcListLogic.pickDesktop(apps!!)!!, pc, b)
            }
        }
    }

    private fun pair(pc: ComputerDetails) {
        if (pairing) return
        val b = binder ?: return
        pairing = true
        val gen = ++pairGen
        b.stopPolling()
        thread {
            var state: PairingManager.PairState? = null
            try {
                val h = http(pc)
                if (h.pairState == PairingManager.PairState.PAIRED) state = PairingManager.PairState.PAIRED
                else {
                    val pin = PairingManager.generatePinString()
                    runOnUiThread { if (gen == pairGen) pairingPin = (pc.name ?: "PC") to pin }
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
                if (gen != pairGen) return@runOnUiThread
                endPairing()
                if (msg != null) toast(msg, long = true) else connect(b.getComputer(pc.uuid) ?: pc)
            }
        }
    }

    private fun cancelPairing() { pairGen++; endPairing() }

    private fun endPairing() {
        pairing = false
        pairingPin = null
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) binder?.startPolling(listener)
    }

    private fun toast(res: Int, long: Boolean = false) = runOnUiThread {
        Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
