# Just Voice

Client-only voice chat mod for Minecraft Java Edition 26.3 (Fabric).

The mod connects directly to a standalone **Just Voice Server** over QUIC. The Minecraft world remains responsible for room membership and player identity; the voice server receives only temporary opaque tokens.

## Player-facing behaviour

The mod listens only to server/game system messages, not player-authored chat:

- `@VOICE INVITE <token>` — replace the current voice session and connect to the configured voice server.
- `@VOICE CLOSE` — immediately stop capture/playback and close the voice connection.
- On Minecraft server disconnect, local voice credentials are cleared automatically.

Recognized control messages are hidden from chat so live access tokens do not remain in chat history. The access token is kept in memory only and is never logged or written to disk.

## Configuration

After first launch, edit `config/just-voice.properties`:

```properties
voice-host=voice.example.com
voice-port=24454
ca-cert-file=
```

- `voice-host` and `voice-port` are the public QUIC endpoint.
- `ca-cert-file` is optional. Leave it empty when the server certificate chains to a normal trusted CA. For a private/self-signed development certificate, set it to the PEM certificate file that should be trusted by the client.

For a local development server using the certificate created in the backend setup, for example:

```properties
voice-host=127.0.0.1
voice-port=24454
ca-cert-file=/home/your-user/.config/just-voice-dev/dev-cert.pem
```

Never put the world API key in this file or in the client mod.

## Voice controls

- Hold **V** to talk (rebindable under Options → Controls).
- The microphone is opened only while push-to-talk is held and a voice session is connected.
- Incoming voice is decoded and played through the system's default audio output.

Audio uses 48 kHz, mono, 20 ms Opus frames. The client has no native Opus dependency and does not require a separate voice-chat application.

## Transport

- QUIC with TLS certificate and hostname validation, ALPN `just-voice/1`, and QUIC DATAGRAM frames.
- The client sends compressed Opus frames; the server relays them unchanged to peers in the same world room.
- The client does not send Minecraft UUIDs, player names, backend URLs, or world API keys to the voice server.
- The QUIC client uses Kwik, a pure-Java QUIC implementation, so no platform-specific QUIC binaries need to be installed.

## Build

Requires Java 25 and Gradle 9.8.2:

```shell
gradle --no-daemon build
```

## Current limitations

The transport and audio path are implemented, but the client still needs an end-to-end test against the local Just Voice Server on the target operating system and audio devices. Kwik's project documents its TLS implementation as not independently security-audited; this mod keeps certificate-chain and hostname validation enabled and does not provide an insecure bypass. Review that trade-off before treating the client as a production security component.
