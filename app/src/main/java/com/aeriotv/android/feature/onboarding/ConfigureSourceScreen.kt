package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.core.data.db.entity.sanitizeGuideDays
import kotlinx.coroutines.launch
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.InfoBanner
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.dpadFocusWash
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
import com.aeriotv.android.ui.textfield.SecretRevealIconButton
import com.aeriotv.android.ui.textfield.rememberSecretRevealState
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.dpadFocusEscape
import com.aeriotv.android.ui.tv.tvFormFieldInput

/**
 * Configure-source form. Mirrors iOS App Store screenshots IMG_1078 (Dispatcharr
 * API Key), IMG_1079 (Xtream Codes), IMG_1080 (M3U + EPG), IMG_1081 (Dispatcharr
 * Username & Password): same source-type card at top, same field set per type,
 * same info banner, same "Test Connection" CTA.
 *
 * Field state mostly lives on PlaylistViewModel so it survives configuration
 * change and survives navigating away to the choose-type screen. A local
 * Dispatcharr-auth-mode toggle controls which form variant renders for the
 * Dispatcharr type.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConfigureSourceScreen(
    sourceType: SourceType,
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Make sure the VM knows which type we're configuring before the user enters anything.
    androidx.compose.runtime.LaunchedEffect(sourceType) {
        if (state.sourceType != sourceType) {
            viewModel.onSourceTypeChange(sourceType)
        }
    }

    // Dispatcharr has two flavours sharing the same Configure screen; track
    // the toggle locally. Key on the nav-passed `sourceType`, not
    // `state.sourceType`, the viewmodel sync happens in the LaunchedEffect
    // above which runs after first composition, so reading state.sourceType
    // here would pick up the previous session's value on first paint.
    var dispatcharrAuthMode by remember(sourceType) {
        mutableStateOf(
            when (sourceType) {
                SourceType.DispatcharrApiKey -> DispatcharrAuthMode.ApiKey
                else -> DispatcharrAuthMode.UsernamePassword
            }
        )
    }

    // Dispatcharr user QR (scanned, picked from Photos, or shared to AerioTV):
    // fill the server URL and API key, switch to API Key, then run the same
    // Test Connection path as typing. The username is advisory, display only.
    var scannedUser by remember(sourceType) { mutableStateOf<String?>(null) }
    val isDispatcharr = sourceType == SourceType.DispatcharrApiKey || sourceType == SourceType.DispatcharrUserPass
    val applyUserKey: (com.aeriotv.android.core.qr.ScannedCode.Result.UserKey) -> Unit = { k ->
        dispatcharrAuthMode = DispatcharrAuthMode.ApiKey
        viewModel.onSourceTypeChange(SourceType.DispatcharrApiKey)
        k.baseUrl?.let(viewModel::onUrlChange)
        viewModel.onApiKeyChange(k.apiKey)
        scannedUser = k.username
        com.aeriotv.android.core.qr.QrImportBus.noteSignIn(
            com.aeriotv.android.core.qr.QrImportBus.SignIn(
                apiKey = k.apiKey,
                baseUrl = k.baseUrl ?: com.aeriotv.android.feature.playlist.normalizeSchemedUrl(viewModel.state.value.url),
                username = k.username,
            ),
        )
        viewModel.loadPlaylist()
    }
    val sharedKey by com.aeriotv.android.core.qr.QrImportBus.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sharedKey, isDispatcharr) {
        if (!isDispatcharr || sharedKey == null) return@LaunchedEffect
        val k = com.aeriotv.android.core.qr.QrImportBus.take() ?: return@LaunchedEffect
        // A shared image starts a fresh draft, like picking the type by hand.
        viewModel.startNewSource(SourceType.DispatcharrApiKey)
        applyUserKey(k)
    }

    val cardIcon: ImageVector
    val cardTitle: String
    val cardSubtitle: String
    when (sourceType) {
        SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
            cardIcon = Icons.Filled.Key
            cardTitle = "Dispatcharr Direct Connect"
            cardSubtitle = "Connect to Dispatcharr with your admin login or a personal API key " +
                    "(*AerioTV is not officially affiliated with the Dispatcharr project)"
        }
        SourceType.XtreamCodes -> {
            cardIcon = Icons.Filled.Tv
            cardTitle = "Xtream Codes"
            cardSubtitle = "Xtream Codes API. Live TV, VOD movies & series."
        }
        SourceType.M3uUrl -> {
            cardIcon = Icons.Filled.Description
            cardTitle = "M3U + EPG"
            cardSubtitle = "Any M3U playlist URL. Works with Dispatcharr, any IPTV provider."
        }
    }

    TvKeyboardOnOkHost {
    Column(modifier = Modifier.fillMaxSize()) {
        // No page title (owner 2026-10-27): the source card names the step.
        // Phones/tablets keep the back arrow; on Android TV the remote BACK
        // steps back to the source-type pick, so the bar is dropped.
        val isTv = rememberIsTvDevice()
        if (!isTv) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }

        val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
        // TV: deadband spec stops the form's +/-1px per-frame jiggle when a
        // focused text field sits at the floating IME's top edge (see
        // TvImeNoJitterBringIntoViewSpec). Same loop as Edit Playlist.
        val bringIntoViewSpec =
            if (rememberIsTvDevice()) com.aeriotv.android.ui.tv.TvImeNoJitterBringIntoViewSpec
            else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringIntoViewSpec,
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Bottom room so Test Connection never sits flush on the
                // display edge when focus scrolls to it; TV adds top room
                // for the overscan margin now that the bar is gone.
                .padding(
                    start = vp.gutter,
                    end = vp.gutter,
                    top = if (isTv) 32.dp else 12.dp,
                    bottom = if (isTv) 64.dp else 32.dp,
                ),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        Column(
            modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
            else
                Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SourceTypeCard(icon = cardIcon, title = cardTitle, subtitle = cardSubtitle)

            when (sourceType) {
                SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                    DispatcharrFields(
                        state = state,
                        viewModel = viewModel,
                        authMode = dispatcharrAuthMode,
                        scannedUser = scannedUser,
                        onUserKey = applyUserKey,
                        onAuthModeChange = { mode ->
                            dispatcharrAuthMode = mode
                            viewModel.onSourceTypeChange(
                                if (mode == DispatcharrAuthMode.ApiKey)
                                    SourceType.DispatcharrApiKey
                                else
                                    SourceType.DispatcharrUserPass
                            )
                        },
                    )
                }
                SourceType.XtreamCodes -> XtreamFields(state, viewModel)
                SourceType.M3uUrl -> M3uFields(state, viewModel)
            }

            // Per-playlist On Demand opt-in (iOS AddServerView.vodEnabledRow,
            // AddServerView.swift:337). Only meaningful for source types that
            // actually carry VOD; M3U playlists never have it. Default ON.
            // When off the OnDemand tab disappears and the multi-thousand-item
            // VOD sync is skipped -- useful for users who only want Live TV
            // from this playlist, or who have a second playlist already
            // providing VOD. Can be flipped later in Edit Playlist.
            if (sourceType.supportsVOD) {
                Spacer(Modifier.height(4.dp))
                VodEnabledRow(
                    checked = state.vodEnabled,
                    onCheckedChange = viewModel::onVodEnabledChange,
                )
            }

            // Task #45 DVR onboarding step (iOS AddServerView Destination
            // picker, Dispatcharr only): where recordings land by default.
            // Committed to the global DVR preference only when Test
            // Connection succeeds; changeable later in Settings > DVR.
            if (sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            ) {
                Spacer(Modifier.height(4.dp))
                DvrDestinationRow(
                    server = state.dvrDestinationServer,
                    onSelect = viewModel::onDvrDestinationChange,
                )
            }

            // Catch-up guide-history retention (task #135): how many days of
            // already-aired guide data this playlist keeps. Past shows on
            // channels with catch-up can be replayed from the guide, so this
            // doubles as the catch-up browse depth. Default 7 days; also
            // editable later in Edit Playlist.
            Spacer(Modifier.height(4.dp))
            GuideHistoryRow(
                selectedDays = state.epgRetentionDays,
                onSelect = viewModel::onEpgRetentionDaysChange,
            )

            val validation = validate(sourceType, state, dispatcharrAuthMode)
            if (validation != null) {
                InfoBanner(text = validation)
            }
            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            // When D-pad focus lands on Test Connection, scroll so the page
            // shows a strip below it instead of parking it on the edge.
            val testBringer = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            val testScope = androidx.compose.runtime.rememberCoroutineScope()
            var testSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val testTail = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
            Button(
                onClick = { viewModel.loadPlaylist() },
                enabled = !state.isLoading && validation == null,
                modifier = Modifier
                    .onSizeChanged { testSize = it }
                    .bringIntoViewRequester(testBringer)
                    .onFocusChanged { fs ->
                        if (fs.isFocused) testScope.launch {
                            testBringer.bringIntoView(
                                androidx.compose.ui.geometry.Rect(
                                    0f, 0f, testSize.width.toFloat(), testSize.height + testTail,
                                ),
                            )
                        }
                    }
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "Test Connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
        }
        }
    }
    }
}

/** Shown under the Username & Password fields on onboarding and Edit Playlist. */
internal const val DISPATCHARR_API_KEY_REQUIRED_NOTE =
    "Your Dispatcharr account must already have an API key. In Dispatcharr, open System, " +
        "then Users, edit the user, and generate an API key before signing in here."

