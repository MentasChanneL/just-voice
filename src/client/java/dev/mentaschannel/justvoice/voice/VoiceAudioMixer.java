package dev.mentaschannel.justvoice.voice;

/**
 * Gain staging for summing multiple mono voice streams.
 * The per-frame gain keeps perceived loudness roughly stable as more people
 * talk, while the soft knee prevents peaks from hard-clipping into PCM limits.
 */
final class VoiceAudioMixer {
    private static final double LIMITER_KNEE = Short.MAX_VALUE * 0.85;
    private static final double LIMITER_RANGE = Short.MAX_VALUE - LIMITER_KNEE;

    private VoiceAudioMixer() {
    }

    static double gainForVoices(int voiceCount) {
        return voiceCount <= 1 ? 1.0 : 1.0 / Math.sqrt(voiceCount);
    }

    static short limitSample(double sample) {
        double magnitude = Math.abs(sample);
        if (magnitude > LIMITER_KNEE) {
            double excess = (magnitude - LIMITER_KNEE) / LIMITER_RANGE;
            magnitude = LIMITER_KNEE + LIMITER_RANGE * (1.0 - Math.exp(-excess));
        }

        double limited = Math.min(Short.MAX_VALUE, magnitude);
        return (short) Math.round(Math.copySign(limited, sample));
    }
}
