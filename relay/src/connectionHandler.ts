import * as crypto from 'node:crypto';
import { WebSocket } from 'ws';
import type { RawData } from 'ws';
import type { RelayConfig } from './config.js';
import type { PeerRegistry } from './peerRegistry.js';
import type { OfflineQueue } from './queue.js';
import type { BundleStore } from './bundleStore.js';
import { parseClientMessage, type ClientToServer, type ServerToClient } from './protocol.js';
import { verifyRegistration, verifyInviteRedemption } from './auth.js';
import { recordRedemption } from './invites.js';

const NONCE_BYTES = 32;
// Checked on the raw JSON text, before protocol.ts's own MAX_CIPHERTEXT_B64 even
// applies — must stay above that cap plus JSON envelope overhead, or every
// oversized-but-otherwise-valid frame dies here first with a generic "frame too
// large" instead of protocol.ts's more specific validation.
const MAX_FRAME_BYTES = 1_000_000;

export function handleConnection(
  ws: WebSocket,
  config: RelayConfig,
  registry: PeerRegistry,
  queue: OfflineQueue,
  bundles: BundleStore,
  statePath: string,
): void {
  const nonce = crypto.randomBytes(NONCE_BYTES).toString('base64');
  let registeredPeerId: string | null = null;

  send(ws, { type: 'hello', nonce });

  ws.on('message', (raw: RawData) => {
    const text = raw.toString();
    if (text.length > MAX_FRAME_BYTES) {
      send(ws, { type: 'error', message: 'frame too large' });
      ws.close();
      return;
    }

    let msg: ClientToServer;
    try {
      msg = parseClientMessage(text);
    } catch {
      send(ws, { type: 'error', message: 'malformed frame' });
      return;
    }

    // Shared tail of a successful register/redeem-invite: mark this socket online
    // under its new peerId, confirm it, and hand over anything that queued while it
    // was offline — identical regardless of which path got it here.
    const completeHandshake = (peerId: string): void => {
      registeredPeerId = peerId;
      registry.setOnline(peerId, ws);
      send(ws, { type: 'registered' });

      for (const blob of queue.drain(peerId)) {
        send(ws, {
          type: 'deliver',
          from: blob.from,
          blobId: blob.blobId,
          ciphertext: blob.ciphertext.toString('base64'),
        });
        blob.ciphertext.fill(0);
      }
    };

    switch (msg.type) {
      case 'register': {
        const peerId = verifyRegistration(msg, nonce, config.allowlist, statePath);
        if (!peerId) {
          send(ws, { type: 'error', message: 'not authorized' });
          ws.close();
          return;
        }
        completeHandshake(peerId);
        break;
      }

      case 'redeem-invite': {
        const result = verifyInviteRedemption(msg, nonce, statePath);
        if (!result) {
          send(ws, { type: 'error', message: 'not authorized' });
          ws.close();
          return;
        }
        // Only now, with the whole handshake having actually succeeded, does the
        // one-time code get burned — see verifyInviteRedemption's own doc comment.
        recordRedemption(statePath, result.tokenHash, result.peerId);
        completeHandshake(result.peerId);
        break;
      }

      case 'announce': {
        if (!registeredPeerId) return;
        registry.setCandidates(registeredPeerId, msg.candidates);
        break;
      }

      case 'request-peer-address': {
        if (!registeredPeerId) return;
        const peer = registry.get(msg.peerId);
        if (peer) {
          send(ws, { type: 'peer-address', peerId: msg.peerId, candidates: peer.candidates });
        } else {
          send(ws, { type: 'peer-offline', peerId: msg.peerId });
        }
        break;
      }

      case 'candidate-exchange': {
        if (!registeredPeerId) return;
        const target = registry.get(msg.to);
        if (target) {
          send(target.socket, {
            type: 'candidate-exchange',
            from: registeredPeerId,
            sessionId: msg.sessionId,
            candidates: msg.candidates,
          });
        }
        break;
      }

      case 'relay-blob': {
        if (!registeredPeerId) return;
        const target = registry.get(msg.to);
        // A registry entry can outlive its socket: the TCP connection can die
        // (NAT timeout, app killed, network loss) before the server's 'close'
        // event has fired, leaving a "zombie" entry that looks online. Forwarding
        // to a non-OPEN socket would otherwise be silently swallowed by send()'s
        // catch block, dropping the message instead of queuing it — a direct
        // violation of guaranteed delivery. Checking readyState here closes that
        // window; the heartbeat sweep in server.ts bounds how long it can stay open.
        if (target && target.socket.readyState === WebSocket.OPEN) {
          send(target.socket, {
            type: 'relay-blob',
            from: registeredPeerId,
            blobId: msg.blobId,
            ciphertext: msg.ciphertext,
          });
        } else {
          queue.enqueue(msg.to, {
            from: registeredPeerId,
            blobId: msg.blobId,
            ciphertext: Buffer.from(msg.ciphertext, 'base64'),
          });
        }
        break;
      }

      case 'queue-blob': {
        if (!registeredPeerId) return;
        queue.enqueue(msg.to, {
          from: registeredPeerId,
          blobId: msg.blobId,
          ciphertext: Buffer.from(msg.ciphertext, 'base64'),
        });
        break;
      }

      case 'delivery-ack': {
        if (!registeredPeerId) return;
        const target = registry.get(msg.to);
        if (target) {
          send(target.socket, {
            type: 'delivery-ack',
            from: registeredPeerId,
            blobId: msg.blobId,
            level: msg.level,
          });
        }
        if (msg.level === 'transport') queue.drop(msg.to, msg.blobId);
        break;
      }

      case 'ping': {
        send(ws, { type: 'pong' });
        break;
      }

      case 'publish-bundle': {
        if (!registeredPeerId) return;
        bundles.publish(registeredPeerId, msg.bundleB64);
        break;
      }

      case 'fetch-bundle': {
        if (!registeredPeerId) return;
        const found = bundles.fetch(msg.peerId);
        if (found) {
          send(ws, { type: 'bundle', peerId: msg.peerId, bundleB64: found });
        } else {
          send(ws, { type: 'bundle-not-found', peerId: msg.peerId });
        }
        break;
      }
    }
  });

  ws.on('close', () => {
    if (registeredPeerId) registry.setOffline(registeredPeerId, ws);
  });

  ws.on('error', () => {
    // Never log the error object itself — it can embed socket/connection details.
  });
}

function send(ws: WebSocket, msg: ServerToClient): void {
  try {
    ws.send(JSON.stringify(msg));
  } catch {
    // Socket already closing — nothing to do.
  }
}
