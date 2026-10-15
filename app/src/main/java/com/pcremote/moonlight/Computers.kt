package com.pcremote.moonlight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Binds Moonlight's ComputerManagerService and exposes its computers as a flow. */
class Computers(private val ctx: Context) {
    private val _binder = MutableStateFlow<ComputerManagerService.ComputerManagerBinder?>(null)
    val binder: StateFlow<ComputerManagerService.ComputerManagerBinder?> = _binder
    private val _pcs = MutableStateFlow<Map<String, ComputerDetails>>(emptyMap())
    val pcs: StateFlow<Map<String, ComputerDetails>> = _pcs
    private var polling = false

    private val listener = ComputerManagerListener { d ->
        val uuid = d.uuid ?: return@ComputerManagerListener
        _pcs.value = _pcs.value + (uuid to ComputerDetails(d))
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val bd = b as ComputerManagerService.ComputerManagerBinder
            Thread { bd.waitForReady(); _binder.value = bd; if (polling) bd.startPolling(listener) }.start()
        }
        override fun onServiceDisconnected(n: ComponentName) { _binder.value = null }
    }

    fun bind() { ctx.bindService(Intent(ctx, ComputerManagerService::class.java), conn, Context.BIND_AUTO_CREATE) }
    fun unbind() { stopPolling(); runCatching { ctx.unbindService(conn) } }
    fun startPolling() { polling = true; _binder.value?.startPolling(listener) }
    fun stopPolling() { polling = false; _binder.value?.stopPolling() }
    fun forget(uuid: String) {
        _binder.value?.getComputer(uuid)?.let { _binder.value?.removeComputer(it) }
        _pcs.value = _pcs.value - uuid
    }

    /** Adds the PC at [host] to Moonlight (if needed) and pairs with [pin]; returns its uuid or null. */
    suspend fun pair(host: String, pin: String): String? = withContext(Dispatchers.IO) {
        val b = binder.filterNotNull().first()
        val details = ComputerDetails().apply { manualAddress = ComputerDetails.AddressTuple(host, NvHTTP.DEFAULT_HTTP_PORT) }
        if (!b.addComputerBlocking(details)) return@withContext null
        val pc = b.getComputer(details.uuid) ?: details
        val http = NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort, b.uniqueId, pc.serverCert,
            PlatformBinding.getCryptoProvider(ctx))
        if (http.pairState == PairingManager.PairState.PAIRED) return@withContext pc.uuid
        val pm = http.pairingManager
        if (pm.pair(http.getServerInfo(true), pin) != PairingManager.PairState.PAIRED) return@withContext null
        b.getComputer(pc.uuid)?.serverCert = pm.pairedCert
        b.invalidateStateForComputer(pc.uuid)
        pc.uuid
    }
}
