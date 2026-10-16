package com.pcremote.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pcremote.R
import com.pcremote.agent.Msg
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics

enum class PowerAction(val wire: String, val needsConfirm: Boolean, val label: Int, val icon: ImageVector) {
    LOCK("lock", false, R.string.pcr_pw_lock, Icons.Rounded.Lock),
    SLEEP("sleep", true, R.string.pcr_pw_sleep, Icons.Rounded.Bedtime),
    RESTART("restart", true, R.string.pcr_pw_restart, Icons.Rounded.RestartAlt),
    SHUTDOWN("shutdown", true, R.string.pcr_pw_shutdown, Icons.Rounded.PowerSettingsNew),
    SIGNOUT("signout", true, R.string.pcr_pw_signout, Icons.AutoMirrored.Rounded.Logout);

    fun msg() = Msg.Power(wire)
}

/** Destructive actions need a second tap on the same action. */
class PowerConfirm {
    var pending: PowerAction? = null
        private set

    fun tap(a: PowerAction): Msg.Power? {
        if (!a.needsConfirm || pending == a) { pending = null; return a.msg() }
        pending = a
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSheet(pcName: String, send: (Msg) -> Unit, onDismiss: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val confirm = remember { PowerConfirm() }
    var pending by remember { mutableStateOf<PowerAction?>(null) }
    ModalBottomSheet(onDismiss, containerColor = c.surface) {
        Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp)) {
            Text(pcName, color = c.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            PowerAction.entries.forEach { a ->
                val armed = pending == a
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MidnightShapes.control)
                        .background(if (armed) c.bad.copy(alpha = 0.18f) else c.surfaceHigh)
                        .clickable {
                            haptics.click()
                            val m = confirm.tap(a)
                            pending = confirm.pending
                            if (m != null) { send(m); onDismiss() }
                        }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(a.icon, null, tint = if (a.needsConfirm) c.bad else c.accent)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(if (armed) R.string.pcr_pw_tap_again else a.label),
                        color = if (armed) c.bad else c.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
