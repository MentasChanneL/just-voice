package dev.mentaschannel.justvoice;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Loads bundled or user-provided microphone textures for the HUD. */
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

    private MicIconManager() {
    }

    public static void reload(VoiceSettings settings) {
        String selection = settings.microphoneIcon();
        if ("custom".equals(selection)) {
            try {
                byte[] bytes = readCustomIcon(settings.customMicrophoneIconFile());
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
                currentTexture = CUSTOM_TEXTURE;
                currentWidth = width;
                currentHeight = height;
                return;
            } catch (IOException | RuntimeException exception) {
                System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                        "Could not load custom microphone icon: " + exception.getMessage());
                settings.setMicrophoneIcon("v2");
                selection = "v2";
            }
        }

        currentTexture = builtinTexture(selection);
        currentWidth = 16;
        currentHeight = 16;
    }

    public static void cycle(VoiceSettings settings) {
        List<String> choices = new ArrayList<>(List.of("v1", "v2", "v3", "v4"));
        if (Files.isRegularFile(settings.customMicrophoneIconFile())) {
            choices.add("custom");
        }

        String selected = settings.microphoneIcon();
        int index = choices.indexOf(selected);
        String next = choices.get((index + 1 + choices.size()) % choices.size());
        settings.setMicrophoneIcon(next);
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

    public static String displayName(VoiceSettings settings) {
        String selection = settings.microphoneIcon();
        return "custom".equals(selection) ? "Custom" : selection.toUpperCase(Locale.ROOT);
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
