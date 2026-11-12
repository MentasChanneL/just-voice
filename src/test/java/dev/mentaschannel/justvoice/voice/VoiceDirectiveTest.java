package dev.mentaschannel.justvoice.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VoiceDirectiveTest {
    private static final String VALID_TOKEN = "abcdefghijklmnopqrstuvwxYZ0123456789_-ABC";

    @Test
    void parsesInviteWithOpaqueTicket() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE INVITE " + VALID_TICKET);

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.INVITE, directive.type());
        assertEquals(VALID_TICKET, directive.token());
    }

    @Test
    void parsesCloseWithoutPayload() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE CLOSE");

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.CLOSE, directive.type());
        assertNull(directive.ticket());
    }

    @Test
    void directiveKeywordsAreCaseInsensitiveAndWhitespaceTolerant() {
        VoiceDirective directive = VoiceDirective.parse("  @voice   invite   " + VALID_TICKET + "  ");

        assertNotNull(directive);
        assertEquals(VoiceDirective.Type.INVITE, directive.type());
    }

    @Test
    void rejectsInviteWithoutTicket() {
        assertNull(VoiceDirective.parse("@VOICE INVITE"));
    }

    @Test
    void rejectsShortOrMalformedTicket() {
        assertNull(VoiceDirective.parse("@VOICE INVITE too-short"));
        assertNull(VoiceDirective.parse("@VOICE INVITE ticket.with.dots"));
    }

    @Test
    void rejectsExtraArguments() {
        assertNull(VoiceDirective.parse("@VOICE CLOSE extra"));
        assertNull(VoiceDirective.parse("@VOICE INVITE " + VALID_TICKET + " extra"));
    }

    @Test
    void onlyRecognizesStandaloneVoiceMarker() {
        assertTrue(VoiceDirective.hasMarker("@VOICE INVITE " + VALID_TICKET));
        assertTrue(VoiceDirective.hasMarker("  @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker("Player: @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker("@VOICEMAIL something"));
        assertFalse(VoiceDirective.hasMarker("hello @VOICE CLOSE"));
        assertFalse(VoiceDirective.hasMarker(null));
    }

    @Test
    void doesNotIncludeTicketInToString() {
        VoiceDirective directive = VoiceDirective.parse("@VOICE INVITE " + VALID_TICKET);

        assertNotNull(directive);
        assertFalse(directive.toString().contains(VALID_TICKET));
        assertTrue(directive.toString().contains("<redacted>"));
    }
}
