package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.uml.backend.DepthBias;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Batches opaque model submissions inside Minecraft's native solid feature phase. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlModelBatcher implements FeatureRenderer<RenderPearlModelBatcher.Submit> {
    static final FeatureRendererType<Submit> TYPE = FeatureRendererType.create("Kasuga world model");
    private static final String INSTANCE_UNIFORM = "KasugaModelInstances";
    private static final int INSTANCE_BYTES = 128;
    private static final int MAX_INSTANCES = 64;
    private static final Identifier SHADER = id("core/model/instanced");
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = Map.of(
            RenderPipelines.ENTITY_SOLID, instanced(RenderPipelines.ENTITY_SOLID, "solid"),
            RenderPipelines.ENTITY_CUTOUT_CULL, instanced(RenderPipelines.ENTITY_CUTOUT_CULL, "cutout_cull"),
            RenderPipelines.ENTITY_CUTOUT, instanced(RenderPipelines.ENTITY_CUTOUT, "cutout")
    );
    private static final Map<BiasedPipeline, RenderPipeline> BIASED_PIPELINES = new HashMap<>();

    private final List<List<Draw>> groups = new ArrayList<>();
    private static FrameStats lastFrame = new FrameStats(0, 0, 0);
    private int drawsExecuted;
    private int instancesDrawn;
    private int verticesUploaded;

    @SubscribeEvent
    public static void registerRenderer(RegisterFeatureRenderersEvent event) {
        event.register(TYPE, new RenderPearlModelBatcher());
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        for (RenderPipeline pipeline : PIPELINES.values()) event.registerPipeline(pipeline);
    }

    @SubscribeEvent
    public static void onShaderReload(ClientResourceReloadFinishEvent event) {
        BIASED_PIPELINES.clear();
    }

    static boolean supports(RenderType layer) {
        return PIPELINES.containsKey(layer.pipeline());
    }

    private static RenderPipeline instanced(RenderPipeline base, String name) {
        return base.toBuilder()
                .withLocation(id("pipeline/model/instanced_" + name))
                .withVertexShader(SHADER)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(INSTANCE_UNIFORM, UniformType.UNIFORM_BUFFER).build())
                .build();
    }

    @Override
    public void prepareGroup(FeatureFrameContext context, List<Submit> submits, boolean strictlyOrdered) {
        List<Draw> draws = new ArrayList<>();
        groups.add(draws);
        for (int start = 0; start < submits.size();) {
            Submit first = submits.get(start);
            RenderType layer = first.batch().layer();
            DepthBias bias = first.depthBias();
            int end = start + 1;
            while (end < submits.size() && submits.get(end).batch().layer() == layer
                    && submits.get(end).depthBias().equals(bias)) end++;
            prepareLayer(context.stagedVertexBuffer(), layer, bias, submits.subList(start, end), draws);
            start = end;
        }
    }

    private static void prepareLayer(StagedVertexBuffer buffers, RenderType layer, DepthBias bias, List<Submit> submits,
                                     List<Draw> draws) {
        PreparedRenderType prepared = layer.prepare();
        if (bias.enabled()) {
            prepared = new PreparedRenderType(prepared.name(), depthBiased(prepared.pipeline(), bias),
                    prepared.oitPipelineSet(), prepared.dynamicTransforms(), prepared.scissorState(), prepared.textures());
        }
        RenderPipeline instancePipeline = depthBiased(PIPELINES.get(layer.pipeline()), bias);

        int fallbackStart = 0;
        for (int start = 0; start + 1 < submits.size();) {
            Submit prototype = submits.get(start);
            int end = start + 1;
            while (end < submits.size() && prototype.canShareGeometry(submits.get(end))) end++;
            if (end - start > 1) {
                prepareFallback(buffers, layer, prepared, submits.subList(fallbackStart, start), draws);
                StagedVertexBuffer.Draw geometry = buffers.appendDraw(layer.format(), layer.primitiveTopology());
                prototype.batch().submitShared(buffers.getVertexBuilder(geometry));
                for (int offset = start; offset < end; offset += MAX_INSTANCES) {
                    List<Submit> chunk = submits.subList(offset, Math.min(end, offset + MAX_INSTANCES));
                    draws.add(new Draw(geometry, prepared, instancePipeline, writeInstances(chunk), chunk.size(), offset == start));
                }
                fallbackStart = end;
            }
            start = end;
        }
        prepareFallback(buffers, layer, prepared, submits.subList(fallbackStart, submits.size()), draws);
    }

    private static RenderPipeline depthBiased(RenderPipeline base, DepthBias bias) {
        if (!bias.enabled()) return base;
        return BIASED_PIPELINES.computeIfAbsent(new BiasedPipeline(base, bias), key -> {
            DepthStencilState depth = base.getDepthStencilState();
            String suffix = Integer.toHexString(Float.floatToIntBits(bias.factor())) + "_"
                    + Integer.toHexString(Float.floatToIntBits(bias.units()));
            // Model depth bias uses conventional depth units. RenderPearl uses reverse Z.
            return base.toBuilder().withLocation(id(base.getLocation().getPath() + "/bias_" + suffix))
                    .withDepthStencilState(new DepthStencilState(depth.depthTest(), depth.writeDepth(),
                            depth.depthBiasScaleFactor() - bias.factor(), depth.depthBiasConstant() - bias.units(),
                            depth.stencilTest()))
                    .build();
        });
    }

    private static void prepareFallback(StagedVertexBuffer buffers, RenderType layer, PreparedRenderType prepared,
                                        List<Submit> submits, List<Draw> draws) {
        if (submits.isEmpty()) return;
        StagedVertexBuffer.Draw geometry = buffers.appendDraw(layer.format(), layer.primitiveTopology());
        VertexConsumer consumer = buffers.getVertexBuilder(geometry);
        for (Submit submit : submits) submit.batch().submit(submit.pose(), consumer);
        draws.add(new Draw(geometry, prepared, prepared.pipeline(), null, 1, true));
    }

    private static GpuBufferSlice writeInstances(List<Submit> submits) {
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(INSTANCE_BYTES * MAX_INSTANCES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data();
            int offset = 0;
            Matrix4f placement = new Matrix4f();
            Matrix3f normal = new Matrix3f();
            for (Submit submit : submits) {
                placement.set(submit.pose().pose());
                normal.set(submit.pose().normal());
                Transform rigidPose = submit.batch().rigidPose();
                if (rigidPose != null) {
                    placement.mul(rigidPose.transform());
                    normal.mul(rigidPose.normal());
                }
                placement.get(offset, data);
                writeColumn(data, offset + 64, normal.m00(), normal.m01(), normal.m02());
                writeColumn(data, offset + 80, normal.m10(), normal.m11(), normal.m12());
                writeColumn(data, offset + 96, normal.m20(), normal.m21(), normal.m22());
                int light = submit.batch().packedLight();
                writeColumn(data, offset + 112, light & 65535, light >>> 16, 0f);
                offset += INSTANCE_BYTES;
            }
            return mapped.slice();
        }
    }

    private static void writeColumn(ByteBuffer data, int offset, float x, float y, float z) {
        data.putFloat(offset, x);
        data.putFloat(offset + Float.BYTES, y);
        data.putFloat(offset + 2 * Float.BYTES, z);
        data.putFloat(offset + 3 * Float.BYTES, 0f);
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage stage, RenderPass pass,
                             int groupIndex, List<Submit> submits, boolean strictlyOrdered) {
        for (Draw draw : groups.get(groupIndex)) {
            StagedVertexBuffer.ExecuteInfo geometry = context.stagedVertexBuffer().getExecuteInfo(draw.geometry());
            if (geometry == null) continue;
            drawsExecuted++;
            if (draw.instances() == null) {
                verticesUploaded += geometry.indexCount() / 6 * 4;
                draw.layer().drawFromBuffer(geometry, pass);
                continue;
            }
            if (draw.firstUse()) verticesUploaded += geometry.indexCount() / 6 * 4;
            instancesDrawn += draw.count();
            pass.setPipeline(RenderSystem.getCompiledPipeline(draw.pipeline()));
            if (draw.layer().scissorState().enabled()) {
                ScissorState scissor = draw.layer().scissorState();
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", draw.layer().dynamicTransforms());
            pass.setUniform(INSTANCE_UNIFORM, draw.instances());
            for (PreparedRenderType.Texture texture : draw.layer().textures()) {
                pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
            }
            pass.setVertexBuffer(0, geometry.vertexBuffer().slice());
            pass.setIndexBuffer(geometry.indexBuffer(), geometry.indexType());
            pass.drawIndexed(geometry.indexCount(), draw.count(), geometry.firstIndex(), geometry.baseVertex(), 0);
        }
    }

    @Override
    public void finishExecute(FeatureFrameContext context) {
        groups.clear();
        if (drawsExecuted != 0) {
            lastFrame = new FrameStats(drawsExecuted, instancesDrawn, verticesUploaded);
        }
        drawsExecuted = 0;
        instancesDrawn = 0;
        verticesUploaded = 0;
    }

    static FrameStats lastFrame() { return lastFrame; }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path);
    }

    record Submit(Model model, RenderPearlModelGeometry.Batch batch, PoseStack.Pose pose,
                  DepthBias depthBias) implements BatchableSubmit {
        @Override public FeatureRendererType<Submit> featureType() { return TYPE; }
        @Override public Object batchKey() { return batch.layer(); }

        boolean canShareGeometry(Submit other) {
            return model == other.model && batch.corners().equals(other.batch.corners());
        }
    }

    private record Draw(StagedVertexBuffer.Draw geometry, PreparedRenderType layer, RenderPipeline pipeline,
                        @Nullable GpuBufferSlice instances, int count, boolean firstUse) {}

    private record BiasedPipeline(RenderPipeline pipeline, DepthBias depthBias) {}

    record FrameStats(int draws, int instancedModels, int geometryVertices) {}
}
