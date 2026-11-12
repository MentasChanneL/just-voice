package dev.mentaschannel.justvoice.voice;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Public Mumble endpoint configuration. No backend API or Mumble service secret
 * is stored in the player client.
 */
public record VoiceClientConfig(String mumbleHost, int mumblePort) {
    private static final Logger LOGGER = Logger.getLogger("justvoice");
    private static final String MUMBLE_HOST_KEY = "mumble-host";
    private static final String MUMBLE_PORT_KEY = "mumble-port";
    private static final String HOST_PLACEHOLDER = "voice.example.invalid";

    public static VoiceClientConfig load(Path configDirectory) {
        Path configFile = configDirectory.resolve("just-voice.properties");
        Properties properties = new Properties();

        if (Files.notExists(configFile)) {
            properties.setProperty(MUMBLE_HOST_KEY, HOST_PLACEHOLDER);
            properties.setProperty(MUMBLE_PORT_KEY, "64738");
            try {
                Files.createDirectories(configDirectory);
                try (Writer writer = Files.newBufferedWriter(configFile)) {
                    properties.store(
                            writer,
                            "Just Voice public Mumble server configuration."
                    );
                }
            } catch (IOException exception) {
                LOGGER.warning("Could not create Just Voice configuration: " + exception.getMessage());
            }
        } else {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                properties.load(reader);
            } catch (IOException exception) {
                LOGGER.warning("Could not read Just Voice configuration: " + exception.getMessage());
            }
        }

        String host = properties.getProperty(MUMBLE_HOST_KEY, "").trim();
        int port = parsePort(properties.getProperty(MUMBLE_PORT_KEY, "64738"));

        if (host.equalsIgnoreCase(HOST_PLACEHOLDER)
                || host.contains("/")
                || host.contains("@")
                || host.chars().anyMatch(Character::isWhitespace)) {
            host = "";
        }

        VoiceClientConfig config = new VoiceClientConfig(host, port);
        if (!config.isConfigured()) {
            LOGGER.warning("Set mumble-host and mumble-port in config/just-voice.properties.");
        }
        return config;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    public boolean isConfigured() {
        return mumbleHost != null
                && !mumbleHost.isBlank()
                && mumblePort >= 1
                && mumblePort <= 65535;
    }
}
