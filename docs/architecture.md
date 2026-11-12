# Architecture

## Components

1. **Fabric client mod** — parses world-generated voice directives, stores its own Mumble username and access token in memory, and will connect directly to the Mumble voice server.
2. **Minecraft world/module** — owns player identity and its mapping from player UUID/name to opaque Mumble token. It manages room lifetime and sends the token only to the intended player.
3. **Python backend** — a stateless-in-purpose bridge with an in-memory room map. It creates/removes Mumble channels and adds/removes opaque ACL tokens. It does not receive player names or UUIDs.
4. **Murmur 1.3.4** — stores channels and ACLs and carries voice traffic. Clients connect directly to its normal Mumble endpoint.

No Minecraft server-side mod or plugin is required.

## Backend state

The backend keeps only:

\`\`\`python
rooms: dict[str, Room]

@dataclass
class Room:
    channel_id: int
    last_heartbeat: float
    tokens: set[str]
\`\`\`

The dictionary key is the world ID from the \`X-World-ID\` HTTP header. The world sends a heartbeat every 10 seconds; after 30 seconds without one, the backend deletes the room. Rooms and tokens are not persisted. On backend restart, leftover \`JV-\` channels under the dedicated parent channel are removed. Run one backend worker so all requests share the same memory map.

## Trust boundaries

- World-to-backend requests use HTTPS and both \`X-World-ID\` and \`X-World-API-Key\` headers. The world ID is an identifier, not a secret; the API key authenticates the caller.
- Keep the API key in server-side world/module configuration. Never send it to a player client.
- The Fabric mod receives only the per-room Mumble token for the intended player.
- The backend does not store or receive Minecraft player identities.
- Murmur ICE must remain disabled or bound to loopback; it is not part of the public architecture.

## World directives

The client uses Fabric's server/game-message receive event, separate from player chat:

- \`@VOICE INVITE <token>\` — store the Mumble ACL token locally. The token is passed directly to Mumble authentication; there is no HTTP redemption.
- \`@VOICE CLOSE\` — clear the local voice session.
- A Minecraft world disconnect also clears local credentials.

Control messages are hidden from visible chat so the token is not copied into history.

## World/backend flow

1. The world calls \`POST /v1/rooms\` with its world ID and API key headers.
2. When inviting a player, the world calls \`POST /v1/tokens\`. The backend creates a random token, adds its ACL entry, and returns the token.
3. The world stores \`player_uuid -> token\` in its own data and sends \`@VOICE INVITE <token>\` privately to the target player.
4. When the player leaves, the world calls \`POST /v1/tokens/revoke\` with the token and sends \`@VOICE CLOSE\` to the player.
5. The world sends \`POST /v1/rooms/heartbeat\` every 10 seconds. On shutdown it calls \`DELETE /v1/rooms\`.

## Voice transport

The Mumble client implementation still needs:

- TLS-protected TCP connection for authentication and control messages.
- Encrypted UDP voice packets using the Mumble 1.3.4 crypto protocol.
- Opus voice encoding and decoding.

Players do not need the standalone Mumble application; the mod will implement the client functionality itself.

