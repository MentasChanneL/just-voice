package dev.mentaschannel.justvoice.voice;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** Persisted client-side audio and voice enablement settings. */
public final class VoiceSettings {
    private static final String FILE_NAME = "just-voice-audio.properties";
    private static final float MIN_GAIN = 0.5f;
    private static final float MAX_GAIN = 3.0f;

    private final Path file;
    private final Properties properties = new Properties();

    private boolean enabled = true;
    private String inputDevice = "";
    private String outputDevice = "";
    private float inputGain = 1.0f;
    private float masterVolume = 1.0f;

    private VoiceSettings(Path file) {
        this.file = file;
    }

    public static VoiceSettings load(Path configDirectory) {
        VoiceSettings settings = new VoiceSettings(configDirectory.resolve(FILE_NAME));
        try {
            Files.createDirectories(configDirectory);
            if (Files.isRegularFile(settings.file)) {
                try (Reader reader = Files.newBufferedReader(settings.file)) {
                    settings.properties.load(reader);
                }
            }
        } catch (IOException exception) {
            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                    "Could not read Just Voice audio settings: " + exception.getMessage());
        }

        settings.enabled = Boolean.parseBoolean(settings.properties.getProperty("enabled", "true"));
        settings.inputDevice = settings.properties.getProperty("input-device", "");
        settings.outputDevice = settings.properties.getProperty("output-device", "");
        settings.inputGain = parseFloat(settings.properties.getProperty("input-gain"), 1.0f, MIN_GAIN, MAX_GAIN);
        settings.masterVolume = parseFloat(settings.properties.getProperty("master-volume"), 1.0f, 0.0f, 2.0f);
        return settings;
    }

    private static float parseFloat(String raw, float fallback, float minimum, float maximum) {
        try {
            float value = Float.parseFloat(raw);
            return Float.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public synchronized String inputDevice() {
        return inputDevice;
    }

    public synchronized void setInputDevice(String inputDevice) {
        this.inputDevice = inputDevice == null ? "" : inputDevice;
        save();
    }

    public synchronized String outputDevice() {
        return outputDevice;
    }

    public synchronized void setOutputDevice(String outputDevice) {
        this.outputDevice = outputDevice == null ? "" : outputDevice;
        save();
    }

    public synchronized float inputGain() {
        return inputGain;
    }

    public synchronized void setInputGain(float inputGain) {
        this.inputGain = clamp(inputGain, MIN_GAIN, MAX_GAIN);
        save();
    }

    public synchronized float masterVolume() {
        return masterVolume;
    }

    public synchronized void setMasterVolume(float masterVolume) {
        this.masterVolume = clamp(masterVolume, 0.0f, 2.0f);
        save();
    }

    public synchronized float playerVolume(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return 1.0f;
        }
        String key = playerVolumeKey(playerName);
        return parseFloat(properties.getProperty(key), 1.0f, 0.0f, 2.0f);
    }

    public synchronized void setPlayerVolume(String playerName, float volume) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        properties.setProperty(playerVolumeKey(playerName), Float.toString(clamp(volume, 0.0f, 2.0f)));
        save();
    }

    private static String playerVolumeKey(String playerName) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(playerName.getBytes(StandardCharsets.UTF_8));
        return "player-volume." + encoded;
    }

    public static List<String> availableDevices() {
        List<String> result = new ArrayList<>();
        result.add("");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            result.add(info.getName());
        }
        return List.copyOf(result);
    }

    public static String displayDevice(String deviceName) {
        return deviceName == null || deviceName.isBlank() ? "System default" : deviceName;
    }

    private void save() {
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("input-device", inputDevice);
        properties.setProperty("output-device", outputDevice);
        properties.setProperty("input-gain", Float.toString(inputGain));
        properties.setProperty("master-volume", Float.toString(masterVolume));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "Just Voice client audio settings. Do not share this file.");
            }
        } catch (IOException exception) {
            System.getLogger("justvoice").log(System.Logger.Level.WARNING,
                    "Could not save Just Voice audio settings: " + exception.getMessage());
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (!Float.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
