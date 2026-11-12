package net.spritzlive.securemessenger.net.hermes

import net.spritzlive.securemessenger.BuildConfig

/**
 * Hermes gateway coordinates — same reasoning as RelayConfig: real values come from
 * BuildConfig, generated at build time from hermes-config.properties (git-ignored),
 * never hardcoded here since this repo is public. See that file's .example sibling.
 *
 * Unlike the relay, the Hermes gateway is expected to hold a publicly-trusted cert
 * (it's a real host, not a connect-by-IP box), so HermesClient still uses the
 * platform default TrustManager/CA store for the actual chain-of-trust decision —
 * there is no custom SSLContext/TrustManager here, so "no certificate trust
 * overrides, ever" holds. [PIN_SHA256_B64] adds a CertificatePinner check *on top*
 * of that normal CA validation, matching RelayClient's defense-in-depth bar: a
 * pin is a further restriction (connection fails unless the chain BOTH validates
 * against a real CA AND matches this SPKI pin), never a replacement for CA
 * checking, so this is strictly additive hardening rather than a trust override.
 */
object HermesConfig {
    const val HOST = BuildConfig.HERMES_HOST
    const val PORT = BuildConfig.HERMES_PORT
    const val WS_PATH = BuildConfig.HERMES_WS_PATH
    const val PAIR_URL = BuildConfig.HERMES_PAIR_URL
    const val PIN_SHA256_B64 = BuildConfig.HERMES_PIN_SHA256_B64

    const val NTFY_HOST = BuildConfig.HERMES_NTFY_HOST
    const val NTFY_TOPIC = BuildConfig.HERMES_NTFY_TOPIC
    const val NTFY_AUTH_TOKEN = BuildConfig.HERMES_NTFY_AUTH_TOKEN

    fun wsUrl(): String = "wss://$HOST:$PORT$WS_PATH"
}
