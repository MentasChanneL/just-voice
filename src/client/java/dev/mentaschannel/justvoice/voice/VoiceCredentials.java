package dev.mentaschannel.justvoice.voice;

/**
 * In-memory connection details assembled locally from config and the world directive.
 * Never persist or log the access token: it is a live Mumble ACL credential.
 */
public record VoiceCredentials(
        String host,
        int port,
        String username,
        String accessToken
) {
    public VoiceCredentials {
        if (host == null || host.isBlank() || host.contains("/") || host.contains("@")) {
            throw new IllegalArgumentException("Invalid Mumble host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Mumble port");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Invalid Mumble username");
        }
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Invalid Mumble access token");
        }
    }

    @Override
    public String toString() {
        return "VoiceCredentials[host=%s, port=%d, username=%s, accessToken=<redacted>]"
                .formatted(host, port, username);
    }
}
