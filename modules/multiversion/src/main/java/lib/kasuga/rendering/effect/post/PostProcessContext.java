package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Native post-process commands for a completed world view. Targets are borrowed for the current graph pass. */
public final class PostProcessContext {
    private static final Identifier FULLSCREEN = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/post/fullscreen");
    private static final Identifier COPY = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/post/copy");
    private static final RenderPipeline COPY_RGBA8 = copyPipeline(GpuFormat.RGBA8_UNORM);
    private static final RenderPipeline COPY_RGBA16 = copyPipeline(GpuFormat.RGBA16_FLOAT);
    private static final RenderPipeline COPY_RGBA32 = copyPipeline(GpuFormat.RGBA32_FLOAT);

    private final RenderTarget mainTarget;
    private final PostProcessTargetPool pool;
    private final CameraRenderState camera;
    private final float partialTick;

    public PostProcessContext(RenderTarget mainTarget, PostProcessTargetPool pool, CameraRenderState camera, float partialTick) {
        this.mainTarget = Objects.requireNonNull(mainTarget);
        this.pool = Objects.requireNonNull(pool);
        this.camera = Objects.requireNonNull(camera);
        this.partialTick = partialTick;
    }

    public RenderTarget mainTarget() { return mainTarget; }
    public CameraRenderState camera() { return camera; }
    public float partialTick() { return partialTick; }

    public RenderTarget acquire(PostProcessTargetDescriptor descriptor) {
        return pool.acquire(descriptor, mainTarget);
    }

    public static GpuTextureView color(RenderTarget target, int index) {
        return PostProcessTargetPool.color(target, index);
    }

    public static GpuTextureView depth(RenderTarget target) {
        return Objects.requireNonNull(target.getDepthTextureView(), "Target is missing its depth attachment");
    }

    public static FilterMode filter(RenderTarget target) {
        PostProcessTargetDescriptor descriptor = PostProcessTargetPool.descriptor(target);
        return descriptor != null && descriptor.filter() == PostProcessTargetDescriptor.TextureFilter.LINEAR
                ? FilterMode.LINEAR : FilterMode.NEAREST;
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(COPY_RGBA8);
        event.registerPipeline(COPY_RGBA16);
        event.registerPipeline(COPY_RGBA32);
    }

    public void copyColor(RenderTarget source, RenderTarget destination, boolean linearFilter) {
        copyColor(source, 0, destination, 0, linearFilter);
    }

    public void copyColor(RenderTarget source, int sourceIndex, RenderTarget destination, int destinationIndex, boolean linearFilter) {
        RenderSystem.assertOnRenderThread();
        GpuTextureView from = color(source, sourceIndex);
        GpuTextureView to = color(destination, destinationIndex);
        if (from.texture() == to.texture()) throw new IllegalArgumentException("Cannot copy a color attachment into itself");
        GpuFormat format = to.texture().getFormat();
        if (source.width == destination.width && source.height == destination.height && from.texture().getFormat() == format) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(from.texture(), to.texture(),
                    0, 0, 0, 0, 0, destination.width, destination.height);
            return;
        }
        RenderPipeline pipeline;
        if (format == GpuFormat.RGBA8_UNORM) pipeline = COPY_RGBA8;
        else if (format == GpuFormat.RGBA16_FLOAT) pipeline = COPY_RGBA16;
        else if (format == GpuFormat.RGBA32_FLOAT) pipeline = COPY_RGBA32;
        else throw new IllegalArgumentException("Unsupported color copy destination format: " + format);
        FilterMode filter = linearFilter ? FilterMode.LINEAR : FilterMode.NEAREST;
        RenderPassDescriptor.Builder pass = RenderPassDescriptor.builder(() -> "Kasuga color copy").withColorAttachment(to);
        draw(pass, RenderSystem.getCompiledPipeline(pipeline), bindings -> bindings.setUniform("SourceSampler", from, RenderSystem.getSamplerCache().getClampToEdge(filter)));
    }

    public void copyDepth(RenderTarget source, RenderTarget destination) {
        RenderSystem.assertOnRenderThread();
        GpuTexture from = depth(source).texture();
        GpuTexture to = depth(destination).texture();
        if (from == to || source.width != destination.width || source.height != destination.height || from.getFormat() != to.getFormat()) {
            throw new IllegalArgumentException("Depth copy requires distinct targets with equal sizes and depth formats");
        }
        destination.copyDepthFrom(source);
    }

    /** Runs a fullscreen triangle with the attachments requested by the pipeline. */
    public static void fullscreen(RenderTarget target, RenderPipeline pipeline, boolean clear, Consumer<RenderPass> inputs) {
        fullscreen(target, pipeline, RenderSystem.getCompiledPipeline(pipeline), clear, inputs);
    }

    /** Runs a fullscreen triangle using a caller-owned compiled pipeline. */
    public static void fullscreen(RenderTarget target, RenderPipeline pipeline, CompiledRenderPipeline compiled,
                                  boolean clear, Consumer<RenderPass> inputs) {
        RenderSystem.assertOnRenderThread();
        RenderPassDescriptor.Builder pass = RenderPassDescriptor.builder(() -> pipeline.getLocation().toString());
        PostProcessTargetDescriptor descriptor = PostProcessTargetPool.descriptor(target);
        Optional<Vector4fc> clearColor = Optional.empty();
        if (clear) {
            clearColor = Optional.of(descriptor == null ? new Vector4f()
                    : new Vector4f(descriptor.clearRed(), descriptor.clearGreen(), descriptor.clearBlue(), descriptor.clearAlpha()));
        }
        List<ColorTargetState> attachments = pipeline.getColorTargetStates();
        for (int index = 0; index < attachments.size(); index++) {
            ColorTargetState state = attachments.get(index);
            if (state == null) {
                pass.withUnusedColorAttachment();
                continue;
            }
            GpuTextureView color = color(target, index);
            if (color.texture().getFormat() != state.format()) {
                throw new IllegalArgumentException("Pipeline and target formats differ at color attachment " + index);
            }
            pass.withColorAttachment(color, clearColor);
        }
        if (pipeline.wantsDepthTexture()) pass.withDepthAttachment(depth(target));
        draw(pass, compiled, inputs);
    }

    private static void draw(RenderPassDescriptor.Builder descriptor, CompiledRenderPipeline pipeline, Consumer<RenderPass> inputs) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            inputs.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline copyPipeline(GpuFormat format) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "pipeline/post/copy_" + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(FULLSCREEN)
                .withFragmentShader(COPY)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("SourceSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .build();
    }
}
