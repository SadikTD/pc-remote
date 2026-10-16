package com.pcremote.modes

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pcremote.R
import com.pcremote.agent.Msg
import com.pcremote.agent.PinVault
import com.pcremote.ui.components.GhostButton
import com.pcremote.ui.components.PrimaryButton
import com.pcremote.ui.theme.LocalMidnight
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * "Unlock with fingerprint": the first time it asks for the PC's Windows PIN or password and keeps
 * it behind the phone's fingerprint; after that one touch unlocks the PC. Returns the trigger.
 * Phones without a fingerprint sensor type the PIN each time (nothing is stored).
 */
@Composable
fun rememberUnlocker(pcId: String, send: (Msg) -> Boolean, incoming: Flow<Msg>?): () -> Unit {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val title = stringResource(R.string.pcr_unlock_prompt)
    LaunchedEffect(incoming) {
        incoming?.collect { m ->
            if (m is Msg.UnlockResult) Toast.makeText(ctx, if (m.ok) R.string.pcr_unlock_done else R.string.pcr_unlock_failed, Toast.LENGTH_LONG).show()
        }
    }
    if (asking) PinDialog(
        canRemember = PinVault.available(ctx),
        onDismiss = { asking = false },
        onSubmit = { pin ->
            asking = false
            scope.launch {
                if (activity != null && PinVault.available(ctx)) PinVault.save(activity, pcId, pin, title)
                send(Msg.Unlock(pin))
            }
        },
    )
    return remember(pcId) {
        {
            if (activity != null && PinVault.has(ctx, pcId) && PinVault.available(ctx)) {
                scope.launch {
                    val pin = PinVault.reveal(activity, pcId, title)
                    if (pin != null) send(Msg.Unlock(pin)) else if (!PinVault.has(ctx, pcId)) asking = true
                }
            } else asking = true
        }
    }
}

@Composable
private fun PinDialog(canRemember: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val c = LocalMidnight.current
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        icon = { Icon(Icons.Rounded.Fingerprint, null, tint = c.accent) },
        title = { Text(stringResource(R.string.pcr_unlock_setup_title), color = c.text) },
        text = {
            Column {
                Text(stringResource(if (canRemember) R.string.pcr_unlock_setup_body else R.string.pcr_unlock_setup_body_nofp), color = c.textDim)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    pin, { pin = it }, singleLine = true, label = { Text(stringResource(R.string.pcr_unlock_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.pcr_unlock), { if (pin.isNotEmpty()) onSubmit(pin) }, enabled = pin.isNotEmpty()) },
        dismissButton = { GhostButton(stringResource(R.string.pcr_cancel), onDismiss) },
    )
}
