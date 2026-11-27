package com.nightstand.photos.smart

import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of what Smart selection learned: swipes (labels), hidden and
 * restored photos, and the personal head. Embeddings are NOT exported — they
 * are recomputed on demand (keeps the file small and private).
 *
 * Photos are identified by MediaStore id AND capture time. On the same phone
 * the id matches; on a new phone ids differ, so import falls back to the
 * capture time (DATE_TAKEN, ms), which survives copying via Google Photos /
 * Smart Switch. Photos that can't be matched are skipped and counted.
 */
object LearningBackup {
    const val KIND = "nightstand-photo-learning"
    const val VERSION = 1

    data class Result(val matched: Int, val skipped: Int)

    fun export(db: PhotoIndexDb, now: Long): String {
        val photos = JSONArray()
        db.rows("state<>${PhotoIndexDb.OK} OR pinned=1").forEach { r ->
            photos.put(JSONObject().put("id", r.mediaId).put("taken", r.dateTaken)
                .put("state", r.state).put("pinned", r.pinned))
        }
        val taken = db.rows().associate { it.mediaId to it.dateTaken }
        val labels = JSONArray()
        db.liveLabels().filter { it.mediaId >= 0 }.forEach { l ->
            labels.put(JSONObject().put("id", l.mediaId).put("taken", taken[l.mediaId] ?: 0L)
                .put("value", l.value).put("source", l.source).put("weight", l.weight.toDouble()).put("at", l.createdAt))
        }
        val head = db.loadHead()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        return JSONObject().put("kind", KIND).put("version", VERSION).put("exported_at", now)
            .put("photos", photos).put("labels", labels).put("head", head ?: JSONObject.NULL)
            .toString(1)
    }

    /** Pure: which local media id an exported photo maps to (same id+time, else unique time). */
    fun resolve(id: Long, taken: Long, localTakenById: Map<Long, Long>, localIdsByTaken: Map<Long, List<Long>>): Long? {
        if (localTakenById[id] == taken) return id
        if (taken <= 0) return null
        return localIdsByTaken[taken]?.singleOrNull()
    }

    fun import(c: Context, db: PhotoIndexDb, json: String, now: Long): Result {
        val o = JSONObject(json)
        require(o.optString("kind") == KIND) { "Not a Nightstand learning file" }
        // Local photos: id → taken, taken → ids
        val byId = HashMap<Long, Long>()
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cur ->
            while (cur.moveToNext()) byId[cur.getLong(0)] = cur.getLong(1)
        }
        val byTaken = byId.entries.groupBy({ it.value }, { it.key })
        val uriOf = { id: Long -> android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString() }
        var matched = 0; var skipped = 0
        val photos = o.optJSONArray("photos") ?: JSONArray()
        for (i in 0 until photos.length()) {
            val p = photos.getJSONObject(i)
            val local = resolve(p.getLong("id"), p.getLong("taken"), byId, byTaken)
            if (local == null) { skipped++; continue }
            db.ensure(local, uriOf(local), byId[local] ?: p.getLong("taken"))
            if (p.optBoolean("pinned")) db.setPinned(listOf(local))
            else db.setState(listOf(local), p.getInt("state"), null, now)
            matched++
        }
        val labels = o.optJSONArray("labels") ?: JSONArray()
        for (i in 0 until labels.length()) {
            val l = labels.getJSONObject(i)
            val local = resolve(l.getLong("id"), l.optLong("taken"), byId, byTaken) ?: continue
            if (db.liveLabels().any { it.mediaId == local && it.value == l.getInt("value") }) continue
            db.ensure(local, uriOf(local), byId[local] ?: l.optLong("taken"))
            db.addLabel(local, local, l.getInt("value"), l.getString("source"), l.getDouble("weight").toFloat(), l.optLong("at", now))
        }
        if (!o.isNull("head")) {
            val blob = Base64.decode(o.getString("head"), Base64.NO_WRAP)
            db.saveHead(blob, 0, now)
        }
        return Result(matched, skipped)
    }
}
