package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one temporary voice session. Tokens stay in memory and are replaced
 * on every INVITE; CLOSE and Minecraft disconnect tear down audio and QUIC.
 */
public final class VoiceSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final long STATUS_DURATION_NANOS = TimeUnit.SECONDS.toNanos(4);

    public enum HudTone {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        ACTIVE
    }

    public record HudStatus(String message, HudTone tone, boolean persistent) {
    }

    private final VoiceClientConfig config;
    private final AtomicReference<QuicVoiceClient> connection = new AtomicReference<>();

    private volatile AudioPlayback playback;
    private volatile MicrophoneCapture microphone;
    private volatile boolean pushToTalkDown;
    private volatile HudStatus hudStatus;
    private volatile long hudStatusExpiresAtNanos;
    private volatile boolean microphoneUnavailable;
    private long generation;

    public VoiceSessionManager(VoiceClientConfig config) {
        this.config = config;
    }

    public synchronized void invite(String token) {
        closeResources();
        long currentGeneration = ++generation;
        microphoneUnavailable = false;
        showStatus("Just Voice: Connecting...", HudTone.INFO);

        if (!config.isConfigured()) {
            LOGGER.warn("Cannot accept Just Voice invitation: voice-host is not configured.");
            showStatus("Just Voice: Server not configured", HudTone.ERROR);
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
            showStatus("Just Voice: Invalid invitation", HudTone.ERROR);
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
                showStatus("Just Voice: Connected", HudTone.SUCCESS);
                updateMicrophone();
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (expectedGeneration == generation && connection.compareAndSet(candidate, null)) {
                    closeAudio();
                    LOGGER.warn("Could not connect to Just Voice server: {}", exception.getMessage());
                    showStatus("Just Voice: Connection failed", HudTone.ERROR);
                }
            }
            candidate.close();
        }
    }

    private synchronized void handleTransportError(long expectedGeneration, String message) {
        if (expectedGeneration != generation) {
            return;
        }
        String sanitized = sanitizeServerMessage(message);
        LOGGER.warn("Just Voice connection ended: {}", sanitized);
        stopMicrophone();
        closeAudio();
        connection.set(null);
        showStatus(transportHudMessage(sanitized), HudTone.ERROR);
    }

    private synchronized void handleMicrophoneError(long expectedGeneration, String message) {
        if (expectedGeneration != generation) {
            return;
        }
        microphoneUnavailable = true;
        LOGGER.warn("Just Voice microphone unavailable: {}", sanitizeServerMessage(message));
        stopMicrophone();
        showStatus("Just Voice: Microphone unavailable", HudTone.ERROR);
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
        if (down != pushToTalkDown) {
            microphoneUnavailable = false;
        }
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
        if (microphoneUnavailable || microphone != null) {
            return;
        }
        if (microphone != null) {
            return;
        }

        long currentGeneration = generation;
        microphone = new MicrophoneCapture(
                current::sendVoice,
                message -> handleMicrophoneError(currentGeneration, message)
        );
        microphone.start();
    }

    public HudStatus getHudStatus() {
        QuicVoiceClient current = connection.get();
        if (pushToTalkDown && microphone != null && current != null && current.isJoined()) {
            if (current.isTransmittingVoice()) {
                return new HudStatus("Just Voice: MIC ON · TRANSMITTING", HudTone.ACTIVE, true);
            }
            return new HudStatus("Just Voice: MIC ON", HudTone.WARNING, true);
        }

        HudStatus currentStatus = hudStatus;
        return currentStatus != null && System.nanoTime() < hudStatusExpiresAtNanos
                ? currentStatus
                : null;
    }

    private void showStatus(String message, HudTone tone) {
        hudStatusExpiresAtNanos = System.nanoTime() + STATUS_DURATION_NANOS;
        hudStatus = new HudStatus(message, tone, false);
    }

    private static String transportHudMessage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("invalid or expired voice token")) {
            return "Just Voice: Invitation expired";
        }
        if (normalized.contains("datagram")) {
            return "Just Voice: Server protocol error";
        }
        return "Just Voice: Connection lost";
    }

    private void stopMicrophone() {
        MicrophoneCapture current = microphone;
        microphone = null;
        if (current != null) {
            current.close();
        }
    }

    public synchronized void close() {
        boolean hadSession = connection.get() != null;
        generation++;
        pushToTalkDown = false;
        microphoneUnavailable = false;
        closeResources();
        if (hadSession) {
            showStatus("Just Voice: Disconnected", HudTone.INFO);
        }
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
