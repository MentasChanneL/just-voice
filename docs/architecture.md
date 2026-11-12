# Architecture

## Components

1. **Fabric client mod** — runs only on the player's Minecraft client. It parses world-generated voice directives, redeems player-scoped invitations, captures and plays audio, and connects directly to the voice server.
2. **Python backend** — receives authenticated requests from Creative+ world code, manages rooms and player permissions, and exchanges short-lived invitation credentials.
3. **Murmur 1.3.4** — carries voice traffic. Clients connect directly to its normal Mumble endpoint; the Minecraft server does not host voice traffic.

No Minecraft server-side mod or plugin is required.

## Trust boundaries

- The Fabric mod contains no Mumble administration password, backend API key, or world secret.
- World-to-backend calls use HTTPS and server-side credentials kept in the world/module configuration.
- An invitation ticket is scoped to one player and one room, expires quickly, and can only be redeemed once.
- The backend owns revocation. A client-side `@VOICE CLOSE` handler clears local credentials immediately, while server-side ACL revocation and session removal remain authoritative.
- Murmur ICE must remain disabled or bound to loopback; it is not part of the public architecture.

## World directives

The client uses Fabric's server/game-message receive event. This is intentionally separate from the player-chat event, so a player typing a fake `@VOICE` command is not treated as a world directive.

- `@VOICE INVITE <ticket>` — redeem the opaque single-use ticket over HTTPS, scoped to the local Minecraft username.
- `@VOICE CLOSE` — clear the local voice session and invalidate any in-flight invitation redemption.
- A disconnect from the Minecraft world also clears local credentials.

The ticket argument after INVITE is the current proposed syntax, not yet verified against the Creative+ message API. Control messages are hidden from the visible chat to avoid exposing the ticket in history.

## Backend redemption

The mod sends only the one-time ticket and its local player name to `POST /v1/invites/redeem`. The response contains a Mumble endpoint, username, and player-specific ACL token. The mod keeps these credentials in memory only. It never receives the backend world API key or Murmur service account password.

HTTPS redirects are disabled for ticket redemption so the ticket cannot be forwarded automatically to another host.

## Voice transport

The client implementation targets the Mumble 1.3.4 protocol:

- TLS-protected TCP connection for authentication and control messages.
- Encrypted UDP voice packets using the Mumble 1.3.4 crypto protocol.
- Opus for voice encoding and decoding.

The mod implements the client functionality itself; players do not need to install the standalone Mumble application.

## First vertical slice

1. Verify the world can send a per-player system message containing the invite ticket.
2. Verify the Python backend service account can authenticate to Murmur over TLS.
3. Create a channel through the Mumble client protocol.
4. Set the channel ACL to grant a test access token.
5. Connect a test client using that token.
6. Revoke the ACL token and remove the active session.
7. Implement the Fabric Mumble voice transport and microphone/playback path.

See [Mumble control-plane notes](https://github.com/MentasChanneL/just-voice-backend/blob/main/docs/mumble-control-plane.md).
