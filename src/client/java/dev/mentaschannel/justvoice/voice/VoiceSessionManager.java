package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds redeemed credentials only in memory until CLOSE or world disconnect.
 *
 * The request generation prevents a slow INVITE response from re-opening voice
 * after a later CLOSE directive has already been received.
 */
public final class VoiceSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");

    private final VoiceBackendClient backendClient;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<VoiceCredentials> credentials = new AtomicReference<>();

    public VoiceSessionManager(VoiceBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    public void invite(String ticket, String playerName) {
        long requestGeneration = generation.incrementAndGet();
        credentials.set(null);

        backendClient.redeemInvite(ticket, playerName).whenComplete((redeemed, throwable) -> {
            if (generation.get() != requestGeneration) {
                return;
            }

            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                LOGGER.warn("Could not redeem Just Voice invitation: {}", cause.getMessage());
                return;
            }

            credentials.set(redeemed);
            LOGGER.info("Just Voice invitation accepted; voice credentials are ready.");
        });
    }

    public void close() {
        generation.incrementAndGet();
        credentials.set(null);
    }

    public Optional<VoiceCredentials> credentials() {
        return Optional.ofNullable(credentials.get());
    }
}
