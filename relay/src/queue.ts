/**
 * Bounded in-memory offline-delivery queue — the one piece of this relay that holds
 * message content at all, and only ever in RAM, never on disk, never for longer than
 * the process is running.
 *
 * Ciphertext is stored as a Buffer (not the base64 string straight off the wire) for
 * exactly one reason: a Buffer's bytes can be overwritten in place with `.fill(0)`
 * when a blob is evicted or delivered, where overwriting a JS string is not possible
 * at all — strings are immutable in this runtime. This mirrors the same "real
 * zeroing, not just dereferencing" requirement the Android app's key storage holds
 * itself to.
 *
 * Honest limitation, stated plainly rather than overclaimed: `.fill(0)` wipes this
 * Buffer's bytes, but it cannot reach any transient copy V8 may have made while
 * parsing the original JSON string off the socket, nor guarantee the underlying
 * memory page is never paged to disk by the OS. Same category of limitation as the
 * JVM dead-store-elimination caveat on the Android side — not perfect, but the best
 * available without a dedicated secure-memory allocator, which is overkill for a
 * two-person relay.
 */
export interface QueuedBlob {
  from: string;
  blobId: string;
  ciphertext: Buffer;
}

interface Bucket {
  blobs: QueuedBlob[];
  bytes: number;
}

export class OfflineQueue {
  private buckets = new Map<string, Bucket>();

  constructor(
    private readonly maxMessagesPerPeer: number,
    private readonly maxBytesPerPeer: number,
  ) {}

  enqueue(peerId: string, blob: QueuedBlob): void {
    const bucket = this.buckets.get(peerId) ?? { blobs: [], bytes: 0 };

    while (
      bucket.blobs.length > 0 &&
      (bucket.blobs.length >= this.maxMessagesPerPeer ||
        bucket.bytes + blob.ciphertext.length > this.maxBytesPerPeer)
    ) {
      const evicted = bucket.blobs.shift();
      if (!evicted) break;
      bucket.bytes -= evicted.ciphertext.length;
      evicted.ciphertext.fill(0);
    }

    bucket.blobs.push(blob);
    bucket.bytes += blob.ciphertext.length;
    this.buckets.set(peerId, bucket);
  }

  /**
   * Removes and returns every queued blob for peerId. The caller (connectionHandler)
   * is responsible for wiping each blob's ciphertext Buffer once it has been handed
   * to the socket — this method's job is just to stop holding them.
   */
  drain(peerId: string): QueuedBlob[] {
    const bucket = this.buckets.get(peerId);
    if (!bucket) return [];
    this.buckets.delete(peerId);
    return bucket.blobs;
  }

  drop(peerId: string, blobId: string): void {
    const bucket = this.buckets.get(peerId);
    if (!bucket) return;
    const idx = bucket.blobs.findIndex((b) => b.blobId === blobId);
    if (idx < 0) return;
    const [removed] = bucket.blobs.splice(idx, 1);
    bucket.bytes -= removed.ciphertext.length;
    removed.ciphertext.fill(0);
  }
}
