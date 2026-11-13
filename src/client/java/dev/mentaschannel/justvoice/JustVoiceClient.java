package dev.mentaschannel.justvoice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mentaschannel.justvoice.voice.VoiceClientConfig;
import dev.mentaschannel.justvoice.voice.VoiceDirective;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
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
    private KeyMapping pushToTalk;

    @Override
    public void onInitializeClient() {
        VoiceClientConfig config = VoiceClientConfig.load(FabricLoader.getInstance().getConfigDir());
        sessionManager = new VoiceSessionManager(config);

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "voice")
        );
        pushToTalk = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.justvoice.push_to_talk",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                category
        ));

        if (!config.isConfigured()) {
            LOGGER.warn("Just Voice server is not configured; edit config/just-voice.properties.");
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String content = message.getString();
            if (!VoiceDirective.hasMarker(content)) {
                return true;
            }

            // Hide control messages: INVITE contains a live, temporary voice token.
            VoiceDirective directive = VoiceDirective.parse(content);
            if (directive == null) {
                LOGGER.warn("Ignored malformed @VOICE system directive.");
                return false;
            }

            switch (directive.type()) {
                case INVITE -> sessionManager.invite(directive.token());
                case CLOSE -> sessionManager.close();
            }

            return false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client ->
                sessionManager.setPushToTalk(pushToTalk.isDown())
        );

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (sessionManager != null) {
                sessionManager.close();
            }
        });

        LOGGER.info("Just Voice client initialized. Push-to-talk: V (rebindable in Controls).");
    }
}