enum class DispatcharrAuthMode { UsernamePassword, ApiKey }

@Composable
private fun DispatcharrFields(
    state: PlaylistViewModel.UiState,
    viewModel: PlaylistViewModel,
    authMode: DispatcharrAuthMode,
    scannedUser: String?,
    onUserKey: (com.aeriotv.android.core.qr.ScannedCode.Result.UserKey) -> Unit,
    onAuthModeChange: (DispatcharrAuthMode) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LabeledField(label = "Name") {
        IconTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            placeholder = "My IPTV Server",
            leading = Icons.Outlined.Sell,
            enabled = !state.isLoading,
        )
    }

    LabeledField(label = "Server URL") {
        IconTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            placeholder = "http://your-dispatcharr-server:9191",
            leading = Icons.Outlined.Link,
            enabled = !state.isLoading,
        )
    }

    LanUrlField(state = state, viewModel = viewModel)

    SegmentedAuthControl(
        selected = authMode,
        onSelect = onAuthModeChange,
        enabled = !state.isLoading,
    )

    // Phones and tablets (draws nothing on TV), in both sign-in modes: the
    // Dispatcharr user QR fills the server and key in one step.
    com.aeriotv.android.feature.qrscan.QrScanActions(
        enabled = !state.isLoading,
        onResult = { r ->
            when (r) {
                is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey -> onUserKey(r)
                is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink ->
                    com.aeriotv.android.feature.qrscan.openSetupLink(context, r.link)
                com.aeriotv.android.core.qr.ScannedCode.Result.Invalid -> Unit
            }
        },
    )
    ClipboardCodeChip(
        enabled = !state.isLoading,
        currentApiKey = state.apiKey,
        onUserKey = onUserKey,
        onSetupLink = { com.aeriotv.android.feature.qrscan.openSetupLink(context, it) },
    )
    scannedUser?.let {
        Text(
            text = "Signed in as $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    when (authMode) {
        DispatcharrAuthMode.ApiKey -> {
            LabeledField(label = "Admin API Key") {
                val apiKeyReveal = rememberSecretRevealState()
                IconTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::onApiKeyChange,
                    placeholder = "Paste your admin API key",
                    leading = Icons.Filled.Key,
                    visualTransformation = apiKeyReveal.transformation,
                    trailing = {
                        SecretRevealIconButton(
                            state = apiKeyReveal,
                            iconSize = 18.dp,
                            contentLabel = "API key",
                        )
                    },
                    trailingFocused = { apiKeyReveal.controlFocused },
                    enabled = !state.isLoading,
                )
            }
            InfoBanner(
                text = "Use a Dispatcharr Admin API Key (System -> Users -> Edit User -> API & XC). " +
                        "This enables native Dispatcharr endpoints for Live TV, Guide, Movies, " +
                        "and TV Shows. If your admin rotates the key, you'll need to re-enter it " +
                        "here. For hands-off auto-refresh, switch to Username & Password.",
            )
        }
        DispatcharrAuthMode.UsernamePassword -> {
            LabeledField(label = "Username") {
                IconTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = "Dispatcharr admin username",
                    leading = Icons.Outlined.Person,
                    enabled = !state.isLoading,
                )
            }
            PasswordField(
                label = "Password",
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = "Dispatcharr admin password",
                enabled = !state.isLoading,
            )
            Text(
                text = "Use your Dispatcharr Dashboard password (System -> Users -> Account tab), " +
                        "not your Dispatcharr XC password.",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DISPATCHARR_API_KEY_REQUIRED_NOTE,
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoBanner(
                text = "Save credentials and refresh automatically. AerioTV signs in with these " +
                        "credentials, then keeps your session alive in the background. If your " +
                        "Dispatcharr admin rotates your API key, AerioTV silently re-authenticates " +
                        "without prompting. Credentials are stored in encrypted Android storage.",
            )
        }
    }
}

