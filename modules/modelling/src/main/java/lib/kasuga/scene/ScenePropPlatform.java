package lib.kasuga.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsBlockRenderer;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftBlockPhysics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

abstract class ScenePropPlatform {
    static boolean screenOpen(Minecraft client) { return client.screen != null; }

    public static void initialize(RenderPipelineRegistrar registrar) {
        MinecraftBlockPhysics.initialize(registrar);
        registrar.world(RenderPipelineDescriptor.builder(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID,
                "scene/vanilla_props"), RenderPhase.AFTER_BLOCK_ENTITIES).priority(100).build(), ScenePropPlatform::render);
    }
    private static void render(WorldRenderPipelineContext context) {
        ClientLevel level = context.level();
        if (level == null) return;
        Vec3 camera = context.camera().getPosition();
        PoseStack poses = context.poseStack();
        SceneProps.visitVisible(level, prop -> {
            Vec3 position = prop.position();
            Quaternionf rotation = prop.renderRotation();
            Vector3f scale = prop.renderScale();
            poses.pushPose();
            try {
                poses.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
                poses.mulPose(rotation);
                poses.scale(scale.x, scale.y, scale.z);
                if (prop.block() != null) {
                    poses.translate(-.5, -.5, -.5);
                    PhysicsBlockRenderer.renderTerrain(context.minecraft().getBlockRenderer(), prop.block(), poses,
                            context.bufferSource(), prop.light(level), OverlayTexture.NO_OVERLAY,
                            level, position, rotation, prop.lightAnchor());
                } else {
                    context.minecraft().getItemRenderer().renderStatic(prop.item(), ItemDisplayContext.FIXED,
                            LevelRenderer.getLightColor(level, BlockPos.containing(position)), OverlayTexture.NO_OVERLAY,
                            poses, context.bufferSource(), level, 0);
                }
            } finally {
                poses.popPose();
            }
        });
        MinecraftBlockPhysics.renderInto(context);
        context.bufferSource().endBatch();
    }
}
