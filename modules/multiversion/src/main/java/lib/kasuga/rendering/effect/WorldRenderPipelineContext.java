package lib.kasuga.rendering.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Borrowed state valid only for one world callback. */
public record WorldRenderPipelineContext(
        RenderPipelineDescriptor descriptor,
        RenderLevelStageEvent event,
        @Nullable RenderPass renderPass,
        @Nullable OitStage oitStage
) {
    public PoseStack poseStack() { return event.getPoseStack(); }
    public Matrix4fc modelViewMatrix() { return event.getModelViewMatrix(); }
    public CameraRenderState camera() { return event.getLevelRenderState().cameraRenderState; }
    public Frustum frustum() { return camera().cullFrustum; }
    public float partialTick() { return event.getLevelRenderState().worldPartialTicks; }
}
