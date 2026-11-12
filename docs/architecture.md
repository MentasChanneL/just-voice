# Architecture

## Components

1. **Fabric client mod** — runs only on the player's Minecraft client. It parses world-generated voice directives, captures and plays audio, and connects directly to the voice server.
2. **Python backend** — receives authenticated requests from Creative+ world code, manages rooms and player permissions, and exchanges short-lived invitation credentials.
3. **Murmur 1.3.4** — carries voice traffic. Clients connect directly to its normal Mumble endpoint; the Minecraft server does not host voice traffic.

No Minecraft server-side mod or plugin is required.

## Trust boundaries

- The Fabric mod contains no Mumble administration password, backend API key, or world secret.
- World-to-backend calls use HTTPS and server-side credentials kept in the world/module configuration.
- An invitation is scoped to one player and one room, expires quickly, and can only be redeemed once.
- The backend owns revocation. A client-side `@VOICE CLOSE` handler disconnects immediately, but server-side ACL revocation and session removal remain authoritative.
- Murmur ICE must remain disabled or bound to loopback; it is not part of the public architecture.

## World directives

The initial world-facing directives are:

- `@VOICE INVITE` — allow this player to join the world's voice room.
- `@VOICE CLOSE` — revoke this player's access and disconnect voice.

The exact payload following `@VOICE INVITE` is not fixed yet. A secure flow likely needs an opaque, short-lived invitation ticket supplied by the backend and included in the system message. The parser must distinguish system messages from player-authored chat, but that distinction alone is not an authorization mechanism.

## Voice transport

The client implementation targets the Mumble 1.3.4 protocol:

- TLS-protected TCP connection for authentication and control messages.
- Encrypted UDP voice packets using the Mumble 1.3.4 crypto protocol.
- Opus for voice encoding and decoding.

The mod implements the client functionality itself; players do not need to install the standalone Mumble application.

## First vertical slice

1. Confirm the Python service account can authenticate to Murmur over TLS.
2. Create a channel through the Mumble client protocol.
3. Set the channel ACL to grant a test access token.
4. Connect a test client using that token.
5. Revoke the ACL token and remove the active session.
6. Only then connect the same flow to the HTTPS invitation API and Minecraft directives.

See [Mumble control-plane notes](mumble-control-plane.md) in the backend repository.
