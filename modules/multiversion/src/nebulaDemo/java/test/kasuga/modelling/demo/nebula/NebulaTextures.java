package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Vector3f;

final class NebulaTextures extends NebulaTextureData implements AutoCloseable {
    private static final int BYTES = DATA_WIDTH * DATA_HEIGHT * 4 * Float.BYTES;
    private NebulaTexture noise;
    private NebulaTexture cell;
    private MappableRingBuffer data;
    private GpuBuffer frameData;

    private void prepare() {
        if (data != null) {
            data.rotate();
            return;
        }
        try {
            noise = NebulaTexture.rgba8("Nebula value noise", NOISE_SIZE, NOISE_SIZE, buildNoiseLutRgba());
            cell = NebulaTexture.rgba8("Nebula Worley atlas", CELL_ATLAS_WIDTH, CELL_ATLAS_HEIGHT, buildWorleyAtlasRgba());
            data = new MappableRingBuffer(() -> "Nebula frame data",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, BYTES);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    void uploadFrameData(float time, Vector3f eye, Vector3f cameraDirection, float[] wake,
                         float[] lights, int lightCount, float[] bolts, int segmentCount,
                         NebulaFlight.Pose craft, NebulaFlight.WarpState warp) {
        prepare();
        frameData = data.currentBuffer();
        try (GpuBufferSlice.MappedView map = frameData.slice().map(false, true)) {
            writeFrame(map.data().asFloatBuffer(), time, eye, cameraDirection, wake, lights, lightCount, bolts,
                    segmentCount, craft, warp);
        }
    }

    void bind(RenderPass pass) {
        pass.setUniform("NoiseSampler", noise.view(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
        pass.setUniform("CellSampler", cell.view(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setUniform("DataSampler", frameData);
    }

    @Override public void close() {
        if (noise != null) noise.close();
        if (cell != null) cell.close();
        if (data != null) data.close();
        noise = cell = null;
        data = null;
        frameData = null;
    }
}
