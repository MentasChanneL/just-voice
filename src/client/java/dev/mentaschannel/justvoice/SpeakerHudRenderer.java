package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Minimal Team Fortress-style speaker panel and local microphone meter.
 * All HUD rendering is client-side; no extra network traffic is needed.
 */
public final class SpeakerHudRenderer {
    private static final int PANEL_BACKGROUND = 0xB010141D;
    private static final int PANEL_BORDER = 0x603A4658;
    private static final int TEXT_COLOR = 0xFFF0F2F5;
    private static final int MUTED_TEXT = 0xFF9AA4B2;
    private static final int METER_BACKGROUND = 0xFF333B47;
    private static final int METER_COLOR = 0xFF48D597;

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
        renderMicrophone(graphics, client, manager);
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

        for (VoiceSessionManager.SpeakerHudEntry speaker : speakers) {
            int bottom = y + rowHeight;
            graphics.fill(x, y, x + panelWidth, bottom, PANEL_BACKGROUND);
            graphics.outline(x, y, panelWidth, rowHeight, PANEL_BORDER);
            graphics.fill(x, y, x + 3, bottom, speaker.color());

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
                    speaker.self() ? speaker.color() : TEXT_COLOR,
                    true
            );

            if (speaker.self()) {
                drawMiniMeter(
                        graphics,
                        x + panelWidth - 31,
                        y + 8,
                        22,
                        6,
                        speaker.inputLevel()
                );
            } else {
                graphics.fill(x + panelWidth - 12, y + 8, x + panelWidth - 6, y + 14, speaker.color());
            }

            y += rowHeight + rowGap;
        }
    }

    private static void renderMicrophone(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            VoiceSessionManager manager
    ) {
        if (!manager.isPushToTalkDown()) {
            return;
        }

        int x = 10;
        int y = graphics.guiHeight() - 38;
        int width = 112;
        int height = 24;

        graphics.fill(x, y, x + width, y + height, PANEL_BACKGROUND);
        graphics.outline(x, y, width, height, PANEL_BORDER);
        graphics.fill(x, y, x + 3, y + height, METER_COLOR);
        graphics.text(client.font, "MIC", x + 9, y + 8, TEXT_COLOR, true);
        drawMiniMeter(graphics, x + 43, y + 9, 60, 7, manager.getMicrophoneLevel());
    }

    private static void drawMiniMeter(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            float rawLevel
    ) {
        float level = Math.max(0.0f, Math.min(1.0f, rawLevel));
        graphics.fill(x, y, x + width, y + height, METER_BACKGROUND);
        int filledWidth = Math.round(width * level);
        if (filledWidth > 0) {
            graphics.fill(x, y, x + filledWidth, y + height, METER_COLOR);
        }
    }
}
