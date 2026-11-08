import * as crypto from 'node:crypto';
import type { AllowlistEntry } from './config.js';
import type { RegisterMessage, RedeemInviteMessage } from './protocol.js';
import { findValidInvite, isRedeemedPeer, normalizeToken } from './invites.js';

/**
 * Friend-to-friend enforcement happens here, at the server, not just in the app:
 * this is an invite-gated allowlist, not an open-registration service — a peerId
 * only ever gets in via a hand-curated config.json entry or by redeeming an
 * admin-issued invite code (see invites.ts). Anything that doesn't match either path
 * is rejected before any protocol logic runs.
 *
 * Every verification path below returns the authorized peerId on success, or null —
 * never throws, never logs which key or token was tried, and never reveals which
 * specific check failed (bad signature vs. not allowlisted vs. bad/expired/used
 * token all look identical to the caller).
 */

interface SignedNonce {
  publicKey: string;
  nonce: string;
  signature: string;
}

/** Nonce-echo check + public key decode only — no signature verification yet, so
 *  callers can reject on cheap grounds (not allowlisted, no valid invite) before
 *  ever paying for the Ed25519 verify below. Returns the raw public key bytes and
 *  the peerId derived from them, or null if the nonce/key shape is already bad. */
function decodePeerId(msg: SignedNonce, expectedNonce: string): { publicKeyBytes: Buffer; peerId: string } | null {
  if (msg.nonce !== expectedNonce) return null;

  let publicKeyBytes: Buffer;
  try {
    publicKeyBytes = Buffer.from(msg.publicKey, 'base64');
  } catch {
    return null;
  }
  if (publicKeyBytes.length !== 32) return null;

  const peerId = crypto.createHash('sha256').update(publicKeyBytes).digest('hex');
  return { publicKeyBytes, peerId };
}

/** The actual crypto check, deferred until a caller has already decided this
 *  connection is worth the cost (allowlisted, or redeeming a valid invite). */
function verifySignature(publicKeyBytes: Buffer, nonceB64: string, signatureB64: string): boolean {
  let signature: Buffer;
  try {
    signature = Buffer.from(signatureB64, 'base64');
  } catch {
    return false;
  }
  try {
    const publicKey = crypto.createPublicKey({
      key: { kty: 'OKP', crv: 'Ed25519', x: publicKeyBytes.toString('base64url') },
      format: 'jwk',
    });
    return crypto.verify(null, Buffer.from(nonceB64, 'base64'), publicKey, signature);
  } catch {
    return false;
  }
}

export function verifyRegistration(
  msg: RegisterMessage,
  expectedNonce: string,
  allowlist: AllowlistEntry[],
  statePath: string,
): string | null {
  const decoded = decodePeerId(msg, expectedNonce);
  if (!decoded) return null;
  const { publicKeyBytes, peerId } = decoded;

  const authorized = allowlist.some((e) => e.peerId === peerId) || isRedeemedPeer(statePath, peerId);
  if (!authorized) return null;

  return verifySignature(publicKeyBytes, expectedNonce, msg.signature) ? peerId : null;
}

/** On success, returns both the newly-authorized peerId and the token's hash — the
 *  caller (connectionHandler) is responsible for calling invites.recordRedemption
 *  with that hash, which this function deliberately does NOT do itself: marking a
 *  one-time code "used" must happen only once the whole handshake has actually
 *  succeeded, never before, so an unrelated failure never strands a real invite. */
export function verifyInviteRedemption(
  msg: RedeemInviteMessage,
  expectedNonce: string,
  statePath: string,
): { peerId: string; tokenHash: string } | null {
  const decoded = decodePeerId(msg, expectedNonce);
  if (!decoded) return null;
  const { publicKeyBytes, peerId } = decoded;

  const invite = findValidInvite(statePath, normalizeToken(msg.token));
  if (!invite) return null;

  return verifySignature(publicKeyBytes, expectedNonce, msg.signature) ? { peerId, tokenHash: invite.tokenHash } : null;
}
