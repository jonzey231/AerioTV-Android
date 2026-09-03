package com.aeriotv.android.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Channel Collections (issue #45): user-defined, named channel groupings
 * shown as Live TV filter pills. Android port of iOS ChannelCollectionsStore
 * + the pill / menu surfaces in ChannelListView + EPGGuideView. All state is
 * one AppPreferences JSON blob; every mutation is an atomic read-modify-write.
 */
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val collections: StateFlow<List<ChannelCollection>> = prefs.channelCollections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * iOS create(name:memberIDs:placement:): blank names fall back to
     * "New Collection"; the new collection starts with the pressed channel
     * already as its first member.
     */
    fun create(name: String, firstMemberId: String, placement: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list ->
                list + ChannelCollection(
                    id = UUID.randomUUID().toString(),
                    name = name.trim().ifEmpty { "New Collection" },
                    memberIds = listOf(firstMemberId),
                    placement = placement,
                )
            }
        }
    }

    fun delete(collectionId: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list -> list.filterNot { it.id == collectionId } }
        }
    }

    /** Move a collection's pill to the front or back of the pill row. */
    fun setPlacement(collectionId: String, placement: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list ->
                list.map { if (it.id == collectionId) it.copy(placement = placement) else it }
            }
        }
    }

    /** Add-or-remove the channel (the "Add to Collection" picker rows). */
    fun toggleMember(collectionId: String, channelId: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list ->
                list.map { c ->
                    if (c.id != collectionId) {
                        c
                    } else if (channelId in c.memberIds) {
                        c.copy(memberIds = c.memberIds - channelId)
                    } else {
                        c.copy(memberIds = c.memberIds + channelId)
                    }
                }
            }
        }
    }

    /** Remove the channel from one collection ("Remove from <name>"). */
    fun removeMember(collectionId: String, channelId: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list ->
                list.map { c ->
                    if (c.id == collectionId) c.copy(memberIds = c.memberIds - channelId) else c
                }
            }
        }
    }

    /** Remove the channel from every collection ("Remove from All Collections"). */
    fun removeFromAll(channelId: String) {
        viewModelScope.launch {
            prefs.updateChannelCollections { list ->
                list.map { c ->
                    if (channelId in c.memberIds) c.copy(memberIds = c.memberIds - channelId) else c
                }
            }
        }
    }
}

/**
 * Context the channel long-press menus consume to offer collection actions,
 * threaded from the host screen down to the row/cell (one param instead of
 * five). Null = the host screen doesn't surface collections.
 *
 * iOS's contextual-remove rule, as coded there: viewing collection X and the
 * channel is in X -> "Remove from <name>"; viewing NO collection and the
 * channel is in at least one -> "Remove from All Collections"; viewing X but
 * the channel is not in X -> no remove row at all.
 */
@androidx.compose.runtime.Immutable
data class CollectionsMenuContext(
    val collections: List<ChannelCollection>,
    /** Collection currently filtering Live TV (from the selectedGroup token). */
    val activeCollectionId: String?,
    /**
     * Opens the Add-to-Collection picker. `channelIndex` / `anchorMillis`
     * describe the guide cell the picker was opened from so the host can
     * restore D-pad focus there when it closes (the guide parks focus on the
     * nav pills otherwise); non-guide hosts pass -1 / 0 and ignore them.
     */
    val onOpenPicker: (
        channelId: String,
        channelName: String,
        channelIndex: Int,
        anchorMillis: Long,
    ) -> Unit,
    val onRemoveMember: (collectionId: String, channelId: String) -> Unit,
    val onRemoveFromAll: (channelId: String) -> Unit,
)

/**
 * One collection filter pill. Capsule visuals matching the Live TV group
 * pills; tap selects the collection filter (the host passes the sentinel to
 * setSelectedGroup), long-press (phone) or long-OK (TV, the same
 * KeyDown-repeat latch the multiview grid uses -- combinedClickable's
 * onLongClick is pointer-only and never fires from a remote) opens the
 * manage menu: Move to Front / Move to Back + Delete Collection.
 */
