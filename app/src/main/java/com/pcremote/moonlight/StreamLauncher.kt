package com.pcremote.moonlight

import android.app.Activity
import android.widget.Toast
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.ServerHelper
import com.pcremote.pclist.PcListLogic
import com.pcremote.quality.LinkAdvisor
import com.pcremote.quality.QualityPrefs
import com.pcremote.quality.WifiHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Screen mode: applies auto quality, finds Sunshine's Desktop app and starts the stream. */
suspend fun launchStream(activity: Activity, computers: Computers, uuid: String) {
    val b = computers.binder.value ?: return
    val pc = b.getComputer(uuid) ?: return
    if (QualityPrefs.isAuto(activity)) {
        val link = QualityPrefs.readLink(activity)
        val preset = LinkAdvisor.pick(link)
        QualityPrefs.apply(activity, preset, LinkAdvisor.bitrateFor(preset, link))
        LinkAdvisor.hint(link)?.takeIf { QualityPrefs.shouldShowHint(activity, it) }?.let {
            Toast.makeText(activity, if (it == WifiHint.BAND_2_4_GHZ) R.string.pcr_hint_24 else R.string.pcr_hint_weak, Toast.LENGTH_LONG).show()
        }
    }
    val apps = withContext(Dispatchers.IO) {
        try {
            NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort, b.uniqueId, pc.serverCert,
                PlatformBinding.getCryptoProvider(activity)).appList
        } catch (e: Exception) { null }
    }
    val problem = PcListLogic.connectProblem(apps)
    if (problem != null) Toast.makeText(activity, problem, Toast.LENGTH_LONG).show()
    else ServerHelper.doStart(activity, PcListLogic.pickDesktop(apps!!)!!, pc, b)
}
