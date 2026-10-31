import { createServer } from 'node:https';
import { readFileSync } from 'node:fs';
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

wss.on('connection', (ws) => handleConnection(ws, config, registry, queue, bundles));

httpsServer.listen(config.port, () => {
  logLifecycle(`listening on ${config.port}`);
});

process.on('SIGTERM', () => {
  logLifecycle('SIGTERM received, shutting down');
  process.exit(0);
});

process.on('uncaughtException', (err) => {
  // Log only the error's name, deliberately — never .message or .stack, which could
  // embed data from the triggering request.
  logLifecycle(`fatal: ${err.name}`);
  process.exit(1);
});
