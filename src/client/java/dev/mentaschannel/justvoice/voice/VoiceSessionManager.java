package dev.mentaschannel.justvoice.voice;

import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the client's temporary voice session, room roster and PTT capture.
 * The latest invite token is memory-only and may be retained while voice is disabled.
 */
public final class VoiceSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final long SPEAKER_FADE_START_NANOS = TimeUnit.MILLISECONDS.toNanos(1_100);
    private static final long SPEAKER_FADE_DURATION_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final long SPEAKER_VISIBLE_NANOS = SPEAKER_FADE_START_NANOS + SPEAKER_FADE_DURATION_NANOS;
    private static final long ROSTER_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_VISIBLE_SPEAKERS = 5;

    public record ChatNotice(String translationKey, ChatFormatting color, Object[] arguments) {
    }

    public record SpeakerHudEntry(
            long peerId,
            String name,
            int color,
            boolean self,
            float inputLevel,
            long lastActiveAtNanos
    ) {
        public float opacity(long nowNanos) {
            long age = Math.max(0L, nowNanos - lastActiveAtNanos);
            if (age <= SPEAKER_FADE_START_NANOS) {
                return 1.0f;
            }
            return Math.max(0.0f,
                    1.0f - (age - SPEAKER_FADE_START_NANOS) / (float) SPEAKER_FADE_DURATION_NANOS);
        }
    }

    private final VoiceClientConfig config;
    private final VoiceSettings settings;
    private final Consumer<ChatNotice> chatNotifier;
    private final AtomicReference<QuicVoiceClient> connection = new AtomicReference<>();
    private final ConcurrentHashMap<Long, VoicePeerProfile> peerProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastVoiceAtNanos = new ConcurrentHashMap<>();

    private volatile AudioPlayback playback;
    private volatile MicrophoneCapture microphone;
    private volatile boolean pushToTalkDown;
    private volatile boolean microphoneUnavailable;
    private volatile float microphoneLevel;
    private volatile long lastRosterRequestNanos;
    private volatile String pendingToken;
    private long generation;

    public VoiceSessionManager(
            VoiceClientConfig config,
            VoiceSettings settings,
            Consumer<ChatNotice> chatNotifier
    ) {
        this.config = config;
        this.settings = settings;
        this.chatNotifier = chatNotifier;
    }

    /**
     * Stores a received token even while voice is disabled. The token is never
     * written to disk and a disabled client does not open a QUIC connection.
     */
    public synchronized void invite(String token) {
        pendingToken = token;
        if (!settings.isEnabled()) {
            notifyChat("message.justvoice.token_saved_disabled", ChatFormatting.GRAY);
            return;
        }
        if (!config.isConfigured()) {
            LOGGER.warn("Cannot accept Just Voice invitation: voice-host is not configured.");
            notifyChat("message.justvoice.server_not_configured", ChatFormatting.RED);
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
            pendingToken = null;
            LOGGER.warn("Ignored invalid Just Voice invitation token.");
            notifyChat("message.justvoice.invalid_invitation", ChatFormatting.RED);
            return;
        }
        connectWithCredentials(credentials);
    }

    private synchronized void connectWithCredentials(VoiceCredentials credentials) {
        closeResources();
        long currentGeneration = ++generation;
        microphoneUnavailable = false;
        microphoneLevel = 0.0f;
        lastRosterRequestNanos = 0L;

        Thread.ofVirtual().name("just-voice-connect").start(
                () -> connect(currentGeneration, credentials)
        );
    }

    private void connect(long expectedGeneration, VoiceCredentials credentials) {
        QuicVoiceClient candidate = new QuicVoiceClient(
                credentials,
                this::receiveVoicePacket,
                this::handlePeerProfile,
                this::handlePeerLeft,
                this::handleRosterSnapshot,
                message -> handleTransportError(expectedGeneration, message)
        );

        synchronized (this) {
            if (expectedGeneration != generation || !settings.isEnabled()) {
                candidate.close();
                return;
            }
            connection.set(candidate);
        }

        try {
            candidate.connect();
            synchronized (this) {
                if (expectedGeneration != generation || connection.get() != candidate || !settings.isEnabled()) {
                    candidate.close();
                    return;
                }
                playback = createPlayback();
                playback.start();
                LOGGER.info("Just Voice session is ready. Hold V to talk.");
                notifyChat("message.justvoice.connected", ChatFormatting.GREEN);
                lastRosterRequestNanos = System.nanoTime();
                updateMicrophone();
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (expectedGeneration == generation && connection.compareAndSet(candidate, null)) {
                    closeAudio();
                    peerProfiles.clear();
                    lastVoiceAtNanos.clear();
                    LOGGER.warn("Could not connect to Just Voice server: {}", exception.getMessage());
                    notifyChat("message.justvoice.connection_failed", ChatFormatting.RED);
                }
            }
            candidate.close();
        }
    }

    private AudioPlayback createPlayback() {
        return new AudioPlayback(
                peerId -> lastVoiceAtNanos.put(peerId, System.nanoTime()),
                peerId -> {
                    VoicePeerProfile profile = peerProfiles.get(peerId);
                    return profile == null ? "" : profile.name();
                },
                settings
        );
    }

    private void receiveVoicePacket(IncomingVoiceFrame frame) {
        AudioPlayback currentPlayback = playback;
        if (currentPlayback != null) {
            currentPlayback.enqueue(frame);
        }
    }

    /** Live peer-joined event, not an item in an initial roster snapshot. */
    private void handlePeerProfile(VoicePeerProfile profile) {
        VoicePeerProfile previous = peerProfiles.put(profile.peerId(), profile);
        QuicVoiceClient current = connection.get();
        boolean isSelf = current != null && profile.peerId() == current.selfPeerId();
        if (previous == null && !isSelf) {
            notifyChat("message.justvoice.peer_joined", ChatFormatting.YELLOW, profile.name());
        }
    }

    /** Atomically replaces the known list without announcing existing room members as new joins. */
    private void handleRosterSnapshot(List<VoicePeerProfile> snapshot) {
        Set<Long> incomingIds = ConcurrentHashMap.newKeySet();
        for (VoicePeerProfile profile : snapshot) {
            incomingIds.add(profile.peerId());
        }

        for (Long oldPeerId : List.copyOf(peerProfiles.keySet())) {
            if (!incomingIds.contains(oldPeerId)) {
                peerProfiles.remove(oldPeerId);
                lastVoiceAtNanos.remove(oldPeerId);
                AudioPlayback currentPlayback = playback;
                if (currentPlayback != null) {
                    currentPlayback.removePeer(oldPeerId);
                }
            }
        }
        for (VoicePeerProfile profile : snapshot) {
            peerProfiles.put(profile.peerId(), profile);
        }
    }

    private void handlePeerLeft(Long peerId) {
        VoicePeerProfile removed = peerProfiles.remove(peerId);
        lastVoiceAtNanos.remove(peerId);
        AudioPlayback currentPlayback = playback;
        if (currentPlayback != null) {
            currentPlayback.removePeer(peerId);
        }
        if (removed != null) {
            notifyChat("message.justvoice.peer_left", ChatFormatting.GRAY, removed.name());
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
        peerProfiles.clear();
        lastVoiceAtNanos.clear();
        notifyChat(transportChatKey(sanitized), ChatFormatting.RED);
    }

    private synchronized void handleMicrophoneError(long expectedGeneration, String message) {
        if (expectedGeneration != generation) {
            return;
        }
        microphoneUnavailable = true;
        LOGGER.warn("Just Voice microphone unavailable: {}", sanitizeServerMessage(message));
        stopMicrophone();
        notifyChat("message.justvoice.microphone_unavailable", ChatFormatting.RED);
    }

    private void sendMicrophoneFrame(byte[] frame) {
        QuicVoiceClient current = connection.get();
        if (current == null) {
            return;
        }
        current.sendVoice(frame);
        if (current.isTransmittingVoice() && microphoneLevel >= 0.03f) {
            lastVoiceAtNanos.put(current.selfPeerId(), System.nanoTime());
        }
    }

    private void setMicrophoneLevel(Float level) {
        microphoneLevel = level == null ? 0.0f : Math.max(0.0f, Math.min(1.0f, level));
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
        boolean shouldCapture = settings.isEnabled()
                && pushToTalkDown
                && current != null
                && current.isJoined();

        if (!shouldCapture) {
            stopMicrophone();
            return;
        }
        if (microphoneUnavailable || microphone != null) {
            return;
        }

        long currentGeneration = generation;
        microphone = new MicrophoneCapture(
                this::sendMicrophoneFrame,
                message -> handleMicrophoneError(currentGeneration, message),
                this::setMicrophoneLevel,
                settings
        );
        microphone.start();
    }

    public void tick() {
        QuicVoiceClient current = connection.get();
        if (!settings.isEnabled() || current == null || !current.isJoined()) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastRosterRequestNanos >= ROSTER_REFRESH_NANOS) {
            lastRosterRequestNanos = now;
            current.requestRoster();
        }

        lastVoiceAtNanos.entrySet().removeIf(
                entry -> now - entry.getValue() > TimeUnit.SECONDS.toNanos(3)
        );
    }

    public List<SpeakerHudEntry> getActiveSpeakers() {
        long now = System.nanoTime();
        QuicVoiceClient current = connection.get();
        long selfId = current == null ? Long.MIN_VALUE : current.selfPeerId();
        List<SpeakerHudEntry> result = new ArrayList<>();

        lastVoiceAtNanos.forEach((peerId, lastActive) -> {
            if (now - lastActive > SPEAKER_VISIBLE_NANOS) {
                return;
            }
            VoicePeerProfile profile = peerProfiles.get(peerId);
            if (profile == null) {
                return;
            }
            result.add(new SpeakerHudEntry(
                    peerId,
                    profile.name(),
                    profile.argbColor(),
                    peerId == selfId,
                    peerId == selfId ? microphoneLevel : 0.0f,
                    lastActive
            ));
        });

        result.sort(Comparator.comparing(SpeakerHudEntry::self).reversed()
                .thenComparing(Comparator.comparingLong(SpeakerHudEntry::lastActiveAtNanos).reversed()));
        if (result.size() > MAX_VISIBLE_SPEAKERS) {
            return List.copyOf(result.subList(0, MAX_VISIBLE_SPEAKERS));
        }
        return List.copyOf(result);
    }

    public Integer getActiveSpeakerColor(String minecraftName) {
        if (minecraftName == null || minecraftName.isBlank()) {
            return null;
        }

        long now = System.nanoTime();
        long newestActivity = 0L;
        Integer color = null;

        for (var item : lastVoiceAtNanos.entrySet()) {
            long activity = item.getValue();
            if (now - activity > SPEAKER_VISIBLE_NANOS || activity < newestActivity) {
                continue;
            }

            VoicePeerProfile profile = peerProfiles.get(item.getKey());
            if (profile != null && profile.name().equals(minecraftName)) {
                newestActivity = activity;
                color = profile.argbColor();
            }
        }
        return color;
    }

    public boolean isSelfPeer(long peerId) {
        QuicVoiceClient current = connection.get();
        return current != null && peerId == current.selfPeerId();
    }

    public List<VoicePeerProfile> getRoomMembers() {
        List<VoicePeerProfile> result = new ArrayList<>(peerProfiles.values());
        result.sort(Comparator.comparing(VoicePeerProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public List<VoicePeerProfile> getRemoteRoomMembers() {
        QuicVoiceClient current = connection.get();
        long selfId = current == null ? Long.MIN_VALUE : current.selfPeerId();
        return getRoomMembers().stream()
                .filter(profile -> profile.peerId() != selfId)
                .toList();
    }

    public void requestRoster() {
        QuicVoiceClient current = connection.get();
        if (current != null && current.isJoined()) {
            current.requestRoster();
        }
    }

    public void setPlayerVolume(String playerName, float volume) {
        settings.setPlayerVolume(playerName, volume);
    }

    public float getPlayerVolume(String playerName) {
        return settings.playerVolume(playerName);
    }

    public void setPlayerMuted(String playerName, boolean muted) {
        settings.setPlayerMuted(playerName, muted);
    }

    public boolean isPlayerMuted(String playerName) {
        return settings.isPlayerMuted(playerName);
    }

    public synchronized void restartPlayback() {
        if (!isConnected()) {
            return;
        }
        closeAudio();
        playback = createPlayback();
        playback.start();
    }

    public float getMicrophoneLevel() {
        return microphoneLevel;
    }

    public boolean isPushToTalkDown() {
        return pushToTalkDown;
    }

    public boolean isConnected() {
        QuicVoiceClient current = connection.get();
        return settings.isEnabled() && current != null && current.isJoined();
    }

    public boolean isMicrophoneActive() {
        return settings.isEnabled() && pushToTalkDown && microphone != null && isConnected();
    }

    public boolean isEnabled() {
        return settings.isEnabled();
    }

    /**
     * Toggle the local voice feature. Disabling closes transport and audio,
     * but deliberately retains the latest invitation token in memory.
     */
    public synchronized void setEnabled(boolean enabled) {
        if (settings.isEnabled() == enabled) {
            if (enabled && connection.get() == null && pendingToken != null && config.isConfigured()) {
                connectPendingToken();
            } else {
                notifyChat(
                        enabled ? "message.justvoice.already_enabled" : "message.justvoice.already_disabled",
                        ChatFormatting.GRAY
                );
            }
            return;
        }

        settings.setEnabled(enabled);
        if (!enabled) {
            generation++;
            pushToTalkDown = false;
            microphoneUnavailable = false;
            closeResources();
            notifyChat("message.justvoice.voice_disabled", ChatFormatting.GRAY);
            return;
        }

        notifyChat("message.justvoice.voice_enabled", ChatFormatting.GREEN);
        if (pendingToken == null) {
            notifyChat("message.justvoice.waiting_for_invitation", ChatFormatting.GRAY);
            return;
        }
        connectPendingToken();
    }

    private void connectPendingToken() {
        if (!config.isConfigured()) {
            notifyChat("message.justvoice.server_not_configured", ChatFormatting.RED);
            return;
        }
        try {
            connectWithCredentials(new VoiceCredentials(
                    config.voiceHost(),
                    config.voicePort(),
                    config.certificateFile(),
                    pendingToken
            ));
        } catch (IllegalArgumentException exception) {
            pendingToken = null;
            notifyChat("message.justvoice.invalid_invitation", ChatFormatting.RED);
        }
    }

    /** @VOICE CLOSE and world disconnect discard the invitation completely. */
    public synchronized void close() {
        close(true);
    }

    public synchronized void closeSilently() {
        close(false);
    }

    private void close(boolean notifyPlayer) {
        boolean hadSession = connection.get() != null;
        boolean hadToken = pendingToken != null;
        pendingToken = null;
        generation++;
        pushToTalkDown = false;
        microphoneUnavailable = false;
        microphoneLevel = 0.0f;
        closeResources();
        if (notifyPlayer && (hadSession || hadToken)) {
            notifyChat("message.justvoice.disconnected", ChatFormatting.GRAY);
        }
    }

    private void notifyChat(String translationKey, ChatFormatting color, Object... arguments) {
        chatNotifier.accept(new ChatNotice(translationKey, color, arguments));
    }

    private static String transportChatKey(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("invalid or expired voice token")) {
            return "message.justvoice.invitation_expired";
        }
        if (normalized.contains("datagram")) {
            return "message.justvoice.protocol_error";
        }
        return "message.justvoice.connection_lost";
    }

    private void closeResources() {
        stopMicrophone();
        closeAudio();
        peerProfiles.clear();
        lastVoiceAtNanos.clear();
        QuicVoiceClient current = connection.getAndSet(null);
        if (current != null) {
            Thread.ofVirtual().name("just-voice-disconnect").start(current::close);
        }
    }

    private void stopMicrophone() {
        MicrophoneCapture current = microphone;
        microphone = null;
        microphoneLevel = 0.0f;
        if (current != null) {
            current.close();
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
