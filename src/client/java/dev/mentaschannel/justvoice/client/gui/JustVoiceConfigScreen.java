package dev.mentaschannel.justvoice.client.gui;

import dev.mentaschannel.justvoice.client.assets.MicIconManager;
import dev.mentaschannel.justvoice.JustVoiceChat;
import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSettings;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                                if ("custom".equals(id)) {
                                    if (!MicIconManager.hasCustomIcon()
                                            || "custom".equals(settings.microphoneIcon())) {
                                        chooseCustomIcon();
                                    } else {
                                        MicIconManager.select(id, settings);
                                    }
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
                        button -> Minecraft.getInstance().gui.setScreen(new JustVoicePlayersScreen(this, 0))
                )
                .bounds(left, y, buttonWidth, 22).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(center - 100, Math.min(height - 28, y), 200, 20).build());
    }

    private Component iconTooltip(String id) {
        if ("custom".equals(id)) {
            return Component.translatable(
                    !MicIconManager.hasCustomIcon()
                            ? "screen.justvoice.mic_icon.upload"
                            : "custom".equals(settings.microphoneIcon())
                                    ? "screen.justvoice.mic_icon.replace"
                                    : "screen.justvoice.mic_icon.custom"
            );
        }
        return Component.translatable("screen.justvoice.mic_icon." + id);
    }

    private void chooseCustomIcon() {
        Minecraft client = Minecraft.getInstance();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            chooseLinuxCustomIcon(client);
            return;
        }

        chooseSdlCustomIcon(client);
    }

    /**
     * Minecraft uses GLFW for its event loop, while SDL's Linux file dialogs
     * may depend on SDL_PumpEvents for the XDG portal response. Prefer the
     * desktop's native chooser process on Linux, and provide an in-game path
     * field when neither Zenity nor KDialog is installed.
     */
    private void chooseLinuxCustomIcon(Minecraft client) {
        Thread.ofVirtual().name("just-voice-icon-picker").start(() -> {
            try {
                Path selected = showLinuxFileDialog();
                if (selected != null) {
                    client.execute(() -> importCustomIcon(selected));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                        "Linux file picker unavailable: " + exception.getMessage()
                                + ". Falling back to manual PNG path entry.");
                client.execute(() -> client.gui.setScreen(
                        new JustVoiceIconPathScreen(this, this::importCustomIcon)
                ));
            }
        });
    }

    private static Path showLinuxFileDialog() throws IOException, InterruptedException {
        String zenity = findExecutableOnPath("zenity");
        if (zenity != null) {
            return runLinuxFileDialog(
                    List.of(
                            zenity,
                            "--file-selection",
                            "--title=Choose a custom microphone icon",
                            "--file-filter=PNG images | *.png",
                            "--filename=" + System.getProperty("user.home", ".") + File.separator
                    )
            );
        }

        String kdialog = findExecutableOnPath("kdialog");
        if (kdialog != null) {
            return runLinuxFileDialog(List.of(
                    kdialog,
                    "--getopenfilename",
                    System.getProperty("user.home", "."),
                    "PNG images (*.png)"
            ));
        }

        throw new IOException("Install zenity or kdialog, or enter the PNG path manually");
    }

    private static Path runLinuxFileDialog(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] response = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();

        String detail = new String(response, StandardCharsets.UTF_8).trim();
        if (exitCode == 1 && detail.isBlank()) {
            return null; // The user cancelled the chooser.
        }
        if (exitCode != 0) {
            throw new IOException(detail.isBlank()
                    ? "File chooser exited with code " + exitCode
                    : detail);
        }

        String selected = new String(response, StandardCharsets.UTF_8).trim();
        if (selected.isEmpty()) {
            throw new IOException("File chooser returned an empty path");
        }
        return Path.of(selected);
    }

    private static String findExecutableOnPath(String executable) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }

        for (String directory : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            try {
                Path candidate = Path.of(directory.isBlank() ? "." : directory)
                        .resolve(executable)
                        .toAbsolutePath()
                        .normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries and keep searching.
            }
        }
        return null;
    }

    private void chooseSdlCustomIcon(Minecraft client) {
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(1);
        var filterName = MemoryUtil.memUTF8("PNG images");
        var filterPattern = MemoryUtil.memUTF8("*.png");

        try {
            filters.get(0).set(filterName, filterPattern);
            SDLDialog.SDL_ShowOpenFileDialog(
                    (userdata, filelist, filter) -> {
                        try {
                            if (filelist == 0L) {
                                client.execute(() -> JustVoiceChat.error("screen.justvoice.icon_import_failed"));
                                return;
                            }

                            long selectedPathPointer = MemoryUtil.memGetAddress(filelist);
                            if (selectedPathPointer == 0L) {
                                return;
                            }

                            Path selectedPath = Path.of(MemoryUtil.memUTF8(selectedPathPointer));
                            client.execute(() -> importCustomIcon(selectedPath));
                        } catch (RuntimeException exception) {
                            client.execute(() -> JustVoiceChat.error("screen.justvoice.icon_import_failed"));
                            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                                    "Could not read selected microphone icon path: "
                                            + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                        } finally {
                            filters.free();
                            MemoryUtil.memFree(filterName);
                            MemoryUtil.memFree(filterPattern);
                        }
                    },
                    0L,
                    client.getWindow().handle(),
                    filters,
                    (CharSequence) System.getProperty("user.home"),
                    false
            );
        } catch (RuntimeException | LinkageError exception) {
            filters.free();
            MemoryUtil.memFree(filterName);
            MemoryUtil.memFree(filterPattern);
            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                    "Could not open the SDL microphone icon picker: "
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            JustVoiceChat.error("screen.justvoice.icon_import_failed");
        }
    }

    private void importCustomIcon(Path selectedPath) {
        try {
            MicIconManager.importCustomIcon(selectedPath, settings);
            JustVoiceChat.info("screen.justvoice.icon_imported");
        } catch (Exception exception) {
            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                    "Could not import microphone icon: " + exception.getMessage());
            JustVoiceChat.error("screen.justvoice.icon_import_failed");
        }
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
                boolean customIcon = "custom".equals(card.id());
                int sourceU = 0;
                int regionWidth = sourceWidth;
                int regionHeight = sourceHeight;
                float previewBox = customIcon ? 20.0f : 16.0f;
                float scale = Math.min(previewBox / regionWidth, previewBox / regionHeight);
                int previewWidth = Math.max(1, Math.round(regionWidth * scale));
                int previewHeight = Math.max(1, Math.round(regionHeight * scale));
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        texture,
                        card.x() + (ICON_CARD_SIZE - previewWidth) / 2,
                        card.y() + 5 + (20 - previewHeight) / 2,
                        (float) sourceU,
                        0.0f,
                        previewWidth,
                        previewHeight,
                        regionWidth,
                        regionHeight,
                        sourceWidth,
                        sourceHeight,
                        0xFFFFFFFF
                );
            }

            if (!"custom".equals(card.id())) {
                Component name = Component.literal(card.id().substring(1));
                graphics.centeredText(font, name,
                        card.x() + ICON_CARD_SIZE / 2, card.y() + 29, 0xFFDDDFE4);
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
