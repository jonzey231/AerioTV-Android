// LicensesScreen.kt
//
// Settings -> About -> Open Source Licenses.
//
// Why this screen exists: AerioTV is GPL-3.0-or-later and it distributes an
// FFmpeg build under the LGPL-2.1-or-later. Both licenses require that the
// license text and the attribution travel WITH the binary, not just with the
// repo, and the LGPL additionally requires telling the user how to relink a
// modified FFmpeg. A link on a website does not satisfy either one; the notice
// has to be reachable from inside the shipped app, which is what this is.
//
// The content here is hand-maintained rather than generated from the
// dependency graph. That is deliberate: the only entries with real obligations
// (FFmpeg's build configuration, the relink instructions, the Play services
// exception) are facts a generator cannot know. When a dependency changes in
// `gradle/libs.versions.toml`, update BOTH this file and the repo-root
// THIRD_PARTY_LICENSES.md, which carries the same list.

package com.aeriotv.android.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R
import com.aeriotv.android.core.tv.TvQrLink
import com.aeriotv.android.core.tv.TvQrLinkDialog
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsNavRow
import com.aeriotv.android.ui.settings.SettingsSectionFooter
import com.aeriotv.android.ui.settings.SettingsSectionHeader
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsFootnoteStyle
import com.aeriotv.android.ui.settings.settingsRowValueStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A license text bundled in `res/raw` and viewable in full inside the app. */
private enum class BundledLicense(val title: String, val res: Int) {
    Gpl3("GNU General Public License v3.0", R.raw.license_gpl_3_0),
    Lgpl21("GNU Lesser General Public License v2.1", R.raw.license_lgpl_2_1),
    Apache2("Apache License 2.0", R.raw.license_apache_2_0),
}

private const val SOURCE_URL = "https://github.com/jonzey231/AerioTV-Android"
private const val FFMPEG_SOURCE_URL = "https://github.com/FFmpeg/FFmpeg/tree/release/6.0"

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    // Which full license text is open, if any. Kept as local state rather than
    // its own SettingsRoute: it is a leaf detail of THIS screen and pushing it
    // on the settings stack would put "GNU General Public License v3.0" in the
    // TV rail's pushed-route slot, which reads as a settings section.
    var viewing by remember { mutableStateOf<BundledLicense?>(null) }
    var qrLink by remember { mutableStateOf<TvQrLink?>(null) }
    val isTv = rememberIsTvDevice()
    val context = LocalContext.current

    // BACK closes the open text first, then leaves the screen. Without this the
    // remote's BACK would pop straight out of Settings from inside a license.
    BackHandler(enabled = viewing != null) { viewing = null }

    val openUrl: (String, String) -> Unit = { title, url ->
        if (isTv) {
            qrLink = TvQrLink(
                title = title,
                caption = "Scan with your phone to open this page.",
                url = url,
            )
        } else {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(
            title = viewing?.title ?: "Open Source Licenses",
            onBack = { if (viewing != null) viewing = null else onBack() },
        )

        when (val open = viewing) {
            null -> LicenseIndex(
                onOpenLicense = { viewing = it },
                onOpenUrl = openUrl,
            )

            else -> LicenseTextViewer(license = open)
        }
    }

    qrLink?.let { link ->
        TvQrLinkDialog(
            title = link.title,
            caption = link.caption,
            url = link.url,
            onDismiss = { qrLink = null },
        )
    }
}

// MARK: - Index

