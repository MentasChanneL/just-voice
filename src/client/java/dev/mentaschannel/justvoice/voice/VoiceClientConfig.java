package dev.mentaschannel.justvoice.voice;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Public voice-server endpoint configuration. No world API key is ever
 * stored in the player client.
 */
public record VoiceClientConfig(String voiceHost, int voicePort, Path certificateFile) {
    private static final Logger LOGGER = Logger.getLogger("justvoice");
    private static final String HOST_KEY = "voice-host";
    private static final String PORT_KEY = "voice-port";
    private static final String CERT_KEY = "ca-cert-file";
    private static final String HOST_PLACEHOLDER = "voice.example.invalid";
    private static final String FILE_NAME = "just-voice.properties";

    public static VoiceClientConfig load(Path configDirectory) {
        Path configFile = migrateLegacyFile(configDirectory);
        Properties properties = new Properties();

        if (Files.notExists(configFile)) {
            properties.setProperty(HOST_KEY, HOST_PLACEHOLDER);
            properties.setProperty(PORT_KEY, "24454");
            properties.setProperty(CERT_KEY, "");
            try {
                Files.createDirectories(configDirectory);
                try (Writer writer = Files.newBufferedWriter(configFile)) {
                    properties.store(writer, "Just Voice QUIC server configuration.");
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

        String host = properties.getProperty(HOST_KEY, "").trim();
        int port = parsePort(properties.getProperty(PORT_KEY, "24454"));
        Path certificate = parseCertificate(properties.getProperty(CERT_KEY, ""));

        if (host.equalsIgnoreCase(HOST_PLACEHOLDER)
                || host.contains("/")
                || host.contains("@")
                || host.chars().anyMatch(Character::isWhitespace)) {
            host = "";
        }

        return new VoiceClientConfig(host, port, certificate);
    }

    private static Path migrateLegacyFile(Path configDirectory) {
        Path target = configDirectory.resolve(FILE_NAME);
        if (Files.exists(target)) {
            return target;
        }

        Path parent = configDirectory.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return target;
        }

        Path legacy = parent.resolve(FILE_NAME);
        if (!Files.isRegularFile(legacy)) {
            return target;
        }

        try {
            Files.createDirectories(configDirectory);
            try {
                Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveException) {
                Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warning("Could not migrate old Just Voice configuration: " + exception.getMessage());
            return legacy;
        }
        return target;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static Path parseCertificate(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                LOGGER.warning("Configured CA certificate file is not readable.");
                return null;
            }
            return path;
        } catch (RuntimeException exception) {
            LOGGER.warning("Configured CA certificate path is invalid.");
            return null;
        }
    }

    public boolean isConfigured() {
        return voiceHost != null
                && !voiceHost.isBlank()
                && voicePort >= 1
                && voicePort <= 65535;
    }
}
