import * as crypto from 'node:crypto';
import { appendFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname } from 'node:path';

// Crockford base32 — no 0/O/1/I/L ambiguity, safe to read aloud or type by hand.
const BASE32_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const TOKEN_BYTES = 15; // 120 bits, encodes to exactly 24 base32 chars (6 groups of 4)
const GROUP_SIZE = 4;

interface CreatedEvent {
  kind: 'created';
  tokenHash: string;
  label: string;
  createdAtMs: number;
  expiresAtMs: number;
}
interface RedeemedEvent {
  kind: 'redeemed';
  tokenHash: string;
  peerId: string;
  redeemedAtMs: number;
}
type InviteEvent = CreatedEvent | RedeemedEvent;

export interface ValidInvite {
  tokenHash: string;
  label: string;
}

/** Strips any separators a human might have typed and normalizes case, before hashing. */
export function normalizeToken(input: string): string {
  return input.replace(/[^0-9A-Za-z]/g, '').toUpperCase();
}

function hashToken(normalized: string): string {
  return crypto.createHash('sha256').update(normalized).digest('hex');
}

function encodeBase32(bytes: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

function group(token: string): string {
  const parts: string[] = [];
  for (let i = 0; i < token.length; i += GROUP_SIZE) parts.push(token.slice(i, i + GROUP_SIZE));
  return parts.join('-');
}

/**
 * Reads every event out of the log, degrading to "no invites" on anything short of a
 * successful parse — a missing, empty, or corrupt state file must never prevent the
 * core allowlist-only register path from working. This is the ONLY thing that's
 * allowed to fail soft; createInvite/recordRedemption below fail loud, since those
 * run from an interactive admin session or a server that's already up.
 */
function readEvents(statePath: string): InviteEvent[] {
  let raw: string;
  try {
    raw = readFileSync(statePath, 'utf8');
  } catch {
    return [];
  }
  const events: InviteEvent[] = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try {
      events.push(JSON.parse(line) as InviteEvent);
    } catch {
      // Skip a malformed line rather than discarding the whole log over one bad write.
    }
  }
  return events;
}

function appendEvent(statePath: string, event: InviteEvent): void {
  mkdirSync(dirname(statePath), { recursive: true });
  // A single appendFileSync call is the whole safety property here: POSIX guarantees
  // this doesn't interleave with another process's append of comparable size, so the
  // admin CLI and the live server can both write this file with no locking code.
  appendFileSync(statePath, JSON.stringify(event) + '\n', 'utf8');
}

/** Generates a new invite, appends its `created` event, and returns the plaintext
 *  token — the only time it's ever available in that form; only its hash is stored. */
export function createInvite(
  statePath: string,
  label: string,
  ttlMinutes: number,
): { token: string; expiresAtMs: number } {
  const token = group(encodeBase32(crypto.randomBytes(TOKEN_BYTES)));
  const now = Date.now();
  const expiresAtMs = now + ttlMinutes * 60_000;
  appendEvent(statePath, {
    kind: 'created',
    tokenHash: hashToken(normalizeToken(token)),
    label,
    createdAtMs: now,
    expiresAtMs,
  });
  return { token, expiresAtMs };
}

/** Folds the log for an unexpired, unused invite matching this (already-normalized)
 *  token. Returns null on any ambiguity rather than guessing. */
export function findValidInvite(statePath: string, normalizedToken: string): ValidInvite | null {
  const tokenHash = hashToken(normalizedToken);
  const events = readEvents(statePath);
  const redeemedHashes = new Set(
    events.filter((e): e is RedeemedEvent => e.kind === 'redeemed').map((e) => e.tokenHash),
  );
  if (redeemedHashes.has(tokenHash)) return null;

  const now = Date.now();
  const created = events
    .filter((e): e is CreatedEvent => e.kind === 'created')
    .find((e) => e.tokenHash === tokenHash && e.expiresAtMs > now);
  if (!created) return null;
  return { tokenHash: created.tokenHash, label: created.label };
}

/** Records that a token was redeemed by a given peer — call only after the caller has
 *  independently verified the redeeming device's signature. */
export function recordRedemption(statePath: string, tokenHash: string, peerId: string): void {
  appendEvent(statePath, { kind: 'redeemed', tokenHash, peerId, redeemedAtMs: Date.now() });
}

/** True if this peerId was granted access by a past invite redemption — lets a
 *  once-invited device keep authenticating normally forever after, no token needed. */
export function isRedeemedPeer(statePath: string, peerId: string): boolean {
  return readEvents(statePath).some((e) => e.kind === 'redeemed' && e.peerId === peerId);
}
