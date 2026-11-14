package dev.mentaschannel.justvoice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mentaschannel.justvoice.voice.VoiceClientConfig;
import dev.mentaschannel.justvoice.voice.VoiceDirective;
import dev.mentaschannel.justvoice.voice.VoicePeerProfile;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-only entrypoint. Voice directives are accepted only from server
 * action-bar messages; player-authored chat is never used for credentials.
 */
public final class JustVoiceClient implements ClientModInitializer {
    public static final String MOD_ID = "justvoice";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile VoiceSessionManager activeSessionManager;
    private static volatile VoiceSettings voiceSettings;

    private VoiceSessionManager sessionManager;
    private KeyMapping pushToTalk;

    @Override
    public void onInitializeClient() {
        VoiceClientConfig config = VoiceClientConfig.load(FabricLoader.getInstance().getConfigDir());
        voiceSettings = VoiceSettings.load(FabricLoader.getInstance().getConfigDir());
        sessionManager = new VoiceSessionManager(config, voiceSettings, JustVoiceClient::showChatNotice);
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

        registerClientCommands();

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
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (sessionManager != null) {
                sessionManager.closeSilently();
            }
        });

        LOGGER.info("Just Voice client initialized. Push-to-talk: V (rebindable in Controls).");
    }

    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(Commands.literal("justvoice")
                        .then(Commands.literal("on").executes(context -> {
                            sessionManager.setEnabled(true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            sessionManager.setEnabled(false);
                            return 1;
                        }))
                        .then(Commands.literal("list").executes(context -> {
                            showRoomRoster(context.getSource()::sendFeedback);
                            return 1;
                        }))
                        .then(Commands.literal("players").executes(context -> {
                            showRoomRoster(context.getSource()::sendFeedback);
                            return 1;
                        }))
                        .then(Commands.literal("settings").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            client.gui.setScreen(new JustVoiceConfigScreen(client.gui.screen()));
                            return 1;
                        }))
                )
        );
    }

    private void showRoomRoster(java.util.function.Consumer<Component> output) {
        if (!sessionManager.isConnected()) {
            output.accept(Component.translatable("command.justvoice.not_connected")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        sessionManager.requestRoster();
        List<VoicePeerProfile> members = sessionManager.getRoomMembers();
        output.accept(Component.translatable("command.justvoice.roster_header", members.size())
                .withStyle(ChatFormatting.GRAY));

        if (members.isEmpty()) {
            output.accept(Component.translatable("command.justvoice.roster_empty")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        for (VoicePeerProfile member : members) {
            String label = member.name();
            if (sessionManager.isSelfPeer(member.peerId())) {
                label += " (you)";
            }
            output.accept(Component.literal("• " + label)
                    .withStyle(style -> style.withColor(member.rgbColor())));
        }
    }

    public static VoiceSessionManager getActiveSessionManager() {
        return activeSessionManager;
    }

    public static VoiceSettings getVoiceSettings() {
        return voiceSettings;
    }

    private static void showChatNotice(VoiceSessionManager.ChatNotice notice) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(
                Component.translatable(notice.translationKey(), notice.arguments())
                        .withStyle(notice.color())
        ));
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
}
