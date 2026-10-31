import * as crypto from 'node:crypto';
import type { AllowlistEntry } from './config.js';
import type { RegisterMessage } from './protocol.js';

/**
 * Friend-to-friend enforcement happens here, at the server, not just in the app:
 * this is a closed 2-entry allowlist, not an open-registration service. Anything
 * that doesn't match an allowlisted key is rejected before any protocol logic runs.
 *
 * Returns the authorized peerId (sha256 of the public key) on success, or null —
 * never throws, never logs which key was tried.
 */
export function verifyRegistration(
  msg: RegisterMessage,
  expectedNonce: string,
  allowlist: AllowlistEntry[],
): string | null {
  if (msg.nonce !== expectedNonce) return null;

  let publicKeyBytes: Buffer;
  try {
    publicKeyBytes = Buffer.from(msg.publicKey, 'base64');
  } catch {
    return null;
  }
  if (publicKeyBytes.length !== 32) return null;

  const peerId = crypto.createHash('sha256').update(publicKeyBytes).digest('hex');
  const entry = allowlist.find((e) => e.peerId === peerId);
  if (!entry) return null;

  let signature: Buffer;
  try {
    signature = Buffer.from(msg.signature, 'base64');
  } catch {
    return null;
  }

  try {
    const publicKey = crypto.createPublicKey({
      key: { kty: 'OKP', crv: 'Ed25519', x: publicKeyBytes.toString('base64url') },
      format: 'jwk',
    });
    const verified = crypto.verify(null, Buffer.from(expectedNonce, 'base64'), publicKey, signature);
    return verified ? peerId : null;
  } catch {
    return null;
  }
}
