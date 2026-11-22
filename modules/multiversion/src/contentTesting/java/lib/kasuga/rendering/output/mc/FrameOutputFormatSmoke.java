package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import org.joml.Vector4f;

import java.util.concurrent.CompletableFuture;

final class FrameOutputFormatSmoke {
    private FrameOutputFormatSmoke() {}

    static CompletableFuture<Void> verify() throws Exception {
        String id = "kasuga:frame-output-format-smoke";
        TextureTarget source = new TextureTarget("Frame output test source", 8, 10, GpuFormat.RGBA8_UNORM, null);
        TextureTarget halfFloat = new TextureTarget("Frame output half float", 12, 14, GpuFormat.RGBA16_FLOAT, null);
        TextureTarget fullFloat = new TextureTarget("Frame output full float", 12, 14, GpuFormat.RGBA32_FLOAT, null);
        try (FrameOutputRouter<GpuTextureView>.Registration custom = MinecraftFrameOutputs.open(id, FrameOutputMode.MIRROR, frame -> {})) {
            clear(source, new Vector4f(0, 0, 0, 1));
            require(MinecraftFrameOutputs.publish(id, source).delivered() == 1, "Custom view was not delivered");
            OutputFrame<GpuTextureView> first = custom.latest().orElseThrow();
            source.resize(12, 14);
            clear(source, new Vector4f(0, 0, 0, 1));
            require(MinecraftFrameOutputs.publish(id, source).delivered() == 1, "Resized view was not delivered");
            OutputFrame<GpuTextureView> resized = custom.latest().orElseThrow();
            require(resized.width() == 12 && resized.height() == 14 && resized.frameNumber() == first.frameNumber() + 1
                            && resized.resource() != first.resource() && first.resource().isClosed(),
                    "Resizing did not retire the old custom view snapshot");
            clear(halfFloat, new Vector4f(0, 0, 0, 1));
            require(MinecraftFrameOutputs.publish(id, halfFloat).delivered() == 1, "Half-float view was not delivered");
            GpuTextureView halfSnapshot = custom.latest().orElseThrow().resource();
            require(resized.resource().isClosed() && halfSnapshot.texture().getFormat() == GpuFormat.RGBA16_FLOAT,
                    "The same-size format switch did not replace the previous snapshot");
            clear(fullFloat, new Vector4f(2.5f, .25f, .75f, 1f));
            require(MinecraftFrameOutputs.publish(id, fullFloat).delivered() == 1, "Full-float view was not delivered");
            GpuTextureView hdr = custom.latest().orElseThrow().resource();
            require(halfSnapshot.isClosed() && hdr.texture().getFormat() == GpuFormat.RGBA32_FLOAT,
                    "The frame output did not preserve full-float storage");
            return readHdrPixel(hdr);
        } finally {
            source.destroyBuffers();
            halfFloat.destroyBuffers();
            fullFloat.destroyBuffers();
        }
    }

    private static CompletableFuture<Void> readHdrPixel(GpuTextureView source) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Frame output HDR pixel",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source.texture(), buffer, 0, () -> {
                try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                    require(mapped.data().getFloat(0) == 2.5f && mapped.data().getFloat(4) == .25f
                                    && mapped.data().getFloat(8) == .75f && mapped.data().getFloat(12) == 1f,
                            "The HDR snapshot changed the source pixel");
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                    return;
                }
                result.complete(null);
            }, 0, 0, 0, 1, 1);
        } catch (RuntimeException error) {
            buffer.close();
            throw error;
        }
        return result;
    }

    private static void clear(TextureTarget target, Vector4f color) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), color);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
