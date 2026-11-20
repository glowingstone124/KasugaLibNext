package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
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
import java.util.List;
import java.util.Map;

final class RenderPearlShaderSupport {
    private RenderPearlShaderSupport() {}

    static BindGroupLayout bindings(Std140UniformBlock uniforms, List<String> samplers) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        if (uniforms.size() > 0) layout.withUniform(RenderPearlGlslBackend.PARAMETERS, UniformType.UNIFORM_BUFFER);
        for (String sampler : samplers) layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        return layout.build();
    }

    static CompiledRenderPipeline compile(RenderPipeline pipeline, Identifier vertexId, String vertex, Identifier fragmentId,
                                          String fragment, ResourceProvider resources) throws IOException {
        try (Source source = new Source(vertexId, fragmentId, vertex, fragment, resources)) {
            CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run)
                    .join().finishCompile();
            if (compiled == null) throw new IOException("RenderPearl could not compile " + pipeline.getLocation());
            return compiled;
        }
    }

    static @Nullable GpuBufferSlice upload(ShaderProgram program, Std140UniformBlock uniforms, Map<String, double[]> values) {
        for (ShaderParameter parameter : program.parameterSchema().parameters()) {
            double[] value = values.get(parameter.name());
            if (value != null) parameter.validate(value);
        }
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

    private record Source(Identifier vertexId, Identifier fragmentId, String vertex, String fragment, ResourceProvider resources,
                          Map<Identifier, CachedIncludeSource> includes) implements ShaderSource {
        Source(Identifier vertexId, Identifier fragmentId, String vertex, String fragment, ResourceProvider resources) {
            this(vertexId, fragmentId, vertex, fragment, resources, new HashMap<>());
        }

        @Override
        public @Nullable String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return vertex;
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