@Composable
private fun LicenseIndex(
    onOpenLicense: (BundledLicense) -> Unit,
    onOpenUrl: (String, String) -> Unit,
) {
    // A takeover has no rail to inset it, so it owes its own title-safe
    // margin. 16dp lands a third of the way into the overscan strip on a
    // 1080p Streamer; overscanStart is the 5% the rest of Settings uses.
    val sideInset = if (rememberIsTvDevice()) TvSettingsMetrics.overscanStart else 16.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sideInset, vertical = 12.dp),
    ) {
        // MARK: AerioTV itself
        item("aerio-header") { SettingsSectionHeader("AerioTV") }
        item("aerio-body") {
            LicenseBlurb(
                "Copyright (C) 2026 Logan Jones\n\n" +
                    "AerioTV for Android is free software: you can redistribute it " +
                    "and/or modify it under the terms of the GNU General Public " +
                    "License as published by the Free Software Foundation, either " +
                    "version 3 of the License, or (at your option) any later version.\n\n" +
                    "This program is distributed in the hope that it will be useful, " +
                    "but WITHOUT ANY WARRANTY; without even the implied warranty of " +
                    "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.",
            )
        }
        item("aerio-rows") {
            LicenseCard {
                SettingsNavRow(
                    title = "GNU General Public License v3.0",
                    subtitle = "The license this app is distributed under",
                    icon = Icons.Outlined.Description,
                    onClick = { onOpenLicense(BundledLicense.Gpl3) },
                )
                SettingsNavRow(
                    title = "Source Code",
                    subtitle = SOURCE_URL.removePrefix("https://"),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = { onOpenUrl("Source Code", SOURCE_URL) },
                    trailingChevron = false,
                )
            }
        }
        item("aerio-footer") {
            SettingsSectionFooter(
                "Linking with the proprietary Google Play services libraries is " +
                    "permitted by an additional permission under GPL section 7. " +
                    "See LICENSE-EXCEPTIONS.md in the source repository.",
            )
        }

        item("gap-1") { Spacer(Modifier.height(18.dp)) }

        // MARK: FFmpeg. The one bundled component with real LGPL obligations,
        // so the build configuration and the relink route are spelled out
        // rather than reduced to a license name.
        item("ffmpeg-header") { SettingsSectionHeader("Playback") }
        item("ffmpeg-body") {
            LicenseBlurb(
                "This app includes FFmpeg (release/6.0, libavcodec 60.3.100), used " +
                    "unmodified as the software audio decoder for AC-3, E-AC-3, DTS, " +
                    "TrueHD and MP2 on devices with no hardware decoder for them.\n\n" +
                    "FFmpeg is licensed under the GNU Lesser General Public License, " +
                    "version 2.1 or later. It is built WITHOUT --enable-gpl, enabling " +
                    "only these decoders: ac3, eac3, dca, mlp, truehd, mp2, aac, mp3, " +
                    "flac, alac. No GPL-only component is linked in.\n\n" +
                    "FFmpeg is loaded as a dynamically linked JNI shared object " +
                    "(libffmpegJNI.so) and can be replaced with a modified build, as " +
                    "section 6 of the LGPL requires. Build steps are in app/libs/README.md " +
                    "in the source repository.",
            )
        }
        item("ffmpeg-rows") {
            LicenseCard {
                SettingsNavRow(
                    title = "GNU Lesser General Public License v2.1",
                    subtitle = "The license FFmpeg is distributed under",
                    icon = Icons.Outlined.Description,
                    onClick = { onOpenLicense(BundledLicense.Lgpl21) },
                )
                SettingsNavRow(
                    title = "FFmpeg Source",
                    subtitle = "github.com/FFmpeg/FFmpeg (release/6.0)",
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = { onOpenUrl("FFmpeg Source", FFMPEG_SOURCE_URL) },
                    trailingChevron = false,
                )
            }
        }

        item("gap-2") { Spacer(Modifier.height(18.dp)) }

        // MARK: Apache-2.0 bulk
        item("apache-header") { SettingsSectionHeader("Libraries") }
        item("apache-body") {
            LicenseBlurb(
                "The following are used under the Apache License, Version 2.0:\n\n" +
                    APACHE_COMPONENTS.joinToString("\n") { "- $it" },
            )
        }
        item("apache-rows") {
            LicenseCard {
                SettingsNavRow(
                    title = "Apache License 2.0",
                    subtitle = "The license the above are distributed under",
                    icon = Icons.Outlined.Description,
                    onClick = { onOpenLicense(BundledLicense.Apache2) },
                )
            }
        }

        item("gap-3") { Spacer(Modifier.height(18.dp)) }

        // MARK: proprietary
        item("gms-header") { SettingsSectionHeader("Google Play Services") }
        item("gms-body") {
            LicenseBlurb(
                "Google Cast (sender and receiver), Play services Auth and the Google " +
                    "Identity library are not open source. They are used under the " +
                    "Android Software Development Kit License Agreement and the Google " +
                    "APIs Terms of Service.",
            )
        }
        item("tail") { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * Grouped in the same order as THIRD_PARTY_LICENSES.md so the two lists can be
 * diffed by eye when a dependency changes.
 */
private val APACHE_COMPONENTS = listOf(
    "AndroidX and Jetpack Compose (The Android Open Source Project)",
    "Media3 / ExoPlayer (The Android Open Source Project)",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization (JetBrains)",
    "Ktor (JetBrains)",
    "Dagger and Hilt (Google)",
    "OkHttp (Square)",
    "Coil (Coil Contributors)",
    "ZXing Core (ZXing Authors)",
    "Reorderable (Calvin Liang)",
)

@Composable
private fun LicenseBlurb(text: String) {
    Text(
        text = text,
        style = settingsFootnoteStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/**
 * Rows are laid out with real spacing and NO surrounding card.
 *
 * `SettingsNavRow` at its default `flat = false` already paints its own card
 * through `settingsRowCard`, which includes `tvFocusScale(1.02f)`. Nesting
 * those inside a `clip()`ped container (the shape `SettingsSectionGroup` uses)
 * clips that 2% focus grow at the container edge and stacks two rounded edges
 * on top of each other, which is what made the TV focus highlight look wrong.
 * Standalone rows let the focus scale and border draw in full.
 */
@Composable
private fun LicenseCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// MARK: - Full text viewer

@Composable
private fun LicenseTextViewer(license: BundledLicense) {
    val context = LocalContext.current
    var paragraphs by remember(license) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(license) {
        paragraphs = withContext(Dispatchers.IO) {
            runCatching {
                context.resources.openRawResource(license.res)
                    .bufferedReader()
                    .use { it.readText() }
                    .let(::reflowLicense)
            }.getOrDefault(emptyList())
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isTv = rememberIsTvDevice()
    val focusRequester = remember { FocusRequester() }

    // A license text has no focusable children, so on TV nothing would take
    // D-pad focus and the page could not be scrolled at all. Make the list
    // itself focusable and translate UP/DOWN into a scroll. Note focusable()
    // is applied WITHOUT a clickable() ahead of it: that ordering swallows
    // focus on Compose TV.
    LaunchedEffect(isTv, paragraphs.isEmpty()) {
        if (isTv && paragraphs.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!isTv) Modifier else Modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionDown -> TvScrollStepPx
                            Key.DirectionUp -> -TvScrollStepPx
                            else -> return@onPreviewKeyEvent false
                        }
                        scope.launch { listState.animateScrollBy(delta, tween(180)) }
                        true
                    },
            ),
        contentPadding = PaddingValues(
            horizontal = if (isTv) TvSettingsMetrics.overscanStart else 20.dp,
            vertical = 12.dp,
        ),
    ) {
        items(items = paragraphs) { para ->
            Text(
                text = para,
                style = settingsRowValueStyle(),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
    }
}

/** One D-pad press of scroll. Roughly half a TV viewport of body text. */
private const val TvScrollStepPx = 420f

/**
 * License texts ship hard-wrapped at about 70 columns, which reads badly on a
 * phone and is unusable at 10 feet. Re-flow each blank-line-delimited
 * paragraph into a single string and let Compose wrap it to the real width.
 *
 * Paragraphs that are indented are left exactly as they are: in the GPL and
 * the Apache appendix that indentation carries structure (section headings,
 * the boilerplate notice block), and re-flowing them would destroy it.
 */
private fun reflowLicense(raw: String): List<String> =
    raw.replace("\r\n", "\n")
        .split("\n\n")
        .map { para ->
            val lines = para.split("\n")
            val preformatted = lines.any { it.startsWith("    ") || it.startsWith("\t") }
            if (preformatted) para.trimEnd() else lines.joinToString(" ") { it.trim() }.trim()
        }
        .filter { it.isNotEmpty() }
