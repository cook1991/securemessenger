const TTL_MS = 10 * 60 * 1000;

interface Entry {
  bundleB64: string;
  publishedAt: number;
}

/**
 * Ephemeral handoff for remote (voice-code) pairing only — the in-person QR flow
 * doesn't need this, since the QR code itself carries the bundle directly. A peer
 * publishes their own current PreKeyBundle under their own peerId; the other side
 * fetches it once by that same peerId (read aloud/texted between the two people —
 * it's public key material, not a secret; the actual trust check is the spoken
 * safety-number fingerprint comparison afterward, not this transfer).
 *
 * Consumed on first fetch (deleted immediately after) and backstopped by a TTL in
 * case nobody ever fetches it. Pure in-memory, like peerRegistry.ts/queue.ts — never
 * touches disk.
 */
export class BundleStore {
  private entries = new Map<string, Entry>();

  publish(peerId: string, bundleB64: string): void {
    this.entries.set(peerId, { bundleB64, publishedAt: Date.now() });
  }

  fetch(peerId: string): string | undefined {
    const entry = this.entries.get(peerId);
    if (!entry) return undefined;
    this.entries.delete(peerId);
    if (Date.now() - entry.publishedAt > TTL_MS) return undefined;
    return entry.bundleB64;
  }
}
