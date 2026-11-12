package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the token sent directly by the Minecraft world until CLOSE or disconnect.
 * The backend never needs to redeem the token or know which player owns it.
 */
public final class VoiceSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");

    private final VoiceClientConfig config;
    private final AtomicReference<VoiceCredentials> credentials = new AtomicReference<>();

    public VoiceSessionManager(VoiceClientConfig config) {
        this.config = config;
    }

    public void invite(String token, String playerName) {
        credentials.set(null);
        if (!config.isConfigured()) {
            LOGGER.warn("Cannot accept Just Voice invitation: Mumble host is not configured.");
            return;
        }

        try {
            credentials.set(new VoiceCredentials(
                    config.mumbleHost(),
                    config.mumblePort(),
                    playerName,
                    token
            ));
            LOGGER.info("Just Voice invitation accepted; Mumble credentials are ready.");
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignored invalid Just Voice invitation token.");
        }
    }

    public void close() {
        credentials.set(null);
    }

    public Optional<VoiceCredentials> credentials() {
        return Optional.ofNullable(credentials.get());
    }
}
