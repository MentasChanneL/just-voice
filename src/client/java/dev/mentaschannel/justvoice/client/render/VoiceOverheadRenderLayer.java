package dev.mentaschannel.justvoice.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Renders the animated speaker icon above players who are currently speaking. */
public final class VoiceOverheadRenderLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
    private static final ResourceLocation SPEAKER_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            JustVoiceClient.MOD_ID,
            "textures/gui/sprites/speaker.png"
    );
    private static final float ICON_HALF_SIZE = 0.34f;
    private static final float HEAD_OFFSET = 0.92f;
    private static final int FULL_BRIGHT = 0x00F000F0;

    private VoiceOverheadRenderLayer(RenderLayerParent<PlayerRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    public static void register() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
                (entityType, entityRenderer, registrationHelper, context) -> {
                    if (entityType == EntityType.PLAYER && entityRenderer instanceof PlayerRenderer playerRenderer) {
                        registrationHelper.register(new VoiceOverheadRenderLayer(playerRenderer));
                    }
                }
        );
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            PlayerRenderState state,
            float yRot,
            float xRot
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || state.isInvisible) {
            return;
        }

        Player player = findPlayer(client, state);
        if (player == null || (player == client.player && client.options.getCameraType().isFirstPerson())) {
            return;
        }

        VoiceSessionManager manager = JustVoiceClient.getActiveSessionManager();
        if (manager == null
                || manager.getActiveSpeakerColor(player.getGameProfile().getName()) == null) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        PoseStack billboardPose = new PoseStack();
        billboardPose.translate(
                state.x - cameraPosition.x,
                state.y - cameraPosition.y + state.boundingBoxHeight + HEAD_OFFSET,
                state.z - cameraPosition.z
        );
        billboardPose.mulPose(camera.rotation());

        VertexConsumer vertices = bufferSource.getBuffer(RenderType.eyes(SPEAKER_TEXTURE));
        submitIconQuad(vertices, billboardPose.last(), FULL_BRIGHT);
    }

    /**
     * PlayerRenderState in 1.21.6 does not retain the entity id. Resolve the
     * render state against nearby world players using its interpolated position.
     */
    private static Player findPlayer(Minecraft client, PlayerRenderState state) {
        Player closest = null;
        double bestDistance = 2.25;
        for (Player candidate : client.level.players()) {
            double dx = candidate.getX() - state.x;
            double dy = candidate.getY() - state.y;
            double dz = candidate.getZ() - state.z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }

    private static void submitIconQuad(VertexConsumer vertices, PoseStack.Pose pose, int lightCoords) {
        vertex(vertices, pose, -ICON_HALF_SIZE, -ICON_HALF_SIZE, 0.0f, 1.0f, lightCoords);
        vertex(vertices, pose, ICON_HALF_SIZE, -ICON_HALF_SIZE, 1.0f, 1.0f, lightCoords);
        vertex(vertices, pose, ICON_HALF_SIZE, ICON_HALF_SIZE, 1.0f, 0.0f, lightCoords);
        vertex(vertices, pose, -ICON_HALF_SIZE, ICON_HALF_SIZE, 0.0f, 0.0f, lightCoords);
    }

    private static void vertex(
            VertexConsumer vertices,
            PoseStack.Pose pose,
            float x,
            float y,
            float u,
            float v,
            int lightCoords
    ) {
        vertices.addVertex(pose, x, y, 0.0f)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lightCoords)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }
}
