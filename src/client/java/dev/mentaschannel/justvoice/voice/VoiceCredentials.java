package dev.mentaschannel.justvoice.voice;

import java.nio.file.Path;

/**
 * Temporary connection details assembled from local config and the private
 * world directive. The access token must never be persisted or logged.
 */
public record VoiceCredentials(
        String host,
        int port,
        Path certificateFile,
        String accessToken
) {
    public VoiceCredentials {
        if (host == null || host.isBlank() || host.contains("/") || host.contains("@")) {
            throw new IllegalArgumentException("Invalid voice server host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid voice server port");
        }
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Invalid voice access token");
        }
    }

    @Override
    public String toString() {
        return "VoiceCredentials[host=%s, port=%d, accessToken=<redacted>]"
                .formatted(host, port);
    }
}
