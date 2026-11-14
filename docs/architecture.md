# Architecture

## Components

1. Fabric client mod — receives a short-lived voice token through a private server action-bar directive, maintains a QUIC room session, captures microphone audio while push-to-talk is held, encodes Opus, and mixes incoming participants locally.
2. Minecraft world/module — owns player UUIDs and maps them to opaque, player-specific tokens. It creates and removes voice rooms through the management API and sends each token only to its intended player.
3. Just Voice Server — one standalone process exposing the authenticated HTTP management API and encrypted QUIC voice transport. It stores room and token state in RAM and relays compressed Opus packets only between peers in the same room.

No Minecraft server-side mod is required when the world integration can call the backend HTTP API and send private system action-bar messages.

## Trust boundaries and lifecycle

- World-to-backend management requests use HTTPS and the X-World-ID and X-World-API-Key headers.
- The world API key remains in server-side world/module configuration. It is never included in the Fabric client.
- A player client receives only its opaque voice token in a private action-bar message. The token is kept in memory and is not written to client files or logs.
- The backend stores a SHA-256 digest of issued tokens in memory. A room deletion, token revocation, heartbeat expiry, or client leave closes the associated voice session.
- The QUIC connection validates the server TLS certificate and hostname and negotiates ALPN just-voice/2.
- The client does not send Minecraft UUIDs. The voice roster contains only short-lived peer IDs and presentation metadata (nickname and color).
- @VOICE CLOSE immediately stops local audio, discards the pending token, and closes the room connection.
- /justvoice off is a local mute control: it stops capture and silences incoming playback but keeps the QUIC room membership alive. /justvoice on resumes local audio without rejoining the room.

Leading spaces may be added before @VOICE; the client strips them before parsing. Recognized directives are canceled before display. This is visual concealment only: a modified client or packet/debug tool can still inspect the token. Use short-lived, player-specific tokens and revoke them promptly.

## Client commands

- /justvoice on and /justvoice off — locally enable or mute voice without leaving the room.
- /justvoice settings — open audio device, gain, volume, and microphone icon settings.
- /justvoice players — open participant-specific volume and mute controls.
- /justvoice list — print the currently known room roster.

## World directives

The client mixin intercepts Minecraft's dedicated ClientboundSetActionBarTextPacket before vanilla renders it. Overlay-flagged system messages are accepted as a fallback. Player-authored chat messages are never accepted as voice directives:

- @VOICE INVITE <token> — replace the local voice session and authenticate to the QUIC voice server.
- @VOICE CLOSE — stop audio, clear local credentials, and close the client session.

A marker delivered outside the action bar is discarded and never executed.

## QUIC transport

- QUIC ALPN: just-voice/2; QUIC DATAGRAM is required.
- 0x01 JOIN — ASCII invitation token.
- 0x02 JOINED — assigned 8-byte peer ID.
- 0x03 LEAVE — leave the current room.
- 0x04 ROSTER_REQUEST — request a roster snapshot.
- 0x05 PEER_PROFILE and 0x06 PEER_LEFT — live participant metadata and departure.
- 0x08 ROSTER_BEGIN, 0x09 ROSTER_ENTRY, 0x0A ROSTER_END — bracket a complete roster snapshot, keeping snapshot entries separate from live join events.
- 0x10 VOICE — uint32 sequence, uint32 timestamp, then an Opus frame.
- 0x11 VOICE_RELAY — 8-byte sender peer ID, sequence, timestamp, then the original Opus frame.
- 0x7F ERROR — server-side protocol or authorization error.

Integer fields use network byte order. The server relays Opus payloads unchanged; it does not decode or re-encode voice packets. QUIC PING keepalives are used while idle rather than sending synthetic audio.

## Audio and presentation

- Audio is mono, 48 kHz, with 20 ms Opus frames.
- The client uses OpenAL for microphone capture and speaker playback, and Concentus for Opus encoding/decoding.
- Capture is opened only while push-to-talk is held and local voice is enabled.
- Incoming audio has an independent Opus decoder per peer. Decoded PCM is mixed locally and passed through per-player mute/volume and master-volume controls.
- The HUD displays the selected microphone icon beside the hotbar and a compact list of recent speakers. A colored MIC marker is rendered above active speakers in third person.
- Audio preferences and per-player volume/mute choices are stored locally under config/just-voice/. Custom microphone PNGs are stored under config/just-voice/mic-icons/.

## Build and verification

The client targets Minecraft Java Edition 26.3, Fabric Loader 0.20.1, and Java 25. CI runs gradle --no-daemon build on pushes and pull requests. A live audio check requires a matching Just Voice Server using protocol v2.
