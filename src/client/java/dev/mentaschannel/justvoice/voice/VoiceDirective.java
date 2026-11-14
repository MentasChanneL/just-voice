package dev.mentaschannel.justvoice.voice;

import java.util.regex.Pattern;

/**
 * Parses directives from server action-bar messages. Leading padding is ignored.
 *
 * INVITE carries the opaque Just Voice session token:
 *     [arbitrary leading whitespace]@VOICE INVITE <token>
 *
 * CLOSE clears the local voice session:
 *     [arbitrary leading whitespace]@VOICE CLOSE
 */
public record VoiceDirective(Type type, String token) {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    public enum Type {
        INVITE,
        CLOSE
    }

    public static boolean hasMarker(String message) {
        if (message == null) {
            return false;
        }

        String normalized = message.stripLeading();
        if (normalized.length() < 6 || !normalized.regionMatches(true, 0, "@VOICE", 0, 6)) {
            return false;
        }

        return normalized.length() == 6 || Character.isWhitespace(normalized.charAt(6));
    }

    public static VoiceDirective parse(String message) {
        if (!hasMarker(message)) {
            return null;
        }

        String[] parts = message.strip().split("\\s+");
        if (parts.length == 3
                && parts[0].equalsIgnoreCase("@VOICE")
                && parts[1].equalsIgnoreCase("INVITE")
                && TOKEN_PATTERN.matcher(parts[2]).matches()) {
            return new VoiceDirective(Type.INVITE, parts[2]);
        }

        if (parts.length == 2
                && parts[0].equalsIgnoreCase("@VOICE")
                && parts[1].equalsIgnoreCase("CLOSE")) {
            return new VoiceDirective(Type.CLOSE, null);
        }

        return null;
    }

    @Override
    public String toString() {
        return type == Type.INVITE
                ? "VoiceDirective[INVITE, token=<redacted>]"
                : "VoiceDirective[CLOSE]";
    }
}
