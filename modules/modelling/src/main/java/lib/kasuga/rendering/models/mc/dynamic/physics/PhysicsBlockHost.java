package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

abstract class PhysicsBlockHost {
    /** Registers the shared physics block render resources through Kasuga's pipeline API. */
    public static void initialize(RenderPipelineRegistrar registrar) {
        PhysicsBlockRenderer.initialize(registrar);
    }

    /** Renders physics blocks into a host-owned Kasuga world pipeline. */
    public static void renderInto(WorldRenderPipelineContext context) {
        Minecraft minecraft = context.minecraft();
        List<MinecraftBlockRigidBody> snapshot = MinecraftBlockPhysics.snapshot();
        if (snapshot.isEmpty()) return;
        ClientLevel level = context.level();
        if (level == null) return;
        PoseStack poses = context.poseStack();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        Vec3 camera = context.camera().getPosition();
        for (MinecraftBlockRigidBody prop : snapshot) {
            Vector3f position = prop.body().positionRef();
            Quaternionf rotation = prop.body().rotationRef();
            poses.pushPose();
            poses.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
            poses.mulPose(rotation);
            poses.translate(-MinecraftBlockRigidBody.HALF_EXTENT,
                    -MinecraftBlockRigidBody.HALF_EXTENT, -MinecraftBlockRigidBody.HALF_EXTENT);
            PhysicsBlockRenderer.renderTerrain(minecraft.getBlockRenderer(), prop.state(), poses, buffers,
                    prop.lightSample(level), OverlayTexture.NO_OVERLAY, level, prop.position(), rotation, prop.lightAnchor());
            poses.popPose();
        }
    }
}
