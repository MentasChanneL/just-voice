# Just Voice

Client-only voice chat mod for Minecraft Java Edition 26.3 (Fabric).

Just Voice is designed to work without a mod or plugin on the Minecraft server. The Minecraft client connects directly to an external Mumble-compatible voice server; a separate backend manages temporary voice rooms and player access.

## Planned responsibilities

- Parse world-generated system directives: `@VOICE INVITE` and `@VOICE CLOSE`.
- Connect to the external voice service without requiring the standalone Mumble application.
- Capture microphone audio and play received voice.
- Encode and decode voice with Opus.
- Implement the Mumble 1.3.4 client transport (TLS control connection and encrypted UDP voice).
- Keep backend credentials and room-management secrets out of the client.

## Target

- Minecraft: 26.3
- Loader: Fabric
- Runtime / compile target: Java 25
- Client-side only; the Minecraft server does not install this mod.

The protocol and invite-ticket details are being documented before the audio transport is implemented.