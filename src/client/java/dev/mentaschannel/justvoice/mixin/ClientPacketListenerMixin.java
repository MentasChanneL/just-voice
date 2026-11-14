package dev.mentaschannel.justvoice.mixin;

import dev.mentaschannel.justvoice.JustVoiceClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * /title actionbar uses a dedicated packet and bypasses Fabric's ordinary
 * ClientReceiveMessageEvents. Intercept it before vanilla renders the text.
 *
 * Minecraft 26.1+ is unobfuscated, so the target is used as-is.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ClientPacketListenerMixin {
    @Inject(
            method = "setActionBarText",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void justvoice$consumeVoiceDirective(
            ClientboundSetActionBarTextPacket packet,
            CallbackInfo callbackInfo
    ) {
        if (JustVoiceClient.consumeActionBarDirective(packet.text())) {
            callbackInfo.cancel();
        }
    }
}
