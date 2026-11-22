package lib.kasuga.rendering.batch;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** An ordered group of indexed draws that share pipeline, bindings and render state. */
public final class IndexedDrawBatch {
    private static final int COMMAND_BYTES = RenderPass.INDIRECT_INDEXED_DRAW_SIZE;
    private final @Nullable GpuBufferSlice commands;
    private final List<Draw> direct;
    private final int drawCount;
    private final int maxIndirectDrawCount;

    private IndexedDrawBatch(@Nullable GpuBufferSlice commands, List<Draw> direct, int drawCount, int maxIndirectDrawCount) {
        this.commands = commands;
        this.direct = direct;
        this.drawCount = drawCount;
        this.maxIndirectDrawCount = maxIndirectDrawCount;
    }

    /**
     * Prepare on the render thread with an encoder from the active Minecraft device,
     * before opening the render pass. Use the batch on that encoder before submission.
     * The encoder owns the temporary storage. A singleton or unsupported device uses direct draws.
     * A portable shader must be independent of the per-call draw ID, which resets for each submission.
     */
    public static IndexedDrawBatch prepare(CommandEncoder encoder, List<Draw> draws) {
        RenderSystem.assertOnRenderThread();
        DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
        DeviceFeatures features = device.features();
        if (!features.nonZeroFirstInstance()) {
            for (Draw draw : draws) {
                if (draw.firstInstance() != 0) {
                    throw new IllegalArgumentException("This graphics device requires firstInstance to be zero");
                }
            }
        }
        int maxIndirectDrawCount = device.limits().maxDrawIndirectDrawCount();
        int drawCount = draws.size();
        if (drawCount < 2 || !features.drawIndirect() || !features.multiDrawIndirect() || maxIndirectDrawCount < 2) {
            return new IndexedDrawBatch(null, List.copyOf(draws), drawCount, 0);
        }

        int bytes = Math.multiplyExact(drawCount, COMMAND_BYTES);
        GpuBufferSlice commands;
        try (GpuBufferSlice.MappedView mapped = encoder.transientMemory()
                .allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_INDIRECT_PARAMETERS)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            for (Draw draw : draws) {
                data.putInt(draw.indexCount()).putInt(draw.instanceCount()).putInt(draw.firstIndex())
                        .putInt(draw.vertexOffset()).putInt(draw.firstInstance());
            }
            commands = mapped.slice();
        }
        return new IndexedDrawBatch(commands, List.of(), drawCount, maxIndirectDrawCount);
    }

    public boolean usesIndirect() {
        return commands != null;
    }

    /** The caller binds the common pipeline, vertex and index buffers, and uniforms. */
    public void draw(RenderPass pass) {
        if (commands == null) {
            for (Draw draw : direct) {
                pass.drawIndexed(draw.indexCount(), draw.instanceCount(), draw.firstIndex(), draw.vertexOffset(), draw.firstInstance());
            }
            return;
        }
        for (int start = 0; start < drawCount;) {
            int count = Math.min(drawCount - start, maxIndirectDrawCount);
            pass.drawIndexedIndirect(commands.slice((long) start * COMMAND_BYTES, (long) count * COMMAND_BYTES), count);
            start += count;
        }
    }

    /** {@code firstIndex} is in index elements, and {@code vertexOffset} is signed. */
    public record Draw(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        public Draw {
            if (indexCount < 0 || instanceCount < 0 || firstIndex < 0 || firstInstance < 0) {
                throw new IllegalArgumentException("Indexed draw counts and first indices must be non-negative");
            }
        }
    }
}
