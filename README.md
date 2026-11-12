# Just Voice

Client-only voice chat mod for Minecraft Java Edition 26.3 (Fabric).

The Minecraft world manages temporary rooms through a small backend. The backend creates Mumble ACL tokens but does not know Minecraft players. The world stores each token against its own player UUID/name and sends the token privately to that player.

## Player-facing behaviour

The mod listens only to server/game system messages, not player-authored chat:

- \`@VOICE INVITE <token>\` — accept the actual Mumble ACL token issued by the backend.
- \`@VOICE CLOSE\` — immediately clear the local voice session.
- On Minecraft server disconnect, local voice credentials are cleared automatically.

Recognized control messages are hidden from chat so live access tokens do not remain in chat history.

## Client configuration

After first launch, edit \`config/just-voice.properties\`:

\`\`\`properties
mumble-host=voice.example.invalid
mumble-port=64738
\`\`\`

Set the public Mumble server hostname and normal client port. The player client does not need the backend URL, world API key, or Mumble service-account credentials.

## Current implementation

- Client-only Fabric entrypoint for Minecraft 26.3 / Java 25.
- System-message directive parser for INVITE and CLOSE.
- Direct in-memory handling of the Mumble access token sent by the world; no HTTP redemption.
- Local Mumble username comes from the Minecraft client, while the backend remains unaware of player identity.
- Unit tests for directive parsing and token redaction.

The Mumble voice transport is not implemented yet. The current client prepares and holds credentials; it does not capture the microphone, connect to the Mumble TLS control channel, send encrypted UDP audio, or play received voice yet.

## Build

\`\`\`shell
gradle build
\`\`\`

See [architecture notes](docs/architecture.md) for the trust model and component boundaries.
