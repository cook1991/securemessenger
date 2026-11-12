# Hermes setup code — gateway side

The app no longer has any gateway settings compiled in. A person connects their own
Hermes agent by scanning (or pasting) a one-time **setup code** their gateway gives
them. Nothing about the WebSocket protocol, HMAC, `resume` or ntfy wake behaviour
changes; only how the app learns where the gateway is.

## 1. Setup code (gateway → person)

A single string, shown as a QR code and/or as text to copy:

```
hermes-setup:1:<payload>
```

`<payload>` is **base64url without padding** of this UTF-8 JSON:

```json
{
  "host": "relay.example.net",
  "port": 443,
  "ws_path": "/ws",
  "pair_path": "/pair",
  "pins": ["<base64 SPKI sha256>", "<backup pin>"],
  "code": "<single-use pairing code>"
}
```

| Field | Rules (the app rejects the code otherwise) |
|---|---|
| `host` | DNS name, no scheme/port/IP. Must serve a publicly trusted cert. |
| `port` | Optional, default 443. 1–65535. |
| `ws_path`, `pair_path` | Optional, defaults `/ws`, `/pair`. Start with `/`; letters, digits, `. _ ~ / -`; no `..` or `//`. |
| `pins` | 1–10 entries, each standard base64 of a 32-byte SHA-256 SPKI hash (same values as the old `hermes.pinSha256Base64`). Include a backup (e.g. the issuing CA) so a cert renewal doesn't break pairing. |
| `code` | The existing single-use pairing code. 1–256 printable ASCII chars, no spaces. |

Unknown extra fields are ignored. The app pins **both** `/pair` and the WebSocket to
`pins`, on top of normal CA validation.

The code is a credential until it's spent: give it to its owner only, short TTL,
single use (as today).

Reference encoder (Python):

```python
import base64, json
payload = {"host": host, "port": 443, "ws_path": "/ws", "pair_path": "/pair",
           "pins": pins, "code": code}
raw = json.dumps(payload, separators=(",", ":")).encode()
setup = "hermes-setup:1:" + base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
```

(`qrencode -t ansiutf8 "$setup"` prints it as a terminal QR.)

## 2. `/pair` response (gateway → app)

Request is unchanged: `POST <pair_path>` with `{"code": "..."}`.

The 2xx response gains three **optional** fields:

```json
{
  "token": "<hex device token>",
  "device_id": "...",
  "ntfy_host": "ntfy.example.net",
  "ntfy_topic": "<this device's wake topic>",
  "ntfy_auth_token": "<optional bearer token for ntfy>"
}
```

- `ntfy_topic`: 20–64 chars of `A-Z a-z 0-9 _ -`, CSPRNG-generated (it is the secret).
- `ntfy_host`: DNS name. Both host and topic must be present, or neither.
- Omit all three if the gateway has no ntfy: the app works without wake-ups.
- Invalid ntfy values don't fail pairing (the code is already spent); the app just
  stores no wake settings.

The topic travels only in this response, over the pinned connection — never in the
setup code, which may be photographed or pasted around.

Errors are unchanged: 403 = invalid/expired code.
