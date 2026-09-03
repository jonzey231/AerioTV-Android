package com.aeriotv.android.feature.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.R
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import kotlinx.coroutines.delay

/**
 * Cold-launch splash. Mirrors the CURRENT iOS/tvOS SplashView
 * (Aerio/App/SplashView.swift), which is a STATIC layout, not the legacy
 * AerioSplash.mp4 clip: black background, the rounded-square AerioLogo,
 * bold "AerioTV", and the cyan "Live TV · Movies · Series" subtitle.
 * Fades in 0.4s, holds to 2.3s, fades out 0.4s, dismisses at 2.8s.
 *
 * The Android port used to crop-fill the legacy portrait mp4, which on a
 * 16:9 TV scaled the 720px-wide clip across the whole panel and kept only
 * the middle third of the frame -- a gigantic, cropped "Aerio" (user
 * report). The video is gone entirely; every form factor now renders the
 * same layout iOS/tvOS draw, with the per-platform metrics from
 * SplashView.swift (tvOS 160/72/28, iPhone 100/50/18, iPad 130/64/24).
 *
 * Gated on `appBehaviorsSkipLoadingScreen` (Phase 8b pref). When the user
 * has flipped that toggle on, the splash dismisses immediately so the
 * Welcome / Main screen lands without the delay.
 */
@Composable
fun SplashGate(
    content: @Composable () -> Unit,
) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val skipLoading by settingsVm.skipLoadingScreen.collectAsStateWithLifecycle(initialValue = false)

    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(skipLoading) {
        if (skipLoading) {
            finished = true
            return@LaunchedEffect
        }
        // iOS SplashView timing: dismiss at 2.8s (0.4 in + hold + 0.4 out).
        delay(2_800L)
        finished = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (!finished && !skipLoading) {
            SplashContent()
        }
    }
}

@Composable
private fun SplashContent(modifier: Modifier = Modifier) {
    // iOS SplashView per-platform metrics: logo / title / subtitle.
    // tvOS 160/72/28; iPhone (compact) 100/50/18; iPad (regular) 130/64/24.
    val isTv = rememberIsTvDevice()
    val compact = LocalConfiguration.current.smallestScreenWidthDp < 600
    // TV metrics are scaled to Apple TV's PROPORTIONS, not its point values:
    // tvOS lays out on a 1920pt canvas while a 1080p Android TV is ~960dp
    // wide, so the same numbers drew everything twice as large (Logan
    // 2026-09-03, emulator vs Apple TV side by side). Logo 8.3% of width,
    // title 3.75%, subtitle 1.5%.
    val logoSize = if (isTv) 80.dp else if (compact) 100.dp else 130.dp
    val titleSize = if (isTv) 36.sp else if (compact) 50.sp else 64.sp
    val subtitleSize = if (isTv) 14.sp else if (compact) 18.sp else 24.sp
    val logoBottomPad = if (isTv) 32.dp else if (compact) 24.dp else 32.dp
    val subtitleTopPad = if (isTv) 6.dp else 10.dp
    val glowRadius = if (isTv) 20.dp else if (compact) 20.dp else 30.dp

    val accent = Color(0xFF1AC4D8)
    // iOS fade envelope: easeIn 0.4s -> hold -> easeOut 0.4s starting at 2.3s.
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "splashAlpha",
    )
    LaunchedEffect(Unit) {
        visible = true
        delay(2_300L)
        visible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // iOS splash sits on PURE BLACK, not the theme background.
            .background(Color.Black)
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The iOS rounded-square treatment is baked into the PNG.
            // Apple's `.shadow(color: 1AC4D8 @ 0.55, radius, y: 7.5% of size)`:
            // a soft cyan glow behind the tile, offset slightly downward.
            val glowPx = with(LocalDensity.current) { glowRadius.toPx() }
            val logoPx = with(LocalDensity.current) { logoSize.toPx() }
            Image(
                painter = painterResource(id = R.drawable.aerio_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    .drawBehind {
                        val c = Offset(size.width / 2f, size.height / 2f + logoPx * 0.075f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0f)),
                                center = c,
                                radius = logoPx / 2f + glowPx * 2f,
                            ),
                            radius = logoPx / 2f + glowPx * 2f,
                            center = c,
                        )
                    },
            )
            Spacer(Modifier.height(logoBottomPad))
            Text(
                text = "AerioTV",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(subtitleTopPad))
            Text(
                text = "Live TV  ·  Movies  ·  Series",
                fontSize = subtitleSize,
                fontWeight = FontWeight.Light,
                color = accent,
            )
        }
    }
}
