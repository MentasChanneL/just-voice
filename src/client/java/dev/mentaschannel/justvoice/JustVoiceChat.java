package dev.mentaschannel.justvoice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;

public final class JustVoiceChat {
    private JustVoiceChat() {}

    public static void info(String langKey, Object... arguments) {
        send(formatMessage("ℹ", 0x5DEAD7, 0xD3FFF9, langKey, arguments));
    }

    public static void warn(String langKey, Object... arguments) {
        send(formatMessage("⚠", 0xFFCE0C, 0xFFF0B5, langKey, arguments));
    }

    public static void error(String langKey, Object... arguments) {
        send(formatMessage("✘", 0xFF0000, 0xFFBABA, langKey, arguments));
    }

    public static void successfully(String langKey, Object... arguments) {
        send(formatMessage("✔", 0x56FF5C, 0xADFFB0, langKey, arguments));
    }

    public static void joined(String name) {
        send(text("", 0x72D2FF).append(icon()).append(lang("message.justvoice.peer_joined", name)));
    }

    public static void left(String name) {
        send(text("", 0x4A88A5).append(icon()).append(lang("message.justvoice.peer_left", name)));
    }

    private static void send(Component message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(
                Component.empty().append(message).setStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(icon().append(text(" ")).append(lang("message.justvoice.is_justvoice_message")))))
        ));
    }

    private static Component formatMessage(String icon, int color1, int color2, String lang, Object ... args) {
        return text("⊂", color1)
                .append(icon())
                .append(text("⊃ " + icon))
                .append(text(" » ", color2).append(lang(lang, args)));
    }

    private static MutableComponent text(String text, int color) {
        return Component.literal(text).setStyle(Style.EMPTY.withFont(FontDescription.DEFAULT).withColor(color));
    }

    private static MutableComponent text(String text) {
        return Component.literal(text).setStyle(Style.EMPTY.withFont(FontDescription.DEFAULT));
    }

    private static MutableComponent lang(String key, Object ... args) {
        return Component.translatable(key, args).setStyle(Style.EMPTY.withFont(FontDescription.DEFAULT));
    }

    private static MutableComponent icon() {
        return Component.literal("0").setStyle(Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("justvoice", "icons"))));
    }
}
