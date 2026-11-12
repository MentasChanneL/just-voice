package dev.mentaschannel.justvoice.voice;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Local client configuration. It contains only the public HTTPS backend URL;
 * no backend API key or Mumble administration secret is stored here.
 */
public record VoiceClientConfig(URI backendUri) {
    private static final Logger LOGGER = Logger.getLogger("justvoice");
    private static final String BACKEND_URL_KEY = "backend-url";

    public static VoiceClientConfig load(Path configDirectory) {
        Path configFile = configDirectory.resolve("just-voice.properties");
        Properties properties = new Properties();

        if (Files.notExists(configFile)) {
            properties.setProperty(
                    BACKEND_URL_KEY,
                    "https://replace-this-with-your-voice-backend.example"
            );
            try {
                Files.createDirectories(configDirectory);
                try (Writer writer = Files.newBufferedWriter(configFile)) {
                    properties.store(
                            writer,
                            "Just Voice client configuration. Use the public HTTPS backend URL."
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

        String configuredUrl = properties.getProperty(BACKEND_URL_KEY, "").trim();
        URI backendUri = validateBackendUri(configuredUrl);
        if (backendUri == null) {
            LOGGER.warning(
                    "Just Voice backend URL is not configured. Edit config/just-voice.properties."
            );
        }

        return new VoiceClientConfig(backendUri);
    }

    private static URI validateBackendUri(String configuredUrl) {
        if (configuredUrl.isBlank() || configuredUrl.contains("replace-this-with-your-")) {
            return null;
        }

        try {
            URI uri = URI.create(configuredUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (!uri.isAbsolute()
                    || host == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }

            boolean secure = "https".equalsIgnoreCase(scheme);
            boolean localDevelopment = "http".equalsIgnoreCase(scheme)
                    && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1"));

            if (!secure && !localDevelopment) {
                LOGGER.warning("Just Voice backend URL must use HTTPS (HTTP is allowed only on loopback).");
                return null;
            }

            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                LOGGER.warning("Just Voice backend URL must be an origin without a path.");
                return null;
            }

            return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException exception) {
            LOGGER.warning("Invalid Just Voice backend URL.");
            return null;
        }
    }
}
