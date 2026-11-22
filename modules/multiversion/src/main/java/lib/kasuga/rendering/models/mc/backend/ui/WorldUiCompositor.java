package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.batch.IndexedDrawBatch;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Composites premultiplied panel textures against the existing world depth. */
final class WorldUiCompositor implements AutoCloseable {
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "pipeline/world_ui"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private final StagedVertexBuffer vertices = new StagedVertexBuffer(() -> "Kasuga world UI quads", 4096);

    void render(RenderTarget target, List<Quad> panels) {
        if (panels.isEmpty()) return;
        List<Draw> draws = new ArrayList<>(panels.size());
        try {
            for (Quad panel : panels) {
                StagedVertexBuffer.Draw draw = vertices.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS);
                VertexConsumer out = vertices.getVertexBuilder(draw);
                ScreenRectangle r = panel.area();
                Matrix4fc pose = panel.view();
                out.addVertex(pose, r.left(), r.top(), 0).setUv(0, 1).setColor(-1);
                out.addVertex(pose, r.left(), r.bottom(), 0).setUv(0, 0).setColor(-1);
                out.addVertex(pose, r.right(), r.bottom(), 0).setUv(1, 0).setColor(-1);
                out.addVertex(pose, r.right(), r.top(), 0).setUv(1, 1).setColor(-1);
                vertices.requestIndexCount(draw);
                draws.add(new Draw(draw, panel.texture()));
            }
            vertices.upload();
            RenderSystem.resizeAllAutoStorageIndexBuffers();
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            List<DrawBatch> batches = prepareBatches(encoder, draws);
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Kasuga world UI", target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                pass.setUniform("DynamicTransforms", transform);
                for (DrawBatch batch : batches) {
                    StagedVertexBuffer.ExecuteInfo info = batch.first().info();
                    pass.setUniform("Sampler0", batch.first().texture(), sampler);
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    @Nullable IndexedDrawBatch commands = batch.commands();
                    if (commands == null) {
                        pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                    } else {
                        commands.draw(pass);
                    }
                }
            }
        } finally {
            vertices.endDraw();
            vertices.endFrame();
        }
    }

    private List<DrawBatch> prepareBatches(CommandEncoder encoder, List<Draw> draws) {
        // Only group adjacent draws so translucent panels still composite back to front.
        List<DrawBatch> batches = new ArrayList<>();
        List<IndexedDrawBatch.Draw> commands = new ArrayList<>();
        DrawBindings first = null;
        for (Draw draw : draws) {
            StagedVertexBuffer.ExecuteInfo info = vertices.getExecuteInfo(draw.mesh());
            if (info == null) continue;
            if (first == null || !first.matches(draw.texture(), info)) {
                if (first != null) batches.add(first.prepare(encoder, commands));
                commands.clear();
                first = new DrawBindings(draw.texture(), info);
                continue;
            }
            if (commands.isEmpty()) commands.add(indexedDraw(first.info()));
            commands.add(indexedDraw(info));
        }
        if (first != null) batches.add(first.prepare(encoder, commands));
        return batches;
    }

    private static IndexedDrawBatch.Draw indexedDraw(StagedVertexBuffer.ExecuteInfo info) {
        return new IndexedDrawBatch.Draw(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
    }

    @Override public void close() { vertices.close(); }

    record Quad(ScreenRectangle area, Matrix4fc view, GpuTextureView texture) {}
    private record Draw(StagedVertexBuffer.Draw mesh, GpuTextureView texture) {}

    private record DrawBindings(GpuTextureView texture, StagedVertexBuffer.ExecuteInfo info) {
        boolean matches(GpuTextureView otherTexture, StagedVertexBuffer.ExecuteInfo other) {
            return texture == otherTexture && info.vertexBuffer() == other.vertexBuffer()
                    && info.indexBuffer() == other.indexBuffer() && info.indexType() == other.indexType();
        }

        DrawBatch prepare(CommandEncoder encoder, List<IndexedDrawBatch.Draw> draws) {
            return new DrawBatch(this, draws.isEmpty() ? null : IndexedDrawBatch.prepare(encoder, draws));
        }
    }

    private record DrawBatch(DrawBindings first, @Nullable IndexedDrawBatch commands) {}
}
