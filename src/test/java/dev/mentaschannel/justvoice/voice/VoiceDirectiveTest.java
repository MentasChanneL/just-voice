package dev.mentaschannel.justvoice.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VoiceDirectiveTest {
    private static final String VALID_TOKEN = "abcdefghijklmnopqrstuvwxYZ0123456789_-ABC";

    @Test
    void parsesInviteWithOpaqueToken() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE INVITE " + VALID_TOKEN);

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.INVITE, directive.type());
        assertEquals(VALID_TOKEN, directive.token());
    }

    @Test
    void parsesCloseWithoutPayload() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE CLOSE");

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.CLOSE, directive.type());
        assertNull(directive.token());
    }

    @Test
    void directiveKeywordsAreCaseInsensitiveAndWhitespaceTolerant() {
        VoiceDirective directive = VoiceDirective.parse("  @voice   invite   " + VALID_TOKEN + "  ");

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.INVITE, directive.type());
    }

    @Test
    void rejectsInviteWithoutToken() {
        assertNull(VoiceDirective.parse("@VOICE INVITE"));
    }

    @Test
    void rejectsShortOrMalformedToken() {
        assertNull(VoiceDirective.parse("@VOICE INVITE too-short"));
        assertNull(VoiceDirective.parse("@VOICE INVITE token.with.dots"));
    }

    @Test
    void rejectsExtraArguments() {
        assertNull(VoiceDirective.parse("@VOICE CLOSE extra"));
        assertNull(VoiceDirective.parse("@VOICE INVITE " + VALID_TOKEN + " extra"));
    }

    @Test
    void onlyRecognizesStandaloneVoiceMarker() {
        assertTrue(VoiceDirective.hasMarker("@VOICE INVITE " + VALID_TOKEN));
        assertTrue(VoiceDirective.hasMarker("  @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker("Player: @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker("@VOICEMAIL something"));
        assertFalse(VoiceDirective.hasMarker("hello @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker(null));
    }

    @Test
    void doesNotIncludeTokenInToString() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE INVITE " + VALID_TOKEN);

        assertNotNull(directive);
        assertFalse(directive.toString().contains(VALID_TOKEN));
        assertTrue(directive.toString().contains("<redacted>"));
    }
}
