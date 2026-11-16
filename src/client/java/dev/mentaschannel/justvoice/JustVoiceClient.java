package dev.mentaschannel.justvoice;

import com.mojang.blaze3d.platform.InputConstants;

import dev.mentaschannel.justvoice.client.assets.MicIconManager;
import dev.mentaschannel.justvoice.client.gui.JustVoiceConfigScreen;
import dev.mentaschannel.justvoice.client.gui.JustVoicePlayersScreen;
import dev.mentaschannel.justvoice.client.render.SpeakerHudRenderer;
import dev.mentaschannel.justvoice.client.render.VoiceOverheadRenderLayer;
import dev.mentaschannel.justvoice.voice.VoiceClientConfig;
import dev.mentaschannel.justvoice.voice.VoiceDirective;
import dev.mentaschannel.justvoice.voice.VoicePeerProfile;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import dev.mentaschannel.justvoice.voice.VoiceSettings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private KeyMapping openSettings;
    private KeyMapping openPlayers;
    private boolean microphoneIconsInitialized;
    private static volatile boolean togglePushToTalkDown;

    @Override
    public void onInitializeClient() {
        var modConfigDirectory = FabricLoader.getInstance().getConfigDir().resolve("just-voice");
        VoiceClientConfig config = VoiceClientConfig.load(modConfigDirectory);
        voiceSettings = VoiceSettings.load(modConfigDirectory);
        sessionManager = new VoiceSessionManager(config, voiceSettings);
        activeSessionManager = sessionManager;
        SpeakerHudRenderer.register();
        VoiceOverheadRenderLayer.register();
        registerPauseMenuButton();

        String category = "key.category.justvoice.voice";
        pushToTalk = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.justvoice.push_to_talk",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                category
        ));
        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.justvoice.open_settings",
                InputConstants.Type.KEYBOARD,
                0,
                category
        ));
        openPlayers = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.justvoice.open_players",
                InputConstants.Type.KEYBOARD,
                0,
                category
        ));

        if (!config.isConfigured()) {
            LOGGER.warn("Just Voice server is not configured; edit config/just-voice/just-voice.properties.");
        }

        registerClientCommandManager();

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

        // NativeImage decoding and DynamicTexture registration require the
        // Minecraft render device. Initialize custom icons on the first client
        // tick instead of during Fabric's early mod-initialization phase.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!microphoneIconsInitialized) {
                MicIconManager.reload(voiceSettings);
                microphoneIconsInitialized = true;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.screen == null) {
                while (openSettings.consumeClick()) {
                    openSettingsScreen();
                }
                while (openPlayers.consumeClick()) {
                    openPlayersScreen();
                }
            }

            if (voiceSettings.isTogglePushToTalk()) {
                while (pushToTalk.consumeClick()) {
                    if (client.screen == null) {
                        togglePushToTalkDown = !togglePushToTalkDown;
                    }
                }
                sessionManager.setPushToTalk(togglePushToTalkDown);
            } else {
                togglePushToTalkDown = false;
                sessionManager.setPushToTalk(client.screen == null && pushToTalk.isDown());
                while (pushToTalk.consumeClick()) {
                    // Drain queued click events; hold mode reads the physical key state above.
                }
            }
            sessionManager.tick();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, clientConnection) -> {
            resetPushToTalkToggle();
            if (sessionManager != null) {
                sessionManager.closeSilently();
            }
        });

        LOGGER.info("Just Voice client initialized");
    }

    /**
     * Adds the microphone shortcut to the small icon rows in both the pause
     * screen and the title screen.
     */
    private void registerPauseMenuButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            int minimumButtons;
            if (screen instanceof PauseScreen pauseScreen && pauseScreen.showsPauseMenu()) {
                minimumButtons = 3;
            } else if (screen instanceof TitleScreen) {
                minimumButtons = 2;
            } else {
                return;
            }

            List<AbstractWidget> widgets = Screens.getWidgets(screen);
            List<AbstractWidget> iconButtons = findPauseIconRow(widgets, minimumButtons);
            if (iconButtons.isEmpty()) {
                LOGGER.warn("Could not find a small icon row on {}; Just Voice shortcut was not added.",
                        screen.getClass().getSimpleName());
                return;
            }

            iconButtons.sort(Comparator.comparingInt(AbstractWidget::getX));
            AbstractWidget first = iconButtons.getFirst();
            int iconWidth = first.getWidth();
            int iconHeight = first.getHeight();
            int iconY = first.getY();

            if (iconButtons.stream().anyMatch(button ->
                    Math.abs(button.getWidth() - iconWidth) > 1
                            || Math.abs(button.getHeight() - iconHeight) > 1
                            || Math.abs(button.getY() - iconY) > 1)) {
                LOGGER.warn("Small icon row on {} uses inconsistent button sizes; "
                        + "Just Voice shortcut was not added.", screen.getClass().getSimpleName());
                return;
            }

            int gap = inferIconGap(iconButtons);
            int itemCount = iconButtons.size() + 1;
            int rowWidth = itemCount * iconWidth + (itemCount - 1) * gap;
            int startX = (screen.width - rowWidth) / 2;

            for (int index = 0; index < iconButtons.size(); index++) {
                AbstractWidget existingButton = iconButtons.get(index);
                existingButton.setRectangle(
                        iconWidth,
                        iconHeight,
                        startX + index * (iconWidth + gap),
                        iconY
                );
            }

            int voiceButtonX = startX + iconButtons.size() * (iconWidth + gap);
            Button voiceButton = new PauseMicButton(
                    voiceButtonX,
                    iconY,
                    iconWidth,
                    iconHeight,
                    () -> client.setScreen(new JustVoicePlayersScreen(screen, 0))
            );
            widgets.add(voiceButton);
        });
    }

    private static List<AbstractWidget> findPauseIconRow(
            List<AbstractWidget> widgets,
            int minimumButtons
    ) {
        Map<Integer, List<AbstractWidget>> rows = new HashMap<>();
        for (AbstractWidget widget : widgets) {
            int width = widget.getWidth();
            int height = widget.getHeight();
            if (width < 16 || width > 24 || height < 16 || height > 24
                    || Math.abs(width - height) > 2) {
                continue;
            }
            rows.computeIfAbsent(widget.getY(), ignored -> new ArrayList<>()).add(widget);
        }

        return rows.values().stream()
                .max(Comparator.comparingInt(List::size))
                .filter(row -> row.size() >= minimumButtons)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }

    private static int inferIconGap(List<? extends AbstractWidget> iconButtons) {
        if (iconButtons.size() < 2) {
            return 4;
        }

        List<? extends AbstractWidget> ordered = iconButtons.stream()
                .sorted(Comparator.comparingInt(AbstractWidget::getX))
                .toList();
        List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            int gap = ordered.get(index).getX() - ordered.get(index - 1).getRight();
            if (gap >= 0 && gap <= 12) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 4;
        }
        gaps.sort(Integer::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    /** A square microphone button with a visible vanilla-like pixel frame. */
    private static final class PauseMicButton extends Button {
        private PauseMicButton(int x, int y, int width, int height, Runnable onPress) {
            super(
                    x,
                    y,
                    width,
                    height,
                    Component.empty(),
                    button -> onPress.run(),
                    DEFAULT_NARRATION
            );
            setTooltip(Tooltip.create(Component.translatable("screen.justvoice.pause_button")));
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics,
                int mouseX,
                int mouseY,
                float partialTick
        ) {
            String textureName = !active
                    ? "mic_button_disabled.png"
                    : isHoveredOrFocused()
                            ? "mic_button_highlighted.png"
                            : "mic_button.png";
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                    MOD_ID,
                    "textures/gui/sprites/widget/" + textureName
            );
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    texture,
                    getX(),
                    getY(),
                    0.0f,
                    0.0f,
                    getWidth(),
                    getHeight(),
                    20,
                    20,
                    20,
                    20,
                    0x00FFFFFF | (Math.round(getAlpha() * 255.0f) << 24)
            );
        }
    }

    private void registerClientCommandManager() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("justvoice")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            sessionManager.setEnabled(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            resetPushToTalkToggle();
                            sessionManager.setEnabled(false);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reconnect").executes(context -> {
                            sessionManager.reconnect();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("list").executes(context -> {
                            showRoomRoster();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("players").executes(context -> {
                            openPlayersScreen();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("settings").executes(context -> {
                            openSettingsScreen();
                            return 1;
                        }))
                )
        );
    }

    private void openSettingsScreen() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                client.setScreen(new JustVoiceConfigScreen(client.screen));
                JustVoiceChat.info("command.justvoice.settings_opened");
            } catch (RuntimeException exception) {
                LOGGER.error("Could not open Just Voice settings screen.", exception);
                JustVoiceChat.error("command.justvoice.screen_open_failed");
            }
        });
    }

    private void openPlayersScreen() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                client.setScreen(new JustVoicePlayersScreen(client.screen, 0));
                JustVoiceChat.info("command.justvoice.manage_opened");
            } catch (RuntimeException exception) {
                LOGGER.error("Could not open Just Voice participant screen.", exception);
                JustVoiceChat.error("command.justvoice.screen_open_failed");
            }
        });
    }

    private void showRoomRoster() {
        if (!sessionManager.isConnected()) {
            JustVoiceChat.error("command.justvoice.not_connected");
            return;
        }

        sessionManager.requestRoster();
        List<VoicePeerProfile> members = sessionManager.getRoomMembers();
        JustVoiceChat.info("command.justvoice.roster_header", members.size());

        if (members.isEmpty()) {
            JustVoiceChat.warn("command.justvoice.roster_empty");
            return;
        }

        for (VoicePeerProfile member : members) {
            String label = member.name();
            if (sessionManager.isSelfPeer(member.peerId())) {
                label += " (you)";
            }
            JustVoiceChat.info("command.justvoice.roster_member", label);
        }
    }

    public static VoiceSessionManager getActiveSessionManager() {
        return activeSessionManager;
    }

    public static VoiceSettings getVoiceSettings() {
        return voiceSettings;
    }

    public static void resetPushToTalkToggle() {
        togglePushToTalkDown = false;
        VoiceSessionManager manager = activeSessionManager;
        if (manager != null) {
            manager.setPushToTalk(false);
        }
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
            case CLOSE -> {
                resetPushToTalkToggle();
                manager.close();
            }
        }
        return true;
    }
}
