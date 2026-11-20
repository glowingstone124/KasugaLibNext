package test.kasuga.modelling.demo.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

final class LensRegistration implements AutoCloseable {
    private static final ResourceLocation ID = id("black_hole_demo");
    private static final ResourceLocation CAPTURE = id("black_hole_demo_scene");
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(CAPTURE);
    private final ShaderRegistration shader;
    private final PostProcessGraphRegistration graph;

    LensRegistration(BlackHoleDemo.Scene demo) {
        shader = RenderShaderRegistry.register(ID, RenderShaderDescriptor.builder(ID, DefaultVertexFormat.BLIT_SCREEN).build(),
                DuplicatePolicy.FAIL);
        try {
            RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(ID, RenderPhase.POST_PROCESS)
                    .priority(1000).build();
            graph = PostProcessGraphRegistry.register(ID, descriptor, DuplicatePolicy.FAIL, createGraph(demo));
        } catch (RuntimeException failure) {
            shader.close();
            throw failure;
        }
    }

    private PostProcessGraph createGraph(BlackHoleDemo.Scene demo) {
        return PostProcessGraph.builder(ID)
                .target(PostProcessTargetDescriptor.builder(CAPTURE).useDepth(true)
                        .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).build())
                .prepare((context, frame) -> {
                    if (!shader.handle().isReady()) return false;
                    WorldRenderPipelineContext world = context.world();
                    float time = (world.level().getGameTime() % 12000L
                            + world.partialTick().getGameTimeDeltaPartialTick(false)) / 20f;
                    LensView view = LensView.prepare(demo, time, world.camera().getPosition(), world.modelViewMatrix(),
                            world.projectionMatrix(), context.mainTarget().width, context.mainTarget().height);
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
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                        IntBuffer previous = stack.mallocInt(4);
                        if (hadScissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previous);
                        try {
                            for (LensView.Lens lens : view.lenses()) {
                                LensView.Bounds bounds = lens.bounds();
                                context.fullscreen(MAIN, shader.handle())
                                        .colorSampler("SceneSampler", context.read(SCENE))
                                        .depthSampler("DepthSampler", context.read(SCENE))
                                        .uniforms(program -> {
                                            setUniforms(program, view, lens);
                                            RenderSystem.enableScissor(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                                        }).draw(false);
                            }
                        } finally {
                            if (hadScissor) RenderSystem.enableScissor(previous.get(0), previous.get(1), previous.get(2), previous.get(3));
                            else RenderSystem.disableScissor();
                        }
                    }
                }).reads(SCENE).writesMain().build())
                .build();
    }

    private static void setUniforms(ShaderInstance program, LensView view, LensView.Lens lens) {
        program.safeGetUniform("Projection").set(view.projection());
        program.safeGetUniform("InverseProjection").set(view.inverseProjection());
        program.safeGetUniform("DepthTransform").set(2f, -1f, 0f, 0f);
        setVector(program, "CenterRadius", lens.center(), view.radius());
        setVector(program, "DiskNormalTime", view.normal(), view.time());
        setVector(program, "DiskTangent", view.tangent(), 0f);
        setVector(program, "DiskBitangent", view.bitangent(), 0f);
    }

    private static void setVector(ShaderInstance program, String name, Vector3f vector, float w) {
        program.safeGetUniform(name).set(vector.x, vector.y, vector.z, w);
    }

    @Override
    public void close() {
        try {
            graph.close();
        } finally {
            shader.close();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, path);
    }
}
