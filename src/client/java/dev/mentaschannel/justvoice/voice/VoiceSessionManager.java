package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one temporary voice session. Tokens stay in memory and are replaced
 * on every INVITE; CLOSE and Minecraft disconnect tear down audio and QUIC.
 */
public final class VoiceSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");

    private final VoiceClientConfig config;
    private final AtomicReference<QuicVoiceClient> connection = new AtomicReference<>();

    private volatile AudioPlayback playback;
    private volatile MicrophoneCapture microphone;
    private volatile boolean pushToTalkDown;
    private long generation;

    public VoiceSessionManager(VoiceClientConfig config) {
        this.config = config;
    }

    public synchronized void invite(String token) {
        closeResources();
        long currentGeneration = ++generation;

        if (!config.isConfigured()) {
            LOGGER.warn("Cannot accept Just Voice invitation: voice-host is not configured.");
            return;
        }

        VoiceCredentials credentials;
        try {
            credentials = new VoiceCredentials(
                    config.voiceHost(),
                    config.voicePort(),
                    config.certificateFile(),
                    token
            );
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignored invalid Just Voice invitation token.");
            return;
        }

        Thread.ofVirtual().name("just-voice-connect").start(() -> connect(currentGeneration, credentials));
    }

    private void connect(long expectedGeneration, VoiceCredentials credentials) {
        QuicVoiceClient candidate = new QuicVoiceClient(
                credentials,
                this::receiveVoicePacket,
                message -> handleTransportError(expectedGeneration, message)
        );

        synchronized (this) {
            if (expectedGeneration != generation) {
                candidate.close();
                return;
            }
            connection.set(candidate);
        }

        try {
            candidate.connect();
            synchronized (this) {
                if (expectedGeneration != generation || connection.get() != candidate) {
                    candidate.close();
                    return;
                }
                playback = new AudioPlayback();
                playback.start();
                LOGGER.info("Just Voice session is ready. Hold V to talk.");
                updateMicrophone();
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (expectedGeneration == generation && connection.compareAndSet(candidate, null)) {
                    closeAudio();
                    LOGGER.warn("Could not connect to Just Voice server: {}", exception.getMessage());
                }
            }
            candidate.close();
        }
    }

    private synchronized void handleTransportError(long expectedGeneration, String message) {
        if (expectedGeneration != generation) {
            return;
        }
        LOGGER.warn("Just Voice connection ended: {}", sanitizeServerMessage(message));
        stopMicrophone();
        closeAudio();
        connection.set(null);
    }

    private void receiveVoicePacket(byte[] datagram) {
        if (datagram.length < 10 || datagram[0] != 0x11) {
            return;
        }
        byte[] opus = Arrays.copyOfRange(datagram, 9, datagram.length);
        AudioPlayback currentPlayback = playback;
        if (currentPlayback != null) {
            currentPlayback.enqueue(opus);
        }
    }

    public synchronized void setPushToTalk(boolean down) {
        pushToTalkDown = down;
        updateMicrophone();
    }

    private void updateMicrophone() {
        QuicVoiceClient current = connection.get();
        boolean shouldCapture = pushToTalkDown && current != null && current.isJoined();

        if (!shouldCapture) {
            stopMicrophone();
            return;
        }
        if (microphone != null) {
            return;
        }

        microphone = new MicrophoneCapture(current::sendVoice);
        microphone.start();
    }

    private void stopMicrophone() {
        MicrophoneCapture current = microphone;
        microphone = null;
        if (current != null) {
            current.close();
        }
    }

    public synchronized void close() {
        generation++;
        pushToTalkDown = false;
        closeResources();
    }

    private void closeResources() {
        stopMicrophone();
        closeAudio();
        QuicVoiceClient current = connection.getAndSet(null);
        if (current != null) {
            Thread.ofVirtual().name("just-voice-disconnect").start(current::close);
        }
    }

    private void closeAudio() {
        AudioPlayback current = playback;
        playback = null;
        if (current != null) {
            current.close();
        }
    }

    private static String sanitizeServerMessage(String message) {
        if (message == null || message.isBlank()) {
            return "request rejected";
        }
        String sanitized = message.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").trim();
        return sanitized.length() > 160 ? sanitized.substring(0, 160) : sanitized;
    }
}
