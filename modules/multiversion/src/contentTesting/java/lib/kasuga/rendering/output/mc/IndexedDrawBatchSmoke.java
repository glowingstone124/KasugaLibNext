package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.batch.IndexedDrawBatch;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class IndexedDrawBatchSmoke {
    private static final int SIZE = 32;
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/batch/indexed_draw_smoke");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "pipeline/indexed_draw_smoke"))
            .withVertexShader(SHADER).withFragmentShader(SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT).withCull(false).build();

    private IndexedDrawBatchSmoke() {}

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    static CompletableFuture<Void> verify() {
        return CompletableFuture.allOf(run(Mode.DRAW), run(Mode.VISIBILITY), run(Mode.FORCED_CPU));
    }

    private static CompletableFuture<Void> run(Mode mode) {
        TextureTarget target = new TextureTarget("Kasuga indexed draw smoke", SIZE, SIZE, GpuFormat.RGBA8_UNORM, null);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
        List<IndexedDrawBatch.Draw> draws = List.of(
                new IndexedDrawBatch.Draw(6, 1, 0, 0, 0),
                new IndexedDrawBatch.Draw(6, 1, 6, 1, 0));
        CompletableFuture<Void> result = new CompletableFuture<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer vertices = stack.malloc(8 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
            quad(vertices, -.9f, .1f, 255, 0);
            quad(vertices, -.1f, .9f, 0, 255);
            vertices.flip();
            ByteBuffer indices = stack.malloc(12 * Short.BYTES);
            for (int index : new int[]{0, 1, 2, 2, 3, 0, 3, 4, 5, 5, 6, 3}) indices.putShort((short) index);
            indices.flip();
            try (GpuBuffer vertex = RenderSystem.getDevice().createBuffer(() -> "Indexed draw smoke vertices", GpuBuffer.USAGE_VERTEX, vertices);
                 GpuBuffer index = RenderSystem.getDevice().createBuffer(() -> "Indexed draw smoke indices", GpuBuffer.USAGE_INDEX, indices)) {
                IndexedDrawBatch batch = prepare(encoder, draws, mode);
                boolean supportsMultiDraw = device.features().drawIndirect() && device.features().multiDrawIndirect()
                        && device.limits().maxDrawIndirectDrawCount() > 1;
                require(batch.usesIndirect() == supportsMultiDraw, "Indexed draw selected the wrong submission path");
                if (mode != Mode.VISIBILITY || !"Vulkan".equals(device.backendName())
                        || Boolean.getBoolean("kasuga.disableVulkanCompute")) {
                    require(!batch.usesGpuVisibility(), "Indexed draw selected compute when it should use the CPU");
                }
                try (RenderPass pass = encoder.createRenderPass(() -> "Indexed draw smoke", target.getColorTextureView(),
                        Optional.of(new Vector4f(0, 0, 0, 1)))) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                    pass.setVertexBuffer(0, vertex.slice());
                    pass.setIndexBuffer(index, IndexType.SHORT);
                    batch.draw(pass);
                }
                read(encoder, target, device.backendName(), batch, mode, result);
            }
        } finally {
            target.destroyBuffers();
        }
        return result;
    }

    private static IndexedDrawBatch prepare(CommandEncoder encoder, List<IndexedDrawBatch.Draw> draws, Mode mode) {
        if (mode == Mode.DRAW) return IndexedDrawBatch.prepare(encoder, draws);
        IndexedDrawBatch.Candidate hidden = new IndexedDrawBatch.Candidate(draws.getFirst(), -2f, 0, 0, .5f);
        List<IndexedDrawBatch.Candidate> candidates = new ArrayList<>();
        candidates.add(new IndexedDrawBatch.Candidate(draws.getFirst(), -.5f, 0, 0, .5f));
        // An escaped hidden command would overpaint the green quad at the overlap pixel.
        for (int i = 0; i < 63; i++) candidates.add(hidden);
        candidates.add(new IndexedDrawBatch.Candidate(draws.getLast(), .5f, 0, 0, .5f));
        candidates.add(hidden);
        List<Vector4f> planes = List.of(new Vector4f(1, 0, 0, 0));
        if (mode != Mode.FORCED_CPU) return IndexedDrawBatch.prepareVisible(encoder, candidates, planes);
        String key = "kasuga.disableVulkanCompute";
        String original = System.setProperty(key, "true");
        try {
            return IndexedDrawBatch.prepareVisible(encoder, candidates, planes);
        } finally {
            if (original == null) System.clearProperty(key);
            else System.setProperty(key, original);
        }
    }

    private static void quad(ByteBuffer buffer, float left, float right, int red, int green) {
        vertex(buffer, left, -.75f, red, green);
        vertex(buffer, right, -.75f, red, green);
        vertex(buffer, right, .75f, red, green);
        vertex(buffer, left, .75f, red, green);
    }

    private static void vertex(ByteBuffer buffer, float x, float y, int red, int green) {
        buffer.putFloat(x).putFloat(y).putFloat(.5f).put((byte) red).put((byte) green).put((byte) 0).put((byte) 255);
    }

    private static void read(CommandEncoder encoder, TextureTarget target, String backend, IndexedDrawBatch batch,
                             Mode mode, CompletableFuture<Void> result) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Indexed draw smoke pixels",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, SIZE * SIZE * 4);
        try {
            encoder.copyTextureToBuffer(target.getColorTexture(), buffer, 0, () -> {
                try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                    checkPixel(mapped.data(), 8, 16, 255, 0);
                    checkPixel(mapped.data(), 24, 16, 0, 255);
                    checkPixel(mapped.data(), 16, 16, 0, 255); // The second draw covers the first in the overlap.
                    checkPixel(mapped.data(), 16, 1, 0, 0);
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                    return;
                }
                if (mode == Mode.DRAW) {
                    LogUtils.getLogger().info("KASUGA_INDEXED_DRAW_PASS backend={} path={} first-index,base-vertex,order,pixels",
                            backend, batch.usesIndirect() ? "indirect" : "direct");
                } else {
                    LogUtils.getLogger().info("KASUGA_INDEXED_VISIBILITY_PASS backend={} mode={} path={} culled,boundary,order,pixels",
                            backend, mode, batch.usesGpuVisibility() ? "gpu" : "cpu");
                }
                result.complete(null);
            }, 0);
        } catch (RuntimeException error) {
            buffer.close();
            throw error;
        }
    }

    private static void checkPixel(ByteBuffer bytes, int x, int y, int red, int green) {
        int offset = (y * SIZE + x) * 4;
        int actualRed = bytes.get(offset) & 255;
        int actualGreen = bytes.get(offset + 1) & 255;
        require(actualRed == red && actualGreen == green && bytes.get(offset + 2) == 0 && (bytes.get(offset + 3) & 255) == 255,
                "Indexed draw pixel at " + x + "," + y + " has red=" + actualRed + " green=" + actualGreen);
    }

    private enum Mode { DRAW, VISIBILITY, FORCED_CPU }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