@Composable
private fun XtreamFields(state: PlaylistViewModel.UiState, viewModel: PlaylistViewModel) {
    LabeledField(label = "Name") {
        IconTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            placeholder = "My IPTV Server",
            leading = Icons.Outlined.Sell,
            enabled = !state.isLoading,
        )
    }
    LabeledField(label = "Server URL") {
        IconTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            placeholder = "http://your-server.com:8080",
            leading = Icons.Outlined.Link,
            enabled = !state.isLoading,
        )
    }
    LanUrlField(state = state, viewModel = viewModel)
    LabeledField(label = "Username") {
        IconTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            placeholder = "XC Username",
            leading = Icons.Outlined.Person,
            enabled = !state.isLoading,
        )
    }
    PasswordField(
        label = "Password",
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        placeholder = "XC Password",
        enabled = !state.isLoading,
    )
    InfoBanner(
        text = "Enter your Xtream Codes server URL and credentials. Dispatcharr users: use your " +
                "Dispatcharr URL with the Xtream Codes username and password from Dispatcharr's " +
                "User settings.",
    )
}

@Composable
private fun M3uFields(state: PlaylistViewModel.UiState, viewModel: PlaylistViewModel) {
    LabeledField(label = "Name") {
        IconTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            placeholder = "My IPTV Server",
            leading = Icons.Outlined.Sell,
            enabled = !state.isLoading,
        )
    }
    LabeledField(label = "M3U URL") {
        IconTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            placeholder = "https://example.com/playlist.m3u",
            leading = Icons.Outlined.Link,
            enabled = !state.isLoading,
        )
    }
    // Task #45 file import (iOS M3UImportView fileImporter twin). Phone and
    // tablet only: TVs have no document-picker UI. The picked file is copied
    // into filesDir/imports/ and its file:// URI dropped into the URL field,
    // so the rest of the add/refresh pipeline treats it like any source
    // (fetchViaTempFile parses file: URIs in place).
    val importContext = androidx.compose.ui.platform.LocalContext.current
    val importScope = androidx.compose.runtime.rememberCoroutineScope()
    if (!rememberIsTvDevice()) {
        val m3uPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                importScope.launch {
                    importPickedFile(importContext, it, "m3u")?.let(viewModel::onUrlChange)
                }
            }
        }
        ImportFileLink(label = "Import M3U from a file instead") {
            // M3U MIME registration is a mess in the wild (x-mpegurl,
            // audio/mpegurl, octet-stream, text/plain) -- accept anything,
            // like iOS's [.data, .plainText] allowance.
            m3uPicker.launch(arrayOf("*/*"))
        }
    }
    LabeledField(label = "EPG URL (Optional)") {
        IconTextField(
            value = state.epgUrl,
            onValueChange = viewModel::onEpgUrlChange,
            placeholder = "https://example.com/epg.xml",
            leading = Icons.Outlined.CalendarToday,
            enabled = !state.isLoading,
        )
    }
    if (!rememberIsTvDevice()) {
        val epgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                importScope.launch {
                    importPickedFile(importContext, it, "xml")?.let(viewModel::onEpgUrlChange)
                }
            }
        }
        ImportFileLink(label = "Import XMLTV from a file instead") {
            epgPicker.launch(arrayOf("*/*"))
        }
    }
    InfoBanner(
        text = "Paste your M3U playlist URL. Works with Dispatcharr's /output/m3u, any IPTV " +
                "provider, or a direct .m3u file link.",
    )
}

