package dev.mentaschannel.justvoice;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Centralized, localized chat output for Just Voice. */
public final class JustVoiceChat {
    private JustVoiceChat() {
    }

    public static void info(String langKey, Object... arguments) {
        send(langKey, ChatFormatting.GREEN, arguments);
    }

    public static void warn(String langKey, Object... arguments) {
        send(langKey, ChatFormatting.YELLOW, arguments);
    }

    public static void error(String langKey, Object... arguments) {
        send(langKey, ChatFormatting.RED, arguments);
    }

    public static void joined(String langKey, Object... arguments) {
        send(langKey, ChatFormatting.YELLOW, arguments);
    }

    public static void left(String langKey, Object... arguments) {
        send(langKey, ChatFormatting.GRAY, arguments);
    }

    private static void send(String langKey, ChatFormatting color, Object... arguments) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(
                Component.translatable(langKey, arguments).withStyle(color)
        ));
    }
}
