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