/**
 * "Paste from clipboard" (phones and tablets): when the form opens and the
 * clipboard holds a Dispatcharr user code (the `user_api_key` JSON) or an
 * AerioTV setup link, one tap applies it like a scan. The clipboard is read
 * once, after the window has focus (Android only answers a focused app;
 * Android 13+ shows its own paste notice). Plain text only; nothing about the
 * clip is logged.
 */
@Composable
private fun ClipboardCodeChip(
    enabled: Boolean,
    currentApiKey: String,
    onUserKey: (com.aeriotv.android.core.qr.ScannedCode.Result.UserKey) -> Unit,
    onSetupLink: (String) -> Unit,
) {
    if (rememberIsTvDevice()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var checked by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<com.aeriotv.android.core.qr.ScannedCode.Result?>(null) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (!focused || checked) return@LaunchedEffect
        checked = true
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return@LaunchedEffect
        val r = runCatching {
            val desc = cm.primaryClipDescription
            if (desc == null || !(desc.hasMimeType("text/*") || desc.hasMimeType("application/json"))) {
                null
            } else {
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (text == null || text.length > com.aeriotv.android.core.qr.CodeTextImport.MAX_BYTES) null
                else com.aeriotv.android.core.qr.CodeTextImport.parse(text)
            }
        }.getOrNull()
        code = r?.takeIf { it !is com.aeriotv.android.core.qr.ScannedCode.Result.Invalid }
    }
    val c = code ?: return
    // Already applied (the key in the form is this one): nothing to offer.
    if (c is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey && c.apiKey == currentApiKey.trim()) return
    androidx.compose.material3.AssistChip(
        onClick = {
            code = null
            when (c) {
                is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey -> onUserKey(c)
                is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink -> onSetupLink(c.link)
                com.aeriotv.android.core.qr.ScannedCode.Result.Invalid -> Unit
            }
        },
        enabled = enabled,
        label = { Text("Paste from clipboard") },
        leadingIcon = {
            Icon(
                Icons.Outlined.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/** Task #45: small inline action under a URL field that swaps it for an
 *  imported local file. */
@Composable
private fun ImportFileLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.textAccent,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** Copies a picked document into filesDir/imports/ and returns its file://
 *  URI, or null when the stream can't be opened. The copy makes the source
 *  durable across reboots and permission-grant expiry (same reason iOS
 *  copies imports into its container) -- refresh re-parses the snapshot. */
private suspend fun importPickedFile(
    context: android.content.Context,
    uri: android.net.Uri,
    ext: String,
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val dir = java.io.File(context.filesDir, "imports").apply { mkdirs() }
        // Carry the picked document's display name into the stored copy so
        // the auto-derived playlist name reads "sample" instead of a UUID
        // (deriveName takes the URL's last path segment minus extension).
        val display = context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9 ._-]"), "")
            ?.take(40)
            ?.ifBlank { null }
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        val dest = java.io.File(dir, "${display ?: "import"}-$suffix.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        android.net.Uri.fromFile(dest).toString()
    }.getOrNull()
}

/**
 * Optional LAN URL field. Pairs with the Server URL so the user can capture
 * both the public/remote and the LAN-side host in one pass during onboarding;
 * AerioTV swaps to the LAN value automatically whenever the server answers
 * locally (a reachability probe, checked at launch, on network changes, and
 * after edits). M3U sources skip this, they're already URL-based and there's
 * no auth credential reuse implied.
 */
@Composable
private fun LanUrlField(state: PlaylistViewModel.UiState, viewModel: PlaylistViewModel) {
    LabeledField(label = "LAN URL (Optional)") {
        IconTextField(
            value = state.lanUrl,
            onValueChange = viewModel::onLanUrlChange,
            placeholder = "http://192.168.1.50:9191",
            leading = Icons.Outlined.Wifi,
            enabled = !state.isLoading,
        )
    }
    Text(
        text = "AerioTV uses this URL automatically whenever your server is reachable on " +
                "the local network, and the public one above otherwise. No setup needed.",
        style = MaterialTheme.typography.bodySmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
internal fun IconTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: ImageVector,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    // True while the trailing control (the reveal eye) holds D-pad focus, so
    // the field leaves OK alone and the button can actually be clicked.
    trailingFocused: () -> Boolean = { false },
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = aerioTextFieldKeyboardOptions(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth()
            .tvFormFieldInput(
                horizontalFocusEscape = trailing != null,
                okSuppressed = trailingFocused,
            ),
        singleLine = true,
        placeholder = { Text(placeholder, style = androidx.compose.material3.LocalTextStyle.current.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = trailing,
        enabled = enabled,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
    )
}


@Composable
internal fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    val reveal = rememberSecretRevealState()
    LabeledField(label = label) {
        IconTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            leading = Icons.Outlined.Lock,
            enabled = enabled,
            visualTransformation = reveal.transformation,
            trailing = {
                SecretRevealIconButton(
                    state = reveal,
                    iconSize = 18.dp,
                    contentLabel = "password",
                )
            },
            trailingFocused = { reveal.controlFocused },
        )
    }
}

@Composable
internal fun SegmentedAuthControl(
    selected: DispatcharrAuthMode,
    onSelect: (DispatcharrAuthMode) -> Unit,
    enabled: Boolean,
) {
    val cornerRadius = 22.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                RoundedCornerShape(cornerRadius),
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegmentItem(
            label = "Username & Password",
            isSelected = selected == DispatcharrAuthMode.UsernamePassword,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DispatcharrAuthMode.UsernamePassword) },
        )
        SegmentItem(
            label = "API Key",
            isSelected = selected == DispatcharrAuthMode.ApiKey,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DispatcharrAuthMode.ApiKey) },
        )
    }
}

