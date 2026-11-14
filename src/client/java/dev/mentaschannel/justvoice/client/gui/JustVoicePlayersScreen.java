package dev.mentaschannel.justvoice.client.gui;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoicePeerProfile;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import dev.mentaschannel.justvoice.voice.VoiceSettings;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.List;

/** Per-player volume and mute controls. Preferences are persisted by case-insensitive nickname. */
public final class JustVoicePlayersScreen extends Screen {
    private static final int ROWS_PER_PAGE = 5;
    private static final int ROW_HEIGHT = 34;
    private static final Identifier HAS_MUTE = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/has_mute.png"
    );
    private static final Identifier CAN_TALK = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/can_talk.png"
    );

    private record MuteControl(String playerName, int x, int y) {
    }

    private final Screen parent;
    private final VoiceSessionManager session;
    private final VoiceSettings settings;
    private final int page;
    private List<VoicePeerProfile> players = List.of();
    private final List<MuteControl> muteControls = new ArrayList<>();
    private int pageCount;

    public JustVoicePlayersScreen(Screen parent, int page) {
        super(Component.translatable("screen.justvoice.players_title"));
        this.parent = parent;
        this.session = JustVoiceClient.getActiveSessionManager();
        this.settings = JustVoiceClient.getVoiceSettings();
        this.page = Math.max(0, page);
    }

    @Override
    protected void init() {
        muteControls.clear();
        if (session != null) {
            session.requestRoster();
            players = session.getRemoteRoomMembers();
        } else {
            players = List.of();
        }
        pageCount = Math.max(1, (players.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int safePage = Math.min(page, pageCount - 1);

        int panelWidth = Math.min(400, width - 24);
        int left = (width - panelWidth) / 2;
        int top = 58;
        int first = safePage * ROWS_PER_PAGE;
        int last = Math.min(players.size(), first + ROWS_PER_PAGE);

        for (int index = first; index < last; index++) {
            VoicePeerProfile profile = players.get(index);
            int row = index - first;
            int y = top + row * ROW_HEIGHT;
            int sliderX = left + 112;
            int muteX = left + panelWidth - 28;
            int sliderWidth = Math.max(90, muteX - sliderX - 8);
            String playerName = profile.name();

            AbstractSliderButton volumeSlider = new AbstractSliderButton(
                    sliderX,
                    y + 7,
                    sliderWidth,
                    20,
                    Component.empty(),
                    settings.playerVolume(playerName) / 2.0
            ) {
                {
                    updateMessage();
                }

                @Override
                protected void updateMessage() {
                    setMessage(Component.translatable(
                            "screen.justvoice.player_volume",
                            Math.round((float) value * 200)
                    ));
                }

                @Override
                protected void applyValue() {
                    settings.setPlayerVolume(playerName, (float) value * 2.0f);
                    updateMessage();
                }
            };
            volumeSlider.active = true;
            volumeSlider.setAlpha(1.0f);
            addRenderableWidget(volumeSlider);

            Button muteButton = Button.builder(
                    Component.empty(),
                    button -> {
                        settings.setPlayerMuted(playerName, !settings.isPlayerMuted(playerName));
                        button.setTooltip(Tooltip.create(muteTooltip(playerName)));
                    }
            )
                    .bounds(muteX, y + 7, 24, 20)
                    .tooltip(Tooltip.create(muteTooltip(playerName)))
                    .build();
            muteButton.active = true;
            muteButton.setAlpha(1.0f);
            addRenderableWidget(muteButton);
            muteControls.add(new MuteControl(playerName, muteX, y + 7));
        }

        int controlsY = top + ROWS_PER_PAGE * ROW_HEIGHT + 6;
        if (safePage > 0) {
            addRenderableWidget(Button.builder(Component.translatable("screen.justvoice.previous"), button ->
                            Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(parent, safePage - 1)))
                    .bounds(left, controlsY, 88, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("screen.justvoice.refresh"), button -> {
                    if (session != null) {
                        session.requestRoster();
                    }
                    Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(parent, safePage));
                })
                .bounds(left + panelWidth / 2 - 45, controlsY, 90, 20).build());

        if (safePage + 1 < pageCount) {
            addRenderableWidget(Button.builder(Component.translatable("screen.justvoice.next"), button ->
                            Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(parent, safePage + 1)))
                    .bounds(left + panelWidth - 88, controlsY, 88, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private Component muteTooltip(String playerName) {
        return Component.translatable(
                settings.isPlayerMuted(playerName)
                        ? "screen.justvoice.unmute_tooltip"
                        : "screen.justvoice.mute_tooltip"
        );
    }

    private PlayerSkin resolvePlayerSkin(String playerName) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            PlayerInfo info = client.getConnection().getPlayerInfoIgnoreCase(playerName);
            if (info != null && info.getSkin() != null) {
                return info.getSkin();
            }
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 22, 0xFFFFFFFF);

        int panelWidth = Math.min(400, width - 24);
        int left = (width - panelWidth) / 2;
        int top = 58;
        int first = Math.min(page, Math.max(0, pageCount - 1)) * ROWS_PER_PAGE;
        int last = Math.min(players.size(), first + ROWS_PER_PAGE);

        if (players.isEmpty()) {
            graphics.centeredText(
                    font,
                    Component.translatable("screen.justvoice.no_peers"),
                    width / 2,
                    top + 22,
                    0xFFAAAAAA
            );
            graphics.centeredText(
                    font,
                    Component.translatable("screen.justvoice.players_help"),
                    width / 2,
                    top + 40,
                    0xFF888888
            );
            return;
        }

        for (int index = first; index < last; index++) {
            VoicePeerProfile profile = players.get(index);
            int row = index - first;
            int y = top + row * ROW_HEIGHT;
            int color = profile.argbColor();
            graphics.fill(left, y, left + panelWidth, y + ROW_HEIGHT - 2, 0xA810141D);
            graphics.outline(left, y, panelWidth, ROW_HEIGHT - 2, color);
            PlayerFaceExtractor.extractRenderState(
                    graphics,
                    resolvePlayerSkin(profile.name()),
                    left + 6,
                    y + 8,
                    18,
                    0xFFFFFFFF
            );
            String name = font.plainSubstrByWidth(profile.name(), 72);
            graphics.text(font, name, left + 30, y + 12, color, true);
        }

        for (MuteControl control : muteControls) {
            Identifier icon = settings.isPlayerMuted(control.playerName()) ? HAS_MUTE : CAN_TALK;
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    icon,
                    control.x() + 4,
                    control.y() + 2,
                    0.0f,
                    0.0f,
                    16,
                    16,
                    16,
                    16
            );
        }

        graphics.centeredText(
                font,
                Component.translatable("screen.justvoice.page", Math.min(page, pageCount - 1) + 1, pageCount),
                width / 2,
                top + ROWS_PER_PAGE * ROW_HEIGHT + 31,
                0xFFAAAAAA
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
