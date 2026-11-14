package dev.mentaschannel.justvoice.client.assets;

import com.mojang.blaze3d.platform.NativeImage;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Loads bundled or user-provided microphone textures for the HUD and settings preview. */
public final class MicIconManager {
    private static final Identifier CUSTOM_TEXTURE = Identifier.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "dynamic/microphone_custom"
    );
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 512;

    private static volatile Identifier currentTexture = builtinTexture("v2");
    private static volatile int currentWidth = 16;
    private static volatile int currentHeight = 16;
    private static volatile boolean customAvailable;
    private static volatile int customWidth = 16;
    private static volatile int customHeight = 16;

    private MicIconManager() {
    }

    public static void reload(VoiceSettings settings) {
        loadCustomTexture(settings);

        String selection = settings.microphoneIcon();
        if ("custom".equals(selection) && !customAvailable) {
            settings.setMicrophoneIcon("v2");
            selection = "v2";
        }

        currentTexture = previewTexture(selection);
        currentWidth = previewWidth(selection);
        currentHeight = previewHeight(selection);
    }

    private static void loadCustomTexture(VoiceSettings settings) {
        Path file = settings.customMicrophoneIconFile();
        if (!Files.isRegularFile(file)) {
            customAvailable = false;
            return;
        }

        try {
            byte[] bytes = readCustomIcon(file);
            NativeImage image = NativeImage.read(bytes);
            int width = image.getWidth();
            int height = image.getHeight();
            if (!validDimensions(width, height)) {
                image.close();
                throw new IOException("Custom microphone icon dimensions must be between 1 and 512 pixels.");
            }

            Minecraft.getInstance().getTextureManager().register(
                    CUSTOM_TEXTURE,
                    new DynamicTexture(() -> "Just Voice custom microphone icon", image)
            );
            customWidth = width;
            customHeight = height;
            customAvailable = true;
        } catch (IOException | RuntimeException exception) {
            customAvailable = false;
            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                    "Could not load custom microphone icon: " + exception.getMessage());
        }
    }

    public static void select(String selection, VoiceSettings settings) {
        if ("custom".equals(selection) && !customAvailable) {
            return;
        }
        settings.setMicrophoneIcon(selection);
        reload(settings);
    }

    public static void importCustomIcon(Path source, VoiceSettings settings) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No PNG file was selected.");
        }
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) {
            throw new IOException("Only PNG microphone icons are supported.");
        }

        long size = Files.size(source);
        if (size <= 0 || size > MAX_FILE_BYTES) {
            throw new IOException("Microphone icon must not exceed 2 MB.");
        }

        byte[] bytes = Files.readAllBytes(source);
        try (NativeImage image = NativeImage.read(bytes)) {
            if (!validDimensions(image.getWidth(), image.getHeight())) {
                throw new IOException("Microphone icon dimensions must be between 1 and 512 pixels.");
            }
        }

        Path target = settings.customMicrophoneIconFile();
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        settings.setMicrophoneIcon("custom");
        reload(settings);
    }

    public static Identifier texture() {
        return currentTexture;
    }

    public static int textureWidth() {
        return currentWidth;
    }

    public static int textureHeight() {
        return currentHeight;
    }

    public static boolean hasCustomIcon() {
        return customAvailable;
    }

    public static Identifier previewTexture(String selection) {
        return "custom".equals(selection) ? CUSTOM_TEXTURE : builtinTexture(selection);
    }

    public static int previewWidth(String selection) {
        return "custom".equals(selection) ? customWidth : 16;
    }

    public static int previewHeight(String selection) {
        return "custom".equals(selection) ? customHeight : 16;
    }

    private static byte[] readCustomIcon(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Custom microphone icon file does not exist.");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_FILE_BYTES) {
            throw new IOException("Custom microphone icon must not exceed 2 MB.");
        }
        return Files.readAllBytes(path);
    }

    private static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION;
    }

    private static Identifier builtinTexture(String name) {
        return Identifier.fromNamespaceAndPath(
                JustVoiceClient.MOD_ID,
                "textures/gui/sprites/mics/" + name + ".png"
        );
    }
}