@Composable
private fun SegmentItem(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .dpadFocusRing(RoundedCornerShape(18.dp), washTint = MaterialTheme.colorScheme.primary, onFilled = isSelected)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun validate(
    sourceType: SourceType,
    state: PlaylistViewModel.UiState,
    authMode: DispatcharrAuthMode,
): String? {
    val missing = mutableListOf<String>()
    if (state.url.isBlank()) missing += "Server URL"
    when (sourceType) {
        // Dispatcharr accepts EITHER an admin API key OR a username +
        // password -- they're alternatives, never both. Validate against
        // whichever the user has actually filled rather than forcing the
        // segmented toggle's mode: a filled API key is accepted even if the
        // control still reads "Username & Password" (and vice versa), so a
        // stuck/mis-set toggle can't demand the other set of fields. Only
        // when NEITHER credential is present do we prompt -- for the field(s)
        // of the currently-selected mode.
        SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
            val hasApiKey = state.apiKey.isNotBlank()
            val hasUserPass = state.username.isNotBlank() && state.password.isNotBlank()
            if (!hasApiKey && !hasUserPass) {
                if (authMode == DispatcharrAuthMode.ApiKey) {
                    missing += "API key"
                } else {
                    if (state.username.isBlank()) missing += "Username"
                    if (state.password.isBlank()) missing += "Password"
                }
            }
        }
        SourceType.XtreamCodes -> {
            if (state.username.isBlank()) missing += "Username"
            if (state.password.isBlank()) missing += "Password"
        }
        SourceType.M3uUrl -> Unit
    }
    return when {
        missing.isEmpty() -> null
        missing.size == 1 -> "${missing.first()} is required."
        else -> "${missing.size} fields need attention."
    }
}

