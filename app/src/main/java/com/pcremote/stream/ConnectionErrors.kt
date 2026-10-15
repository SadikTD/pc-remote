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
