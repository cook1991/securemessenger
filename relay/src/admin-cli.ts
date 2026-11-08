import { dirname, join } from 'node:path';
import { createInvite } from './invites.js';

/**
 * Run by hand over SSH, never by systemd — the admin-only half of the invite flow.
 * Creating a code still requires the same access an admin always needed to edit
 * config.json; what this removes is the restart that used to follow. The server
 * picks up a newly-created invite on its very next redemption attempt, with no
 * signal, no IPC, and no restart — it just reads the same state file fresh.
 *
 *   node dist/admin-cli.js create-invite --config <path-to-config.json> --label <text> [--ttl-minutes 60]
 */

function usage(message: string): never {
  console.error(`admin-cli: ${message}`);
  console.error('usage: node dist/admin-cli.js create-invite --config <path> --label <text> [--ttl-minutes 60]');
  process.exit(1);
}

function flag(args: string[], name: string): string | undefined {
  const i = args.indexOf(`--${name}`);
  if (i === -1) return undefined;
  const value = args[i + 1];
  if (value === undefined) usage(`--${name} needs a value`);
  return value;
}

const [command, ...rest] = process.argv.slice(2);

if (command !== 'create-invite') {
  usage(command ? `unknown command '${command}'` : 'no command given');
}

const configPath = flag(rest, 'config');
const label = flag(rest, 'label');
const ttlRaw = flag(rest, 'ttl-minutes');
if (!configPath) usage('--config is required');
if (!label) usage('--label is required');

const ttlMinutes = ttlRaw ? Number(ttlRaw) : 60;
if (!Number.isFinite(ttlMinutes) || ttlMinutes <= 0) usage('--ttl-minutes must be a positive number');

const statePath = join(dirname(configPath), 'state', 'invites.jsonl');
const { token, expiresAtMs } = createInvite(statePath, label, ttlMinutes);

console.log(`Invite for "${label}", valid until ${new Date(expiresAtMs).toLocaleString()}:`);
console.log('');
console.log(`  ${token}`);
console.log('');
console.log('This is shown only once -- it is not stored in plaintext anywhere. Share it out');
console.log('of band (text/voice) with the person pairing; they enter it in the app when');
console.log('voice-code pairing shows "this device isn\'t recognized yet."');
