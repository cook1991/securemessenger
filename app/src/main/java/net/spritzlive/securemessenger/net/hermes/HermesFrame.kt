package net.spritzlive.securemessenger.net.hermes

import kotlinx.serialization.Serializable

/**
 * Wire schema for the Hermes relay link — every field nullable except the three
 * common to all frame types, since which fields are meaningful depends on [type]
 * (text/hmac only for "message", last_id only for "resume"). Deliberately a flat
 * data class rather than a sealed hierarchy: kotlinx.serialization would need a
 * custom discriminator either way, and the gateway's frames are simple enough that
 * the flat shape costs nothing in practice.
 */
@Serializable
data class HermesFrame(
    val id: String,
    val type: String,
    val ts: Long,
    val text: String? = null,
    val hmac: String? = null,
    val last_id: String? = null,
) {
    companion object {
        const val TYPE_MESSAGE = "message"
        const val TYPE_ACK = "ack"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"
        const val TYPE_RESUME = "resume"
    }
}
