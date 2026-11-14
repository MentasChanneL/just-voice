package dev.mentaschannel.justvoice.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;

/**
 * Renders a small team-coloured MIC marker above a speaking player.
 * The local avatar is included only in third-person camera modes.
 */
public final class VoiceOverheadRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final String MARKER = "MIC";

    private VoiceOverheadRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
                (entityType, entityRenderer, registrationHelper, context) -> {
                    if (entityType == EntityTypes.PLAYER
                            && entityRenderer instanceof AvatarRenderer<?> avatarRenderer) {
                        registrationHelper.register(new VoiceOverheadRenderLayer(avatarRenderer));
                    }
                }
        );
    }

    @Override
    public void submit(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            AvatarRenderState state,
            float yRot,
            float xRot
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || state.isInvisible) {
            return;
        }

        if (!(client.level.getEntity(state.id) instanceof Player player)) {
            return;
        }

        if (player == client.player && client.options.getCameraType().isFirstPerson()) {
            return;
        }

        VoiceSessionManager manager = JustVoiceClient.getActiveSessionManager();
        if (manager == null) {
            return;
        }

        Integer speakerColor = manager.getActiveSpeakerColor(player.getName().getString());
        if (speakerColor == null) {
            return;
        }

        Font font = client.font;
        float textX = -font.width(MARKER) / 2.0f;

        poseStack.pushPose();
        poseStack.translate(0.0, state.boundingBoxHeight + 0.18, 0.0);
        poseStack.rotate(client.gameRenderer.mainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        submitNodeCollector.submitTextBackground(
                poseStack,
                textX - 2.0f,
                -2.0f,
                textX + font.width(MARKER) + 2.0f,
                font.lineHeight + 1.0f,
                0x990B1018,
                Font.DisplayMode.NORMAL,
                lightCoords
        );
        submitNodeCollector.submitText(
                poseStack,
                textX,
                0.0f,
                Component.literal(MARKER).getVisualOrderText(),
                true,
                Font.DisplayMode.NORMAL,
                lightCoords,
                speakerColor,
                0,
                0
        );
        poseStack.popPose();
    }
}
