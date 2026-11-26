package com.nightstand.photos.smart

/**
 * The look-ahead queue: keeps up to [depth] photos that are already scored and
 * eligible, so the next one can be shown without waiting for the model. Pure
 * (no threads here — [SmartPhotos] drives it on its background thread).
 */
class LookAhead<C, T>(val depth: Int = 3, val maxTries: Int = 20) {
    private val ready = ArrayList<T>()
    val size get() = ready.size
    val items: List<T> get() = ready.toList()

    /**
     * Pull candidates from [next] and keep those [evaluate] accepts (non-null),
     * until [depth] are ready or [maxTries] candidates were looked at. Returns
     * how many candidates were tried.
     */
    fun fill(next: () -> C?, evaluate: (C) -> T?): Int {
        var tries = 0
        while (ready.size < depth && tries < maxTries) {
            val c = next() ?: break
            tries++
            evaluate(c)?.let { ready += it }
        }
        return tries
    }

    /** Remove and return the best ready item by [rank]; items failing [stillOk] are dropped first. */
    fun take(stillOk: (T) -> Boolean = { true }, rank: (T) -> Float): T? {
        ready.retainAll(stillOk)
        val best = ready.maxByOrNull(rank) ?: return null
        ready.remove(best)
        return best
    }

    fun removeIf(pred: (T) -> Boolean) { ready.removeAll(pred) }
    fun clear() = ready.clear()
}
