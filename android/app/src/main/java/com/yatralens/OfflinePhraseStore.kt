package com.yatralens

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

/** User-reviewed translations available without connectivity or a cloud call. */
class OfflinePhraseStore(context: Context) : SQLiteOpenHelper(context, "offline_phrases.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE phrase (
            id TEXT PRIMARY KEY, original TEXT NOT NULL, translation TEXT NOT NULL,
            source_language TEXT NOT NULL, target_language TEXT NOT NULL,
            provider TEXT NOT NULL, reviewed_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX ix_phrase_reviewed ON phrase(reviewed_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class Phrase(val id: String, val original: String, val translation: String,
                      val sourceLanguage: String, val targetLanguage: String, val reviewedAt: String)

    fun saveReviewed(original: String, translation: String, sourceLanguage: String,
                     targetLanguage: String, provider: String = "Sarvam translation, traveller reviewed"): String {
        require(original.isNotBlank() && translation.isNotBlank())
        require(sourceLanguage.isNotBlank() && targetLanguage.isNotBlank())
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("phrase", null, ContentValues().apply {
            put("id", id); put("original", original.trim()); put("translation", translation.trim())
            put("source_language", sourceLanguage); put("target_language", targetLanguage)
            put("provider", provider); put("reviewed_at", Instant.now().toString())
        })
        return id
    }

    fun recent(limit: Int = 30): List<Phrase> {
        require(limit in 1..100)
        val output = mutableListOf<Phrase>()
        readableDatabase.rawQuery("""SELECT id, original, translation, source_language,
            target_language, reviewed_at FROM phrase ORDER BY reviewed_at DESC LIMIT ?""",
            arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) output.add(Phrase(
                cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getString(5)))
        }
        return output
    }

    fun delete(id: String): Boolean = writableDatabase.delete("phrase", "id=?", arrayOf(id)) > 0
}
