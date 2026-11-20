package lib.kasuga.rendering.effect.particle.instance.renderpearl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.particle.ParticleBatchRenderer;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Draws untextured, tinted instances in the current native world pass. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlParticleBatchRenderer implements ParticleBatchRenderer.Prepared {
    private static final Identifier SHADER = id("core/particle/instance");
    private static final int INSTANCE_BYTES = 5 * 4 * Float.BYTES;
    private static final String INSTANCE_DATA = "ParticleInstances";
    private static final String VIEW = "ParticleView";
    private static final Map<ParticleInstanceMesh.Topology, Pipelines> PIPELINES = new EnumMap<>(ParticleInstanceMesh.Topology.class);

    static {
        for (ParticleInstanceMesh.Topology topology : ParticleInstanceMesh.Topology.values()) {
            PrimitiveTopology nativeTopology = switch (topology) {
                case LINES -> PrimitiveTopology.LINES;
                case TRIANGLES -> PrimitiveTopology.TRIANGLES;
                case TRIANGLE_STRIP -> PrimitiveTopology.TRIANGLE_STRIP;
            };
            String name = topology.name().toLowerCase(java.util.Locale.ROOT);
            RenderPipeline.Snippet base = RenderPipeline.builder()
                    .withVertexShader(SHADER).withFragmentShader(SHADER)
                    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform(VIEW, UniformType.UNIFORM_BUFFER)
                            .withUniform(INSTANCE_DATA, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build())
                    .withVertexBinding(0, DefaultVertexFormat.POSITION)
                    .withPrimitiveTopology(nativeTopology)
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .withCull(false)
                    .buildSnippet();
            RenderPipeline alpha = RenderPipeline.builder(base).withLocation(id("pipeline/particle/" + name))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
            OitPipelineSet oit = OitPipelineSet.builder(id("kasuga_particle_" + name), RenderPipeline.builder(base)).build();
            PIPELINES.put(topology, new Pipelines(alpha, oit));
        }
    }

    private final ParticleInstanceMesh mesh;
    private final Matrix4f model = new Matrix4f();
    private final Vector4f color = new Vector4f();
    private GpuBuffer vertices;
    private MappableRingBuffer instanceRing;
    private GpuBuffer instances;
    private GpuBufferSlice view;

    public RenderPearlParticleBatchRenderer(ParticleInstanceMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        for (Pipelines pipelines : PIPELINES.values()) {
            event.registerPipeline(pipelines.alpha());
            event.registerPipeline(pipelines.oit().depthBoundsPipeline());
            event.registerPipeline(pipelines.oit().transmittancePipeline());
            event.registerPipeline(pipelines.oit().accumulatePipeline());
        }
    }

    @Override
    public void prepare(ParticleInstanceBuffer batch, Vec3 eye, Matrix4fc viewRotation) {
        RenderSystem.assertOnRenderThread();
        int bytes = Math.multiplyExact(batch.size(), INSTANCE_BYTES);
        if (vertices == null) uploadMesh();
        if (instanceRing == null || instanceRing.size() < bytes) {
            int oldCapacity = instanceRing == null ? 0 : instanceRing.size();
            int capacity = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(bytes, Math.max(256L * INSTANCE_BYTES, 2L * oldCapacity))));
            capacity = capacity / INSTANCE_BYTES * INSTANCE_BYTES;
            MappableRingBuffer replacement = new MappableRingBuffer(() -> "Kasuga particle instances",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, capacity);
            if (instanceRing != null) instanceRing.close();
            instanceRing = replacement;
        } else {
            // The previous draw (including all its OIT stages) precedes this fence.
            instanceRing.rotate();
        }
        instances = instanceRing.currentBuffer();
        try (GpuBufferSlice.MappedView mapped = instances.slice(0, bytes).map(false, true)) {
            ByteBuffer data = mapped.data();
            int offset = 0;
            for (int index = 0; index < batch.size(); index++) {
                batch.matrix(index, model);
                model.m30((float) (model.m30() - eye.x));
                model.m31((float) (model.m31() - eye.y));
                model.m32((float) (model.m32() - eye.z));
                model.get(offset, data);
                batch.color(index, color).get(offset + 64, data);
                offset += INSTANCE_BYTES;
            }
        }
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            viewRotation.get(0, mapped.data());
            view = mapped.slice().slice(0, 64);
        }
    }

    private void uploadMesh() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(Math.multiplyExact(mesh.positionComponentCount(), Float.BYTES));
            mesh.writePositions(data.asFloatBuffer());
            vertices = RenderSystem.getDevice().createBuffer(() -> "Kasuga particle base mesh", GpuBuffer.USAGE_VERTEX, data);
        }
    }

    @Override
    public void render(ParticleInstanceBuffer batch, WorldRenderPipelineContext context) {
        Pipelines pipelines = PIPELINES.get(mesh.topology());
        if (context.renderPass() == null) {
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Kasuga particle instances")
                    .withColorAttachment(target.getColorTextureView()).withDepthAttachment(target.getDepthTextureView()).build();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
                draw(pass, pipelines.alpha(), batch.size());
            }
            return;
        }
        RenderPipeline pipeline = context.oitStage() == null ? pipelines.alpha() : pipelines.oit().getPipeline(context.oitStage());
        draw(context.renderPass(), pipeline, batch.size());
    }

    private void draw(RenderPass pass, RenderPipeline pipeline, int count) {
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform(VIEW, view);
        pass.setUniform(INSTANCE_DATA, instances);
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(mesh.vertexCount(), count, 0, 0);
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (vertices != null) vertices.close();
        if (instanceRing != null) instanceRing.close();
        vertices = null;
        instanceRing = null;
        instances = null;
        view = null;
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }

    private record Pipelines(RenderPipeline alpha, OitPipelineSet oit) {}
}