@Composable
fun CollectionPill(
    collection: ChannelCollection,
    selected: Boolean,
    isTv: Boolean,
    onSelect: () -> Unit,
    onSetPlacement: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var manageOpen by remember { mutableStateOf(false) }
    val manageGuard = rememberTvMenuGuard()
    var centerLatched by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box {
        Box(
            modifier = Modifier
                .height(if (isTv) 30.dp else 32.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary
                        // TV group pills are soft filled capsules; phone group
                        // pills are outlined FilterChips. Match each so a
                        // collection pill is indistinguishable from a group pill.
                        isTv -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        else -> Color.Transparent
                    },
                )
                .then(
                    when {
                        isTv && focused -> Modifier.border(2.dp, Color.White, CircleShape)
                        !isTv && !selected ->
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        else -> Modifier
                    },
                )
                .then(
                    if (isTv) {
                        Modifier
                            .onKeyEvent { ev ->
                                if (ev.key != Key.DirectionCenter &&
                                    ev.key != Key.Enter &&
                                    ev.key != Key.NumPadEnter
                                ) {
                                    return@onKeyEvent false
                                }
                                when (ev.type) {
                                    KeyEventType.KeyDown -> {
                                        if (ev.nativeKeyEvent.repeatCount == 0) {
                                            centerLatched = false
                                        } else if (!centerLatched) {
                                            centerLatched = true
                                            manageOpen = true
                                            manageGuard.arm()
                                        }
                                        true
                                    }
                                    KeyEventType.KeyUp -> {
                                        if (!centerLatched) onSelect()
                                        centerLatched = false
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable(interactionSource = interaction)
                    } else {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onSelect,
                            onLongClick = { manageOpen = true },
                        )
                    },
                )
                .padding(horizontal = if (isTv) 12.dp else 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = collection.name,
                style = if (isTv) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Manage menu (iOS collectionManageActions): whichever move applies
        // for the current placement, then the destructive delete.
        val atEnd = collection.placement != ChannelCollection.PLACEMENT_BEGINNING
        if (isTv) {
            if (manageOpen) {
                TvActionMenuDialog(
                    title = collection.name,
                    actions = listOf(
                        TvMenuAction(
                            if (atEnd) "Move to Front" else "Move to Back",
                        ) {
                            onSetPlacement(
                                if (atEnd) ChannelCollection.PLACEMENT_BEGINNING
                                else ChannelCollection.PLACEMENT_END,
                            )
                        },
                        TvMenuAction("Delete Collection", destructive = true) { onDelete() },
                    ),
                    guard = manageGuard,
                    onDismiss = { manageOpen = false },
                )
            }
        } else {
            DropdownMenu(
                expanded = manageOpen,
                onDismissRequest = { manageOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    text = { Text(if (atEnd) "Move to Front" else "Move to Back") },
                    onClick = {
                        manageOpen = false
                        onSetPlacement(
                            if (atEnd) ChannelCollection.PLACEMENT_BEGINNING
                            else ChannelCollection.PLACEMENT_END,
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete Collection", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        manageOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * The "Add to Collection" picker + the chained "New Collection" name dialog
 * (iOS confirmationDialog + .alert-with-TextField). Compose dialogs stack
 * cleanly, so no 0.4s asyncAfter deferral is needed here. Picking an
 * existing collection TOGGLES membership and closes (each tap dismisses,
 * matching iOS -- it is not a persistent multi-select); "New Collection"
 * swaps to the name dialog whose two commit buttons choose the pill
 * placement, creating the collection with this channel as its first member.
 */
@Composable
fun AddToCollectionFlow(
    channelId: String,
    channelName: String,
    isTv: Boolean,
    collections: List<ChannelCollection>,
    onToggleMember: (collectionId: String, channelId: String) -> Unit,
    onCreate: (name: String, firstMemberId: String, placement: String) -> Unit,
    onClose: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val pickerGuard = rememberTvMenuGuard()

    // TvActionMenuDialog auto-dismisses BEFORE running a row's action
    // (activate = { onDismiss(); onClick() }), so a "New Collection…" click
    // can't keep this flow alive through onDismiss alone -- it would close
    // the whole flow before the name dialog composes. Rows only set state;
    // this deferred check runs on the very next recomposition: naming keeps
    // the flow (the name dialog shows), anything else really closes.
    var pendingClose by remember { mutableStateOf(false) }
    if (pendingClose && !naming) {
        SideEffect { onClose() }
        return
    }

    if (!naming) {
        if (isTv) {
            TvActionMenuDialog(
                title = "Add to Collection",
                actions = buildList {
                    collections.forEach { c ->
                        val member = channelId in c.memberIds
                        add(
                            // iOS marks membership with a literal check prefix.
                            TvMenuAction(if (member) "✓ ${c.name}" else c.name) {
                                onToggleMember(c.id, channelId)
                            },
                        )
                    }
                    add(TvMenuAction("New Collection…") { naming = true })
                },
                guard = pickerGuard,
                onDismiss = { pendingClose = true },
            )
        } else {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Add to Collection") },
                text = {
                    Column {
                        collections.forEach { c ->
                            val member = channelId in c.memberIds
                            TextButton(
                                onClick = {
                                    onToggleMember(c.id, channelId)
                                    onClose()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (member) "✓ ${c.name}" else c.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                        TextButton(
                            onClick = { naming = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "New Collection…",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onClose) { Text("Cancel") }
                },
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("New Collection") },
            text = {
                Column {
                    Text(
                        "Name the collection and choose where its pill appears " +
                            "in the Live TV row.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(newName, channelId, ChannelCollection.PLACEMENT_BEGINNING)
                        onClose()
                    },
                ) { Text("Add at Beginning") }
                TextButton(
                    onClick = {
                        onCreate(newName, channelId, ChannelCollection.PLACEMENT_END)
                        onClose()
                    },
                ) { Text("Add at End") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Cancel") }
            },
        )
    }
}
