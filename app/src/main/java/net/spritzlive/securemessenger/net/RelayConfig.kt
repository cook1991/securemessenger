package net.spritzlive.securemessenger.net

import net.spritzlive.securemessenger.BuildConfig

/**
 * The real deployed relay. ECDSA P-256, not Ed25519: Android's TLS stack
 * (BoringSSL/Conscrypt) rejected an Ed25519 TLS cert outright
 * (SSLV3_ALERT_HANDSHAKE_FAILURE at ClientHello) in earlier LAN testing, even though
 * plain Ed25519 *signing* (used for the relay-auth challenge) works fine off the TLS
 * path -- that finding carried into this production cert too.
 *
 * Values come from BuildConfig, generated at build time from relay-config.properties
 * (git-ignored) -- not hardcoded here, since this repo is public. See that file's
 * .example sibling and app/build.gradle.kts for why.
 */
object RelayConfig {
    const val HOST = BuildConfig.RELAY_HOST
    const val PORT = BuildConfig.RELAY_PORT
    const val PIN_SHA256_B64 = BuildConfig.RELAY_PIN_SHA256_B64
    const val CERT_PEM = BuildConfig.RELAY_CERT_PEM
}
