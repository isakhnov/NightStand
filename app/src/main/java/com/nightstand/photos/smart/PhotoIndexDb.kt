package com.nightstand.photos.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * photos_smart.db — what Smart selection knows about each photo it has looked
 * at, the user's swipes (labels) and the personal head's weights. Plain
 * android.database.sqlite (no Room). Table and column names follow
 * docs/photos-intelligence/step-b/DESIGN.md §5.1 so Step B only adds tables.
 *
 * photo.state: 0 shown normally · 1 hidden by you (swipe) · 2 look-alike of a
 * hidden photo · 3 burst member that isn't the best. `pinned` = the user restored
 * it, so it is never hidden automatically again.
 */
class PhotoIndexDb(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    companion object {
        const val NAME = "photos_smart.db"
        const val VERSION = 1
        const val OK = 0; const val HIDDEN = 1; const val LOOKALIKE = 2; const val BURST = 3
        const val V_DISCARD = 0; const val V_SHOW = 1; const val V_UNDO = -2; const val V_RESET = -3
    }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("""CREATE TABLE photo (
            media_id INTEGER PRIMARY KEY, uri TEXT, date_taken INTEGER, bucket_id TEXT,
            model_version TEXT, embedding BLOB, zs_margin REAL, zs_top TEXT, head_score REAL,
            group_id INTEGER, state INTEGER NOT NULL DEFAULT 0, suppressed_by INTEGER, hidden_at INTEGER,
            pinned INTEGER NOT NULL DEFAULT 0, scored_at INTEGER, last_shown_at INTEGER,
            show_count INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX photo_taken ON photo(date_taken)")
        db.execSQL("CREATE INDEX photo_state ON photo(state)")
        db.execSQL("""CREATE TABLE dup_group (
            group_id INTEGER PRIMARY KEY, rep_media_id INTEGER NOT NULL, rep_locked INTEGER NOT NULL DEFAULT 0,
            size INTEGER NOT NULL, first_taken INTEGER, last_taken INTEGER, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE label (
            id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, media_id INTEGER NOT NULL, content_hash INTEGER,
            value INTEGER NOT NULL, source TEXT NOT NULL, weight REAL NOT NULL, split TEXT NOT NULL DEFAULT 'train',
            batch_id INTEGER, created_at INTEGER NOT NULL, superseded_by INTEGER)""")
        db.execSQL("CREATE INDEX label_live ON label(group_id) WHERE superseded_by IS NULL")
        db.execSQL("""CREATE TABLE online_head (
            base_version_id INTEGER PRIMARY KEY, weights BLOB, n_updates INTEGER, updated_at INTEGER)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // --- photo rows -------------------------------------------------------------

    class Row(
        val mediaId: Long, val uri: String?, val dateTaken: Long, val modelVersion: String?,
        val embedding: FloatArray?, val zsMargin: Float, val zsTop: String?, val headScore: Float,
        val groupId: Long?, val state: Int, val suppressedBy: Long?, val hiddenAt: Long, val pinned: Boolean,
        val lastShownAt: Long, val showCount: Int,
    )

    private val cols = "media_id, uri, date_taken, model_version, embedding, zs_margin, zs_top, head_score, " +
        "group_id, state, suppressed_by, hidden_at, pinned, last_shown_at, show_count"

    private fun row(c: android.database.Cursor) = Row(
        c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getBlob(4)?.let { Vec.fromBlob(it) },
        c.getFloat(5), c.getString(6), c.getFloat(7), if (c.isNull(8)) null else c.getLong(8), c.getInt(9),
        if (c.isNull(10)) null else c.getLong(10), c.getLong(11), c.getInt(12) != 0, c.getLong(13), c.getInt(14),
    )

    fun get(mediaId: Long): Row? = readableDatabase.rawQuery("SELECT $cols FROM photo WHERE media_id=?", arrayOf("$mediaId"))
        .use { if (it.moveToFirst()) row(it) else null }

    fun rows(where: String = "1", args: Array<String>? = null, order: String = "media_id", limit: Int = -1): List<Row> =
        readableDatabase.rawQuery("SELECT $cols FROM photo WHERE $where ORDER BY $order" + (if (limit > 0) " LIMIT $limit" else ""), args)
            .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    /** Insert or update the scoring of a photo; keeps state/labels/exposure. */
    fun saveScore(mediaId: Long, uri: String?, dateTaken: Long, bucket: String?, modelVersion: String,
                  embedding: FloatArray, zs: Float, zsTop: String, head: Float, now: Long) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("uri", uri); put("date_taken", dateTaken); put("bucket_id", bucket); put("model_version", modelVersion)
            put("embedding", Vec.toBlob(embedding)); put("zs_margin", zs); put("zs_top", zsTop)
            put("head_score", head); put("scored_at", now)
        }
        if (db.update("photo", v, "media_id=?", arrayOf("$mediaId")) == 0) {
            v.put("media_id", mediaId); db.insertOrThrow("photo", null, v)
        }
    }

    /** Make sure a row exists (a photo hidden before it was ever scored). */
    fun ensure(mediaId: Long, uri: String?, dateTaken: Long) {
        writableDatabase.insertWithOnConflict("photo", null, ContentValues().apply {
            put("media_id", mediaId); put("uri", uri); put("date_taken", dateTaken)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setState(ids: Collection<Long>, state: Int, suppressedBy: Long?, now: Long) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.update("photo", ContentValues().apply {
                    put("state", state)
                    if (suppressedBy == null) putNull("suppressed_by") else put("suppressed_by", suppressedBy)
                    if (state == OK) putNull("hidden_at") else put("hidden_at", now)
                }, "media_id=?", arrayOf("$id"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun setPinned(ids: Collection<Long>) = ids.forEach {
        writableDatabase.update("photo", ContentValues().apply { put("pinned", 1); put("state", OK); putNull("suppressed_by") },
            "media_id=?", arrayOf("$it"))
    }

    fun setHeadScore(id: Long, h: Float) =
        writableDatabase.update("photo", ContentValues().apply { put("head_score", h) }, "media_id=?", arrayOf("$id"))

    fun markShown(id: Long, now: Long) =
        writableDatabase.execSQL("UPDATE photo SET last_shown_at=?, show_count=show_count+1 WHERE media_id=?", arrayOf<Any>(now, id))

    fun idsInState(state: Int): Set<Long> =
        readableDatabase.rawQuery("SELECT media_id FROM photo WHERE state=?", arrayOf("$state"))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    /** Photos the user hid (swipes and their look-alikes): skipped even when Smart is off. */
    fun hiddenIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT media_id FROM photo WHERE state IN ($HIDDEN,$LOOKALIKE) AND pinned=0", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    /** Photos with a current embedding (for look-alike scans and burst windows). */
    fun embedded(modelVersion: String): List<Row> = rows("model_version=? AND embedding IS NOT NULL", arrayOf(modelVersion))

    // --- bursts ------------------------------------------------------------------

    fun saveGroup(members: List<Long>, rep: Long, firstTaken: Long, lastTaken: Long, now: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Reuse an existing group id of any member, so labels pointing at it stay valid.
            val existing = members.firstNotNullOfOrNull { get(it)?.groupId }
            val v = ContentValues().apply {
                put("rep_media_id", rep); put("size", members.size); put("first_taken", firstTaken)
                put("last_taken", lastTaken); put("updated_at", now)
            }
            val gid = if (existing != null && db.update("dup_group", v, "group_id=?", arrayOf("$existing")) > 0) existing
            else db.insertOrThrow("dup_group", null, v)
            members.forEach { db.execSQL("UPDATE photo SET group_id=? WHERE media_id=?", arrayOf<Any>(gid, it)) }
            db.setTransactionSuccessful()
            return gid
        } finally { db.endTransaction() }
    }

    fun groupMembers(groupId: Long): List<Long> =
        readableDatabase.rawQuery("SELECT media_id FROM photo WHERE group_id=?", arrayOf("$groupId"))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun groupRep(groupId: Long): Long? =
        readableDatabase.rawQuery("SELECT rep_media_id FROM dup_group WHERE group_id=?", arrayOf("$groupId"))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    // --- labels --------------------------------------------------------------------

    class Label(val id: Long, val mediaId: Long, val groupId: Long, val value: Int, val source: String,
                val weight: Float, val createdAt: Long)

    fun addLabel(mediaId: Long, groupId: Long, value: Int, source: String, weight: Float, now: Long): Long =
        writableDatabase.insertOrThrow("label", null, ContentValues().apply {
            put("media_id", mediaId); put("group_id", groupId); put("value", value); put("source", source)
            put("weight", weight); put("created_at", now)
        })

    fun supersede(labelIds: Collection<Long>, by: Long) = labelIds.forEach {
        writableDatabase.execSQL("UPDATE label SET superseded_by=? WHERE id=? AND superseded_by IS NULL", arrayOf<Any>(by, it))
    }

    fun liveLabels(value: Int? = null): List<Label> = readableDatabase.rawQuery(
        "SELECT id, media_id, group_id, value, source, weight, created_at FROM label WHERE superseded_by IS NULL" +
            (if (value != null) " AND value=$value" else "") + " ORDER BY id", null,
    ).use { c -> buildList { while (c.moveToNext()) add(Label(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4), c.getFloat(5), c.getLong(6))) } }

    fun liveSwipeFor(mediaId: Long): Label? = liveLabels(V_DISCARD).lastOrNull { it.mediaId == mediaId }

    // --- head ------------------------------------------------------------------------

    fun saveHead(blob: ByteArray, updates: Int, now: Long) {
        writableDatabase.insertWithOnConflict("online_head", null, ContentValues().apply {
            put("base_version_id", 0); put("weights", blob); put("n_updates", updates); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadHead(): ByteArray? = readableDatabase.rawQuery("SELECT weights FROM online_head WHERE base_version_id=0", null)
        .use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun clearHead() = writableDatabase.delete("online_head", null, null)

    // --- meta / counts ---------------------------------------------------------------

    fun meta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) = writableDatabase.insertWithOnConflict("meta", null,
        ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)

    data class Counts(val photos: Int, val scored: Int, val junk: Int, val hiddenByYou: Int, val lookAlikes: Int,
                      val bursts: Int, val swipes: Int, val groups: Int)

    fun counts(): Counts {
        val db = readableDatabase
        fun n(sql: String) = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Counts(
            photos = n("SELECT COUNT(*) FROM photo"),
            scored = n("SELECT COUNT(*) FROM photo WHERE embedding IS NOT NULL"),
            junk = n("SELECT COUNT(*) FROM photo WHERE embedding IS NOT NULL AND state=0 AND zs_margin + head_score/${Scoring.PRIOR_A} < 0"),
            hiddenByYou = n("SELECT COUNT(*) FROM photo WHERE state=$HIDDEN"),
            lookAlikes = n("SELECT COUNT(*) FROM photo WHERE state=$LOOKALIKE"),
            bursts = n("SELECT COUNT(*) FROM photo WHERE state=$BURST"),
            swipes = n("SELECT COUNT(*) FROM label WHERE superseded_by IS NULL AND value=$V_DISCARD"),
            groups = n("SELECT COUNT(*) FROM dup_group WHERE size>1"),
        )
    }

    /** Reset learning: supersede every live label, unhide swiped and look-alike photos, forget the head. Bursts stay. */
    fun resetLearning(now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val reset = addLabel(-1, -1, V_RESET, "reset", 0f, now)
            db.execSQL("UPDATE label SET superseded_by=? WHERE superseded_by IS NULL AND id<>?", arrayOf<Any>(reset, reset))
            db.execSQL("UPDATE photo SET state=0, suppressed_by=NULL, hidden_at=NULL, pinned=0 WHERE state IN ($HIDDEN,$LOOKALIKE)")
            db.execSQL("UPDATE photo SET head_score=0")
            db.delete("online_head", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
