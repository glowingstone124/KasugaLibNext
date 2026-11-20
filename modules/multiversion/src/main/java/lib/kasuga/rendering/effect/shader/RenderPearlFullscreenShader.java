package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.RenderPearlGlslBackend;
import lib.kasuga.shader.backend.Std140UniformBlock;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        Identifier id = Identifier.parse(program.id());
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(id.withPrefix("pipeline/generated/"))
                .withVertexShader(VERTEX)
                .withFragmentShader(id)
                .withBindGroupLayout(RenderPearlShaderSupport.bindings(uniforms, generated.samplers()))
                .withColorTargetState(output)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        Identifier vertexPath = ShaderType.VERTEX.idConverter().idToFile(VERTEX);
        String vertex;
        try (InputStream input = resources.open(vertexPath)) {
            vertex = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        CompiledRenderPipeline compiled = RenderPearlShaderSupport.compile(pipeline, VERTEX, vertex, id,
                generated.fragmentSource(), resources);
        return new RenderPearlFullscreenShader(program, pipeline, compiled, uniforms);
    }

    /** Uniform overrides use column-major matrices and one element per scalar array entry. */
    public void draw(RenderTarget output, boolean clear, Map<String, double[]> values, Consumer<RenderPass> samplers) {
        RenderSystem.assertOnRenderThread();
        if (compiled.isClosed()) throw new IllegalStateException("Shader is closed: " + program.id());
        GpuBufferSlice parameters = RenderPearlShaderSupport.upload(program, uniforms, values);
        PostProcessContext.fullscreen(output, pipeline, compiled, clear, pass -> {
            if (parameters != null) pass.setUniform(RenderPearlGlslBackend.PARAMETERS, parameters);
            samplers.accept(pass);
        });
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (!compiled.isClosed()) compiled.close();
    }

}
