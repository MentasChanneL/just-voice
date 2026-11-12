# Just Voice

Client-only voice chat mod for Minecraft Java Edition 26.3 (Fabric).

Just Voice is designed to work without a mod or plugin on the Minecraft server. The Minecraft client connects directly to an external voice host; a separate backend manages temporary voice rooms and player access.

## Player-facing behaviour

The mod listens only to server/game system messages, not player-authored chat:

- `@VOICE INVITE <ticket>` — exchange a one-time ticket with the backend and prepare the player's voice credentials.
- `@VOICE CLOSE` — immediately clear the local voice session.
- On Minecraft server disconnect, local voice credentials are cleared automatically.

Recognized `@VOICE` control messages are hidden from chat so invitation tickets do not remain in chat history or get copied accidentally.

**Important:** the ticket argument after `INVITE` is the current proposed message format. It must be verified against the Creative+ scripting API before deployment. The mod does not accept bare `@VOICE INVITE` because that would provide no secure, player-specific proof that the world intended to invite this player.

## Backend configuration

After the first launch, edit `config/just-voice.properties` and set:

```properties
backend-url=https://your-voice-backend.example
```

Only the public backend URL is stored by the mod. It must use HTTPS (plain HTTP is accepted only for loopback development). The world API key, Mumble service account password, and ticket-signing secret never belong in the mod.

## Current implementation

- Client-only Fabric entrypoint for Minecraft 26.3 / Java 25.
- System-message directive parser for INVITE and CLOSE.
- One-time ticket redemption through the backend HTTPS API.
- In-memory storage of redeemed Mumble host, username, and player access token.
- Pending invitation requests are invalidated by a later CLOSE or Minecraft disconnect, so a slow HTTP response cannot reopen a closed session.
- Unit tests for directive parsing and ticket redaction.

The Mumble voice transport is not implemented yet. The current client prepares and holds credentials; it does not capture the microphone, connect to the Mumble TLS control channel, send encrypted UDP audio, or play received voice yet.

## Build

```shell
gradle build
```

See [architecture notes](docs/architecture.md) for the trust model and component boundaries.
