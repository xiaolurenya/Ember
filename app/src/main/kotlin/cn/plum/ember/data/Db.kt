package cn.plum.ember.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地持久化：观看进度 / 播放历史 / 收藏。
 * 不引入 Room 的原因：这些表结构简单到不需要对象关系映射，手写 DAO 反而
 * 让 CI 构建链少一个 KSP 插件依赖，故障面更小。
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "ember.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE progress(
              sourceId TEXT NOT NULL,
              itemId   TEXT NOT NULL,
              positionSec INTEGER NOT NULL DEFAULT 0,
              durationSec INTEGER NOT NULL DEFAULT 0,
              watched   INTEGER NOT NULL DEFAULT 0,
              updatedAt INTEGER NOT NULL,
              PRIMARY KEY(sourceId, itemId)
            )
        """)
        db.execSQL("""
            CREATE TABLE history(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sourceId TEXT NOT NULL,
              itemId   TEXT NOT NULL,
              title    TEXT NOT NULL,
              poster   TEXT,
              positionSec INTEGER NOT NULL DEFAULT 0,
              durationSec INTEGER NOT NULL DEFAULT 0,
              playedAt INTEGER NOT NULL,
              UNIQUE(sourceId, itemId)
            )
        """)
        db.execSQL("""
            CREATE TABLE favorites(
              sourceId TEXT NOT NULL,
              itemId   TEXT NOT NULL,
              title    TEXT NOT NULL,
              poster   TEXT,
              year     INTEGER,
              addedAt  INTEGER NOT NULL,
              PRIMARY KEY(sourceId, itemId)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // 首版，后续加字段走 ALTER TABLE 而不是 DROP（避免丢用户数据）
    }

    // ---------------- 进度 ----------------
    fun saveProgress(p: PlayProgress) {
        val cv = ContentValues().apply {
            put("sourceId", p.sourceId)
            put("itemId", p.itemId)
            put("positionSec", p.positionSec)
            put("durationSec", p.durationSec)
            put("watched", if (p.watched) 1 else 0)
            put("updatedAt", p.updatedAt)
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun progressOf(sourceId: String, itemId: String): PlayProgress? {
        readableDatabase.rawQuery(
            "SELECT positionSec, durationSec, watched FROM progress WHERE sourceId=? AND itemId=?",
            arrayOf(sourceId, itemId)
        ).use { c ->
            if (c.moveToFirst()) {
                return PlayProgress(
                    sourceId, itemId,
                    c.getLong(0), c.getLong(1),
                    c.getInt(2) == 1
                )
            }
        }
        return null
    }

    fun clearProgress(sourceId: String, itemId: String) {
        writableDatabase.delete("progress", "sourceId=? AND itemId=?", arrayOf(sourceId, itemId))
    }

    // ---------------- 历史 ----------------
    fun recordHistory(sourceId: String, itemId: String, title: String, poster: String?, pos: Long, dur: Long) {
        val cv = ContentValues().apply {
            put("sourceId", sourceId); put("itemId", itemId)
            put("title", title); put("poster", poster)
            put("positionSec", pos); put("durationSec", dur)
            put("playedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun history(limit: Int = 60): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT sourceId, itemId, title, poster, positionSec, durationSec, playedAt FROM history ORDER BY playedAt DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryRow(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getLong(6)
                )
            }
        }
        return out
    }

    // ---------------- 收藏 ----------------
    fun toggleFavorite(sourceId: String, itemId: String, title: String, poster: String?, year: Int?): Boolean {
        val db = writableDatabase
        val exists = db.rawQuery(
            "SELECT 1 FROM favorites WHERE sourceId=? AND itemId=?",
            arrayOf(sourceId, itemId)
        ).use { it.moveToFirst() }
        return if (exists) {
            db.delete("favorites", "sourceId=? AND itemId=?", arrayOf(sourceId, itemId))
            false
        } else {
            val cv = ContentValues().apply {
                put("sourceId", sourceId); put("itemId", itemId)
                put("title", title); put("poster", poster); put("year", year)
                put("addedAt", System.currentTimeMillis())
            }
            db.insert("favorites", null, cv)
            true
        }
    }

    fun isFavorite(sourceId: String, itemId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE sourceId=? AND itemId=?",
            arrayOf(sourceId, itemId)
        ).use { it.moveToFirst() }

    fun favorites(): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT sourceId, itemId, title, poster, 0, 0, addedAt FROM favorites ORDER BY addedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), 0, 0, c.getLong(6))
            }
        }
        return out
    }
}

data class HistoryRow(
    val sourceId: String,
    val itemId: String,
    val title: String,
    val poster: String?,
    val positionSec: Long,
    val durationSec: Long,
    val playedAt: Long
)
