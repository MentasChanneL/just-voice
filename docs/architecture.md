# Architecture

## Components

1. **Fabric client mod** — parses private world-generated voice directives from server action-bar messages, holds its temporary token in memory, captures microphone audio while push-to-talk is held, encodes Opus, and connects directly to the Just Voice Server over QUIC.
2. **Minecraft world/module** — owns player identity and maps player UUIDs to opaque tokens. It creates and removes world rooms through the management API and sends each token only to its intended player.
3. **Just Voice Server** — one standalone process hosting the HTTP management API and QUIC voice transport. It keeps room/token/session state in RAM and relays compressed Opus datagrams only within the token's room.

No Minecraft server-side mod or plugin is required when the world can call the backend HTTP API and issue private server action-bar messages.

## Trust boundaries

- World-to-backend management requests use HTTPS and both `X-World-ID` and `X-World-API-Key` headers.
- The world API key stays in server-side world/module configuration. It is never sent to the client.
- A player client receives only its temporary opaque voice token in a private server action-bar message.
- The Fabric client validates the QUIC server certificate and hostname and negotiates ALPN `just-voice/1`.
- The voice server does not receive Minecraft UUIDs or player names. A local display username is not part of the voice protocol.
- Tokens are kept in memory only. `@VOICE CLOSE` and Minecraft disconnect immediately clear local credentials and close the client session.

Leading spaces may be added before `@VOICE`; the client strips any amount of leading whitespace. The action-bar message is canceled before display. This is visual concealment only: a modified client or packet/debug tool can still inspect the token. Use short-lived, player-specific tokens and revoke them promptly.

## World directives

The client uses Fabric's server/game-message receive event and requires its action-bar flag to be set. Player-authored chat messages are never accepted as voice directives:

- `@VOICE INVITE <token>` — replace the local voice session and authenticate directly to the QUIC voice server.
- `@VOICE CLOSE` — stop local audio and close the voice connection.

Recognized directives are canceled before vanilla renders the action-bar message. A marker delivered outside the action bar is discarded and never executed.

## Voice transport

- QUIC ALPN: `just-voice/1`.
- QUIC DATAGRAM is required.
- JOIN: `0x01` followed by the ASCII token; the server responds with `0x02` after authorization.
- LEAVE: `0x03`.
- Client voice: `0x10` + uint32 big-endian sequence + uint32 big-endian monotonic timestamp + Opus frame.
- Relayed voice: `0x11` + the same sequence/timestamp/Opus payload.
- Server error: `0x7F` followed by a one-byte error code.
- Opus audio is mono, 48 kHz, 20 ms per frame.
- The server relays the compressed Opus payload unchanged; it does not decode or re-encode audio.
- The client enables the QUIC DATAGRAM extension and uses QUIC PING keepalives when no speech is being sent.

## Audio behaviour

The microphone device is opened only while the user holds the push-to-talk key and a voice connection is joined. Releasing the key closes the capture line. Received Opus packets are queued in a bounded buffer, decoded, and sent to the default Java Sound output device.

The action bar reports connection and microphone errors. While push-to-talk is held, the microphone indicator distinguishes an active capture session from outgoing voice datagrams queued for QUIC. The current protocol does not contain per-speaker identity or spatial metadata, so the client plays the room's received audio as a single mono mix.
