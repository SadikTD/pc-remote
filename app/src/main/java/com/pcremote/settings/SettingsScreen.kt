package com.pcremote.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcremote.BuildConfig
import com.pcremote.R
import com.pcremote.quality.QualityPreset
import com.pcremote.quality.QualityPrefs
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics

private const val REPO_URL = "https://github.com/SadikTD/pc-remote"

@Composable
fun SettingsScreen(model: SettingsModel, onBack: () -> Unit, onThemeChanged: () -> Unit) {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(model.theme) }
    var dynamic by remember { mutableStateOf(model.dynamicColor) }
    var haptics by remember { mutableStateOf(model.haptics) }
    var natural by remember { mutableStateOf(model.naturalScroll) }
    var pointer by remember { mutableFloatStateOf(model.pointerSpeed) }
    var gyro by remember { mutableFloatStateOf(model.gyroSensitivity) }
    var auto by remember { mutableStateOf(QualityPrefs.isAuto(ctx)) }
    var preset by remember { mutableStateOf(QualityPrefs.current(ctx)) }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = c.text) }
            Text(stringResource(R.string.pcr_settings), color = c.text, style = MaterialTheme.typography.headlineMedium)
        }

        Group(R.string.pcr_set_appearance) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeChoice.SYSTEM to R.string.pcr_theme_system, ThemeChoice.DARK to R.string.pcr_theme_dark,
                    ThemeChoice.LIGHT to R.string.pcr_theme_light).forEach { (t, label) ->
                    Segment(stringResource(label), selected = theme == t, Modifier.weight(1f)) {
                        theme = t; model.theme = t; onThemeChanged()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow(Icons.Rounded.Palette, R.string.pcr_dynamic_color, R.string.pcr_dynamic_color_desc, dynamic) {
                    dynamic = it; model.dynamicColor = it; onThemeChanged()
                }
            }
        }

        Group(R.string.pcr_set_quality) {
            SwitchRow(Icons.Rounded.AutoAwesome, R.string.pcr_auto_quality, R.string.pcr_auto_quality_desc, auto) {
                auto = it; QualityPrefs.setAuto(ctx, it)
            }
            listOf(QualityPreset.SMOOTH to R.string.pcr_preset_SMOOTH, QualityPreset.SHARP to R.string.pcr_preset_SHARP,
                QualityPreset.BATTERY to R.string.pcr_preset_BATTERY).forEach { (p, label) ->
                Row(
                    Modifier.fillMaxWidth().clip(MidnightShapes.control).clickable(enabled = !auto) { preset = p; QualityPrefs.apply(ctx, p) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(!auto && preset == p, null, enabled = !auto)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), color = if (auto) c.textDim else c.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Group(R.string.pcr_set_controls) {
            SwitchRow(Icons.Rounded.Vibration, R.string.pcr_haptics, null, haptics) { haptics = it; model.haptics = it }
            SwitchRow(Icons.Rounded.SwapVert, R.string.pcr_natural_scroll, R.string.pcr_natural_scroll_desc, natural) { natural = it; model.naturalScroll = it }
            SliderRow(Icons.Rounded.Mouse, R.string.pcr_pointer_speed, pointer, 0.5f..3f) { pointer = it; model.pointerSpeed = it }
            SliderRow(Icons.Rounded.ScreenRotation, R.string.pcr_gyro_sensitivity, gyro, 0.25f..3f) { gyro = it; model.gyroSensitivity = it }
        }

        Group(R.string.pcr_set_about) {
            LinkRow(Icons.Rounded.Info, R.string.pcr_version, null, trailing = BuildConfig.VERSION_NAME) {}
            LinkRow(Icons.Rounded.Code, R.string.pcr_source, R.string.pcr_source_desc) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            }
            LinkRow(Icons.Rounded.HelpOutline, R.string.pcr_setup_guide, null) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.pcremote.SETUP_GUIDE_URL)))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Group(title: Int, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMidnight.current
    Spacer(Modifier.height(24.dp))
    Text(stringResource(title).uppercase(), color = c.textDim, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(MidnightShapes.card).background(c.surface).padding(8.dp), content = content)
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val h = rememberHaptics()
    Box(modifier.clip(MidnightShapes.control).background(if (selected) c.accent else c.surfaceHigh)
        .clickable { h.tick(); onClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RowShell(icon: ImageVector, title: Int, desc: Int?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val c = LocalMidnight.current
    Row(
        Modifier.fillMaxWidth().clip(MidnightShapes.control).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(stringResource(desc), color = c.textDim, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: Int, desc: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val h = rememberHaptics()
    RowShell(icon, title, desc, { h.tick(); onChange(!checked) }) { Switch(checked, { h.tick(); onChange(it) }) }
}

@Composable
private fun LinkRow(icon: ImageVector, title: Int, desc: Int?, trailing: String? = null, onClick: () -> Unit) {
    val c = LocalMidnight.current
    RowShell(icon, title, desc, onClick) {
        if (trailing != null) Text(trailing, color = c.textDim, style = MaterialTheme.typography.bodyMedium)
        else Icon(Icons.Rounded.ChevronRight, null, tint = c.textDim)
    }
}

@Composable
private fun SliderRow(icon: ImageVector, title: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = LocalMidnight.current
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(title), color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.1f×".format(value), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value, onChange, valueRange = range, modifier = Modifier.padding(start = 38.dp))
    }
}
