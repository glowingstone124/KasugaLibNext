package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.RenderPearlGlslBackend;
import lib.kasuga.shader.backend.Std140UniformBlock;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/** Owns a Shader DSL vertex/fragment pipeline on the Minecraft graphics device. */
public final class RenderPearlGraphicsShader implements AutoCloseable {
    private final ShaderProgram program;
    private final RenderPipeline pipeline;
    private final CompiledRenderPipeline compiled;
    private final Std140UniformBlock uniforms;

    private RenderPearlGraphicsShader(ShaderProgram program, RenderPipeline pipeline, CompiledRenderPipeline compiled,
                                      Std140UniformBlock uniforms) {
        this.program = program;
        this.pipeline = pipeline;
        this.compiled = compiled;
        this.uniforms = uniforms;
    }

    /** The caller configures vertex bindings, topology and attachment state on a fresh builder. */
    public static RenderPearlGraphicsShader compile(ShaderProgram program, ResourceProvider resources,
                                                     RenderPipeline.Builder builder) throws IOException {
        RenderSystem.assertOnRenderThread();
        RenderPearlGlslBackend.Graphics generated = RenderPearlGlslBackend.emitGraphics(program);
        Std140UniformBlock uniforms = new Std140UniformBlock(generated.uniforms());
        Identifier id = Identifier.parse(program.id());
        RenderPipeline pipeline = builder.withLocation(id.withPrefix("pipeline/generated/"))
                .withVertexShader(id)
                .withFragmentShader(id)
                .withBindGroupLayout(RenderPearlShaderSupport.bindings(uniforms, generated.samplers()))
                .build();
        CompiledRenderPipeline compiled = RenderPearlShaderSupport.compile(pipeline, id, generated.vertexSource(), id,
                generated.fragmentSource(), resources);
        return new RenderPearlGraphicsShader(program, pipeline, compiled, uniforms);
    }

    /** Uploads the frame's parameters before starting a render pass. */
    public Prepared prepare(Map<String, double[]> values) {
        requireOpen();
        return new Prepared(RenderPearlShaderSupport.upload(program, uniforms, values));
    }

    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (compiled.isClosed()) throw new IllegalStateException("Shader is closed: " + program.id());
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (!compiled.isClosed()) compiled.close();
    }

    public final class Prepared {
        private final @Nullable GpuBufferSlice parameters;

        private Prepared(@Nullable GpuBufferSlice parameters) {
            this.parameters = parameters;
        }

        /** Binds this frame's pipeline and parameters. The caller binds samplers and geometry before drawing. */
        public void bind(RenderPass pass) {
            requireOpen();
            pass.setPipeline(compiled);
            bindParameters(pass);
        }

        public void render(RenderTarget output, boolean clearColor, boolean clearDepth, Consumer<RenderPass> commands) {
            requireOpen();
            PostProcessContext.render(output, pipeline, compiled, clearColor, clearDepth, pass -> {
                bindParameters(pass);
                commands.accept(pass);
            });
        }

        private void bindParameters(RenderPass pass) {
            if (parameters != null) pass.setUniform(RenderPearlGlslBackend.PARAMETERS, parameters);
        }
    }
}
