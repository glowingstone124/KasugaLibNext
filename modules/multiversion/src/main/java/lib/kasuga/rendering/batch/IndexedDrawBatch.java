package lib.kasuga.rendering.batch;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An ordered group of indexed draws that share pipeline, bindings and render state. */
public final class IndexedDrawBatch {
    static final int COMMAND_BYTES = RenderPass.INDIRECT_INDEXED_DRAW_SIZE;
    private final @Nullable GpuBufferSlice commands;
    private final List<Draw> direct;
    private final int drawCount;
    private final int maxIndirectDrawCount;
    private final boolean gpuVisibility;
    private final boolean indirectCount;

    private IndexedDrawBatch(@Nullable GpuBufferSlice commands, List<Draw> direct, int drawCount, int maxIndirectDrawCount,
                             boolean gpuVisibility, boolean indirectCount) {
        this.commands = commands;
        this.direct = direct;
        this.drawCount = drawCount;
        this.maxIndirectDrawCount = maxIndirectDrawCount;
        this.gpuVisibility = gpuVisibility;
        this.indirectCount = indirectCount;
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
            return new IndexedDrawBatch(null, List.copyOf(draws), drawCount, 0, false, false);
        }

        int bytes = Math.multiplyExact(drawCount, COMMAND_BYTES);
        GpuBufferSlice commands;
        try (GpuBufferSlice.MappedView mapped = encoder.transientMemory()
                .allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_INDIRECT_PARAMETERS)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            for (Draw draw : draws) {
                draw.write(data);
            }
            commands = mapped.slice();
        }
        return new IndexedDrawBatch(commands, List.of(), drawCount, maxIndirectDrawCount, false, false);
    }

    /**
     * Cull conservative bounding spheres against up to six normalized inward planes. Coordinates
     * must be in the same space. Use camera-relative coordinates for large worlds. On an encoder
     * without supported compute, cull on the CPU and select the regular indexed draw path.
     * Prepare before opening the render pass, and draw before the encoder is submitted.
     * Vulkan can compact visible commands and read the draw count on the GPU. With
     * {@code -Dkasuga.disableVulkanDrawIndirectCount=true} it uses fixed-count compute instead.
     * {@code -Dkasuga.disableVulkanCompute=true} selects CPU visibility.
     * Surviving draws keep their original order. Shaders must be independent of the draw ID.
     */
    public static IndexedDrawBatch prepareVisible(CommandEncoder encoder, List<Candidate> candidates,
                                                  List<? extends Vector4fc> planes) {
        RenderSystem.assertOnRenderThread();
        if (planes.size() > 6) throw new IllegalArgumentException("Expected up to six clip planes");
        for (Vector4fc plane : planes) {
            double lengthSquared = (double) plane.x() * plane.x() + (double) plane.y() * plane.y()
                    + (double) plane.z() * plane.z();
            if (!Double.isFinite(lengthSquared) || Math.abs(lengthSquared - 1.0) > .001 || !Float.isFinite(plane.w())) {
                throw new IllegalArgumentException("Clip planes must be finite and normalized");
            }
        }
        DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
        DeviceFeatures features = device.features();
        int maxDraws = device.limits().maxDrawIndirectDrawCount();
        if (candidates.size() > 1 && !planes.isEmpty() && maxDraws > 1
                && features.drawIndirect() && features.multiDrawIndirect() && features.nonZeroFirstInstance()) {
            VulkanIndexedVisibility.Prepared gpu = VulkanIndexedVisibility.prepare(encoder, candidates, planes, maxDraws);
            if (gpu != null) {
                return new IndexedDrawBatch(gpu.commands(), List.of(), candidates.size(), gpu.drawsPerSubmission(), true, gpu.counted());
            }
        }
        List<Draw> visible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.isVisible(planes)) visible.add(candidate.draw());
        }
        return prepare(encoder, visible);
    }

    public boolean usesGpuVisibility() {
        return gpuVisibility;
    }

    /** Whether this batch uses GPU-generated compacted commands and draw counts. */
    public boolean usesIndirectCount() {
        return indirectCount;
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

    /** An indexed draw and its conservative bounding sphere. */
    public record Candidate(Draw draw, float x, float y, float z, float radius) {
        public Candidate {
            Objects.requireNonNull(draw, "draw");
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(radius) || radius < 0) {
                throw new IllegalArgumentException("Expected a finite bounding sphere with a non-negative radius");
            }
        }

        private boolean isVisible(List<? extends Vector4fc> planes) {
            for (Vector4fc plane : planes) {
                float distance = plane.x() * x + plane.y() * y + plane.z() * z + plane.w();
                if (distance < -radius - .0001f) return false;
            }
            return true;
        }
    }

    /** {@code firstIndex} is in index elements, and {@code vertexOffset} is signed. */
    public record Draw(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        public Draw {
            if (indexCount < 0 || instanceCount < 0 || firstIndex < 0 || firstInstance < 0) {
                throw new IllegalArgumentException("Indexed draw counts and first indices must be non-negative");
            }
        }

        void write(ByteBuffer data) {
            data.putInt(indexCount).putInt(instanceCount).putInt(firstIndex).putInt(vertexOffset).putInt(firstInstance);
        }
    }
}
