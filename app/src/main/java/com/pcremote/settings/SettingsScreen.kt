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
import com.pcremote.ui.components.glass
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

    Box(Modifier.fillMaxSize().background(c.bg)) {
    com.pcremote.ui.components.Aurora(intensity = 0.4f)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.pcremote.ui.components.GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pcr_back), onBack)
            Spacer(Modifier.width(14.dp))
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

        WakeGroup()
        UnlockGroup()

        Group(R.string.pcr_set_about) {
            LinkRow(Icons.Rounded.Info, R.string.pcr_version, null, trailing = BuildConfig.VERSION_NAME) {}
            var checking by remember { mutableStateOf(false) }
            LinkRow(Icons.Rounded.SystemUpdate, R.string.pcr_update_check, R.string.pcr_update_check_desc, trailing = if (checking) "…" else null) {
                checking = true
                com.pcremote.update.Updater.checkNow(ctx) { checking = false }
            }
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
}

/** Wake from anywhere through Home Assistant. */
@Composable
private fun WakeGroup() {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    val ha = remember { com.pcremote.home.HomeAssistant.get(ctx) }
    var url by remember { mutableStateOf(ha.url) }
    var token by remember { mutableStateOf(ha.token) }
    var saved by remember { mutableStateOf(false) }
    Group(R.string.pcr_set_wake) {
        Text(stringResource(R.string.pcr_ha_desc), color = c.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp))
        OutlinedTextField(url, { url = it; saved = false }, singleLine = true, label = { Text(stringResource(R.string.pcr_ha_url)) },
            placeholder = { Text("https://home.example.com") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        OutlinedTextField(token, { token = it; saved = false }, singleLine = true, label = { Text(stringResource(R.string.pcr_ha_token)) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.pcremote.ui.components.GhostButton(stringResource(if (saved) R.string.pcr_saved else R.string.pcr_save), {
                ha.url = url; ha.token = token; url = ha.url; saved = true
            }, icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Save)
        }
    }
}

/** PINs kept behind the fingerprint, one per PC. */
@Composable
private fun UnlockGroup() {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    val pcs = remember { com.pcremote.agent.PcStore.get(ctx).all() }
    var tick by remember { mutableIntStateOf(0) }
    Group(R.string.pcr_set_unlock) {
        Text(stringResource(if (com.pcremote.agent.PinVault.available(ctx)) R.string.pcr_unlock_settings_desc else R.string.pcr_unlock_settings_nofp),
            color = c.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp))
        key(tick) {
            pcs.filter { com.pcremote.agent.PinVault.has(ctx, it.id) }.forEach { pc ->
                RowShell(Icons.Rounded.Fingerprint, R.string.pcr_unlock_saved_for, null, null, label = pc.name) {
                    TextButton({ com.pcremote.agent.PinVault.forget(ctx, pc.id); tick++ }) { Text(stringResource(R.string.pcr_forget), color = c.bad) }
                }
            }
        }
    }
}

@Composable
private fun Group(title: Int, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMidnight.current
    Spacer(Modifier.height(24.dp))
    Text(stringResource(title), color = c.textDim, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass().padding(8.dp), content = content)
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
private fun RowShell(icon: ImageVector, title: Int, desc: Int?, onClick: (() -> Unit)?, label: String? = null, trailing: @Composable () -> Unit) {
    val c = LocalMidnight.current
    Row(
        Modifier.fillMaxWidth().clip(MidnightShapes.control).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label ?: stringResource(title), color = c.text, style = MaterialTheme.typography.bodyLarge)
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
