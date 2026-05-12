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
    val selected: StateFlow<List<M3UChannel>> get() = store.selected
    val audioFocusedIndex: StateFlow<Int> get() = store.audioFocusedIndex
    val maxTiles: Int get() = store.maxTiles
    fun toggle(channel: M3UChannel) = store.toggle(channel)
    fun isSelected(channel: M3UChannel): Boolean = store.isSelected(channel)
    fun setAudioFocus(index: Int) = store.setAudioFocus(index)
    fun clear() = store.clear()
}

/**
 * Stateless wrapper around the Hilt-injected handle so composables only see
 * the surface they need without an extra `: MultiviewStoreHandleVm` parameter
 * each. Equivalent to iOS's `@EnvironmentObject var multiviewStore`.
 */
class MultiviewStoreHandle(
    val selected: StateFlow<List<M3UChannel>>,
    val audioFocusedIndex: StateFlow<Int>,
    val maxTiles: Int,
    val toggle: (M3UChannel) -> Unit,
    val setAudioFocus: (Int) -> Unit,
    val clear: () -> Unit,
)

@Composable
fun rememberMultiviewStoreHandle(
    vm: MultiviewStoreHandleVm = hiltViewModel(),
): MultiviewStoreHandle = remember(vm) {
    MultiviewStoreHandle(
        selected = vm.selected,
        audioFocusedIndex = vm.audioFocusedIndex,
        maxTiles = vm.maxTiles,
        toggle = vm::toggle,
        setAudioFocus = vm::setAudioFocus,
        clear = vm::clear,
    )
}
