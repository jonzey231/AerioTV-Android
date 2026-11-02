package com.aeriotv.android.feature.player

/**
 * Pure state machine for the TV pick-up reorder (Switch Stream sheet): the
 * user lifts one row, moves it with Up and Down, then places it (Select) or
 * cancels (Back). No Compose and no app singletons, so it is unit tested
 * directly and can back other D-pad ordered lists later.
 *
 * [order] is the live order while lifted (it follows every move), and the
 * original order is kept for cancel.
 */
class PickUpReorder<T>(initial: List<T>) {

    /** Outcome of a place: [order] is the full new order, [changed] = from != to. */
    data class Drop<T>(val item: T, val from: Int, val to: Int, val order: List<T>) {
        val changed: Boolean get() = from != to
    }

    var order: List<T> = initial
        private set
    private var original: List<T> = initial

    /** Index the lifted item started at, or -1 when nothing is lifted. */
    var fromIndex: Int = -1
        private set

    /** Current index of the lifted item, or -1 when nothing is lifted. */
    var liftedIndex: Int = -1
        private set

    val isLifted: Boolean get() = liftedIndex >= 0
    val liftedItem: T? get() = if (isLifted) order[liftedIndex] else null

    /** Replace the resting order (server refetch). Ignored while lifted. */
    fun reset(items: List<T>) {
        if (isLifted) return
        order = items
        original = items
    }

    /** Lift the item at [index]. False when already lifted or out of range. */
    fun start(index: Int): Boolean {
        if (isLifted || index !in order.indices) return false
        original = order
        fromIndex = index
        liftedIndex = index
        return true
    }

    /** Move the lifted item by [delta] (-1 up, +1 down). False at a bound or when not lifted. */
    fun move(delta: Int): Boolean {
        if (!isLifted) return false
        val to = liftedIndex + delta
        if (to !in order.indices || to == liftedIndex) return false
        order = order.toMutableList().apply { add(to, removeAt(liftedIndex)) }
        liftedIndex = to
        return true
    }

    /** Place the lifted item. Null when nothing is lifted. */
    fun drop(): Drop<T>? {
        if (!isLifted) return null
        val result = Drop(order[liftedIndex], fromIndex, liftedIndex, order)
        original = order
        fromIndex = -1
        liftedIndex = -1
        return result
    }

    /** Cancel: restore the original order. Returns the lifted item's start index, or -1. */
    fun cancel(): Int {
        if (!isLifted) return -1
        val from = fromIndex
        order = original
        fromIndex = -1
        liftedIndex = -1
        return from
    }
}
