package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Main audio settings screen exposed through Mod Menu and /justvoice settings. */
public final class JustVoiceConfigScreen extends Screen {
    private final Screen parent;
    private final VoiceSettings settings;

    private List<String> inputDevices;
    private List<String> outputDevices;
    private int inputDeviceIndex;
    private int outputDeviceIndex;

    public JustVoiceConfigScreen(Screen parent) {
        super(Component.translatable("screen.justvoice.title"));
        this.parent = parent;
        this.settings = JustVoiceClient.getVoiceSettings();
    }

    @Override
    protected void init() {
        inputDevices = VoiceSettings.availableDevices();
        outputDevices = VoiceSettings.availableDevices();
        inputDeviceIndex = selectedIndex(inputDevices, settings.inputDevice());
        outputDeviceIndex = selectedIndex(outputDevices, settings.outputDevice());

        int center = width / 2;
        int buttonWidth = Math.min(360, width - 32);
        int left = center - buttonWidth / 2;
        int y = 64;

        addRenderableWidget(Button.builder(inputDeviceLabel(), button -> cycleInputDevice())
                .bounds(left, y, buttonWidth, 20).build());
        y += 28;

        addRenderableWidget(Button.builder(outputDeviceLabel(), button -> cycleOutputDevice())
                .bounds(left, y, buttonWidth, 20).build());
        y += 38;

        addRenderableWidget(new AbstractSliderButton(
                left, y, buttonWidth, 20,
                Component.empty(),
                normalizedInputGain()
        ) {
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
        y += 29;

        addRenderableWidget(new AbstractSliderButton(
                left, y, buttonWidth, 20,
                Component.empty(),
                settings.masterVolume() / 2.0
        ) {
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
        y += 36;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.justvoice.manage_players"),
                        button -> Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(this))
                )
                .bounds(left, y, buttonWidth, 22).build());
        y += 34;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(center - 100, Math.min(height - 28, y), 200, 20).build());
    }

    private static int selectedIndex(List<String> devices, String current) {
        int index = devices.indexOf(current == null ? "" : current);
        return index < 0 ? 0 : index;
    }

    private double normalizedInputGain() {
        return Math.max(0.0, Math.min(1.0, (settings.inputGain() - 0.5f) / 2.5f));
    }

    private void cycleInputDevice() {
        inputDeviceIndex = (inputDeviceIndex + 1) % inputDevices.size();
        settings.setInputDevice(inputDevices.get(inputDeviceIndex));
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
