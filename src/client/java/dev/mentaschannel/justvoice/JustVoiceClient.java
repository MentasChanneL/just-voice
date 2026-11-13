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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only entrypoint. Voice directives are accepted only from server
 * action-bar messages; player-authored chat is never used for credentials.
 */
public final class JustVoiceClient implements ClientModInitializer {
    public static final String MOD_ID = "justvoice";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final long TRANSIENT_HUD_REFRESH_NANOS = 2_500_000_000L;
    private static final long PERSISTENT_HUD_REFRESH_NANOS = 750_000_000L;

    private static volatile VoiceSessionManager activeSessionManager;

    private VoiceSessionManager sessionManager;
    private KeyMapping pushToTalk;
    private VoiceSessionManager.HudStatus lastHudStatus;
    private long lastHudMessageAtNanos;

    @Override
    public void onInitializeClient() {
        VoiceClientConfig config = VoiceClientConfig.load(FabricLoader.getInstance().getConfigDir());
        sessionManager = new VoiceSessionManager(config);
        activeSessionManager = sessionManager;
        SpeakerHudRenderer.register();
        VoiceOverheadRenderLayer.register();

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
            if (!VoiceDirective.hasMarker(message.getString())) {
                return true;
            }

            // Overlay system-chat messages are supported as a fallback. The regular
            // /title actionbar packet is handled by ClientPacketListenerMixin below.
            if (!overlay) {
                LOGGER.warn("Ignored Just Voice directive delivered outside the action bar.");
                return false;
            }
            return !consumeActionBarDirective(message);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            sessionManager.setPushToTalk(pushToTalk.isDown());
            sessionManager.tick();
            updateHud(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (sessionManager != null) {
                sessionManager.close();
            }
            lastHudStatus = null;
        });

        LOGGER.info("Just Voice client initialized. Push-to-talk: V (rebindable in Controls).");
    }

    public static VoiceSessionManager getActiveSessionManager() {
        return activeSessionManager;
    }

    /**
     * Consumes a padded server action-bar directive. Returns true when the
     * message must be canceled, including malformed or pre-initialization cases.
     */
    public static boolean consumeActionBarDirective(Component message) {
        if (!VoiceDirective.hasMarker(message.getString())) {
            return false;
        }

        VoiceSessionManager manager = activeSessionManager;
        if (manager == null) {
            LOGGER.warn("Discarded Just Voice directive before client initialization.");
            return true;
        }

        VoiceDirective directive = VoiceDirective.parse(message.getString());
        if (directive == null) {
            LOGGER.warn("Ignored malformed Just Voice action-bar directive.");
            return true;
        }

        switch (directive.type()) {
            case INVITE -> manager.invite(directive.token());
            case CLOSE -> manager.close();
        }
        return true;
    }

    private void updateHud(Minecraft client) {
        VoiceSessionManager.HudStatus status = sessionManager.getHudStatus();
        long now = System.nanoTime();

        if (status == null) {
            if (lastHudStatus != null && lastHudStatus.persistent()) {
                client.gui.hud.setOverlayMessage(Component.empty(), false);
            }
            lastHudStatus = null;
            return;
        }

        long refreshInterval = status.persistent()
                ? PERSISTENT_HUD_REFRESH_NANOS
                : TRANSIENT_HUD_REFRESH_NANOS;
        boolean changed = !status.equals(lastHudStatus);
        boolean refresh = now - lastHudMessageAtNanos >= refreshInterval;

        if (changed || refresh) {
            ChatFormatting color = switch (status.tone()) {
                case INFO -> ChatFormatting.GRAY;
                case SUCCESS, ACTIVE -> ChatFormatting.GREEN;
                case WARNING -> ChatFormatting.YELLOW;
                case ERROR -> ChatFormatting.RED;
            };
            client.gui.hud.setOverlayMessage(
                    Component.literal(status.message()).withStyle(color),
                    false
            );
            lastHudStatus = status;
            lastHudMessageAtNanos = now;
        }
    }
}
