package test.kasuga.modelling.demo.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class LensRegistration implements AutoCloseable {
    private static final Identifier ID = id("black_hole_demo");
    private static final Identifier CAPTURE = id("black_hole_demo_scene");
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(CAPTURE);
    private static final String PARAMETERS = "LensParameters";
    private static final int PARAMETER_BYTES = 2 * 64 + 5 * 16;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/black_hole_demo"))
            .withVertexShader(id("core/post/fullscreen"))
            .withFragmentShader(id("core/black_hole_demo"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER)
                    .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .build();
    private final PostProcessGraphRegistration graph;

    LensRegistration(BlackHoleDemo.Scene demo) {
        graph = PostProcessGraphRegistry.register(ID, 1000, DuplicatePolicy.FAIL, createGraph(demo));
    }

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    private static PostProcessGraph createGraph(BlackHoleDemo.Scene demo) {
        return PostProcessGraph.builder(ID)
                .target(PostProcessTargetDescriptor.builder(CAPTURE).useDepth(true)
                        .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).build())
                .prepare((context, frame) -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level == null) return false;
                    float time = (minecraft.level.getGameTime() % 12000L + context.partialTick()) / 20f;
                    LensView view = LensView.prepare(demo, time, context.camera().pos, context.camera().viewRotationMatrix,
                            WorldProjection.current(), context.mainTarget().width, context.mainTarget().height);
                    if (view == null) return false;
                    frame.put(ID, view);
                    return true;
                })
                .pass(PostProcessGraphPassDescriptor.builder(CAPTURE, context -> {
                    context.copyColor(MAIN, SCENE, true);
                    context.copyDepth(MAIN, SCENE);
                }).reads(MAIN).writes(SCENE).build())
                .pass(PostProcessGraphPassDescriptor.builder(ID, context -> {
                    LensView view = context.frame().require(ID, LensView.class);
                    for (LensView.Lens lens : view.lenses()) {
                        LensView.Bounds bounds = lens.bounds();
                        GpuBufferSlice uniforms = upload(view, lens);
                        PostProcessContext.fullscreen(context.write(MAIN), PIPELINE, false, pass -> {
                            pass.enableScissor(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                            pass.setUniform(PARAMETERS, uniforms);
                            context.bindColor(pass, "SceneSampler", SCENE, 0);
                            context.bindDepth(pass, "DepthSampler", SCENE);
                        });
                    }
                }).reads(SCENE).writesMain().build())
                .build();
    }

    private static GpuBufferSlice upload(LensView view, LensView.Lens lens) {
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(PARAMETER_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer bytes = mapped.data();
            view.projection().get(0, bytes);
            view.inverseProjection().get(64, bytes);
            bytes.position(128);
            bytes.putFloat(zeroToOne ? 1f : 2f).putFloat(zeroToOne ? 0f : -1f).putFloat(.5f).putFloat(0f);
            putVector(bytes, lens.center(), view.radius());
            putVector(bytes, view.normal(), view.time());
            putVector(bytes, view.tangent(), 0);
            putVector(bytes, view.bitangent(), 0);
            return mapped.slice().slice(0, PARAMETER_BYTES);
        }
    }

    private static void putVector(ByteBuffer bytes, Vector3f vector, float w) {
        bytes.putFloat(vector.x).putFloat(vector.y).putFloat(vector.z).putFloat(w);
    }

    @Override
    public void close() { graph.close(); }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }
}
