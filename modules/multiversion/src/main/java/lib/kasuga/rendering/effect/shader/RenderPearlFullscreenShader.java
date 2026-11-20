package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.RenderPearlGlslBackend;
import lib.kasuga.shader.backend.Std140UniformBlock;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Owning handle for a generated 26.3 fullscreen shader (usable with either graphics backend). */
public final class RenderPearlFullscreenShader implements AutoCloseable {
    private static final Identifier VERTEX = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/post/fullscreen");
    private final ShaderProgram program;
    private final RenderPipeline pipeline;
    private final CompiledRenderPipeline compiled;
    private final Std140UniformBlock uniforms;

    private RenderPearlFullscreenShader(ShaderProgram program, RenderPipeline pipeline, CompiledRenderPipeline compiled,
                                        Std140UniformBlock uniforms) {
        this.program = program;
        this.pipeline = pipeline;
        this.compiled = compiled;
        this.uniforms = uniforms;
    }

    /** Compile on the render thread, preferably before the first draw. Recompile to pick up changes to resource files. */
    public static RenderPearlFullscreenShader compile(ShaderProgram program, ResourceProvider resources, ColorTargetState output) throws IOException {
        RenderSystem.assertOnRenderThread();
        RenderPearlGlslBackend.Fullscreen generated = RenderPearlGlslBackend.emitFullscreen(program);
        Std140UniformBlock uniforms = new Std140UniformBlock(generated.uniforms());
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        if (uniforms.size() > 0) layout.withUniform(RenderPearlGlslBackend.PARAMETERS, UniformType.UNIFORM_BUFFER);
        for (String sampler : generated.samplers()) layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        Identifier id = Identifier.parse(program.id());
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(id.withPrefix("pipeline/generated/"))
                .withVertexShader(VERTEX)
                .withFragmentShader(id)
                .withBindGroupLayout(layout.build())
                .withColorTargetState(output)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        Identifier vertexPath = ShaderType.VERTEX.idConverter().idToFile(VERTEX);
        String vertex;
        try (InputStream input = resources.open(vertexPath)) {
            vertex = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        CompiledRenderPipeline compiled;
        try (Source source = new Source(id, vertex, generated.fragmentSource(), resources)) {
            compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        }
        if (compiled == null) throw new IOException("RenderPearl could not compile " + program.id());
        return new RenderPearlFullscreenShader(program, pipeline, compiled, uniforms);
    }

    /** Uniform overrides use column-major matrices and one element per scalar array entry. */
    public void draw(RenderTarget output, boolean clear, Map<String, double[]> values, Consumer<RenderPass> samplers) {
        RenderSystem.assertOnRenderThread();
        if (compiled.isClosed()) throw new IllegalStateException("Shader is closed: " + program.id());
        for (ShaderParameter parameter : program.parameterSchema().parameters()) {
            double[] value = values.get(parameter.name());
            if (value != null) parameter.validate(value);
        }
        GpuBufferSlice parameters = upload(values);
        PostProcessContext.fullscreen(output, pipeline, compiled, clear, pass -> {
            if (parameters != null) pass.setUniform(RenderPearlGlslBackend.PARAMETERS, parameters);
            samplers.accept(pass);
        });
    }

    private @Nullable GpuBufferSlice upload(Map<String, double[]> values) {
        if (uniforms.size() == 0) {
            if (!values.isEmpty()) throw new IllegalArgumentException("Shader declares no uniforms: " + program.id());
            return null;
        }
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(uniforms.size(), alignment, GpuBuffer.USAGE_UNIFORM)) {
            uniforms.encode(mapped.data(), values);
            return mapped.slice().slice(0, uniforms.size());
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (!compiled.isClosed()) compiled.close();
    }

    private record Source(Identifier fragmentId, String vertex, String fragment, ResourceProvider resources,
                          Map<Identifier, CachedIncludeSource> includes) implements ShaderSource {
        Source(Identifier fragmentId, String vertex, String fragment, ResourceProvider resources) {
            this(fragmentId, vertex, fragment, resources, new HashMap<>());
        }

        @Override
        public @Nullable String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX && id.equals(VERTEX)) return vertex;
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return fragment;
            return null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id, include -> {
                try (InputStream input = resources.open(include.withPrefix("shaders/include/"))) {
                    return CachedIncludeSource.create(include, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException failure) {
                    return CachedIncludeSource.createError("Couldn't load shader include " + include + ": " + failure.getMessage());
                }
            });
        }

        @Override
        public void close() {
            for (CachedIncludeSource include : includes.values()) include.close();
            includes.clear();
        }
    }
}
