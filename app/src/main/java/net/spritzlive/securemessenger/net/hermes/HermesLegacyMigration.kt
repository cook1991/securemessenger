package net.spritzlive.securemessenger.net.hermes

import net.spritzlive.securemessenger.crypto.HermesAuthStore

/**
 * TRANSITIONAL — delete together with HermesConfig and the HERMES_* BuildConfig
 * fields once the one device paired under compiled-in settings has run a build
 * containing this. Copies those settings into HermesAuthStore so that device keeps
 * its pairing after the settings leave the build.
 */
internal object HermesLegacyMigration {
    fun run(auth: HermesAuthStore) {
        if (!auth.isPaired() || auth.loadConfig() != null) return
        if (HermesConfig.HOST.isBlank()) return
        val pairPath = HermesConfig.PAIR_URL.substringAfter(HermesConfig.HOST, "/pair").substringAfter(":${HermesConfig.PORT}")
        val config = runCatching {
            HermesGatewayConfig(
                host = HermesConfig.HOST,
                port = HermesConfig.PORT,
                wsPath = HermesConfig.WS_PATH,
                pairPath = pairPath.ifBlank { "/pair" },
                pins = HermesConfig.PIN_SHA256_B64.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                ntfyHost = HermesConfig.NTFY_HOST.ifBlank { null },
                ntfyTopic = HermesConfig.NTFY_TOPIC.ifBlank { null },
                ntfyAuthToken = HermesConfig.NTFY_AUTH_TOKEN.ifBlank { null },
            ).validate()
        }.getOrNull() ?: return
        auth.saveConfig(config)
    }
}
