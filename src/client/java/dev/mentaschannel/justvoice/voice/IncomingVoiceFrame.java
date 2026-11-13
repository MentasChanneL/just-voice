package dev.mentaschannel.justvoice.voice;

/**
 * An Opus frame received from one authenticated voice peer.
 */
public record IncomingVoiceFrame(
        long peerId,
        int sequence,
        int timestampMillis,
        byte[] opusPayload
) {
    public IncomingVoiceFrame {
        opusPayload = opusPayload.clone();
    }

    @Override
    public byte[] opusPayload() {
        return opusPayload.clone();
    }
}
