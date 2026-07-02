package com.aeriotv.android.feature.multiview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.data.M3UChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose-friendly handle around the app-scoped [MultiviewStore]. Hilt
 * doesn't expose @Singleton classes directly to composables; the standard
 * pattern is a per-screen ViewModel that holds the singleton reference and
 * forwards calls. Keeps composables stateless w.r.t. multiview lifecycle.
 */
@HiltViewModel
class MultiviewStoreHandleVm @Inject constructor(
    private val store: MultiviewStore,
) : ViewModel() {
    val selected: StateFlow<List<MultiviewTile>> get() = store.selected
    val audioFocusedIndex: StateFlow<Int> get() = store.audioFocusedIndex
    val maxTiles: Int get() = store.maxTiles
    val softLimit: Int get() = store.softLimit
    fun warningRecentlyShown(): Boolean = store.warningRecentlyShown()
    fun noteWarningShown() = store.noteWarningShown()
    fun toggle(channel: M3UChannel) = store.toggle(channel)
    fun isSelected(channel: M3UChannel): Boolean = store.isSelected(channel)
    fun addTile(tile: MultiviewTile): Boolean = store.addTile(tile)
    fun removeTile(tileId: String) = store.removeTile(tileId)
    fun setAudioFocus(index: Int) = store.setAudioFocus(index)
    fun swap(from: Int, to: Int) = store.swap(from, to)
    fun move(from: Int, to: Int) = store.move(from, to)
    fun removeAt(index: Int) = store.removeAt(index)
    fun clear() = store.clear()
    fun seedCurrent(channel: M3UChannel) = store.seedCurrent(channel)
    fun restore(snapshot: List<MultiviewTile>, audioFocus: Int) = store.restore(snapshot, audioFocus)
}

/**
 * Stateless wrapper around the Hilt-injected handle so composables only see
 * the surface they need without an extra `: MultiviewStoreHandleVm` parameter
 * each. Equivalent to iOS's `@EnvironmentObject var multiviewStore`.
 */
class MultiviewStoreHandle(
    val selected: StateFlow<List<MultiviewTile>>,
    val audioFocusedIndex: StateFlow<Int>,
    val maxTiles: Int,
    val softLimit: Int,
    val warningRecentlyShown: () -> Boolean,
    val noteWarningShown: () -> Unit,
    val toggle: (M3UChannel) -> Unit,
    val addTile: (MultiviewTile) -> Boolean,
    val removeTile: (String) -> Unit,
    val setAudioFocus: (Int) -> Unit,
    val swap: (Int, Int) -> Unit,
    val move: (Int, Int) -> Unit,
    val removeAt: (Int) -> Unit,
    val clear: () -> Unit,
    val seedCurrent: (M3UChannel) -> Unit,
    val restore: (List<MultiviewTile>, Int) -> Unit,
)

@Composable
fun rememberMultiviewStoreHandle(
    vm: MultiviewStoreHandleVm = hiltViewModel(),
): MultiviewStoreHandle = remember(vm) {
    MultiviewStoreHandle(
        selected = vm.selected,
        audioFocusedIndex = vm.audioFocusedIndex,
        maxTiles = vm.maxTiles,
        softLimit = vm.softLimit,
        warningRecentlyShown = vm::warningRecentlyShown,
        noteWarningShown = vm::noteWarningShown,
        toggle = vm::toggle,
        addTile = vm::addTile,
        removeTile = vm::removeTile,
        setAudioFocus = vm::setAudioFocus,
        swap = vm::swap,
        move = vm::move,
        removeAt = vm::removeAt,
        clear = vm::clear,
        seedCurrent = vm::seedCurrent,
        restore = vm::restore,
    )
}
