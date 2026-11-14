package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main audio settings screen exposed through Mod Menu and /justvoice settings. */
public final class JustVoiceConfigScreen extends Screen {
    private static final int ICON_CARD_SIZE = 42;
    private static final int ICON_CARD_GAP = 7;
    private static final List<String> ICON_CHOICES = List.of("v1", "v2", "v3", "v4", "custom");

    private record IconCard(String id, int x, int y) {
    }

    private final Screen parent;
    private final VoiceSettings settings;
    private final List<IconCard> iconCards = new ArrayList<>();

    private List<String> inputDevices;
    private List<String> outputDevices;
    private int inputDeviceIndex;
    private int outputDeviceIndex;
    private Button importIconButton;

    public JustVoiceConfigScreen(Screen parent) {
        super(Component.translatable("screen.justvoice.title"));
        this.parent = parent;
        this.settings = JustVoiceClient.getVoiceSettings();
    }

    @Override
    protected void init() {
        inputDevices = VoiceSettings.availableInputDevices();
        outputDevices = VoiceSettings.availableOutputDevices();
        inputDeviceIndex = selectedIndex(inputDevices, settings.inputDevice());
        outputDeviceIndex = selectedIndex(outputDevices, settings.outputDevice());
        iconCards.clear();

        int center = width / 2;
        int buttonWidth = Math.min(360, width - 32);
        int left = center - buttonWidth / 2;
        int y = 54;

        addRenderableWidget(Button.builder(voiceEnabledLabel(), button -> {
                    var session = JustVoiceClient.getActiveSessionManager();
                    if (session != null) {
                        session.setEnabled(!settings.isEnabled());
                    }
                    button.setMessage(voiceEnabledLabel());
                })
                .bounds(left, y, buttonWidth, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(inputDeviceLabel(), button -> {
                    cycleInputDevice();
                    button.setMessage(inputDeviceLabel());
                })
                .bounds(left, y, buttonWidth, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(outputDeviceLabel(), button -> {
                    cycleOutputDevice();
                    button.setMessage(outputDeviceLabel());
                })
                .bounds(left, y, buttonWidth, 20).build());
        y += 27;

        int iconRowWidth = ICON_CHOICES.size() * ICON_CARD_SIZE
                + (ICON_CHOICES.size() - 1) * ICON_CARD_GAP;
        int iconLeft = center - iconRowWidth / 2;
        int iconY = y + 13;
        for (int i = 0; i < ICON_CHOICES.size(); i++) {
            String id = ICON_CHOICES.get(i);
            int x = iconLeft + i * (ICON_CARD_SIZE + ICON_CARD_GAP);
            Button button = Button.builder(
                            Component.empty(),
                            pressed -> {
                                if ("custom".equals(id) && !MicIconManager.hasCustomIcon()) {
                                    chooseCustomIcon();
                                } else {
                                    MicIconManager.select(id, settings);
                                }
                            }
                    )
                    .bounds(x, iconY, ICON_CARD_SIZE, ICON_CARD_SIZE)
                    .tooltip(Tooltip.create(iconTooltip(id)))
                    .build();
            addRenderableWidget(button);
            iconCards.add(new IconCard(id, x, iconY));
        }
        y = iconY + ICON_CARD_SIZE + 8;

        importIconButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.justvoice.import_mic_icon"),
                        button -> chooseCustomIcon()
                )
                .bounds(center - 100, y, 200, 20).build());
        y += 28;

        addRenderableWidget(new AbstractSliderButton(
                left, y, buttonWidth, 20,
                Component.empty(),
                normalizedInputGain()
        ) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                float gain = 0.5f + (float) value * 2.5f;
                setMessage(Component.translatable(
                        "screen.justvoice.input_gain",
                        Math.round(gain * 100)
                ));
            }

            @Override
            protected void applyValue() {
                settings.setInputGain(0.5f + (float) value * 2.5f);
                updateMessage();
            }
        });
        y += 28;

        addRenderableWidget(new AbstractSliderButton(
                left, y, buttonWidth, 20,
                Component.empty(),
                settings.masterVolume() / 2.0
        ) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable(
                        "screen.justvoice.master_volume",
                        Math.round((float) value * 200)
                ));
            }

            @Override
            protected void applyValue() {
                settings.setMasterVolume((float) value * 2.0f);
                updateMessage();
            }
        });
        y += 30;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.justvoice.manage_players"),
                        button -> Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(this))
                )
                .bounds(left, y, buttonWidth, 22).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(center - 100, Math.min(height - 28, y), 200, 20).build());
    }

    private Component iconTooltip(String id) {
        if ("custom".equals(id)) {
            return Component.translatable(
                    MicIconManager.hasCustomIcon()
                            ? "screen.justvoice.mic_icon.custom"
                            : "screen.justvoice.mic_icon.upload"
            );
        }
        return Component.translatable("screen.justvoice.mic_icon." + id);
    }

    private void chooseCustomIcon() {
        Minecraft client = Minecraft.getInstance();
        EventQueue.invokeLater(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, "Select a PNG microphone icon", FileDialog.LOAD);
                dialog.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
                dialog.setVisible(true);
                String selectedFile = dialog.getFile();
                if (selectedFile == null || dialog.getDirectory() == null) {
                    return;
                }

                Path selectedPath = Path.of(dialog.getDirectory(), selectedFile);
                client.execute(() -> {
                    try {
                        MicIconManager.importCustomIcon(selectedPath, settings);
                        importIconButton.setMessage(Component.translatable("screen.justvoice.replace_mic_icon"));
                        JustVoiceChat.info("screen.justvoice.icon_imported");
                    } catch (Exception exception) {
                        System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                                "Could not import microphone icon: " + exception.getMessage());
                        JustVoiceChat.error("screen.justvoice.icon_import_failed");
                    }
                });
            } catch (RuntimeException exception) {
                client.execute(() -> JustVoiceChat.error("screen.justvoice.icon_import_failed"));
                System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                        "Could not open the microphone icon file picker: " + exception.getMessage());
            }
        });
    }

    private static int selectedIndex(List<String> devices, String current) {
        int index = devices.indexOf(current == null ? "" : current);
        return index < 0 ? 0 : index;
    }

    private Component voiceEnabledLabel() {
        return Component.translatable(
                settings.isEnabled()
                        ? "screen.justvoice.voice_enabled"
                        : "screen.justvoice.voice_disabled"
        );
    }

    private double normalizedInputGain() {
        return Math.max(0.0, Math.min(1.0, (settings.inputGain() - 0.5f) / 2.5f));
    }

    private void cycleInputDevice() {
        inputDeviceIndex = (inputDeviceIndex + 1) % inputDevices.size();
        settings.setInputDevice(inputDevices.get(inputDeviceIndex));
        var session = JustVoiceClient.getActiveSessionManager();
        if (session != null) {
            session.restartMicrophone();
        }
    }

    private void cycleOutputDevice() {
        outputDeviceIndex = (outputDeviceIndex + 1) % outputDevices.size();
        settings.setOutputDevice(outputDevices.get(outputDeviceIndex));
        var session = JustVoiceClient.getActiveSessionManager();
        if (session != null) {
            session.restartPlayback();
        }
    }

    private Component inputDeviceLabel() {
        return Component.translatable(
                "screen.justvoice.input_device",
                VoiceSettings.displayDevice(settings.inputDevice())
        );
    }

    private Component outputDeviceLabel() {
        return Component.translatable(
                "screen.justvoice.output_device",
                VoiceSettings.displayDevice(settings.outputDevice())
        );
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(
                font,
                Component.translatable("screen.justvoice.description"),
                width / 2,
                42,
                0xFFAAAAAA
        );
        graphics.centeredText(
                font,
                Component.translatable("screen.justvoice.microphone_icon"),
                width / 2,
                iconCards.isEmpty() ? 0 : iconCards.getFirst().y() - 13,
                0xFFE3E6EB
        );

        for (IconCard card : iconCards) {
            boolean selected = settings.microphoneIcon().equals(card.id());
            boolean hovered = mouseX >= card.x() && mouseX < card.x() + ICON_CARD_SIZE
                    && mouseY >= card.y() && mouseY < card.y() + ICON_CARD_SIZE;
            int background = selected ? 0xFF293F37 : hovered ? 0xFF343B46 : 0xFF252A32;
            int border = selected ? 0xFF65D6A3 : hovered ? 0xFF9BA7B5 : 0xFF59616D;

            graphics.fill(card.x(), card.y(), card.x() + ICON_CARD_SIZE,
                    card.y() + ICON_CARD_SIZE, background);
            graphics.outline(card.x(), card.y(), ICON_CARD_SIZE, ICON_CARD_SIZE, border);

            if ("custom".equals(card.id()) && !MicIconManager.hasCustomIcon()) {
                graphics.centeredText(font, Component.literal("+"),
                        card.x() + ICON_CARD_SIZE / 2, card.y() + 7, 0xFFFFFFFF);
            } else {
                Identifier texture = MicIconManager.previewTexture(card.id());
                int sourceWidth = MicIconManager.previewWidth(card.id());
                int sourceHeight = MicIconManager.previewHeight(card.id());
                float scale = Math.min(22.0f / sourceWidth, 22.0f / sourceHeight);
                int previewWidth = Math.max(1, Math.round(sourceWidth * scale));
                int previewHeight = Math.max(1, Math.round(sourceHeight * scale));
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        texture,
                        card.x() + (ICON_CARD_SIZE - previewWidth) / 2,
                        card.y() + 4 + (22 - previewHeight) / 2,
                        0.0f,
                        0.0f,
                        previewWidth,
                        previewHeight,
                        sourceWidth,
                        sourceHeight,
                        0xFFFFFFFF
                );
            }

            Component name = Component.literal("custom".equals(card.id())
                    ? "★" : card.id().substring(1));
            graphics.centeredText(font, name,
                    card.x() + ICON_CARD_SIZE / 2, card.y() + 29, 0xFFDDDFE4);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
