package dev.mentaschannel.justvoice;

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
        sessionManager = new VoiceSessionManager(config);
        if (!config.isConfigured()) {
            LOGGER.warn("Just Voice Mumble host is not configured; edit config/just-voice.properties.");
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String content = message.getString();
            if (!VoiceDirective.hasMarker(content)) {
                return true;
            }

            // Hide control messages: INVITE contains a live Mumble ACL token.
            VoiceDirective directive = VoiceDirective.parse(content);
            if (directive == null) {
                LOGGER.warn("Ignored malformed @VOICE system directive.");
                return false;
            }

            switch (directive.type()) {
                case INVITE -> sessionManager.invite(
                        directive.token(),
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
