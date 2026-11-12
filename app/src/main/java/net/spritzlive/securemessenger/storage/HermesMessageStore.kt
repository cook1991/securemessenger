package net.spritzlive.securemessenger.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class HermesMessageStatus { SENDING, SENT, ACKED, RECEIVED }

data class HermesMessage(
    val id: Long,
    val frameId: String,
    val text: String,
    val outgoing: Boolean,
    val ts: Long,
    val status: HermesMessageStatus,
)

/**
 * Plain local history for the Hermes conversation — deliberately not routed through
 * CryptoShredRepository/MessageDatabase's per-message key-wrapping: that machinery
 * exists to make Signal-session ciphertext's at-rest key destroyable independent of
 * the row, which doesn't apply here (Hermes frames are HMAC-authenticated over a
 * TLS link, not stored as app-level ciphertext in the first place). A separate
 * SQLiteOpenHelper/db file, kept fully independent of the primary contact's message
 * store so neither schema migration can ever touch the other's data.
 */
class HermesMessageStore(context: Context) :
    SQLiteOpenHelper(context, "securemessenger_hermes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hermes_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                frame_id TEXT NOT NULL UNIQUE,
                text TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_hermes_messages_frame_id ON hermes_messages(frame_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(frameId: String, text: String, outgoing: Boolean, ts: Long, status: HermesMessageStatus): Long {
        val values = ContentValues().apply {
            put("frame_id", frameId)
            put("text", text)
            put("outgoing", if (outgoing) 1 else 0)
            put("ts", ts)
            put("status", status.name)
        }
        return writableDatabase.insertOrThrow("hermes_messages", null, values)
    }

    fun updateStatusByFrameId(frameId: String, status: HermesMessageStatus) {
        val values = ContentValues().apply { put("status", status.name) }
        writableDatabase.update("hermes_messages", values, "frame_id = ?", arrayOf(frameId))
    }

    fun loadAll(): List<HermesMessage> {
        val rows = ArrayList<HermesMessage>()
        writableDatabase.query(
            "hermes_messages", null, null, null, null, null, "ts ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    HermesMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        frameId = cursor.getString(cursor.getColumnIndexOrThrow("frame_id")),
                        text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                        outgoing = cursor.getInt(cursor.getColumnIndexOrThrow("outgoing")) != 0,
                        ts = cursor.getLong(cursor.getColumnIndexOrThrow("ts")),
                        status = HermesMessageStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    ),
                )
            }
        }
        return rows
    }
}
