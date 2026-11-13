# Just Voice

Client-only voice chat mod for Minecraft Java Edition 26.3 (Fabric).

The mod connects directly to a standalone **Just Voice Server** over QUIC. The Minecraft world remains responsible for room membership and player identity; the voice server receives only temporary opaque tokens.

## Player-facing behaviour

The mod accepts voice directives only from server-sent **action-bar packets** (not player-authored chat). It intercepts the dedicated `/title actionbar` packet before vanilla renders it; overlay-flagged system-chat messages are also supported as a fallback.

- `@VOICE INVITE <token>` — replace the current voice session and connect to the configured voice server.
- `@VOICE CLOSE` — immediately stop capture/playback and close the voice connection.

The server may prepend any number of ordinary spaces before `@VOICE`. The client strips leading whitespace before parsing, so the padding length is not fixed. A recognized directive is canceled before Minecraft displays it. A marker delivered as a normal chat message is discarded but never executed.

Example server command:

```mcfunction
/title PlayerName actionbar {"text":"                         @VOICE INVITE <token>"}
```

Use enough leading spaces for the visible text to sit outside the player's action-bar area on the target UI scale. The mod does not depend on an exact count.

**Security note:** moving a token off-screen is only visual concealment, not encryption or access control. The token is still present in the packet delivered to that client and can be inspected by a modified client or packet/debug tooling. Keep tokens short-lived, player-specific, one-use where practical, and revoke them on disconnect or room teardown. The mod never prints or stores the token in logs or files.

## Configuration

After first launch, edit `config/just-voice.properties`:

```properties
voice-host=voice.example.com
voice-port=24454
ca-cert-file=
```

- `voice-host` and `voice-port` are the public QUIC endpoint.
- `ca-cert-file` is optional. Leave it empty when the server certificate chains to a normal trusted CA. For a private/self-signed development certificate, set it to the PEM certificate file that should be trusted by the client.

For a local development server using the certificate created in the backend setup:

```properties
voice-host=localhost
voice-port=24454
ca-cert-file=/home/your-user/.config/just-voice-dev/dev-cert.pem
```

Never put the world API key in this file or in the client mod.

## Voice controls and indicators

- Hold **V** to talk (rebindable under Options → Controls).
- The microphone is opened only while push-to-talk is held and a voice session is connected.
- The action bar briefly reports connection success, disconnection, or connection/microphone errors.
- While V is held, the action bar shows `MIC ON`; it changes to `MIC ON · TRANSMITTING` after voice datagrams are successfully queued for QUIC. The indicator disappears when push-to-talk is released.
- Incoming voice is decoded and played through the system's default audio output.

Audio uses 48 kHz, mono, 20 ms Opus frames. The client has no native Opus dependency and does not require a separate voice-chat application.

## Transport

- QUIC with TLS certificate and hostname validation, ALPN `just-voice/1`, and QUIC DATAGRAM frames.
- Idle sessions send QUIC PING keep-alives at half the peer's negotiated idle timeout; no dummy voice/audio packets are generated.
- The client sends compressed Opus frames; the server relays them unchanged to peers in the same world room.
- The client does not send Minecraft UUIDs, player names, backend URLs, or world API keys to the voice server.
- The QUIC client uses Kwik, a pure-Java QUIC implementation, so no platform-specific QUIC binaries need to be installed.

## Build

Requires Java 25 and Gradle 9.8.2:

```shell
gradle --no-daemon build
```

## Current limitations

The transport and audio path still needs an end-to-end test on the target operating system and audio devices. Kwik's project documents its TLS implementation as not independently security-audited; this mod keeps certificate-chain and hostname validation enabled and does not provide an insecure bypass. Review that trade-off before treating the client as a production security component.
