package com.pcremote.onboarding

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.limelight.R
import com.pcremote.ui.components.*
import com.pcremote.ui.theme.LocalMidnight
import kotlinx.coroutines.launch

/** Opens the Play-services QR scanner (no camera permission, no bundled model). */
fun scanQr(ctx: android.content.Context, onResult: (String) -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan().addOnSuccessListener { b -> b.rawValue?.let(onResult) }
}

@Composable
fun OnboardingScreen(model: PairingModel, onDone: (String) -> Unit, onManual: () -> Unit, onSkip: () -> Unit) {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 3 }
    val state by model.state.collectAsState()

    LaunchedEffect(state) { (state as? PairingState.Done)?.let { onDone(it.pcId) } }

    fun scan() = scanQr(ctx) { raw -> scope.launch { model.onScanned(raw) } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Aurora()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.pcr_ob_skip), color = c.textDim) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Step(Icons.Rounded.Devices, R.string.pcr_ob_welcome_title, R.string.pcr_ob_welcome_body)
                    1 -> Step(Icons.Rounded.Download, R.string.pcr_ob_get_title, R.string.pcr_ob_get_body) {
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.pcr_ob_get_link), color = c.accent, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        GhostButton(stringResource(R.string.pcr_ob_share), icon = Icons.Rounded.Share, onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://" + ctx.getString(R.string.pcr_ob_get_link))
                            }, null))
                        })
                    }
                    else -> Step(Icons.Rounded.QrCodeScanner, R.string.pcr_ob_scan_title, R.string.pcr_ob_scan_body) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onManual) { Text(stringResource(R.string.pcr_ob_manual), color = c.textDim) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PagerDots(3, pager.currentPage)
                Spacer(Modifier.weight(1f))
                if (pager.currentPage < 2) {
                    PrimaryButton(stringResource(R.string.pcr_ob_next), { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
                } else {
                    PrimaryButton(stringResource(R.string.pcr_ob_scan), ::scan, icon = Icons.Rounded.QrCodeScanner)
                }
            }
        }
        PairingOverlay(state, onRetry = { model.reset(); scan() }, onDismiss = model::reset)
    }
}

@Composable
private fun Step(icon: ImageVector, title: Int, body: Int, extra: @Composable ColumnScope.() -> Unit = {}) {
    val c = LocalMidnight.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(112.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.25f), c.accent2.copy(alpha = 0.15f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = c.text, modifier = Modifier.size(52.dp)) }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(title), style = MaterialTheme.typography.displaySmall, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = c.textDim, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp))
        extra()
    }
}

/** Full-screen glass state for pairing progress and errors. */
@Composable
fun PairingOverlay(state: PairingState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalMidnight.current
    AnimatedContent(state, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() }, label = "pair") { s ->
        when (s) {
            is PairingState.Pairing, is PairingState.Error -> Box(
                Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.88f)).safeDrawingPadding().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    if (s is PairingState.Pairing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceHigh)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.pcr_ob_pairing, s.pcName), style = MaterialTheme.typography.titleLarge, color = c.text)
                    } else if (s is PairingState.Error) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = c.bad, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(s.message), style = MaterialTheme.typography.bodyLarge, color = c.text)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton(stringResource(R.string.pcr_try_again), onRetry)
                            GhostButton(stringResource(R.string.pcr_close), onDismiss)
                        }
                    }
                }
            }
            else -> Spacer(Modifier)
        }
    }
}
