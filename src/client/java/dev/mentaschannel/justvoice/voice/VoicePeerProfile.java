package dev.mentaschannel.justvoice.voice;

/**
 * A peer's short-lived presentation identity as announced by the voice server.
 * The numeric ID is unrelated to a Minecraft UUID.
 */
public record VoicePeerProfile(long peerId, String name, int rgbColor) {
    public int argbColor() {
        return 0xFF000000 | (rgbColor & 0x00FFFFFF);
    }
}
