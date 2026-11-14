package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;

/**
 * Team-coloured speaker panel. The local microphone state is represented by
 * one translucent/opaque icon above the hotbar, clear of the attack indicator.
 */
public final class SpeakerHudRenderer {
    private static final int TEXT_COLOR = 0xFFF0F2F5;
    private static final int METER_BACKGROUND = 0xFF333B47;
    private static final int METER_COLOR = 0xFF48D597;

    private static final int AVATAR_SIZE = 16;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_GAP = 3;

    private static final Identifier MICROPHONE = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/mics/v2.png"
    );

    private SpeakerHudRenderer() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(JustVoiceClient.MOD_ID, "speaker_hud"),
                SpeakerHudRenderer::render
        );
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.hud.isHidden()) {
            return;
        }

        VoiceSessionManager manager = JustVoiceClient.getActiveSessionManager();
        if (manager == null) {
            return;
        }

        renderSpeakers(graphics, client, manager.getActiveSpeakers());
        renderMicrophoneIcon(graphics, manager);
    }

    private static void renderSpeakers(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            List<VoiceSessionManager.SpeakerHudEntry> speakers
    ) {
        if (speakers.isEmpty()) {
            return;
        }

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int margin = 8;
        int maxTextWidth = 0;
        for (VoiceSessionManager.SpeakerHudEntry speaker : speakers) {
            maxTextWidth = Math.max(maxTextWidth, client.font.width(speaker.name()));
        }

        int panelWidth = Math.max(140, Math.min(240, maxTextWidth + 70));
        int x = guiWidth - margin - panelWidth;
        int y = guiHeight - 34 - speakers.size() * (ROW_HEIGHT + ROW_GAP);

        long now = System.nanoTime();
        for (VoiceSessionManager.SpeakerHudEntry speaker : speakers) {
            float opacity = speaker.opacity(now);
            if (opacity <= 0.0f) {
                y += ROW_HEIGHT + ROW_GAP;
                continue;
            }

            int bottom = y + ROW_HEIGHT;
            int background = tintedBackground(speaker.color());
            graphics.fill(
                    x,
                    y,
                    x + panelWidth,
                    bottom,
                    withOpacity(background, opacity)
            );
            graphics.outline(
                    x,
                    y,
                    panelWidth,
                    ROW_HEIGHT,
                    withOpacity(speaker.color(), opacity)
            );

            PlayerSkin skin = resolvePlayerSkin(client, speaker.name());
            PlayerFaceExtractor.extractRenderState(
                    graphics,
                    skin,
                    x + 4,
                    y + 4,
                    AVATAR_SIZE,
                    withOpacity(0xFFFFFFFF, opacity)
            );

            String label = speaker.name();
            int textX = x + 26;
            int maxNameWidth = panelWidth - 66;
            if (client.font.width(label) > maxNameWidth) {
                label = client.font.plainSubstrByWidth(label, maxNameWidth - 8) + "…";
            }

            graphics.text(
                    client.font,
                    label,
                    textX,
                    y + 8,
                    withOpacity(TEXT_COLOR, opacity),
                    true
            );

            if (speaker.self()) {
                drawMiniMeter(
                        graphics,
                        x + panelWidth - 31,
                        y + 9,
                        22,
                        6,
                        speaker.inputLevel(),
                        opacity
                );
            } else {
                graphics.fill(
                        x + panelWidth - 11,
                        y + 9,
                        x + panelWidth - 5,
                        y + 15,
                        withOpacity(speaker.color(), opacity)
                );
            }

            y += ROW_HEIGHT + ROW_GAP;
        }
    }

    /**
     * Skin profiles are already known to the Minecraft client from the player
     * list. Do not download skin URLs or ask the voice server for textures.
     */
    private static PlayerSkin resolvePlayerSkin(Minecraft client, String playerName) {
        if (client.getConnection() != null) {
            PlayerInfo playerInfo = client.getConnection().getPlayerInfoIgnoreCase(playerName);
            if (playerInfo != null) {
                PlayerSkin skin = playerInfo.getSkin();
                if (skin != null) {
                    return skin;
                }
            }
        }

        return DefaultPlayerSkin.getDefaultSkin();
    }

    private static void renderMicrophoneIcon(
            GuiGraphicsExtractor graphics,
            VoiceSessionManager manager
    ) {
        if (!manager.isConnected()) {
            return;
        }

        int iconSize = 16;
        int screenCenter = graphics.guiWidth() / 2;
        int hotbarRight = screenCenter + 91;
        AttackIndicatorStatus attackIndicator =
                Minecraft.getInstance().options.attackIndicator().get();
        boolean attackIndicatorOnHotbar = attackIndicator == AttackIndicatorStatus.HOTBAR;

        // Normally sit beside the hotbar. When Minecraft draws its attack
        // indicator there, move the mic icon one slot farther to the right.
        int x = hotbarRight + (attackIndicatorOnHotbar ? 24 : 4);
        int y = graphics.guiHeight() - iconSize - 4;

        // Keep the icon on the right side of the hotbar even at small GUI scales.
        x = Math.min(x, graphics.guiWidth() - iconSize - 2);
        x = Math.max(2, x);
        int tint = manager.isMicrophoneActive() ? 0xFFFFFFFF : 0x66FFFFFF;

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                MICROPHONE,
                x,
                y,
                0.0f,
                0.0f,
                iconSize,
                iconSize,
                iconSize,
                iconSize,
                tint
        );
    }

    private static int tintedBackground(int argb) {
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;

        // A dark, translucent version of the player's own presentation color.
        red = Math.round(red * 0.18f);
        green = Math.round(green * 0.18f);
        blue = Math.round(blue * 0.18f);

        return 0xB8000000 | (red << 16) | (green << 8) | blue;
    }

    private static void drawMiniMeter(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            float rawLevel,
            float opacity
    ) {
        float level = Math.max(0.0f, Math.min(1.0f, rawLevel));
        graphics.fill(x, y, x + width, y + height, withOpacity(METER_BACKGROUND, opacity));
        int filledWidth = Math.round(width * level);
        if (filledWidth > 0) {
            graphics.fill(x, y, x + filledWidth, y + height, withOpacity(METER_COLOR, opacity));
        }
    }

    private static int withOpacity(int color, float opacity) {
        int originalAlpha = (color >>> 24) & 0xff;
        int alpha = Math.round(originalAlpha * Math.max(0.0f, Math.min(1.0f, opacity)));
        return (color & 0x00ffffff) | (alpha << 24);
    }
}
