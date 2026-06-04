package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.InfoBanner
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard
import com.aeriotv.android.feature.playlist.PlaylistViewModel

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
@OptIn(ExperimentalMaterial3Api::class)
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
    // `state.sourceType` — the viewmodel sync happens in the LaunchedEffect
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Configure", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
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

        val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = vp.gutter, vertical = 12.dp),
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

            Button(
                onClick = { viewModel.loadPlaylist() },
                enabled = !state.isLoading && validation == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
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

enum class DispatcharrAuthMode { UsernamePassword, ApiKey }

@Composable
private fun DispatcharrFields(
    state: PlaylistViewModel.UiState,
    viewModel: PlaylistViewModel,
    authMode: DispatcharrAuthMode,
    onAuthModeChange: (DispatcharrAuthMode) -> Unit,
) {
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

    when (authMode) {
        DispatcharrAuthMode.ApiKey -> {
            LabeledField(label = "Admin API Key") {
                IconTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::onApiKeyChange,
                    placeholder = "Paste your admin API key",
                    leading = Icons.Filled.Key,
                    visualTransformation = PasswordVisualTransformation(),
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
                style = MaterialTheme.typography.bodySmall,
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
    LabeledField(label = "EPG URL (optional)") {
        IconTextField(
            value = state.epgUrl,
            onValueChange = viewModel::onEpgUrlChange,
            placeholder = "https://example.com/epg.xml",
            leading = Icons.Outlined.CalendarToday,
            enabled = !state.isLoading,
        )
    }
    InfoBanner(
        text = "Paste your M3U playlist URL. Works with Dispatcharr's /output/m3u, any IPTV " +
                "provider, or a direct .m3u file link.",
    )
}

/**
 * Optional LAN URL field. Pairs with the Server URL so the user can capture
 * both the public/remote and the LAN-side host in one pass during onboarding;
 * AerioTV swaps to the LAN value once the device joins a saved home SSID
 * (Settings > Network > Home WiFi). M3U sources skip this — they're already
 * URL-based and there's no auth credential reuse implied.
 */
@Composable
private fun LanUrlField(state: PlaylistViewModel.UiState, viewModel: PlaylistViewModel) {
    LabeledField(label = "LAN URL (optional)") {
        IconTextField(
            value = state.lanUrl,
            onValueChange = viewModel::onLanUrlChange,
            placeholder = "http://192.168.1.50:9191",
            leading = Icons.Outlined.Wifi,
            enabled = !state.isLoading,
        )
    }
    Text(
        text = "When you're on a saved home WiFi network, AerioTV routes through this URL " +
                "instead of the public one above. Mark a home SSID in Settings > Network " +
                "to activate the switch.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
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
private fun IconTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: ImageVector,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = aerioTextFieldKeyboardOptions(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    LabeledField(label = label) {
        IconTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            leading = Icons.Outlined.Lock,
            enabled = enabled,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun SegmentedAuthControl(
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                ),
            )
        }
        Text(
            text = "When off, this playlist's movies and TV shows aren't loaded into On Demand. Useful if you only want Live TV from this server, or if you have a second playlist that already provides On Demand. You can change this later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
