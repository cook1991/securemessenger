import { createServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { WebSocketServer } from 'ws';
import { loadConfig } from './config.js';
import { PeerRegistry } from './peerRegistry.js';
import { OfflineQueue } from './queue.js';
import { BundleStore } from './bundleStore.js';
import { handleConnection } from './connectionHandler.js';
import { logLifecycle } from './logging.js';

const configPath = process.argv[2];
if (!configPath) {
  logLifecycle('fatal: usage: node dist/server.js <path-to-config.json>');
  process.exit(1);
}

const config = loadConfig(configPath);
// Invite-redemption state lives in its own subdirectory, never inside config.json —
// see invites.ts. This is the one thing the relay process writes to disk; the
// systemd unit grants ReadWritePaths to exactly this directory and nothing else.
const statePath = join(dirname(configPath), 'state', 'invites.jsonl');

// https, not a bare tls.Server: the WebSocket upgrade handshake happens at the HTTP
// layer (an HTTP Upgrade request), so `ws` needs an HTTP(S) server to attach to —
// this is the whole TLS+HTTP stack of wss://, not raw TLS sockets.
const httpsServer = createServer({
  cert: readFileSync(config.tlsCertPath),
  key: readFileSync(config.tlsKeyPath),
});
// This server exists only to host the WebSocket upgrade — refuse any plain HTTP
// request that isn't one, so it can't be used as an ordinary web server.
httpsServer.on('request', (_req, res) => {
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ server: httpsServer });
const registry = new PeerRegistry();
const queue = new OfflineQueue(config.queue.maxMessagesPerPeer, config.queue.maxBytesPerPeer);
const bundles = new BundleStore();

// A dead TCP connection (NAT idle timeout, app killed, network loss) doesn't
// always surface a 'close' event promptly — nothing reads/writes the socket
// until the next message, so it can sit registered-but-unreachable
// indefinitely. That "zombie" state is exactly what let a relay-blob get
// silently dropped instead of queued (see connectionHandler.ts). This sweep
// pings every connection every 30s and terminates any that didn't pong since
// the last sweep, bounding how long a zombie entry can survive to ~60s.
const alive = new WeakMap<import('ws').WebSocket, boolean>();
const HEARTBEAT_INTERVAL_MS = 30_000;

wss.on('connection', (ws) => {
  alive.set(ws, true);
  ws.on('pong', () => alive.set(ws, true));
  handleConnection(ws, config, registry, queue, bundles, statePath);
});

const heartbeatTimer = setInterval(() => {
  for (const ws of wss.clients) {
    if (alive.get(ws) === false) {
      ws.terminate();
      continue;
    }
    alive.set(ws, false);
    ws.ping();
  }
}, HEARTBEAT_INTERVAL_MS);

httpsServer.listen(config.port, () => {
  logLifecycle(`listening on ${config.port}`);
});

process.on('SIGTERM', () => {
  clearInterval(heartbeatTimer);
  logLifecycle('SIGTERM received, shutting down');
  process.exit(0);
});

process.on('uncaughtException', (err) => {
  // Log only the error's name, deliberately — never .message or .stack, which could
  // embed data from the triggering request.
  logLifecycle(`fatal: ${err.name}`);
  process.exit(1);
});
