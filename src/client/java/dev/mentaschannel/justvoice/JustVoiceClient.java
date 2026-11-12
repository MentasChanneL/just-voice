package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceBackendClient;
import dev.mentaschannel.justvoice.voice.VoiceClientConfig;
import dev.mentaschannel.justvoice.voice.VoiceDirective;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only entrypoint. Voice directives are accepted only from server/game
 * messages, never from player-authored chat messages.
 */
public final class JustVoiceClient implements ClientModInitializer {
    public static final String MOD_ID = "justvoice";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private VoiceSessionManager sessionManager;

    @Override
    public void onInitializeClient() {
        VoiceClientConfig config = VoiceClientConfig.load(FabricLoader.getInstance().getConfigDir());
        if (config.backendUri() != null) {
            sessionManager = new VoiceSessionManager(new VoiceBackendClient(config.backendUri()));
        } else {
            LOGGER.warn("Just Voice is installed, but the backend URL is not configured.");
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String content = message.getString();
            if (!VoiceDirective.hasMarker(content)) {
                return true;
            }

            // Hide control messages: INVITE contains a single-use ticket and
            // must not be copied into chat history or exposed on screen.
            VoiceDirective directive = VoiceDirective.parse(content);
            if (directive == null) {
                LOGGER.warn("Ignored malformed @VOICE system directive.");
                return false;
            }

            if (sessionManager == null) {
                LOGGER.warn("Ignored @VOICE directive because the backend URL is not configured.");
                return false;
            }

            switch (directive.type()) {
                case INVITE -> sessionManager.invite(
                        directive.ticket(),
                        Minecraft.getInstance().getUser().getName()
                );
                case CLOSE -> sessionManager.close();
            }

            return false;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (sessionManager != null) {
                sessionManager.close();
            }
        });

        LOGGER.info("Just Voice client initialized.");
    }
}
