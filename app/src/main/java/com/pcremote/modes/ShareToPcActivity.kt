package com.pcremote.modes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.limelight.R
import com.pcremote.agent.PcStore
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.MidnightTheme

/** "Send to PC" in Android's share sheet. */
class ShareToPcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val pcs = PcStore.get(this).all()
        setContent {
            MidnightTheme(dark = true) {
                var chosen by remember { mutableStateOf(pcs.singleOrNull()?.id) }
                val c = LocalMidnight.current
                when {
                    pcs.isEmpty() -> Box(Modifier.fillMaxSize().background(c.bg).padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.pcr_share_no_pc), color = c.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    chosen == null -> Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(R.string.pcr_share_choose), color = c.text, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        pcs.forEach { pc ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MidnightShapes.control).background(c.surface)
                                .clickable { chosen = pc.id }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Computer, null, tint = c.accent)
                                Spacer(Modifier.width(14.dp))
                                Text(pc.name, color = c.text, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    else -> FilesScreen(chosen!!, onBack = ::finish, showPower = false, initial = uris)
                }
            }
        }
    }
}
