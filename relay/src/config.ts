import { readFileSync } from 'node:fs';

export interface AllowlistEntry {
  peerId: string; // hex sha256 of the device's raw 32-byte Ed25519 relay-auth public key
  label: string; // for your own reading only ("jason" / "eric") — never logged at runtime
}

export interface RelayConfig {
  port: number;
  tlsCertPath: string;
  tlsKeyPath: string;
  allowlist: AllowlistEntry[];
  queue: { maxMessagesPerPeer: number; maxBytesPerPeer: number };
}

/**
 * This is the ONLY thing this service ever reads from or writes to disk: the
 * allowlist, port, and cert paths. No message content or connection state is ever
 * persisted — see queue.ts and peerRegistry.ts, which are in-memory only.
 */
export function loadConfig(path: string): RelayConfig {
  const raw = readFileSync(path, 'utf8');
  const parsed = JSON.parse(raw) as Partial<RelayConfig>;
  if (!Array.isArray(parsed.allowlist) || parsed.allowlist.length === 0) {
    throw new Error('config.json must declare at least one allowlist entry');
  }
  if (!parsed.port || !parsed.tlsCertPath || !parsed.tlsKeyPath || !parsed.queue) {
    throw new Error('config.json missing required fields');
  }
  return parsed as RelayConfig;
}
