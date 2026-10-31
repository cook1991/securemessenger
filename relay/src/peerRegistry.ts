import type { WebSocket } from 'ws';
import type { Candidate } from './protocol.js';

interface PeerEntry {
  socket: WebSocket;
  candidates: Candidate[];
  lastSeen: number;
}

/**
 * Who's online right now, and how to reach them — nothing more. Pure in-memory
 * presence; there is no history here, only "right now." Entries vanish the instant
 * a socket closes.
 */
export class PeerRegistry {
  private peers = new Map<string, PeerEntry>();

  setOnline(peerId: string, socket: WebSocket): void {
    this.peers.set(peerId, { socket, candidates: [], lastSeen: Date.now() });
  }

  setOffline(peerId: string, socket: WebSocket): void {
    const entry = this.peers.get(peerId);
    if (entry && entry.socket === socket) this.peers.delete(peerId);
  }

  setCandidates(peerId: string, candidates: Candidate[]): void {
    const entry = this.peers.get(peerId);
    if (entry) {
      entry.candidates = candidates;
      entry.lastSeen = Date.now();
    }
  }

  get(peerId: string): PeerEntry | undefined {
    return this.peers.get(peerId);
  }
}
