package lib.kasuga.rendering.models.mc.dynamic;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.PipelineBlendMode;
import lib.kasuga.rendering.effect.pipeline.PipelineCullMode;
import lib.kasuga.rendering.effect.pipeline.PipelineDepthTest;
import lib.kasuga.rendering.effect.pipeline.PipelineTarget;
import lib.kasuga.rendering.effect.pipeline.PipelineWriteMask;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime.EmitterModule;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime.ParticleView;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ModelParticleRenderer {
    private static final Map<ResourceLocation, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();
    private static boolean initialized;
    private static int lastVisibleCount;

    private ModelParticleRenderer() {}

    static void invalidateTextures() { TEXTURES.clear(); }
    static int lastVisibleCount() { return lastVisibleCount; }

    static synchronized void initialize(RenderPipelineRegistrar registrar) {
        if (initialized) return;
        registrar.world(RenderPipelineDescriptor.builder(ResourceLocation.parse("kasuga_lib:effects/model_emitters"), RenderPhase.AFTER_PARTICLES)
                .draw(draw -> draw.vertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
                        .primitiveMode(VertexFormat.Mode.QUADS).bufferSize(32768).sortOnUpload(true)
                        .shaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                        .blend(PipelineBlendMode.TRANSLUCENT).depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.DISABLED).writeMask(PipelineWriteMask.COLOR).target(PipelineTarget.PARTICLES))
                .build(), ModelParticleRenderer::render);
        initialized = true;
    }

    private static void render(WorldRenderPipelineContext context) {
        List<ParticleView> particles = new ArrayList<>();
        for (EmitterModule module : ModelParticleRuntime.modules()) if (module.enabled()) particles.addAll(module.particles());
        Vec3 camera = context.camera().getPosition();
        particles.sort(Comparator.comparingDouble((ParticleView p) -> p.distanceSquared(camera.x, camera.y, camera.z)).reversed());
        Set<RenderType> used = new HashSet<>();
        lastVisibleCount = 0;
        try {
            for (ParticleView particle : particles) {
                Vector3d position = particle.position();
                double radius = particle.size();
                if (context.frustum() != null && !context.frustum().isVisible(new AABB(
                        position.x - radius, position.y - radius, position.z - radius,
                        position.x + radius, position.y + radius, position.z + radius))) continue;
                ResourceLocation texture = TEXTURES.computeIfAbsent(particle.texture(), id ->
                        context.minecraft().getResourceManager().getResource(id).isPresent() ? id : ModelParticleRuntime.DEFAULT_TEXTURE);
                RenderType type = context.pipeline().renderType(texture, false, false);
                used.add(type);
                VertexConsumer buffer = context.bufferSource().getBuffer(type);
                PoseStack pose = context.poseStack();
                pose.pushPose();
                try {
                    pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
                    pose.mulPose(context.camera().rotation());
                    Matrix4f matrix = pose.last().pose();
                    float half = particle.size() * .5f;
                    Vector4f color = particle.color();
                    vertex(buffer, matrix, -half, -half, 0, 1, color);
                    vertex(buffer, matrix, half, -half, 1, 1, color);
                    vertex(buffer, matrix, half, half, 1, 0, color);
                    vertex(buffer, matrix, -half, half, 0, 0, color);
                } finally {
                    pose.popPose();
                }
                lastVisibleCount++;
            }
        } finally {
            for (RenderType type : used) context.bufferSource().endBatch(type);
        }
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, float u, float v, Vector4f color) {
        buffer.addVertex(pose, x, y, 0).setColor(color.x, color.y, color.z, color.w).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
    }
}
