package com.pcremote.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.preferences.StreamSettings
import com.pcremote.quality.QualityPreset
import com.pcremote.quality.QualityPrefs

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }

    @Composable
    private fun Screen() {
        var auto by remember { mutableStateOf(QualityPrefs.isAuto(this)) }
        var preset by remember { mutableStateOf(QualityPrefs.current(this)) }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Text(stringResource(R.string.pcr_settings), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pcr_auto_quality))
                    Text(stringResource(R.string.pcr_auto_quality_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(auto, { auto = it; QualityPrefs.setAuto(this@SettingsActivity, it) })
            }
            Spacer(Modifier.height(8.dp))
            for (p in listOf(QualityPreset.SMOOTH, QualityPreset.SHARP, QualityPreset.BATTERY)) {
                val label = when (p) {
                    QualityPreset.SMOOTH -> R.string.pcr_preset_SMOOTH
                    QualityPreset.SHARP -> R.string.pcr_preset_SHARP
                    QualityPreset.BATTERY -> R.string.pcr_preset_BATTERY
                }
                Row(Modifier.fillMaxWidth().selectable(selected = !auto && preset == p, enabled = !auto) {
                    preset = p; QualityPrefs.apply(this@SettingsActivity, p)
                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !auto && preset == p, onClick = null, enabled = !auto)
                    Spacer(Modifier.width(12.dp)); Text(stringResource(label))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.pcr_advanced), Modifier.fillMaxWidth()
                .clickable { startActivity(Intent(this@SettingsActivity, StreamSettings::class.java)) }
                .padding(vertical = 12.dp))
        }
    }
}
