/**
 * Deliberately minimal: process lifecycle only (start/stop/fatal). NEVER log a
 * connection's IP, a peer id, a message id, or any content — that is the entire
 * point of this relay, and it is enforced by never passing those values here, not
 * by filtering them out after the fact. journald (via the systemd unit's
 * StandardOutput=journal) is the only place these lines go.
 */
export function logLifecycle(message: string): void {
  console.error(`[securemessenger-relay] ${message}`);
}
