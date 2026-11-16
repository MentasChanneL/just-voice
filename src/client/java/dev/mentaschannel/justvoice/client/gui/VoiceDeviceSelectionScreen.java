package dev.mentaschannel.justvoice.client.gui;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** A paged, explicit device list for selecting microphone or voice output. */
public final class VoiceDeviceSelectionScreen extends Screen {
    public enum DeviceType {
        INPUT,
        OUTPUT
    }

    private static final int ROWS_PER_PAGE = 8;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final VoiceSettings settings;
    private final DeviceType type;
    private final int requestedPage;
    private List<String> devices = List.of();
    private int pageCount = 1;

    public VoiceDeviceSelectionScreen(Screen parent, DeviceType type) {
        this(parent, type, 0);
    }

    private VoiceDeviceSelectionScreen(Screen parent, DeviceType type, int page) {
        super(Component.translatable(
                type == DeviceType.INPUT
                        ? "screen.justvoice.input_devices_title"
                        : "screen.justvoice.output_devices_title"
        ));
        this.parent = parent;
        this.settings = JustVoiceClient.getVoiceSettings();
        this.type = type;
        this.requestedPage = Math.max(0, page);
    }

    @Override
    protected void init() {
        List<String> discovered = type == DeviceType.INPUT
                ? VoiceSettings.availableInputDevices()
                : VoiceSettings.availableOutputDevices();
        devices = new ArrayList<>(discovered);

        String selectedDevice = selectedDevice();
        if (!devices.contains(selectedDevice)) {
            devices.add(selectedDevice);
        }

        pageCount = Math.max(1, (devices.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int page = Math.min(requestedPage, pageCount - 1);
        int first = page * ROWS_PER_PAGE;
        int last = Math.min(devices.size(), first + ROWS_PER_PAGE);

        int buttonWidth = Math.min(420, width - 32);
        int left = (width - buttonWidth) / 2;
        int top = 66;

        for (int index = first; index < last; index++) {
            String device = devices.get(index);
            boolean selected = device.equals(selectedDevice);
            Component label = Component.literal((selected ? "✔ " : "") + VoiceSettings.displayDevice(device));
            addRenderableWidget(Button.builder(label, button -> chooseDevice(device))
                    .bounds(left, top + (index - first) * ROW_HEIGHT, buttonWidth, 20)
                    .build());
        }

        int controlsY = Math.min(height - 54, top + ROWS_PER_PAGE * ROW_HEIGHT + 8);
        if (page > 0) {
            addRenderableWidget(Button.builder(Component.translatable("screen.justvoice.previous"), button ->
                            Minecraft.getInstance().setScreen(
                                    new VoiceDeviceSelectionScreen(parent, type, page - 1)
                            ))
                    .bounds(left, controlsY, 90, 20)
                    .build());
        }
        if (page + 1 < pageCount) {
            addRenderableWidget(Button.builder(Component.translatable("screen.justvoice.next"), button ->
                            Minecraft.getInstance().setScreen(
                                    new VoiceDeviceSelectionScreen(parent, type, page + 1)
                            ))
                    .bounds(left + buttonWidth - 90, controlsY, 90, 20)
                    .build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
    }

    private String selectedDevice() {
        return type == DeviceType.INPUT ? settings.inputDevice() : settings.outputDevice();
    }

    private void chooseDevice(String device) {
        if (type == DeviceType.INPUT) {
            settings.setInputDevice(device);
            var session = JustVoiceClient.getActiveSessionManager();
            if (session != null) {
                session.restartMicrophone();
            }
        } else {
            settings.setOutputDevice(device);
            var session = JustVoiceClient.getActiveSessionManager();
            if (session != null) {
                session.restartPlayback();
            }
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(
            net.minecraft.client.gui.GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 22, 0xFFFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.translatable("screen.justvoice.device_list_help"),
                width / 2,
                42,
                0xFFAAAAAA
        );

        if (devices.isEmpty()) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.justvoice.no_audio_devices"),
                    width / 2,
                    86,
                    0xFFFFAA88
            );
        }

        int page = Math.min(requestedPage, pageCount - 1);
        graphics.drawCenteredString(
                font,
                Component.translatable("screen.justvoice.page", page + 1, pageCount),
                width / 2,
                height - 45,
                0xFFAAAAAA
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
