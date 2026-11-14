package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoicePeerProfile;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;

/** Per-player volume and mute controls. Preferences are persisted by case-insensitive nickname. */
public final class JustVoicePlayersScreen extends Screen {
    private static final int ROWS_PER_PAGE = 5;
    private static final int ROW_HEIGHT = 38;
    private static final Identifier PLACEHOLDER = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/mics/v2.png"
    );

    private final Screen parent;
    private final VoiceSessionManager session;
    private final VoiceSettings settings;
    private final int page;
    private List<VoicePeerProfile> players = List.of();
    private int pageCount;

    public JustVoicePlayersScreen(Screen parent) {
        this(parent, 0);
    }

    private JustVoicePlayersScreen(Screen parent, int page) {
        super(Component.translatable("screen.justvoice.players_title"));
        this.parent = parent;
        this.session = JustVoiceClient.getActiveSessionManager();
        this.settings = JustVoiceClient.getVoiceSettings();
        this.page = Math.max(0, page);
    }

    @Override
    protected void init() {
        if (session != null) {
            session.requestRoster();
            players = session.getRemoteRoomMembers();
        } else {
            players = List.of();
        }
        pageCount = Math.max(1, (players.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int safePage = Math.min(page, pageCount - 1);

        int panelWidth = Math.min(480, width - 24);
        int left = (width - panelWidth) / 2;
        int top = 58;
        int first = safePage * ROWS_PER_PAGE;
        int last = Math.min(players.size(), first + ROWS_PER_PAGE);

        for (int index = first; index < last; index++) {
            VoicePeerProfile profile = players.get(index);
            int row = index - first;
            int y = top + row * ROW_HEIGHT;
            int sliderX = left + 145;
            int muteX = left + panelWidth - 83;
            int sliderWidth = Math.max(90, muteX - sliderX - 8);
            String playerName = profile.name();

            addRenderableWidget(new AbstractSliderButton(
                    sliderX,
                    y + 8,
                    sliderWidth,
                    20,
                    Component.empty(),
                    settings.playerVolume(playerName) / 2.0
            ) {
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
            });

            Button muteButton = addRenderableWidget(Button.builder(
                    muteLabel(playerName),
                    button -> {
                        settings.setPlayerMuted(playerName, !settings.isPlayerMuted(playerName));
                        button.setMessage(muteLabel(playerName));
                    }
            ).bounds(muteX, y + 8, 78, 20).build());
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

    private Component muteLabel(String playerName) {
        return Component.translatable(
                settings.isPlayerMuted(playerName)
                        ? "screen.justvoice.unmute"
                        : "screen.justvoice.mute"
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

        int panelWidth = Math.min(480, width - 24);
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
                    y + 9,
                    20,
                    0xFFFFFFFF
            );
            String name = font.plainSubstrByWidth(profile.name(), 104);
            graphics.text(font, name, left + 33, y + 14, color, true);
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
