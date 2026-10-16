package com.pcremote.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pcremote.R
import com.pcremote.agent.PairUri
import com.pcremote.ui.theme.LocalMidnight

/** For phones without a usable camera: paste the pairing link copied from the PC app. */
@Composable
fun ManualPairDialog(onPair: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalMidnight.current
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf(clip.getText()?.text?.takeIf { PairUri.parse(it.trim()) != null }.orEmpty()) }
    val valid = PairUri.parse(text.trim()) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.pcr_manual_title), color = c.text) },
        text = {
            Column {
                Text(stringResource(R.string.pcr_manual_desc), color = c.textDim)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("pcremote://pair?…") }, isError = text.isNotBlank() && !valid)
            }
        },
        confirmButton = { TextButton(onClick = { onPair(text.trim()) }, enabled = valid) { Text(stringResource(R.string.pcr_pair)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pcr_s2_back)) } },
    )
}
