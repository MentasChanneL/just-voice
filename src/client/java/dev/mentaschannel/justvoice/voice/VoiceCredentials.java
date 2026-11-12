package dev.mentaschannel.justvoice.voice;

/**
 * Short-lived in-memory voice credentials returned by the backend.
 * Never persist or log this record: the access token is a live Mumble ACL token.
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
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Missing Mumble access token");
        }
    }

    @Override
    public String toString() {
        return "VoiceCredentials[host=%s, port=%d, username=%s, accessToken=<redacted>]"
                .formatted(host, port, username);
    }
}
