package com.yatralens

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

/** Local source registry. A generated sentence is never an evidence source. */
class TripMemoryStore(context: Context) : SQLiteOpenHelper(context, "trip_memory.db", null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trip (
            id TEXT PRIMARY KEY, started_at TEXT NOT NULL, ended_at TEXT,
            mode TEXT NOT NULL, status TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE source (
            id TEXT PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
            kind TEXT NOT NULL, provider TEXT NOT NULL, canonical_uri TEXT, local_uri TEXT,
            source_hash TEXT, observed_at TEXT NOT NULL, captured_at TEXT,
            last_verified_at TEXT, valid_from TEXT, valid_until TEXT, title TEXT,
            excerpt TEXT, language TEXT, permission_scope TEXT NOT NULL, retention_policy TEXT NOT NULL,
            source_version TEXT, created_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX ix_source_trip_time ON source(trip_id, observed_at)")
        db.execSQL("""CREATE TABLE evidence (
            id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES source(id) ON DELETE CASCADE,
            media_kind TEXT NOT NULL, start_ms INTEGER, end_ms INTEGER,
            crop_json TEXT, excerpt TEXT, evidence_uri TEXT, created_at TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE claim (
            id TEXT PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
            subject_key TEXT NOT NULL, predicate TEXT NOT NULL, raw_value TEXT NOT NULL,
            normalized_value TEXT NOT NULL, unit TEXT, raw_quote TEXT,
            source_id TEXT NOT NULL REFERENCES source(id),
            evidence_id TEXT NOT NULL REFERENCES evidence(id),
            observed_at TEXT NOT NULL, valid_from TEXT, valid_until TEXT,
            verification_state TEXT NOT NULL, supersedes_claim_id TEXT REFERENCES claim(id),
            created_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX ix_claim_subject_predicate ON claim(trip_id, subject_key, predicate)")
        db.execSQL("""CREATE TABLE conflict (
            id TEXT PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
            conflict_type TEXT NOT NULL, material INTEGER NOT NULL, state TEXT NOT NULL,
            detected_at TEXT NOT NULL, resolved_at TEXT)""")
        db.execSQL("""CREATE TABLE conflict_claim (
            conflict_id TEXT NOT NULL REFERENCES conflict(id) ON DELETE CASCADE,
            claim_id TEXT NOT NULL REFERENCES claim(id) ON DELETE CASCADE,
            PRIMARY KEY(conflict_id, claim_id))""")
        db.execSQL("""CREATE TABLE resolution (
            id TEXT PRIMARY KEY, conflict_id TEXT NOT NULL REFERENCES conflict(id) ON DELETE CASCADE,
            chosen_claim_id TEXT REFERENCES claim(id), decision TEXT NOT NULL,
            explanation TEXT, resolved_by TEXT NOT NULL, created_at TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE trip_event (
            id TEXT PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
            title TEXT NOT NULL, starts_at TEXT, ends_at TEXT, place_name TEXT,
            latitude REAL, longitude REAL,
            source_id TEXT REFERENCES source(id), status TEXT NOT NULL)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE source ADD COLUMN language TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trip_event ADD COLUMN latitude REAL")
            db.execSQL("ALTER TABLE trip_event ADD COLUMN longitude REAL")
        }
        if (newVersion != 3) error("No migration from trip memory schema $oldVersion to $newVersion")
    }

    fun startTrip(id: String, mode: String, at: Instant = Instant.now()) {
        val values = ContentValues().apply {
            put("id", id); put("started_at", at.toString()); put("mode", mode); put("status", "ACTIVE")
        }
        writableDatabase.insertOrThrow("trip", null, values)
    }

    fun endTrip(id: String, at: Instant = Instant.now()) {
        val values = ContentValues().apply { put("ended_at", at.toString()); put("status", "ENDED") }
        writableDatabase.update("trip", values, "id=?", arrayOf(id))
    }

    data class SavedDestination(val eventId: String, val sourceId: String, val placeName: String,
                                val latitude: Double, val longitude: Double, val enteredAt: Instant)

    /** A traveller-entered booking location is explicitly unverified until its source is checked. */
    fun saveDestination(tripId: String, placeName: String, latitude: Double, longitude: Double,
                        bookingExcerpt: String): SavedDestination {
        require(placeName.isNotBlank() && bookingExcerpt.isNotBlank())
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val sourceId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val now = Instant.now()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("source", null, ContentValues().apply {
                put("id", sourceId); put("trip_id", tripId); put("kind", "user_entered_booking")
                put("provider", "Traveller entry"); put("title", placeName)
                put("excerpt", bookingExcerpt.trim()); put("observed_at", now.toString())
                put("permission_scope", "LOCAL_ONLY"); put("retention_policy", "USER_MANAGED")
                put("created_at", now.toString())
            })
            db.insertOrThrow("trip_event", null, ContentValues().apply {
                put("id", eventId); put("trip_id", tripId); put("title", "Saved destination")
                put("place_name", placeName.trim()); put("latitude", latitude)
                put("longitude", longitude); put("source_id", sourceId)
                put("status", "USER_ENTERED_UNVERIFIED")
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return SavedDestination(eventId, sourceId, placeName.trim(), latitude, longitude, now)
    }

    fun latestDestination(tripId: String): SavedDestination? = readableDatabase.rawQuery(
        """SELECT e.id, e.source_id, e.place_name, e.latitude, e.longitude, s.observed_at
            FROM trip_event e JOIN source s ON s.id=e.source_id
            WHERE e.trip_id=? AND e.latitude IS NOT NULL AND e.longitude IS NOT NULL
            ORDER BY s.observed_at DESC LIMIT 1""", arrayOf(tripId)).use { cursor ->
        if (!cursor.moveToFirst()) null else SavedDestination(
            cursor.getString(0), cursor.getString(1), cursor.getString(2),
            cursor.getDouble(3), cursor.getDouble(4), Instant.parse(cursor.getString(5)))
    }

    /** Removes an evidence item and every fact or decision derived from it in one transaction. */
    fun deleteEvidenceCascade(tripId: String, evidenceId: String): Boolean {
        val db = writableDatabase
        var deleted = false
        db.beginTransaction()
        try {
            val sourceId = db.rawQuery("""SELECT s.id FROM evidence e JOIN source s ON s.id=e.source_id
                WHERE e.id=? AND s.trip_id=?""", arrayOf(evidenceId, tripId)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (sourceId != null) {
                val conflicts = mutableListOf<String>()
                val derivedClaims = mutableListOf<String>()
                db.rawQuery("SELECT id FROM claim WHERE evidence_id=?", arrayOf(evidenceId)).use { cursor ->
                    while (cursor.moveToNext()) derivedClaims.add(cursor.getString(0))
                }
                db.rawQuery("""SELECT DISTINCT cc.conflict_id FROM conflict_claim cc
                    JOIN claim c ON c.id=cc.claim_id WHERE c.evidence_id=?""",
                    arrayOf(evidenceId)).use { cursor ->
                    while (cursor.moveToNext()) conflicts.add(cursor.getString(0))
                }
                for (conflictId in conflicts) {
                    db.delete("resolution", "conflict_id=?", arrayOf(conflictId))
                    db.delete("conflict_claim", "conflict_id=?", arrayOf(conflictId))
                    db.delete("conflict", "id=?", arrayOf(conflictId))
                }
                for (claimId in derivedClaims) db.update("claim", ContentValues().apply {
                    putNull("supersedes_claim_id")
                }, "supersedes_claim_id=?", arrayOf(claimId))
                db.delete("claim", "evidence_id=?", arrayOf(evidenceId))
                db.delete("evidence", "id=?", arrayOf(evidenceId))
                val sourceUsed = db.rawQuery("""SELECT 1 FROM evidence WHERE source_id=?
                    UNION SELECT 1 FROM claim WHERE source_id=?
                    UNION SELECT 1 FROM trip_event WHERE source_id=? LIMIT 1""",
                    arrayOf(sourceId, sourceId, sourceId)).use { it.moveToFirst() }
                if (!sourceUsed) db.delete("source", "id=?", arrayOf(sourceId))
                deleted = true
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return deleted
    }

    data class ClaimDraft(
        val tripId: String,
        val subjectKey: String,
        val predicate: String,
        val rawValue: String,
        val normalizedValue: String,
        val sourceKind: String,
        val sourceProvider: String,
        val sourceTitle: String,
        val sourceExcerpt: String?,
        val evidenceKind: String,
        val evidenceUri: String?,
        val evidenceStartMs: Long? = null,
        val evidenceEndMs: Long? = null,
        val observedAt: Instant,
        val permissionScope: String,
        val retentionPolicy: String = "USER_MANAGED",
        val validFrom: Instant? = null,
        val validUntil: Instant? = null
    )

    data class IngestedClaim(val claimId: String, val sourceId: String, val evidenceId: String, val conflictId: String?)

    /** Store a reviewed final transcript without asserting an extracted agreement. */
    fun saveTranscript(tripId: String, text: String, language: String?, durationMs: Long,
                       observedAt: Instant = Instant.now(), owner: String = "TRAVELLER"): String {
        require(text.isNotBlank() && durationMs > 0)
        require(owner in setOf("TRAVELLER", "LOCAL"))
        val db = writableDatabase
        val sourceId = UUID.randomUUID().toString()
        val evidenceId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        db.beginTransaction()
        try {
            db.insertOrThrow("source", null, ContentValues().apply {
                put("id", sourceId); put("trip_id", tripId); put("kind", "speech_transcript")
                put("provider", "Sarvam Saaras v4")
                put("title", if (owner == "LOCAL") "Local person's turn reviewed by traveller"
                    else "Traveller's reviewed spoken turn")
                put("excerpt", text); put("observed_at", observedAt.toString())
                put("language", language)
                put("captured_at", observedAt.toString()); put("permission_scope", "USER_APPROVED_TRANSCRIPT")
                put("retention_policy", "USER_MANAGED"); put("source_version", "saaras:v4")
                put("created_at", now)
            })
            db.insertOrThrow("evidence", null, ContentValues().apply {
                put("id", evidenceId); put("source_id", sourceId); put("media_kind", "text")
                put("start_ms", 0); put("end_ms", durationMs); put("excerpt", text)
                put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return evidenceId
    }

    /** Retain reviewed OCR text and its source fingerprint, never the raw capture by default. */
    fun saveOcrText(tripId: String, text: String, imageSha256: String?, jobId: String?,
                    language: String = "ml-IN",
                    observedAt: Instant = Instant.now()): String {
        require(text.isNotBlank())
        require(language in setOf("en-IN", "ml-IN", "te-IN", "hi-IN"))
        val db = writableDatabase
        val sourceId = UUID.randomUUID().toString()
        val evidenceId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        db.beginTransaction()
        try {
            db.insertOrThrow("source", null, ContentValues().apply {
                put("id", sourceId); put("trip_id", tripId); put("kind", "selected_image_ocr")
                put("provider", "Sarvam Document AI"); put("source_version", "sarvam-vision-v1")
                put("title", "User-reviewed text crop"); put("excerpt", text)
                put("source_hash", imageSha256); put("canonical_uri", jobId?.let { "sarvam-job:$it" })
                put("observed_at", observedAt.toString()); put("captured_at", observedAt.toString())
                put("language", language); put("permission_scope", "USER_APPROVED_OCR_TEXT")
                put("retention_policy", "TEXT_AND_HASH_ONLY"); put("created_at", now)
            })
            db.insertOrThrow("evidence", null, ContentValues().apply {
                put("id", evidenceId); put("source_id", sourceId); put("media_kind", "ocr_text")
                put("crop_json", "{\"left\":0.1,\"top\":0.2,\"right\":0.9,\"bottom\":0.8}")
                put("excerpt", text); put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return evidenceId
    }

    /** Atomic ingestion. Every saved claim has an actual source and evidence row. */
    fun ingestClaim(draft: ClaimDraft): IngestedClaim {
        require(draft.sourceExcerpt?.isNotBlank() == true || draft.evidenceUri?.isNotBlank() == true) {
            "A claim needs an excerpt or retained evidence URI"
        }
        require(draft.subjectKey.isNotBlank() && draft.predicate.isNotBlank() && draft.normalizedValue.isNotBlank())
        require(draft.validFrom == null || draft.validUntil == null || draft.validUntil > draft.validFrom) {
            "Validity end must follow validity start"
        }
        val db = writableDatabase
        val sourceId = UUID.randomUUID().toString()
        val evidenceId = UUID.randomUUID().toString()
        val claimId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        var conflictId: String? = null
        db.beginTransaction()
        try {
            db.insertOrThrow("source", null, ContentValues().apply {
                put("id", sourceId); put("trip_id", draft.tripId); put("kind", draft.sourceKind)
                put("provider", draft.sourceProvider); put("title", draft.sourceTitle)
                put("excerpt", draft.sourceExcerpt); put("local_uri", draft.evidenceUri)
                put("observed_at", draft.observedAt.toString()); put("captured_at", draft.observedAt.toString())
                put("valid_from", draft.validFrom?.toString()); put("valid_until", draft.validUntil?.toString())
                put("permission_scope", draft.permissionScope); put("retention_policy", draft.retentionPolicy)
                put("created_at", now)
            })
            db.insertOrThrow("evidence", null, ContentValues().apply {
                put("id", evidenceId); put("source_id", sourceId); put("media_kind", draft.evidenceKind)
                put("start_ms", draft.evidenceStartMs); put("end_ms", draft.evidenceEndMs)
                put("excerpt", draft.sourceExcerpt); put("evidence_uri", draft.evidenceUri); put("created_at", now)
            })
            db.insertOrThrow("claim", null, ContentValues().apply {
                put("id", claimId); put("trip_id", draft.tripId); put("subject_key", draft.subjectKey)
                put("predicate", draft.predicate); put("raw_value", draft.rawValue)
                put("normalized_value", draft.normalizedValue); put("raw_quote", draft.sourceExcerpt)
                put("source_id", sourceId); put("evidence_id", evidenceId)
                put("observed_at", draft.observedAt.toString())
                put("valid_from", draft.validFrom?.toString()); put("valid_until", draft.validUntil?.toString())
                put("verification_state", "PROPOSED")
                put("created_at", now)
            })
            conflictId = detectConflict(db, draft.tripId, draft.subjectKey, draft.predicate,
                draft.normalizedValue, claimId, draft.validFrom, draft.validUntil, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return IngestedClaim(claimId, sourceId, evidenceId, conflictId)
    }

    /** Attach a reviewed fact to the exact saved transcript/OCR evidence, without duplicating its source. */
    fun claimFromEvidence(tripId: String, evidenceId: String, subjectKey: String,
                          predicate: String, rawValue: String, normalizedValue: String,
                          observedAt: Instant? = null, validFrom: Instant? = null,
                          validUntil: Instant? = null): IngestedClaim {
        require(subjectKey.isNotBlank() && predicate.isNotBlank() && normalizedValue.isNotBlank())
        require(validFrom == null || validUntil == null || validUntil > validFrom)
        val db = writableDatabase
        val claimId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        var result: IngestedClaim? = null
        db.beginTransaction()
        try {
            val source = db.rawQuery("""SELECT s.id, e.excerpt, s.observed_at FROM evidence e
                JOIN source s ON s.id=e.source_id WHERE e.id=? AND s.trip_id=?""",
                arrayOf(evidenceId, tripId)).use { cursor ->
                require(cursor.moveToFirst()) { "Evidence must belong to the trip" }
                Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
            require(source.second?.isNotBlank() == true) { "Reviewed text evidence is required" }
            db.insertOrThrow("claim", null, ContentValues().apply {
                put("id", claimId); put("trip_id", tripId); put("subject_key", subjectKey)
                put("predicate", predicate); put("raw_value", rawValue)
                put("normalized_value", normalizedValue); put("raw_quote", source.second)
                put("source_id", source.first); put("evidence_id", evidenceId)
                put("observed_at", (observedAt ?: Instant.parse(source.third)).toString())
                put("valid_from", validFrom?.toString()); put("valid_until", validUntil?.toString())
                put("verification_state", "PROPOSED"); put("created_at", now)
            })
            val conflictId = detectConflict(db, tripId, subjectKey, predicate, normalizedValue,
                claimId, validFrom, validUntil, now)
            result = IngestedClaim(claimId, source.first, evidenceId, conflictId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(result)
    }

    private fun detectConflict(db: SQLiteDatabase, tripId: String, subjectKey: String,
                               predicate: String, normalizedValue: String, claimId: String,
                               validFrom: Instant?, validUntil: Instant?, now: String): String? {
        if (predicate !in MATERIAL_PREDICATES) return null
        val conflicting = mutableListOf<String>()
        db.rawQuery("""SELECT id FROM claim WHERE trip_id=? AND subject_key=? AND predicate=?
            AND normalized_value<>? AND id<>?
            AND (valid_until IS NULL OR valid_until>?)
            AND (valid_from IS NULL OR valid_from<?)""", arrayOf(
            tripId, subjectKey, predicate, normalizedValue, claimId,
            validFrom?.toString() ?: "0001-01-01T00:00:00Z",
            validUntil?.toString() ?: "9999-12-31T23:59:59Z"
        )).use { cursor -> while (cursor.moveToNext()) conflicting.add(cursor.getString(0)) }
        if (conflicting.isEmpty()) return null
        val conflictId = UUID.randomUUID().toString()
        db.insertOrThrow("conflict", null, ContentValues().apply {
            put("id", conflictId); put("trip_id", tripId)
            put("conflict_type", "${predicate.uppercase()}_CONTRADICTION")
            put("material", 1); put("state", "OPEN"); put("detected_at", now)
        })
        for (id in conflicting + claimId) db.insertOrThrow("conflict_claim", null, ContentValues().apply {
            put("conflict_id", conflictId); put("claim_id", id)
        })
        return conflictId
    }

    fun resolveConflict(conflictId: String, decision: String, chosenClaimId: String?, explanation: String?) {
        require(decision in setOf("KEEP_OLD", "CONFIRM_NEW", "ASK_OPERATOR", "UNDECIDED", "CORRECT_TRANSCRIPTION"))
        if (decision in setOf("KEEP_OLD", "CONFIRM_NEW", "CORRECT_TRANSCRIPTION")) {
            require(chosenClaimId != null) { "A resolved conflict needs a selected claim" }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (chosenClaimId != null) {
                db.rawQuery("SELECT 1 FROM conflict_claim WHERE conflict_id=? AND claim_id=?",
                    arrayOf(conflictId, chosenClaimId)).use { require(it.moveToFirst()) { "Chosen claim is not part of conflict" } }
            }
            db.insertOrThrow("resolution", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("conflict_id", conflictId)
                put("chosen_claim_id", chosenClaimId); put("decision", decision)
                put("explanation", explanation); put("resolved_by", "TRAVELLER")
                put("created_at", Instant.now().toString())
            })
            if (decision !in setOf("ASK_OPERATOR", "UNDECIDED")) {
                db.update("conflict", ContentValues().apply {
                    put("state", "RESOLVED"); put("resolved_at", Instant.now().toString())
                }, "id=?", arrayOf(conflictId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun openConflictCount(tripId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM conflict WHERE trip_id=? AND state='OPEN'", arrayOf(tripId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    data class ConflictClaim(val claimId: String, val predicate: String, val value: String,
                             val sourceTitle: String, val excerpt: String, val observedAt: String)
    data class ConflictCard(val conflictId: String, val kind: String, val claims: List<ConflictClaim>)

    fun openConflicts(tripId: String): List<ConflictCard> {
        val db = readableDatabase
        val result = mutableListOf<ConflictCard>()
        db.rawQuery("SELECT id, conflict_type FROM conflict WHERE trip_id=? AND state='OPEN' ORDER BY detected_at DESC",
            arrayOf(tripId)).use { conflicts ->
            while (conflicts.moveToNext()) {
                val conflictId = conflicts.getString(0)
                val claims = mutableListOf<ConflictClaim>()
                db.rawQuery("""SELECT c.id, c.predicate, c.raw_value, s.title, e.excerpt, c.observed_at
                    FROM conflict_claim cc JOIN claim c ON c.id=cc.claim_id
                    JOIN source s ON s.id=c.source_id JOIN evidence e ON e.id=c.evidence_id
                    WHERE cc.conflict_id=? ORDER BY c.observed_at""", arrayOf(conflictId)).use { rows ->
                    while (rows.moveToNext()) claims.add(ConflictClaim(
                        rows.getString(0), rows.getString(1), rows.getString(2),
                        rows.getString(3) ?: "Untitled source", rows.getString(4) ?: "",
                        rows.getString(5)))
                }
                result.add(ConflictCard(conflictId, conflicts.getString(1), claims))
            }
        }
        return result
    }

    fun latestTripId(): String? = readableDatabase.rawQuery(
        "SELECT id FROM trip WHERE mode<>'TEST_FIXTURE' ORDER BY started_at DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    /** A process death leaves an ACTIVE row; reopening requires fresh visible user opt-in. */
    fun interruptedTripId(mode: String): String? = readableDatabase.rawQuery(
        "SELECT id FROM trip WHERE status='ACTIVE' AND mode=? ORDER BY started_at DESC LIMIT 1", arrayOf(mode)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    data class EvidenceEntry(val id: String, val observedAt: String, val title: String,
                             val provider: String, val excerpt: String)

    fun recentEvidenceItems(tripId: String): List<EvidenceEntry> = readableDatabase.rawQuery(
        """SELECT e.id, s.observed_at, s.title, s.provider, e.excerpt FROM evidence e
           JOIN source s ON s.id=e.source_id WHERE s.trip_id=?
           ORDER BY s.observed_at DESC LIMIT 20""", arrayOf(tripId)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(EvidenceEntry(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4) ?: ""))
        }
    }

    /** Read-only evidence access works without network or a running trip service. */
    fun recentEvidence(tripId: String): List<String> = readableDatabase.rawQuery(
        """SELECT s.observed_at, s.title, s.provider, e.excerpt FROM evidence e
           JOIN source s ON s.id=e.source_id WHERE s.trip_id=?
           ORDER BY s.observed_at DESC LIMIT 20""", arrayOf(tripId)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                "${cursor.getString(0)} • ${cursor.getString(1)} (${cursor.getString(2)})\n${cursor.getString(3) ?: "No text"}"
            )
        }
    }

    companion object {
        val MATERIAL_PREDICATES = setOf("boarding_time", "departure_time", "arrival_time", "price", "boarding_place", "destination")
    }
}

