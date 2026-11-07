package net.spritzlive.securemessenger.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.spritzlive.securemessenger.data.MessageStatus

/** Raw row as stored — ciphertext, not plaintext. CryptoShredRepository does the decrypting. */
data class RawMessageRow(
    val id: Long,
    val peerId: String,
    val date: Long,
    val outgoing: Boolean,
    val status: MessageStatus,
    val keyId: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val shredded: Boolean,
)

data class KeyRow(val wrapIv: ByteArray, val wrappedKey: ByteArray)

/**
 * Two tables, no cross-table foreign-key enforcement needed (SQLite's is opt-in and
 * this app is small enough that CryptoShredRepository owns the invariant directly):
 * `messages` holds per-message ciphertext, `message_keys` holds the wrapped per-message
 * key that ciphertext needs. Deleting a `message_keys` row (MessageKeyStore.destroyKey)
 * is the actual crypto-shred; this class only stores/retrieves bytes, no crypto here.
 */
class MessageDatabase(context: Context) :
    SQLiteOpenHelper(context, "securemessenger_messages.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_id TEXT NOT NULL,
                date INTEGER NOT NULL,
                outgoing INTEGER NOT NULL,
                status TEXT NOT NULL,
                key_id TEXT NOT NULL,
                iv BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                shredded INTEGER NOT NULL DEFAULT 0,
                sent_blob_id TEXT,
                received_blob_id TEXT,
                read_ack_sent INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_peer_date ON messages(peer_id, date)")
        db.execSQL("CREATE INDEX idx_messages_sent_blob_id ON messages(sent_blob_id)")
        db.execSQL(
            """
            CREATE TABLE message_keys (
                key_id TEXT PRIMARY KEY,
                wrap_iv BLOB NOT NULL,
                wrapped_key BLOB NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First real migration this app has ever needed — plain ALTER TABLE ADD
        // COLUMN, no data rewrite, every new column nullable/defaulted, so this is
        // safe to run once against Jason's and Eric's existing message history.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sent_blob_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN received_blob_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN read_ack_sent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX idx_messages_sent_blob_id ON messages(sent_blob_id)")
        }
    }

    fun insertMessage(
        peerId: String,
        date: Long,
        outgoing: Boolean,
        status: MessageStatus,
        keyId: String,
        iv: ByteArray,
        ciphertext: ByteArray,
        receivedBlobId: String? = null,
    ): Long {
        val values = ContentValues().apply {
            put("peer_id", peerId)
            put("date", date)
            put("outgoing", if (outgoing) 1 else 0)
            put("status", status.name)
            put("key_id", keyId)
            put("iv", iv)
            put("ciphertext", ciphertext)
            put("shredded", 0)
            put("received_blob_id", receivedBlobId)
        }
        return writableDatabase.insertOrThrow("messages", null, values)
    }

    fun updateStatus(id: Long, status: MessageStatus) {
        val values = ContentValues().apply { put("status", status.name) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateSentBlobId(id: Long, blobId: String) {
        val values = ContentValues().apply { put("sent_blob_id", blobId) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    fun findIdBySentBlobId(blobId: String): Long? {
        writableDatabase.query(
            "messages", arrayOf("id"), "sent_blob_id = ?", arrayOf(blobId), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    /** Incoming messages this device has decrypted but hasn't yet told the peer a
     *  human actually opened the thread to see — see NetworkRepository.markThreadRead. */
    fun loadUnackedReceivedBlobIds(peerId: String): List<Pair<Long, String>> {
        val rows = ArrayList<Pair<Long, String>>()
        writableDatabase.query(
            "messages", arrayOf("id", "received_blob_id"),
            "peer_id = ? AND outgoing = 0 AND read_ack_sent = 0 AND received_blob_id IS NOT NULL",
            arrayOf(peerId), null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getLong(0) to cursor.getString(1))
        }
        return rows
    }

    fun markReadAckSent(id: Long) {
        val values = ContentValues().apply { put("read_ack_sent", 1) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    fun loadMessageById(id: Long): RawMessageRow? {
        writableDatabase.query(
            "messages",
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toRawMessageRow()
        }
    }

    fun loadThreadRaw(peerId: String): List<RawMessageRow> {
        val rows = ArrayList<RawMessageRow>()
        writableDatabase.query(
            "messages",
            null,
            "peer_id = ?",
            arrayOf(peerId),
            null,
            null,
            "date ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.toRawMessageRow())
        }
        return rows
    }

    /** Clears the now-useless ciphertext and marks the row a tombstone; does not touch message_keys. */
    fun markShredded(id: Long) {
        val values = ContentValues().apply {
            put("shredded", 1)
            put("ciphertext", ByteArray(0))
            put("iv", ByteArray(0))
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    fun insertKey(keyId: String, wrapIv: ByteArray, wrappedKey: ByteArray) {
        val values = ContentValues().apply {
            put("key_id", keyId)
            put("wrap_iv", wrapIv)
            put("wrapped_key", wrappedKey)
        }
        writableDatabase.insertOrThrow("message_keys", null, values)
    }

    fun loadKey(keyId: String): KeyRow? {
        writableDatabase.query(
            "message_keys",
            arrayOf("wrap_iv", "wrapped_key"),
            "key_id = ?",
            arrayOf(keyId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return KeyRow(cursor.getBlob(0), cursor.getBlob(1))
        }
    }

    /** The actual crypto-shred: once this row is gone, the matching ciphertext is unrecoverable. */
    fun deleteKey(keyId: String) {
        writableDatabase.delete("message_keys", "key_id = ?", arrayOf(keyId))
    }

    private fun android.database.Cursor.toRawMessageRow(): RawMessageRow = RawMessageRow(
        id = getLong(getColumnIndexOrThrow("id")),
        peerId = getString(getColumnIndexOrThrow("peer_id")),
        date = getLong(getColumnIndexOrThrow("date")),
        outgoing = getInt(getColumnIndexOrThrow("outgoing")) != 0,
        status = MessageStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        keyId = getString(getColumnIndexOrThrow("key_id")),
        iv = getBlob(getColumnIndexOrThrow("iv")),
        ciphertext = getBlob(getColumnIndexOrThrow("ciphertext")),
        shredded = getInt(getColumnIndexOrThrow("shredded")) != 0,
    )
}
