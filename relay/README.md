# Secure Messenger Relay

A minimal, zero-knowledge rendezvous/relay, invite-gated so only people Jason explicitly
approves can ever join. It never sees message content — everything it forwards or briefly
queues is already end-to-end encrypted ciphertext. The only things it ever writes to disk are
its own config (read-only after startup — the allowlist, port, and cert paths) and a small
invite-redemption log (`state/invites.jsonl`, append-only — see "Inviting a new device" below).
No database, no Express, no socket.io: bare `node:tls` + the `ws` package, kept small enough to
read end to end.

## What it does (and doesn't)

- Lets the two phones find each other's current reachable address so they can attempt a
  **direct** peer-to-peer connection first (works well on WiFi).
- When direct fails (common on cellular, due to carrier-grade NAT), relays already-encrypted
  blobs between the two phones live, dropping each one from memory immediately after forwarding.
- Briefly **queues** encrypted blobs in memory if the recipient is offline, bounded by count
  and total bytes per peer, so delivery is eventually guaranteed — nothing is ever written to
  disk, and queued ciphertext is explicitly zeroed (`Buffer.fill(0)`) the moment it's delivered
  or evicted.
- Enforces an **invite-gated allowlist** — this is not an open-registration service. A device
  gets in one of two ways: a hand-curated entry in `config.json` (how Jason's and Eric's original
  phones are configured), or by redeeming a single-use, admin-issued invite code over the wire
  (see "Inviting a new device" below). Anything else is dropped immediately, with no distinction
  shown between "wrong signature," "not allowlisted," and "bad/expired/used invite code."
- Logs **lifecycle only** (start/stop/fatal) — never a connection's IP, a peer id, or any
  content. See `src/logging.ts`.

## Build & run (local)

```bash
cd relay
npm install
npm run build
node dist/server.js ./config.json
```

## First-time setup on the VPS

1. **Generate a self-signed certificate with an IP SAN** (not Let's Encrypt — see the plan's
   rationale: a public CA would publish this relay's hostname to Certificate Transparency logs,
   real exposure for a server meant to be invisible plumbing for two people):

   ```bash
   openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 3650 -nodes \
     -keyout relay-key.pem -out relay-cert.pem \
     -subj "/CN=securemessenger-relay" \
     -addext "subjectAltName=IP:72.14.176.95"
   ```

   ECDSA P-256, **not** Ed25519, for this TLS certificate — confirmed on a real Android device
   (Phase 1 LAN spike) that Android's TLS stack (BoringSSL/Conscrypt) rejects an Ed25519 TLS
   certificate outright with `SSLV3_ALERT_HANDSHAKE_FAILURE` at `ClientHello`, even though plain
   Ed25519 *signing* (used below for the relay-auth key) works fine. Don't swap this back to
   `ed25519` without reconfirming on-device.

   Copy both files to `/etc/securemessenger/` on the VPS (owned/readable by the
   `securemessenger` user only).

2. **Compute the certificate's SPKI pin** — this is what gets hardcoded into both Android app
   builds via OkHttp's `CertificatePinner`, so the app only ever trusts this exact key, not any
   public CA:

   ```bash
   openssl x509 -in relay-cert.pem -pubkey -noout | \
     openssl pkey -pubin -outform der | \
     openssl dgst -sha256 -binary | \
     base64
   ```

   The result is the value that goes after `sha256/` in the app's pin configuration.

3. **Create the dedicated system user and install:**

   ```bash
   sudo useradd --system --no-create-home --shell /usr/sbin/nologin securemessenger
   sudo mkdir -p /opt/securemessenger-relay
   # copy relay/dist, relay/package.json, relay/config.json here
   sudo chown -R securemessenger:securemessenger /opt/securemessenger-relay
   ```

4. **Fill in `config.json`** from `config.json.example` — each `allowlist` entry's `peerId` is
   the hex SHA-256 of that phone's raw 32-byte Ed25519 relay-auth public key, generated during
   that phone's pairing setup (see the Android app's `RelayAuthKeyStore.kt`).

5. **Install the systemd unit:**

   ```bash
   sudo cp systemd/securemessenger-relay.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now securemessenger-relay
   ```

6. **Verify nothing is logged beyond lifecycle lines:**

   ```bash
   journalctl -u securemessenger-relay -f
   ```

   During a live test session with both phones, this should only ever show `listening on …`
   and similar lifecycle text — never an IP, a peer id, or anything resembling message content.
   If port 8443 turns out to be blocked on Eric's carrier, switch `port` to `443` in
   `config.json` and uncomment the `CAP_NET_BIND_SERVICE` lines in the systemd unit (so the
   process still runs unprivileged rather than as root).

## Inviting a new device

A device's identity (`peerId`) is derived from a keypair generated fresh on first launch and
never backed up — it changes on every reinstall. Rather than hand-editing `config.json` and
restarting the service each time someone new joins or an existing person reinstalls, create a
short-lived invite code instead; the new device redeems it live, no restart required:

```bash
sudo -u securemessenger node dist/admin-cli.js create-invite --config ./config.json --label "Sarah" --ttl-minutes 60
```

Run as `sudo -u securemessenger`, **not** as `jcook` directly — `/opt/securemessenger-relay` and
its new `state/` subdirectory are owned by the `securemessenger` system user, and the CLI's
writes need to match the live server's so neither ever hits a permission error. `--ttl-minutes`
defaults to 60; the code is printed once and isn't stored in plaintext anywhere.

Share the printed code with the person out of band (text, voice call). In the app, if voice-code
pairing shows "this device isn't recognized yet," they enter the code there — the relay verifies
it, grants that device's peerId permanent access (recorded in `state/invites.jsonl`, never in
`config.json`), and pairing continues exactly as it always has. The code is single-use; redeeming
it a second time fails the same opaque way an unrecognized device always has.

This does **not** change how Jason's and Eric's original, already-allowlisted phones connect —
their `config.json` entries are untouched by any of this, and a plain reinstall of either still
needs a fresh invite redeemed, same as anyone else.

## Known simplification (documented, not hidden)

Draining the offline queue (`queue.drain`) removes blobs from the queue and hands them to
`ws.send()` in the same step as the recipient registering. If the send fails immediately after
(the socket errors right after `register` succeeds), that blob is lost rather than re-queued.
Acceptable for a two-person MVP; worth revisiting if it ever proves to matter in practice.
