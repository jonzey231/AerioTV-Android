package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.core.data.db.entity.sanitizeGuideDays
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.network.dispatcharrDefaultUserAgent
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.SettingsActionRow
import com.aeriotv.android.ui.settings.SettingsInfoRow
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsTextField
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsHeaderTextButton
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
import com.aeriotv.android.feature.onboarding.SettingUpScreen
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import kotlinx.coroutines.launch

/**
 * Edit Playlist sub-screen. Mirrors Apple's Edit Playlist form field for field
 * (Features/Settings/EditServerSheet.swift): Cancel header left, "Edit
 * Playlist" title, Save header right on touch, a Save row at the end of the
 * form on TV.
 *
 * Phase 3 (Logan 2026-09-18): every field and cell is a SHARED Settings
 * component now, so the form and the rest of Settings are one surface -
 * [SettingsSection] cards, [SettingsTextField] fields (accent 2dp focus, never
 * white), [SettingsPickerRow] for Guide Days and Channel Profile (a pushed
 * sub-page on touch, inline options on TV) and a [SettingsToggleRow] for the On
 * Demand opt-in. Section order follows Apple's: Connection, Authentication,
 * EPG Source, Local Network, User-Agent, On Demand, Guide Days, Channel
 * Profile.
 *
 * Save calls [PlaylistViewModel.saveEdits] which reuses the bootstrap load path
 * with `existingId` so the row's UUID stays stable.
 *
 * Source type is NOT editable here - changing it would invalidate the auth
 * fields shape. iOS gates that behind a separate "Change Source Type" flow
 * (Settings > Change Playlist), which on Android maps to the existing clear+
 * re-onboard path.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditPlaylistScreen(
    onBack: () -> Unit,
    /** The playlist to edit, active or not. Null = the active playlist. */
    playlistId: String? = null,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Any saved playlist is editable (owner decision, Settings phase 3). The
    // active one reads the live UiState row as before; a non-active one reads
    // its stored row.
    val allPlaylists by viewModel.allPlaylists
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val playlist = if (playlistId == null || playlistId == state.playlist?.id) {
        state.playlist
    } else {
        allPlaylists.firstOrNull { it.id == playlistId }
    }
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
    // Per-playlist Dispatcharr User-Agent (Apple `customUserAgent`). Blank =
    // the app default; the field's placeholder and helper both show it.
    var userAgent by remember(playlist?.id) { mutableStateOf(playlist?.customUserAgent.orEmpty()) }
    // Per-playlist On Demand opt-in (iOS ServerConnection.vodEnabled). Default
    // true so existing rows that pre-date the column still behave as before;
    // re-seeds when the user switches between playlists in this screen.
    var vodEnabled by remember(playlist?.id) { mutableStateOf(playlist?.vodEnabled ?: true) }
    // Catch-up EPG history retention (task #135). Default 7 days; drives how
    // far back the guide keeps (and can replay) already-aired programmes.
    var epgRetentionDays by remember(playlist?.id) {
        mutableStateOf(playlist?.epgRetentionDays ?: 7)
    }
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
    // Refresh Session (Apple parity): re-mints the Direct Connect JWT pair and
    // re-reads the account's api_key. State lives here, not in the ViewModel:
    // the message is a transient acknowledgement for this screen only.
    var refreshingSession by remember(playlist?.id) { mutableStateOf(false) }
    var sessionMessage by remember(playlist?.id) { mutableStateOf<String?>(null) }
    var sessionFailed by remember(playlist?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Pull the server's channel profiles once the Dispatcharr row is loaded so
    // the picker can list them. Re-runs if the user navigates to a different
    // playlist while this screen is alive.
    LaunchedEffect(playlist?.id, isDispatcharr) {
        if (isDispatcharr) viewModel.loadDispatcharrProfiles(playlist?.id)
    }

    // Save is now AWAITED (Logan 2026-09-18: changed credentials, saved, the
    // old username was still on the detail page). The repository restores the
    // previous row when the login or the channel fetch that follows a
    // credential change fails, and this screen used to pop on the same frame
    // Save was tapped, so that rollback was invisible and read as a lost edit.
    var saving by remember(playlist?.id) { mutableStateOf(false) }
    var saveError by remember(playlist?.id) { mutableStateOf<String?>(null) }
    // Logan 2026-09-18: a successful save takes several seconds and the top-bar
    // "Saving..." label was far too subtle for it. Rather than invent another
    // progress affordance, the save reuses the staged loading screen the add
    // flow already shows ("Setting Up"), titled "Saving Changes" and driven by
    // the REAL repository stages. It covers the form, so nothing can be edited
    // mid-save and there is no Back affordance; on success the screen pops as
    // before, on failure it gets out of the way and the form shows Save Failed
    // with the typed values intact.
    val saveStage by viewModel.saveStage.collectAsStateWithLifecycle()
    val savePlan by viewModel.savePlan.collectAsStateWithLifecycle()
    // ...and ONLY when this save really has a network stage. A rename, a Guide
    // Days change or an On Demand toggle writes one row and pops, so the staged
    // screen would be a lie and a flash (Logan 2026-09-18).
    val saveHasProgress by viewModel.saveHasProgress.collectAsStateWithLifecycle()
    // Logan 2026-09-18: a 401 from the credential change left the form open
    // with the reason rendered off screen (top of the list on phone, the whole
    // form away from the TV Save row), so Save read as a dead button. The
    // message now sits next to whichever Save the platform uses, the list
    // scrolls it into view, the IME is dismissed so it cannot be covered, and
    // it clears the moment the user touches a field or re-taps Save.
    val clearSaveError = { if (saveError != null) saveError = null }
    val canSave = url.trim().isNotEmpty() && name.trim().isNotEmpty() &&
        !state.isLoading && !saving
    val isTv = rememberIsTvDevice()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val performSave = {
        saving = true
        saveError = null
        // Off with the IME before the result lands: a floating keyboard covers
        // the bottom half of the form on phones and most of it on TV.
        keyboard?.hide()
        if (!isTv) focusManager.clearFocus()
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
            // Dispatcharr credentials follow the Authentication section's
            // MODE toggle, not the type the row happened to be created with:
            // the toggle is how a Dispatcharr playlist moves between API key
            // and username/password (Apple parity), and effectiveSourceType
            // below persists the matching type.
            apiKey = when {
                isDispatcharr -> apiKey.takeIf { dispatcharrMode == DispatcharrMode.ApiKey }
                else -> null
            },
            username = when {
                isDispatcharr -> username.takeIf {
                    dispatcharrMode == DispatcharrMode.UsernamePassword
                }
                sourceType == SourceType.XtreamCodes -> username
                else -> null
            },
            password = when {
                isDispatcharr -> password.takeIf {
                    dispatcharrMode == DispatcharrMode.UsernamePassword
                }
                sourceType == SourceType.XtreamCodes -> password
                else -> null
            },
            sourceTypeOverride = if (isDispatcharr) {
                if (dispatcharrMode == DispatcharrMode.ApiKey) SourceType.DispatcharrApiKey
                else SourceType.DispatcharrUserPass
            } else null,
            dispatcharrProfileId = if (isDispatcharr) selectedProfileId else null,
            vodEnabled = vodEnabled,
            epgRetentionDays = epgRetentionDays,
            // Dispatcharr-only field; other source types never send one, so the
            // stored value is left alone rather than blanked.
            customUserAgent = if (isDispatcharr) userAgent.trim() else null,
            onResult = { failure ->
                saving = false
                saveError = failure
                if (failure == null) onBack()
            },
            playlistId = playlist?.id,
        )
    }

    // Back is ignored for the few seconds a save is in flight, on phone system
    // back and on the TV remote alike: the staged screen is not a page to leave,
    // and a failure re-enables the form by itself, so nobody gets trapped.
    androidx.activity.compose.BackHandler(enabled = saving && saveHasProgress) {}
    if (saving && saveHasProgress) {
        SettingUpScreen(
            title = "Saving Changes",
            // Before the first reported stage, show the plan's first step as the
            // one in flight. Never a hardcoded "Verifying credentials...": this
            // save may not verify anything.
            saveStage = saveStage ?: savePlan.stages.firstOrNull()
                ?: com.aeriotv.android.core.data.repository.PlaylistRepository
                    .SaveStage.LoadingChannels,
            savePlan = savePlan,
        )
        return
    }

    SettingsSubPageHost {
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
                        Text("Cancel", color = MaterialTheme.colorScheme.textAccent)
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
                style = MaterialTheme.typography.bodyMedium.subtext(),
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
        // Bring the failure into view. Phone: Save is the top-bar action, so
        // the message sits at the top of the form and the list scrolls there.
        // TV: Save is the last row, the message is the row above it, and focus
        // stays on Save so the user reads the reason and can press again.
        LaunchedEffect(saveError) {
            if (saveError == null) return@LaunchedEffect
            if (isTv) {
                val target = (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
                runCatching { listState.animateScrollToItem(target) }
            } else {
                runCatching { listState.animateScrollToItem(0) }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsFormWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(
                com.aeriotv.android.ui.settings.SettingsCardMetrics.sectionSpacing,
            ),
        ) {
            // A failed save keeps the user on the form with their typed values
            // intact and says why, instead of popping back to a detail page
            // still showing the previous credentials. On TV the same card is
            // emitted just above the Save Changes row instead (see below).
            if (!isTv) {
                saveError?.let { message ->
                    item("save-error") { SaveFailedCard(message) }
                }
            }

            item("connection") {
                SettingsSection(header = "Connection") {
                    FieldGroup {
                        SettingsTextField(
                            label = "Name",
                            value = name,
                            onValueChange = { name = it; clearSaveError() },
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingsTextField(
                            // Apple labels every source type's address field
                            // "URL" with no helper; the type is shown in the
                            // Info section at the end, as EditServerPage does.
                            label = "URL",
                            value = url,
                            onValueChange = { url = it; clearSaveError() },
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                    }
                }
            }

            when (sourceType) {
                // Apple parity: ONE Authentication section for either
                // Dispatcharr type. The mode toggle is how a Dispatcharr
                // playlist moves between username/password and an API key, so
                // it has to be here for an existing API-key playlist too - it
                // used to appear only on rows created as username/password,
                // which is why an API-key playlist showed a lone unlabeled
                // field and no way back.
                SourceType.DispatcharrApiKey,
                SourceType.DispatcharrUserPass,
                -> item("auth") {
                    SettingsSection(header = "Authentication") {
                        FieldGroup {
                            SegmentedToggle(
                                left = "Username & Password",
                                right = "API Key",
                                selected = dispatcharrMode,
                                onSelect = {
                                    dispatcharrMode = it
                                    clearSaveError()
                                    // A stale "Session refreshed" line must not
                                    // linger on the other mode (Apple does the
                                    // same on its picker).
                                    sessionMessage = null
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            if (dispatcharrMode == DispatcharrMode.UsernamePassword) {
                                SettingsTextField(
                                    label = "Username",
                                    value = username,
                                    onValueChange = { username = it; clearSaveError() },
                                    keyboardOptions = aerioTextFieldKeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                                    ),
                                )
                                Spacer(Modifier.height(12.dp))
                                SettingsTextField(
                                    label = "Password",
                                    value = password,
                                    onValueChange = { password = it; clearSaveError() },
                                    helper = "Use your Dispatcharr Dashboard password (System > Users > Account tab), not your Dispatcharr XC password.\n" +
                                        com.aeriotv.android.feature.onboarding.DISPATCHARR_API_KEY_REQUIRED_NOTE,
                                    secure = true,
                                    secureLabel = "password",
                                    keyboardOptions = aerioTextFieldKeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                    ),
                                )
                            } else {
                                SettingsTextField(
                                    label = "API Key",
                                    value = apiKey,
                                    onValueChange = { apiKey = it; clearSaveError() },
                                    secure = true,
                                    secureLabel = "API key",
                                    keyboardOptions = aerioTextFieldKeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                    ),
                                )
                            }
                        }
                        // Refresh Session: Username & Password mode only, as on
                        // Apple. Re-runs the /api/accounts/token/ login and
                        // re-reads /api/accounts/users/me/, so an admin-rotated
                        // api_key is picked up without a re-save.
                        if (dispatcharrMode == DispatcharrMode.UsernamePassword) {
                            SettingsActionRow(
                                label = if (refreshingSession) "Refreshing..." else "Refresh Session",
                                leadingIcon = Icons.Filled.Refresh,
                                enabled = username.isNotBlank() && password.isNotBlank(),
                                running = refreshingSession,
                                subtitle = "Use if streaming or logos suddenly fail. Re-fetches the API key from your Dispatcharr account.",
                                statusLine = sessionMessage,
                                statusIsError = sessionFailed,
                                onClick = {
                                    refreshingSession = true
                                    sessionMessage = null
                                    scope.launch {
                                        val (ok, message) = viewModel.refreshDispatcharrSession(playlist?.id)
                                        sessionFailed = !ok
                                        sessionMessage = message
                                        refreshingSession = false
                                    }
                                },
                            )
                        }
                    }
                }
                SourceType.XtreamCodes -> item("auth") {
                    SettingsSection(header = "Authentication") {
                        FieldGroup {
                            SettingsTextField(
                                label = "Username",
                                value = username,
                                onValueChange = { username = it; clearSaveError() },
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                                ),
                            )
                            Spacer(Modifier.height(12.dp))
                            SettingsTextField(
                                label = "Password",
                                value = password,
                                onValueChange = { password = it; clearSaveError() },
                                secure = true,
                                secureLabel = "password",
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                ),
                            )
                        }
                    }
                }
                SourceType.M3uUrl -> { /* no auth */ }
            }

            // EPG Source. Dispatcharr owns its guide through the REST API and
            // has no XMLTV override on Android (see the report), so the section
            // is M3U / Xtream Codes only.
            if (sourceType == SourceType.M3uUrl || sourceType == SourceType.XtreamCodes) {
                item("epg") {
                    // Apple: M3U's EPG field has no helper; Xtream Codes carries
                    // this exact one.
                    val footerText = if (sourceType == SourceType.M3uUrl) {
                        null
                    } else {
                        "Optional. Adds Sports/News/Movies/Kids color tints from this XMLTV feed's category tags. Xtream Codes doesn't expose categories on its own. Leave blank to skip."
                    }
                    SettingsSection(header = "EPG Source", footer = footerText) {
                        FieldGroup {
                            SettingsTextField(
                                label = "XMLTV URL",
                                value = epgUrl,
                                onValueChange = { epgUrl = it; clearSaveError() },
                                placeholder = "https://example.com/xmltv.xml",
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                ),
                            )
                        }
                    }
                }
            }

            // Local Network gets its own section (unified settings layout,
            // 2026-07): the same grouping iOS/tvOS use, with the footer
            // explaining the automatic LAN/WAN switch.
            item("lan") {
                SettingsSection(
                    header = "Local Network",
                    footer = "Used automatically whenever the server is reachable on your local " +
                        "network. No setup needed. Leave blank to always use the main URL.",
                ) {
                    FieldGroup {
                        SettingsTextField(
                            label = "Local URL (Optional)",
                            value = lanUrl,
                            onValueChange = { lanUrl = it; clearSaveError() },
                            placeholder = "http://192.168.1.10:9191",
                            keyboardOptions = aerioTextFieldKeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                        )
                    }
                }
            }

            // User-Agent (Dispatcharr only, Apple parity). Identification, not
            // behavior: Dispatcharr's admin Stats panel attributes traffic by
            // User-Agent, so a household running several boxes can tell them
            // apart. Blank = the app default.
            if (isDispatcharr) {
                item("user-agent") {
                    SettingsSection(header = "User-Agent") {
                        FieldGroup {
                            SettingsTextField(
                                label = "User-Agent",
                                value = userAgent,
                                onValueChange = { userAgent = it; clearSaveError() },
                                placeholder = dispatcharrDefaultUserAgent,
                                helper = "Shown in Dispatcharr's admin Stats panel to identify this device. Leave blank for default: $dispatcharrDefaultUserAgent",
                                keyboardOptions = aerioTextFieldKeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                                ),
                            )
                        }
                        SettingsActionRow(
                            label = "Reset to Default",
                            leadingIcon = Icons.Filled.Undo,
                            enabled = userAgent.isNotBlank(),
                            onClick = { userAgent = ""; clearSaveError() },
                        )
                    }
                }
            }

            // Per-playlist On Demand opt-in (iOS Edit Server "Fetch On Demand
            // from this playlist" toggle). Surfaces only for source types that
            // actually carry VOD; M3U is live-only so the toggle would be
            // pointless.
            if (sourceType.supportsVOD) {
                item("on-demand") {
                    SettingsSection(
                        header = "On Demand",
                        footer = "When off, this playlist's movies and TV shows aren't loaded into On Demand. Useful if you only want Live TV from this server, or if you have a second playlist that already provides On Demand.",
                    ) {
                        SettingsToggleRow(
                            title = "Fetch On Demand from This Playlist",
                            checked = vodEnabled,
                            onCheckedChange = { vodEnabled = it; clearSaveError() },
                        )
                    }
                }
            }

            // Catch-up EPG history retention (task #135). Applies to every
            // source type: even without catch-up, retained history keeps the
            // guide browsable into the past.
            item("guide-days") {
                SettingsSection(
                    header = "Guide Days",
                    footer = "How many days of guide data to load, back and ahead. " +
                        "Dispatcharr only; other sources show what their guide carries.",
                ) {
                    SettingsPickerRow(
                        title = "Guide Days",
                        // 0 = All Available (Logan 2026-09-11); a stored 30
                        // from the old option reads back as All Available.
                        options = listOf(1, 3, 7, 14, 0).map { days ->
                            SettingsPickerOption(
                                value = days,
                                label = when (days) {
                                    0 -> "All Available"
                                    1 -> "1 Day"
                                    7 -> "7 Days (Default)"
                                    else -> "$days Days"
                                },
                            )
                        },
                        selected = sanitizeGuideDays(epgRetentionDays),
                        onSelect = { epgRetentionDays = it; clearSaveError() },
                    )
                }
            }

            if (isDispatcharr) {
                item("channel-profile") {
                    SettingsSection(
                        header = "Channel Profile",
                        footer = "Sync only the channels in a Dispatcharr Channel Profile. " +
                            "Changes apply on the next channel refresh.",
                    ) {
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
                                    style = MaterialTheme.typography.bodyMedium.subtext(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            SettingsPickerRow(
                                title = "Channel Profile",
                                options = buildList {
                                    add(SettingsPickerOption<Int?>(null, "All Channels"))
                                    state.availableProfiles.forEach { profile ->
                                        add(
                                            SettingsPickerOption(
                                                value = profile.id,
                                                label = profile.name,
                                                subtitle = "${profile.channelCount} channels",
                                            ),
                                        )
                                    }
                                },
                                selected = selectedProfileId,
                                onSelect = { selectedProfileId = it; clearSaveError() },
                            )
                        }
                    }
                }
            }

            // Apple's closing "Info" section: the read-only source type, which
            // replaced the old "To switch types, use Change Playlist" helper.
            item("info") {
                SettingsSection(header = "Info") {
                    SettingsInfoRow(
                        label = "Type",
                        value = when (sourceType) {
                            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass ->
                                "Dispatcharr Direct Connect"
                            SourceType.XtreamCodes -> "Xtream Codes"
                            SourceType.M3uUrl -> "M3U + EPG"
                        },
                    )
                }
            }

            // TV: Save lives at the end of the form, where the D-pad lands
            // after the last field. Phones keep the iOS-style header Save.
            if (isTv) {
                saveError?.let { message ->
                    item("save-error") { SaveFailedCard(message) }
                }
                item("save") {
                    SettingsActionRow(
                        label = if (saving) "Saving..." else "Save Changes",
                        leadingIcon = Icons.Filled.Check,
                        onClick = performSave,
                        enabled = canSave,
                        running = saving,
                        subtitle = when {
                            saving -> "Checking the connection with the new details"
                            canSave -> null
                            else -> "Name and URL are required"
                        },
                    )
                }
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
 * The reason a save failed, in the standard Settings card. Only the section
 * header carries colorScheme.error; the message itself is normal body text on
 * the usual card fill, per the Settings visual-restraint rule (no bright fills,
 * no outsized borders).
 */
@Composable
private fun SaveFailedCard(message: String) {
    SettingsSection(
        header = "Save Failed",
        headerColor = MaterialTheme.colorScheme.error,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/**
 * The inset well a group of [SettingsTextField]s sits in inside a section card.
 * One place, so every field in Settings keeps the same gutter and the same
 * height whichever screen it is on.
 */
@Composable
private fun FieldGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
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
