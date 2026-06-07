package com.aeriotv.android.feature.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.R
import com.aeriotv.android.feature.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Cold-launch splash. Mirrors iOS SplashView (Aerio/App/SplashView.swift):
 * a 96dp app-icon block above the bold "AerioTV" title and the cyan
 * "Live TV · Movies · Series" subtitle. Fades in 0.4s, holds 2.3s, fades
 * out 0.4s, dismisses at 2.8s.
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
    var opacity by remember { mutableStateOf(0f) }
    val animated by animateFloatAsState(
        targetValue = opacity,
        animationSpec = tween(durationMillis = 400),
        label = "splash-fade",
    )

    LaunchedEffect(skipLoading) {
        if (skipLoading) {
            finished = true
            return@LaunchedEffect
        }
        opacity = 1f
        delay(2_300L)
        opacity = 0f
        delay(500L)
        finished = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (!finished) {
            SplashContent(modifier = Modifier.alpha(animated))
        }
    }
}

@Composable
private fun SplashContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Brand block — matches the WelcomeScreen BrandLogo so the splash
            // and the onboarding entry feel like the same surface. The iOS
            // rounded-square is baked into the PNG.
            // Cyan glow halo behind the mark (iOS SplashView parity, same
            // radial-gradient treatment as the WelcomeScreen BrandLogo).
            val accent = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to accent.copy(alpha = 0.55f),
                                0.35f to accent.copy(alpha = 0.28f),
                                0.7f to accent.copy(alpha = 0.06f),
                                1.0f to Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                            radius = size.minDimension / 2f,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                        radius = size.minDimension / 2f,
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.aerio_logo),
                    contentDescription = null,
                    modifier = Modifier.size(108.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "AerioTV",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Live TV  ·  Movies  ·  Series",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
