package dev.mentaschannel.justvoice.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.mentaschannel.justvoice.JustVoiceClient;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the animated GUI-atlas speaker sprite as a camera-facing marker
 * above speaking players. Its eyes render type disables cardinal lighting,
 * preventing camera-facing brightness changes with the billboard normal.
 */
public final class VoiceOverheadRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final SpriteId SPEAKER_SPRITE = new SpriteId(
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/gui.png"),
            Identifier.fromNamespaceAndPath(JustVoiceClient.MOD_ID, "speaker")
    );
    private static final float ICON_HALF_SIZE = 0.34f;
    private static final float HEAD_OFFSET = 0.92f;
    private static final int FULL_BRIGHT = 0x00F000F0;

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

        // Match the server-issued voice profile against the account name,
        // not a potentially customized/formatted display-name component.
        if (manager.getActiveSpeakerColor(player.getGameProfile().name()) == null) {
            return;
        }

        // RenderLayer receives a pose already rotated/scaled for the player
        // model. Build a fresh camera-relative pose so the icon cannot inherit
        // the model's upside-down Y axis, body yaw, swim pose or rotations.
        var camera = client.gameRenderer.mainCamera();
        Vec3 cameraPosition = camera.position();

        PoseStack billboardPose = new PoseStack();
        billboardPose.translate(
                state.x - cameraPosition.x,
                state.y - cameraPosition.y + state.boundingBoxHeight + HEAD_OFFSET,
                state.z - cameraPosition.z
        );
        billboardPose.rotate(camera.rotation());

        TextureAtlasSprite speakerSprite = client.getAtlasManager().get(SPEAKER_SPRITE);
        submitNodeCollector.submitCustomGeometry(
                billboardPose,
                RenderTypes.eyes(speakerSprite.atlasLocation()),
                (pose, vertices) -> submitIconQuad(vertices, pose, speakerSprite, FULL_BRIGHT)
        );
    }

    private static void submitIconQuad(
            VertexConsumer vertices,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            int lightCoords
    ) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // RenderTypes.eyes uses face culling. Emit the billboard's front
        // face counter-clockwise (as viewed by the camera) so it is not culled.
        vertex(vertices, pose, -ICON_HALF_SIZE, -ICON_HALF_SIZE, u0, v1, lightCoords);
        vertex(vertices, pose, ICON_HALF_SIZE, -ICON_HALF_SIZE, u1, v1, lightCoords);
        vertex(vertices, pose, ICON_HALF_SIZE, ICON_HALF_SIZE, u1, v0, lightCoords);
        vertex(vertices, pose, -ICON_HALF_SIZE, ICON_HALF_SIZE, u0, v0, lightCoords);
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
