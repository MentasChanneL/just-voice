package dev.mentaschannel.justvoice;

import net.minecraft.client.renderer.RenderPipelines;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Minimal Team Fortress-style speaker panel. The local microphone state is
 * represented by the supplied mic textures beside the vanilla hotbar.
 */
public final class SpeakerHudRenderer {
    private static final int PANEL_BACKGROUND = 0xB010141D;
    private static final int PANEL_BORDER = 0x603A4658;
    private static final int TEXT_COLOR = 0xFFF0F2F5;
    private static final int METER_BACKGROUND = 0xFF333B47;
    private static final int METER_COLOR = 0xFF48D597;

    private static final Identifier MIC_ON = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/mic_on.png"
    );
    private static final Identifier MIC_OFF = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/mic_off.png"
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
        if (client.player == null) {
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
        int rowHeight = 22;
        int rowGap = 3;
        int maxTextWidth = 0;
        for (VoiceSessionManager.SpeakerHudEntry speaker : speakers) {
            maxTextWidth = Math.max(maxTextWidth, client.font.width(speaker.name()));
        }

        int panelWidth = Math.max(112, Math.min(220, maxTextWidth + 54));
        int x = guiWidth - margin - panelWidth;
        int y = guiHeight - 34 - speakers.size() * (rowHeight + rowGap);

        long now = System.nanoTime();
        for (VoiceSessionManager.SpeakerHudEntry speaker : speakers) {
            float opacity = speaker.opacity(now);
            if (opacity <= 0.0f) {
                y += rowHeight + rowGap;
                continue;
            }

            int bottom = y + rowHeight;
            graphics.fill(x, y, x + panelWidth, bottom, withOpacity(PANEL_BACKGROUND, opacity));
            graphics.outline(x, y, panelWidth, rowHeight, withOpacity(PANEL_BORDER, opacity));
            graphics.fill(x, y, x + 3, bottom, withOpacity(speaker.color(), opacity));

            String label = speaker.name();
            int maxNameWidth = panelWidth - 50;
            if (client.font.width(label) > maxNameWidth) {
                label = client.font.plainSubstrByWidth(label, maxNameWidth - 8) + "…";
            }

            graphics.text(
                    client.font,
                    label,
                    x + 9,
                    y + 7,
                    withOpacity(speaker.self() ? speaker.color() : TEXT_COLOR, opacity),
                    true
            );

            if (speaker.self()) {
                drawMiniMeter(
                        graphics,
                        x + panelWidth - 31,
                        y + 8,
                        22,
                        6,
                        speaker.inputLevel(),
                        opacity
                );
            } else {
                graphics.fill(
                        x + panelWidth - 12,
                        y + 8,
                        x + panelWidth - 6,
                        y + 14,
                        withOpacity(speaker.color(), opacity)
                );
            }

            y += rowHeight + rowGap;
        }
    }

    private static void renderMicrophoneIcon(
            GuiGraphicsExtractor graphics,
            VoiceSessionManager manager
    ) {
        if (!manager.isConnected()) {
            return;
        }

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int hotbarLeft = guiWidth / 2 - 91;
        int hotbarRight = hotbarLeft + 182;
        int iconSize = 16;
        int x = hotbarRight + 6;

        // On narrow GUI scales, put the icon to the left of the hotbar instead.
        if (x + iconSize > guiWidth - 3) {
            x = hotbarLeft - iconSize - 6;
        }
        x = Math.max(3, Math.min(x, guiWidth - iconSize - 3));

        // Center the 16x16 icon against the vanilla hotbar.
        int y = guiHeight - 22 + 3;
        Identifier texture = manager.isMicrophoneActive() ? MIC_ON : MIC_OFF;
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f,
                iconSize, iconSize, iconSize, iconSize);
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
