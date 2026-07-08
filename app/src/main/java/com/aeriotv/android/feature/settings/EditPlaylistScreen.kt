package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.tv.dpadFocusEscape
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.tvFormFieldInput

/**
 * Edit Playlist sub-screen. Mirrors iOS Edit Playlist modal: Cancel header
 * left, "Edit Playlist" title, Save header right. Three sections — Connection,
 * Authentication (with segmented control for Dispatcharr User+Pass vs API Key),
 * EPG Source (M3U only). Save calls [PlaylistViewModel.saveEdits] which reuses
 * the bootstrap load path with `existingId` so the row's UUID stays stable.
 *
 * Source type is NOT editable here — changing it would invalidate the auth
 * fields shape. iOS gates that behind a separate "Change Source Type" flow
 * (Settings > Change Playlist), which on Android maps to the existing clear+
 * re-onboard path.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditPlaylistScreen(
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlist = state.playlist
    val sourceType = remember(playlist?.sourceType) {
        SourceType.entries.firstOrNull { it.name == playlist?.sourceType } ?: SourceType.M3uUrl
    }

    var name by remember(playlist?.id) { mutableStateOf(playlist?.name.orEmpty()) }
    var url by remember(playlist?.id) { mutableStateOf(playlist?.urlString.orEmpty()) }
    var lanUrl by remember(playlist?.id) { mutableStateOf(playlist?.lanUrlString.orEmpty()) }
    var epgUrl by remember(playlist?.id) { mutableStateOf(playlist?.epgUrl.orEmpty()) }
    var apiKey by remember(playlist?.id) { mutableStateOf(playlist?.apiKey.orEmpty()) }
    var username by remember(playlist?.id) { mutableStateOf(playlist?.username.orEmpty()) }
    var password by remember(playlist?.id) { mutableStateOf(playlist?.password.orEmpty()) }
    // Per-playlist On Demand opt-in (iOS ServerConnection.vodEnabled). Default
    // true so existing rows that pre-date the column still behave as before;
    // re-seeds when the user switches between playlists in this screen.
    var vodEnabled by remember(playlist?.id) { mutableStateOf(playlist?.vodEnabled ?: true) }
    var dispatcharrMode by remember(playlist?.id) {
        mutableStateOf(
            when (sourceType) {
                SourceType.DispatcharrApiKey -> DispatcharrMode.ApiKey
                SourceType.DispatcharrUserPass -> DispatcharrMode.UsernamePassword
                else -> DispatcharrMode.ApiKey
            },
        )
    }

    val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
        sourceType == SourceType.DispatcharrUserPass
    // Selected channel-profile id (null = All Channels). Seeded from the saved
    // row; re-seeds when the edited playlist changes.
    var selectedProfileId by remember(playlist?.id) {
        mutableStateOf(playlist?.dispatcharrProfileId)
    }
    // Pull the server's channel profiles once the Dispatcharr row is loaded so
    // the picker can list them. Re-runs if the user navigates to a different
    // playlist while this screen is alive.
    LaunchedEffect(playlist?.id, isDispatcharr) {
        if (isDispatcharr) viewModel.loadDispatcharrProfiles()
    }

    val canSave = url.trim().isNotEmpty() && name.trim().isNotEmpty() && !state.isLoading
    val isTv = rememberIsTvDevice()
    val performSave = {
        viewModel.saveEdits(
            name = name,
            url = url,
            lanUrl = lanUrl,
            // Persist EPG URL for both M3uUrl and XtreamCodes:
            // M3U uses it as the only EPG source; XtreamCodes
            // treats it as an OVERRIDE of the server's
            // xmltv.php (audit #19, for richer category tags
            // from third-party XMLTV providers). Dispatcharr
            // sources own their EPG via the API, so this
            // field doesn't apply there.
            epgUrl = when (sourceType) {
                SourceType.M3uUrl, SourceType.XtreamCodes -> epgUrl
                else -> null
            },
            apiKey = when {
                sourceType == SourceType.DispatcharrApiKey -> apiKey
                sourceType == SourceType.DispatcharrUserPass &&
                    dispatcharrMode == DispatcharrMode.ApiKey -> apiKey
                else -> null
            },
            username = when (sourceType) {
                SourceType.DispatcharrUserPass, SourceType.XtreamCodes -> username
                else -> null
            },
            password = when (sourceType) {
                SourceType.DispatcharrUserPass, SourceType.XtreamCodes -> password
                else -> null
            },
            dispatcharrProfileId = if (isDispatcharr) selectedProfileId else null,
            vodEnabled = vodEnabled,
        )
        onBack()
    }

    TvKeyboardOnOkHost {
    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Edit Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // No Cancel/back affordance on Android TV -- the remote BACK
                // discards and pops the screen. Phones/tablets keep Cancel.
                if (!isTv) {
                    TextButton(onClick = onBack) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            actions = {
                // TV gets a Save row at the END of the form instead (a corner
                // action is off the natural D-pad path through the fields).
                if (!isTv) {
                    SettingsHeaderTextButton(
                        label = "Save",
                        enabled = canSave,
                        onClick = performSave,
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (playlist == null) {
            Text(
                "No playlist loaded",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        // TV: deadband spec stops the form's +/-1px per-frame jiggle when a
        // focused text field sits at the floating IME's top edge (see
        // TvImeNoJitterBringIntoViewSpec).
        val bringIntoViewSpec =
            if (rememberIsTvDevice()) com.aeriotv.android.ui.tv.TvImeNoJitterBringIntoViewSpec
            else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringIntoViewSpec,
        ) {
        LazyColumn(
            modifier = Modifier.adaptiveFormWidth(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // Save button at the bottom of the form stays tappable.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(header = "Connection") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = {
                                Text(
                                    when (sourceType) {
                                        SourceType.M3uUrl -> "Playlist URL"
                                        SourceType.DispatcharrApiKey,
                                        SourceType.DispatcharrUserPass -> "Server URL"
                                        SourceType.XtreamCodes -> "Server URL"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lanUrl,
                            onValueChange = { lanUrl = it },
                            label = { Text("LAN URL (optional)") },
                            placeholder = { Text("http://192.168.1.10:9191") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Used automatically whenever the server answers at this address (checked at launch, on network changes, and after edits).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Type: ${sourceType.displayName}. To switch types, use Change Playlist.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (sourceType) {
                SourceType.DispatcharrApiKey -> item {
                    Section(header = "Authentication") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                ),
                            )
                        }
                    }
                }
                SourceType.DispatcharrUserPass -> item {
                    Section(header = "Authentication") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            SegmentedToggle(
                                left = "Username & Password",
                                right = "API Key",
                                selected = dispatcharrMode,
                                onSelect = { dispatcharrMode = it },
                            )
                            Spacer(Modifier.height(10.dp))
                            if (dispatcharrMode == DispatcharrMode.UsernamePassword) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                    keyboardOptions = aerioTextFieldKeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                                    ),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                    keyboardOptions = aerioTextFieldKeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                    ),
                                )
                            } else {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API Key") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                )
                            }
                        }
                    }
                }
                SourceType.XtreamCodes -> item {
                    Section(header = "Authentication") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                ),
                            )
                        }
                    }
                }
                SourceType.M3uUrl -> { /* no auth */ }
            }

            // Per-playlist On Demand opt-in (iOS Edit Server "Fetch VOD from
            // this playlist" toggle). Surfaces only for source types that
            // actually carry VOD; M3U is live-only so the toggle would be
            // pointless. Mirrors the same row used at Add Playlist
            // (ConfigureSourceScreen.VodEnabledRow).
            if (sourceType.supportsVOD) {
                item {
                    Section(
                        header = "On Demand",
                        footer = "When off, this playlist's movies and TV shows aren't loaded into On Demand. Useful if you only want Live TV from this server, or if you have a second playlist that already provides On Demand.",
                    ) {
                        // Whole row is the focus/toggle target so D-pad focus is
                        // visible; the Switch is display-only.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusWash()
                                .toggleable(
                                    value = vodEnabled,
                                    onValueChange = { vodEnabled = it },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Fetch On Demand from this playlist",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(12.dp))
                            androidx.compose.material3.Switch(
                                checked = vodEnabled,
                                onCheckedChange = null,
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                ),
                            )
                        }
                    }
                }
            }

            if (isDispatcharr) {
                item {
                    Section(
                        header = "Channel Profile",
                        footer = "Limit this playlist to the channels in a Dispatcharr profile. " +
                            "\"All Channels\" shows everything on the server.",
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (state.profilesLoading && state.availableProfiles.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Loading profiles...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                ProfileRow(
                                    label = "All Channels",
                                    detail = null,
                                    selected = selectedProfileId == null,
                                    onClick = { selectedProfileId = null },
                                )
                                state.availableProfiles.forEach { profile ->
                                    ProfileRow(
                                        label = profile.name,
                                        detail = "${profile.channelCount} channels",
                                        selected = selectedProfileId == profile.id,
                                        onClick = { selectedProfileId = profile.id },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (sourceType == SourceType.M3uUrl || sourceType == SourceType.XtreamCodes) {
                item {
                    val footerText = if (sourceType == SourceType.M3uUrl) {
                        "Optional XMLTV URL. Leave empty if your M3U doesn't ship with a separate EPG."
                    } else {
                        "Optional override. When set, AerioTV pulls the guide from this XMLTV URL instead of the server's xmltv.php. Useful when an external provider supplies richer category tags."
                    }
                    Section(
                        header = "EPG Source",
                        footer = footerText,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = epgUrl,
                                onValueChange = { epgUrl = it },
                                label = { Text("XMLTV URL") },
                                singleLine = true,
                                placeholder = { Text("https://example.com/xmltv.xml") },
                                modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                ),
                            )
                        }
                    }
                }
            }

            // TV: Save lives at the end of the form, where the D-pad lands
            // after the last field. Phones keep the iOS-style header Save.
            if (isTv) {
                item {
                    SettingsActionRow(
                        label = "Save Changes",
                        leadingIcon = Icons.Filled.Check,
                        onClick = performSave,
                        enabled = canSave,
                        subtitle = if (canSave) null else "Name and Server URL are required",
                    )
                }
            }
        }
        }
        }
    }
    }
}

private enum class DispatcharrMode { UsernamePassword, ApiKey }

/**
 * One selectable channel-profile row in the Edit Playlist > Channel Profile
 * picker. Shows the profile name (+ optional channel count) with a trailing
 * checkmark when active, matching the app's other single-select lists (sort
 * menu, group filter).
 */
@Composable
private fun ProfileRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusWash()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Section(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        ) { content() }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SegmentedToggle(
    left: String,
    right: String,
    selected: DispatcharrMode,
    onSelect: (DispatcharrMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentChip(
            label = left,
            selected = selected == DispatcharrMode.UsernamePassword,
            onClick = { onSelect(DispatcharrMode.UsernamePassword) },
            modifier = Modifier.weight(1f),
        )
        SegmentChip(
            label = right,
            selected = selected == DispatcharrMode.ApiKey,
            onClick = { onSelect(DispatcharrMode.ApiKey) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .dpadFocusRing(shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}
