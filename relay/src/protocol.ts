/**
 * Wire protocol: small JSON envelopes over wss://. Every incoming frame is validated
 * here before it reaches connectionHandler.ts — never trust a parsed object blindly.
 */

export interface Candidate {
  address: string;
  port: number;
}

export interface RegisterMessage {
  type: 'register';
  publicKey: string; // base64, raw 32-byte Ed25519 relay-auth public key
  nonce: string; // base64, must echo the server's `hello` nonce
  signature: string; // base64, Ed25519 signature over the raw nonce bytes
}
export interface AnnounceMessage {
  type: 'announce';
  candidates: Candidate[];
}
export interface RequestPeerAddressMessage {
  type: 'request-peer-address';
  peerId: string;
}
export interface CandidateExchangeMessage {
  type: 'candidate-exchange';
  to: string;
  sessionId: string;
  candidates: Candidate[];
}
export interface RelayBlobMessage {
  type: 'relay-blob';
  to: string;
  blobId: string;
  ciphertext: string; // base64 — opaque to the relay, already E2E-encrypted
}
export interface QueueBlobMessage {
  type: 'queue-blob';
  to: string;
  blobId: string;
  ciphertext: string;
}
export interface DeliveryAckMessage {
  type: 'delivery-ack';
  to: string;
  blobId: string;
  // transport = socket delivered it; app = peer decrypted it; read = peer's human
  // opened the thread — distinct from 'app', which fires automatically on decrypt.
  level: 'transport' | 'app' | 'read';
}
export interface PingMessage {
  type: 'ping';
}
export interface PublishBundleMessage {
  type: 'publish-bundle';
  bundleB64: string; // opaque to the relay — the Android app's QrPairing-packed bytes, base64'd
}
export interface FetchBundleMessage {
  type: 'fetch-bundle';
  peerId: string;
}

export type ClientToServer =
  | RegisterMessage
  | AnnounceMessage
  | RequestPeerAddressMessage
  | CandidateExchangeMessage
  | RelayBlobMessage
  | QueueBlobMessage
  | DeliveryAckMessage
  | PingMessage
  | PublishBundleMessage
  | FetchBundleMessage;

export type ServerToClient =
  | { type: 'hello'; nonce: string }
  | { type: 'registered' }
  | { type: 'peer-address'; peerId: string; candidates: Candidate[] }
  | { type: 'peer-offline'; peerId: string }
  | { type: 'candidate-exchange'; from: string; sessionId: string; candidates: Candidate[] }
  | { type: 'relay-blob'; from: string; blobId: string; ciphertext: string }
  | { type: 'deliver'; from: string; blobId: string; ciphertext: string }
  | { type: 'delivery-ack'; from: string; blobId: string; level: 'transport' | 'app' | 'read' }
  | { type: 'pong' }
  | { type: 'bundle'; peerId: string; bundleB64: string }
  | { type: 'bundle-not-found'; peerId: string }
  | { type: 'error'; message: string };

// No attachments in the MVP (text-only), so this is a generous cap on the base64
// ciphertext length, not a media size budget. Revisit if attachments are ever added.
const MAX_CIPHERTEXT_B64 = 200_000;
const MAX_CANDIDATES = 8;
// The packed PreKeyBundle is ~1.9KB raw (dominated by the Kyber-1024 key, per
// QrPairing's own doc) — base64'd that's under 3KB. 20KB is generous headroom, not a
// size budget being relied on.
const MAX_BUNDLE_B64 = 20_000;

export function parseClientMessage(raw: string): ClientToServer {
  const obj: unknown = JSON.parse(raw);
  if (typeof obj !== 'object' || obj === null || typeof (obj as any).type !== 'string') {
    throw new Error('not an envelope');
  }
  const o = obj as Record<string, unknown>;
  switch (o.type) {
    case 'register':
      assertString(o.publicKey);
      assertString(o.nonce);
      assertString(o.signature);
      return o as unknown as RegisterMessage;
    case 'announce':
      assertCandidates(o.candidates);
      return o as unknown as AnnounceMessage;
    case 'request-peer-address':
      assertString(o.peerId);
      return o as unknown as RequestPeerAddressMessage;
    case 'candidate-exchange':
      assertString(o.to);
      assertString(o.sessionId);
      assertCandidates(o.candidates);
      return o as unknown as CandidateExchangeMessage;
    case 'relay-blob':
    case 'queue-blob':
      assertString(o.to);
      assertString(o.blobId);
      assertCiphertext(o.ciphertext);
      return o as unknown as RelayBlobMessage | QueueBlobMessage;
    case 'delivery-ack':
      assertString(o.to);
      assertString(o.blobId);
      if (o.level !== 'transport' && o.level !== 'app' && o.level !== 'read') throw new Error('bad level');
      return o as unknown as DeliveryAckMessage;
    case 'ping':
      return o as unknown as PingMessage;
    case 'publish-bundle':
      assertString(o.bundleB64);
      if ((o.bundleB64 as string).length > MAX_BUNDLE_B64) throw new Error('bundle too large');
      return o as unknown as PublishBundleMessage;
    case 'fetch-bundle':
      assertString(o.peerId);
      return o as unknown as FetchBundleMessage;
    default:
      throw new Error(`unknown type ${String(o.type)}`);
  }
}

function assertString(v: unknown): asserts v is string {
  if (typeof v !== 'string' || v.length === 0) throw new Error('expected non-empty string');
}

function assertCiphertext(v: unknown): asserts v is string {
  assertString(v);
  if (v.length > MAX_CIPHERTEXT_B64) throw new Error('ciphertext too large');
}

function assertCandidates(v: unknown): asserts v is Candidate[] {
  if (!Array.isArray(v) || v.length > MAX_CANDIDATES) throw new Error('bad candidates');
  for (const c of v) {
    if (
      typeof c !== 'object' ||
      c === null ||
      typeof (c as any).address !== 'string' ||
      typeof (c as any).port !== 'number'
    ) {
      throw new Error('bad candidate');
    }
  }
}