/**
 * On Demand opt-in row for Add / Edit Playlist. Mirrors the iOS
 * AddServerView.vodEnabledRow (AddServerView.swift:337-348): title + help
 * text + accent-tinted Switch. Title is wrapped in a Row so the Switch
 * sits on the right with the text claiming the rest of the width. The
 * help paragraph reuses the bodySmall + onSurfaceVariant pair the other
 * form descriptions use.
 */
@Composable
private fun VodEnabledRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // Whole-row toggle target: on TV the bare Switch's focus state is
        // invisible, so the row carries the D-pad focus wash instead.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .dpadFocusRing(RoundedCornerShape(12.dp), washTint = MaterialTheme.colorScheme.primary)
                .toggleable(
                    value = checked,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        ) {
            Text(
                text = "Fetch On Demand from this playlist",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                ),
            )
        }
        Text(
            text = "When off, this playlist's movies and TV shows aren't loaded into On Demand. Useful if you only want Live TV from this server, or if you have a second playlist that already provides On Demand. You can change this later in Settings.",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Guide-history retention picker for Add Playlist (task #135): a compact pill
 * row (1/3/7/14/30 days) matching the form's typography, with the same
 * bodySmall help paragraph the other rows use. Each pill carries the D-pad
 * focus wash so the choice is navigable on TV.
 */
@Composable
private fun DvrDestinationRow(
    server: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Default Recording Destination",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Server (Dispatcharr)", false to "This Device").forEach { (isServer, label) ->
                val selected = isServer == server
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        // Ring before the clip so the focused scale is not
                        // cut off; white ring on the selected (filled) pill.
                        .dpadFocusRing(RoundedCornerShape(50), washTint = MaterialTheme.colorScheme.primary, onFilled = selected)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        )
                        .clickable { onSelect(isServer) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            text = "Where recordings are saved by default. Server recording requires a Dispatcharr admin account. You can change this later in Settings > DVR.",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun GuideHistoryRow(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Guide Days",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 0 = All Available (Logan 2026-09-11); legacy 30 reads as All.
            listOf(1, 3, 7, 14, 0).forEach { days ->
                val selected = days == sanitizeGuideDays(selectedDays)
                Text(
                    text = when (days) {
                        0 -> "All"
                        1 -> "1 Day"
                        else -> "$days Days"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        // Ring before the clip so the focused scale is not
                        // cut off; white ring on the selected (filled) pill.
                        .dpadFocusRing(RoundedCornerShape(50), washTint = MaterialTheme.colorScheme.primary, onFilled = selected)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        )
                        .clickable { onSelect(days) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            text = "How many days of guide data to load, back and ahead. Dispatcharr only; other sources show what their guide carries.",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
