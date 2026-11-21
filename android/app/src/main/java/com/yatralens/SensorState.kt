package com.yatralens

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Live diagnostic state. Only event type and health metadata are persisted. */
object SensorState {
    private val fields = ConcurrentHashMap<String, String>()
    @Volatile private var ocrText: String = ""
    fun set(key: String, value: Any?) { fields[key] = value?.toString() ?: "unknown" }
    fun setOcrText(value: String) { ocrText = value }
    fun getOcrText(): String = ocrText
    fun get(key: String): String = fields[key] ?: "not started"
    fun snapshot(): String = fields.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value}" }
    fun reset() { fields.clear(); ocrText = "" }
}

class EventStore(context: Context) : SQLiteOpenHelper(context, "sensor_events.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sensor_event (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, observed_at TEXT NOT NULL, elapsed_ms INTEGER NOT NULL, detail TEXT NOT NULL)")
        db.execSQL("CREATE INDEX ix_sensor_event_time ON sensor_event(observed_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(kind: String, detail: String = "") {
        writableDatabase.execSQL(
            "INSERT INTO sensor_event(kind, observed_at, elapsed_ms, detail) VALUES (?, ?, ?, ?)",
            arrayOf(kind, Instant.now().toString(), SystemClock.elapsedRealtime(), detail.take(200))
        )
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM sensor_event", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
}

