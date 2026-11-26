package com.nightstand.photos.smart

/**
 * One per burst: photos taken in quick succession that look alike are one
 * group, and only its best member is shown. Chain rule on photos sorted by
 * capture time: a photo joins the previous one's group if it was taken within
 * [gapMs] of it AND their embeddings have cosine ≥ [minCos]. Pure; unit-tested.
 */
object BurstGrouper {
    const val GAP_MS = 3_000L
    const val MIN_COS = 0.90f

    class Item(val id: Long, val takenMs: Long, val embedding: FloatArray)

    fun group(items: List<Item>, gapMs: Long = GAP_MS, minCos: Float = MIN_COS): List<List<Item>> {
        val sorted = items.sortedWith(compareBy({ it.takenMs }, { it.id }))
        val out = mutableListOf<MutableList<Item>>()
        for (it in sorted) {
            val last = out.lastOrNull()?.last()
            if (last != null && it.takenMs - last.takenMs in 0..gapMs && Vec.dot(it.embedding, last.embedding) >= minCos) {
                out.last() += it
            } else out += mutableListOf(it)
        }
        return out
    }

    /** The member to show: highest [score]; ties → earliest. */
    fun best(group: List<Item>, score: (Item) -> Float): Item =
        group.maxWith(compareBy<Item> { score(it) }.thenByDescending { it.takenMs })
}
