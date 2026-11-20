package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.host.DocumentSmoke;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.WorldPipelineSmoke;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor.ColorFormat;
import lib.kasuga.rendering.effect.shader.RenderPearlFullscreenShader;
import lib.kasuga.shader.FloatArrayExpr;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.IntExpr;
import lib.kasuga.shader.Mat3Expr;
import lib.kasuga.shader.Sampler2DExpr;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.Vec3Expr;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class PostProcessGraphSmoke {
    private static final Identifier GRAPH = id("pipeline/post/graph_smoke");
    private static final Identifier COLORS_ID = id("graph_smoke/colors");
    private static final Identifier DEPTH_ID = id("graph_smoke/depth");
    private static final Identifier PROBE_ID = id("graph_smoke/probe");
    private static final Identifier GENERATED_ID = id("graph_smoke/generated");
    private static final PostProcessGraphTarget GENERATED = PostProcessGraphTarget.managed(GENERATED_ID);
    private static final PostProcessGraphTarget PROBE = PostProcessGraphTarget.managed(PROBE_ID);
    private static final PostProcessGraphTarget COLORS = PostProcessGraphTarget.managed(COLORS_ID);
    private static final PostProcessGraphTarget DEPTH = PostProcessGraphTarget.managed(DEPTH_ID);
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final RenderPipeline MRT = RenderPipeline.builder()
            .withLocation(GRAPH)
            .withVertexShader(id("core/post/fullscreen"))
            .withFragmentShader(id("core/post/graph_smoke"))
            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, ColorTargetState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();
    private static final RenderPipeline VERIFY = RenderPipeline.builder()
            .withLocation(id("pipeline/post/graph_verify"))
            .withVertexShader(id("core/post/fullscreen"))
            .withFragmentShader(id("core/post/graph_verify"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("HdrSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MainDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CopiedDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();
    private static PostProcessGraphRegistration registration;
    private static PostProcessGraphRegistration brokenRegistration;
    private static RenderTarget probe;
    private static RenderTarget generatedOutput;
    private static RenderPearlFullscreenShader generatedShader;
    private static boolean verifiedGenerated;
    private static boolean threw;
    private static boolean verifiedMain;
    private static boolean verifiedProbe;
    private static GpuTextureView hdr;
    private static GpuTextureView ldr;
    private static GpuTextureView depth;
    private static boolean prepared;
    private static int frames;
    private static boolean pending;
    private static boolean finished;
    private static RuntimeException failure;

    private PostProcessGraphSmoke() {}

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(MRT);
        event.registerPipeline(VERIFY);
    }

    public static boolean needsFrames() { return !finished; }

    public static void afterFrame(Minecraft client) {
        if (failure != null) throw failure;
        if (registration == null) {
            install(client);
            return;
        }
        if (pending || frames < 3) return;
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                verifyPixels(image);
                client.execute(() -> {
                    verifiedMain = true;
                    finish(client);
                });
            } catch (RuntimeException error) {
                failure = error;
            }
        });
        Screenshot.takeScreenshot(probe, image -> {
            try (image) {
                verifyProbe(image);
                client.execute(() -> {
                    verifiedProbe = true;
                    finish(client);
                });
            } catch (RuntimeException error) {
                failure = error;
            }
        });
        Screenshot.takeScreenshot(generatedOutput, image -> {
            try (image) {
                verifyGenerated(image);
                client.execute(() -> {
                    verifiedGenerated = true;
                    finish(client);
                });
            } catch (RuntimeException error) {
                failure = error;
            }
        });
    }

    private static void install(Minecraft client) {
        compileGeneratedShader(client);
        WorldPipelineSmoke.install(() -> frames);
        PostProcessGraph broken = PostProcessGraph.builder(id("graph_smoke/broken"))
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/throw_once"), context -> {
                    threw = true;
                    brokenRegistration.close();
                    throw new IllegalStateException("Intentional post-process isolation probe");
                }).writesMain().build())
                .build();
        brokenRegistration = PostProcessGraphRegistry.register(id("smoke"), -1, DuplicatePolicy.FAIL, broken);
        PostProcessGraph graph = PostProcessGraph.builder(GRAPH)
                .target(PostProcessTargetDescriptor.builder(COLORS_ID).screenScale(.5f)
                        .colorAttachments(ColorFormat.RGBA16F, ColorFormat.RGBA8).build())
                .target(PostProcessTargetDescriptor.builder(DEPTH_ID).useDepth(true).build())
                .target(PostProcessTargetDescriptor.builder(PROBE_ID).fixedSize(16, 16).build())
                .target(PostProcessTargetDescriptor.builder(GENERATED_ID).fixedSize(16, 16).build())
                .prepare((context, frame) -> {
                    if (prepared) return true;
                    require(threw && !brokenRegistration.isActive(), "Failed graph was not isolated from the next graph");
                    prepared = true;
                    return false;
                })
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/verify"), context -> {
                    probe = context.write(PROBE);
                    context.fullscreen(PROBE, VERIFY, true, pass -> {
                        context.bindColor(pass, "HdrSampler", COLORS, 0);
                        context.bindDepth(pass, "MainDepthSampler", MAIN);
                        context.bindDepth(pass, "CopiedDepthSampler", DEPTH);
                    });
                }).readsMain().reads(COLORS, DEPTH).writes(PROBE).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/generated"), context -> {
                    generatedOutput = context.write(GENERATED);
                    context.fullscreen(GENERATED, generatedShader, true,
                            Map.of("Tint", new double[]{.1, .2, .5}, "Weights", new double[]{.9, .3, .8}),
                            pass -> context.bindColor(pass, "ProbeSampler", PROBE, 0));
                }).reads(PROBE).writes(GENERATED).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/display"), context -> {
                    hdr = PostProcessContext.color(context.read(COLORS), 0);
                    ldr = PostProcessContext.color(context.read(COLORS), 1);
                    depth = PostProcessContext.depth(context.read(DEPTH));
                    require(hdr.texture().getFormat() == GpuFormat.RGBA16_FLOAT && ldr.texture().getFormat() == GpuFormat.RGBA8_UNORM,
                            "Unexpected MRT formats");
                    require(hdr.getWidth(0) < context.write(MAIN).width, "Scaled target was not smaller than the world target");
                    context.copyColor(COLORS, 1, MAIN, 0, true);
                    frames++;
                }).reads(COLORS, DEPTH).writesMain().build())
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/capture_depth"),
                        context -> context.copyDepth(MAIN, DEPTH)).readsMain().writes(DEPTH).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("graph_smoke/render_mrt"),
                        context -> context.fullscreen(COLORS, MRT, true, pass -> {})).writes(COLORS).build())
                .build();
        PostProcessGraphRegistration original = PostProcessGraphRegistry.register(id("smoke"), 0, DuplicatePolicy.FAIL, graph);
        registration = PostProcessGraphRegistry.register(id("smoke"), 0, DuplicatePolicy.REPLACE, graph);
        original.close();
        require(registration.isActive() && !original.isActive(), "A stale handle removed its replacement");
    }

    private static void compileGeneratedShader(Minecraft client) {
        ShaderProgram program = ShaderProgram.fullscreen("kasuga_lib:graph_smoke/generated", shader -> {
            Vec3Expr tint = shader.uniformVec3("Tint", 0, 0, 0);
            FloatExpr scale = shader.uniformFloat("Scale", .5f);
            Mat3Expr transform = shader.uniformMat3("Transform", 1, 0, 0, 0, 2, 0, 0, 0, 1);
            FloatArrayExpr weights = shader.uniformFloatArray("Weights", 3, 0);
            IntExpr index = shader.uniformInt("Index", 1);
            Sampler2DExpr source = shader.sampler2D("ProbeSampler");
            FloatExpr probe = source.sample(shader.texCoord()).r();
            Vec3Expr result = transform.transform(tint).mul(scale);
            shader.fragmentColor(shader.vec4(result.x(), result.y(), weights.get(index).mul(probe), shader.f32(1)));
        });
        try {
            generatedShader = RenderPearlFullscreenShader.compile(program, client.getResourceManager(), ColorTargetState.DEFAULT);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to compile generated fullscreen shader", exception);
        }
    }

    private static void verifyGenerated(NativeImage image) {
        int pixel = image.getPixel(8, 8);
        require(Math.abs((pixel >>> 16 & 255) - 13) <= 1 && Math.abs((pixel >>> 8 & 255) - 51) <= 1
                && Math.abs((pixel & 255) - 77) <= 1, "Generated shader parameters or sampler were incorrect: " + Integer.toHexString(pixel));
    }

    private static void verifyPixels(NativeImage image) {
        int y = image.getHeight() / 2;
        int left = image.getPixel(image.getWidth() / 4, y);
        int right = image.getPixel(image.getWidth() * 3 / 4, y);
        require((left >>> 16 & 255) > 180 && (left & 255) > 100 && (right & 255) > 180 && (right >>> 16 & 255) < 65,
                "Graph's second attachment did not reach the final frame: " + Integer.toHexString(left) + ", " + Integer.toHexString(right));
    }

    private static void verifyProbe(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixel(x, y);
                require((pixel >>> 16 & 255) > 240 && (pixel >>> 8 & 255) > 240,
                        "HDR value or copied depth did not match: " + Integer.toHexString(pixel));
            }
        }
    }

    private static void finish(Minecraft client) {
        if (!verifiedMain || !verifiedProbe || !verifiedGenerated) return;
        generatedShader.close();
        LogUtils.getLogger().info("KASUGA_SHADER_DSL_PASS generated-fullscreen,uniform-defaults,uniform-overrides,std140,sampler");
        WorldPipelineSmoke.finish();
        GpuTextureView probeView = PostProcessContext.color(probe, 0);
        registration.close();
        require(hdr.isClosed() && ldr.isClosed() && depth.isClosed() && probeView.isClosed() && !registration.isActive(), "Graph targets did not retire");
        finished = true;
        LogUtils.getLogger().info("KASUGA_POST_GRAPH_PASS dependency-order,mrt,hdr-readback,scaled-copy,depth-readback,exception-isolation,replace,retire");
        DocumentSmoke.start(client);
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
