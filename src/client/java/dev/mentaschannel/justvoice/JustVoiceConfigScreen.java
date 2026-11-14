package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Main audio settings screen exposed through Mod Menu and /justvoice settings. */
public final class JustVoiceConfigScreen extends Screen {
    private final Screen parent;
    private final VoiceSettings settings;

    private List<String> inputDevices;
    private List<String> outputDevices;
    private int inputDeviceIndex;
    private int outputDeviceIndex;
    private Button microphoneIconButton;

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
        y += 26;

        microphoneIconButton = addRenderableWidget(Button.builder(microphoneIconLabel(), button -> {
                    MicIconManager.cycle(settings);
                    button.setMessage(microphoneIconLabel());
                })
                .bounds(left, y, buttonWidth, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.justvoice.import_mic_icon"),
                        button -> chooseCustomIcon()
                )
                .bounds(left, y, buttonWidth, 20).build());
        y += 32;

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
                        microphoneIconButton.setMessage(microphoneIconLabel());
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

    private Component microphoneIconLabel() {
        return Component.translatable(
                "screen.justvoice.microphone_icon",
                MicIconManager.displayName(settings)
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
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
